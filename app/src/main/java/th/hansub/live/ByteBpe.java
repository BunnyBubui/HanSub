package th.hansub.live;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Turns the tokens of the Zipformer-CTC Chinese model into characters with a time each.
 *
 * Example: this model does not write "对" as a token. It writes it as three raw bytes (E5 AF B9), and each
 * byte is shown as an odd letter such as "ƌ" or "ŕ". The text it returns is right, but the tokens cannot be
 * read, and the sentence cutter (StreamCommit) needs one readable piece plus a time for each character.
 * This class turns the letters back into bytes, groups the bytes into characters, and gives each
 * character the time of the token that started it.
 *
 * The letter-to-byte table below was read off the model's own output and checked against its text.
 */
final class ByteBpe {
    /** Marks the start of a word. Chinese has no spaces, so it is skipped. */
    private static final char WORD_START = '▁';
    /** Letter code to byte, or -1. Only the bytes Chinese text needs: 0x80-0xBF and 0xE4-0xE9, plus plain ASCII. */
    private static final int[] BYTE_OF = new int[512];

    static {
        Arrays.fill(BYTE_OF, -1);
        for (int c = 33; c <= 126; c++) BYTE_OF[c] = c;
        int b = 128;
        // The 64 continuation bytes, in order. Letters 306, 307, 319, 320 and 329 are not used by the model.
        int[][] runs = {{289, 305}, {308, 318}, {321, 328}, {330, 357}};
        for (int[] run : runs) for (int c = run[0]; c <= run[1]; c++) BYTE_OF[c] = b++;
        // The six first bytes of common Chinese characters.
        b = 228;
        for (int c = 395; c <= 400; c++) BYTE_OF[c] = b++;
    }

    /** Characters and the time (seconds) of each. */
    static final class Chars {
        final String[] text;
        final float[] times;

        Chars(String[] text, float[] times) {
            this.text = text;
            this.times = times;
        }
    }

    private ByteBpe() {}

    static Chars decode(String[] tokens, float[] times) {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        List<Integer> owner = new ArrayList<>();
        for (int i = 0; i < tokens.length; i++) {
            String t = tokens[i];
            for (int k = 0; k < t.length(); k++) {
                char ch = t.charAt(k);
                if (ch == WORD_START || ch >= BYTE_OF.length || BYTE_OF[ch] < 0) continue;
                data.write(BYTE_OF[ch]);
                owner.add(i);
            }
        }
        byte[] bytes = data.toByteArray();
        List<String> out = new ArrayList<>();
        List<Float> at = new ArrayList<>();
        int i = 0;
        while (i < bytes.length) {
            int first = bytes[i] & 0xff;
            int len = first < 0x80 ? 1 : first >= 0xF0 ? 4 : first >= 0xE0 ? 3 : first >= 0xC0 ? 2 : 0;
            boolean ok = len > 0 && i + len <= bytes.length;
            for (int k = 1; ok && k < len; k++) ok = (bytes[i + k] & 0xC0) == 0x80;
            if (!ok) {
                i++;
                continue;
            }
            out.add(new String(bytes, i, len, StandardCharsets.UTF_8));
            int o = owner.get(i);
            at.add(times.length == 0 ? 0f : times[Math.min(o, times.length - 1)]);
            i += len;
        }
        float[] t = new float[at.size()];
        for (int k = 0; k < t.length; k++) t[k] = at.get(k);
        return new Chars(out.toArray(new String[0]), t);
    }
}
