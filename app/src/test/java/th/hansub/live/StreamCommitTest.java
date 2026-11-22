package th.hansub.live;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class StreamCommitTest {
    private static final String[] T = {"我", "们", "走", "吧", "，", "你", "跟", "我", "来"};
    private static final float[] S = {0.1f, 0.3f, 0.5f, 0.7f, 0.8f, 1.2f, 1.4f, 1.6f, 1.8f};

    @Test public void waitsUntilTwoReadingsAgree() {
        assertEquals(-1, StreamCommit.commitIndex(T, S, null, 2.0f));
    }

    @Test public void commitsUpToTheSettledComma() {
        assertEquals(4, StreamCommit.commitIndex(T, S, T, 2.0f));
        assertEquals("我们走吧，", StreamCommit.text(T, 4));
    }

    @Test public void commaTooCloseToTheNewestSoundWaits() {
        assertEquals(-1, StreamCommit.commitIndex(T, S, T, 1.2f));
    }

    @Test public void tooShortClauseIsNotCommittedAlone() {
        String[] t = {"好", "，", "走", "吧", "我", "们"};
        float[] s = {0.1f, 0.2f, 0.4f, 0.6f, 0.8f, 1.0f};
        assertEquals(-1, StreamCommit.commitIndex(t, s, t, 2.0f));
    }

    @Test public void longSpeechWithoutMarksCutsAtTheWidestGap() {
        String[] t = {"一", "二", "三", "四", "五", "六", "七", "八"};
        float[] s = {0.2f, 0.5f, 0.8f, 1.1f, 2.4f, 2.7f, 3.0f, 3.3f};
        assertEquals(3, StreamCommit.commitIndex(t, s, t, 5.0f));
        assertEquals(1.75f, StreamCommit.cutSeconds(s, 3, t.length), 1e-6);
    }

    @Test public void onlyTheStablePartCounts() {
        String[] before = {"我", "们", "走", "吧", "，", "你"};
        String[] now = {"我", "们", "去", "吧", "，", "你"};
        assertEquals(-1, StreamCommit.commitIndex(now, S, before, 2.0f));
    }
}
