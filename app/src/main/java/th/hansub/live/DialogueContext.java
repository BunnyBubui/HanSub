package th.hansub.live;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small in-memory history: final Chinese lines only, no persistent transcript. */
public final class DialogueContext {
    private final ArrayDeque<String> lines=new ArrayDeque<>();
    private final int capacity;
    private final LinkedHashMap<String,String> names=new LinkedHashMap<>();

    public DialogueContext(int capacity) {
        this.capacity=Math.max(1,Math.min(10,capacity));
    }

    public synchronized void record(String text) {
        if (text==null) return;
        String trimmed=text.trim();
        if (trimmed.isEmpty()) return;
        if (trimmed.length()>400) trimmed=trimmed.substring(0,400);
        if (!lines.isEmpty() && lines.getLast().equals(trimmed)) return;
        lines.addLast(trimmed);
        while (lines.size()>capacity) lines.removeFirst();
    }

    public synchronized List<String> previous() {
        return new ArrayList<>(lines);
    }

    public synchronized void parseNames(String raw) {
        names.clear();
        if (raw==null) return;
        for (String line:raw.split("\\r?\\n")) {
            int split=line.indexOf('=');
            if (split<=0 || split==line.length()-1 || names.size()>=30) continue;
            String chinese=line.substring(0,split).trim();
            String thai=line.substring(split+1).trim();
            if (chinese.length()>0 && chinese.length()<=32
                    && thai.length()>0 && thai.length()<=64) names.put(chinese,thai);
        }
    }

    public synchronized Map<String,String> glossary() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(names));
    }
}
