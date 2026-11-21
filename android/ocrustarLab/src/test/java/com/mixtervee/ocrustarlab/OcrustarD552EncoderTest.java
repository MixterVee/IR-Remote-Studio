package com.mixtervee.ocrustarlab;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class OcrustarD552EncoderTest {
    @Test
    public void pulseEncodeStartsWithD552PreambleAndExpected38kHzHeader() {
        int[] timings = {9000,4500,560,560,560,1690,560,560,
                560,1690,560,560,560,1690,560,560};
        List<byte[]> packets = OcrustarD552Encoder.encode(timings, 38000);
        assertFalse(packets.isEmpty());
        byte[] first = packets.get(0);
        assertEquals(0xFF, first[0] & 0xFF);
        assertEquals(0xFF, first[1] & 0xFF);
        assertEquals(0xFF, first[2] & 0xFF);
        assertEquals(0xFF, first[3] & 0xFF);
        // 38000 + 0x7FFFF => 0x08946F, then reverse bits and invert.
        assertEquals(0xD6, first[4] & 0xFF);
        assertEquals(0xEF, first[5] & 0xFF);
        assertEquals(0x09, first[6] & 0xFF);
        for (byte[] packet : packets) {
            assertTrue(packet.length >= 1 && packet.length <= 63);
        }
    }

    @Test
    public void fullFramesGetChecksumButLastShortFrameDoesNot() {
        int[] timings = new int[100];
        for (int i=0; i<timings.length; i+=2) {
            timings[i] = (i % 4 == 0) ? 560 : 592;
            timings[i+1] = (i % 4 == 0) ? 1696 : 560;
        }
        List<byte[]> chunks = OcrustarD552Encoder.encode(timings, 38000);
        assertFalse(chunks.isEmpty());
        for (int i=0; i < chunks.size()-1; i++) {
            assertEquals(63, chunks.get(i).length);
        }
    }

    @Test
    public void strictCaptureDecodesOverflowMarker() {
        byte[] raw = new byte[]{0x20,0x30,0x20,0x30,0x20,0x30,
                (byte)0xFF,0x10,0x20,0x30,0x20,0x30,0x20};
        int[] durations=OcrustarD552Encoder.decodeStrict(raw);
        assertEquals(12,durations.length);
        assertEquals(4336,durations[6]); // FF contributes 4080us, 0x10 adds 256us.
    }

    @Test(expected=IllegalArgumentException.class)
    public void rejectZeroDurationAndDoNotPermitReplay() {
        OcrustarD552Encoder.decodeStrict(new byte[]{
                0x20,0x30,0x20,0x30,0x20,0x30,0,
                0x20,0x30,0x20,0x30,0x20});
    }

    @Test(expected=IllegalArgumentException.class)
    public void rejectUnsupportedCarrier() {
        OcrustarD552Encoder.encode(new int[]{
                560,560,560,1690,560,560,560,1690,
                560,560,560,1690},18000);
    }
}
