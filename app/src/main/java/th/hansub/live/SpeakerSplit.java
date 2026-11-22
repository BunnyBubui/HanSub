package th.hansub.live;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure maths for finding where the speaker changes inside one stretch of speech.
 *
 * The sound is cut into 1.5 s windows that start every 0.75 s, and each window gets a voice
 * "fingerprint" (a speaker embedding). Window i and window i+2 sit side by side without overlap.
 * When their fingerprints look unlike each other, a different person probably started talking there.
 */
final class SpeakerSplit {
    static final int RATE = 16000;
    static final int WINDOW = RATE * 3 / 2;
    static final int HOP = RATE * 3 / 4;

    private SpeakerSplit() {}

    static float cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 1f;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 1f;
        return (float) (dot / Math.sqrt(na * nb));
    }

    /** Start sample of every window for a stretch of [length] samples. */
    static int[] windowStarts(int length) {
        if (length < WINDOW) return new int[0];
        int n = (length - WINDOW) / HOP + 1;
        int[] starts = new int[n];
        for (int i = 0; i < n; i++) starts[i] = i * HOP;
        return starts;
    }

    /**
     * Sample positions where a new speaker starts. sims[i] compares window i with window i+2, so a low
     * value marks a change at the start of window i+2. Only the lowest point of each dip counts, and two
     * cuts are never closer than [minGapSamples]. Cuts too close to either end are dropped.
     */
    static List<Integer> cuts(float[] sims, int[] starts, int length, float threshold, int minGapSamples) {
        List<Integer> out = new ArrayList<>();
        int last = 0;
        for (int i = 0; i < sims.length; i++) {
            if (sims[i] >= threshold) continue;
            boolean lowest = (i == 0 || sims[i] <= sims[i - 1]) && (i == sims.length - 1 || sims[i] < sims[i + 1]);
            if (!lowest) continue;
            int at = starts[i + 2];
            if (at - last < minGapSamples || length - at < minGapSamples) continue;
            out.add(at);
            last = at;
        }
        return out;
    }

    /** Turns cut points into [start, end) pieces covering the whole stretch. */
    static List<int[]> pieces(List<Integer> cuts, int length) {
        List<int[]> out = new ArrayList<>();
        int from = 0;
        for (int c : cuts) {
            out.add(new int[]{from, c});
            from = c;
        }
        out.add(new int[]{from, length});
        return out;
    }
}
