package th.hansub.live;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Looks for a newer build of HanSub. Every CI build publishes its versionCode in the notes of the
 * "preview-ui" release; this compares that number with the installed one. It only reads the release page
 * and never installs anything: the person opens the download link and installs it themselves.
 */
final class UpdateChecker {
    private UpdateChecker() {}

    static final int FAILED = -1, CURRENT = 0, NEWER = 1;
    static final String RELEASE_API = "https://api.github.com/repos/BunnyBubui/HanSub/releases/tags/preview-ui";
    static final String APK_URL = "https://github.com/BunnyBubui/HanSub/releases/download/preview-ui/HanSub-debug.apk";
    private static final Pattern CODE = Pattern.compile("versionCode:\\s*(\\d+)");

    interface Callback {
        void done(int status, long remoteCode, long localCode);
    }

    /** The number after "versionCode:" in release notes, or 0 when there is none. */
    static long parseVersionCode(String notes) {
        if (notes == null) return 0L;
        Matcher m = CODE.matcher(notes);
        if (!m.find()) return 0L;
        try {
            return Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    static int compare(long remote, long local) {
        if (remote <= 0L) return FAILED;
        return remote > local ? NEWER : CURRENT;
    }

    @SuppressWarnings("deprecation")
    static long installedCode(Context c) {
        try {
            PackageInfo info = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        } catch (Exception e) {
            return 0L;
        }
    }

    /** Runs the check on a background thread and calls back on the main thread. */
    static void check(Context context, Callback callback) {
        final Context app = context.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            long remote = 0L;
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(RELEASE_API).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                if (conn.getResponseCode() == 200) {
                    remote = parseVersionCode(new JSONObject(read(conn.getInputStream())).optString("body", ""));
                }
            } catch (Exception ignored) {
                remote = 0L;
            } finally {
                if (conn != null) conn.disconnect();
            }
            final long local = installedCode(app);
            final long found = remote;
            main.post(() -> callback.done(compare(found, local), found, local));
        }).start();
    }

    private static String read(InputStream in) throws Exception {
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) out.append(line).append('\n');
        }
        return out.toString();
    }
}
