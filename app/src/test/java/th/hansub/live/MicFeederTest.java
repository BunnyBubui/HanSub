package th.hansub.live;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class MicFeederTest {
    /** Little-endian 16-bit sound from sample values. */
    private static byte[] pcm(int... samples) {
        byte[] b = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            b[i * 2] = (byte) (samples[i] & 0xff);
            b[i * 2 + 1] = (byte) ((samples[i] >> 8) & 0xff);
        }
        return b;
    }

    private static int sample(byte[] b, int index) {
        return (short) ((b[index * 2] & 0xff) | (b[index * 2 + 1] << 8));
    }

    @Test public void downsampleAveragesEveryThreeSamples() {
        byte[] out = new byte[8];
        int written = MicFeeder.downsample(pcm(3, 6, 9, -300, 0, 300), 12, 3, out);
        assertEquals(4, written);
        assertEquals(6, sample(out, 0));
        assertEquals(0, sample(out, 1));
    }

    @Test public void downsampleDropsAPartialGroup() {
        byte[] out = new byte[8];
        assertEquals(2, MicFeeder.downsample(pcm(30, 30, 30, 1, 1), 10, 3, out));
    }

    @Test public void downsampleKeepsNegativeAndLargeValues() {
        byte[] out = new byte[4];
        MicFeeder.downsample(pcm(-32768, -32768, -32768), 6, 3, out);
        assertEquals(-32768, sample(out, 0));
        MicFeeder.downsample(pcm(32767, 32767, 32767), 6, 3, out);
        assertEquals(32767, sample(out, 0));
    }

    @Test public void peakIsTheLargestAbsoluteSample() {
        assertEquals(0, MicFeeder.peak(pcm(0, 0, 0), 6));
        assertEquals(500, MicFeeder.peak(pcm(10, -500, 20), 6));
        assertEquals(32768, MicFeeder.peak(pcm(-32768), 2));
    }
}
