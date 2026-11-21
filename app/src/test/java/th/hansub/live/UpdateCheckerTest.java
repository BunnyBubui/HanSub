package th.hansub.live;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class UpdateCheckerTest {
    @Test public void readsTheVersionCodeFromReleaseNotes() {
        assertEquals(1042L, UpdateChecker.parseVersionCode(
                "Preview build. versionCode: 1042. Commit abc. Debug APK."));
        assertEquals(7L, UpdateChecker.parseVersionCode("versionCode:7"));
    }

    @Test public void missingOrBrokenNotesGiveZero() {
        assertEquals(0L, UpdateChecker.parseVersionCode(null));
        assertEquals(0L, UpdateChecker.parseVersionCode(""));
        assertEquals(0L, UpdateChecker.parseVersionCode("no number here"));
        assertEquals(0L, UpdateChecker.parseVersionCode("versionCode: 99999999999999999999999"));
    }

    @Test public void comparesRemoteWithInstalled() {
        assertEquals(UpdateChecker.NEWER, UpdateChecker.compare(1050L, 1042L));
        assertEquals(UpdateChecker.CURRENT, UpdateChecker.compare(1042L, 1042L));
        assertEquals(UpdateChecker.CURRENT, UpdateChecker.compare(1000L, 1042L));
        assertEquals(UpdateChecker.FAILED, UpdateChecker.compare(0L, 1042L));
    }
}
