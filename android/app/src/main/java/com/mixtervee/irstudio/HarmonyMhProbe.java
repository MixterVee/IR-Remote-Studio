package com.mixtervee.irstudio;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;
import android.os.SystemClock;

import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

/**
 * Experimental Harmony 300 (C124) MH session handshake.
 *
 * Sends only the documented MH session-start packets. No IR capture,
 * firmware/flash operations, configuration, resets, or other requests.
 * This test exchanges the first MH response, then conditionally reads the
 * documented identity metadata. Unsuccessful sessions never trigger retries.
 */
public final class HarmonyMhProbe {
    private HarmonyMhProbe() {}

    private static final byte[] MH_START = {
            (byte) 0xFF, 0x00, 0x00, 0x01, 0x01
    };
    private static final byte[] MH_INITIAL_REQUEST = {
            (byte) 0xFF, (byte) 0xFF, 0x01, 0x01, 0x01, 0x66
    };
    private static final byte[] MH_CONTINUE = {
            (byte) 0xFF, 0x00, 0x02
    };

    /** Full, non-interpretive trace; protocol acceptance is NOT inferred. */
    static String responseSummary(byte[] bytes, int length) {
        if (bytes == null || length <= 0 || length > bytes.length) {
            return "No valid MH reply bytes received.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("MH reply received: ").append(length).append(" bytes\n");
        sb.append("MH reply complete hex (").append(length).append(" bytes):\n");
        for (int i = 0; i < length; i++) {
            if (i > 0) sb.append((i % 16 == 0) ? '\n' : ' ');
            sb.append(String.format(Locale.ROOT, "%02X", bytes[i] & 0xFF));
        }
        sb.append('\n');
        int first = bytes[0] & 0xFF;
        int second = length > 1 ? bytes[1] & 0xFF : -1;
        int third = length > 2 ? bytes[2] & 0xFF : -1;
        boolean observedHeader = length >= 6
                && first == 0xFF && second == 0xFF
                && third == 0x81 && (bytes[4] & 0xFF) == 0x81;
        sb.append("Report size: ").append(length == 64 ? "64/64" : "unexpected")
                .append("\n");
        sb.append("Observed FF FF 81 ?? 81 prefix: ")
                .append(observedHeader ? "YES" : "NO").append("\n");
        int lastNonZero = -1;
        for (int i = 0; i < length; i++) if (bytes[i] != 0) lastNonZero = i;
        sb.append("Last non-zero byte offset: ").append(lastNonZero)
                .append("\n");
        sb.append("Interpretation: USB response confirmed; MH message meaning ")
                .append("and IR-learning readiness NOT established.\n");
        return sb.toString();
    }

    private static ByteBuffer report(byte[] prefix) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(64);
        buffer.put(prefix);
        while (buffer.position() < 64) buffer.put((byte) 0);
        buffer.flip();
        return buffer;
    }

    private static boolean writeAndWait(UsbDeviceConnection conn,
                                        UsbEndpoint endpoint, byte[] prefix,
                                        StringBuilder log, String label) {
        UsbRequest out = new UsbRequest();
        try {
            if (!out.initialize(conn, endpoint)) {
                log.append(label).append(": unable to initialize USB OUT").append('\n');
                return false;
            }
            ByteBuffer payload = report(prefix);
            if (!out.queue(payload)) {
                log.append(label).append(": unable to queue USB OUT").append('\n');
                return false;
            }
            UsbRequest completed = conn.requestWait(2000);
            int sent = payload.position();
            if (completed != out || sent != 64) {
                log.append(label).append(": incomplete USB OUT (")
                        .append(sent).append("/64 bytes)").append('\n');
                return false;
            }
            return true;
        } catch (TimeoutException error) {
            log.append(label).append(": USB OUT timed out").append('\n');
            return false;
        } finally {
            out.cancel();
            out.close();
        }
    }

