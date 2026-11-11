package com.mixtervee.irstudio;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Hardware-observed U1 FFF0 BLE protocol. Pure Java so parser has JVM tests.
 *
 * Only these known commands are exposed by the Android transport:
 * 0x01 identity, 0x0C stored IR read, 0x04 start learning, 0x05 finish.
 * DO NOT guess device/profile/firmware/restore commands.
 */
public final class U1Protocol {
    private U1Protocol() {}

    public static final String SERVICE = "0000fff0-0000-1000-8000-00805f9b34fb";
    public static final String NOTIFY = "0000fff1-0000-1000-8000-00805f9b34fb";
    public static final String WRITE = "0000fff2-0000-1000-8000-00805f9b34fb";
    public static final String CCCD = "00002902-0000-1000-8000-00805f9b34fb";
    public static final int HELLO = 0x01;
    public static final int BANNER = 0x02;
    public static final int LEARN_READY = 0x04;
    public static final int LEARN_START = 0x04;
    public static final int LEARN_FINISH = 0x05;
    public static final int LEARN_CAPTURED = 0x06;
    public static final int READ_IR = 0x0C;
    public static final int IR_DATA = 0x0D;
    public static final int SLOT = 8;
    // Exactly the recorded U1-v0.3.3 learning payloads.
    public static final byte[] START_PAYLOAD = hex("081802000000000000");
    public static final byte[] FINISH_PAYLOAD = {1};

    public static final class Frame {
        public final int command;
        public final byte[] payload;

        Frame(int command, byte[] payload) {
            this.command = command;
            this.payload = payload;
        }
    }

    public static final class Signal {
        public final int slot;
        public final int carrierHz;
        public final int[] timingsUs;
        public final int fragments;

        Signal(int slot, int carrierHz, int[] timingsUs, int fragments) {
            this.slot = slot;
            this.carrierHz = carrierHz;
            this.timingsUs = Arrays.copyOf(timingsUs, timingsUs.length);
            this.fragments = fragments;
        }
    }

