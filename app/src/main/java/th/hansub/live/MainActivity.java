package th.hansub.live;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int REQUEST_MIC = 101, REQUEST_MIC_ROW = 102, REQUEST_NOTIFY = 103;
    private TextView status, micChip, overlayChip, notifyChip;
    private View statusDot;
    private boolean landscape;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        build();
    }

    @Override public void onResume() {
        super.onResume();
        refresh();
    }

    private void build() {
        landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;

        ScrollView page = new ScrollView(this);
        page.setFillViewport(true);
        page.setVerticalScrollBarEnabled(false);
        page.setBackground(Glass.page());
        // In landscape the content gets two columns and a width limit, so nothing stretches across the screen.
        Glass.Column column = new Glass.Column(this, landscape ? 940 : 560);
        int side = dp(landscape ? 24 : 22);
        column.setPadding(side, dp(24), side, dp(28));
        page.addView(column, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL));
        setContentView(page);
        Glass.fitSystemBars(page);

        LinearLayout intro = pane();
        buildIntro(intro);
        LinearLayout actions = pane();
        buildActions(actions);

        if (landscape) {
            LinearLayout both = new LinearLayout(this);
            both.setOrientation(LinearLayout.HORIZONTAL);
            both.addView(intro, new LinearLayout.LayoutParams(0, -2, 1f));
            both.addView(new View(this), new LinearLayout.LayoutParams(dp(24), 1));
            both.addView(actions, new LinearLayout.LayoutParams(0, -2, 1f));
            column.addView(both, new LinearLayout.LayoutParams(-1, -2));
        } else {
            column.addView(intro, new LinearLayout.LayoutParams(-1, -2));
            add(column, actions, 16);
        }
    }

    /** Brand, headline and the start/stop card. */
    private void buildIntro(LinearLayout pane) {
        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        TextView mark = Glass.text(this, "H", 18, Glass.ON_ACCENT, true);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(Glass.panel(this, 10, Glass.ACCENT));
        brand.addView(mark, new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, -2, 1f);
        nameParams.leftMargin = dp(12);
        brand.addView(Glass.text(this, "HanSub", 18, Glass.TEXT, true), nameParams);
        brand.addView(Glass.chip(this, "v" + versionName(), false));
        pane.addView(brand, new LinearLayout.LayoutParams(-1, -2));

        add(pane, Glass.text(this, "ดูหนังจีน\nพร้อมซับไทย", 32, Glass.TEXT, true), 26);
        add(pane, Glass.text(this,
                "ระบบแปลภาษาจีนกลางเป็นไทยแบบใกล้เรียลไทม์ พร้อมซับลอยบนหน้าจอ", 15, Glass.TEXT2, false), 10);

        LinearLayout card = card(18, 18, 18, 18);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        statusDot = new View(this);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(10), dp(10));
        dotParams.rightMargin = dp(8);
        head.addView(statusDot, dotParams);
        head.addView(Glass.text(this, "สถานะระบบ", 13, Glass.TEXT3, false));
        add(card, head, 0);
        status = Glass.text(this, "พร้อมเริ่มแปล", 20, Glass.TEXT, true);
        add(card, status, 8);

        Glass.Btn start = Glass.button(this, "เริ่มแปลและแสดงซับ", Glass.ACCENT, Glass.ON_ACCENT);
        start.setOnClickListener(v -> startTranslation());
        add(card, start, 18);
        Glass.Btn stop = Glass.button(this, "หยุดการแปล", Glass.FILL_STRONG, Glass.TEXT);
        stop.setOnClickListener(v -> stopTranslation());
        add(card, stop, 8);
        add(pane, card, 24);
    }

    /** Model manager link, permissions and the usage note. */
    private void buildActions(LinearLayout pane) {
        LinearLayout models = new LinearLayout(this);
        models.setGravity(Gravity.CENTER_VERTICAL);
        models.setPadding(dp(18), dp(16), dp(14), dp(16));
        models.setBackground(Glass.panel(this, 20, Glass.FILL));
        LinearLayout texts = pane();
        texts.addView(Glass.text(this, "AI Model Manager", 17, Glass.TEXT, true));
        add(texts, Glass.text(this,
                "เลือกโหมดแปลและดาวน์โหลด/ลบโมเดลจีน ไทย และอังกฤษได้จากในแอป", 13, Glass.TEXT2, false), 4);
        models.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        models.addView(Glass.text(this, "›", 28, Glass.TEXT3, false));
        models.setOnClickListener(v -> startActivity(new Intent(this, ModelManagerActivity.class)));
        Glass.pressable(models);
        pane.addView(models, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout perms = card(18, 16, 18, 4);
        perms.addView(Glass.text(this, "ตั้งค่าสิทธิ์", 17, Glass.TEXT, true));
        micChip = permissionRow(perms, "ไมโครโฟน", "ใช้ฟังเสียงพูด", v -> requestMic());
        overlayChip = permissionRow(perms, "แสดงซับทับแอปอื่น", "ให้ซับลอยอยู่บนหน้าจอหนัง",
                v -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()))));
        if (Build.VERSION.SDK_INT >= 33) {
            notifyChip = permissionRow(perms, "การแจ้งเตือน", "แสดงปุ่มหยุดในแถบแจ้งเตือน",
                    v -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFY));
        }
        add(pane, perms, 16);

        add(pane, Glass.text(this,
                "ใช้ไมโครโฟนรับเสียง (ไม่ใช่การดึงเสียงภายในแอปหนัง)\n"
                + "แนะนำให้ทดสอบกับลำโพงจากอีกเครื่องก่อน และดาวน์โหลดโมเดลจีน+ไทยล่วงหน้า\n"
                + "Powered by Google Translate • การรู้จำเสียงขึ้นอยู่กับบริการของ Android",
                12, Glass.TEXT3, false), 20);
    }

    /** One tappable row: title and hint on the left, a state chip on the right. Returns the chip. */
    private TextView permissionRow(LinearLayout parent, String title, String hint, View.OnClickListener click) {
        View divider = new View(this);
        divider.setBackgroundColor(Glass.alpha(Glass.TEXT, 0x14));
        add(parent, divider, 12, 1);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));
        LinearLayout texts = pane();
        texts.addView(Glass.text(this, title, 15, Glass.TEXT, false));
        add(texts, Glass.text(this, hint, 12, Glass.TEXT3, false), 2);
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView chip = Glass.chip(this, "…", false);
        LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(-2, -2);
        chipParams.leftMargin = dp(12);
        row.addView(chip, chipParams);
        row.setOnClickListener(click);
        Glass.pressable(row);
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return chip;
    }

    private void refresh() {
        if (status == null) return;
        boolean active = getSharedPreferences("hansub", MODE_PRIVATE).getBoolean("running", false);
        status.setText(active ? "กำลังฟังและแปลบทพูด" : "พร้อมเริ่มแปล");
        statusDot.setBackground(Glass.oval(active ? Glass.ACCENT : Glass.TEXT3));
        setPermissionChip(micChip, micGranted());
        setPermissionChip(overlayChip, Settings.canDrawOverlays(this));
        if (notifyChip != null) {
            setPermissionChip(notifyChip, checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED);
        }
    }

    private void setPermissionChip(TextView chip, boolean granted) {
        Glass.setChip(chip, granted ? "อนุญาตแล้ว" : "แตะเพื่ออนุญาต", granted);
    }

    private boolean micGranted() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestMic() {
        if (!micGranted()) requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MIC_ROW);
    }

    private void startTranslation() {
        if (!micGranted()) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MIC);
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            status.setText("โปรดอนุญาตซับลอยก่อน");
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        Intent start = new Intent(this, LiveTranslateService.class).setAction(LiveTranslateService.ACTION_START);
        try {
            startForegroundService(start);
            status.setText("กำลังเตรียมโมเดลแปลภาษา…");
        } catch (RuntimeException error) {
            status.setText("เปิดไม่ได้: " + error.getClass().getSimpleName());
        }
    }

    private void stopTranslation() {
        stopService(new Intent(this, LiveTranslateService.class));
        getSharedPreferences("hansub", MODE_PRIVATE).edit().putBoolean("running", false).apply();
        refresh();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        boolean granted = grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQUEST_MIC && granted) startTranslation();
        else refresh();
    }

    private String versionName() {
        try {
            String name = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return name == null ? "" : name;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return "";
        }
    }

    private LinearLayout pane() {
        LinearLayout pane = new LinearLayout(this);
        pane.setOrientation(LinearLayout.VERTICAL);
        return pane;
    }

    private LinearLayout card(int left, int top, int right, int bottom) {
        LinearLayout card = pane();
        card.setPadding(dp(left), dp(top), dp(right), dp(bottom));
        card.setBackground(Glass.panel(this, 20, Glass.FILL));
        return card;
    }

    private void add(LinearLayout parent, View view, int topMarginDp) {
        add(parent, view, topMarginDp, -2);
    }

    private void add(LinearLayout parent, View view, int topMarginDp, int heightPx) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, heightPx);
        params.topMargin = dp(topMarginDp);
        parent.addView(view, params);
    }

    private int dp(float v) { return Glass.dp(this, v); }
}
