package th.hansub.live;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.ParcelFileDescriptor;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Records the microphone itself and hands the sound to the system speech recognizer through a pipe.
 *
 * Why: when the recognizer opens the microphone by itself, Android asks for audio focus, and a video app
 * answers by pausing. The recognizer starts a new session after every sentence, so the video pauses and
 * resumes again and again. Here this app owns the microphone, keeps it open the whole time, and the
 * recognizer only reads from a pipe, so it has no reason to ask for focus.
 *
 * Between two sessions the last 0.4 s of sound is kept, so the start of the next sentence is not lost.
 */
final class MicFeeder {
    static final int RATE = 16000;
    private static final int PREROLL_BYTES = RATE * 2 * 4 / 10;

    private final Object lock = new Object();
    private final byte[] ring = new byte[PREROLL_BYTES];
    private int ringFill;
    private OutputStream sink;
    private AudioRecord record;
    private Thread thread;
    private volatile boolean alive;

    /** Opens the microphone. Returns false if the phone refuses (no permission, microphone busy). */
    boolean start() {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) return false;
        try {
            record = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 2, RATE));
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                release();
                return false;
            }
            record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                release();
                return false;
            }
        } catch (RuntimeException e) {
            release();
            return false;
        }
        alive = true;
        thread = new Thread(this::pump, "hansub-mic");
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
        byte[] buf = new byte[RATE / 5];
        AudioRecord source = record;
        while (alive) {
            int n = source.read(buf, 0, buf.length);
            if (n < 0) break;
            if (n == 0) continue;
            OutputStream out;
            synchronized (lock) {
                out = sink;
                if (out == null) remember(buf, n);
            }
            if (out != null) {
                try {
                    out.write(buf, 0, n);
                } catch (IOException e) {
                    // The recognizer closed its end. Keep recording; the next session gets a new pipe.
                    synchronized (lock) {
                        if (sink == out) closeSink();
                    }
                }
            }
        }
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
