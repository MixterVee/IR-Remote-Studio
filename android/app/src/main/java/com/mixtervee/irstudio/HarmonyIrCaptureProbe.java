package com.mixtervee.irstudio;

import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbRequest;
import android.os.SystemClock;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

/**
 * Experimental ONE-SHOT Harmony 300 MH IR receive diagnostic (arch 0x10).
 * Uses the documented transient /ir/ir_cap read session, not configuration
 * or firmware writes. Stops the receive session in a finally block.
 *
 * Does not save, transmit, or export a learned command in this build.
 */
final class HarmonyIrCaptureProbe {
    private HarmonyIrCaptureProbe() {}

    private static final int REPORT_BYTES = 64;
    private static final int MAX_PACKETS = 16;
    private static final int MAX_WORDS = 480;
    private static final long START_WAIT_MS = 10000;
    private static final long MORE_WAIT_MS = 1200;
    private static final long EXCHANGE_WAIT_MS = 3500;

    static byte[] openCommand() {
        byte[] command = new byte[REPORT_BYTES];
        byte[] prefix = { (byte) 0xFF, 0x01, 0x00, 0x02, (byte) 0x80,
                '/', 'i', 'r', '/', 'i', 'r', '_', 'c', 'a', 'p',
                0x00, (byte) 0x80, 'R', 0x00 };
        System.arraycopy(prefix, 0, command, 0, prefix.length);
        return command;
    }

    static byte[] captureAck(byte parameter) {
        return packet(new byte[] {
                (byte) 0xFF, 0x04, 0x01, 0x02, 0x01, parameter, 0x01, 0x00
        });
    }

    static byte[] stopCommand(byte parameter) {
        return packet(new byte[] {
                (byte) 0xFF, 0x06, 0x02, 0x02, 0x01, parameter, 0x01, 0x06
        });
    }

    static byte[] closeCommand(byte parameter) {
        return packet(new byte[] {
                (byte) 0xFF, 0x07, 0x03, 0x01, 0x01, parameter
        });
    }

    private static byte[] packet(byte[] prefix) {
        if (prefix == null || prefix.length == 0 || prefix.length > REPORT_BYTES)
            throw new IllegalArgumentException("Invalid HID report prefix");
        byte[] report = new byte[REPORT_BYTES];
        System.arraycopy(prefix, 0, report, 0, prefix.length);
        return report;
    }

    private static ByteBuffer direct(byte[] payload) {
        ByteBuffer result = ByteBuffer.allocateDirect(REPORT_BYTES);
        result.put(packet(payload));
        result.flip();
        return result;
    }

