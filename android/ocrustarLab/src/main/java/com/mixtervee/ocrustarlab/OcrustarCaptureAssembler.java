package com.mixtervee.ocrustarlab;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * Bounded reassembly of a learned IR payload across arbitrary USB bulk reads.
 *
 * On 045C:0132, the learned frame begins with FE FE FE FE, followed by a
 * 16-bit big-endian payload byte count. An independent 6-byte FA status
 * response may arrive first. These are host-side copies only.
 */
public final class OcrustarCaptureAssembler {
    public static final int MAX_PAYLOAD_BYTES = 8192;
    private static final int MIN_PAYLOAD_BYTES = 12;
    private final ByteArrayOutputStream search = new ByteArrayOutputStream(32);
    private final ByteArrayOutputStream payload = new ByteArrayOutputStream(1024);
    private int expectedLength = -1;
    private int ignoredBeforeHeader;
    private int trailingBytes;
    private int dataChunks;

    public void accept(byte[] packet) {
        if (packet == null || packet.length == 0) return;
        if (isComplete()) {
            trailingBytes += packet.length;
            return;
        }
        if (expectedLength >= 0) {
            appendPayload(packet, 0, packet.length);
            return;
        }

        // Preserve up to five trailing bytes to handle headers split
        // across USB reads. Scan past status/ack packets such as FA×4.
        search.write(packet, 0, packet.length);
        byte[] pending = search.toByteArray();
        for (int i = 0; i <= pending.length - 6; i++) {
            if (!isFePrefix(pending, i)) continue;
            int declared = ((pending[i + 4] & 0xFF) << 8) |
                    (pending[i + 5] & 0xFF);
            if (declared < MIN_PAYLOAD_BYTES || declared > MAX_PAYLOAD_BYTES) {
                continue;
            }
            expectedLength = declared;
            ignoredBeforeHeader += i;
            search.reset();
            appendPayload(pending, i + 6, pending.length - (i + 6));
            return;
        }

        int toKeep = Math.min(5, pending.length);
        ignoredBeforeHeader += pending.length - toKeep;
        search.reset();
        search.write(pending, pending.length - toKeep, toKeep);
    }

    private static boolean isFePrefix(byte[] data, int offset) {
        return (data[offset] & 0xFF) == 0xFE &&
                (data[offset + 1] & 0xFF) == 0xFE &&
                (data[offset + 2] & 0xFF) == 0xFE &&
                (data[offset + 3] & 0xFF) == 0xFE;
    }

    private void appendPayload(byte[] data, int offset, int count) {
        if (count <= 0) return;
        int remaining = expectedLength - payload.size();
        int accepted = Math.min(remaining, count);
        if (accepted > 0) {
            payload.write(data, offset, accepted);
            dataChunks++;
        }
        trailingBytes += count - accepted;
    }

    public boolean hasHeader() { return expectedLength >= 0; }
    public boolean isComplete() {
        return expectedLength >= 0 && payload.size() == expectedLength;
    }
    public int getExpectedLength() { return expectedLength; }
    public int getReceivedLength() { return payload.size(); }
    public int getIgnoredBeforeHeader() { return ignoredBeforeHeader; }
    public int getTrailingBytes() { return trailingBytes; }
    public int getDataChunks() { return dataChunks; }
    public byte[] getPayload() { return payload.toByteArray(); }

    /** 16-us timing conversion is tentative until verified against replay. */
    public static String timingSummary(byte[] bytes) {
        if (bytes.length == 0) return "Empty payload.";
        int durationCount = 0;
        int overflowBytes = 0;
        long overflowMicros = 0;
        long totalMicros = 0;
        int maxUs = 0;
        int pendingOverflows = 0;
        int plausible = 0;
        for (byte item : bytes) {
            int value = item & 0xFF;
            if (value == 0xFF) {
                pendingOverflows++;
                overflowBytes++;
                continue;
            }
            long us = ((long) pendingOverflows * 4080L) + (value * 16L);
            durationCount++;
            totalMicros += us;
            overflowMicros += ((long) pendingOverflows * 4080L);
            maxUs = (int) Math.max(maxUs, Math.min(Integer.MAX_VALUE, us));
            if (us >= 16 && us <= 200000) plausible++;
            pendingOverflows = 0;
        }
        return "Tentative 16us-unit timing parse: " + durationCount
                + " durations (" + plausible + " in 16..200000us), "
                + overflowBytes + " FF overflow bytes, "
                + pendingOverflows + " trailing unpaired FF bytes, "
                + "max duration " + maxUs + "us, "
                + "sum " + totalMicros + "us, "
                + "overflow component " + overflowMicros + "us. "
                + "Not a verified IR waveform; full raw data preserved.";
    }

    public static byte[] firstBytes(byte[] data, int count) {
        return Arrays.copyOf(data, Math.min(data.length, count));
    }

    public static byte[] lastBytes(byte[] data, int count) {
        return Arrays.copyOfRange(data, Math.max(0, data.length - count), data.length);
    }
}
