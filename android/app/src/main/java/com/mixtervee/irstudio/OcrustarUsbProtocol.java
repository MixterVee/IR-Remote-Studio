package com.mixtervee.irstudio;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Only the observed, documented ElkSmart D226/D552 USB handshake and IR
 * receive protocol. No private Ocrustar app access, firmware or config writes.
 *
 * Reference: https://github.com/deadboy18/Ocrustar-USB-IR/blob/main/docs/PROTOCOL.md
 * Independent MIT reverse engineering by deadboy18.
 *
 * Raw learn response does not contain the carrier frequency. 38 kHz below
 * is an explicit default/estimate, NOT a measurement of captured IR.
 */
public final class OcrustarUsbProtocol {
    public static final int VID = 0x045C;
    public static final int ESTIMATED_CARRIER_HZ = 38000;
    public static final byte[] HELLO = {(byte) 0xFC, (byte) 0xFC, (byte) 0xFC, (byte) 0xFC};
    public static final byte[] ACK = {(byte) 0xFA, (byte) 0xFA, (byte) 0xFA, (byte) 0xFA};
    public static final byte[] START_LEARN = {(byte) 0xFE, (byte) 0xFE, (byte) 0xFE, (byte) 0xFE};
    public static final byte[] STOP_LEARN = {(byte) 0xFD, (byte) 0xFD, (byte) 0xFD, (byte) 0xFD};

    // Identified ElkSmart/Ocrustar USB product family. A valid protocol
    // handshake is ALSO required: product IDs alone never authorize writes.
    private static final int[] PIDS = {
            0x02AA, 0x014A, 0x0134, 0x0195, 0x0184,
            0x0130, 0x0189, 0x018F, 0x0131, 0x0132, 0x0133
    };

    private OcrustarUsbProtocol() {}

    public static boolean isCandidate(int vid, int pid) {
        if (vid != VID) return false;
        for (int known : PIDS) if (known == pid) return true;
        return false;
    }

    /**
     * Bulk endpoint addresses differ between the documented Ocrustar/ElkSmart
     * hardware variants, even though their protocol commands are similar.
     * 045C:0132 (USB-C / iAP-capable): OUT 0x02, IN 0x82.
     * 045C:02AA (D226): OUT 0x01, IN 0x81.
     *
     * 0132 source: ariemeir/infrared-reverse-engineering-iphone-remote-app
     * elksmart_ir_reverse_engineering.md §2 (actual USB descriptors).
     * 02AA source: deadboy18/Ocrustar-USB-IR docs/PROTOCOL.md §1.
     * Remaining candidate IDs have not been verified; the earlier D226
     * default is retained only with identity-gated capture.
     */
    public static int expectedBulkOut(int vid, int pid) {
        if (!isCandidate(vid, pid)) return -1;
        return pid == 0x0132 ? 0x02 : 0x01;
    }

    public static int expectedBulkIn(int vid, int pid) {
        int out = expectedBulkOut(vid, pid);
        return out < 0 ? -1 : (0x80 | out);
    }

    /**
     * On the 045C:0132 family, FA FA FA FA + 2 bytes is an identity/
     * firmware-version response, not an IR-learning acknowledgement.
     * Verified in independent reverse-engineering of the Android SDK.
     */
    public static boolean isFirmwareVersionReply(byte[] data, int count) {
        if (data == null || count < 6 || count > data.length) return false;
        for (int i = 0; i < 4; i++)
            if ((data[i] & 0xFF) != 0xFA) return false;
        return true;
    }

    public static String firmwareVersionReply(byte[] data, int count) {
        if (!isFirmwareVersionReply(data, count))
            throw new IllegalArgumentException("Not an Ocrustar firmware reply.");
        return String.format(Locale.ROOT, "FA FA FA FA %02X %02X",
                data[4] & 0xFF, data[5] & 0xFF);
    }

    public static String usbId(int vid, int pid) {
        return String.format(Locale.ROOT, "%04X:%04X", vid, pid);
    }

