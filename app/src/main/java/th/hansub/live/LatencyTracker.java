package th.hansub.live;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Sliding-window wall-clock latency summary, one engine per tracker. */
public final class LatencyTracker {
    private final ArrayList<Long> samples=new ArrayList<>();
    private final int capacity;

    public LatencyTracker(int capacity) {
        this.capacity=Math.max(1,Math.min(200,capacity));
    }

    public synchronized void add(long millis) {
        if (millis<0) return;
        samples.add(millis);
        if (samples.size()>capacity) samples.remove(0);
    }

    public synchronized int size() {return samples.size();}

    public synchronized long percentile(int percentile) {
        if (samples.isEmpty()) return -1;
        List<Long> ordered=new ArrayList<>(samples);
        Collections.sort(ordered);
        int p=Math.max(0,Math.min(100,percentile));
        int index=(int)Math.ceil((p/100.0)*ordered.size())-1;
        return ordered.get(Math.max(0,index));
    }
}
