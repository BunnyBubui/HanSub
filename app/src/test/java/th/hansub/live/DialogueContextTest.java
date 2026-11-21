package th.hansub.live;

import org.junit.Test;
import static org.junit.Assert.*;

public class DialogueContextTest {
    @Test public void boundedHistoryAndDuplicateFinal() {
        DialogueContext c=new DialogueContext(2);
        c.record("你好");
        c.record("你好");
        c.record("今天怎么样");
        c.record("再见");
        assertEquals(2,c.previous().size());
        assertEquals("今天怎么样",c.previous().get(0));
    }
    @Test public void glossaryParsing() {
        DialogueContext c=new DialogueContext(3);
        c.parseNames("张总=คุณจาง\n小雨=เสี่ยวอวี่\ninvalid");
        assertEquals("คุณจาง",c.glossary().get("张总"));
        assertEquals(2,c.glossary().size());
        c.parseNames("阿明=อาหมิง");
        assertEquals(1,c.glossary().size());
    }
}
