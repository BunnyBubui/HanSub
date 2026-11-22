package th.hansub.live;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the request for the on-device translator (Hy-MT2) and cleans what it answers. Pure text work,
 * so it is tested on the build server without loading any model.
 *
 * The wording follows the model card: a Chinese instruction naming the target language in Chinese
 * (泰语 = Thai), and, when a name list applies, "参考下面的翻译" followed by "term 翻译成 term" lines.
 */
final class TranslatePrompt {
    private static final int MAX_TERMS = 8;

    private TranslatePrompt() {}

    /**
     * @param chinese  one sentence
     * @param glossary the user's "中文=ไทย" lines (the same list the cloud mode uses); may be empty
     */
    static String build(String chinese, String glossary) {
        StringBuilder out = new StringBuilder();
        List<String[]> terms = matching(chinese, glossary);
        if (!terms.isEmpty()) {
            out.append("参考下面的翻译：\n");
            for (String[] term : terms) out.append(term[0]).append(" 翻译成 ").append(term[1]).append('\n');
            out.append('\n');
        }
        out.append("将以下文本翻译为泰语，注意只需要输出翻译后的结果，不要额外解释：\n\n").append(chinese);
        return out.toString();
    }

    /** The glossary entries whose Chinese side occurs in the sentence (longest names first, at most eight). */
    static List<String[]> matching(String chinese, String glossary) {
        List<String[]> found = new ArrayList<>();
        if (glossary == null || glossary.isEmpty()) return found;
        for (String line : glossary.split("\\r?\\n")) {
            String[] pair = pair(line);
            if (pair != null && chinese.contains(pair[0])) found.add(pair);
        }
        found.sort((a, b) -> b[0].length() - a[0].length());
        return found.size() > MAX_TERMS ? new ArrayList<>(found.subList(0, MAX_TERMS)) : found;
    }

    /** "张总=ประธานจาง" gives {"张总","ประธานจาง"}. Accepts = or the full-width ＝ ； blank and # lines give null. */
    static String[] pair(String line) {
        String text = line.trim();
        if (text.isEmpty() || text.startsWith("#")) return null;
        int cut = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '=' || c == '＝') {cut = i;break;}
        }
        if (cut <= 0 || cut >= text.length() - 1) return null;
        String chinese = text.substring(0, cut).trim();
        String thai = text.substring(cut + 1).trim();
        return chinese.isEmpty() || thai.isEmpty() ? null : new String[]{chinese, thai};
    }

    /** How many tokens the answer may use. Thai takes several tokens per Chinese character, so the cap is generous. */
    static int maxNewTokens(String chinese) {
        return Math.min(240, 48 + chinese.length() * 6);
    }

    /** Removes special markers and line breaks the model may leave around its answer. */
    static String clean(String answer) {
        if (answer == null) return "";
        String text = answer.replaceAll("<[｜|][^>]*[｜|]>", "");
        text = text.replaceAll("[\\r\\n]+", " ").replaceAll(" {2,}", " ").trim();
        return text;
    }
}
