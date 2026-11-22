package th.hansub.live;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * The Hy-MT2-1.8B translation model file (Tencent, GGUF, 4-bit Q4_K_M, about 1.13 GB). Too big for the APK, so the
 * app downloads it once. A half-finished download is kept as ".part" and continued with an HTTP range request.
 */
final class TranslatorModel {
    static final String NAME = "Hy-MT2-1.8B (Tencent) Q4_K_M";
    static final String FILE = "Hy-MT2-1.8B-Q4_K_M.gguf";
    private static final String URL =
            "https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF/resolve/main/" + FILE;
    /** A complete file is about 1.13 GB; anything much smaller is an error page or a cut-off download. */
    private static final long MIN_SIZE = 1_000_000_000L;

    static final int IDLE = 0, RUNNING = 1, DONE = 2, FAILED = 3;
    static volatile int state = IDLE;
    static volatile long doneBytes, totalBytes;
    static volatile String error = "";
    private static volatile boolean cancel;

    private TranslatorModel() {}

    static File file(Context c) {
        return new File(new File(c.getFilesDir(), "mt"), FILE);
    }

    static boolean ready(Context c) {
        File f = file(c);
        return f.isFile() && f.length() >= MIN_SIZE;
    }

    static long sizeOnDisk(Context c) {
        File f = file(c);
        return f.isFile() ? f.length() : 0;
    }

    static synchronized void download(Context context) {
        if (state == RUNNING) return;
        Context app = context.getApplicationContext();
        state = RUNNING;
        cancel = false;
        error = "";
        doneBytes = 0;
        totalBytes = 0;
        new Thread(() -> {
            try {
                File target = file(app);
                File dir = target.getParentFile();
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("สร้างโฟลเดอร์ไม่ได้");
                if (!ready(app)) fetch(target);
                state = DONE;
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                state = FAILED;
            }
        }, "hansub-mt-download").start();
    }

    static void cancel() {
        cancel = true;
    }

    static void delete(Context c) {
        cancel = true;
        File f = file(c);
        f.delete();
        new File(f.getPath() + ".part").delete();
        state = IDLE;
    }

    private static void fetch(File target) throws IOException {
        File part = new File(target.getPath() + ".part");
        long have = part.isFile() ? part.length() : 0;
        HttpURLConnection http = (HttpURLConnection) new URL(URL).openConnection();
        http.setConnectTimeout(20000);
        http.setReadTimeout(30000);
        http.setInstanceFollowRedirects(true);
        if (have > 0) http.setRequestProperty("Range", "bytes=" + have + "-");
        int code = http.getResponseCode();
        boolean append = code == 206;
        if (code != 200 && code != 206) {
            http.disconnect();
            throw new IOException("ดาวน์โหลดไม่ได้ (HTTP " + code + ")");
        }
        if (!append) have = 0;
        long length = http.getContentLengthLong();
        totalBytes = length > 0 ? have + length : 0;
        doneBytes = have;
        byte[] buf = new byte[256 * 1024];
        try (InputStream in = http.getInputStream(); OutputStream out = new FileOutputStream(part, append)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                if (cancel) throw new IOException("ยกเลิกแล้ว");
                out.write(buf, 0, n);
                doneBytes += n;
            }
        } finally {
            http.disconnect();
        }
        if (part.length() < MIN_SIZE) {
            // Keep a long partial file so the next try continues from it; a tiny one is an error page.
            if (part.length() < 1_000_000L) part.delete();
            throw new IOException("ไฟล์ยังไม่ครบ");
        }
        if (target.exists()) target.delete();
        if (!part.renameTo(target)) throw new IOException("บันทึกไฟล์ไม่ได้");
    }
}