    /**
     * Match only the repeatable, user-observed stage-one response prefix.
     * This is a conservative gate for a session-only experiment, NOT a
     * claim that 0x81 denotes success in the protocol specification.
     */
    static boolean matchesObservedStageOne(byte[] bytes) {
        return bytes != null && bytes.length == 64
                && (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xFF
                && (bytes[2] & 0xFF) == 0x81
                && (bytes[3] & 0xFF) == 0x00
                && (bytes[4] & 0xFF) == 0x81;
    }

    /** Explain initial exchange progress without interpreting the protocol reply. */
    static String initialExchangeStatus(boolean outCompleted, int outBytes,
                                        boolean inCompleted, int inBytes,
                                        boolean waitTimedOut) {
        return "MH initial exchange: OUT completed=" + outCompleted
                + ", OUT bytes=" + outBytes
                + ", IN completed=" + inCompleted
                + ", IN bytes=" + inBytes
                + ", wait timeout=" + waitTimedOut;
    }

    /**
     * Stage one uses exactly the existing five sync packets and initial request.
     * No retries or recovery commands are sent on an incomplete exchange.
     */
    private static boolean exchangeFinal(UsbDeviceConnection conn, UsbEndpoint in,
                                         UsbEndpoint out, StringBuilder log) {
        UsbRequest reader = new UsbRequest();
        UsbRequest writer = new UsbRequest();
        try {
            if (!reader.initialize(conn, in) || !writer.initialize(conn, out)) {
                log.append("Could not initialize HID IN/OUT transfers\n");
                return false;
            }
            ByteBuffer response = ByteBuffer.allocateDirect(64);
            ByteBuffer command = report(MH_INITIAL_REQUEST);
            // Queue IN before OUT, preserving the established successful order.
            if (!reader.queue(response)) {
                log.append("Failed to queue interrupt IN request\n");
                return false;
            }
            if (!writer.queue(command)) {
                log.append("Failed to queue final MH initialization request\n");
                return false;
            }

            boolean writeCompleted = false;
            boolean readCompleted = false;
            boolean waitTimedOut = false;
            // Concordance MH_TIMEOUT is 5 seconds. Use 6 seconds for Android's
            // USB scheduling overhead; do not retry or send additional packets.
            long deadline = SystemClock.elapsedRealtime() + 6000;
            while (!writeCompleted || !readCompleted) {
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0) {
                    waitTimedOut = true;
                    break;
                }
                try {
                    UsbRequest completed = conn.requestWait(remaining);
                    if (completed == writer) writeCompleted = true;
                    else if (completed == reader) readCompleted = true;
                    else if (completed == null) {
                        log.append("MH initial request wait returned null\n");
                        break;
                    }
                } catch (TimeoutException error) {
                    waitTimedOut = true;
                    break;
                }
            }
            int outBytes = command.position();
            int inBytes = response.position();
            log.append(initialExchangeStatus(writeCompleted, outBytes,
                    readCompleted, inBytes, waitTimedOut)).append('\n');
            boolean outComplete = writeCompleted && outBytes == 64;
            log.append("MH initialization OUT: ")
                    .append(outComplete ? "64 bytes sent" : "not confirmed").append('\n');

            if (readCompleted && inBytes > 0 && inBytes <= 64) {
                response.flip();
                byte[] bytes = new byte[inBytes];
                response.get(bytes);
                log.append(responseSummary(bytes, inBytes)).append('\n');
                boolean observed = outComplete && matchesObservedStageOne(bytes);
                log.append("Session continuation gate (observed response): ")
                        .append(observed ? "PASS" : "FAIL").append('\n');
                return observed;
            }

            log.append("MH initial reply: ")
                    .append(readCompleted ? "empty or malformed response"
                            : "no complete reply during 6-second wait")
                    .append('\n');
            log.append("No MH continuation or identity request sent.\n");
            return false;
        } finally {
            reader.cancel();
            writer.cancel();
            reader.close();
            writer.close();
        }
    }

    static boolean matchesObservedStageTwo(byte[] bytes) {
        return bytes != null && bytes.length == 64
                && (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0x00
                && (bytes[2] & 0xFF) == 0x82
                && (bytes[3] & 0xFF) == 0x00
                && (bytes[4] & 0xFF) == 0x7D;
    }

    /** Stage two exchanges only the documented FF 00 02 session message.
     *  Identity reading, if enabled by a matching reply, is a separate step.
     */
    private static boolean exchangeContinuation(UsbDeviceConnection conn, UsbEndpoint in,
                                             UsbEndpoint out, StringBuilder log)
            throws TimeoutException {
        UsbRequest reader = new UsbRequest();
        UsbRequest writer = new UsbRequest();
        try {
            if (!reader.initialize(conn, in) || !writer.initialize(conn, out)) {
                log.append("MH continuation: could not initialize USB transfers\n");
                return false;
            }
            ByteBuffer response = ByteBuffer.allocateDirect(64);
            ByteBuffer command = report(MH_CONTINUE);
            if (!reader.queue(response)) {
                log.append("MH continuation: could not queue IN\n");
                return false;
            }
            if (!writer.queue(command)) {
                log.append("MH continuation: could not queue OUT\n");
                return false;
            }
            boolean writeCompleted = false;
            boolean readCompleted = false;
            long deadline = SystemClock.elapsedRealtime() + 4000;
            while (!writeCompleted || !readCompleted) {
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0) break;
                UsbRequest completed = conn.requestWait(remaining);
                if (completed == writer) writeCompleted = true;
                else if (completed == reader) readCompleted = true;
            }
            log.append("MH continuation packet: FF 00 02 (zero-padded)\n");
            log.append("MH continuation OUT: ")
                    .append(writeCompleted && command.position() == 64
                            ? "64 bytes sent" : "not confirmed").append('\n');
            boolean stageTwo = false;
            if (readCompleted) {
                int count = response.position();
                response.flip();
                byte[] bytes = new byte[count];
                response.get(bytes);
                log.append("MH continuation reply:\n");
                log.append(responseSummary(bytes, count)).append('\n');
                log.append("(The FF FF 81 prefix check above applies to stage one only.)\n");
                stageTwo = writeCompleted && command.position() == 64
                        && matchesObservedStageTwo(bytes);
                log.append("Observed stage-two reply pattern: ")
                        .append(stageTwo ? "MATCH" : "DIFFERENT").append('\n');
            } else {
                log.append("MH continuation IN: none within 4 seconds\n");
            }
            log.append("No IR learning or configuration access attempted.\n");
            return stageTwo;
        } finally {
            reader.cancel();
            writer.cancel();
            reader.close();
            writer.close();
        }
    }

