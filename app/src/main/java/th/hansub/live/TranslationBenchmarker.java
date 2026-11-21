package th.hansub.live;

import android.os.SystemClock;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

/**
 * A quick on-device comparison of latency and text output. This is NOT an accuracy
 * benchmark. First-run model initialization can affect timings.
 */
public final class TranslationBenchmarker {
    public interface Listener {
        void onComplete(String report);
    }

    private TranslationBenchmarker() {}

    private static Translator make(String from,String to) {
        return Translation.getClient(new TranslatorOptions.Builder()
            .setSourceLanguage(from).setTargetLanguage(to).build());
    }

    public static void run(Listener listener) {
        final String sample="你怎么现在才来？我已经等你很久了。";
        Translator fast=make(TranslateLanguage.CHINESE,TranslateLanguage.THAI);
        Translator first=make(TranslateLanguage.CHINESE,TranslateLanguage.ENGLISH);
        Translator second=make(TranslateLanguage.ENGLISH,TranslateLanguage.THAI);
        long startFast=SystemClock.elapsedRealtimeNanos();
        fast.translate(sample).addOnCompleteListener(a->{
            long fastMs=(SystemClock.elapsedRealtimeNanos()-startFast)/1_000_000L;
            if (!a.isSuccessful()) {
                close(fast,first,second);
                listener.onComplete("Fast ล้มเหลว: "+failure(a.getException()));
                return;
            }
            String fastThai=a.getResult();
            long startPivot=SystemClock.elapsedRealtimeNanos();
            first.translate(sample).addOnCompleteListener(b->{
                if (!b.isSuccessful()) {
                    close(fast,first,second);
                    listener.onComplete("Pivot ขั้น 1 ล้มเหลว: "+failure(b.getException()));
                    return;
                }
                second.translate(b.getResult()).addOnCompleteListener(c->{
                    long pivotMs=(SystemClock.elapsedRealtimeNanos()-startPivot)/1_000_000L;
                    if (!c.isSuccessful()) {
                        close(fast,first,second);
                        listener.onComplete("Pivot ขั้น 2 ล้มเหลว: "+failure(c.getException()));
                        return;
                    }
                    String pivotThai=c.getResult();
                    close(fast,first,second);
                    listener.onComplete("ประโยคทดสอบ: "+sample+"\n\n"
                        +"Fast: "+fastMs+" ms\n"+fastThai+"\n\n"
                        +"Pivot: "+pivotMs+" ms\n"+pivotThai+"\n\n"
                        +"เป็นการวัดเพียงประโยคเดียว รวมการเริ่มต้นโมเดล "
                        +"และไม่รวมเวลารู้จำเสียง จึงยังตัดสินความแม่นยำหรือความเร็วเฉลี่ยไม่ได้");
                });
            });
        });
    }

    private static String failure(Exception error) {
        return error==null?"unknown":error.getClass().getSimpleName();
    }
    private static void close(Translator... clients) {
        for (Translator client:clients) client.close();
    }
}
