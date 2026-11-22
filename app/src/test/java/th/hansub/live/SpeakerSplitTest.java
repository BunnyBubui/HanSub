package th.hansub.live;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class SpeakerSplitTest {
    @Test public void cosineOfSameAndOppositeVectors() {
        assertEquals(1f, SpeakerSplit.cosine(new float[]{1, 2}, new float[]{2, 4}), 1e-6);
        assertEquals(-1f, SpeakerSplit.cosine(new float[]{1, 0}, new float[]{-1, 0}), 1e-6);
        assertEquals(0f, SpeakerSplit.cosine(new float[]{1, 0}, new float[]{0, 1}), 1e-6);
    }

    @Test public void windowsStartEveryHop() {
        int[] s = SpeakerSplit.windowStarts(SpeakerSplit.RATE * 3);
        assertArrayEquals(new int[]{0, 12000, 24000}, s);
        assertEquals(0, SpeakerSplit.windowStarts(1000).length);
    }

    @Test public void cutsAtTheLowestPointOfADip() {
        int len = SpeakerSplit.RATE * 6;
        int[] starts = SpeakerSplit.windowStarts(len);
        float[] sims = {0.9f, 0.8f, 0.2f, 0.3f, 0.9f};
        List<Integer> cuts = SpeakerSplit.cuts(sims, starts, len, 0.42f, SpeakerSplit.RATE);
        assertEquals(Collections.singletonList(starts[4]), cuts);
    }

    @Test public void noCutWhenVoicesStaySimilar() {
        int len = SpeakerSplit.RATE * 6;
        int[] starts = SpeakerSplit.windowStarts(len);
        float[] sims = {0.9f, 0.8f, 0.7f, 0.8f, 0.9f};
        assertEquals(0, SpeakerSplit.cuts(sims, starts, len, 0.42f, SpeakerSplit.RATE).size());
    }

    @Test public void piecesCoverTheWholeStretch() {
        List<int[]> p = SpeakerSplit.pieces(Arrays.asList(100, 300), 500);
        assertEquals(3, p.size());
        assertArrayEquals(new int[]{0, 100}, p.get(0));
        assertArrayEquals(new int[]{300, 500}, p.get(2));
    }
}
