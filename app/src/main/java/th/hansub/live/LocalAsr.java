package th.hansub.live;

import android.content.Context;
import android.os.SystemClock;

import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.SileroVadModelConfig;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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

        /** One finished stretch of speech, cut where the voice changes: text and speaker number per piece. */
        void onFinal(List<String> chinese, List<Integer> speakers, long decodeMs);
    }

    private static final int RATE = MicFeeder.RATE;
    private static final int WINDOW = 512;
    private static final int PREROLL = RATE * 4 / 10;

    private final OfflineRecognizer recognizer;
    private final Vad vad;
    private final Listener listener;
    /** Null when the speaker model is missing or switched off. */
    private final SpeakerTracker speakers;
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
        // Continuous speech is committed piece by piece (StreamCommit), so the VAD only needs a far limit.
        silero.setMaxSpeechDuration(20f);
        VadModelConfig vadConfig = new VadModelConfig();
        vadConfig.setSileroVadModelConfig(silero);
        vadConfig.setSampleRate(RATE);
        vadConfig.setNumThreads(1);
        vad = new Vad(null, vadConfig);

        SpeakerTracker tracker = null;
        if (c.getSharedPreferences("hansub", Context.MODE_PRIVATE).getBoolean("speaker_split", true)
                && LocalModels.speakerReady(c)) {
            try {
                tracker = new SpeakerTracker(c);
            } catch (RuntimeException e) {
                tracker = null;
            }
        }
        speakers = tracker;
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
            if (speakers != null) speakers.release();
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
        String[] lastTokens = null;
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
            // While talking, sound before the uncommitted part is no longer needed.
            if (speaking && speechStart > RATE * 6) {
                System.arraycopy(buf, speechStart, buf, 0, len - speechStart);
                len -= speechStart;
                fed -= speechStart;
                speechStart = 0;
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
                    lastTokens = null;
                }
            }

            boolean finished = false;
            while (!vad.empty()) {
                vad.pop();
                finished = true;
            }
            if (finished) {
                // The speaker paused: whatever was not committed yet is read once more and sent.
                if (fed - speechStart > RATE / 4) {
                    long t0 = SystemClock.elapsedRealtime();
                    float[] rest = Arrays.copyOfRange(buf, speechStart, fed);
                    List<String> texts = new ArrayList<>();
                    List<Integer> who = new ArrayList<>();
                    List<int[]> pieces = speakers != null ? speakers.split(rest)
                            : SpeakerSplit.pieces(new ArrayList<>(), rest.length);
                    for (int[] p : pieces) {
                        String text = decode(Arrays.copyOfRange(rest, p[0], p[1]));
                        if (text.isEmpty()) continue;
                        texts.add(text);
                        who.add(speakers != null ? speakers.label(rest, p[0], p[1]) : 0);
                    }
                    long ms = SystemClock.elapsedRealtime() - t0;
                    lastDecodeMs = ms;
                    if (!texts.isEmpty()) listener.onFinal(texts, who, ms);
                }
                speaking = false;
                lastPartial = "";
                lastTokens = null;
                len = 0;
                fed = 0;
                speechStart = 0;
                continue;
            }

            // While the sentence goes on: re-read it, show it live, and commit the part that has settled.
            long now = SystemClock.elapsedRealtime();
            if (speaking && now - lastPartialAt >= interval && queue.size() < 4 && fed - speechStart > RATE / 3) {
                long t0 = SystemClock.elapsedRealtime();
                OfflineRecognizerResult r = read(Arrays.copyOfRange(buf, speechStart, fed));
                long ms = SystemClock.elapsedRealtime() - t0;
                lastDecodeMs = ms;
                // A slow phone decodes less often instead of falling behind.
                interval = Math.max(250, Math.min(1000, ms * 2));
                lastPartialAt = SystemClock.elapsedRealtime();
                String[] tokens = r.getTokens();
                float[] times = r.getTimestamps();
                float audioS = (fed - speechStart) / (float) RATE;
                int k = StreamCommit.commitIndex(tokens, times, lastTokens, audioS);
                if (k >= 0) {
                    String text = StreamCommit.text(tokens, k);
                    int cut = speechStart + Math.round(StreamCommit.cutSeconds(times, k, tokens.length) * RATE);
                    cut = Math.max(speechStart + RATE / 4, Math.min(cut, fed));
                    if (!text.isEmpty()) {
                        int id = speakers != null ? speakers.label(buf, speechStart, cut) : 0;
                        List<String> one = new ArrayList<>();
                        one.add(text);
                        List<Integer> who = new ArrayList<>();
                        who.add(id);
                        listener.onFinal(one, who, ms);
                    }
                    speechStart = cut;
                    lastTokens = null;
                    lastPartial = "";
                    continue;
                }
                lastTokens = tokens;
                String text = AsrText.clean(r.getText());
                if (!text.isEmpty() && !text.equals(lastPartial)) {
                    lastPartial = text;
                    listener.onPartial(text);
                }
            }
        }
    }

    private OfflineRecognizerResult read(float[] samples) {
        OfflineStream stream = recognizer.createStream();
        try {
            stream.acceptWaveform(samples, RATE);
            recognizer.decode(stream);
            return recognizer.getResult(stream);
        } finally {
            stream.release();
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
