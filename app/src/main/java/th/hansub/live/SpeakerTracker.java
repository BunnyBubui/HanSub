package th.hansub.live;

import android.content.Context;

import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Tells voices apart with the 3D-Speaker CAM++ model: cuts a stretch of speech where the voice changes,
 * and gives each piece a speaker number that stays the same while the same voice keeps coming back.
 *
 * It only compares voices. It does not know who the characters are, and two characters dubbed by
 * the same actor will look like one speaker.
 */
final class SpeakerTracker {
    /** Below this, two neighbouring windows are treated as different people. */
    private static final float CHANGE = 0.42f;
    /** At or above this, a piece is matched to a speaker heard before. */
    private static final float SAME = 0.55f;
    private static final int MAX_SPEAKERS = 6;

    private final SpeakerEmbeddingExtractor extractor;
    private final List<float[]> voices = new ArrayList<>();
    private final List<Integer> ids = new ArrayList<>();
    private int nextId;

    SpeakerTracker(Context c) {
        extractor = new SpeakerEmbeddingExtractor(null, new SpeakerEmbeddingExtractorConfig(
                LocalModels.file(c, LocalModels.SPEAKER).getAbsolutePath(), 1, false, "cpu"));
    }

    void release() {
        try {
            extractor.release();
        } catch (RuntimeException ignored) {
            // Already released.
        }
    }

    float[] embed(float[] samples, int from, int to) {
        OnlineStream stream = extractor.createStream();
        try {
            stream.acceptWaveform(Arrays.copyOfRange(samples, from, to), SpeakerSplit.RATE);
            stream.inputFinished();
            return extractor.isReady(stream) ? extractor.compute(stream) : null;
        } finally {
            stream.release();
        }
    }

    /** Splits [samples] where the voice changes. Short stretches are returned whole. */
    List<int[]> split(float[] samples) {
        int[] starts = SpeakerSplit.windowStarts(samples.length);
        if (starts.length < 3) return SpeakerSplit.pieces(new ArrayList<>(), samples.length);
        float[][] emb = new float[starts.length][];
        for (int i = 0; i < starts.length; i++) emb[i] = embed(samples, starts[i], starts[i] + SpeakerSplit.WINDOW);
        float[] sims = new float[starts.length - 2];
        for (int i = 0; i < sims.length; i++) sims[i] = SpeakerSplit.cosine(emb[i], emb[i + 2]);
        List<Integer> cuts = SpeakerSplit.cuts(sims, starts, samples.length, CHANGE, SpeakerSplit.RATE);
        return SpeakerSplit.pieces(cuts, samples.length);
    }

    /** The speaker number for a piece: the closest voice heard before, or a new number. */
    int label(float[] samples, int from, int to) {
        if (to - from < SpeakerSplit.RATE / 2) return ids.isEmpty() ? 0 : ids.get(ids.size() - 1);
        float[] e = embed(samples, from, to);
        if (e == null) return ids.isEmpty() ? 0 : ids.get(ids.size() - 1);
        int best = -1;
        float bestSim = SAME;
        for (int i = 0; i < voices.size(); i++) {
            float s = SpeakerSplit.cosine(e, voices.get(i));
            if (s >= bestSim) {
                bestSim = s;
                best = i;
            }
        }
        if (best >= 0) {
            // Move the matched voice to the end, so the oldest unused voice is the one forgotten first.
            float[] v = voices.remove(best);
            int id = ids.remove(best);
            for (int k = 0; k < v.length; k++) v[k] = v[k] * 0.8f + e[k] * 0.2f;
            voices.add(v);
            ids.add(id);
            return id;
        }
        if (voices.size() >= MAX_SPEAKERS) {
            voices.remove(0);
            ids.remove(0);
        }
        voices.add(e);
        ids.add(nextId);
        return nextId++;
    }
}
