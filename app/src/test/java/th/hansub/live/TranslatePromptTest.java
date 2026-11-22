package th.hansub.live;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class TranslatePromptTest {
    @Test public void plainPromptNamesThaiInChineseAndEndsWithTheSentence() {
        String prompt = TranslatePrompt.build("你好，师兄。", "");
        assertEquals("将以下文本翻译为泰语，注意只需要输出翻译后的结果，不要额外解释：\n\n你好，师兄。", prompt);
    }

    @Test public void onlyNamesThatAppearAreSent() {
        String glossary = "张总=ประธานจาง\n小雨=เสี่ยวอวี่\n";
        String prompt = TranslatePrompt.build("小雨，你来了。", glossary);
        assertTrue(prompt.startsWith("参考下面的翻译：\n小雨 翻译成 เสี่ยวอวี่\n\n将以下文本翻译为泰语"));
        assertTrue(!prompt.contains("张总"));
    }

    @Test public void longerNamesComeFirstAndAtMostEightAreSent() {
        StringBuilder glossary = new StringBuilder("云=เมฆ\n云山=ภูเขาเมฆ\n");
        StringBuilder sentence = new StringBuilder("云山");
        for (int i = 0; i < 12; i++) {
            glossary.append("名").append((char) ('A' + i)).append("=x").append(i).append('\n');
            sentence.append("名").append((char) ('A' + i));
        }
        List<String[]> found = TranslatePrompt.matching(sentence.toString(), glossary.toString());
        assertEquals(8, found.size());
        assertEquals("云山", found.get(0)[0]);
    }

    @Test public void pairAcceptsFullWidthEqualsAndSkipsBadLines() {
        assertEquals("张总", TranslatePrompt.pair("张总＝ประธานจาง")[0]);
        assertEquals("ประธานจาง", TranslatePrompt.pair("张总 = ประธานจาง ")[1]);
        assertNull(TranslatePrompt.pair("# comment"));
        assertNull(TranslatePrompt.pair("张总="));
        assertNull(TranslatePrompt.pair("=ไทย"));
        assertNull(TranslatePrompt.pair(""));
    }

    @Test public void cleanRemovesMarkersAndBreaks() {
        assertEquals("สวัสดี พี่ชาย", TranslatePrompt.clean("  สวัสดี\nพี่ชาย<｜hy_place▁holder▁no▁2｜> "));
        assertEquals("", TranslatePrompt.clean(null));
    }

    @Test public void tokenCapGrowsWithLengthButIsBounded() {
        assertEquals(54, TranslatePrompt.maxNewTokens("你"));
        assertEquals(240, TranslatePrompt.maxNewTokens("你".repeat(100)));
    }
}