    private static String prefix(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; bytes != null && i < Math.min(12, bytes.length); i++) {
            if (i > 0) out.append(' ');
            out.append(String.format(Locale.ROOT, "%02X", bytes[i] & 0xFF));
        }
        return out.toString();
    }

    /** One OUT and one IN; never queue additional commands after a failure. */
    private static byte[] exchange(UsbDeviceConnection conn, UsbEndpoint in,
                                    UsbEndpoint out, byte[] payload, long timeout,
                                    StringBuilder log, String label) {
        UsbRequest reader = new UsbRequest();
        UsbRequest writer = new UsbRequest();
        try {
            if (!reader.initialize(conn, in) || !writer.initialize(conn, out)) {
                log.append(label).append(": USB initialization failed\n");
                return null;
            }
            ByteBuffer incoming = ByteBuffer.allocateDirect(REPORT_BYTES);
            ByteBuffer outgoing = direct(payload);
            if (!reader.queue(incoming) || !writer.queue(outgoing)) {
                log.append(label).append(": queue failed\n");
                return null;
            }
            boolean doneIn = false;
            boolean doneOut = false;
            long deadline = SystemClock.elapsedRealtime() + timeout;
            while (!doneIn || !doneOut) {
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0) break;
                UsbRequest complete;
                try {
                    complete = conn.requestWait(remaining);
                } catch (TimeoutException ignored) {
                    break;
                }
                if (complete == reader) doneIn = true;
                else if (complete == writer) doneOut = true;
                else if (complete == null) break;
            }
            int inBytes = incoming.position();
            int outBytes = outgoing.position();
            log.append(label).append(": OUT=").append(doneOut).append('/')
                    .append(outBytes).append(" IN=").append(doneIn).append('/')
                    .append(inBytes).append('\n');
            if (!doneOut || outBytes != REPORT_BYTES || !doneIn
                    || inBytes < 6 || inBytes > REPORT_BYTES) {
                return null;
            }
            incoming.flip();
            byte[] reply = new byte[inBytes];
            incoming.get(reply);
            log.append(label).append(" reply prefix: ").append(prefix(reply))
                    .append('\n');
            return reply;
        } catch (RuntimeException ex) {
            log.append(label).append(" USB error: ")
                    .append(ex.getClass().getSimpleName()).append('\n');
            return null;
        } finally {
            reader.cancel();
            writer.cancel();
            reader.close();
            writer.close();
        }
    }

    /** Single bounded interrupt-IN read (never leaves a request queued). */
    private static byte[] nextReport(UsbDeviceConnection conn, UsbEndpoint in,
                                     long timeoutMs) {
        UsbRequest request = new UsbRequest();
        try {
            if (!request.initialize(conn, in)) return null;
            ByteBuffer buffer = ByteBuffer.allocateDirect(REPORT_BYTES);
            if (!request.queue(buffer)) return null;
            UsbRequest result;
            try {
                result = conn.requestWait(timeoutMs);
            } catch (TimeoutException ignored) {
                return null;
            }
            int count = buffer.position();
            if (result != request || count < 1 || count > REPORT_BYTES) return null;
            buffer.flip();
            byte[] report = new byte[count];
            buffer.get(report);
            return report;
        } finally {
            request.cancel();
            request.close();
        }
    }

    /** MH packets use the last byte for packed raw-word length. */
    static boolean appendSignalWords(byte[] report, List<Integer> words) {
        if (report == null || report.length != REPORT_BYTES || words == null)
            return false;
        int length = report[REPORT_BYTES - 1] & 0xFF;
        if (length < 2 || length > 62 || (length & 1) != 0) return false;
        int newWords = (length - 2) / 2;
        if (words.size() + newWords > MAX_WORDS) return false;
        for (int offset = 2; offset < length; offset += 2) {
            words.add(((report[offset] & 0xFF) << 8)
                    | (report[offset + 1] & 0xFF));
        }
        return true;
    }

    static String summary(List<Integer> words) {
        if (words == null || words.size() < 3)
            return "IR waveform: insufficient timing words for carrier estimate\n";
        long firstOnUs = words.get(1);
        long carrierCycles = words.get(2);
        long hz = firstOnUs > 0 ? carrierCycles * 1000000L / firstOnUs : 0;
        StringBuilder out = new StringBuilder();
        out.append("Captured timing words: ").append(words.size()).append('\n');
        out.append("First burst: ").append(firstOnUs).append(" microseconds\n");
        out.append("Carrier estimate: ");
        if (hz >= 18000 && hz <= 80000) {
            out.append(hz).append(" Hz\n");
        } else {
            out.append("unverified (").append(hz).append(" Hz)\n");
        }
        out.append("First timing words (raw, not yet export-ready):");
        for (int i = 0; i < Math.min(words.size(), 20); i++)
            out.append(i == 0 ? " " : ", ").append(words.get(i));
        out.append('\n');
        return out.toString();
    }

    static String capture(UsbDeviceConnection conn, UsbEndpoint in,
                          UsbEndpoint out) {
        StringBuilder log = new StringBuilder();
        log.append("\nHarmony MH IR receive test (experimental)\n");
        log.append("Arch 0x10; one button press; maximum 10-second start wait\n");
        byte parameter = 0;
        boolean opened = false;
        try {
            byte[] response = exchange(conn, in, out, openCommand(),
                    EXCHANGE_WAIT_MS, log, "Open /ir/ir_cap");
            if (response == null || (response[0] & 0xFF) != 0xFF
                    || (response[1] & 0xFF) != 0x01) {
                log.append("IR receive open not confirmed; stopped\n");
                return log.toString();
            }
            parameter = response[5];
            opened = true;
            byte[] ack = exchange(conn, in, out, captureAck(parameter),
                    EXCHANGE_WAIT_MS, log, "IR receive ACK");
            if (ack == null || (ack[0] & 0xFF) != 0xFF
                    || (ack[1] & 0xFF) != 0x04) {
                log.append("IR receive ACK not confirmed\n");
                return log.toString();
            }

            List<Integer> words = new ArrayList<>();
            int packets = 0;
            int sequence = 0x90; // Concordance uses 0x90 for MH architecture 16.
            boolean done = false;
            for (int i = 0; i < MAX_PACKETS; i++) {
                byte[] report = nextReport(conn, in,
                        packets == 0 ? START_WAIT_MS : MORE_WAIT_MS);
                if (report == null) {
                    log.append(packets == 0
                            ? "No IR data within 10 seconds\n"
                            : "IR data stream ended after timeout\n");
                    break;
                }
                int type = report[0] & 0xF0;
                if (type == 0xF0) {
                    done = true;
                    break;
                }
                if (type != 0x90 || report.length != REPORT_BYTES) {
                    log.append("Unexpected IR report type or size; stopped\n");
                    break;
                }
                if ((report[1] & 0xFF) != sequence) {
                    log.append("Unexpected IR sequence number; stopped\n");
                    break;
                }
                if (!appendSignalWords(report, words)) {
                    log.append("Malformed IR timing packet; stopped\n");
                    break;
                }
                packets++;
                sequence = (sequence + 0x10) & 0xFF;
            }
            log.append("IR data packets received: ").append(packets).append('\n');
            log.append("IR end marker received: ").append(done).append('\n');
            log.append(summary(words));
            log.append("Diagnostic only: no command saved or exported.\n");
        } catch (RuntimeException error) {
            log.append("IR capture error: ")
                    .append(error.getClass().getSimpleName()).append('\n');
        } finally {
            if (opened) {
                // Concordance's transient stop+close are always attempted
                // even when no IR button was pressed or the data was malformed.
                log.append("Closing IR capture session\n");
                byte[] stop = exchange(conn, in, out, stopCommand(parameter),
                        EXCHANGE_WAIT_MS, log, "IR receive STOP");
                log.append("IR stop reply: ")
                        .append(stop == null ? "not confirmed" : "received")
                        .append('\n');
                byte[] close = exchange(conn, in, out, closeCommand(parameter),
                        EXCHANGE_WAIT_MS, log, "IR sequence CLOSE");
                log.append("IR sequence close reply: ")
                        .append(close == null ? "not confirmed" : "received")
                        .append('\n');
            }
        }
        return log.toString();
    }
}
