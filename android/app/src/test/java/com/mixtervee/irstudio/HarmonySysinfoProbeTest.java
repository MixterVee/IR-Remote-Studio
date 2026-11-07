package com.mixtervee.irstudio;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class HarmonySysinfoProbeTest {
    @Test public void requestTargetsOnlySysinfoRead() {
        byte[] cmd = HarmonySysinfoProbe.readRequest();
        assertEquals(64, cmd.length);
        assertEquals(0xFF, cmd[0] & 0xFF);
        assertEquals(0x01, cmd[1] & 0xFF);
        assertEquals(0x03, cmd[2] & 0xFF);
        assertEquals(0x02, cmd[3] & 0xFF);
        assertEquals(0x80, cmd[4] & 0xFF);
        assertEquals('R', cmd[19]);
        String command = new String(cmd, StandardCharsets.US_ASCII);
        assertTrue(command.contains("/sys/sysinfo"));
        assertFalse(command.contains("/cfg/"));
        assertFalse(command.contains("/ir/"));
    }

    @Test public void acceptsBoundedFileLengthsOnly() {
        byte[] header = new byte[64];
        header[9] = 0x02;
        header[10] = 0x32; // 562 bytes
        assertEquals(562, HarmonySysinfoProbe.declaredLength(header));
        header[9] = 0x04;
        assertEquals(-1, HarmonySysinfoProbe.declaredLength(header)); // >1000
        assertEquals(-1, HarmonySysinfoProbe.declaredLength(new byte[63]));
    }

    @Test public void redactsDeviceIdentifiersAndUnknownKeys() {
        String input = "fw_ver 1.22\nhw_ver 0007\narch 02\nfw_type 01\n"
                + "guid SECRET_GUID_ABC\nserial_number SECRET_SERIAL_XYZ\n"
                + "private_key SECRET_NOT_TO_SHARE\n";
        String safe = HarmonySysinfoProbe.safeMetadata(
                input.getBytes(StandardCharsets.UTF_8));
        assertTrue(safe.contains("fw_ver: 1.22"));
        assertTrue(safe.contains("hw_ver: 0007"));
        assertTrue(safe.contains("arch: 02"));
        assertFalse(safe.contains("SECRET"));
        assertFalse(safe.contains("private_key"));
    }
}
