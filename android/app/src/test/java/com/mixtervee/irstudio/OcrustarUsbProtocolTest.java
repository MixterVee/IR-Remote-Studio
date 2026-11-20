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

    @Test public void verifiedDifferentBulkEndpointsFor0132And02aa() {
        // User's real dongle: Android detects 045C:0132. The documented
        // iAP-capable USB-C hardware has bulk OUT 0x02 and IN 0x82.
        assertEquals(0x02,
                OcrustarUsbProtocol.expectedBulkOut(0x045C, 0x0132));
        assertEquals(0x82,
                OcrustarUsbProtocol.expectedBulkIn(0x045C, 0x0132));
        // D226 USB-A reference uses a DIFFERENT physical endpoint pair.
        assertEquals(0x01,
                OcrustarUsbProtocol.expectedBulkOut(0x045C, 0x02AA));
        assertEquals(0x81,
                OcrustarUsbProtocol.expectedBulkIn(0x045C, 0x02AA));
        // A Harmony or an unrecognized model must never be mapped.
        assertEquals(-1,
                OcrustarUsbProtocol.expectedBulkOut(0x046D, 0xC124));
        assertEquals(-1,
                OcrustarUsbProtocol.expectedBulkIn(0x045C, 0xFFFF));
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

    @Test public void native0132AcceptsVersionIdentityWhileLegacyStillRejectsIt() {
        // Published native Android implementation for 045C:0132 classifies
        // both FC model and FA firmware/version replies as identity, and
        // does not send the extra FA command before FE learning.
        byte[] firmware = {(byte) 0xFA, (byte) 0xFA,
                (byte) 0xFA, (byte) 0xFA, 0x22, 0x01};
        assertTrue(OcrustarUsbProtocol.identifyForUsbPid(
                0x0132, firmware, firmware.length).contains("0132 version"));
        try {
            OcrustarUsbProtocol.identifyForUsbPid(
                    0x02AA, firmware, firmware.length);
            fail("Legacy path must not accept firmware reply as model identity");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Unrecognized"));
        }
        byte[] d552 = {(byte) 0xFC, (byte) 0xFC,
                (byte) 0xFC, (byte) 0xFC, 0x70, 0x01};
        assertEquals("D552", OcrustarUsbProtocol.identifyForUsbPid(
                0x0132, d552, d552.length));
        assertEquals("D552", OcrustarUsbProtocol.identifyForUsbPid(
                0x02AA, d552, d552.length));
        byte[] garbage = {1, 2, 3, 4, 5, 6};
        try {
            OcrustarUsbProtocol.identifyForUsbPid(
                    0x0132, garbage, garbage.length);
            fail("Native device must still reject unrecognized reply");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Unrecognized"));
        }
    }

    @Test public void onlyFcModelIdentityReceivesOneFaAckOn0132() {
        byte[] d552 = {(byte) 0xFC,(byte) 0xFC,(byte) 0xFC,
                (byte) 0xFC,0x70,0x01};
        byte[] firmware = {(byte) 0xFA,(byte) 0xFA,(byte) 0xFA,
                (byte) 0xFA,0x22,0x01};
        assertTrue("FC D552 model response needs FA acknowledgment",
                OcrustarUsbProtocol.shouldAcknowledgeIdentity(
                        0x0132, d552, d552.length));
        assertTrue("Legacy D552 response still uses FA acknowledgment",
                OcrustarUsbProtocol.shouldAcknowledgeIdentity(
                        0x02AA, d552, d552.length));
        assertFalse("FA-version reply must not start an ACK loop",
                OcrustarUsbProtocol.shouldAcknowledgeIdentity(
                        0x0132, firmware, firmware.length));
        try {
            OcrustarUsbProtocol.shouldAcknowledgeIdentity(0x0132,
                    new byte[]{1,2,3,4,5,6}, 6);
            fail("Unrecognized USB identity must not be acknowledged");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void native0132CapturesFeFrameWithoutSeparateFfAck() {
        byte[] raw = rawTimings(8992, 4496, 560, 560, 560, 1680,
                560, 560, 560, 1680, 560, 560, 560, 40000);
        OcrustarUsbProtocol.Capture capture = new OcrustarUsbProtocol.Capture();
        byte[] frame = packet(raw);
        capture.accept(frame, frame.length);
        assertTrue(capture.complete());
        assertFalse(capture.sawLearnAck());
        assertTrue(capture.pronto().startsWith("0000"));
        assertTrue(capture.diagnostic().contains("FE capture header: FOUND"));
    }

    @Test public void real0132FirmwareReplyIsNotAnIrLearningAcknowledgement() {
        // Actual user USB capture after v0.4.8: FA FA FA FA 22 01.
        // Source SDK documents FA×4 + two bytes as firmware/version metadata.
        byte[] observed = {(byte) 0xFA, (byte) 0xFA,
                (byte) 0xFA, (byte) 0xFA, 0x22, 0x01};
        assertTrue(OcrustarUsbProtocol.isFirmwareVersionReply(observed, 6));
        assertEquals("FA FA FA FA 22 01",
                OcrustarUsbProtocol.firmwareVersionReply(observed, 6));
        byte[] ack = {(byte) 0xFF, (byte) 0xFF,
                (byte) 0xFF, (byte) 0xFF};
        assertFalse(OcrustarUsbProtocol.isFirmwareVersionReply(ack, 4));

        OcrustarUsbProtocol.Capture capture = new OcrustarUsbProtocol.Capture();
        capture.accept(observed, observed.length);
        assertFalse(capture.sawLearnAck());
        assertFalse(capture.complete());
        assertTrue(capture.diagnostic().contains(
                "Firmware/version response (FA FA FA FA): SEEN"));
        assertTrue(capture.diagnostic().contains(
                "Learn ACK (FF FF FF FF): not observed"));
        capture.accept(ack, ack.length);
        assertTrue(capture.sawLearnAck());
        assertFalse(capture.complete());
    }

    @Test public void diagnosticsDistinguishNoResponseFromLearnAckAndPayload() {
        OcrustarUsbProtocol.Capture capture = new OcrustarUsbProtocol.Capture();
        assertTrue(capture.diagnostic().contains("USB receive packets: 0"));
        assertTrue(capture.diagnostic().contains("First USB bytes: (none)"));
        assertFalse(capture.sawLearnAck());

        // The 0132 learning command first elicits an FF-FF-FF-FF ACK.
        byte[] ack = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
        capture.accept(ack, ack.length);
        assertTrue(capture.sawLearnAck());
        assertFalse(capture.complete());
        assertTrue(capture.diagnostic().contains("Learn ACK (FF FF FF FF): SEEN"));
        assertTrue(capture.diagnostic().contains("FE capture header: not observed"));

        // An FE-framed response may arrive in separate USB transfers.
        byte[] header = {(byte) 0xFE, (byte) 0xFE, (byte) 0xFE,
                (byte) 0xFE, 0, 16, 32, 64, 96, 112};
        capture.accept(header, header.length);
        assertEquals(16, capture.expected());
        assertEquals(4, capture.received());
        assertFalse(capture.complete());
        assertTrue(capture.diagnostic().contains("FE capture header: FOUND"));
        assertTrue(capture.diagnostic().contains("Capture bytes collected: 4 / 16"));
        assertTrue(capture.diagnostic().contains("USB receive packets: 2"));
        assertTrue(capture.diagnostic().contains("USB bytes read: 14"));
    }

    @Test public void boundedReceivePrefixDoesNotExposeWholeIrCapture() {
        OcrustarUsbProtocol.Capture capture = new OcrustarUsbProtocol.Capture();
        byte[] noise = new byte[64];
        java.util.Arrays.fill(noise, (byte) 0x34);
        capture.accept(noise, noise.length);
        String report = capture.diagnostic();
        assertTrue(report.contains("USB bytes read: 64"));
        assertTrue(report.contains("FE capture header: not observed"));
        String first = report.substring(report.indexOf("First USB bytes: "));
        assertTrue(first.length() < 125);
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

        byte[] tooShort = packet(rawTimings(9000, 4500, 560, 560, 560, 560));
        OcrustarUsbProtocol.Capture shortCapture = new OcrustarUsbProtocol.Capture();
        shortCapture.accept(tooShort, tooShort.length);
        try {
            shortCapture.decodeTimings();
            fail("short signal should not be accepted");
        } catch (IllegalArgumentException expected) { }
    }
}