    public static byte[] hex(String text) {
        if (text == null || (text.length() & 1) != 0)
            throw new IllegalArgumentException("Invalid hex length");
        byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(text.charAt(i * 2), 16);
            int lo = Character.digit(text.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("Bad hex");
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    public static byte[] encode(int command, byte[] payload) {
        if (command < 0 || command > 255 || payload == null || payload.length > 255)
            throw new IllegalArgumentException("Invalid U1 command");
        byte[] result = new byte[5 + payload.length];
        result[0] = (byte) 0xA5;
        result[1] = 0x5A;
        result[2] = (byte) payload.length;
        result[3] = (byte) command;
        System.arraycopy(payload, 0, result, 4, payload.length);
        int sum = 0;
        for (int i = 0; i < result.length - 1; i++) sum += result[i] & 0xFF;
        result[result.length - 1] = (byte) sum;
        return result;
    }

    public static Frame decode(byte[] packet) {
        if (packet == null || packet.length < 5
                || (packet[0] & 0xFF) != 0xA5 || (packet[1] & 0xFF) != 0x5A
                || packet.length != (packet[2] & 0xFF) + 5)
            throw new IllegalArgumentException("Invalid U1 frame header/length");
        int sum = 0;
        for (int i = 0; i < packet.length - 1; i++) sum += packet[i] & 0xFF;
        if ((sum & 0xFF) != (packet[packet.length - 1] & 0xFF))
            throw new IllegalArgumentException("Invalid U1 frame checksum");
        return new Frame(packet[3] & 0xFF,
                Arrays.copyOfRange(packet, 4, packet.length - 1));
    }

    public static void verifyBanner(Frame packet) {
        if (packet.command != BANNER || packet.payload.length < 6)
            throw new IllegalArgumentException("U1 identification reply missing or invalid");
    }

    private static int u8(byte[] b, int at) { return b[at] & 0xFF; }
    private static long u32(byte[] b, int at) {
        return ((long) u8(b, at) << 24) | ((long) u8(b, at + 1) << 16)
                | ((long) u8(b, at + 2) << 8) | u8(b, at + 3);
    }

    public static final class Assembler {
        private final int slot;
        private final ByteArrayOutputStream collected = new ByteArrayOutputStream();
        private int nextIndex = 1;
        private int expected = 0;

        public Assembler(int slot) {
            if (slot < 1 || slot > 8)
                throw new IllegalArgumentException("U1 slot must be 1 through 8");
            this.slot = slot;
        }
        public int receivedFragments() { return nextIndex - 1; }
        public int expectedFragments() { return expected; }

        public Signal add(byte[] payload) {
            if (payload == null || payload.length < 4
                    || u8(payload, 0) != 1 || u8(payload, 1) != 0)
                throw new IllegalArgumentException("Unexpected U1 IR fragment header");
            int index = u8(payload, 2);
            if (index != nextIndex)
                throw new IllegalArgumentException("Missing/out-of-order IR fragment "
                        + index + "; expected " + nextIndex);
            nextIndex++;
            collected.write(payload, 3, payload.length - 3);
            byte[] b = collected.toByteArray();
            if (expected == 0) {
                if (b.length < 4) throw new IllegalArgumentException("IR header incomplete");
                expected = u8(b, 2);
                if (expected < 1 || expected > 80)
                    throw new IllegalArgumentException("Bad U1 IR fragment count");
            }
            if (index < expected) {
                if (b.length > 65536) throw new IllegalArgumentException("IR too large");
                return null;
            }
            if (index != expected) throw new IllegalArgumentException("Unexpected IR fragment count");
            if (b.length < 25 || u8(b, 0) != 1 || u8(b, 1) != 0
                    || u8(b, 2) != expected || u8(b, 3) != slot)
                throw new IllegalArgumentException("Wrong U1 IR slot or header");
            long bytes = u32(b, 12);
            long carrier = u32(b, 16);
            if (bytes < 48 || bytes > 16384 || bytes % 8 != 0
                    || b.length != 21 + bytes || carrier < 10000 || carrier > 100000)
                throw new IllegalArgumentException("U1 IR timing length/carrier invalid");
            int checksum = 0;
            for (int i = 0; i < b.length - 1; i++) checksum += b[i] & 0xFF;
            if ((checksum & 0xFF) != u8(b, b.length - 1))
                throw new IllegalArgumentException("U1 IR aggregate checksum invalid");
            int[] timings = new int[(int) bytes / 4];
            for (int i = 0; i < timings.length; i++) {
                long duration = u32(b, 20 + 4 * i);
                if (duration < 1 || duration > 5000000)
                    throw new IllegalArgumentException("Invalid U1 IR timing");
                timings[i] = (int) duration;
            }
            return new Signal(slot, (int) carrier, timings, expected);
        }
    }

    /** Matches the Windows carrier/duration encoding for a raw signal. */
    public static String pronto(Signal signal) {
        if (signal.carrierHz < 10000 || signal.carrierHz > 100000
                || signal.timingsUs.length < 12
                || (signal.timingsUs.length & 1) != 0)
            throw new IllegalArgumentException("Unsupported IR signal");
        int frequencyWord = (int) Math.round(1000000.0 / (signal.carrierHz * 0.241246));
        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.US, "0000 %04X %04X 0000",
                frequencyWord, signal.timingsUs.length / 2));
        for (int i = 0; i < signal.timingsUs.length; i++) {
            int duration = signal.timingsUs[i];
            // Like Windows normalize_timings: tame only terminal idle
            // appended by a learning device, preserve real mark/space pulses.
            if (i == signal.timingsUs.length - 1 && duration > 200000)
                duration = 40000;
            int cycles = (int) Math.round(duration * signal.carrierHz / 1000000.0);
            if (cycles < 1 || cycles > 65535)
                throw new IllegalArgumentException("Signal timing cannot be exported as Pronto");
            out.append(String.format(Locale.US, " %04X", cycles));
        }
        String result = out.toString();
        IrplusCodec.decodePronto(result);
        return result;
    }
}
