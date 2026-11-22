package th.hansub.live;

/**
 * Decides when part of a sentence that is still being spoken can be sent for translation.
 *
 * Example: someone talks for ten seconds without a pause. Every few hundred milliseconds the recognizer
 * re-reads the speech so far and returns tokens with times. Once "我们走吧，" has come out the same in
 * two readings in a row, and it ends well before the newest sound, it is safe: it is committed,
 * translated, and the recognizer continues from the sound after it. When nobody uses punctuation for a
 * long time, the cut goes at the longest small pause between words instead.
 */
final class StreamCommit {
    /** Sentence and clause marks that SenseVoice writes. */
    private static final String MARKS = "，。！？；、,.!?;…";
    /** A mark this close to the newest sound may still change, so it is not used yet. */
    static final float TAIL_GUARD_S = 0.45f;
    /** Without any mark, force a cut once this much uncommitted speech has piled up. */
    static final float FORCE_AFTER_S = 4.5f;
    private static final int MIN_CHARS = 4;

    private StreamCommit() {}

    static boolean isMark(String token) {
        String t = token.trim();
        return t.length() == 1 && MARKS.indexOf(t.charAt(0)) >= 0;
    }

    /** How many leading tokens are the same in both readings. */
    static int stablePrefix(String[] now, String[] before) {
        if (before == null) return 0;
        int n = Math.min(now.length, before.length), i = 0;
        while (i < n && now[i].equals(before[i])) i++;
        return i;
    }

    /**
     * Index of the last token to commit, or -1 to wait. [audioS] is the length of the sound that was read.
     */
    static int commitIndex(String[] tokens, float[] times, String[] before, float audioS) {
        int n = Math.min(tokens.length, times.length);
        int stable = Math.min(n, stablePrefix(tokens, before));
        int best = -1, chars = 0;
        for (int i = 0; i < stable; i++) {
            if (times[i] > audioS - TAIL_GUARD_S) break;
            if (!isMark(tokens[i])) chars += tokens[i].trim().length();
            else if (chars >= MIN_CHARS) best = i;
        }
        if (best >= 0 || audioS < FORCE_AFTER_S) return best;
        // No usable mark: cut at the widest gap between two words, away from both ends.
        float widest = -1f;
        chars = 0;
        for (int i = 0; i + 1 < stable; i++) {
            if (times[i + 1] > audioS - 1f) break;
            chars += tokens[i].trim().length();
            if (chars < MIN_CHARS) continue;
            float gap = times[i + 1] - times[i];
            if (gap > widest) {
                widest = gap;
                best = i;
            }
        }
        return best;
    }

    /** Where to cut the sound, in seconds: halfway to the next token, or a little after the last one. */
    static float cutSeconds(float[] times, int index, int count) {
        if (index + 1 < Math.min(count, times.length)) return (times[index] + times[index + 1]) / 2f;
        return times[index] + 0.15f;
    }

    /** The committed text: tokens 0..index joined, without a leading mark. */
    static String text(String[] tokens, int index) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i <= index && i < tokens.length; i++) b.append(tokens[i].replace("▁", " "));
        String s = AsrText.clean(b.toString());
        while (!s.isEmpty() && isMark(s.substring(0, 1))) s = s.substring(1).trim();
        return s;
    }
}
