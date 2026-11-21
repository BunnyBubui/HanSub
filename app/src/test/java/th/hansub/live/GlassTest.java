package th.hansub.live;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GlassTest {
    @Test public void alphaReplacesOnlyTheAlphaChannel() {
        assertEquals(0x2eff9f45, Glass.alpha(0xffff9f45, 0x2e));
        assertEquals(0xff112233, Glass.alpha(0x00112233, 0x1ff));
        assertEquals(0x00112233, Glass.alpha(0xff112233, -5));
    }

    @Test public void mixMovesEachChannelAndKeepsAlpha() {
        assertEquals(0xff808080, Glass.mix(Glass.BLACK, Glass.WHITE, 0.5f));
        assertEquals(0x80ff0000, Glass.mix(0x80ff0000, 0xff00ff00, 0f));
        assertEquals(0x8000ff00, Glass.mix(0x80ff0000, 0xff00ff00, 1f));
    }

    @Test public void accentGlassGoesFromLighterToDarker() {
        assertEquals(Glass.ACCENT_HI, Glass.top(Glass.ACCENT));
        assertEquals(Glass.ACCENT_LO, Glass.bottom(Glass.ACCENT));
        assertTrue(brightness(Glass.ACCENT_HI) > brightness(Glass.ACCENT));
        assertTrue(brightness(Glass.ACCENT) > brightness(Glass.ACCENT_LO));
    }

    @Test public void textOnTheAccentButtonIsReadable() {
        // Dark text on the orange button: the gap in brightness must be large.
        assertTrue(brightness(Glass.ACCENT) - brightness(Glass.ON_ACCENT) > 120);
    }

    @Test public void pageIsDarkerThanPanels() {
        assertTrue(brightness(Glass.BG) < brightness(Glass.PANEL));
        assertTrue(brightness(Glass.BG_DEEP) <= brightness(Glass.BG));
    }

    private static int brightness(int c) {
        return (((c >> 16) & 255) + ((c >> 8) & 255) + (c & 255)) / 3;
    }
}
