package com.mixtervee.irstudio;

import org.junit.Test;
import static org.junit.Assert.*;

public class HarmonyIdentityProbeTest {
    @Test public void decodesVersionResponse() {
        byte[] result = new byte[64];
        result[0]=(byte)0x25;
        result[1]=(byte)0x34;
        result[2]=(byte)0x21;
        String output=HarmonyIdentityProbe.decodeResponse(result,64);
        assertTrue(output.contains("format: valid"));
        assertTrue(output.contains("Firmware version: 3.4"));
        assertTrue(output.contains("Hardware revision: 2.1"));
    }

    @Test public void rejectsWrongResponseType() {
        byte[] result = {(byte)0x90, 0, 0};
        assertTrue(HarmonyIdentityProbe.decodeResponse(result,3)
            .contains("Unexpected response type"));
    }
}
