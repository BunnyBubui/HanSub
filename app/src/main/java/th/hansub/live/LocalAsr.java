package th.hansub.live;

import android.content.Context;
import android.os.SystemClock;

import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.SileroVadModelConfig;
import com.k2fsa.sherpa.onnx.SpeechSegment;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;

import java.util.Arrays;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Chinese speech to text on the phone, with no internet and no audio focus.
 *
 * How it works: Silero VAD listens to every 32 ms of sound and marks where speech starts and ends.
 * While someone is talking, SenseVoice re-reads the sentence so far every few hundred milliseconds,
 * which gives a live "partial" line. When the VAD says the sentence ended (or it ran 6 s), SenseVoice
 * reads the whole piece once more and that becomes the "final" line that is translated.
 *
 * The capture thread only drops sound into a queue; all model work happens on this class's own thread.
 */
final class LocalAsr implements MicFeeder.AudioListener {
    interface Listener {
        void onPartial(String chinese);

        void onFinal(String chinese, long decodeMs);
    }

    private static final int RATE = MicFeeder.RATE;
    private static final int WINDOW = 512;
    private static final int PREROLL = RATE * 4 / 10;

    private final OfflineRecognizer recognizer;
    private final Vad vad;
    private final Listener listener;
    private final LinkedBlockingQueue<float[]> queue = new LinkedBlockingQueue<>(3000);
    private Thread worker;
    private volatile boolean alive;

    /** Loudness of the last chunk, 0..1, for the stats line. */
    volatile float level;
    /** How long the last decode took. */
    volatile long lastDecodeMs;
    volatile int backlog;

    /** Loads both models. Takes a second or two, so call it off the main thread. */
    LocalAsr(Context c, Listener listener) {
        this.listener = listener;
        int cores = Runtime.getRuntime().availableProcessors();

        OfflineSenseVoiceModelConfig sense = new OfflineSenseVoiceModelConfig();
        sense.setModel(LocalModels.file(c, LocalModels.MODEL).getAbsolutePath());
        sense.setLanguage("zh");
        sense.setUseInverseTextNormalization(true);
        OfflineModelConfig model = new OfflineModelConfig();
        model.setSenseVoice(sense);
        model.setTokens(LocalModels.file(c, LocalModels.TOKENS).getAbsolutePath());
        model.setNumThreads(Math.max(2, Math.min(4, cores / 2)));
        model.setProvider("cpu");
        OfflineRecognizerConfig config = new OfflineRecognizerConfig();
        config.setModelConfig(model);
        recognizer = new OfflineRecognizer(null, config);

        SileroVadModelConfig silero = new SileroVadModelConfig();
        silero.setModel(LocalModels.file(c, LocalModels.VAD).getAbsolutePath());
        silero.setThreshold(0.45f);
        // A 0.5 s pause ends a sentence. Shorter cut sentences in half, so the Thai came in broken pieces.
        silero.setMinSilenceDuration(0.5f);
        silero.setMinSpeechDuration(0.2f);
        silero.setWindowSize(WINDOW);
        // Long speeches are cut every 8 s so the subtitle never waits too long.
        silero.setMaxSpeechDuration(8f);
        VadModelConfig vadConfig = new VadModelConfig();
        vadConfig.setSileroVadModelConfig(silero);
        vadConfig.setSampleRate(RATE);
        vadConfig.setNumThreads(1);
        vad = new Vad(null, vadConfig);
    }

    void start() {
        alive = true;
        worker = new Thread(this::run, "hansub-asr");
        worker.setPriority(Thread.MAX_PRIORITY - 1);
        worker.start();
    }

    void stop() {
        alive = false;
        Thread t = worker;
        if (t != null) {
            t.interrupt();
            try {
                t.join(4000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            // Still decoding: freeing the models now would crash it. Leave them to the garbage collector.
            if (t.isAlive()) return;
        }
        worker = null;
        try {
            vad.release();
            recognizer.release();
        } catch (RuntimeException ignored) {
            // Already released.
        }
    }

    /** Called on the capture thread with 16 kHz 16-bit sound. */
    @Override public void onAudio(byte[] pcm, int bytes) {
        if (!alive) return;
        int n = bytes / 2;
        float[] samples = new float[n];
        float peak = 0f;
        for (int i = 0; i < n; i++) {
            float v = (short) ((pcm[i * 2] & 0xff) | (pcm[i * 2 + 1] << 8)) / 32768f;
            samples[i] = v;
            float a = Math.abs(v);
            if (a > peak) peak = a;
        }
        level = peak;
        queue.offer(samples);
    }

    private void run() {
        float[] buf = new float[RATE * 20];
        int len = 0, fed = 0, speechStart = 0;
        boolean speaking = false;
        long lastPartialAt = 0, interval = 300;
        String lastPartial = "";
        while (alive) {
            float[] s;
            try {
                s = queue.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                break;
            }
            backlog = queue.size();
            if (s == null) continue;

            // Keep the buffer short while nobody talks: only the last 0.4 s is needed as a lead-in.
            if (!speaking && len > RATE * 2) {
                int keep = Math.min(PREROLL, len);
                int drop = len - keep;
                System.arraycopy(buf, drop, buf, 0, keep);
                len = keep;
                fed = Math.max(0, fed - drop);
            }
            if (len + s.length > buf.length) buf = Arrays.copyOf(buf, Math.max(buf.length * 2, len + s.length));
            System.arraycopy(s, 0, buf, len, s.length);
            len += s.length;

            while (fed + WINDOW <= len) {
                vad.acceptWaveform(Arrays.copyOfRange(buf, fed, fed + WINDOW));
                fed += WINDOW;
                if (!speaking && vad.isSpeechDetected()) {
                    speaking = true;
                    speechStart = Math.max(0, fed - PREROLL);
                    lastPartialAt = SystemClock.elapsedRealtime();
                }
            }

            boolean finished = false;
            while (!vad.empty()) {
                SpeechSegment segment = vad.front();
                vad.pop();
                long t0 = SystemClock.elapsedRealtime();
                String text = decode(segment.getSamples());
                long ms = SystemClock.elapsedRealtime() - t0;
                lastDecodeMs = ms;
                if (!text.isEmpty()) listener.onFinal(text, ms);
                finished = true;
            }
            if (finished) {
                speaking = false;
                lastPartial = "";
                len = 0;
                fed = 0;
                continue;
            }

            // Live line while the sentence is still going. Skipped when we are behind, so finals stay on time.
            long now = SystemClock.elapsedRealtime();
            if (speaking && now - lastPartialAt >= interval && queue.size() < 4 && fed - speechStart > RATE / 3) {
                long t0 = SystemClock.elapsedRealtime();
                String text = decode(Arrays.copyOfRange(buf, speechStart, fed));
                long ms = SystemClock.elapsedRealtime() - t0;
                lastDecodeMs = ms;
                // A slow phone decodes less often instead of falling behind.
                interval = Math.max(250, Math.min(1200, ms * 2));
                lastPartialAt = SystemClock.elapsedRealtime();
                if (!text.isEmpty() && !text.equals(lastPartial)) {
                    lastPartial = text;
                    listener.onPartial(text);
                }
            }
        }
    }

    private String decode(float[] samples) {
        OfflineStream stream = recognizer.createStream();
        try {
            stream.acceptWaveform(samples, RATE);
            recognizer.decode(stream);
            return AsrText.clean(recognizer.getResult(stream).getText());
        } finally {
            stream.release();
        }
    }

}
