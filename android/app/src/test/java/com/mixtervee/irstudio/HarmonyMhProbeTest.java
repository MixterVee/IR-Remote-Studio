package com.mixtervee.irstudio;

import org.junit.Test;
import static org.junit.Assert.*;

public class HarmonyMhProbeTest {
    @Test public void reportsOnlyExpectedHexBytes() {
        byte[] input = {(byte)0xFF,(byte)0x01,(byte)0xFE,(byte)0x10};
        String summary = HarmonyMhProbe.responseSummary(input, 4);
        assertTrue(summary.contains("MH reply received: 4 bytes"));
        assertTrue(summary.contains("FF 01 FE 10"));
    }
    @Test public void rejectsEmptyResponse() {
        assertTrue(HarmonyMhProbe.responseSummary(new byte[0],0)
            .contains("No valid MH acknowledgement"));
    }
}
