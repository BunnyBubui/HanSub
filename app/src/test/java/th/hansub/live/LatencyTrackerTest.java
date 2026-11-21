package th.hansub.live;

import org.junit.Test;
import static org.junit.Assert.*;

public class LatencyTrackerTest {
    @Test public void nearestRankPercentiles() {
        LatencyTracker t=new LatencyTracker(4);
        assertEquals(-1,t.percentile(50));
        t.add(100);t.add(200);t.add(300);t.add(400);
        assertEquals(200,t.percentile(50));
        assertEquals(400,t.percentile(95));
        t.add(500);
        assertEquals(300,t.percentile(50));
    }
}
