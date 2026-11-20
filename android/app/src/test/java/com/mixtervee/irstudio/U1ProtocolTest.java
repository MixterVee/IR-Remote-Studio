package com.mixtervee.irstudio;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import static org.junit.Assert.*;

/** Port-verification tests with synthetic FFF1 packets, no Android or U1 device needed. */
public class U1ProtocolTest {
    @Test public void observedCommandPacketsAndChecksum() {
        assertArrayEquals(U1Protocol.hex("A55A000100"),
                U1Protocol.encode(U1Protocol.HELLO, new byte[0]));
        assertArrayEquals(U1Protocol.hex("081802000000000000"),
                U1Protocol.START_PAYLOAD);
        U1Protocol.Frame read = U1Protocol.decode(
                U1Protocol.encode(U1Protocol.READ_IR, new byte[]{8, (byte) 0xFF}));
        assertEquals(0x0C, read.command);
        assertArrayEquals(new byte[]{8, (byte) 0xFF}, read.payload);
        assertArrayEquals(U1Protocol.encode(U1Protocol.LEARN_FINISH, new byte[]{1}),
                U1Protocol.encode(5, U1Protocol.FINISH_PAYLOAD));
    }

    @Test public void rejectsWrongChecksumsHeadersAndShortIdentity() {
        byte[] frame = U1Protocol.encode(U1Protocol.BANNER, new byte[]{1,2,3,4,5,6});
        U1Protocol.verifyBanner(U1Protocol.decode(frame));
        frame[frame.length - 1] ^= 1;
        try {
            U1Protocol.decode(frame);
            fail("Frame checksum must reject corrupted packets");
        } catch (IllegalArgumentException expected) { }
        try {
            U1Protocol.decode(new byte[]{0x00, 0x5A, 0, 1, 0});
            fail("Bad frame prefix");
        } catch (IllegalArgumentException expected) { }
        try {
            U1Protocol.verifyBanner(U1Protocol.decode(
                    U1Protocol.encode(U1Protocol.BANNER, new byte[]{0,1,2})));
            fail("Device identity must be long enough");
        } catch (IllegalArgumentException expected) { }
    }

    private static byte[] syntheticBody(int length, int slot, int carrier, int pieces) {
        byte[] data = new byte[21 + length];
        data[0] = 1;
        data[1] = 0;
        data[2] = (byte) pieces;
        data[3] = (byte) slot;
        put32(data, 12, length);
        put32(data, 16, carrier);
        for (int offset = 20; offset < 20 + length; offset += 4) {
            put32(data, offset, (offset - 20) % 8 == 0 ? 450 : 1800);
        }
        int sum = 0;
        for (int i = 0; i < data.length - 1; i++) sum += data[i] & 0xFF;
        data[data.length - 1] = (byte) sum;
        return data;
    }

    private static void put32(byte[] data, int offset, int n) {
        data[offset] = (byte) (n >>> 24);
        data[offset + 1] = (byte) (n >>> 16);
        data[offset + 2] = (byte) (n >>> 8);
        data[offset + 3] = (byte) n;
    }

    private static byte[][] fragments(byte[] body, int count) {
        byte[][] result = new byte[count][];
        int at = 0;
        for (int i = 0; i < count; i++) {
            int n = (body.length - at + count - i - 1) / (count - i);
            byte[] payload = new byte[3 + n];
            payload[0] = 1;
            payload[1] = 0;
            payload[2] = (byte) (i + 1);
            System.arraycopy(body, at, payload, 3, n);
            result[i] = payload;
            at += n;
        }
        assertEquals(body.length, at);
        return result;
    }

    @Test public void assembles37FragmentsAndExportsPronto() {
        byte[] source = syntheticBody(416, 8, 38095, 37);
        byte[][] chunks = fragments(source, 37);
        U1Protocol.Assembler collector = new U1Protocol.Assembler(8);
        U1Protocol.Signal output = null;
        for (int i = 0; i < chunks.length; i++) {
            byte[] packet = U1Protocol.encode(U1Protocol.IR_DATA, chunks[i]);
            U1Protocol.Frame message = U1Protocol.decode(packet);
            output = collector.add(message.payload);
            if (i != chunks.length - 1) assertNull(output);
        }
        assertNotNull(output);
        assertEquals(8, output.slot);
        assertEquals(38095, output.carrierHz);
        assertEquals(104, output.timingsUs.length);
        assertEquals(37, output.fragments);
        String pronto = U1Protocol.pronto(output);
        assertEquals(104, IrplusCodec.decodePronto(pronto).microseconds.length);
    }

    @Test public void rejectsFragmentMisorderOrWrongAggregateChecksum() {
        byte[][] chunks = fragments(syntheticBody(416, 8, 38095, 37), 37);
        U1Protocol.Assembler missing = new U1Protocol.Assembler(8);
        missing.add(chunks[0]);
        try {
            missing.add(chunks[2]);
            fail("Missing IR fragment should be rejected");
        } catch (IllegalArgumentException expected) { }

        byte[] badBody = syntheticBody(416, 8, 38095, 37);
        badBody[badBody.length - 1] ^= 1;
        U1Protocol.Assembler bad = new U1Protocol.Assembler(8);
        try {
            for (byte[] piece : fragments(badBody, 37)) bad.add(piece);
            fail("Corrupted IR aggregate checksum must be rejected");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void rejectsWrongSlotBeforeAcceptingBackup() {
        byte[][] chunks = fragments(syntheticBody(416, 7, 38095, 37), 37);
        U1Protocol.Assembler collector = new U1Protocol.Assembler(8);
        try {
            for (byte[] piece : chunks) collector.add(piece);
            fail("Cannot back up a different slot as slot 8");
        } catch (IllegalArgumentException expected) { }
    }
}
