package th.hansub.live;

import android.content.Context;
import android.os.SystemClock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Hy-MT2 on the phone. One worker thread runs the model, one request at a time.
 *
 * Speech can come faster than the model translates. Only the newest {@link #MAX_WAITING} requests wait; an older
 * one is dropped, and the line keeps the quick ML Kit draft that is already on screen.
 */
final class LocalTranslator {
    private static final int CONTEXT = 1024;
    private static final int MAX_WAITING = 2;

    private static final class Job {
        final String prompt;
        final int maxNew;
        final BooleanSupplier wanted;
        final Consumer<String> done;

        Job(String prompt, int maxNew, BooleanSupplier wanted, Consumer<String> done) {
            this.prompt = prompt;
            this.maxNew = maxNew;
            this.wanted = wanted;
            this.done = done;
        }
    }

    private final long handle;
    private final ArrayDeque<Job> waiting = new ArrayDeque<>();
    private final Thread worker;
    private volatile boolean closed;
    /** How long the last answer took, in milliseconds. */
    volatile long lastMs;

    private LocalTranslator(long handle) {
        this.handle = handle;
        worker = new Thread(this::run, "hansub-mt");
        worker.start();
    }

    static boolean supported() {
        return LlmNative.available();
    }

    /** Loads the model. Takes a few seconds, so call it off the main thread. */
    static LocalTranslator open(Context context) throws IOException {
        if (!LlmNative.available()) throw new IOException("เครื่องนี้ไม่รองรับ (ต้องเป็นแบบ 64 บิต)");
        LlmNative.init(context.getApplicationInfo().nativeLibraryDir);
        int cores = Runtime.getRuntime().availableProcessors();
        // The speech recognizer is running at the same time, so the translator does not take every core.
        int threads = Math.max(2, Math.min(4, cores / 2));
        long handle = LlmNative.load(TranslatorModel.file(context).getPath(), threads, CONTEXT);
        if (handle == 0) {
            String why = LlmNative.lastError();
            throw new IOException("โหลดโมเดลแปลไม่ได้" + (why == null || why.isEmpty() ? "" : ": " + why));
        }
        return new LocalTranslator(handle);
    }

    /**
     * Queues one sentence.
     *
     * @param wanted asked just before the model starts; false means nobody needs this answer any more
     * @param done   gets the Thai text on the worker thread; never called when the request was dropped or failed
     */
    void submit(String prompt, int maxNew, BooleanSupplier wanted, Consumer<String> done) {
        synchronized (waiting) {
            if (closed) return;
            waiting.addLast(new Job(prompt, maxNew, wanted, done));
            while (waiting.size() > MAX_WAITING) waiting.removeFirst();
            waiting.notifyAll();
        }
    }

    private void run() {
        while (true) {
            Job job;
            synchronized (waiting) {
                while (waiting.isEmpty() && !closed) {
                    try {
                        waiting.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (closed) return;
                job = waiting.removeFirst();
            }
            try {
                if (!job.wanted.getAsBoolean()) continue;
                long started = SystemClock.elapsedRealtime();
                byte[] bytes = LlmNative.generate(handle, job.prompt, job.maxNew);
                lastMs = SystemClock.elapsedRealtime() - started;
                if (bytes == null || closed) continue;
                String text = TranslatePrompt.clean(new String(bytes, StandardCharsets.UTF_8));
                if (!text.isEmpty()) job.done.accept(text);
            } catch (RuntimeException e) {
                // One bad request must not stop the worker; the line keeps its draft.
            }
        }
    }

    void close() {
        synchronized (waiting) {
            closed = true;
            waiting.clear();
            waiting.notifyAll();
        }
        LlmNative.cancel(handle);
        try {
            worker.join(4000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        // If the worker is still inside the model, freeing now would crash. Leaking one model is the safer failure.
        if (!worker.isAlive()) LlmNative.free(handle);
    }
}
