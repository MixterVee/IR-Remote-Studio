package com.mixtervee.irstudio;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class HarmonyIrCaptureProbeTest {

    @Test public void opensOnlyTheTransientIrCapReadPath() {
        byte[] command = HarmonyIrCaptureProbe.openCommand();
        assertEquals(64, command.length);
        assertEquals(0xFF, command[0] & 0xFF);
        assertEquals(0x01, command[1] & 0xFF);
        assertEquals(0x00, command[2] & 0xFF);
        assertEquals(0x02, command[3] & 0xFF);
        assertEquals(0x80, command[4] & 0xFF);
        String text = new String(command);
        assertTrue(text.contains("/ir/ir_cap"));
        assertFalse(text.contains("/cfg/"));
        assertEquals('R', command[17]);
        for (int i = 19; i < 64; i++) assertEquals(0, command[i]);
    }

    @Test public void captureAckStopAndCloseAre64BytePadded() {
        byte[] ack = HarmonyIrCaptureProbe.captureAck((byte)0x06);
        byte[] stop = HarmonyIrCaptureProbe.stopCommand((byte)0x06);
        byte[] close = HarmonyIrCaptureProbe.closeCommand((byte)0x06);
        assertEquals(64, ack.length);
        assertEquals(64, stop.length);
        assertEquals(64, close.length);
        assertArrayEquals(new byte[]{(byte)0xFF, 0x04, 0x01, 0x02, 0x01, 0x06, 0x01, 0x00},
                prefix(ack, 8));
        assertArrayEquals(new byte[]{(byte)0xFF, 0x06, 0x02, 0x02, 0x01, 0x06, 0x01, 0x06},
                prefix(stop, 8));
        assertArrayEquals(new byte[]{(byte)0xFF, 0x07, 0x03, 0x01, 0x01, 0x06},
                prefix(close, 6));
        for (int i = 8; i < 64; i++) {
            assertEquals(0, ack[i]);
            assertEquals(0, stop[i]);
        }
        for (int i = 6; i < 64; i++) assertEquals(0, close[i]);
    }

    @Test public void decodesPacketTimingWordsAndFrequency() {
        byte[] report = new byte[64];
        report[0] = (byte) 0x90;
        report[1] = (byte) 0x90;
        report[2] = 0; report[3] = 0;  // skipped leading word
        report[4] = 0x01; report[5] = (byte)0xF4; // 500us
        report[6] = 0; report[7] = 19; // 19 carrier cycles -> 38000 Hz
        report[63] = 8;
        List<Integer> words = new ArrayList<>();
        assertTrue(HarmonyIrCaptureProbe.appendSignalWords(report, words));
        assertEquals(3, words.size());
        assertEquals(500, (int)words.get(1));
        assertEquals(19, (int)words.get(2));
        assertTrue(HarmonyIrCaptureProbe.summary(words).contains("38000 Hz"));
    }

    @Test public void rejectsMalformedOrTruncatedIrPackets() {
        List<Integer> words = new ArrayList<>();
        assertFalse(HarmonyIrCaptureProbe.appendSignalWords(new byte[2], words));
        byte[] report = new byte[64];
        report[63] = 63;
        assertFalse(HarmonyIrCaptureProbe.appendSignalWords(report, words));
        report[63] = 64;
        assertFalse(HarmonyIrCaptureProbe.appendSignalWords(report, words));
        report[63] = 8;
        assertTrue(HarmonyIrCaptureProbe.appendSignalWords(report, words));
        assertEquals(3, words.size());
    }

    private static byte[] prefix(byte[] bytes, int size) {
        byte[] result = new byte[size];
        System.arraycopy(bytes, 0, result, 0, size);
        return result;
    }
}