    public static String identifyHello(byte[] b, int length) {
        if (b == null || length < 6 || length > b.length)
            throw new IllegalArgumentException("No complete ElkSmart identity reply.");
        for (int i = 0; i < 4; i++)
            if (b[i] != (byte) 0xFC)
                throw new IllegalArgumentException("Unrecognized USB handshake. No learn command sent.");
        int hi = b[4] & 0xFF, lo = b[5] & 0xFF;
        if (hi == 0x02 && lo == 0xAA) return "D226";
        if (hi == 0x70 && lo == 0x01) return "D552";
        throw new IllegalArgumentException(String.format(Locale.ROOT,
                "Unknown dongle revision %02X%02X; learning disabled.", hi, lo));
    }

    /**
     * Identify 045C:0132 from either FC×4 + D226/D552 model identity,
     * or FA×4 + firmware/version metadata. Whether an FA follow-up is
     * appropriate depends on the reply header; see shouldAcknowledgeIdentity.
     * The older 02AA path retains its existing model-handshake behavior.
     */
    public static String identifyForUsbPid(int pid, byte[] bytes, int count) {
        if (pid == 0x0132) {
            if (isFirmwareVersionReply(bytes, count))
                return "0132 version " + firmwareVersionReply(bytes, count);
            return identifyHello(bytes, count);
        }
        return identifyHello(bytes, count);
    }

    /**
     * Experimental 045C:0132 handshake correction. When real hardware
     * responds FC FC FC FC 70 01 (D552), send the single documented
     * FA FA FA FA acknowledgement before FE learning. If an FA-version
     * reply is already received instead, do NOT send FA again: that can
     * cause an endless acknowledgment loop. Invalid replies fail closed.
     */
    public static boolean shouldAcknowledgeIdentity(int pid, byte[] bytes, int count) {
        if (pid == 0x0132 && isFirmwareVersionReply(bytes, count)) return false;
        identifyHello(bytes, count);
        return true;
    }

    /**
     * Diagnostic-only silent learning probe for PID 0132.
     * Schedule at 2.5, 5.5 and 9 seconds after entering learning mode.
     * Stop on the first USB response (even if not a valid FE capture).
     * Never retry indefinitely, never transmit unrelated commands.
     */
    public static boolean silentLearnRetryDue(long elapsedMs, int retries,
                                              boolean sawUsbLearnReply) {
        if (sawUsbLearnReply || retries < 0 || retries >= 3) return false;
        final long[] checkpointsMs = {2500L, 5500L, 9000L};
        return elapsedMs >= checkpointsMs[retries];
    }

    /**
     * Bounded stream assembly: accepts multiple USB bulk chunks. Does not
     * assume every USB transfer is an entire learn response.
     */
    public static final class Capture {
        private static final int MAX_RAW = 8192;
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        private final ByteArrayOutputStream payload = new ByteArrayOutputStream();
        private int expected = -1;
        private int usbPackets = 0;
        private int usbBytes = 0;
        private int learnAckCount = 0;
        private int firmwareReplyCount = 0;
        private int rolling4 = 0;
        // Once an FE×4 capture header begins, FF raw timing-overflow
        // bytes are DATA, not standalone learn acknowledgements.
        private boolean irHeaderSeen = false;
        private final StringBuilder initialBytes = new StringBuilder();

        /**
         * Hardware diagnostics, read-only and bounded. Helps distinguish
         * no USB replies from malformed or incomplete capture framing.
         * Only the first 32 bytes are logged; no saved IR code is changed.
         */
        public String diagnostic() {
            return "USB receive packets: " + usbPackets
                    + "\nUSB bytes read: " + usbBytes
                    + "\nFirmware/version response (FA FA FA FA): "
                    + (firmwareReplyCount > 0 ? "SEEN" : "not observed")
                    + "\nLearn ACK (FF FF FF FF): "
                    + (learnAckCount > 0 ? "SEEN" : "not observed")
                    + "\nFE capture header: "
                    + (expected > 0 ? "FOUND" : "not observed")
                    + "\nCapture bytes collected: " + payload.size()
                    + " / " + (expected < 0 ? "unknown" : expected)
                    + "\nFirst USB bytes: "
                    + (initialBytes.length() == 0 ? "(none)" : initialBytes);
        }

        public boolean sawLearnAck() { return learnAckCount > 0; }

        public boolean complete() { return expected > 0 && payload.size() >= expected; }
        public int received() { return payload.size(); }
        public int expected() { return expected; }

