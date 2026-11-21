package th.hansub.live;

import android.content.Context;
import android.content.SharedPreferences;

/** Only aggregate latency numbers are persisted, never the subtitles. */
public final class TranslationStats {
    private TranslationStats() {}

    public static void record(Context context,String mode,long elapsedMs) {
        if (elapsedMs<0 || elapsedMs>120_000) return;
        SharedPreferences prefs=context.getSharedPreferences("hansub_stats",Context.MODE_PRIVATE);
        String key="ms_"+mode;
        String existing=prefs.getString(key,"");
        String[] parts=existing.isEmpty()?new String[0]:existing.split(",");
        StringBuilder out=new StringBuilder();
        for(int i=Math.max(0,parts.length-39);i<parts.length;i++) {
            if(!parts[i].isEmpty()) out.append(parts[i]).append(',');
        }
        out.append(elapsedMs);
        prefs.edit().putString(key,out.toString()).apply();
    }

    public static String summary(Context context,String mode) {
        String csv=context.getSharedPreferences("hansub_stats",Context.MODE_PRIVATE)
                .getString("ms_"+mode,"");
        LatencyTracker tracker=new LatencyTracker(40);
        for(String item:csv.split(",")) {
            try {tracker.add(Long.parseLong(item));}
            catch(NumberFormatException ignored) {}
        }
        if(tracker.size()==0) return "ยังไม่มีข้อมูลจากเครื่องนี้";
        return "ตัวอย่าง "+tracker.size()+" ประโยค • P50 "+tracker.percentile(50)
                +" ms • P95 "+tracker.percentile(95)+" ms";
    }
}
