package com.mixtervee.irstudio;

import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbRequest;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

/**
 * Bounded metadata-only /sys/sysinfo read for Harmony MH remotes.
 * Independent implementation using published Concordance protocol facts.
 * Does not read /cfg/usercfg or /ir/ir_cap, or send any write-file command.
 */
final class HarmonySysinfoProbe {
    private HarmonySysinfoProbe() {}
    private static final int REPORT_SIZE = 64;
    private static final int MAX_SYSINFO_SIZE = 1000;
    private static final long TIMEOUT_MS = 5000;

    static byte[] readRequest() {
        byte[] path = "/sys/sysinfo".getBytes(StandardCharsets.US_ASCII);
        byte[] data = new byte[REPORT_SIZE];
        data[0] = (byte) 0xFF;
        data[1] = 0x01;
        data[2] = 0x03; // CRemoteMH::GetIdentity invokes ReadFile with start_seq=0x03
        data[3] = 0x02;
        data[4] = (byte) 0x80;
        System.arraycopy(path, 0, data, 5, path.length);
        int end = 5 + path.length;
        data[end] = 0x00;
        data[end + 1] = (byte) 0x80;
        data[end + 2] = 'R';
        data[end + 3] = 0x00;
        return data;
    }

    static int declaredLength(byte[] response) {
        if (response == null || response.length < 11 || response.length > REPORT_SIZE) return -1;
        long length = ((long) (response[7] & 0xFF) << 24)
                | ((long) (response[8] & 0xFF) << 16)
                | ((long) (response[9] & 0xFF) << 8)
                | (long) (response[10] & 0xFF);
        if (length <= 0 || length > MAX_SYSINFO_SIZE) return -1;
        return (int) length;
    }

    /** GetIdentity's /sys/sysinfo transfer uses start_seq=3: read=3, ACK=4, close=5. */
    static byte[] ackRequest(int length, byte parameter) {
        if (length < 1 || length > MAX_SYSINFO_SIZE)
            throw new IllegalArgumentException("Invalid sysinfo size");
        int packets = (length + 61) / 62;
        int count = Math.min(packets + 1, 0x33);
        return new byte[] {
                (byte) 0xFF, 0x04, 0x04, 0x02, 0x01, parameter,
                0x01, (byte) count
        };
    }

    static byte[] closeRequest(byte parameter) {
        return new byte[] {
                (byte) 0xFF, 0x07, 0x05, 0x01, 0x01, parameter
        };
    }

    private static String firstBytes(byte[] reply) {
        StringBuilder value = new StringBuilder();
        if (reply == null) return "none";
        for (int i = 0; i < Math.min(reply.length, 12); i++) {
            if (i > 0) value.append(' ');
            value.append(String.format(Locale.ROOT, "%02X", reply[i] & 0xFF));
        }
        return value.toString();
    }

    /**
     * All MH control commands are zero-padded to a complete 64-byte HID OUT
     * report. ACK and sequence-close prefixes are only 8 and 6 bytes long.
     * Sending their raw lengths previously caused a partial OUT transfer.
     */
    static byte[] paddedHidReport(byte[] prefix) {
        if (prefix == null || prefix.length == 0 || prefix.length > REPORT_SIZE) {
            throw new IllegalArgumentException("MH HID command must be 1..64 bytes");
        }
        byte[] packet = new byte[REPORT_SIZE];
        System.arraycopy(prefix, 0, packet, 0, prefix.length);
        return packet;
    }

    private static ByteBuffer direct(byte[] data) {
        ByteBuffer bytes = ByteBuffer.allocateDirect(REPORT_SIZE);
        bytes.put(paddedHidReport(data));
        bytes.flip();
        return bytes;
    }

    /**
     * One 64-byte OUT with an IN request already queued. Both transfers
     * must complete successfully before this routine returns a response.
     */
    private static byte[] exchange(UsbDeviceConnection connection, UsbEndpoint in,
                                   UsbEndpoint out, byte[] command, StringBuilder log,
                                   String label) throws TimeoutException {
        UsbRequest reader = new UsbRequest();
        UsbRequest writer = new UsbRequest();
        try {
            if (!reader.initialize(connection, in) || !writer.initialize(connection, out)) {
                log.append(label).append(": endpoint initialization failed\n");
                return null;
            }
            ByteBuffer incoming = ByteBuffer.allocateDirect(REPORT_SIZE);
            ByteBuffer outgoing = direct(command);
            if (!reader.queue(incoming)) {
                log.append(label).append(": IN queue failed\n");
                return null;
            }
            if (!writer.queue(outgoing)) {
                log.append(label).append(": OUT queue failed\n");
                return null;
            }
            boolean gotIn = false;
            boolean gotOut = false;
            long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
            while (!gotIn || !gotOut) {
                long left = deadline - SystemClock.elapsedRealtime();
                if (left <= 0) break;
                UsbRequest complete = connection.requestWait(left);
                if (complete == reader) gotIn = true;
                else if (complete == writer) gotOut = true;
            }
            int sent = outgoing.position();
            int received = incoming.position();
            log.append(label).append(": OUT=")
                    .append(gotOut).append('/').append(sent).append(" bytes, IN=")
                    .append(gotIn).append('/').append(received).append(" bytes\n");
            if (!gotOut || sent != REPORT_SIZE || !gotIn ||
                    received < 1 || received > REPORT_SIZE) {
                log.append(label).append(": incomplete, empty or timed out\n");
                return null;
            }
            incoming.flip();
            byte[] reply = new byte[received];
            incoming.get(reply);
            // Only log the short control header; never log file-data contents.
            log.append(label).append(" response prefix: ")
                    .append(firstBytes(reply)).append('\n');
            return reply;
        } finally {
            reader.cancel();
            writer.cancel();
            reader.close();
            writer.close();
        }
    }

