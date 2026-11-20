package com.mixtervee.irstudio;

import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * One dedicated, continuously running USB IN reader for the whole Ocrustar
 * session. The worker owns all reads; authentication and learn code consume
 * immutable packet copies from the same bounded queue.
 *
 * The vendor's CommunicationRunnable uses a 16 KiB buffer and a 500 ms USB
 * read timeout. This worker matches those properties without introducing
 * firmware/configuration writes or a competing second USB reader.
 *
 * This class deliberately depends on Java only so its scheduling and packet
 * delivery can be unit tested without a physical Android device.
 */
final class OcrustarUsbReadPump {
    static final int BUFFER_SIZE = 16384;
    static final int READ_TIMEOUT_MS = 500;
    static final int MAX_QUEUED_PACKETS = 256;

    interface Reader {
        int read(byte[] buffer, int timeoutMs);
    }

    interface Observer {
        void onRead(int result, byte[] buffer);
    }

    private final Reader source;
    private final Observer observer;
    private final BlockingQueue<byte[]> packets =
            new ArrayBlockingQueue<>(MAX_QUEUED_PACKETS);

    private volatile boolean running;
    private volatile RuntimeException failure;
    private Thread worker;

    OcrustarUsbReadPump(Reader source, Observer observer) {
        if (source == null || observer == null)
            throw new IllegalArgumentException("USB receive callbacks are required.");
        this.source = source;
        this.observer = observer;
    }

    synchronized void start() {
        if (worker != null) throw new IllegalStateException("USB receiver already started.");
        running = true;
        worker = new Thread(this::runLoop, "Ocrustar-USB-IN");
        worker.setDaemon(true);
        worker.start();
    }

    private void runLoop() {
        byte[] buffer = new byte[BUFFER_SIZE];
        try {
            while (running) {
                int n = source.read(buffer, READ_TIMEOUT_MS);
                if (!running) break;
                if (n > buffer.length)
                    throw new IllegalStateException("USB read exceeded receive buffer.");
                observer.onRead(n, buffer);
                if (n > 0 && !packets.offer(Arrays.copyOf(buffer, n))) {
                    throw new IllegalStateException(
                            "USB receive queue exceeded 256 packets; learning aborted.");
                }
            }
        } catch (RuntimeException exception) {
            if (running) failure = exception;
        } finally {
            running = false;
        }
    }

    /**
     * Called only by the session's controlling thread, never by another USB
     * reader. Returns null on a queue wait timeout, not on a USB transport error.
     */
    byte[] poll(int waitMs) {
        checkFailure();
        if (waitMs < 0) throw new IllegalArgumentException("Negative USB queue timeout.");
        try {
            byte[] data = packets.poll(waitMs, TimeUnit.MILLISECONDS);
            checkFailure();
            return data;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for Ocrustar USB data.",
                    interrupted);
        }
    }

    int discardPending() {
        int count = packets.size();
        packets.clear();
        return count;
    }

    void checkFailure() {
        if (failure != null)
            throw new IllegalStateException("Ocrustar USB reader stopped: "
                    + failure.getMessage(), failure);
    }

    void stopAndJoin() {
        running = false;
        Thread thread = worker;
        if (thread == null) return;
        try {
            // The USB IN transfer itself has a 500 ms timeout.
            thread.join(1200);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
