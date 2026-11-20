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
     * Bounded stream assembly: accepts multiple USB bulk chunks. Does not
     * assume every USB transfer is an entire learn response.
     */
    public static final class Capture {
        private static final int MAX_RAW = 8192;
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        private final ByteArrayOutputStream payload = new ByteArrayOutputStream();
        private int expected = -1;

        public boolean complete() { return expected > 0 && payload.size() >= expected; }
        public int received() { return payload.size(); }
        public int expected() { return expected; }

        public void accept(byte[] chunk, int length) {
            if (chunk == null || length < 0 || length > chunk.length)
                throw new IllegalArgumentException("Invalid USB read length.");
            if (complete() || length == 0) return;
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
