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
    @Test public void cloudModesFallbackToChineseAndThaiPacks() {
        assertEquals(ModelCatalog.CLOUD_FAST,ModelCatalog.normalizeMode("cloud_fast"));
        assertEquals(ModelCatalog.CLOUD_QUALITY,ModelCatalog.normalizeMode("cloud_quality"));
        assertTrue(ModelCatalog.isCloud("cloud_fast"));
        assertFalse(ModelCatalog.isCloud("pivot"));
        assertArrayEquals(new String[]{"zh","th"}, ModelCatalog.requiredPacks("cloud_quality"));
    }
    @Test public void offlineSpeechIsOptIn() {
        assertFalse(ModelCatalog.isOfflineSpeech(null));
        assertTrue(ModelCatalog.isOfflineSpeech("offline"));
    }
}
