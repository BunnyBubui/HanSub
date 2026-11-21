package th.hansub.live;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.speech.ModelDownloadListener;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.TranslateRemoteModel;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ModelManagerActivity extends Activity {
    private static final int BG=0xff12151b, CARD=0xff222731, ACCENT=0xfff4bd7e, MUTED=0xffbbc3cd;
    private final RemoteModelManager manager=RemoteModelManager.getInstance();
    private final Map<String,TextView> packStatuses=new HashMap<>();
    private final Map<String,Button> packButtons=new HashMap<>();
    private final Set<String> installed=new HashSet<>();
    private LinearLayout root;
    private TextView feedback;
    private Button fastButton, pivotButton, defaultSpeechButton, offlineSpeechButton;
    private SpeechRecognizer speechDownloader;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView page=new ScrollView(this);
        page.setFillViewport(true);
        page.setBackgroundColor(BG);
        root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20),dp(40),dp(20),dp(28));
        page.addView(root);
        setContentView(page);
        if (Build.VERSION.SDK_INT>=35) {
            page.setOnApplyWindowInsetsListener((v,insets)-> {
                android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());
                root.setPadding(dp(20),bars.top+dp(24),dp(20),bars.bottom+dp(28));
                return insets;
            });
            page.requestApplyInsets();
        }

        label("HAN SUB  /  AI MODELS",14,ACCENT,true);
        gap(12);
        label("Model Manager",28,Color.WHITE,true);
        gap(9);
        label("ดาวน์โหลด เลือก และลบชุดโมเดลแปลที่มีอยู่จริงใน Google ML Kit",14,MUTED,false);
        gap(20);
        label("ชุดโมเดลภาษา (On-device)",18,Color.WHITE,true);
        gap(10);
        pack("zh","ภาษาจีน","ต้องใช้ในทุกโหมด");
        pack("th","ภาษาไทย","ต้องใช้ในทุกโหมด");
        pack("en","ภาษาอังกฤษ","ใช้กับโหมด Pivot เท่านั้น");
        feedback=label("กำลังตรวจสอบโมเดล…",13,ACCENT,false);
        gap(13);

        label("เลือกโหมดการแปล",18,Color.WHITE,true);
        gap(10);
        fastButton=button("Fast • 中文 → ไทย (แนะนำ)",CARD,Color.WHITE);
        fastButton.setOnClickListener(v->selectMode(ModelCatalog.FAST));
        root.addView(fastButton);
        gap(8);
        pivotButton=button("Pivot • 中文 → English → ไทย (ทดลอง)",CARD,Color.WHITE);
        pivotButton.setOnClickListener(v->selectMode(ModelCatalog.PIVOT));
        root.addView(pivotButton);
        gap(8);
        label("Fast เรียกแปลหนึ่งขั้นตอนจึงเหมาะกับซับสดมากกว่า\n"
                +"Pivot แปลสองขั้นตอน อาจช้ากว่าและไม่ได้รับประกันว่าแม่นยำขึ้น",12,MUTED,false);
        gap(18);

        label("โมเดลรู้จำเสียงจีน",18,Color.WHITE,true);
        gap(9);
        defaultSpeechButton=button("Speech • ระบบ Android (ค่าเริ่มต้น)",CARD,Color.WHITE);
        defaultSpeechButton.setOnClickListener(v->selectSpeech(ModelCatalog.SPEECH_DEFAULT));
        root.addView(defaultSpeechButton);
        gap(8);
        offlineSpeechButton=button("Speech • On-device เท่านั้น (ถ้ารองรับ)",CARD,Color.WHITE);
        offlineSpeechButton.setOnClickListener(v->selectSpeech(ModelCatalog.SPEECH_OFFLINE));
        root.addView(offlineSpeechButton);
        gap(8);
        Button downloadSpeech=button("ขอดาวน์โหลดชุดรู้จำเสียงจีนผ่าน Android",0xff353d4a,Color.WHITE);
        downloadSpeech.setOnClickListener(v->downloadSpeech());
        root.addView(downloadSpeech);
        gap(10);
        label("ชุด Speech เป็นของบริการระบบ Android ไม่ได้จัดเก็บโดย ML Kit "
             +"จึงไม่สามารถลบผ่านปุ่มลบโมเดลแปล การดาวน์โหลด/ความคืบหน้าขึ้นกับเครื่อง",12,MUTED,false);
        gap(22);
        label("Powered by Google Translate",12,MUTED,false);
        updateSelections();
        refreshPacks();
    }

    private void pack(String lang,String name,String description) {
        LinearLayout panel=new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14),dp(13),dp(14),dp(13));
        panel.setBackground(background(CARD));
        root.addView(panel,new LinearLayout.LayoutParams(-1,-2));
        TextView title=text(name+"  /  "+lang,16,Color.WHITE,true);
        panel.addView(title);
        TextView detail=text(description,12,MUTED,false);
        panel.addView(detail);
        TextView status=text("กำลังตรวจสอบ…",12,ACCENT,false);
        panel.addView(status);
        packStatuses.put(lang,status);
        Button action=button("ดาวน์โหลด",0xff393f49,Color.WHITE);
        action.setOnClickListener(v->togglePack(lang));
        panel.addView(action);
        packButtons.put(lang,action);
        gap(9);
    }

    private void refreshPacks() {
        manager.getDownloadedModels(TranslateRemoteModel.class)
            .addOnSuccessListener(models->{
                installed.clear();
                for (TranslateRemoteModel model: models) installed.add(model.getLanguage());
                for (String lang:new String[]{"zh","th","en"}) {
                    boolean present=installed.contains(lang);
                    packStatuses.get(lang).setText(present ? "ติดตั้งแล้ว" : "ยังไม่ดาวน์โหลด");
                    Button b=packButtons.get(lang);
                    b.setText(present?"ลบชุดโมเดล":"ดาวน์โหลดชุดโมเดล");
                    b.setEnabled(!getSharedPreferences("hansub",MODE_PRIVATE)
                        .getBoolean("running",false));
                }
                feedback.setText("ตรวจสอบโมเดลสำเร็จ");
            })
            .addOnFailureListener(error->feedback.setText("อ่านสถานะโมเดลไม่ได้: "+error.getMessage()));
    }

    private void togglePack(String lang) {
        if (getSharedPreferences("hansub",MODE_PRIVATE).getBoolean("running",false)) {
            feedback.setText("โปรดหยุดการแปลก่อนจัดการโมเดล");
            return;
        }
        Button button=packButtons.get(lang);
        button.setEnabled(false);
        feedback.setText(installed.contains(lang)?"กำลังลบโมเดล "+lang+"…":"กำลังดาวน์โหลดโมเดล "+lang+"…");
        TranslateRemoteModel model=new TranslateRemoteModel.Builder(lang).build();
        if (installed.contains(lang)) {
            manager.deleteDownloadedModel(model)
                .addOnSuccessListener(v->refreshPacks())
                .addOnFailureListener(e->{feedback.setText("ลบไม่สำเร็จ: "+e.getMessage());refreshPacks();});
        } else {
            manager.download(model,new DownloadConditions.Builder().build())
                .addOnSuccessListener(v->refreshPacks())
                .addOnFailureListener(e->{feedback.setText("ดาวน์โหลดไม่สำเร็จ: "+e.getMessage());refreshPacks();});
        }
    }

    private void selectMode(String mode) {
        getSharedPreferences("hansub",MODE_PRIVATE).edit().putString("translation_mode",mode).apply();
        updateSelections();
        feedback.setText("โหมดใหม่มีผลเมื่อเริ่มแปลครั้งถัดไป");
    }

    private void selectSpeech(String speech) {
        if (ModelCatalog.isOfflineSpeech(speech) && (Build.VERSION.SDK_INT<31 ||
                 !SpeechRecognizer.isOnDeviceRecognitionAvailable(this))) {
            feedback.setText("อุปกรณ์นี้ไม่มีบริการรู้จำเสียง On-device ของ Android");
            return;
        }
        getSharedPreferences("hansub",MODE_PRIVATE).edit().putString("speech_mode",speech).apply();
        updateSelections();
        feedback.setText("ตั้งค่าเสียงแล้ว เริ่มใช้เมื่อเปิดการแปลครั้งถัดไป");
    }

    private void updateSelections() {
        String mode=getSharedPreferences("hansub",MODE_PRIVATE).getString("translation_mode",ModelCatalog.FAST);
        String speech=getSharedPreferences("hansub",MODE_PRIVATE).getString("speech_mode",ModelCatalog.SPEECH_DEFAULT);
        selected(fastButton,ModelCatalog.FAST.equals(mode));
        selected(pivotButton,ModelCatalog.PIVOT.equals(mode));
        selected(defaultSpeechButton,!ModelCatalog.isOfflineSpeech(speech));
        selected(offlineSpeechButton,ModelCatalog.isOfflineSpeech(speech));
    }

    private void selected(Button b,boolean active) {
        b.setTextColor(active?BG:Color.WHITE);
        b.setBackground(background(active?ACCENT:CARD));
    }

    private void downloadSpeech() {
        if (Build.VERSION.SDK_INT<33 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            feedback.setText("เครื่องนี้ไม่รองรับการขอดาวน์โหลด Speech pack ผ่าน API นี้");
            return;
        }
        try {
            if (speechDownloader!=null) speechDownloader.destroy();
            speechDownloader=SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
            speechDownloader.setRecognitionListener(new SilentListener());
            Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,"zh-CN");
            feedback.setText("ส่งคำขอดาวน์โหลดโมเดลเสียงจีน…");
            if (Build.VERSION.SDK_INT>=34) {
                speechDownloader.triggerModelDownload(intent,this::runOnUiThread,new ModelDownloadListener(){
                    @Override public void onProgress(int progress) {feedback.setText("กำลังดาวน์โหลด Speech: "+progress+"%");}
                    @Override public void onSuccess() {feedback.setText("บริการระบบแจ้งว่า Speech model พร้อมใช้งานแล้ว");}
                    @Override public void onScheduled() {feedback.setText("ระบบตั้งคิวดาวน์โหลดแล้ว โปรดตรวจสอบภายหลัง");}
                    @Override public void onError(int error) {feedback.setText("ดาวน์โหลด Speech ไม่สำเร็จ รหัส "+error);}
                });
            } else {
                speechDownloader.triggerModelDownload(intent);
                feedback.setText("ส่งคำขอไปยังบริการเสียงแล้ว (Android 13 ไม่รายงานผลอัตโนมัติ)");
            }
        } catch (RuntimeException e) {
            feedback.setText("บริการเสียงไม่รองรับ: "+e.getClass().getSimpleName());
        }
    }

    @Override protected void onResume() {super.onResume();if (feedback!=null) refreshPacks();}
    @Override protected void onDestroy() {
        if (speechDownloader!=null) {speechDownloader.destroy();speechDownloader=null;}
        super.onDestroy();
    }

    private static class SilentListener implements RecognitionListener {
        @Override public void onReadyForSpeech(Bundle b) {}
        @Override public void onBeginningOfSpeech() {}
        @Override public void onRmsChanged(float v) {}
        @Override public void onBufferReceived(byte[] b) {}
        @Override public void onEndOfSpeech() {}
        @Override public void onError(int e) {}
        @Override public void onResults(Bundle b) {}
        @Override public void onPartialResults(Bundle b) {}
        @Override public void onEvent(int e,Bundle b) {}
    }

    private TextView label(String value,int size,int color,boolean bold) {
        TextView t=text(value,size,color,bold);
        root.addView(t);
        return t;
    }
    private TextView text(String value,int size,int color,boolean bold) {
        TextView t=new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif",bold?Typeface.BOLD:Typeface.NORMAL));
        t.setLineSpacing(dp(3),1f);
        return t;
    }
    private void gap(int h) {View v=new View(this);root.addView(v,new LinearLayout.LayoutParams(1,dp(h)));}
    private Button button(String value,int color,int textColor) {
        Button b=new Button(this);
        b.setText(value);b.setAllCaps(false);b.setTextColor(textColor);b.setTextSize(14);
        b.setMinHeight(dp(49));b.setBackground(background(color));return b;
    }
    private GradientDrawable background(int color) {
        GradientDrawable d=new GradientDrawable();
        d.setColor(color);d.setCornerRadius(dp(13));return d;
    }
    private int dp(float v) {return Math.round(v*getResources().getDisplayMetrics().density);}
}
