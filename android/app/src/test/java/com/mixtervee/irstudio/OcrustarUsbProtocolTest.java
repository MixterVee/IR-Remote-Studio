package com.mixtervee.irstudio;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import static org.junit.Assert.*;

/** Deterministic offline USB protocol tests; never contacts real hardware. */
public class OcrustarUsbProtocolTest {
    @Test public void matchesOnlyKnownElkSmartUsbIds() {
        assertTrue(OcrustarUsbProtocol.isCandidate(0x045C, 0x02AA));
        assertTrue(OcrustarUsbProtocol.isCandidate(0x045C, 0x0134));
        assertTrue(OcrustarUsbProtocol.isCandidate(0x045C, 0x0132));
        assertFalse(OcrustarUsbProtocol.isCandidate(0x046D, 0xC124));
        assertFalse(OcrustarUsbProtocol.isCandidate(0x045C, 0xFFFF));
        assertEquals("045C:02AA", OcrustarUsbProtocol.usbId(0x045C, 0x02AA));
    }

    @Test public void validatesKnownHandshakeAndRefusesUnrecognizedVersions() {
        assertEquals("D226", OcrustarUsbProtocol.identifyHello(
                new byte[]{(byte) 0xFC,(byte) 0xFC,(byte) 0xFC,
                        (byte) 0xFC,0x02,(byte) 0xAA},6));
        assertEquals("D552", OcrustarUsbProtocol.identifyHello(
                new byte[]{(byte) 0xFC,(byte) 0xFC,(byte) 0xFC,
                        (byte) 0xFC,0x70,0x01},6));
        try {
            OcrustarUsbProtocol.identifyHello(
                new byte[]{(byte) 0xFC,(byte) 0xFC,(byte) 0xFC,
                        (byte) 0xFC,0x55,0x55},6);
            fail("unknown hardware must be refused");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Unknown dongle"));
        }
        try {
            OcrustarUsbProtocol.identifyHello(
                new byte[]{0,0,0,0,0x02,(byte) 0xAA},6);
            fail("must refuse non-ElkSmart protocol");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Unrecognized"));
        }
    }

    /** Encode learned raw duration bytes with the documented 16us scaling. */
    private static byte[] rawTimings(int... micros) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int value : micros) {
            int units = value / 16;
            while (units >= 255) {
                out.write(255);
                units -= 255;
            }
            out.write(units);
        }
        return out.toByteArray();
    }

    private static byte[] packet(byte[] raw) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < 4; i++) out.write(0xFE);
        out.write((raw.length >>> 8) & 0xFF);
        out.write(raw.length & 0xFF);
        out.write(raw, 0, raw.length);
        return out.toByteArray();
    }

    @Test public void assemblesFragmentedIrCaptureAndProducesUsablePronto() {
        byte[] raw = rawTimings(8992, 4496, 560, 560, 560, 1680,
                560, 560, 560, 1680, 560, 560, 560, 40000);
        byte[] frame = packet(raw);
        OcrustarUsbProtocol.Capture capture = new OcrustarUsbProtocol.Capture();
        // The four-byte FE learning indication and the actual header
        // can arrive on the same or separate USB transfers.
        capture.accept(new byte[]{(byte) 0xFE,(byte) 0xFE,
                (byte) 0xFE,(byte) 0xFE},4);
        capture.accept(frame, 3);
        assertFalse(capture.complete());
        byte[] part = java.util.Arrays.copyOfRange(frame, 3, 11);
        capture.accept(part, part.length);
        assertFalse(capture.complete());
        byte[] remainder = java.util.Arrays.copyOfRange(frame, 11, frame.length);
        capture.accept(remainder, remainder.length);
        assertTrue(capture.complete());
        assertEquals(raw.length, capture.expected());
        int[] timings = capture.decodeTimings();
        assertEquals(14, timings.length);
        assertEquals(8992, timings[0]);
        assertEquals(4496, timings[1]);
        String pronto = capture.pronto();
        IrplusCodec.Signal decoded = IrplusCodec.decodePronto(pronto);
        assertEquals(14, decoded.microseconds.length);
        assertTrue(decoded.carrierHz > 37500 && decoded.carrierHz < 38500);
    }

    @Test public void ignoresIncompleteResponseAndRejectsMalformedTimings() {
        OcrustarUsbProtocol.Capture capture = new OcrustarUsbProtocol.Capture();
        capture.accept(new byte[]{(byte) 0xFE,(byte) 0xFE,
                (byte) 0xFE,(byte) 0xFE},4);
        assertFalse(capture.complete());
        try {
            capture.decodeTimings();
            fail("no signal should be returned");
        } catch (IllegalStateException expected) { }

        byte[] tooShort = packet(rawTimings(9000, 4500, 560, 560));
        OcrustarUsbProtocol.Capture shortCapture = new OcrustarUsbProtocol.Capture();
        shortCapture.accept(tooShort, tooShort.length);
        try {
            shortCapture.decodeTimings();
            fail("short signal should not be accepted");
        } catch (IllegalArgumentException expected) { }
    }
}
