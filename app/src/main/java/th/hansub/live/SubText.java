package th.hansub.live;

import android.os.Build;

import java.util.Locale;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Cleans a translated Thai line before it is shown. */
final class SubText {
    /** A run of Chinese characters, possibly with Chinese punctuation inside. */
    private static final Pattern HAN = Pattern.compile("[\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF]+");
    private static final Pattern CJK_PUNCT = Pattern.compile("[，。、；：！？「」『』（）《》…]");

    private SubText() {}

    /**
     * Replaces every Chinese run left in the Thai text by [romanize] of it, and turns Chinese
     * punctuation into spaces. Example: "คุณ王小明มาแล้ว" with pinyin becomes "คุณ Wang Xiaoming มาแล้ว".
     */
    static String fixLeftovers(String thai, UnaryOperator<String> romanize) {
        if (thai == null) return "";
        Matcher m = HAN.matcher(thai);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String roman = romanize.apply(m.group());
            m.appendReplacement(out, Matcher.quoteReplacement(" " + (roman == null ? "" : roman.trim()) + " "));
        }
        m.appendTail(out);
        String s = CJK_PUNCT.matcher(out).replaceAll(" ");
        return s.replaceAll(" {2,}", " ").trim();
    }

    /** Chinese characters to pinyin without tone marks, each syllable group capitalised like a name. */
    static String pinyin(String han) {
        if (Build.VERSION.SDK_INT < 29) return "";
        try {
            String latin = android.icu.text.Transliterator.getInstance("Han-Latin; Latin-ASCII")
                    .transliterate(han).trim();
            StringBuilder b = new StringBuilder();
            for (String word : latin.split("\\s+")) {
                if (word.isEmpty()) continue;
                if (b.length() > 0) b.append(' ');
                b.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
            }
            return b.toString();
        } catch (RuntimeException e) {
            return "";
        }
    }
}
