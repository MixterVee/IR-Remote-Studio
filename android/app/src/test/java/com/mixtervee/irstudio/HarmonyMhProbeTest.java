package com.mixtervee.irstudio;

import org.junit.Test;
import static org.junit.Assert.*;

public class HarmonyMhProbeTest {
    @Test public void reportsFullResponseNotJustFirst24Bytes() {
        byte[] input = new byte[64];
        input[0] = (byte) 0xFF;
        input[1] = (byte) 0xFF;
        input[2] = (byte) 0x81;
        input[4] = (byte) 0x81;
        input[63] = (byte) 0xA5;
        String summary = HarmonyMhProbe.responseSummary(input, input.length);
        assertTrue(summary.contains("MH reply received: 64 bytes"));
        assertTrue(summary.contains("MH reply complete hex (64 bytes):"));
        assertTrue(summary.contains("FF FF 81 00 81"));
        assertTrue(summary.contains("00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 A5"));
        assertTrue(summary.contains("Report size: 64/64"));
        assertTrue(summary.contains("Observed FF FF 81 ?? 81 prefix: YES"));
        assertTrue(summary.contains("Last non-zero byte offset: 63"));
        assertTrue(summary.contains("NOT established"));
    }

    @Test public void doesNotClaimUnknownResponsesAreValid() {
        byte[] input = {(byte)0xFF,(byte)0x01,(byte)0xFE,(byte)0x10};
        String summary = HarmonyMhProbe.responseSummary(input, 4);
        assertTrue(summary.contains("MH reply received: 4 bytes"));
        assertTrue(summary.contains("FF 01 FE 10"));
        assertTrue(summary.contains("Report size: unexpected"));
        assertTrue(summary.contains("Observed FF FF 81 ?? 81 prefix: NO"));
        assertTrue(summary.contains("NOT established"));
    }

    @Test public void rejectsEmptyResponse() {
        assertTrue(HarmonyMhProbe.responseSummary(new byte[0], 0)
            .contains("No valid MH reply bytes received"));
    }
}
