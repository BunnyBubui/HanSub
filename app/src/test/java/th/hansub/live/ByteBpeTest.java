package th.hansub.live;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ByteBpeTest {
    // Real output of the Zipformer-CTC model for the sentence 对我做了介绍那么我想说的是呢.
    private static final String[] TOKENS = {"▁ƌŕş", "▁ƍĩĴ", "▁ƌĢĽ", "▁ƋŠħ", "▁ƋšĬ", "▁Ǝ", "š", "Į",
            "▁Ɛģň", "▁Ƌşĩ", "▁ƍĩĴ", "▁ƍĤř", "▁ƏŕŚ", "▁ƎĽĥ", "▁ƍĻŕ", "▁ƌĴŇ"};
    private static final float[] TIMES = {0.0f, 0.32f, 0.48f, 0.64f, 0.8f, 0.96f, 1.0f, 1.08f,
            1.6f, 1.76f, 1.92f, 2.08f, 2.24f, 2.4f, 2.56f, 2.72f};

    @Test public void bytesBecomeChineseCharacters() {
        ByteBpe.Chars c = ByteBpe.decode(TOKENS, TIMES);
        assertEquals("对我做了介绍那么我想说的是呢", String.join("", c.text));
        assertEquals(14, c.text.length);
    }

    @Test public void aCharacterSplitOverThreeTokensKeepsTheFirstTime() {
        ByteBpe.Chars c = ByteBpe.decode(TOKENS, TIMES);
        assertEquals("绍", c.text[5]);
        assertEquals(0.96f, c.times[5], 1e-6);
        assertEquals(1.6f, c.times[6], 1e-6);
    }

    @Test public void halfACharacterAtTheEndIsDropped() {
        ByteBpe.Chars c = ByteBpe.decode(new String[] {"▁ƌŕş", "▁Ǝ", "š"}, new float[] {0f, 0.4f, 0.5f});
        assertEquals("对", String.join("", c.text));
    }

    @Test public void plainAsciiPassesThroughAndNoTimesMeansZero() {
        ByteBpe.Chars c = ByteBpe.decode(new String[] {"▁ab"}, new float[0]);
        assertEquals("ab", String.join("", c.text));
        assertEquals(0f, c.times[0], 0f);
    }
}
