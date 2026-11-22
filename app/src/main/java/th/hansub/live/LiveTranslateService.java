package th.hansub.live;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.util.ArrayList;
import java.util.List;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;

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
    /** Own microphone capture, so the recognizer never asks for audio focus and videos keep playing. */
    private MicFeeder feeder;
    private ParcelFileDescriptor sessionSound;
    private int ownMicFailures;
    /** Screen-capture consent, only held in "capture other apps' sound" mode. */
    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;
    private boolean silenceHinted;
    /** On-device Chinese speech to text (SenseVoice). Null when the system recognizer is used instead. */
    private LocalAsr localAsr;
    private TextView statsView;
    /** On-device Hy-MT2 translator. Null until its model has loaded, and when it is off or unsupported. */
    private LocalTranslator mt;
    /** Short reason shown in the stats line when the local translator could not start. */
    private String mtNote="";
    /** Batch number of the oldest subtitle still on screen. The translator skips sentences older than this. */
    private volatile int oldestShownSeq;
    /** Which sentence each shown line is: (batch number << 8) | position in the batch. */
    private final List<Integer> shownKeys=new ArrayList<>();
    /**
     * Live Thai for the part of the sentence still being spoken, shown dimmer under the settled lines. It is
     * replaced, never added to, so nothing is repeated when the part settles.
     */
    private String tentative="";
    private String partialSource="",partialWaiting;
    private boolean partialBusy;
    private int partialTicket;
    /** Hy-MT2 answers that arrived before their ML Kit draft was shown. */
    private final java.util.HashMap<Integer,String> mtResults=new java.util.HashMap<>();
    private volatile long lastTranslateMs,lastMtMs;
    private Translator direct, chineseEnglish, englishThai;
    private String mode=ModelCatalog.FAST;
    private boolean running,modelsReady;
    private int generation,retries,cloudAppliedTicket=-1;
    private long lastPartialAt;
    private String lastPartial="";
    private final DialogueContext dialogue=new DialogueContext(6);
    private RemoteContextTranslator contextual;
    private String remoteUrl="", clientToken="";
    private WindowManager windows;
    private LinearLayout overlay;
    private TextView zhView,thView;
    private WindowManager.LayoutParams params;
    private boolean overlayMoved;
    /** Widest the subtitle box gets. On a wide landscape screen it stays centred instead of stretching edge to edge. */
    private static final int OVERLAY_MAX_DP=480;
    private static final float SCALE_MIN=0.6f, SCALE_MAX=1.6f, SCALE_STEP=0.15f;
    private float overlayScale=1f;

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
        int resultCode=intent!=null?intent.getIntExtra("projection_code",0):0;
        @SuppressWarnings("deprecation")
        Intent projectionData=intent!=null?(Intent)intent.getParcelableExtra("projection_data"):null;
        boolean playbackRequested=Build.VERSION.SDK_INT>=29 && projectionData!=null
                && "playback".equals(getSharedPreferences("hansub",MODE_PRIVATE).getString("audio_path","own"));
        try {
            NotificationManager manager=getSystemService(NotificationManager.class);
            if (manager!=null) manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL,"HanSub microphone",NotificationManager.IMPORTANCE_LOW));
            if (Build.VERSION.SDK_INT>=29) {
                // Say exactly which kinds we use. Asking for screen capture without consent would be refused.
                int type=ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                        |(playbackRequested?ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION:0);
                startForeground(NOTIFICATION_ID,notification(),type);
            } else {
                startForeground(NOTIFICATION_ID,notification());
            }
            if (playbackRequested) openProjection(resultCode,projectionData);
        } catch (RuntimeException e) {
            stopSelf();return START_NOT_STICKY;
        }
        running=true;
        getSharedPreferences("hansub",MODE_PRIVATE).edit().putBoolean("running",true).apply();
        createOverlay();
        if (overlay==null) {stopSelf();return START_NOT_STICKY;}
        mode=ModelCatalog.normalizeMode(
                getSharedPreferences("hansub",MODE_PRIVATE).getString("translation_mode",ModelCatalog.FAST));
        dialogue.parseNames(getSharedPreferences("hansub",MODE_PRIVATE).getString("character_names",""));
        remoteUrl=getSharedPreferences("hansub",MODE_PRIVATE).getString("proxy_url","");
        clientToken=getSharedPreferences("hansub",MODE_PRIVATE).getString("proxy_token","");
        if (ModelCatalog.isCloud(mode)) contextual=new RemoteContextTranslator();
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
        startLocalTranslator();
        caption("中文 → ไทย",ModelCatalog.PIVOT.equals(mode)
                ?"กำลังฟัง (โหมด Pivot)…":ModelCatalog.isCloud(mode)
                ?"กำลังฟัง (Cloud + ML Kit fallback)…":"กำลังฟัง (โหมด Fast)…");
        if ("local".equals(getSharedPreferences("hansub",MODE_PRIVATE).getString("asr_engine","local"))) {
            if (LocalModels.activeReady(this)) {
                startLocalEngine();
                return;
            }
            caption("中文 → ไทย","ยังไม่ได้ดาวน์โหลดโมเดลถอดเสียงในเครื่อง ใช้ระบบ Android ชั่วคราว");
        }
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
            openOwnMic();
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
        if (feeder!=null && Build.VERSION.SDK_INT>=33) {
            try {
                closeSessionSound();
                sessionSound=feeder.attach();
                i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE,sessionSound);
                i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT,1);
                i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING,AudioFormat.ENCODING_PCM_16BIT);
                i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE,MicFeeder.RATE);
            } catch (java.io.IOException e) {
                useSystemMic("สร้างช่องเสียงไม่ได้");
            }
        }
        return i;
    }

    /**
     * Loads Hy-MT2 in the background. Until it is ready, and for any sentence it skips, the ML Kit draft stays.
     * Off when the model is not downloaded, the phone is 32-bit, the user turned it off, or Cloud mode is on.
     */
    private void startLocalTranslator() {
        android.content.SharedPreferences prefs=getSharedPreferences("hansub",MODE_PRIVATE);
        if (!prefs.getBoolean("mt_local",true) || ModelCatalog.isCloud(mode)) return;
        if (!TranslatorModel.ready(this)) return;
        if (!LocalTranslator.supported()) {mtNote="แปลในเครื่องใช้ได้เฉพาะเครื่อง 64 บิต";return;}
        mtNote="กำลังโหลด Hy-MT2";
        new Thread(()->{
            try {
                LocalTranslator opened=LocalTranslator.open(this);
                main.post(()->{
                    if (!running) {new Thread(opened::close,"hansub-mt-close").start();return;}
                    mt=opened;
                    mtNote="";
                });
            } catch (Throwable error) {
                String why=error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();
                main.post(()->mtNote="Hy-MT2 ใช้ไม่ได้ ("+why+")");
            }
        },"hansub-mt-load").start();
    }

    /**
     * Loads SenseVoice and Silero VAD on a background thread, then opens our own capture and feeds it in.
     * Nothing here touches Android's speech service, so there is no audio focus and no restarts.
     */
    private void startLocalEngine() {
        caption("中文 → ไทย","กำลังโหลดโมเดลถอดเสียงในเครื่อง…");
        new Thread(()->{
            LocalAsr asr;
            try {
                asr=new LocalAsr(this,new LocalAsr.Listener() {
                    @Override public void onPartial(String chinese) {main.post(()->heard(chinese,false));}
                    @Override public void onFinal(List<String> chinese,List<Integer> speakers,long decodeMs) {
                        main.post(()->heardFinal(chinese,speakers));
                    }
                });
            } catch (Throwable error) {
                main.post(()->failure("โหลดโมเดลถอดเสียงไม่ได้: "+error.getClass().getSimpleName()
                        +" ลองลบแล้วดาวน์โหลดใหม่ใน Model Manager"));
                return;
            }
            main.post(()->{
                if (!running) {asr.stop();return;}
                localAsr=asr;
                if (!openCapture()) {failure("เปิดไมค์หรือจับเสียงไม่ได้");return;}
                feeder.setListener(asr);
                asr.start();
                caption("中文 → ไทย",feeder.isPlayback()
                        ?"กำลังฟังเสียงจากแอปที่เล่นอยู่…":"กำลังฟังจากไมค์ (เปิดลำโพงไว้)…");
                startStats();
            });
        },"hansub-asr-load").start();
    }

    /** Text from the on-device recognizer. The Chinese line shows at once; translation is paced. */
    private void heard(String chinese,boolean finalResult) {
        if (!running || !modelsReady) return;
        long now=SystemClock.elapsedRealtime();
        if (!finalResult) {
            // Live Chinese, and a quick ML Kit Thai draft of it; settled sentences are added by heardFinal.
            captionZh(chinese);
            lastPartialAt=now;
            if (localAsr!=null) {
                if (chinese.isEmpty()) {
                    if (!tentative.isEmpty()) {clearTentative();updateThai(renderLines());}
                } else {
                    liveDraft(chinese);
                }
            }
        } else {
            lastPartialAt=0;
            java.util.List<String> previous=dialogue.previous();
            dialogue.record(chinese);
            translate(chinese,true,previous);
        }
    }

    /** Subtitle colour per speaker number, like fansubs. Speaker 0 stays white. */
    private static final int[] SPEAKER_COLORS={0xFFFFFFFF,0xFFFFE08A,0xFF9FE3FF,0xFFB8F5B0,0xFFFFB8D9,0xFFD9C8FF};

    /** Translated batches wait here until every earlier batch is shown, so lines never come out of order. */
    private final java.util.TreeMap<Integer,Object[]> readyBatches=new java.util.TreeMap<>();
    private int batchSeq,batchShown;
    /** The Thai lines on screen now (newest last) and who said each. */
    private final List<String> shownLines=new ArrayList<>();
    private final List<Integer> shownOwners=new ArrayList<>();

    /**
     * Settled speech from the on-device recognizer: a clause committed mid-sentence, or what was left when
     * the speaker paused. It may come cut where the voice changes. Each piece is cut into sentences and every
     * sentence is translated on its own, in parallel; the results are added to the screen in order.
     */
    private void heardFinal(List<String> pieces,List<Integer> speakers) {
        if (!running || !modelsReady || pieces.isEmpty()) return;
        lastPartialAt=0;
        clearTentative();
        List<String> lines=new ArrayList<>();
        List<Integer> owners=new ArrayList<>();
        StringBuilder all=new StringBuilder();
        for (int i=0;i<pieces.size();i++) {
            all.append(pieces.get(i));
            for (String sentence:SubText.sentences(pieces.get(i))) {
                lines.add(sentence);
                owners.add(speakers.get(i));
            }
        }
        if (lines.isEmpty()) return;
        String chinese=all.toString();
        java.util.List<String> previous=dialogue.previous();
        dialogue.record(chinese);
        if (ModelCatalog.isCloud(mode)) {
            translate(chinese,true,previous);
            return;
        }
        captionZh(chinese);
        final int seq=batchSeq++;
        final long startedAt=SystemClock.elapsedRealtime();
        final String[] thai=new String[lines.size()];
        final int[] left={lines.size()};
        String localMode=ModelCatalog.PIVOT.equals(mode)?ModelCatalog.PIVOT:ModelCatalog.FAST;
        askLocalTranslator(seq,lines);
        for (int i=0;i<lines.size();i++) {
            final int index=i;
            translateOne(localMode,lines.get(i),result->main.post(()->{
                thai[index]=result==null?"":result;
                if (--left[0]>0) return;
                lastTranslateMs=SystemClock.elapsedRealtime()-startedAt;
                TranslationStats.record(this,localMode,lastTranslateMs);
                readyBatches.put(seq,new Object[]{thai,owners,seq});
                flushBatches();
            }));
        }
    }

    /** Quick ML Kit translation of the sentence as it is being spoken. One request at a time; the newest text wins. */
    private void liveDraft(String chinese) {
        if (!getSharedPreferences("hansub",MODE_PRIVATE).getBoolean("live_draft",true) || ModelCatalog.isCloud(mode)) return;
        if (chinese.length()<2 || chinese.equals(partialSource)) return;
        if (partialBusy) {partialWaiting=chinese;return;}
        partialBusy=true;
        partialSource=chinese;
        final int ticket=partialTicket;
        String localMode=ModelCatalog.PIVOT.equals(mode)?ModelCatalog.PIVOT:ModelCatalog.FAST;
        translateOne(localMode,chinese,result->main.post(()->{
            partialBusy=false;
            if (running && ticket==partialTicket && result!=null) {
                String draft=SubText.fixLeftovers(result,SubText::pinyin);
                if (!draft.equals(tentative)) {
                    tentative=draft;
                    updateThai(renderLines());
                }
            }
            String next=partialWaiting;
            partialWaiting=null;
            if (next!=null && running) liveDraft(next);
        }));
    }

    /** A settled sentence replaces the live draft: forget it and ignore any draft still being translated. */
    private void clearTentative() {
        partialTicket++;
        partialSource="";
        partialWaiting=null;
        tentative="";
    }

    /** Sends each sentence to Hy-MT2, if it is loaded. The better translation replaces the draft when it arrives. */
    private void askLocalTranslator(int seq,List<String> lines) {
        LocalTranslator translator=mt;
        if (translator==null) return;
        String names=getSharedPreferences("hansub",MODE_PRIVATE).getString("character_names","");
        for (int i=0;i<lines.size();i++) {
            final int key=(seq<<8)|Math.min(i,255);
            String sentence=lines.get(i);
            translator.submit(TranslatePrompt.build(sentence,names),TranslatePrompt.maxNewTokens(sentence),
                    ()->running && (key>>8)>=oldestShownSeq,
                    thai->main.post(()->applyLocalTranslation(key,thai)));
        }
    }

    /** Swaps a draft line for the Hy-MT2 translation. If the draft has not been shown yet, it is used instead of the draft. */
    private void applyLocalTranslation(int key,String thai) {
        if (!running) return;
        String line=SubText.fixLeftovers(thai,SubText::pinyin);
        if (line.isEmpty()) return;
        lastMtMs=mt==null?0:mt.lastMs;
        int at=shownKeys.indexOf(key);
        if (at>=0) {
            shownLines.set(at,line);
            updateThai(renderLines());
        } else if (shownKeys.isEmpty() || key>shownKeys.get(shownKeys.size()-1)) {
            mtResults.put(key,line);
        }
    }

    @SuppressWarnings("unchecked")
    private void flushBatches() {
        boolean changed=false;
        while (readyBatches.containsKey(batchShown)) {
            Object[] batch=readyBatches.remove(batchShown++);
            String[] thai=(String[])batch[0];
            List<Integer> owners=(List<Integer>)batch[1];
            int seq=(Integer)batch[2];
            for (int i=0;i<thai.length;i++) {
                int key=(seq<<8)|Math.min(i,255);
                String better=mtResults.remove(key);
                String line=better!=null?better:SubText.fixLeftovers(thai[i],SubText::pinyin);
                if (line.isEmpty()) continue;
                shownLines.add(line);
                shownOwners.add(owners.get(i));
                shownKeys.add(key);
                changed=true;
            }
        }
        // Two lines roll like film subtitles: the newest at the bottom, the one before it above.
        while (shownLines.size()>2) {shownLines.remove(0);shownOwners.remove(0);shownKeys.remove(0);}
        if (!shownKeys.isEmpty()) {
            int oldest=shownKeys.get(0);
            oldestShownSeq=oldest>>8;
            mtResults.keySet().removeIf(k->k<oldest);
        }
        if (changed && running) captionTh(renderLines());
    }

    private CharSequence renderLines() {
        // With a live draft on screen only the newest settled line stays above it, so there are never more than two lines.
        int keep=tentative.isEmpty()?2:1;
        int from=Math.max(0,shownLines.size()-keep);
        boolean dialogueLines=new java.util.HashSet<>(shownOwners.subList(from,shownOwners.size())).size()>1;
        SpannableStringBuilder text=new SpannableStringBuilder();
        for (int i=from;i<shownLines.size();i++) {
            if (text.length()>0) text.append('\n');
            int start=text.length();
            text.append(dialogueLines?"– "+shownLines.get(i):shownLines.get(i));
            int color=SPEAKER_COLORS[Math.floorMod(shownOwners.get(i),SPEAKER_COLORS.length)];
            text.setSpan(new ForegroundColorSpan(color),start,text.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (!tentative.isEmpty()) {
            if (text.length()>0) text.append('\n');
            int start=text.length();
            text.append(tentative);
            // Dimmer than settled lines, so it is clear this part may still change.
            text.setSpan(new ForegroundColorSpan(0xB3FFFFFF),start,text.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return text;
    }

    private void translateOne(String localMode,String chinese,java.util.function.Consumer<String> done) {
        if (ModelCatalog.PIVOT.equals(localMode) && chineseEnglish!=null && englishThai!=null) {
            chineseEnglish.translate(chinese)
                .addOnSuccessListener(english->englishThai.translate(english)
                    .addOnSuccessListener(done::accept).addOnFailureListener(e->done.accept(null)))
                .addOnFailureListener(e->done.accept(null));
        } else if (direct!=null) {
            direct.translate(chinese).addOnSuccessListener(done::accept).addOnFailureListener(e->done.accept(null));
        } else {
            done.accept(null);
        }
    }

    /** A thin line under the subtitle: input level, decode and translate time, and how far behind we are. */
    private void startStats() {
        if (statsView==null || !getSharedPreferences("hansub",MODE_PRIVATE).getBoolean("show_stats",true)) return;
        statsView.setVisibility(View.VISIBLE);
        main.post(new Runnable() {
            @Override public void run() {
                LocalAsr asr=localAsr;
                if (!running || asr==null || statsView==null) return;
                int bars=Math.round((float)Math.sqrt(asr.level)*5f);
                StringBuilder meter=new StringBuilder();
                for (int i=0;i<5;i++) meter.append(i<bars?'\u25AE':'\u25AF');
                String behind=asr.backlog>1?String.format(java.util.Locale.US," · ค้าง %.1fs",asr.backlog*0.2f):"";
                String better=mt!=null?" · Hy-MT2 "+lastMtMs+"ms":mtNote.isEmpty()?"":" · "+mtNote;
                statsView.setText("เสียง "+meter+" · ถอด "+asr.engineName()+" "+asr.lastDecodeMs+"ms · ร่าง "+lastTranslateMs+"ms"+better
                        +" · ช้า "+String.format(java.util.Locale.US,"%.1f",asr.lagMs/1000f)+"s"+behind);
                main.postDelayed(this,500);
            }
        });
    }

    /** Opens our own capture: other apps' sound if consent was given, otherwise the microphone. */
    private boolean openCapture() {
        if (projection!=null) {
            MicFeeder candidate=new MicFeeder();
            if (candidate.startPlayback(projection)) {
                feeder=candidate;
                watchSilence();
                return true;
            }
            releaseProjection();
            caption("MIC","จับเสียงจากแอปอื่นไม่ได้ ใช้ไมค์แทน");
        }
        MicFeeder candidate=new MicFeeder();
        if (candidate.startMic()) {
            feeder=candidate;
            return true;
        }
        return false;
    }

    /** System recognizer path: feeds it our capture through a pipe on Android 13+, unless set to "system". */
    private void openOwnMic() {
        if (Build.VERSION.SDK_INT<33) return;
        String path=getSharedPreferences("hansub",MODE_PRIVATE).getString("audio_path","own");
        if ("system".equals(path) && projection==null) return;
        if (!openCapture()) caption("MIC","เปิดไมค์เองไม่ได้ ใช้ไมค์ระบบแทน (วิดีโออาจหยุดชั่วคราว)");
    }

    /** Takes the screen-capture consent the person gave in the app and keeps it while we listen. */
    private void openProjection(int resultCode,Intent data) {
        MediaProjectionManager manager=getSystemService(MediaProjectionManager.class);
        if (manager==null || data==null) return;
        try {
            projection=manager.getMediaProjection(resultCode,data);
            if (projection==null) return;
            projectionCallback=new MediaProjection.Callback() {
                @Override public void onStop() {
                    // The person ended the capture from the system chip. Without it there is no sound.
                    main.post(()->{if (running) stopSelf();});
                }
            };
            projection.registerCallback(projectionCallback,main);
        } catch (RuntimeException e) {
            projection=null;
        }
    }

    private void releaseProjection() {
        if (projection==null) return;
        try {
            if (projectionCallback!=null) projection.unregisterCallback(projectionCallback);
            projection.stop();
        } catch (RuntimeException ignored) {
            // Already stopped.
        }
        projection=null;
        projectionCallback=null;
    }

    /**
     * If the captured sound stays silent, say so once. Either the scene is quiet or the app that plays
     * the video does not allow its sound to be captured.
     */
    private void watchSilence() {
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (!running || feeder==null || !feeder.isPlayback()) return;
                long silent=feeder.silentForMs();
                if (silent<1500) silenceHinted=false;
                if (silent>=20000 && !silenceHinted) {
                    silenceHinted=true;
                    caption("เงียบ","ไม่ได้ยินเสียงจากแอปที่เล่นอยู่ อาจเป็นฉากเงียบ หรือแอปนั้นห้ามดึงเสียง ลองสลับเป็นโหมดไมค์");
                }
                main.postDelayed(this,3000);
            }
        },3000);
    }

    /** Falls back to the system microphone. The old behaviour: works, but videos may pause. */
    private void useSystemMic(String reason) {
        if (feeder==null) return;
        feeder.stop();
        feeder=null;
        releaseProjection();
        closeSessionSound();
        caption("MIC",reason+" ใช้ไมค์ระบบแทน (วิดีโออาจหยุดชั่วคราว)");
    }

    private void closeSessionSound() {
        if (sessionSound!=null) {
            try {sessionSound.close();} catch (java.io.IOException ignored) {}
            sessionSound=null;
        }
    }

    /** Called when one recognizer session ends: closes the pipe so the next session starts clean. */
    private void endSession() {
        if (feeder!=null) feeder.detach();
        closeSessionSound();
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
            // Half a sentence is shown in Chinese only. Translating it made the Thai line rewrite itself
            // again and again as the sentence grew.
            if (chinese.equals(lastPartial)) return;
            lastPartial=chinese;
            lastPartialAt=now;
            captionZh(chinese);
        } else {
            // Always process final results, even when identical to the last partial.
            java.util.List<String> previous=dialogue.previous();
            dialogue.record(chinese);
            translate(chinese,true,previous);
        }
    }

    private void translate(String chinese,boolean finalResult,java.util.List<String> previous) {
        if (!running) return;
        final int ticket=++generation;
        final long startedAt=SystemClock.elapsedRealtime();
        // Show the Chinese now and keep the last Thai line until the new one is ready: no blank flash.
        captionZh(chinese);
        if (finalResult && ModelCatalog.isCloud(mode) && contextual!=null
                && remoteUrl.startsWith("https://") && !clientToken.trim().isEmpty()) {
            // Optimistic fast caption while the contextual model works online.
            translateOffline(ticket,chinese,startedAt,true,ModelCatalog.FAST);
            contextual.translate(remoteUrl,clientToken,mode,chinese,previous,dialogue.glossary(),
                (thai,error)->{
                    if (!running || ticket!=generation) return;
                    if (thai!=null && !thai.trim().isEmpty()) {
                        cloudAppliedTicket=ticket;
                        display(ticket,chinese,thai,mode,startedAt,true);
                    }
                    // On failure keep the already displayed ML Kit subtitle.
                });
            return;
        }
        String localMode=ModelCatalog.PIVOT.equals(mode)?ModelCatalog.PIVOT:ModelCatalog.FAST;
        translateOffline(ticket,chinese,startedAt,finalResult,localMode);
    }

    private void translateOffline(int ticket,String chinese,long startedAt,
                                  boolean finalResult,String localMode) {
        if (!running || ticket!=generation) return;
        if (ModelCatalog.PIVOT.equals(localMode)) {
            if (chineseEnglish==null || englishThai==null) return;
            chineseEnglish.translate(chinese)
                .addOnSuccessListener(english->{
                    if (!running || ticket!=generation) return;
                    englishThai.translate(english)
                        .addOnSuccessListener(thai->display(ticket,chinese,thai,localMode,startedAt,finalResult))
                        .addOnFailureListener(e->translationFailed(ticket,chinese));
                }).addOnFailureListener(e->translationFailed(ticket,chinese));
        } else if (direct!=null) {
            direct.translate(chinese)
                .addOnSuccessListener(thai->display(ticket,chinese,thai,localMode,startedAt,finalResult))
                .addOnFailureListener(e->translationFailed(ticket,chinese));
        }
    }

    private void display(int ticket,String chinese,String thai,String effectiveMode,
                         long startedAt,boolean finalResult) {
        if (!running || ticket!=generation) return;
        if (cloudAppliedTicket==ticket && ModelCatalog.FAST.equals(effectiveMode)) return;
        lastTranslateMs=SystemClock.elapsedRealtime()-startedAt;
        // ML Kit leaves words it does not know (often names) in Chinese. Show them as pinyin instead.
        captionTh(SubText.fixLeftovers(thai,SubText::pinyin));
        if (finalResult) {
            TranslationStats.record(this,effectiveMode,SystemClock.elapsedRealtime()-startedAt);
        }
    }

    private void translationFailed(int ticket,String chinese) {
        if (running && ticket==generation) caption(chinese,"แปลไม่สำเร็จ โปรดลองใหม่");
    }

    @Override public void onReadyForSpeech(Bundle data) {retries=0;}
    @Override public void onBeginningOfSpeech() {retries=0;}
    @Override public void onRmsChanged(float rms) {}
    @Override public void onBufferReceived(byte[] buffer) {}
    @Override public void onEndOfSpeech() {}
    @Override public void onPartialResults(Bundle b) {ownMicFailures=0;speech(b,false);}
    @Override public void onResults(Bundle b) {
        endSession();
        ownMicFailures=0;
        speech(b,true);
        lastPartial="";
        retries=0;
        restart(150);
    }
    @Override public void onError(int code) {
        endSession();
        if (!running) return;
        lastPartial="";
        if (feeder!=null && (code==SpeechRecognizer.ERROR_CLIENT || code==SpeechRecognizer.ERROR_AUDIO)
                && ++ownMicFailures>=3) {
            // The recognizer does not accept our sound. Do not leave the person without translation.
            ownMicFailures=0;
            useSystemMic("บริการเสียงไม่รับเสียงจากแอป");
        }
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
        if ((code==SpeechRecognizer.ERROR_RECOGNIZER_BUSY || code==SpeechRecognizer.ERROR_CLIENT)
                && recognizer!=null) {
            // A stuck session blocks the next startListening; cancel it so the retry can start clean.
            try {recognizer.cancel();} catch (RuntimeException ignored) {}
        }
        retries=Math.min(retries+1,6);
        restart(code==SpeechRecognizer.ERROR_NO_MATCH ||
                code==SpeechRecognizer.ERROR_SPEECH_TIMEOUT?450:Math.min(850L*retries,5000));
    }
    @Override public void onEvent(int eventType,Bundle data) {}

    private void createOverlay() {
        try {
            windows=(WindowManager)getSystemService(WINDOW_SERVICE);
            overlay=new LinearLayout(this);
            overlay.setOrientation(LinearLayout.VERTICAL);
            overlay.setGravity(Gravity.CENTER);
            overlayScale=Math.max(SCALE_MIN,Math.min(SCALE_MAX,
                    getSharedPreferences("hansub",MODE_PRIVATE).getFloat("overlay_scale",1f)));
            overlay.setPadding(dp(12),dp(6),dp(12),dp(10));
            // Plain background: one flat, mostly opaque dark colour. No gradient, no glow.
            GradientDrawable plain=new GradientDrawable();
            plain.setColor(0xE0101012);
            plain.setCornerRadius(dp(12));
            overlay.setBackground(plain);
            // Top row: smaller (A-), a grip for dragging, larger (A+).
            LinearLayout top=new LinearLayout(this);
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.addView(sizeButton("A\u2212",-SCALE_STEP));
            top.addView(new View(this),new LinearLayout.LayoutParams(0,1,1f));
            View grip=new View(this);
            grip.setBackground(Glass.oval(0x55FFFFFF));
            top.addView(grip,new LinearLayout.LayoutParams(dp(34),dp(3)));
            top.addView(new View(this),new LinearLayout.LayoutParams(0,1,1f));
            top.addView(sizeButton("A+",SCALE_STEP));
            overlay.addView(top,new LinearLayout.LayoutParams(-1,-2));
            zhView=new TextView(this);
            zhView.setGravity(Gravity.CENTER);
            zhView.setMaxLines(2);
            zhView.setTextColor(0xFFB4B4B8);
            overlay.addView(zhView,new LinearLayout.LayoutParams(-1,-2));
            thView=new TextView(this);
            thView.setGravity(Gravity.CENTER);
            thView.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
            thView.setMaxLines(3);
            thView.setTextColor(0xFFFFFFFF);
            LinearLayout.LayoutParams thaiParams=new LinearLayout.LayoutParams(-1,-2);
            thaiParams.topMargin=dp(2);
            overlay.addView(thView,thaiParams);
            statsView=new TextView(this);
            statsView.setGravity(Gravity.CENTER);
            statsView.setTextSize(10);
            statsView.setTextColor(0x99FFFFFF);
            statsView.setSingleLine(true);
            statsView.setVisibility(View.GONE);
            LinearLayout.LayoutParams statsParams=new LinearLayout.LayoutParams(-1,-2);
            statsParams.topMargin=dp(4);
            overlay.addView(statsView,statsParams);
            applyTextSizes();
            Configuration config=getResources().getConfiguration();
            params=new WindowManager.LayoutParams(
                    overlayWidth(config),
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        |WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        |WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            params.gravity=Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL;
            params.y=defaultOverlayY(config);
            overlay.setOnTouchListener(new android.view.View.OnTouchListener(){
                float previousY;
                @Override public boolean onTouch(android.view.View view,MotionEvent event) {
                    if (event.getActionMasked()==MotionEvent.ACTION_DOWN) {
                        previousY=event.getRawY();return true;
                    }
                    if (event.getActionMasked()==MotionEvent.ACTION_MOVE) {
                        int delta=Math.round(event.getRawY()-previousY);
                        previousY=event.getRawY();
                        overlayMoved=true;
                        params.y=clampOverlayY(params.y-delta,getResources().getConfiguration());
                        if (windows!=null) windows.updateViewLayout(overlay,params);
                        return true;
                    }
                    return event.getActionMasked()==MotionEvent.ACTION_UP;
                }
            });
            windows.addView(overlay,params);
            if (!Motion.reduced(this)) {
                // Appears with a soft spring, like Lumora's panels.
                overlay.setAlpha(0f);
                overlay.setScaleX(0.92f);
                overlay.setScaleY(0.92f);
                overlay.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(520)
                        .setInterpolator(Motion.SPRING).start();
            }
        } catch (RuntimeException error) {
            overlay=null;
        }
    }

    /** Screen width minus a small margin, capped so the box stays readable in landscape. */
    private int overlayWidth(Configuration config) {
        int widthDp=config.screenWidthDp>0?config.screenWidthDp
                :Math.round(getResources().getDisplayMetrics().widthPixels
                    /getResources().getDisplayMetrics().density);
        return dp(Math.max(200,Math.min(widthDp-26,Math.round(OVERLAY_MAX_DP*Math.max(1f,overlayScale)))));
    }

    private int screenHeightPx(Configuration config) {
        return config.screenHeightDp>0?dp(config.screenHeightDp)
                :getResources().getDisplayMetrics().heightPixels;
    }

    /** Distance from the bottom edge. Landscape screens are short, so the box sits lower there. */
    private int defaultOverlayY(Configuration config) {
        return dp(config.orientation==Configuration.ORIENTATION_LANDSCAPE?24:85);
    }

    private int clampOverlayY(int y,Configuration config) {
        int max=Math.max(dp(12),screenHeightPx(config)-dp(120));
        return Math.max(dp(12),Math.min(max,y));
    }

    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        if (windows!=null && overlay!=null && params!=null) {
            params.width=overlayWidth(config);
            params.y=overlayMoved?clampOverlayY(params.y,config):defaultOverlayY(config);
            try {windows.updateViewLayout(overlay,params);}
            catch (RuntimeException ignored) {}
        }
    }

    /** Base sizes are small on purpose; the A- / A+ buttons scale both lines together. */
    private void applyTextSizes() {
        if (zhView!=null) zhView.setTextSize(11f*overlayScale);
        if (thView!=null) thView.setTextSize(16f*overlayScale);
    }

    private TextView sizeButton(String label,float delta) {
        TextView button=new TextView(this);
        button.setText(label);
        button.setTextSize(13);
        button.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        button.setTextColor(0xFFD0D0D4);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(10),dp(4),dp(10),dp(4));
        button.setOnClickListener(v->changeScale(delta));
        return button;
    }

    private void changeScale(float delta) {
        float next=Math.max(SCALE_MIN,Math.min(SCALE_MAX,Math.round((overlayScale+delta)*100f)/100f));
        if (next==overlayScale) return;
        overlayScale=next;
        getSharedPreferences("hansub",MODE_PRIVATE).edit().putFloat("overlay_scale",overlayScale).apply();
        applyTextSizes();
        if (windows!=null && overlay!=null && params!=null) {
            params.width=overlayWidth(getResources().getConfiguration());
            try {windows.updateViewLayout(overlay,params);}
            catch (RuntimeException ignored) {}
        }
    }

    private void captionZh(String chinese) {
        main.post(()->{if (zhView!=null && running) zhView.setText(chinese);});
    }

    private void captionTh(CharSequence thai) {
        main.post(()->{
            if (thView==null || !running) return;
            boolean changed=!TextUtils.equals(thai.toString(),thView.getText().toString());
            thView.setText(thai);
            if (changed && !Motion.reduced(this)) {
                thView.setAlpha(0.35f);
                thView.setTranslationY(dp(4));
                thView.animate().alpha(1f).translationY(0f).setDuration(180).setInterpolator(Motion.SOFT).start();
            }
        });
    }

    /** Changes the Thai text in place, without the rise animation: used when a better translation replaces a draft. */
    private void updateThai(CharSequence thai) {
        main.post(()->{if (thView!=null && running) thView.setText(thai);});
    }

    private void caption(String chinese,String thai) {
        main.post(()->{
            if (zhView!=null && thView!=null && running) {
                boolean changed=!thai.contentEquals(thView.getText());
                zhView.setText(chinese);
                thView.setText(thai);
                if (changed && !Motion.reduced(this)) {
                    // The new line rises into place instead of snapping.
                    thView.setAlpha(0.35f);
                    thView.setTranslationY(dp(4));
                    thView.animate().alpha(1f).translationY(0f).setDuration(180)
                            .setInterpolator(Motion.SOFT).start();
                }
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
        if (feeder!=null) {feeder.setListener(null);feeder.stop();feeder=null;}
        if (localAsr!=null) {localAsr.stop();localAsr=null;}
        if (mt!=null) {LocalTranslator closing=mt;mt=null;new Thread(closing::close,"hansub-mt-close").start();}
        releaseProjection();
        closeSessionSound();
        if (direct!=null) {direct.close();direct=null;}
        if (chineseEnglish!=null) {chineseEnglish.close();chineseEnglish=null;}
        if (englishThai!=null) {englishThai.close();englishThai=null;}
        if (contextual!=null) {contextual.close();contextual=null;}
        if (windows!=null && overlay!=null) {
            try {windows.removeViewImmediate(overlay);}catch (RuntimeException ignored) {}
        }
        overlay=null;
        super.onDestroy();
    }
}
