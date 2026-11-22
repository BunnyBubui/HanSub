package th.hansub.live;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SubTextTest {
    @Test public void chineseLeftoversAreReplaced() {
        assertEquals("คุณ [王小明] มาแล้ว", SubText.fixLeftovers("คุณ王小明มาแล้ว", h -> "[" + h + "]"));
    }

    @Test public void chinesePunctuationBecomesSpaces() {
        assertEquals("ไปกัน เร็ว", SubText.fixLeftovers("ไปกัน，เร็ว。", h -> h));
    }

    @Test public void cleanThaiIsUnchanged() {
        assertEquals("สวัสดีครับ", SubText.fixLeftovers("สวัสดีครับ", h -> "X"));
        assertEquals("", SubText.fixLeftovers(null, h -> "X"));
    }

    @Test public void emptyRomanizationJustDropsTheRun() {
        assertEquals("ชื่อ คือ", SubText.fixLeftovers("ชื่อ李คือ", h -> ""));
    }
}