    /** Bounded read of one 64-byte HID report with no OUT packet. */
    private static byte[] read(UsbDeviceConnection connection, UsbEndpoint in,
                               StringBuilder log) throws TimeoutException {
        UsbRequest reader = new UsbRequest();
        try {
            if (!reader.initialize(connection, in)) {
                log.append("MH file data IN initialization failed\n");
                return null;
            }
            ByteBuffer incoming = ByteBuffer.allocateDirect(REPORT_SIZE);
            if (!reader.queue(incoming)) {
                log.append("MH file data IN queue failed\n");
                return null;
            }
            UsbRequest done = connection.requestWait(TIMEOUT_MS);
            int received = incoming.position();
            if (done != reader || received < 2 || received > REPORT_SIZE) {
                log.append("MH file data IN timed out, empty or incomplete (bytes=")
                        .append(received).append(")\n");
                return null;
            }
            incoming.flip();
            byte[] reply = new byte[received];
            incoming.get(reply);
            return reply;
        } finally {
            reader.cancel();
            reader.close();
        }
    }

    /**
     * Only expose allow-listed non-identifying sysinfo fields.
     * GUID, serial_number and unrecognized fields are never appended.
     */
    static String safeMetadata(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        StringBuilder result = new StringBuilder();
        String[] allowed = {"fw_ver", "hw_ver", "arch", "fw_type", "skin"};
        for (String key : allowed) {
            for (String line : text.split("[\\r\\n\\u0000]+")) {
                if (!line.startsWith(key + " ")) continue;
                String value = line.substring(key.length()).trim();
                if (value.matches("[A-Za-z0-9._-]{1,32}")) {
                    result.append(key).append(": ").append(value).append('\n');
                }
                break;
            }
        }
        if (result.length() == 0) result.append("Known firmware metadata not found\n");
        result.append("Serial numbers and GUIDs omitted from report.\n");
        return result.toString();
    }

    /**
     * Uses only the documented read-only sysinfo path. The final 0x07 packet
     * resets the transient file-transfer SEQUENCE, not device programming.
     */
    static void readIdentity(UsbDeviceConnection connection, UsbEndpoint in,
                             UsbEndpoint out, StringBuilder log) throws TimeoutException {
        log.append("\nRead-only identity query: /sys/sysinfo\n");
        byte[] header = exchange(connection, in, out, readRequest(), log,
                "sysinfo read request");
        if (header == null) return;
        int length = declaredLength(header);
        log.append("sysinfo declared size: ").append(length).append('\n');
        if (length < 1) {
            log.append("Untrusted or excessive file length: stopped before file ACK\n");
            return;
        }

        int dataPackets = (length + 61) / 62;
        byte parameter = header[5];
        byte[] ack = ackRequest(length, parameter);
        log.append("sysinfo expected data packets: ").append(dataPackets)
                .append("; ACK sequence: 04; packet count + 1: ")
                .append(ack[7] & 0xFF).append('\n');
        boolean transferStarted = false;
        try {
            byte[] ackResponse = exchange(connection, in, out, ack, log,
                    "sysinfo transfer ACK");
            if (ackResponse == null || ackResponse.length < 3) {
                log.append("sysinfo ACK reply too short; file read stopped\n");
                return;
            }
            transferStarted = true;
            int expectedSeq = ackResponse[2] & 0x3F;
            ByteArrayOutputStream content = new ByteArrayOutputStream(length);
            for (int i = 0; i < dataPackets; i++) {
                byte[] packet = read(connection, in, log);
                if (packet == null) return;
                expectedSeq = (expectedSeq + 1) & 0x3F;
                int seq = packet[0] & 0x3F;
                int size = packet[1] & 0x3F;
                if (seq != expectedSeq || size < 1 || size > 62
                        || packet.length < size + 2 || content.size() + size > length) {
                    log.append("sysinfo data packet malformed at index ")
                            .append(i + 1).append("; stopped\n");
                    return;
                }
                content.write(packet, 2, size);
            }
            log.append("sysinfo collected bytes: ").append(content.size()).append('\n');
            if (content.size() != length) {
                log.append("sysinfo incomplete; metadata withheld\n");
                return;
            }
            log.append(safeMetadata(content.toByteArray()));
        } finally {
            // The documented session-sequence reset is permitted only after
            // the file ACK has been accepted, never blindly after an error.
            if (transferStarted) {
                byte[] reset = closeRequest(parameter);
                byte[] resetResponse = exchange(connection, in, out, reset,
                        log, "sysinfo sequence close");
                log.append("sysinfo session sequence close (05): ")
                        .append(resetResponse != null ? "reply received" : "unconfirmed")
                        .append('\n');
            }
        }
    }
}
