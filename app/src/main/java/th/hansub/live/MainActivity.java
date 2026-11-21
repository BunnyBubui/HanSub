package th.hansub.live;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int BG = 0xff12151b, CARD = 0xff21252d, ACCENT = 0xfff4bd7e;
    private static final int MUTED = 0xffb7bdc8;
    private static final int REQUEST_MIC = 101;
    private TextView status;
    private LinearLayout body;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        build();
    }

    @Override public void onResume() {
        super.onResume();
        if (status != null) {
            boolean active = getSharedPreferences("hansub", MODE_PRIVATE).getBoolean("running", false);
            status.setText(active ? "กำลังฟังและแปลบทพูด" : "พร้อมเริ่มแปล");
        }
    }

    private void build() {
        ScrollView page = new ScrollView(this);
        page.setFillViewport(true);
        page.setBackgroundColor(BG);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(22),dp(44),dp(22),dp(30));
        page.addView(body);
        setContentView(page);
        if (Build.VERSION.SDK_INT >= 35) {
            page.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                body.setPadding(dp(22),bars.top+dp(24),dp(22),bars.bottom+dp(30));
                return insets;
            });
            page.requestApplyInsets();
        }

        add("H  /  HAN SUB",15,ACCENT,true);
        space(18);
        add("ดูหนังจีน\nพร้อมซับไทย",31,Color.WHITE,true);
        space(12);
        add("ระบบแปลภาษาจีนกลางเป็นไทยแบบใกล้เรียลไทม์ พร้อมซับลอยบนหน้าจอ",15,MUTED,false);
        space(22);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16),dp(18),dp(16),dp(18));
        card.setBackground(round(CARD));
        body.addView(card,new LinearLayout.LayoutParams(-1,-2));
        card.addView(label("สถานะระบบ",13,ACCENT,true));
        status=label("พร้อมเริ่มแปล",19,Color.WHITE,true);
        LinearLayout.LayoutParams statusParams=new LinearLayout.LayoutParams(-1,-2);
        statusParams.topMargin=dp(10);
        card.addView(status,statusParams);
        card.addView(spacer(16));
        Button start=button("เริ่มแปลและแสดงซับ",ACCENT,BG);
        start.setOnClickListener(v -> startTranslation());
        card.addView(start);
        card.addView(spacer(8));
        Button stop=button("หยุดการแปล",0xff404650,Color.WHITE);
        stop.setOnClickListener(v -> {
            stopService(new Intent(this,LiveTranslateService.class));
            getSharedPreferences("hansub",MODE_PRIVATE).edit().putBoolean("running",false).apply();
            onResume();
        });
        card.addView(stop);
        space(20);

        add("AI Model Manager",20,Color.WHITE,true);
        space(8);
        add("เลือกโหมดแปลและดาวน์โหลด/ลบโมเดลจีน ไทย และอังกฤษได้จากในแอป",14,MUTED,false);
        space(12);
        Button models=button("จัดการโมเดลและโหมดการแปล",ACCENT,BG);
        models.setOnClickListener(v -> startActivity(new Intent(this,ModelManagerActivity.class)));
        body.addView(models);
        space(18);

        add("ตั้งค่าสิทธิ์",19,Color.WHITE,true);
        space(10);
        Button overlay=button("อนุญาตแสดงซับทับแอปอื่น",0xff303640,Color.WHITE);
        overlay.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:"+getPackageName()))));
        body.addView(overlay);
        if (Build.VERSION.SDK_INT>=33) {
            space(8);
            Button notify=button("อนุญาตการแจ้งเตือน",0xff303640,Color.WHITE);
            notify.setOnClickListener(v -> requestPermissions(
                new String[]{Manifest.permission.POST_NOTIFICATIONS},103));
            body.addView(notify);
        }

        space(25);
        add("ใช้ไมโครโฟนรับเสียง (ไม่ใช่การดึงเสียงภายในแอปหนัง)\n"
            +"แนะนำให้ทดสอบกับลำโพงจากอีกเครื่องก่อน และดาวน์โหลดโมเดลจีน+ไทยล่วงหน้า\n"
            +"Powered by Google Translate • การรู้จำเสียงขึ้นอยู่กับบริการของ Android",12,MUTED,false);
    }

    private void startTranslation() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},REQUEST_MIC);
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            status.setText("โปรดอนุญาตซับลอยก่อน");
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:"+getPackageName())));
            return;
        }
        Intent start=new Intent(this,LiveTranslateService.class).setAction(LiveTranslateService.ACTION_START);
        try {
            startForegroundService(start);
            status.setText("กำลังเตรียมโมเดลแปลภาษา…");
        } catch (RuntimeException error) {
            status.setText("เปิดไม่ได้: "+error.getClass().getSimpleName());
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grants) {
        super.onRequestPermissionsResult(requestCode,permissions,grants);
        if (requestCode==REQUEST_MIC && grants.length>0
                && grants[0]==PackageManager.PERMISSION_GRANTED) startTranslation();
    }

    private void add(String text,int sp,int color,boolean bold) {
        body.addView(label(text,sp,color,bold));
    }
    private TextView label(String text,int sp,int color,boolean bold) {
        TextView t=new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(sp);
        t.setLineSpacing(dp(3),1f);
        t.setTypeface(Typeface.create("sans-serif",bold?Typeface.BOLD:Typeface.NORMAL));
        return t;
    }
    private Button button(String name,int bg,int fg) {
        Button b=new Button(this);
        b.setText(name);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(fg);
        b.setMinHeight(dp(52));
        b.setBackground(round(bg));
        return b;
    }
    private GradientDrawable round(int color) {
        GradientDrawable d=new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(14));
        return d;
    }
    private void space(int height) {body.addView(spacer(height));}
    private View spacer(int height) {
        View v=new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1,dp(height)));
        return v;
    }
    private int dp(float v) {return Math.round(v*getResources().getDisplayMetrics().density);}
}
