package th.hansub.live;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AsrTextTest {
    @Test public void cleanRemovesSenseVoiceTags() {
        assertEquals("你好世界", AsrText.clean("<|zh|><|NEUTRAL|><|Speech|><|withitn|>你好世界"));
        assertEquals("你好", AsrText.clean("  你好  "));
        assertEquals("", AsrText.clean(null));
    }
}
