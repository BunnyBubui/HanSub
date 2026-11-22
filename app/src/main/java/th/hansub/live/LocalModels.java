package th.hansub.live;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;

/**
 * The on-device speech model files: SenseVoice (Chinese speech to text) and Silero VAD (finds where
 * speech starts and stops). They are too big for the APK, so the app downloads them once.
 *
 * Downloads resume: a half-finished file is kept as ".part" and continued with an HTTP range request.
 */
final class LocalModels {
    static final String NAME = "SenseVoice Small int8 (2025-09-09)";
    private static final String DIR = "asr/sensevoice-int8-2025-09-09";
    private static final String HF =
            "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09/resolve/main/";
    static final String MODEL = "model.int8.onnx", TOKENS = "tokens.txt", VAD = "silero_vad.onnx";
    /** 3D-Speaker CAM++: a voice fingerprint, used to tell speakers apart. Optional. */
    static final String SPEAKER = "campplus_sv_zh-cn_16k-common.onnx";
    private static final String[] SPEAKER_FILE = {SPEAKER,
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/"
                    + "3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx", "5000000"};

    private static String[] vadFile() {
        return new String[] {VAD, "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
                "500000", DIR};
    }

    /** File name, address, and the smallest size a complete file can have (rejects error pages). */
    private static final String[][] FILES = {
            {TOKENS, HF + TOKENS, "20000"},
            {VAD, "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx", "500000"},
            {MODEL, HF + MODEL, "100000000"},
    };

    /** The other speech model: Zipformer-CTC for Chinese, more exact than SenseVoice on fast speech in tests. */
    static final String ZIP_NAME = "Zipformer-CTC จีน int8 (2025-07-03)";
    private static final String ZIP_DIR = "asr/zipformer-ctc-zh-int8-2025-07-03";
    private static final String ZIP_HF =
            "https://huggingface.co/csukuangfj/sherpa-onnx-zipformer-ctc-zh-int8-2025-07-03/resolve/main/";
    /** Same four columns as FILES, plus the folder the file goes in. */
    private static final String[][] ZIP_FILES = {
            {TOKENS, ZIP_HF + TOKENS, "10000", ZIP_DIR},
            {MODEL, ZIP_HF + MODEL, "300000000", ZIP_DIR},
    };
    /** Silero VAD is shared by both models and lives with SenseVoice. */
    private static final String[] VAD_FILE = vadFile();

    static final int IDLE = 0, RUNNING = 1, DONE = 2, FAILED = 3;
    static volatile int state = IDLE;
    static volatile long doneBytes, totalBytes;
    static volatile String error = "";
    private static volatile boolean cancel;

    private LocalModels() {}

    static File dir(Context c) {
        return new File(c.getFilesDir(), DIR);
    }

    static File file(Context c, String name) {
        return new File(dir(c), name);
    }

    /** A file of the Zipformer-CTC model (model.int8.onnx or tokens.txt). */
    static File zipFile(Context c, String name) {
        return new File(new File(c.getFilesDir(), ZIP_DIR), name);
    }

    /** True when the person chose Zipformer-CTC in the Model Manager. */
    static boolean useZip(Context c) {
        return "zip".equals(c.getSharedPreferences("hansub", Context.MODE_PRIVATE).getString("local_model", "sense"));
    }

    static boolean zipReady(Context c) {
        for (String[] f : ZIP_FILES) {
            File file = new File(new File(c.getFilesDir(), f[3]), f[0]);
            if (!file.isFile() || file.length() < Long.parseLong(f[2])) return false;
        }
        File vad = file(c, VAD);
        return vad.isFile() && vad.length() >= Long.parseLong(VAD_FILE[2]);
    }

    /** Whether the model the person chose has all its files. */
    static boolean activeReady(Context c) {
        return useZip(c) ? zipReady(c) : ready(c);
    }

    static long zipSizeOnDisk(Context c) {
        long sum = 0;
        File[] files = new File(c.getFilesDir(), ZIP_DIR).listFiles();
        if (files != null) for (File f : files) sum += f.length();
        return sum;
    }

    static boolean ready(Context c) {
        for (String[] f : FILES) {
            File file = file(c, f[0]);
            if (!file.isFile() || file.length() < Long.parseLong(f[2])) return false;
        }
        return true;
    }

    static boolean speakerReady(Context c) {
        File f = file(c, SPEAKER);
        return f.isFile() && f.length() >= Long.parseLong(SPEAKER_FILE[2]);
    }

    static long sizeOnDisk(Context c) {
        long sum = 0;
        File[] files = dir(c).listFiles();
        if (files != null) for (File f : files) sum += f.length();
        return sum;
    }

    /** Starts the SenseVoice download on a background thread. Does nothing if one is already running. */
    static void download(Context context) {
        String[][] all = Arrays.copyOf(FILES, FILES.length + 1);
        all[FILES.length] = withDir(SPEAKER_FILE);
        start(context, all);
    }

    /** Starts the Zipformer-CTC download (its two files, plus the VAD and speaker model if they are missing). */
    static void downloadZip(Context context) {
        String[][] all = Arrays.copyOf(ZIP_FILES, ZIP_FILES.length + 2);
        all[ZIP_FILES.length] = VAD_FILE;
        all[ZIP_FILES.length + 1] = withDir(SPEAKER_FILE);
        start(context, all);
    }

    private static String[] withDir(String[] f) {
        return new String[] {f[0], f[1], f[2], DIR};
    }

    private static synchronized void start(Context context, String[][] all) {
        if (state == RUNNING) return;
        Context app = context.getApplicationContext();
        state = RUNNING;
        cancel = false;
        error = "";
        doneBytes = 0;
        totalBytes = 0;
        new Thread(() -> {
            try {
                for (String[] f : all) {
                    File dir = f.length > 3 ? new File(app.getFilesDir(), f[3]) : dir(app);
                    if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("สร้างโฟลเดอร์ไม่ได้");
                    File target = new File(dir, f[0]);
                    long min = Long.parseLong(f[2]);
                    if (target.isFile() && target.length() >= min) continue;
                    fetch(f[1], target, min);
                }
                state = DONE;
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                state = FAILED;
            }
        }, "hansub-model-download").start();
    }

    static void cancel() {
        cancel = true;
    }

    static void delete(Context c) {
        cancel = true;
        File[] files = dir(c).listFiles();
        if (files != null) for (File f : files) f.delete();
        state = IDLE;
    }

    static void deleteZip(Context c) {
        cancel = true;
        File[] files = new File(c.getFilesDir(), ZIP_DIR).listFiles();
        if (files != null) for (File f : files) f.delete();
        state = IDLE;
    }

    private static void fetch(String address, File target, long min) throws IOException {
        File part = new File(target.getPath() + ".part");
        long have = part.isFile() ? part.length() : 0;
        HttpURLConnection http = (HttpURLConnection) new URL(address).openConnection();
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
        byte[] buf = new byte[64 * 1024];
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
        if (part.length() < min) {
            part.delete();
            throw new IOException("ไฟล์ " + target.getName() + " ไม่ครบ");
        }
        if (target.exists()) target.delete();
        if (!part.renameTo(target)) throw new IOException("บันทึกไฟล์ไม่ได้");
    }
}
