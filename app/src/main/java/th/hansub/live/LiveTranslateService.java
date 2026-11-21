package th.hansub.live;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.util.ArrayList;

/**
 * Source: microphone through the system recognizer. Does not capture other applications'
 * internal/protected audio. The recognizer itself may use network services in default mode.
 */
public class LiveTranslateService extends Service implements RecognitionListener {
    public static final String ACTION_START="th.hansub.live.START";
    public static final String ACTION_STOP="th.hansub.live.STOP";
    private static final String CHANNEL="hansub_live_mic";
    private static final int NOTIFICATION_ID=201;
    private final Handler main=new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private Translator direct, chineseEnglish, englishThai;
    private String mode=ModelCatalog.FAST;
    private boolean running,modelsReady;
    private int generation,retries;
    private long lastPartialAt;
    private String lastPartial="";
    private WindowManager windows;
    private LinearLayout overlay;
    private TextView zhView,thView;
    private WindowManager.LayoutParams params;

    @Override public IBinder onBind(Intent intent) {return null;}

    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if (intent!=null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_NOT_STICKY;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED
                || !Settings.canDrawOverlays(this)) {
            stopSelf();return START_NOT_STICKY;
        }
        try {
            NotificationManager manager=getSystemService(NotificationManager.class);
            if (manager!=null) manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL,"HanSub microphone",NotificationManager.IMPORTANCE_LOW));
            startForeground(NOTIFICATION_ID,notification());
        } catch (RuntimeException e) {
            stopSelf();return START_NOT_STICKY;
        }
        running=true;
        getSharedPreferences("hansub",MODE_PRIVATE).edit().putBoolean("running",true).apply();
        createOverlay();
        if (overlay==null) {stopSelf();return START_NOT_STICKY;}
        mode=ModelCatalog.normalizeMode(
                getSharedPreferences("hansub",MODE_PRIVATE).getString("translation_mode",ModelCatalog.FAST));
        caption("中文 → ไทย","กำลังเตรียมโมเดล…");
        prepareTranslation();
        return START_NOT_STICKY;
    }

    private Notification notification() {
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,LiveTranslateService.class)
                .setAction(ACTION_STOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("HanSub Live • กำลังฟังเสียงจีน")
                .setContentText("ใช้ไมโครโฟน • แตะเพื่อเปิดแอป")
                .setContentIntent(open)
                .addAction(R.drawable.ic_notification,"หยุด",stop)
                .setOngoing(true).setCategory(Notification.CATEGORY_SERVICE).build();
    }

    private Translator translator(String source,String target) {
        return Translation.getClient(new TranslatorOptions.Builder()
                .setSourceLanguage(source).setTargetLanguage(target).build());
    }

    private void prepareTranslation() {
        DownloadConditions conditions=new DownloadConditions.Builder().build();
        if (ModelCatalog.PIVOT.equals(mode)) {
            chineseEnglish=translator(TranslateLanguage.CHINESE,TranslateLanguage.ENGLISH);
            englishThai=translator(TranslateLanguage.ENGLISH,TranslateLanguage.THAI);
            chineseEnglish.downloadModelIfNeeded(conditions)
                .addOnSuccessListener(v->{
                    if (!running) return;
                    englishThai.downloadModelIfNeeded(conditions)
                        .addOnSuccessListener(v2->readyForSpeech())
                        .addOnFailureListener(this::modelFailed);
                }).addOnFailureListener(this::modelFailed);
        } else {
            direct=translator(TranslateLanguage.CHINESE,TranslateLanguage.THAI);
            direct.downloadModelIfNeeded(conditions)
                .addOnSuccessListener(v->readyForSpeech())
                .addOnFailureListener(this::modelFailed);
        }
    }

    private void modelFailed(Exception error) {
        if (!running) return;
        caption("MODEL ERROR","โมเดลแปลภาษาไม่พร้อม โปรดดาวน์โหลดใน Model Manager");
        main.postDelayed(this::stopSelf,4500);
    }

    private void readyForSpeech() {
        if (!running) return;
        modelsReady=true;
        caption("中文 → ไทย",ModelCatalog.PIVOT.equals(mode)
                ?"กำลังฟัง (โหมด Pivot)…":"กำลังฟัง (โหมด Fast)…");
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            failure("ไม่พบบริการรู้จำเสียงในอุปกรณ์");return;
        }
        try {
            String speech=getSharedPreferences("hansub",MODE_PRIVATE).getString(
                    "speech_mode",ModelCatalog.SPEECH_DEFAULT);
            if (ModelCatalog.isOfflineSpeech(speech)) {
                if (Build.VERSION.SDK_INT<31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                    failure("เครื่องนี้ไม่รองรับ On-device Speech โปรดเลือกโหมดปกติ");return;
                }
                recognizer=SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
            } else {
                recognizer=SpeechRecognizer.createSpeechRecognizer(this);
            }
            recognizer.setRecognitionListener(this);
            startListening();
        } catch (RuntimeException error) {
            failure("เปิดระบบเสียงไม่ได้: "+error.getClass().getSimpleName());
        }
    }

    private void failure(String message) {
        caption("SPEECH ERROR",message);
        main.postDelayed(this::stopSelf,5500);
    }

    private Intent recognizerIntent() {
        Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE,"zh-CN");
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,"zh-CN");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,1);
        return i;
    }

    private void startListening() {
        if (!running || !modelsReady || recognizer==null) return;
        try {recognizer.startListening(recognizerIntent());}
        catch (RuntimeException e) {restart(1400);}
    }

    private void restart(long delay) {
        if (running) main.postDelayed(this::startListening,delay);
    }

    private void speech(Bundle data,boolean finalResult) {
        if (!running || !modelsReady || data==null) return;
        ArrayList<String> guesses=data.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (guesses==null || guesses.isEmpty()) return;
        String chinese=guesses.get(0).trim();
        if (chinese.isEmpty()) return;
        long now=SystemClock.elapsedRealtime();
        if (!finalResult) {
            if (chinese.equals(lastPartial) || now-lastPartialAt<750) return;
            lastPartial=chinese;
            lastPartialAt=now;
        } else if (chinese.equals(lastPartial)) {
            // The partial result was already shown; avoid repeating exactly the same translation.
            return;
        }
        translate(chinese);
    }

    private void translate(String chinese) {
        if (!running) return;
        final int ticket=++generation;
        caption(chinese,"กำลังแปล…");
        if (ModelCatalog.PIVOT.equals(mode)) {
            if (chineseEnglish==null || englishThai==null) return;
            chineseEnglish.translate(chinese)
                .addOnSuccessListener(english->{
                    if (!running || ticket!=generation) return;
                    englishThai.translate(english)
                        .addOnSuccessListener(thai->showTranslation(ticket,chinese,thai))
                        .addOnFailureListener(e->translationFailed(ticket,chinese));
                }).addOnFailureListener(e->translationFailed(ticket,chinese));
        } else if (direct!=null) {
            direct.translate(chinese)
                .addOnSuccessListener(thai->showTranslation(ticket,chinese,thai))
                .addOnFailureListener(e->translationFailed(ticket,chinese));
        }
    }

    private void showTranslation(int ticket,String chinese,String thai) {
        if (running && ticket==generation) caption(chinese,thai);
    }

    private void translationFailed(int ticket,String chinese) {
        if (running && ticket==generation) caption(chinese,"แปลไม่สำเร็จ โปรดลองใหม่");
    }

    @Override public void onReadyForSpeech(Bundle data) {retries=0;}
    @Override public void onBeginningOfSpeech() {retries=0;}
    @Override public void onRmsChanged(float rms) {}
    @Override public void onBufferReceived(byte[] buffer) {}
    @Override public void onEndOfSpeech() {}
    @Override public void onPartialResults(Bundle b) {speech(b,false);}
    @Override public void onResults(Bundle b) {
        speech(b,true);
        lastPartial="";
        retries=0;
        restart(330);
    }
    @Override public void onError(int code) {
        if (!running) return;
        lastPartial="";
        if (code==SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            failure("ไม่มีสิทธิ์ไมโครโฟน");return;
        }
        if (Build.VERSION.SDK_INT>=31 &&
                (code==SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
                    || code==SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)) {
            failure("บริการเสียงไม่มีโมเดลภาษาจีน โปรดดาวน์โหลดชุด Speech หรือเปลี่ยนโหมด");
            return;
        }
        if (code==SpeechRecognizer.ERROR_NETWORK || code==SpeechRecognizer.ERROR_NETWORK_TIMEOUT) {
            caption("NETWORK","บริการรู้จำเสียงเชื่อมต่อไม่ได้ กำลังลองใหม่…");
        }
        retries=Math.min(retries+1,6);
        restart(code==SpeechRecognizer.ERROR_NO_MATCH ||
                code==SpeechRecognizer.ERROR_SPEECH_TIMEOUT?900:Math.min(850L*retries,5000));
    }
    @Override public void onEvent(int eventType,Bundle data) {}

    private void createOverlay() {
        try {
            windows=(WindowManager)getSystemService(WINDOW_SERVICE);
            overlay=new LinearLayout(this);
            overlay.setOrientation(LinearLayout.VERTICAL);
            overlay.setGravity(Gravity.CENTER);
            overlay.setPadding(dp(13),dp(10),dp(13),dp(11));
            GradientDrawable bg=new GradientDrawable();
            bg.setColor(0xe811151a);
            bg.setCornerRadius(dp(15));
            bg.setStroke(dp(1),0x77f4bd7e);
            overlay.setBackground(bg);
            zhView=new TextView(this);
            zhView.setGravity(Gravity.CENTER);
            zhView.setTextSize(14);
            zhView.setMaxLines(2);
            zhView.setTextColor(0xfff4bd7e);
            overlay.addView(zhView,new LinearLayout.LayoutParams(-1,-2));
            thView=new TextView(this);
            thView.setGravity(Gravity.CENTER);
            thView.setTextSize(20);
            thView.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
            thView.setMaxLines(3);
            thView.setTextColor(Color.WHITE);
            LinearLayout.LayoutParams thaiParams=new LinearLayout.LayoutParams(-1,-2);
            thaiParams.topMargin=dp(4);
            overlay.addView(thView,thaiParams);
            params=new WindowManager.LayoutParams(
                    getResources().getDisplayMetrics().widthPixels-dp(26),
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        |WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        |WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            params.gravity=Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL;
            params.y=dp(85);
            overlay.setOnTouchListener(new android.view.View.OnTouchListener(){
                float previousY;
                @Override public boolean onTouch(android.view.View view,MotionEvent event) {
                    if (event.getActionMasked()==MotionEvent.ACTION_DOWN) {
                        previousY=event.getRawY();return true;
                    }
                    if (event.getActionMasked()==MotionEvent.ACTION_MOVE) {
                        int delta=Math.round(event.getRawY()-previousY);
                        previousY=event.getRawY();
                        params.y=Math.max(dp(12),Math.min(
                            getResources().getDisplayMetrics().heightPixels-dp(120),params.y-delta));
                        if (windows!=null) windows.updateViewLayout(overlay,params);
                        return true;
                    }
                    return event.getActionMasked()==MotionEvent.ACTION_UP;
                }
            });
            windows.addView(overlay,params);
        } catch (RuntimeException error) {
            overlay=null;
        }
    }

    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        if (windows!=null && overlay!=null && params!=null) {
            params.width=getResources().getDisplayMetrics().widthPixels-dp(26);
            try {windows.updateViewLayout(overlay,params);}
            catch (RuntimeException ignored) {}
        }
    }

    private void caption(String chinese,String thai) {
        main.post(()->{
            if (zhView!=null && thView!=null && running) {
                zhView.setText(chinese);
                thView.setText(thai);
            }
        });
    }

    private int dp(float value) {return Math.round(value*getResources().getDisplayMetrics().density);}

    @Override public void onDestroy() {
        running=false;
        modelsReady=false;
        generation++;
        getSharedPreferences("hansub",MODE_PRIVATE).edit().putBoolean("running",false).apply();
        main.removeCallbacksAndMessages(null);
        if (recognizer!=null) {recognizer.cancel();recognizer.destroy();recognizer=null;}
        if (direct!=null) {direct.close();direct=null;}
        if (chineseEnglish!=null) {chineseEnglish.close();chineseEnglish=null;}
        if (englishThai!=null) {englishThai.close();englishThai=null;}
        if (windows!=null && overlay!=null) {
            try {windows.removeViewImmediate(overlay);}catch (RuntimeException ignored) {}
        }
        overlay=null;
        super.onDestroy();
    }
}
