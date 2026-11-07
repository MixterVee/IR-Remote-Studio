package com.mixtervee.irstudio;

import org.junit.Test;
import static org.junit.Assert.*;

public class HarmonyUsbIdsTest {
    @Test public void identifiesHarmony300350WithoutAssumingOtherLogitechDevices() {
        assertTrue(HarmonyUsbIds.isHarmony300Or350(0x046D, 0xC124));
        assertTrue(HarmonyUsbIds.isLikelyHarmony(0x046D, 0xC124));
        assertTrue(HarmonyUsbIds.isLikelyHarmony(0x046D, 0xC122));
        assertFalse(HarmonyUsbIds.isHarmony300Or350(0x046D, 0xC122));
        assertFalse(HarmonyUsbIds.isLikelyHarmony(0x046D, 0xC52B));
        assertFalse(HarmonyUsbIds.isLikelyHarmony(0x1234, 0xC124));
        assertTrue(HarmonyUsbIds.identify(0x046D, 0xC124).contains("300/350"));
    }
}