    public static String run(UsbManager manager, UsbDevice device) {
        StringBuilder log = new StringBuilder();
        log.append("IR Remote Studio — Harmony 300 read-only identity test").append('\n')
                .append("Read-only /sys/sysinfo with serial/GUID redaction; no permanent device changes")
                .append('\n').append('\n');
        if (manager == null || device == null ||
                !HarmonyUsbIds.isHarmony300Or350(device.getVendorId(), device.getProductId())) {
            return log.append("Expected Harmony USB ID 046D:C124; nothing sent.")
                    .toString();
        }
        if (!manager.hasPermission(device)) {
            return log.append("USB permission not granted; nothing sent.").toString();
        }

        UsbInterface hid = null;
        UsbEndpoint in = null;
        UsbEndpoint out = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface candidate = device.getInterface(i);
            if (candidate.getInterfaceClass() != UsbConstants.USB_CLASS_HID) continue;
            UsbEndpoint foundIn = null, foundOut = null;
            for (int j = 0; j < candidate.getEndpointCount(); j++) {
                UsbEndpoint ep = candidate.getEndpoint(j);
                if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_INT ||
                        ep.getMaxPacketSize() != 64) continue;
                if (ep.getDirection() == UsbConstants.USB_DIR_IN) foundIn = ep;
                if (ep.getDirection() == UsbConstants.USB_DIR_OUT) foundOut = ep;
            }
            if (foundIn != null && foundOut != null) {
                hid = candidate;
                in = foundIn;
                out = foundOut;
                break;
            }
        }
        if (hid == null) {
            return log.append("No matching HID interrupt endpoint pair; nothing sent.")
                    .toString();
        }

        UsbDeviceConnection conn = null;
        boolean claimed = false;
        try {
            conn = manager.openDevice(device);
            if (conn == null) return log.append("openDevice: failed").toString();
            log.append("openDevice: OK").append('\n');
            claimed = conn.claimInterface(hid, false);
            log.append("claimInterface(force=false): ")
                    .append(claimed ? "SUCCESS" : "FAILED").append('\n');
            if (!claimed) return log.append("No forced takeover attempted.").toString();

            // The MH 300 firmware expects five session-start packets before
            // a follow-up acknowledgement request.
            for (int i = 0; i < 5; i++) {
                if (!writeAndWait(conn, out, MH_START, log, "MH sync " + (i + 1))) {
                    return log.append("Stopped before final MH request.").toString();
                }
            }
            log.append("MH sync: five 64-byte OUT reports completed").append('\n');
            if (exchangeFinal(conn, in, out, log)) {
                if (exchangeContinuation(conn, in, out, log)) {
                    HarmonySysinfoProbe.readIdentity(conn, in, out, log);
                } else {
                    log.append("sysinfo read skipped: stage-two reply did not match observed pattern\n");
                }
            } else {
                log.append("MH continuation skipped: stage-one response not confirmed\n");
            }
        } catch (TimeoutException error) {
            log.append("MH IN/OUT acknowledgement timed out").append('\n');
        } catch (SecurityException error) {
            log.append("USB permission error: ")
                    .append(error.getClass().getSimpleName()).append('\n');
        } catch (RuntimeException error) {
            log.append("USB error: ").append(error.getClass().getSimpleName())
                    .append(": ").append(error.getMessage()).append('\n');
        } finally {
            if (conn != null) {
                if (claimed) {
                    try {
                        conn.releaseInterface(hid);
                        log.append("HID interface released").append('\n');
                    } catch (RuntimeException ignored) {
                        log.append("Unable to release HID interface").append('\n');
                    }
                }
                conn.close();
                log.append("USB connection closed").append('\n');
            }
        }
        return log.toString();
    }
}
