package th.hansub.live;

import android.annotation.TargetApi;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Captures sound itself and hands it to the system speech recognizer through a pipe.
 *
 * Two sources:
 * - the microphone, which hears the speaker but not headphones;
 * - the sound other apps are playing (Android 10+ playback capture), which works with headphones too.
 *
 * Why not let the recognizer record by itself: it asks for audio focus at every sentence, and a video app
 * answers by pausing. Here this app keeps the capture open the whole time and the recognizer only reads
 * from a pipe, so it has no reason to ask for focus.
 *
 * Between two sessions the last 0.4 s of sound is kept, so the start of the next sentence is not lost.
 */
final class MicFeeder {
    /** Receives every chunk of 16 kHz 16-bit mono sound, on the capture thread. */
    interface AudioListener {
        void onAudio(byte[] pcm, int bytes);
    }

    static final int RATE = 16000;
    private static final int PLAYBACK_RATE = 48000;
    private static final int PREROLL_BYTES = RATE * 2 * 4 / 10;
    /** A sample this small or smaller counts as silence. */
    private static final int SILENCE_PEAK = 4;

    private final Object lock = new Object();
    private final byte[] ring = new byte[PREROLL_BYTES];
    private int ringFill;
    private OutputStream sink;
    private AudioRecord record;
    private Thread thread;
    private int sourceRate = RATE;
    private boolean playback;
    private volatile boolean alive;
    private volatile long lastSoundAt;
    private volatile AudioListener listener;

    void setListener(AudioListener l) {
        listener = l;
    }

    boolean isPlayback() {
        return playback;
    }

    /** How long the capture has heard only silence. */
    long silentForMs() {
        return SystemClock.elapsedRealtime() - lastSoundAt;
    }

    /** Opens the microphone. Returns false if the phone refuses (no permission, microphone busy). */
    boolean startMic() {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) return false;
        try {
            sourceRate = RATE;
            playback = false;
            record = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 2, RATE));
            return begin();
        } catch (RuntimeException e) {
            release();
            return false;
        }
    }

    /** Captures what other apps play. Needs the user's screen-capture consent, held by [projection]. */
    @TargetApi(29)
    boolean startPlayback(MediaProjection projection) {
        try {
            AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();
            // 48 kHz is what the system mixer already uses; it is cut down to 16 kHz in pump().
            sourceRate = PLAYBACK_RATE;
            playback = true;
            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(PLAYBACK_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build();
            record = new AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(PLAYBACK_RATE * 2)
                    .build();
            return begin();
        } catch (RuntimeException e) {
            release();
            return false;
        }
    }

    private boolean begin() {
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            release();
            return false;
        }
        record.startRecording();
        if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            release();
            return false;
        }
        lastSoundAt = SystemClock.elapsedRealtime();
        alive = true;
        thread = new Thread(this::pump, "hansub-capture");
        thread.start();
        return true;
    }

    /**
     * Starts a new pipe for one recognizer session and returns the end the recognizer reads from.
     * The caller closes the returned descriptor when the session is over.
     */
    ParcelFileDescriptor attach() throws IOException {
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]);
        synchronized (lock) {
            closeSink();
            try {
                if (ringFill > 0) out.write(ring, 0, ringFill);
            } catch (IOException ignored) {
                // The recognizer is not reading yet; the pre-roll is only a bonus.
            }
            ringFill = 0;
            sink = out;
        }
        return pipe[0];
    }

    /** Ends the current session's pipe. The recognizer sees the end of the sound. */
    void detach() {
        synchronized (lock) {
            closeSink();
        }
    }

    void stop() {
        alive = false;
        Thread t = thread;
        if (t != null) {
            try {
                t.join(600);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        thread = null;
        detach();
        release();
    }

    private void pump() {
        AudioRecord source = record;
        byte[] in = new byte[sourceRate / 5 * 2];
        byte[] cut = new byte[RATE / 5 * 2];
        int factor = sourceRate / RATE;
        while (alive) {
            int n = source.read(in, 0, in.length);
            if (n < 0) break;
            if (n == 0) continue;
            byte[] data = in;
            int len = n;
            if (factor > 1) {
                len = downsample(in, n, factor, cut);
                data = cut;
            }
            if (peak(data, len) > SILENCE_PEAK) lastSoundAt = SystemClock.elapsedRealtime();
            AudioListener l = listener;
            if (l != null) l.onAudio(data, len);
            OutputStream out;
            synchronized (lock) {
                out = sink;
                if (out == null) remember(data, len);
            }
            if (out != null) {
                try {
                    out.write(data, 0, len);
                } catch (IOException e) {
                    // The recognizer closed its end. Keep recording; the next session gets a new pipe.
                    synchronized (lock) {
                        if (sink == out) closeSink();
                    }
                }
            }
        }
    }

    /** Averages every [factor] samples into one. Crude, but enough for speech. Returns the bytes written. */
    static int downsample(byte[] in, int bytes, int factor, byte[] out) {
        int samples = bytes / 2;
        int groups = Math.min(samples / factor, out.length / 2);
        for (int g = 0; g < groups; g++) {
            int sum = 0;
            for (int k = 0; k < factor; k++) {
                int i = (g * factor + k) * 2;
                sum += (short) ((in[i] & 0xff) | (in[i + 1] << 8));
            }
            short avg = (short) (sum / factor);
            out[g * 2] = (byte) (avg & 0xff);
            out[g * 2 + 1] = (byte) ((avg >> 8) & 0xff);
        }
        return groups * 2;
    }

    /** Largest absolute sample value in 16-bit little-endian sound. */
    static int peak(byte[] data, int bytes) {
        int max = 0;
        for (int i = 0; i + 1 < bytes; i += 2) {
            int v = Math.abs((short) ((data[i] & 0xff) | (data[i + 1] << 8)));
            if (v > max) max = v;
        }
        return max;
    }

    /** Keeps only the newest PREROLL_BYTES of sound. */
    private void remember(byte[] data, int n) {
        if (n >= ring.length) {
            System.arraycopy(data, n - ring.length, ring, 0, ring.length);
            ringFill = ring.length;
            return;
        }
        int overflow = ringFill + n - ring.length;
        if (overflow > 0) {
            System.arraycopy(ring, overflow, ring, 0, ringFill - overflow);
            ringFill -= overflow;
        }
        System.arraycopy(data, 0, ring, ringFill, n);
        ringFill += n;
    }

    private void closeSink() {
        if (sink != null) {
            try {
                sink.close();
            } catch (IOException ignored) {
                // Already closed.
            }
            sink = null;
        }
    }

    private void release() {
        if (record != null) {
            try {
                record.stop();
            } catch (RuntimeException ignored) {
                // Never started.
            }
            record.release();
            record = null;
        }
    }
}
