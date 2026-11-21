package com.mixtervee.ocrustarlab;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

public class OcrustarCaptureAssemblerTest {
    @Test
    public void reassemblesObserved552ByteFrameAcrossEight63ByteReadsAndFinal54() {
        OcrustarCaptureAssembler assembly = new OcrustarCaptureAssembler();
        assembly.accept(new byte[]{(byte)0xFA,(byte)0xFA,(byte)0xFA,(byte)0xFA,0x22,0x01});
        assertFalse(assembly.hasHeader());

        byte[] payload = new byte[552];
        for (int i=0; i<payload.length; i++) payload[i]=(byte) (i % 251);
        byte[] frame = new byte[6 + payload.length];
        Arrays.fill(frame,0,4,(byte)0xFE);
        frame[4]=0x02;
        frame[5]=0x28;
        System.arraycopy(payload,0,frame,6,payload.length);

        // Matches the actual capture: eight full 63-byte USB reads, one 54-byte read.
        for (int at=0;at<frame.length;at+=63) {
            int n=Math.min(63,frame.length-at);
            assembly.accept(Arrays.copyOfRange(frame,at,at+n));
            if (at + n < frame.length) assertFalse(assembly.isComplete());
        }
        assertTrue(assembly.isComplete());
        assertEquals(552,assembly.getExpectedLength());
        assertEquals(552,assembly.getReceivedLength());
        assertArrayEquals(payload,assembly.getPayload());
        assertEquals(6,assembly.getIgnoredBeforeHeader());
        assertEquals(0,assembly.getTrailingBytes());
        assertEquals(9,assembly.getDataChunks());
    }

    @Test
    public void acceptsHeaderSplitAcrossUsbPackets() {
        OcrustarCaptureAssembler a=new OcrustarCaptureAssembler();
        a.accept(new byte[]{(byte)0xFA,0x22,(byte)0xFE,(byte)0xFE});
        a.accept(new byte[]{(byte)0xFE,(byte)0xFE,0x00,0x0c,1,2,3,4,5});
        assertTrue(a.hasHeader());
        assertEquals(12,a.getExpectedLength());
        a.accept(new byte[]{6,7,8,9,10,11,12});
        assertTrue(a.isComplete());
        assertEquals(12,a.getPayload()[11]);
        assertEquals(2,a.getIgnoredBeforeHeader());
    }

    @Test
    public void doesNotOverrunDeclaredPayload() {
        OcrustarCaptureAssembler a=new OcrustarCaptureAssembler();
        a.accept(new byte[]{(byte)0xFE,(byte)0xFE,(byte)0xFE,(byte)0xFE,0,12,
                1,2,3,4,5,6,7,8,9,10,11,12,13,14});
        assertTrue(a.isComplete());
        assertEquals(12,a.getReceivedLength());
        assertEquals(2,a.getTrailingBytes());
    }
}
