package com.mixtervee.irstudio;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Offline concurrency tests. No USB hardware or Android framework required. */
public class OcrustarUsbReadPumpTest {
    @Test public void dedicatedReaderQueuesIndependentCopiesOfUsbPackets() {
        AtomicInteger reads = new AtomicInteger();
        AtomicReference<String> readerName = new AtomicReference<>();
        AtomicInteger observedPackets = new AtomicInteger();
        OcrustarUsbReadPump receiver = new OcrustarUsbReadPump(
                (bytes, timeout) -> {
                    assertEquals(16384, bytes.length);
                    assertEquals(500, timeout);
                    readerName.set(Thread.currentThread().getName());
                    int number = reads.incrementAndGet();
                    if (number <= 2) {
                        bytes[0] = (byte) number;
                        bytes[1] = (byte) 0xFA;
                        return 2;
                    }
                    try {
                        Thread.sleep(15);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return -1;
                },
                (count, buffer) -> {
                    if (count > 0) observedPackets.incrementAndGet();
                });
        receiver.start();
        try {
            assertArrayEquals(new byte[]{1, (byte) 0xFA}, receiver.poll(1000));
            assertArrayEquals(new byte[]{2, (byte) 0xFA}, receiver.poll(1000));
            assertTrue(readerName.get().contains("Ocrustar-USB-IN"));
            assertEquals(2, observedPackets.get());
            receiver.checkFailure();
        } finally {
            receiver.stopAndJoin();
        }
    }

    @Test public void noDataReturnsNullButDoesNotTerminateReader() {
        AtomicInteger calls = new AtomicInteger();
        OcrustarUsbReadPump receiver = new OcrustarUsbReadPump(
                (buffer, timeout) -> {
                    calls.incrementAndGet();
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return -1;
                },
                (count, bytes) -> {});
        receiver.start();
        try {
            assertNull(receiver.poll(90));
            assertTrue(calls.get() > 0);
            assertEquals(0, receiver.discardPending());
            receiver.checkFailure();
        } finally {
            receiver.stopAndJoin();
        }
    }
}