        public void accept(byte[] chunk, int length) {
            if (chunk == null || length < 0 || length > chunk.length)
                throw new IllegalArgumentException("Invalid USB read length.");
            if (complete() || length == 0) return;
            usbPackets++;
            usbBytes += length;
            for (int i = 0; i < length; i++) {
                int value = chunk[i] & 0xFF;
                rolling4 = (rolling4 << 8) | value;
                if (rolling4 == 0xFEFEFEFE) irHeaderSeen = true;
                if (!irHeaderSeen && expected < 0
                        && rolling4 == 0xFFFFFFFF) learnAckCount++;
                if (!irHeaderSeen && expected < 0
                        && rolling4 == 0xFAFAFAFA) firmwareReplyCount++;
                if (usbBytes - length + i < 32) {
                    if (initialBytes.length() > 0) initialBytes.append(' ');
                    initialBytes.append(String.format(Locale.ROOT, "%02X", value));
                }
            }
            if (expected > 0) {
                payload.write(chunk, 0, Math.min(length, expected - payload.size()));
                return;
            }
            pending.write(chunk, 0, length);
            byte[] bytes = pending.toByteArray();
            if (bytes.length > MAX_RAW + 64) throw new IllegalArgumentException(
                    "IR response exceeded maximum allowed length.");
            for (int i = 0; i + 5 < bytes.length; i++) {
                if (bytes[i] != (byte) 0xFE || bytes[i + 1] != (byte) 0xFE
                        || bytes[i + 2] != (byte) 0xFE || bytes[i + 3] != (byte) 0xFE)
                    continue;
                // Standalone four-byte FE acknowledgement followed by another
                // FE header must not be mistaken for a 0xFEFE length.
                int count = ((bytes[i + 4] & 0xFF) << 8) | (bytes[i + 5] & 0xFF);
                if (count > MAX_RAW) continue;
                if (count < 8) throw new IllegalArgumentException(
                        "Invalid IR payload length " + count + ".");
                expected = count;
                int available = Math.min(count, bytes.length - i - 6);
                payload.write(bytes, i + 6, available);
                pending.reset();
                return;
            }
            // If a standalone ACK (FE x4) arrives, only retain a short tail
            // to recognize a header split across USB packet boundaries.
            if (bytes.length > 64) {
                pending.reset();
                pending.write(bytes, bytes.length - 5, 5);
            }
        }

        public int[] decodeTimings() {
            if (!complete()) throw new IllegalStateException("No complete learned IR signal.");
            byte[] data = payload.toByteArray();
            List<Integer> timings = new ArrayList<>();
            long carry = 0, total = 0;
            for (int i = 0; i < expected; i++) {
                int b = data[i] & 0xFF;
                if (b == 255) {
                    carry += 4080;
                    if (carry > 1_900_000) throw new IllegalArgumentException(
                            "IR timing overflow.");
                    continue;
                }
                long micros = b * 16L + carry;
                carry = 0;
                if (micros <= 0 || micros > 1_900_000)
                    throw new IllegalArgumentException("Invalid IR pulse/space duration.");
                // Receiver can append a very long terminal idle. Normalize
                // only that idle when converting to Pronto.
                if (i == expected - 1 && micros > 200_000) micros = 40000;
                total += micros;
                if (total >= 2_000_000) throw new IllegalArgumentException(
                        "IR capture exceeds two seconds.");
                timings.add((int) micros);
                if (timings.size() > 2048) throw new IllegalArgumentException(
                        "Too many IR timing values.");
            }
            if (carry != 0) throw new IllegalArgumentException("Truncated IR timing overflow.");
            if (timings.size() < 12) throw new IllegalArgumentException(
                    "IR signal too short; try pressing the source remote again.");
            // A last mark without a final space is common in raw receivers.
            // Add an idle gap to represent it in alternating Pronto pairs.
            if ((timings.size() & 1) != 0) timings.add(40000);
            int[] out = new int[timings.size()];
            for (int i = 0; i < out.length; i++) out[i] = timings.get(i);
            return out;
        }

        public String pronto() {
            int[] timings = decodeTimings();
            // Reuse existing tested raw Pronto encoder; no U1 USB/BLE calls.
            U1Protocol.Signal signal = new U1Protocol.Signal(
                    U1Protocol.SLOT, ESTIMATED_CARRIER_HZ, timings, 1);
            return U1Protocol.pronto(signal);
        }
    }
}
