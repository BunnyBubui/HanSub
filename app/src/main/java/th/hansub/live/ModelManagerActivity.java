package th.hansub.live;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.ModelDownloadListener;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.translate.TranslateRemoteModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Model Manager: language packs, translation mode, cloud context server and speech model, in Lumora's glass style. */
public class ModelManagerActivity extends Activity {
    private final RemoteModelManager manager=RemoteModelManager.getInstance();
    private final Map<String,TextView> packStatuses=new HashMap<>();
    private final Map<String,Glass.Btn> packButtons=new HashMap<>();
    private final Map<String,ProgressBar> packProgress=new HashMap<>();
    private final Set<String> installed=new HashSet<>();
    private final List<View> blocks=new ArrayList<>();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private Glass.Column root;
    private FrameLayout host;
    private TextView benchmarkOutput, performance;
    private Notice notice;
    private Option fastOption, pivotOption, cloudFastOption, cloudQualityOption, defaultSpeech, offlineSpeech;
    private SpeechRecognizer speechDownloader;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        host=new FrameLayout(this);
        host.setBackground(Glass.page());
        ScrollView page=new ScrollView(this);
        page.setFillViewport(true);
        page.setVerticalScrollBarEnabled(false);
        // Capped width: on a wide landscape screen the form stays readable instead of stretching.
        root=new Glass.Column(this,640);
        root.setPadding(dp(18),dp(14),dp(18),dp(28));
        page.addView(root,new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER_HORIZONTAL));
        host.addView(page,new FrameLayout.LayoutParams(-1,-1));
        setContentView(host);
        notice=new Notice(host);
        Glass.fitSystemBars(page,(l,t,r,b)->notice.setBottomInset(b));

        buildHeader();
        buildPacks();
        buildModes();
        buildPerformance();
        buildCloud();
        buildSpeech();
        TextView credit=Glass.text(this,"Powered by Google Translate",12,Glass.TEXT3,false);
        credit.setGravity(Gravity.CENTER);
        block(credit,22);
        updateSelections(false);
        refreshPacks();

        if (state==null && !Motion.reduced(this)) {
            for (View v: blocks) v.setAlpha(0f);
            Cine.enter(this,"MODELS",()->{
                for (int i=0;i<blocks.size();i++) Motion.rise(blocks.get(i),60L+i*55L,dp(14),480);
            });
        }
    }

    // ---- layout --------------------------------------------------------------------------------

    private void buildHeader() {
        LinearLayout row=new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout back=new FrameLayout(this);
        back.setBackground(Glass.panel(this,22,Glass.FILL));
        back.setClickable(true);
        back.setContentDescription("กลับ");
        IconView arrow=new IconView(this,IconView.CHEVRON);
        arrow.setTint(Glass.TEXT);
        arrow.setRotation(180f);
        back.addView(arrow,new FrameLayout.LayoutParams(dp(22),dp(22),Gravity.CENTER));
        back.setOnClickListener(v->finish());
        Motion.touchable(back);
        row.addView(back,new LinearLayout.LayoutParams(dp(44),dp(44)));
        LinearLayout titles=new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(Glass.text(this,"Model Manager",24,Glass.TEXT,true));
        titles.addView(Glass.text(this,"ดาวน์โหลด เลือก และลบชุดโมเดลแปล",13,Glass.TEXT2,false));
        LinearLayout.LayoutParams titleParams=new LinearLayout.LayoutParams(0,-2,1f);
        titleParams.leftMargin=dp(14);
        row.addView(titles,titleParams);
        block(row,6);
    }

    private void buildPacks() {
        LinearLayout card=section("ชุดโมเดลภาษา","ทำงานบนเครื่อง ไม่ต้องใช้เน็ตตอนแปล");
        pack(card,"zh","ภาษาจีน","ต้องใช้ในทุกโหมด");
        pack(card,"th","ภาษาไทย","ต้องใช้ในทุกโหมด");
        pack(card,"en","ภาษาอังกฤษ","ใช้กับโหมด Pivot เท่านั้น");
    }

    private void buildModes() {
        LinearLayout card=section("โหมดการแปล","โหมดใหม่มีผลเมื่อเริ่มแปลครั้งถัดไป");
        fastOption=option(card,"Fast • 中文 → ไทย","แนะนำ แปลขั้นตอนเดียว เหมาะกับซับสด");
        fastOption.setOnClickListener(v->selectMode(ModelCatalog.FAST));
        pivotOption=option(card,"Pivot • 中文 → English → ไทย","ทดลอง แปลสองขั้นตอน อาจช้ากว่า");
        pivotOption.setOnClickListener(v->selectMode(ModelCatalog.PIVOT));
        cloudFastOption=option(card,"Cloud Lite • Gemini 3.5 Flash-Lite","ต้องมีเซิร์ฟเวอร์ของคุณเอง");
        cloudFastOption.setOnClickListener(v->selectMode(ModelCatalog.CLOUD_FAST));
        cloudQualityOption=option(card,"Cloud Quality • Gemini 3.8 Flash","ต้องมีเซิร์ฟเวอร์ของคุณเอง");
        cloudQualityOption.setOnClickListener(v->selectMode(ModelCatalog.CLOUD_QUALITY));

        Glass.Btn benchmark=Glass.button(this,"ทดสอบ Fast เทียบ Pivot บนเครื่องนี้",Glass.FILL_STRONG,Glass.TEXT);
        benchmarkOutput=Glass.text(this,"ใช้ประโยคเดียวกันเพื่อดูเวลาตอบสนองและผลแปลทั้ง 2 โหมด",12,Glass.TEXT3,false);
        benchmark.setOnClickListener(v->{
            if (getSharedPreferences("hansub",MODE_PRIVATE).getBoolean("running",false)) {
                benchmarkOutput.setText("โปรดหยุดการแปลก่อนทดสอบ");
                return;
            }
            if (!(installed.contains("zh") && installed.contains("en") && installed.contains("th"))) {
                benchmarkOutput.setText("ต้องดาวน์โหลดโมเดลจีน ไทย และอังกฤษก่อนทดสอบ");
                return;
            }
            benchmark.setEnabled(false);
            benchmarkOutput.setText("กำลังแปลทดสอบด้วยโมเดลทั้งสองแบบ…");
            TranslationBenchmarker.run(report->{
                benchmarkOutput.setText(report);
                benchmark.setEnabled(true);
            });
        });
        add(card,benchmark,14);
        add(card,benchmarkOutput,8);
    }

    private void buildPerformance() {
        LinearLayout card=section("ผลความเร็วจากการแปลจริง","เวลาตอบสนอง P50/P95 ไม่ใช่คะแนนความแม่นยำ");
        performance=Glass.text(this,"ยังไม่มีข้อมูล",13,Glass.TEXT2,false);
        add(card,performance,12);
        showPerformance();
    }

    private void buildCloud() {
        LinearLayout card=section("Context AI • เซิร์ฟเวอร์ของคุณ",
                "Cloud แปลประโยคสุดท้ายโดยใช้บริบทก่อนหน้าได้สูงสุด 6 ประโยคและชื่อที่กำหนดเอง เมื่อเน็ตล่มจะกลับไปใช้ ML Kit บนเครื่อง");
        EditText endpoint=field("https://YOUR-CLOUD-RUN-URL/translate",false);
        endpoint.setText(getSharedPreferences("hansub",MODE_PRIVATE).getString("proxy_url",""));
        add(card,endpoint,12);
        EditText token=field("Client token (ไม่ใช่ Gemini API key)",true);
        token.setText(getSharedPreferences("hansub",MODE_PRIVATE).getString("proxy_token",""));
        add(card,token,8);
        add(card,Glass.text(this,"ชื่อจีน=ชื่อไทย (หนึ่งชื่อต่อบรรทัด)",13,Glass.TEXT,true),14);
        EditText names=field("张总=ประธานจาง\n小雨=เสี่ยวอวี่",false);
        names.setSingleLine(false);
        names.setMinLines(3);
        names.setText(getSharedPreferences("hansub",MODE_PRIVATE).getString("character_names",""));
        add(card,names,6);
        Glass.Btn save=Glass.button(this,"บันทึก Cloud และรายชื่อตัวละคร",Glass.ACCENT,Glass.ON_ACCENT);
        save.setOnClickListener(v->{
            String url=endpoint.getText().toString().trim();
            String secret=token.getText().toString().trim();
            if ((!url.isEmpty() && (!url.startsWith("https://") || !url.endsWith("/translate")))
                    || (url.length()>0 && secret.length()<16)) {
                notice("ต้องใช้ HTTPS URL ที่ลงท้าย /translate และ Client token อย่างน้อย 16 ตัว");
                return;
            }
            getSharedPreferences("hansub",MODE_PRIVATE).edit()
                .putString("proxy_url",url)
                .putString("proxy_token",secret)
                .putString("character_names",names.getText().toString())
                .apply();
            notice("บันทึกแล้ว ใช้กับการแปลครั้งถัดไป");
        });
        add(card,save,12);
        add(card,Glass.text(this,"โหมด Cloud ต้อง deploy backend และมี Gemini API key ฝั่งเซิร์ฟเวอร์ก่อน "
            +"ข้อความจีนและบริบทจะถูกส่งไปยัง URL ที่ตั้งค่า โปรดใช้เซิร์ฟเวอร์ที่เชื่อถือได้ "
            +"และห้ามใส่ Gemini API key ในแอป",12,Glass.TEXT3,false),10);
    }

    private void buildSpeech() {
        LinearLayout card=section("โมเดลรู้จำเสียงจีน",
                "ชุด Speech เป็นของบริการระบบ Android ไม่ได้จัดเก็บโดย ML Kit จึงลบผ่านปุ่มลบโมเดลแปลไม่ได้");
        defaultSpeech=option(card,"Speech • ระบบ Android","ค่าเริ่มต้น");
        defaultSpeech.setOnClickListener(v->selectSpeech(ModelCatalog.SPEECH_DEFAULT));
        offlineSpeech=option(card,"Speech • On-device เท่านั้น","ถ้าเครื่องรองรับ");
        offlineSpeech.setOnClickListener(v->selectSpeech(ModelCatalog.SPEECH_OFFLINE));
        Glass.Btn download=Glass.button(this,"ขอดาวน์โหลดชุดรู้จำเสียงจีนผ่าน Android",Glass.FILL_STRONG,Glass.TEXT);
        download.setOnClickListener(v->downloadSpeech());
        add(card,download,14);
    }

    // ---- building blocks -----------------------------------------------------------------------

    /** A glass card with a title; added to the page and counted for the entrance. */
    private LinearLayout section(String title,String note) {
        LinearLayout card=new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18),dp(16),dp(18),dp(18));
        card.setBackground(Glass.panel(this,20,Glass.FILL));
        card.addView(Glass.text(this,title,17,Glass.TEXT,true));
        if (note!=null) add(card,Glass.text(this,note,12,Glass.TEXT3,false),4);
        block(card,14);
        return card;
    }

    private void block(View v,int topDp) {
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.topMargin=dp(topDp);
        root.addView(v,p);
        blocks.add(v);
    }

    private void add(LinearLayout parent,View v,int topDp) {
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.topMargin=dp(topDp);
        parent.addView(v,p);
    }

    private void pack(LinearLayout card,String lang,String name,String description) {
        View divider=new View(this);
        divider.setBackgroundColor(Glass.alpha(Glass.TEXT,0x14));
        LinearLayout.LayoutParams lineParams=new LinearLayout.LayoutParams(-1,1);
        lineParams.topMargin=dp(14);
        card.addView(divider,lineParams);

        LinearLayout row=new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout texts=new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Glass.text(this,name+"  /  "+lang,15,Glass.TEXT,true));
        texts.addView(Glass.text(this,description,12,Glass.TEXT3,false));
        TextView status=Glass.text(this,"กำลังตรวจสอบ…",12,Glass.TEXT2,true);
        texts.addView(status);
        packStatuses.put(lang,status);
        row.addView(texts,new LinearLayout.LayoutParams(0,-2,1f));
        Glass.Btn action=Glass.button(this,"ดาวน์โหลด",Glass.ACCENT,Glass.ON_ACCENT);
        action.setMinHeight(dp(40));
        action.setTextSize(14);
        action.setOnClickListener(v->togglePack(lang));
        LinearLayout.LayoutParams actionParams=new LinearLayout.LayoutParams(-2,-2);
        actionParams.leftMargin=dp(12);
        row.addView(action,actionParams);
        packButtons.put(lang,action);
        add(card,row,12);

        ProgressBar bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        bar.setIndeterminateTintList(ColorStateList.valueOf(Glass.ACCENT));
        bar.setVisibility(View.GONE);
        packProgress.put(lang,bar);
        LinearLayout.LayoutParams barParams=new LinearLayout.LayoutParams(-1,dp(4));
        barParams.topMargin=dp(8);
        card.addView(bar,barParams);
    }

    /** A selectable row: title and note, with a ring that fills with the accent when picked. */
    private final class Option extends LinearLayout {
        private final FrameLayout mark;
        private final IconView check;
        private boolean on;

        Option(String title,String note) {
            super(ModelManagerActivity.this);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(14),dp(12),dp(12),dp(12));
            setClickable(true);
            LinearLayout texts=new LinearLayout(getContext());
            texts.setOrientation(VERTICAL);
            texts.addView(Glass.text(getContext(),title,15,Glass.TEXT,true));
            if (note!=null) texts.addView(Glass.text(getContext(),note,12,Glass.TEXT3,false));
            addView(texts,new LinearLayout.LayoutParams(0,-2,1f));
            mark=new FrameLayout(getContext());
            check=new IconView(getContext(),IconView.CHECK);
            check.setTint(Glass.ON_ACCENT);
            check.setWeight(2.6f);
            mark.addView(check,new FrameLayout.LayoutParams(dp(16),dp(16),Gravity.CENTER));
            LinearLayout.LayoutParams markParams=new LinearLayout.LayoutParams(dp(24),dp(24));
            markParams.leftMargin=dp(12);
            addView(mark,markParams);
            Motion.touchable(this);
            setOn(false,false);
        }

        void setOn(boolean value,boolean animate) {
            boolean turnedOn=value && !on;
            on=value;
            setBackground(Glass.panel(getContext(),14,value?Glass.ACCENT_SOFT:Glass.FILL_STRONG));
            if (value) {
                mark.setBackground(Glass.oval(Glass.ACCENT));
            } else {
                GradientDrawable ring=new GradientDrawable();
                ring.setShape(GradientDrawable.OVAL);
                ring.setColor(Color.TRANSPARENT);
                ring.setStroke(dp(2),Glass.TEXT3);
                mark.setBackground(ring);
            }
            check.setVisibility(value?View.VISIBLE:View.INVISIBLE);
            if (animate && turnedOn) Motion.pop(mark);
        }
    }

    private Option option(LinearLayout card,String title,String note) {
        Option o=new Option(title,note);
        add(card,o,10);
        return o;
    }

    private EditText field(String hint,boolean secret) {
        EditText e=new EditText(this);
        e.setHint(hint);
        e.setTextColor(Glass.TEXT);
        e.setHintTextColor(Glass.TEXT3);
        e.setTextSize(14);
        e.setPadding(dp(14),dp(12),dp(14),dp(12));
        e.setBackground(Glass.panel(this,14,Glass.FILL_STRONG));
        e.setHighlightColor(Glass.alpha(Glass.ACCENT,0x55));
        if (secret) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }

    private void notice(String message) {
        notice.show(message);
    }

    // ---- behaviour -----------------------------------------------------------------------------

    private void refreshPacks() {
        manager.getDownloadedModels(TranslateRemoteModel.class)
            .addOnSuccessListener(models->{
                installed.clear();
                for (TranslateRemoteModel model: models) installed.add(model.getLanguage());
                boolean running=getSharedPreferences("hansub",MODE_PRIVATE).getBoolean("running",false);
                for (String lang:new String[]{"zh","th","en"}) {
                    boolean present=installed.contains(lang);
                    TextView status=packStatuses.get(lang);
                    status.setText(present?"ติดตั้งแล้ว":"ยังไม่ดาวน์โหลด");
                    status.setTextColor(present?Glass.ACCENT:Glass.TEXT3);
                    Glass.Btn b=packButtons.get(lang);
                    b.setText(present?"ลบ":"ดาวน์โหลด");
                    b.setTextColor(present?Glass.TEXT:Glass.ON_ACCENT);
                    b.setBackground(Glass.panel(this,14,present?Glass.FILL_STRONG:Glass.ACCENT));
                    b.setEnabled(!running);
                    packProgress.get(lang).setVisibility(View.GONE);
                }
            })
            .addOnFailureListener(error->notice("อ่านสถานะโมเดลไม่ได้: "+error.getMessage()));
    }

    private void togglePack(String lang) {
        if (getSharedPreferences("hansub",MODE_PRIVATE).getBoolean("running",false)) {
            notice("โปรดหยุดการแปลก่อนจัดการโมเดล");
            return;
        }
        packButtons.get(lang).setEnabled(false);
        packProgress.get(lang).setVisibility(View.VISIBLE);
        packStatuses.get(lang).setText(installed.contains(lang)?"กำลังลบ…":"กำลังดาวน์โหลด…");
        TranslateRemoteModel model=new TranslateRemoteModel.Builder(lang).build();
        if (installed.contains(lang)) {
            manager.deleteDownloadedModel(model)
                .addOnSuccessListener(v->refreshPacks())
                .addOnFailureListener(e->{notice("ลบไม่สำเร็จ: "+e.getMessage());refreshPacks();});
        } else {
            manager.download(model,new DownloadConditions.Builder().build())
                .addOnSuccessListener(v->refreshPacks())
                .addOnFailureListener(e->{notice("ดาวน์โหลดไม่สำเร็จ: "+e.getMessage());refreshPacks();});
        }
    }

    private void showPerformance() {
        if (performance==null) return;
        performance.setText(
                "Fast (ML Kit) • "+TranslationStats.summary(this,ModelCatalog.FAST)+"\n"
                +"Pivot • "+TranslationStats.summary(this,ModelCatalog.PIVOT)+"\n"
                +"Cloud Lite • "+TranslationStats.summary(this,ModelCatalog.CLOUD_FAST)+"\n"
                +"Cloud Quality • "+TranslationStats.summary(this,ModelCatalog.CLOUD_QUALITY));
    }

    private void selectMode(String mode) {
        getSharedPreferences("hansub",MODE_PRIVATE).edit().putString("translation_mode",mode).apply();
        updateSelections(true);
        notice("โหมดใหม่มีผลเมื่อเริ่มแปลครั้งถัดไป");
    }

    private void selectSpeech(String speech) {
        if (ModelCatalog.isOfflineSpeech(speech) && (Build.VERSION.SDK_INT<31 ||
                 !SpeechRecognizer.isOnDeviceRecognitionAvailable(this))) {
            notice("อุปกรณ์นี้ไม่มีบริการรู้จำเสียง On-device ของ Android");
            return;
        }
        getSharedPreferences("hansub",MODE_PRIVATE).edit().putString("speech_mode",speech).apply();
        updateSelections(true);
        notice("ตั้งค่าเสียงแล้ว เริ่มใช้เมื่อเปิดการแปลครั้งถัดไป");
    }

    private void updateSelections(boolean animate) {
        String mode=getSharedPreferences("hansub",MODE_PRIVATE).getString("translation_mode",ModelCatalog.FAST);
        String speech=getSharedPreferences("hansub",MODE_PRIVATE).getString("speech_mode",ModelCatalog.SPEECH_DEFAULT);
        fastOption.setOn(ModelCatalog.FAST.equals(mode),animate);
        pivotOption.setOn(ModelCatalog.PIVOT.equals(mode),animate);
        cloudFastOption.setOn(ModelCatalog.CLOUD_FAST.equals(mode),animate);
        cloudQualityOption.setOn(ModelCatalog.CLOUD_QUALITY.equals(mode),animate);
        defaultSpeech.setOn(!ModelCatalog.isOfflineSpeech(speech),animate);
        offlineSpeech.setOn(ModelCatalog.isOfflineSpeech(speech),animate);
    }

    private void downloadSpeech() {
        if (Build.VERSION.SDK_INT<33 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            notice("เครื่องนี้ไม่รองรับการขอดาวน์โหลด Speech pack ผ่าน API นี้");
            return;
        }
        try {
            if (speechDownloader!=null) speechDownloader.destroy();
            speechDownloader=SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
            speechDownloader.setRecognitionListener(new SilentListener());
            Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,"zh-CN");
            notice("ส่งคำขอดาวน์โหลดโมเดลเสียงจีน…");
            if (Build.VERSION.SDK_INT>=34) {
                speechDownloader.triggerModelDownload(intent,this::runOnUiThread,new ModelDownloadListener(){
                    @Override public void onProgress(int progress) {notice("กำลังดาวน์โหลด Speech: "+progress+"%");}
                    @Override public void onSuccess() {notice("บริการระบบแจ้งว่า Speech model พร้อมใช้งานแล้ว");}
                    @Override public void onScheduled() {notice("ระบบตั้งคิวดาวน์โหลดแล้ว โปรดตรวจสอบภายหลัง");}
                    @Override public void onError(int error) {notice("ดาวน์โหลด Speech ไม่สำเร็จ รหัส "+error);}
                });
            } else {
                speechDownloader.triggerModelDownload(intent);
                notice("ส่งคำขอไปยังบริการเสียงแล้ว (Android 13 ไม่รายงานผลอัตโนมัติ)");
            }
        } catch (RuntimeException e) {
            notice("บริการเสียงไม่รองรับ: "+e.getClass().getSimpleName());
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (fastOption!=null) refreshPacks();
        if (performance!=null) showPerformance();
    }

    @Override public void finish() {
        super.finish();
        // Home opens its own bars on the way back, so the system slide would only fight with them.
        overridePendingTransition(0,0);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        notice.cancel();
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

    private int dp(float v) {return Glass.dp(this,v);}
}
