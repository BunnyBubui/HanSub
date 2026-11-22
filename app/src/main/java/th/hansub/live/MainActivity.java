package th.hansub.live;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Home, in the "Bento" style of Lumora: a hero block with the start button, then tiles of different sizes.
 * The app opens with Lumora's film-bar entrance; the permission sheet grows out of the spot that was tapped.
 */
public class MainActivity extends Activity {
    private static final int REQUEST_MIC = 102, REQUEST_NOTIFY = 103, REQUEST_PROJECTION = 104;
    private static final int KIND_MIC = 0, KIND_OVERLAY = 1, KIND_NOTIFY = 2;
    private static boolean checkedForUpdate;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<View> blocks = new ArrayList<>();
    private final List<PermRow> permRows = new ArrayList<>();
    private final SharedPreferences.OnSharedPreferenceChangeListener runningListener = (p, key) -> {
        if ("running".equals(key)) refresh();
    };

    private boolean landscape, pendingStart, sheetOpen, updateAvailable;
    /** Consent to capture other apps' sound. It can be used once, so it is cleared after each start. */
    private int projectionCode;
    private Intent projectionData;
    private Boolean lastActive;
    private int bottomInset;
    private FrameLayout host;
    private Glass.Column column;
    private LogoView logo;
    private TextView status, modelNote, permNote, permBadge, updateNote, updateBadge;
    private View statusDot;
    private LinearLayout toggle, sheetDone;
    private FrameLayout sheetLayer;
    private Glass.Column sheet;
    private View scrim;
    private Notice notice;
    private Cine.Curtain shutter;
    private ObjectAnimator pulse;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        build(state == null);
    }

    // ---- layout --------------------------------------------------------------------------------

    private void build(boolean coldStart) {
        landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;

        host = new FrameLayout(this);
        host.setBackground(Glass.page());
        host.addView(new Glow(this), new FrameLayout.LayoutParams(-1, -1));
        ScrollView page = new ScrollView(this);
        page.setFillViewport(true);
        page.setVerticalScrollBarEnabled(false);
        // In landscape the content gets two columns and a width limit, so nothing stretches across the screen.
        column = new Glass.Column(this, landscape ? 940 : 560);
        int side = dp(landscape ? 24 : 18);
        column.setPadding(side, dp(14), side, dp(28));
        page.addView(column, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL));
        host.addView(page, new FrameLayout.LayoutParams(-1, -1));
        setContentView(host);
        notice = new Notice(host);
        Glass.fitSystemBars(page, (l, t, r, b) -> {
            bottomInset = b;
            notice.setBottomInset(b);
        });

        View header = buildHeader();
        View hero = buildHero();
        TextView modelTileNote = Glass.text(this, "", 12, Glass.TEXT3, false);
        modelNote = modelTileNote;
        permNote = Glass.text(this, "", 12, Glass.TEXT3, false);
        permBadge = Glass.chip(this, "", true);
        updateNote = Glass.text(this, "", 12, Glass.TEXT3, false);
        updateBadge = Glass.chip(this, "ใหม่", true);
        updateBadge.setVisibility(View.GONE);
        View.OnClickListener openModels = v -> openModels();
        View.OnClickListener openPerms = v -> openSheet(false);
        View.OnClickListener checkUpdate = v -> onUpdateTile();
        TextView footer = Glass.text(this,
                "รับเสียงจากไมค์หรือจากแอปที่เล่นอยู่ เลือกได้ในหน้าโมเดลและโหมดแปล\n"
                + "ดาวน์โหลดโมเดลจีน+ไทยล่วงหน้าเพื่อให้เริ่มแปลได้เร็ว\n"
                + "Powered by Google Translate • การรู้จำเสียงขึ้นอยู่กับบริการของ Android",
                12, Glass.TEXT3, false);

        if (landscape) {
            LinearLayout left = vertical();
            addTo(left, header, 0);
            addTo(left, hero, 18);
            LinearLayout right = vertical();
            addTo(right, wideTile(IconView.CHIP, "โมเดลและโหมดแปล", modelTileNote, null, openModels), 62);
            addTo(right, wideTile(IconView.SHIELD, "สิทธิ์การใช้งาน", permNote, permBadge, openPerms), 12);
            addTo(right, wideTile(IconView.UPDATE, "ตรวจอัปเดตแอป", updateNote, updateBadge, checkUpdate), 12);
            addTo(right, footer, 18);
            LinearLayout both = new LinearLayout(this);
            both.addView(left, new LinearLayout.LayoutParams(0, -2, 1.1f));
            both.addView(new View(this), new LinearLayout.LayoutParams(dp(20), 1));
            both.addView(right, new LinearLayout.LayoutParams(0, -2, 1f));
            column.addView(both, new LinearLayout.LayoutParams(-1, -2));
            blocks.add(header);
            blocks.add(hero);
            blocks.addAll(children(right));
        } else {
            addTo(column, header, 0);
            addTo(column, hero, 18);
            LinearLayout pair = new LinearLayout(this);
            View modelTile = squareTile(IconView.CHIP, "โมเดลและโหมดแปล", modelTileNote, null, openModels);
            View permTile = squareTile(IconView.SHIELD, "สิทธิ์การใช้งาน", permNote, permBadge, openPerms);
            LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, dp(132), 1f);
            a.rightMargin = dp(6);
            LinearLayout.LayoutParams b = new LinearLayout.LayoutParams(0, dp(132), 1f);
            b.leftMargin = dp(6);
            pair.addView(modelTile, a);
            pair.addView(permTile, b);
            addTo(column, pair, 12);
            addTo(column, wideTile(IconView.UPDATE, "ตรวจอัปเดตแอป", updateNote, updateBadge, checkUpdate), 12);
            addTo(column, footer, 18);
            blocks.add(header);
            blocks.add(hero);
            blocks.add(pair);
            blocks.add(children(column).get(3));
            blocks.add(footer);
        }
        buildSheet();
        refresh();
        refreshPermissionRows(false);

        if (coldStart && !Motion.reduced(this)) {
            for (View v : blocks) v.setAlpha(0f);
            logo.setProgress(0f);
            Cine.intro(this, "HANSUB", () -> {
                for (int i = 0; i < blocks.size(); i++) Motion.rise(blocks.get(i), 80L + i * 60L, dp(14), 480);
                // A slow push-in while the bars open, like a camera settling.
                column.setPivotX(column.getWidth() / 2f);
                column.setPivotY(0f);
                column.setScaleX(1.04f);
                column.setScaleY(1.04f);
                column.animate().scaleX(1f).scaleY(1f).setDuration(760).setInterpolator(Motion.SOFT).start();
                ValueAnimator draw = ValueAnimator.ofFloat(0f, 1f);
                draw.setDuration(1000);
                draw.setStartDelay(160);
                draw.setInterpolator(new PathInterpolator(0.3f, 0f, 0.3f, 1f));
                draw.addUpdateListener(an -> logo.setProgress((float) an.getAnimatedValue()));
                draw.start();
            });
        }
        if (coldStart) {
            handler.postDelayed(this::firstRunPermissions, Motion.reduced(this) ? 300 : 1500);
            silentUpdateCheck();
        }
    }

    private View buildHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        logo = new LogoView(this);
        row.addView(logo, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, -2, 1f);
        nameParams.leftMargin = dp(8);
        row.addView(Glass.text(this, "HanSub", 22, Glass.TEXT, true), nameParams);
        row.addView(Glass.chip(this, "v" + versionName(), false));
        return row;
    }

    private View buildHero() {
        LinearLayout hero = vertical();
        hero.setPadding(dp(20), dp(20), dp(20), dp(20));
        hero.setBackground(Glass.panel(this, 24, Glass.FILL));

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusDot = new View(this);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(10), dp(10));
        dotParams.rightMargin = dp(8);
        statusRow.addView(statusDot, dotParams);
        status = Glass.text(this, "พร้อมเริ่มแปล", 14, Glass.TEXT2, true);
        statusRow.addView(status);
        addTo(hero, statusRow, 0);
        addTo(hero, Glass.text(this, "ดูหนังจีน\nพร้อมซับไทย", 30, Glass.TEXT, true), 14);
        addTo(hero, Glass.text(this,
                "แปลภาษาจีนกลางเป็นไทยแบบใกล้เรียลไทม์ พร้อมซับลอยบนหน้าจอ", 14, Glass.TEXT2, false), 8);

        toggle = Glass.action(this, IconView.PLAY, "เริ่มแปลและแสดงซับ", Glass.ACCENT, Glass.ON_ACCENT);
        toggle.setOnClickListener(v -> {
            if (isRunning()) stopTranslation();
            else startTranslation();
        });
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, dp(58));
        tp.topMargin = dp(22);
        hero.addView(toggle, tp);
        return hero;
    }

    /** A tile with an icon at the top left, the name and a note at the bottom. */
    private FrameLayout squareTile(int icon, String name, TextView note, TextView badge, View.OnClickListener click) {
        FrameLayout t = new FrameLayout(this);
        t.setBackground(Glass.panel(this, 18, Glass.PANEL));
        t.setClickable(true);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(dp(38), dp(38), Gravity.TOP | Gravity.START);
        ip.setMargins(dp(14), dp(14), 0, 0);
        t.addView(iconChip(icon), ip);
        if (badge != null) {
            FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            bp.setMargins(0, dp(14), dp(12), 0);
            t.addView(badge, bp);
        }
        LinearLayout words = vertical();
        words.addView(Glass.text(this, name, 15, Glass.TEXT, true));
        note.setMaxLines(2);
        words.addView(note);
        FrameLayout.LayoutParams wp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.START);
        wp.setMargins(dp(14), 0, dp(10), dp(14));
        t.addView(words, wp);
        t.setOnClickListener(click);
        Motion.touchable(t);
        return t;
    }

    /** A wide tile: icon, name and note in a row, with an arrow at the end. */
    private LinearLayout wideTile(int icon, String name, TextView note, TextView badge, View.OnClickListener click) {
        LinearLayout t = new LinearLayout(this);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(14), dp(14), dp(12), dp(14));
        t.setBackground(Glass.panel(this, 18, Glass.PANEL));
        t.setClickable(true);
        t.addView(iconChip(icon), new LinearLayout.LayoutParams(dp(38), dp(38)));
        LinearLayout words = vertical();
        words.addView(Glass.text(this, name, 15, Glass.TEXT, true));
        note.setMaxLines(2);
        words.addView(note);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, -2, 1f);
        wp.leftMargin = dp(14);
        t.addView(words, wp);
        if (badge != null) {
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
            bp.rightMargin = dp(6);
            t.addView(badge, bp);
        }
        IconView arrow = new IconView(this, IconView.CHEVRON);
        arrow.setTint(Glass.TEXT3);
        t.addView(arrow, new LinearLayout.LayoutParams(dp(20), dp(20)));
        t.setOnClickListener(click);
        Motion.touchable(t);
        return t;
    }

    private View iconChip(int icon) {
        FrameLayout chip = new FrameLayout(this);
        chip.setBackground(Glass.panel(this, 11, Glass.ACCENT_SOFT));
        IconView v = new IconView(this, icon);
        v.setTint(Glass.ACCENT);
        chip.addView(v, new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        return chip;
    }

    // ---- permission sheet ----------------------------------------------------------------------

    /** One row of the sheet: what the permission is for, and a button or a tick. */
    private final class PermRow {
        final int kind;
        final LinearLayout view;
        final Glass.Btn grant;
        final TextView done;
        private boolean wasGranted;

        PermRow(int kind, int icon, String title, String hint) {
            this.kind = kind;
            view = new LinearLayout(MainActivity.this);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setPadding(0, dp(12), 0, dp(12));
            view.addView(iconChip(icon), new LinearLayout.LayoutParams(dp(38), dp(38)));
            LinearLayout words = vertical();
            words.addView(Glass.text(MainActivity.this, title, 15, Glass.TEXT, true));
            words.addView(Glass.text(MainActivity.this, hint, 12, Glass.TEXT3, false));
            LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, -2, 1f);
            wp.leftMargin = dp(14);
            view.addView(words, wp);
            FrameLayout slot = new FrameLayout(MainActivity.this);
            grant = Glass.button(MainActivity.this, "อนุญาต", Glass.ACCENT, Glass.ON_ACCENT);
            grant.setMinHeight(dp(38));
            grant.setTextSize(13);
            grant.setOnClickListener(v -> requestPermission(kind));
            done = Glass.chip(MainActivity.this, "อนุญาตแล้ว", true);
            slot.addView(grant, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL | Gravity.END));
            slot.addView(done, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL | Gravity.END));
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
            sp.leftMargin = dp(10);
            view.addView(slot, sp);
            wasGranted = granted();
            update(false);
        }

        boolean granted() {
            switch (kind) {
                case KIND_MIC: return micGranted();
                case KIND_OVERLAY: return Settings.canDrawOverlays(MainActivity.this);
                default: return notifyGranted();
            }
        }

        void update(boolean animate) {
            boolean ok = granted();
            grant.setVisibility(ok ? View.GONE : View.VISIBLE);
            done.setVisibility(ok ? View.VISIBLE : View.GONE);
            if (animate && ok && !wasGranted) {
                Motion.pop(done);
                Motion.tick(done);
            }
            wasGranted = ok;
        }
    }

    private void buildSheet() {
        sheetLayer = new FrameLayout(this);
        sheetLayer.setVisibility(View.GONE);
        scrim = new View(this);
        scrim.setBackgroundColor(0xff000000);
        scrim.setOnClickListener(v -> closeSheet());
        sheetLayer.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        sheet = new Glass.Column(this, 480);
        sheet.setPadding(dp(20), dp(12), dp(20), dp(20));
        sheet.setBackground(Glass.panel(this, 26, Glass.FILL_POP));
        sheet.setClickable(true);
        View grip = new View(this);
        grip.setBackground(Glass.oval(Glass.alpha(Glass.TEXT, 0x40)));
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(36), dp(4));
        gp.gravity = Gravity.CENTER_HORIZONTAL;
        sheet.addView(grip, gp);
        addTo(sheet, Glass.text(this, "ตั้งค่าสิทธิ์", 20, Glass.TEXT, true), 14);
        addTo(sheet, Glass.text(this,
                "HanSub ต้องใช้สิทธิ์เหล่านี้เพื่อฟังเสียงและแสดงซับลอยทับแอปอื่น", 13, Glass.TEXT2, false), 4);

        permRows.add(new PermRow(KIND_MIC, IconView.MIC, "ไมโครโฟน", "ใช้ฟังเสียงพูดภาษาจีน"));
        permRows.add(new PermRow(KIND_OVERLAY, IconView.WINDOW, "แสดงทับแอปอื่น", "ให้ซับลอยอยู่บนหน้าจอหนัง"));
        if (Build.VERSION.SDK_INT >= 33) {
            permRows.add(new PermRow(KIND_NOTIFY, IconView.BELL, "การแจ้งเตือน",
                    "ไม่บังคับ แต่ช่วยให้เห็นปุ่มหยุดในแถบแจ้งเตือน"));
        }
        for (int i = 0; i < permRows.size(); i++) addTo(sheet, permRows.get(i).view, i == 0 ? 8 : 0);

        sheetDone = Glass.action(this, IconView.CHECK, "เสร็จสิ้น", Glass.ACCENT, Glass.ON_ACCENT);
        sheetDone.setOnClickListener(v -> {
            boolean start = pendingStart && requiredGranted();
            closeSheet();
            if (start) startTranslation();
        });
        LinearLayout.LayoutParams doneParams = new LinearLayout.LayoutParams(-1, dp(54));
        doneParams.topMargin = dp(12);
        sheet.addView(sheetDone, doneParams);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.leftMargin = dp(12);
        lp.rightMargin = dp(12);
        sheetLayer.addView(sheet, lp);
        host.addView(sheetLayer, new FrameLayout.LayoutParams(-1, -1));
    }

    private void openSheet(boolean forStart) {
        pendingStart = forStart;
        if (sheetOpen) return;
        sheetOpen = true;
        refreshPermissionRows(false);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) sheet.getLayoutParams();
        lp.bottomMargin = dp(12) + bottomInset;
        sheet.setLayoutParams(lp);
        sheetLayer.setVisibility(View.VISIBLE);
        scrim.setAlpha(0f);
        if (Motion.reduced(this)) scrim.setAlpha(0.6f);
        else scrim.animate().alpha(0.6f).setDuration(260).start();
        sheet.setVisibility(View.INVISIBLE);
        sheet.post(() -> {
            float[] tap = Motion.TapPoint.inside(sheet);
            float w = sheet.getWidth();
            float px = tap != null ? Math.max(0f, Math.min(w, tap[0])) : w / 2f;
            Motion.show(sheet, px, sheet.getHeight(), true, null);
        });
    }

    private void closeSheet() {
        if (!sheetOpen) return;
        sheetOpen = false;
        pendingStart = false;
        if (Motion.reduced(this)) scrim.setAlpha(0f);
        else scrim.animate().alpha(0f).setDuration(240).start();
        Motion.hide(sheet, sheet.getWidth() / 2f, sheet.getHeight(), true, () -> sheetLayer.setVisibility(View.GONE));
    }

    @Override public void onBackPressed() {
        if (sheetOpen) closeSheet();
        else super.onBackPressed();
    }

    private void refreshPermissionRows(boolean animate) {
        for (PermRow row : permRows) row.update(animate);
        boolean ready = requiredGranted();
        if (sheetDone != null) {
            Glass.setAction(sheetDone, pendingStart && ready ? IconView.PLAY : IconView.CHECK,
                    pendingStart && ready ? "เริ่มแปลเลย" : "เสร็จสิ้น", Glass.ACCENT, Glass.ON_ACCENT);
        }
    }

    /** The first time the app opens with something missing, the sheet offers to set it up. Never again after that. */
    private void firstRunPermissions() {
        SharedPreferences prefs = getSharedPreferences("hansub", MODE_PRIVATE);
        if (isDestroyed() || prefs.getBoolean("perm_prompted", false) || requiredGranted()) return;
        prefs.edit().putBoolean("perm_prompted", true).apply();
        openSheet(false);
    }

    private void requestPermission(int kind) {
        switch (kind) {
            case KIND_MIC:
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MIC);
                break;
            case KIND_OVERLAY:
                openOverlaySettings();
                break;
            default:
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFY);
                break;
        }
    }

    /**
     * Opens the "display over other apps" page for this app. If the phone has no page for it, tries the
     * general page, then the app's own settings, and finally tells the person where to look.
     */
    private void openOverlaySettings() {
        Uri self = Uri.parse("package:" + getPackageName());
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, self));
            return;
        } catch (RuntimeException ignored) {
            // fall through to the next page
        }
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            return;
        } catch (RuntimeException ignored) {
            // fall through to the next page
        }
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, self));
            notice.show("เปิดแอป HanSub แล้วเลือก 'แสดงทับแอปอื่น' เพื่ออนุญาต");
        } catch (RuntimeException e) {
            notice.show("เปิดหน้าตั้งค่าไม่ได้ ไปที่ ตั้งค่า > แอป > HanSub > แสดงทับแอปอื่น");
        }
    }

    // ---- state ---------------------------------------------------------------------------------

    private SharedPreferences prefs() {
        return getSharedPreferences("hansub", MODE_PRIVATE);
    }

    private boolean isRunning() {
        return prefs().getBoolean("running", false);
    }

    private boolean micGranted() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean notifyGranted() {
        return Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean requiredGranted() {
        return micGranted() && Settings.canDrawOverlays(this);
    }

    private int missingRequired() {
        return (micGranted() ? 0 : 1) + (Settings.canDrawOverlays(this) ? 0 : 1);
    }

    private String modeLabel() {
        String mode = ModelCatalog.normalizeMode(prefs().getString("translation_mode", ModelCatalog.FAST));
        if (ModelCatalog.PIVOT.equals(mode)) return "Pivot";
        if (ModelCatalog.CLOUD_FAST.equals(mode)) return "Cloud Lite";
        if (ModelCatalog.CLOUD_QUALITY.equals(mode)) return "Cloud Quality";
        return "Fast";
    }

    private void refresh() {
        if (status == null) return;
        boolean active = isRunning();
        status.setText(active ? "กำลังฟังและแปลบทพูด" : "พร้อมเริ่มแปล");
        statusDot.setBackground(Glass.oval(active ? Glass.ACCENT : Glass.TEXT3));
        if (lastActive == null || lastActive != active) {
            boolean first = lastActive == null;
            lastActive = active;
            Glass.setAction(toggle, active ? IconView.STOP : IconView.PLAY,
                    active ? "หยุดการแปล" : "เริ่มแปลและแสดงซับ",
                    active ? Glass.FILL_STRONG : Glass.ACCENT, active ? Glass.TEXT : Glass.ON_ACCENT);
            if (!first) Motion.pop(toggle);
        }
        pulse(active);
        modelNote.setText("โหมดตอนนี้: " + modeLabel());
        int missing = missingRequired();
        permNote.setText(missing == 0 ? "ครบแล้ว พร้อมใช้งาน" : "ยังขาด " + missing + " รายการ แตะเพื่อตั้งค่า");
        permBadge.setVisibility(missing == 0 ? View.GONE : View.VISIBLE);
        Glass.setChip(permBadge, "ขาด " + missing, true);
        if (!updateAvailable) {
            updateNote.setText("เวอร์ชัน " + versionName() + " (" + UpdateChecker.installedCode(this) + ")");
        }
    }

    /** The status dot breathes while the app is listening. */
    private void pulse(boolean active) {
        if (active && !Motion.reduced(this)) {
            if (pulse == null) {
                pulse = ObjectAnimator.ofFloat(statusDot, "alpha", 1f, 0.3f);
                pulse.setDuration(900);
                pulse.setRepeatCount(ValueAnimator.INFINITE);
                pulse.setRepeatMode(ValueAnimator.REVERSE);
            }
            if (!pulse.isStarted()) pulse.start();
        } else {
            if (pulse != null) {
                pulse.cancel();
                pulse = null;
            }
            statusDot.setAlpha(1f);
        }
    }

    private void startTranslation() {
        if (!requiredGranted()) {
            // Ask for what is missing in one place, then carry on by itself once it is all granted.
            openSheet(true);
            return;
        }
        pendingStart = false;
        if (wantsPlayback() && projectionData == null) {
            askForScreenCapture();
            return;
        }
        Intent start = new Intent(this, LiveTranslateService.class).setAction(LiveTranslateService.ACTION_START);
        if (projectionData != null) {
            start.putExtra("projection_code", projectionCode);
            start.putExtra("projection_data", projectionData);
            projectionData = null;
        }
        try {
            startForegroundService(start);
            status.setText("กำลังเตรียมโมเดลแปลภาษา…");
        } catch (RuntimeException error) {
            status.setText("เปิดไม่ได้: " + error.getClass().getSimpleName());
        }
    }

    private boolean wantsPlayback() {
        return Build.VERSION.SDK_INT >= 29
                && "playback".equals(prefs().getString("audio_path", "own"));
    }

    /** Android asks for screen-capture consent before an app may record the sound other apps play. */
    private void askForScreenCapture() {
        try {
            android.media.projection.MediaProjectionManager manager =
                    getSystemService(android.media.projection.MediaProjectionManager.class);
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_PROJECTION);
        } catch (RuntimeException e) {
            notice.show("เปิดหน้าขอสิทธิ์จับเสียงไม่ได้ ลองเปลี่ยนเป็นโหมดไมค์");
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PROJECTION) return;
        if (resultCode == RESULT_OK && data != null) {
            projectionCode = resultCode;
            projectionData = data;
            startTranslation();
        } else {
            notice.show("ต้องอนุญาตก่อนจึงจะจับเสียงจากแอปอื่นได้ หรือเปลี่ยนเป็นโหมดไมค์");
        }
    }

    private void stopTranslation() {
        stopService(new Intent(this, LiveTranslateService.class));
        prefs().edit().putBoolean("running", false).apply();
        refresh();
    }

    private void onPermissionsChanged() {
        refreshPermissionRows(true);
        refresh();
        if (pendingStart && requiredGranted()) {
            closeSheet();
            startTranslation();
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        onPermissionsChanged();
    }

    // ---- pages and updates ---------------------------------------------------------------------

    /** Home to the Model Manager: the bars shut, the next page starts under them and opens them. */
    private void openModels() {
        Runnable go = () -> {
            try {
                startActivity(new Intent(this, ModelManagerActivity.class));
                overridePendingTransition(0, 0);
            } catch (RuntimeException e) {
                if (shutter != null) {
                    shutter.open(320);
                    shutter = null;
                }
            }
        };
        if (Motion.reduced(this)) {
            go.run();
            return;
        }
        if (shutter != null) return;
        shutter = Cine.leave(this, "MODELS", go);
    }

    @Override protected void onRestart() {
        super.onRestart();
        // Back from another page: the bars that shut on the way out open again.
        if (shutter != null) {
            shutter.open(320);
            shutter = null;
        }
    }

    private void onUpdateTile() {
        if (updateAvailable) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.APK_URL)));
            } catch (RuntimeException e) {
                notice.show("เปิดลิงก์ดาวน์โหลดไม่ได้");
            }
            return;
        }
        updateNote.setText("กำลังตรวจ…");
        UpdateChecker.check(this, (result, remote, local) -> showUpdateResult(result, remote, true));
    }

    private void silentUpdateCheck() {
        if (checkedForUpdate) return;
        checkedForUpdate = true;
        UpdateChecker.check(this, (result, remote, local) -> {
            if (result == UpdateChecker.NEWER) showUpdateResult(result, remote, false);
        });
    }

    private void showUpdateResult(int result, long remote, boolean tellUser) {
        if (isDestroyed() || updateNote == null) return;
        if (result == UpdateChecker.NEWER) {
            updateAvailable = true;
            updateNote.setText("มีเวอร์ชันใหม่ (" + remote + ") แตะเพื่อดาวน์โหลด");
            updateBadge.setVisibility(View.VISIBLE);
            if (tellUser) Motion.pop(updateBadge);
        } else if (result == UpdateChecker.CURRENT) {
            updateNote.setText("เป็นเวอร์ชันล่าสุดแล้ว");
        } else {
            updateNote.setText("ตรวจไม่ได้ ลองใหม่ภายหลัง");
        }
    }

    // ---- lifecycle -----------------------------------------------------------------------------

    @Override protected void onStart() {
        super.onStart();
        prefs().registerOnSharedPreferenceChangeListener(runningListener);
    }

    @Override public void onResume() {
        super.onResume();
        // Back from the Settings page or a permission dialog: pick up what changed, and go on if a start was waiting.
        onPermissionsChanged();
    }

    @Override protected void onStop() {
        prefs().unregisterOnSharedPreferenceChangeListener(runningListener);
        pulse(false);
        super.onStop();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (notice != null) notice.cancel();
        super.onDestroy();
    }

    // ---- helpers -------------------------------------------------------------------------------

    private String versionName() {
        try {
            String name = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return name == null ? "" : name;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return "";
        }
    }

    private LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private List<View> children(LinearLayout parent) {
        List<View> list = new ArrayList<>();
        for (int i = 0; i < parent.getChildCount(); i++) list.add(parent.getChildAt(i));
        return list;
    }

    private void addTo(LinearLayout parent, View view, int topMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(topMarginDp);
        parent.addView(view, params);
    }

    private int dp(float v) {
        return Glass.dp(this, v);
    }

    /** One still, faint light in the accent colour at the top right, like Lumora's home. */
    private static final class Glow extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix matrix = new Matrix();

        Glow(Context c) {
            super(c);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            float rx = w * 0.9f, ry = h * 0.32f;
            RadialGradient shader = new RadialGradient(0f, 0f, 1f, Glass.alpha(Glass.ACCENT, 0x1c), 0x00000000,
                    Shader.TileMode.CLAMP);
            matrix.setScale(rx, ry);
            matrix.postTranslate(w * 0.85f, 0f);
            shader.setLocalMatrix(matrix);
            paint.setShader(shader);
        }

        @Override protected void onDraw(Canvas canvas) {
            canvas.drawRect(0f, 0f, getWidth(), getHeight(), paint);
        }
    }
}
