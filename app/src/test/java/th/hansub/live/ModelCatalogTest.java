package th.hansub.live;

import org.junit.Test;
import static org.junit.Assert.*;

public class ModelCatalogTest {
    @Test public void fastIsDefault() {
        assertEquals(ModelCatalog.FAST, ModelCatalog.normalizeMode(null));
        assertArrayEquals(new String[]{"zh","th"}, ModelCatalog.requiredPacks("fast"));
    }
    @Test public void pivotNeedsEnglish() {
        assertArrayEquals(new String[]{"zh","en","th"}, ModelCatalog.requiredPacks("pivot"));
    }
    @Test public void offlineSpeechIsOptIn() {
        assertFalse(ModelCatalog.isOfflineSpeech(null));
        assertTrue(ModelCatalog.isOfflineSpeech("offline"));
    }
}
