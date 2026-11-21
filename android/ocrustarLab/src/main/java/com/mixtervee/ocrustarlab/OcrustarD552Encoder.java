package com.mixtervee.ocrustarlab;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Experimental D552 (FC FC FC FC 70 01) transmitter, isolated to IR Lab.
 *
 * Derivation: reverse-engineered Ocrustar protocol, including the corrected
 * dual-regime duration encoder, as published in:
 * https://github.com/deadboy18/Ocrustar-USB-IR (MIT license).
 * This class does not use the vendor SDK or alter device settings.
 */
public final class OcrustarD552Encoder {
    private OcrustarD552Encoder() {}

    public static final int DEFAULT_CARRIER_HZ = 38000;
    public static final int MAX_TIMINGS = 2000;
    private static final int MAX_SIGNAL_MICROS = 1_000_000;
    private static final int MAX_FRAME_BYTES = 63;

    /**
     * Report full strict waveform validation. No zero duration is allowed.
     * The raw decoder still preserves zero values for diagnostics.
     */
    public static int[] decodeStrict(byte[] raw) {
        if (raw == null || raw.length < 12 || raw.length > 8192)
            throw new IllegalArgumentException("Raw signal length out of bounds");
        int[] values = new int[raw.length];
        int n = 0;
        int carry = 0;
        long total = 0;
        for (byte item : raw) {
            int b = item & 0xFF;
            if (b == 0xFF) {
                carry += 4080;
                if (carry > 200000)
                    throw new IllegalArgumentException("Very long FF overflow duration");
                continue;
            }
            int micros = b * 16 + carry;
            carry = 0;
            if (micros < 16 || micros > 200000) {
                throw new IllegalArgumentException(
                        "Invalid duration " + micros + " us at index " + n
                        + " (zero or implausible timing).");
            }
            values[n++] = micros;
            total += micros;
            if (total >= MAX_SIGNAL_MICROS)
                throw new IllegalArgumentException("Captured signal exceeds 1 second");
        }
        if (carry != 0) throw new IllegalArgumentException("Incomplete FF overflow suffix");
        if (n < 12 || (n & 1) != 0 || n > MAX_TIMINGS)
            throw new IllegalArgumentException("Unpaired or too many timing values (" + n + ")");
        return Arrays.copyOf(values, n);
    }

    private static final class Pair {
        final int mark, space;
        Pair(int mark, int space) { this.mark = mark; this.space = space; }
        @Override public int hashCode() { return 31 * mark + space; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Pair)) return false;
            Pair p = (Pair) other; return mark == p.mark && space == p.space;
        }
        int sum() { return mark + space; }
    }

    private static int mangle(int value) {
        int b = value & 0xFF;
        int reversed = Integer.reverse(b) >>> 24;
        return (~reversed) & 0xFF;
    }

    /**
     * D552 firmware uses two regimes: up to 2032 us -> rounded 16-us ticks;
     * above 2032 us -> LEB128 of RAW microseconds. Do not prescale long gaps.
     */
    private static void duration(ByteArrayOutputStream out, int micros) {
        if (micros < 0) throw new IllegalArgumentException("Negative IR timing");
        if (micros <= 2032) {
            int ticks = micros <= 1 ? micros : (micros + 8) / 16;
            out.write(ticks & 0xFF);
            return;
        }
        int value = micros;
        do {
            int b = value & 0x7F;
            value >>>= 7;
            if (value != 0) b |= 0x80;
            if (b == 0xFF) b = 0xFE;
            out.write(b);
        } while (value != 0);
    }

    /** Output D552's pulse-pair dictionary + inline duration stream. */
    static byte[] compress(int[] timings) {
        if (timings == null || timings.length < 12 || (timings.length & 1) != 0)
            throw new IllegalArgumentException("Timings must be even and nonempty");
        Map<Pair, Integer> counts = new LinkedHashMap<>();
        List<Pair> pairs = new ArrayList<>();
        long total = 0;
        for (int i = 0; i < timings.length; i += 2) {
            if (timings[i] < 16 || timings[i + 1] < 16)
                throw new IllegalArgumentException("Cannot replay zero or too-short IR timings");
            total += (long) timings[i] + timings[i + 1];
            if (total >= MAX_SIGNAL_MICROS)
                throw new IllegalArgumentException("Signal exceeds 1 second");
            Pair pair = new Pair(timings[i], timings[i + 1]);
            pairs.add(pair);
            counts.put(pair, counts.getOrDefault(pair, 0) + 1);
        }
        List<Map.Entry<Pair, Integer>> popular = new ArrayList<>(counts.entrySet());
        // Keep encounter order for ties, matching Counter.most_common(2).
        popular.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        Pair p0 = popular.get(0).getKey();
        Pair p1 = popular.size() > 1 ? popular.get(1).getKey() : p0;
        if (p0.sum() > p1.sum()) {
            Pair swap = p0; p0 = p1; p1 = swap;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        duration(out, p1.mark); duration(out, p1.space);
        duration(out, p0.mark); duration(out, p0.space);
        out.write(0xFF); out.write(0xFF); out.write(0xFF);
        for (Pair p : pairs) {
            if (p.equals(p0)) out.write(0);
            else if (p.equals(p1)) out.write(1);
            else { duration(out, p.mark); duration(out, p.space); }
        }
        return out.toByteArray();
    }

    /** Complete host-to-USB frames, 62 data bytes + checksum per full frame. */
    public static List<byte[]> encode(int[] timings, int carrierHz) {
        if (carrierHz < 30000 || carrierHz > 60000)
            throw new IllegalArgumentException("Carrier outside 30-60 kHz lab range");
        byte[] compressed = compress(timings);
        if (compressed.length > 65535)
            throw new IllegalArgumentException("Compressed waveform too large");
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        for (int i = 0; i < 4; i++) message.write(0xFF);
        int freq = carrierHz + 0x7FFFF;
        message.write(mangle(freq >>> 8));
        message.write(mangle(freq >>> 16));
        message.write(mangle(freq));
        message.write(mangle(compressed.length >>> 8));
        message.write(mangle(compressed.length));
        message.write(compressed, 0, compressed.length);

        byte[] source = message.toByteArray();
        List<byte[]> frames = new ArrayList<>();
        for (int offset = 0; offset < source.length; offset += 62) {
            int size = Math.min(62, source.length - offset);
            byte[] frame = Arrays.copyOfRange(source, offset, offset + size);
            if (size == 62) {
                int sum = 0;
                for (byte b : frame) sum += b & 0xFF;
                int checksumInput = (sum & 0xF0) | ((sum >>> 8) & 0x0F);
                frame = Arrays.copyOf(frame, MAX_FRAME_BYTES);
                frame[62] = (byte) mangle(checksumInput);
            }
            frames.add(frame);
        }
        return frames;
    }
}
