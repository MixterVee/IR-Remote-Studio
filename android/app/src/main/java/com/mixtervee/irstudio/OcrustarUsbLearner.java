package com.mixtervee.irstudio;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.SystemClock;

import java.util.Locale;

/**
 * Direct Android USB-host receiver for ElkSmart/Ocrustar D226/D552.
 *
 * Does not use or alter the vendor app, cloud API, dongle firmware, or
 * configuration. Sends ONLY the documented USB FC handshake, FA ACK,
 * FE start and FD stop learning tokens. Unknown device identities fail closed.
 *
 * The USB learning protocol is independently documented:
 * https://github.com/deadboy18/Ocrustar-USB-IR/blob/main/docs/PROTOCOL.md
 * Physical Ocrustar hardware verification is still required.
 */
public final class OcrustarUsbLearner {
    private OcrustarUsbLearner() {}

    public interface Progress {
        /** Start-learning request has been sent; hardware ACK pending. */
        void ready(String model);
        /** Documented FF FF FF FF learn ACK has actually been received. */
        default void acknowledged(String model) { }
    }

    public static UsbDevice find(UsbManager manager) {
        if (manager == null) return null;
        for (UsbDevice candidate : manager.getDeviceList().values()) {
            if (OcrustarUsbProtocol.isCandidate(candidate.getVendorId(),
                    candidate.getProductId())) return candidate;
        }
        return null;
    }

    public static String status(UsbManager manager) {
        UsbDevice device = find(manager);
        if (device == null) return "NOT CONNECTED — Insert Ocrustar USB-C dongle";
        String label = "OCRUSTAR " + OcrustarUsbProtocol.usbId(
                device.getVendorId(), device.getProductId());
        if (!manager.hasPermission(device))
            return label + " — Tap USB Setup for permission";
        return bulkPort(device) != null
                ? label + " — USB ACCESS GRANTED"
                : label + " — Unsupported endpoint layout; open USB Setup";
    }

    /** This is a user-initiated Android permission request, not a protocol write. */
    public static void requestPermission(Activity activity, UsbManager manager,
                                         UsbDevice device) {
        Intent intent = new Intent(activity, UsbAccessReceiver.class);
        intent.setAction(UsbAccessReceiver.ACTION_USB_GRANT);
        PendingIntent pending = PendingIntent.getBroadcast(activity, 17, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        manager.requestPermission(device, pending);
    }

    private static final class Port {
        final UsbInterface usbInterface;
        final UsbEndpoint in;
        final UsbEndpoint out;
        Port(UsbInterface usbInterface, UsbEndpoint in, UsbEndpoint out) {
            this.usbInterface = usbInterface;
            this.in = in;
            this.out = out;
        }
    }

    private static Port bulkPort(UsbDevice device) {
        final int expectedIn = OcrustarUsbProtocol.expectedBulkIn(
                device.getVendorId(), device.getProductId());
        final int expectedOut = OcrustarUsbProtocol.expectedBulkOut(
                device.getVendorId(), device.getProductId());
        if (expectedIn < 0 || expectedOut < 0) return null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            UsbEndpoint in = null, out = null;
            for (int j = 0; j < iface.getEndpointCount(); j++) {
                UsbEndpoint ep = iface.getEndpoint(j);
                if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                if (ep.getAddress() == expectedIn
                        && ep.getDirection() == UsbConstants.USB_DIR_IN)
                    in = ep;
                else if (ep.getAddress() == expectedOut
                        && ep.getDirection() == UsbConstants.USB_DIR_OUT)
                    out = ep;
            }
            if (in != null && out != null) return new Port(iface, in, out);
        }
        return null;
    }


    /**
     * USB-only transaction trace. Does not record entire learned IR payloads,
     * serial numbers, stored remotes, or any personal data. Diagnostic bytes are
     * capped at the first 16 of each *initial* incoming packet.
     *
     * Android bulkTransfer() returns -1 for both timeout and some USB errors;
     * do not claim that -1 proves either condition.
     */
    private static final class UsbTrace {
        private static final String[] PHASES =
                {"DRAIN", "IDENTITY", "POST-AUTH", "LEARNING"};
        private final int[] reads = new int[4];
        private final int[] empty = new int[4];
        private final int[] packets = new int[4];
        private final int[] bytes = new int[4];
        private final StringBuilder events = new StringBuilder();
        private int eventCount;
        volatile int phase;

        void setPhase(int phase) { this.phase = phase; }

        synchronized void log(String event) {
            if (eventCount++ < 45) {
                events.append("  ").append(event).append('\n');
            } else if (eventCount == 46) {
                events.append("  (additional events omitted)\n");
            }
        }

        synchronized void write(String label, byte[] data, int result) {
            log("OUT " + label + ": " + result + "/" + data.length
                    + " bytes, command " + shortHex(data, data.length));
        }

        synchronized void read(int phase, int result, byte[] data) {
            reads[phase]++;
            if (result <= 0) {
                empty[phase]++;
                return;
            }
            packets[phase]++;
            bytes[phase] += result;
            // Record only a few initial packets, never a full remote waveform.
            if (packets[phase] <= 3)
                log("IN " + PHASES[phase] + ": " + result
                        + " bytes, first bytes " + shortHex(data, result));
        }

        synchronized String report() {
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < PHASES.length; i++) {
                result.append(PHASES[i]).append(": ")
                        .append(reads[i]).append(" reads, ")
                        .append(empty[i]).append(" no-data/-1, ")
                        .append(packets[i]).append(" received packets, ")
                        .append(bytes[i]).append(" bytes\n");
            }
            result.append("Note: Android bulkTransfer -1 can mean a timeout ")
                    .append("or a transfer error; the USB API does not distinguish them.\n")
                    .append("USB EVENTS (bounded):\n").append(events);
            return result.toString();
        }

        private static String shortHex(byte[] data, int length) {
            if (data == null || length <= 0) return "(none)";
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < Math.min(length, 16); i++) {
                if (i > 0) result.append(' ');
                result.append(String.format(Locale.ROOT, "%02X", data[i] & 0xFF));
            }
            if (length > 16) result.append(" ...");
            return result.toString();
        }
    }

    public static String describeUsb(UsbManager manager) {
        UsbDevice device = find(manager);
        if (device == null) {
            return "No known Ocrustar / ElkSmart USB dongle found. "
                    + "Insert the dongle in your USB-C port and reopen USB Setup. "
                    + "Some hardware revisions use different USB IDs.";
        }
        Port port = bulkPort(device);
        final int expectedIn = OcrustarUsbProtocol.expectedBulkIn(
                device.getVendorId(), device.getProductId());
        final int expectedOut = OcrustarUsbProtocol.expectedBulkOut(
                device.getVendorId(), device.getProductId());
        StringBuilder result = new StringBuilder("USB ID: ")
                .append(OcrustarUsbProtocol.usbId(
                        device.getVendorId(), device.getProductId()))
                .append("\nAndroid USB permission: ")
                .append(manager.hasPermission(device) ? "GRANTED" : "NOT GRANTED")
                .append(String.format(Locale.ROOT,
                        "\nExpected bulk OUT 0x%02X / IN 0x%02X: ",
                        expectedOut, expectedIn))
                .append(port != null ? "FOUND" : "NOT FOUND");
        result.append("\nUSB interfaces: ").append(device.getInterfaceCount());
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            result.append(String.format(Locale.ROOT,
                    "\n  Interface %d: class 0x%02X, subclass 0x%02X",
                    iface.getId(), iface.getInterfaceClass(),
                    iface.getInterfaceSubclass()));
            for (int j = 0; j < iface.getEndpointCount(); j++) {
                UsbEndpoint ep = iface.getEndpoint(j);
                result.append(String.format(Locale.ROOT,
                        "\n    Endpoint 0x%02X (%s, type %s, packet %d)",
                        ep.getAddress(),
                        ep.getDirection() == UsbConstants.USB_DIR_IN ? "IN" : "OUT",
                        ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                                ? "BULK" : ep.getType() ==
                                        UsbConstants.USB_ENDPOINT_XFER_INT
                                ? "INTERRUPT" : "OTHER",
                        ep.getMaxPacketSize()));
            }
        }
        if (port == null) result.append("\n\nEndpoint layout does not match "
                + "this driver. No learning commands will be sent. "
                + "Please copy or screenshot this diagnostic report.");
        else result.append("\n\nBulk interface matches this hardware model. "
                + "The first learning attempt must still verify its "
                + "identity before entering learning mode.");
        return result.toString();
    }

    private static void write(UsbDeviceConnection conn, UsbEndpoint out,
                              byte[] bytes, String label, UsbTrace trace) {
        int sent = conn.bulkTransfer(out, bytes, bytes.length, 1000);
        trace.write(label, bytes, sent);
        if (sent != bytes.length)
            throw new IllegalStateException(label + " USB write failed ("
                    + sent + "/" + bytes.length + ").");
    }

    private static final class Handshake {
        final String model;
        final boolean acknowledged;
        Handshake(String model, boolean acknowledged) {
            this.model = model;
            this.acknowledged = acknowledged;
        }
    }

    private static Handshake handshake(UsbDeviceConnection conn, Port port,
                                       int productId, UsbTrace trace,
                                       OcrustarUsbReadPump receiver) {
        trace.setPhase(1);
        for (int attempt = 0; attempt < 3; attempt++) {
            write(conn, port.out, OcrustarUsbProtocol.HELLO, "Identity", trace);
            long deadline = SystemClock.elapsedRealtime() + 1000;
            while (SystemClock.elapsedRealtime() < deadline) {
                int waitMs = (int) Math.min(200,
                        deadline - SystemClock.elapsedRealtime());
                if (waitMs <= 0) break;
                byte[] packet = receiver.poll(waitMs);
                if (packet == null) continue;
                // The 0132 can emit the iAP2 detect token FF 55 02 00
                // EE 10 at USB connect. It is NOT a model response.
                if (packet.length < 6
                        || !startsWith(packet, (byte) 0xFC)
                        && !(productId == 0x0132 && startsWith(packet, (byte) 0xFA))) {
                    trace.log("Ignoring non-identity USB startup packet ("
                            + packet.length + " bytes)");
                    continue;
                }
                String type = OcrustarUsbProtocol.identifyForUsbPid(
                        productId, packet, packet.length);
                boolean sendAck = OcrustarUsbProtocol.shouldAcknowledgeIdentity(
                        productId, packet, packet.length);
                trace.log("Identity classified as " + type + " for PID "
                        + String.format(Locale.ROOT, "%04X", productId));
                if (sendAck) {
                    write(conn, port.out, OcrustarUsbProtocol.ACK,
                            "Identity acknowledgment after FC model", trace);
                } else {
                    trace.log("FA firmware-version identity: no FA echo");
                }
                return new Handshake(type, sendAck);
            }
        }
        throw new IllegalStateException(
                "No D226/D552 identity reply. Close the Ocrustar app, "
                + "unplug/reinsert the dongle and try again.");
    }

    private static boolean startsWith(byte[] packet, byte value) {
        return packet.length >= 4 && packet[0] == value && packet[1] == value
                && packet[2] == value && packet[3] == value;
    }

    /**
     * Read firmware metadata from the SAME continuously running USB worker.
     * Never echo the FA reply; this is not an authorization challenge.
     */
    private static String readPostAuthVersion(OcrustarUsbReadPump receiver,
                                              UsbTrace trace) {
        trace.setPhase(2);
        byte[] prefix = new byte[32];
        int size = 0, packets = 0;
        long deadline = SystemClock.elapsedRealtime() + 450;
        while (SystemClock.elapsedRealtime() < deadline && packets < 8) {
            int waitMs = (int) Math.min(100,
                    deadline - SystemClock.elapsedRealtime());
            if (waitMs <= 0) break;
            byte[] read = receiver.poll(waitMs);
            if (read == null) continue;
            packets++;
            int add = Math.min(read.length, prefix.length - size);
            if (add > 0) {
                System.arraycopy(read, 0, prefix, size, add);
                size += add;
            }
        }
        if (OcrustarUsbProtocol.isFirmwareVersionReply(prefix, size))
            return "Firmware/version reply observed before learning: "
                    + OcrustarUsbProtocol.firmwareVersionReply(prefix, size);
        if (size == 0) return "No post-auth firmware reply before learning.";
        StringBuilder result = new StringBuilder("Post-auth USB bytes (first ");
        result.append(size).append("): ");
        for (int i = 0; i < size; i++) {
            if (i > 0) result.append(' ');
            result.append(String.format(Locale.ROOT, "%02X", prefix[i] & 0xFF));
        }
        return result.toString();
    }

    /**
     * Call on a worker thread only. Always sends STOP_LEARN in finally when
     * START_LEARN was successfully written, even after signal errors/timeouts.
     */
    public static String learn(UsbManager manager, UsbDevice device,
                               Progress progress) {
        if (manager == null || device == null
                || !OcrustarUsbProtocol.isCandidate(
                        device.getVendorId(), device.getProductId()))
            throw new IllegalArgumentException("No recognized Ocrustar USB device.");
        if (!manager.hasPermission(device))
            throw new IllegalStateException("Grant USB permission in USB Setup first.");
        Port port = bulkPort(device);
        if (port == null)
            throw new IllegalStateException(String.format(Locale.ROOT,
                    "Ocrustar expected bulk OUT 0x%02X / IN 0x%02X not present. "
                    + "Open USB Setup and capture its endpoint list.",
                    OcrustarUsbProtocol.expectedBulkOut(device.getVendorId(),
                            device.getProductId()),
                    OcrustarUsbProtocol.expectedBulkIn(device.getVendorId(),
                            device.getProductId())));
        UsbDeviceConnection connection = manager.openDevice(device);
        if (connection == null)
            throw new IllegalStateException(
                    "Cannot open dongle. Close the official Ocrustar app first.");
        boolean claimed = false, armed = false;
        OcrustarUsbReadPump receiver = null;
        String pronto = null;
        RuntimeException failure = null;
        UsbTrace trace = new UsbTrace();
        try {
            trace.log(String.format(Locale.ROOT,
                    "Device %04X:%04X, interface %d, OUT 0x%02X / IN 0x%02X",
                    device.getVendorId(), device.getProductId(),
                    port.usbInterface.getId(), port.out.getAddress(),
                    port.in.getAddress()));
            // Match the official app's interface-claim behavior. A single
            // receiver thread must own IN before any authentication writes.
            claimed = connection.claimInterface(port.usbInterface, true);
            trace.log("claimInterface(force=true): " + (claimed ? "SUCCESS" : "FAILED"));
            if (!claimed)
                throw new IllegalStateException(
                        "USB interface busy. Close the Ocrustar app.");
            final boolean native0132 = device.getProductId() == 0x0132;
            receiver = new OcrustarUsbReadPump(
                    (buffer, timeoutMs) -> connection.bulkTransfer(
                            port.in, buffer, buffer.length, timeoutMs),
                    (count, buffer) -> trace.read(trace.phase, count, buffer));
            receiver.start();
            trace.log("Dedicated USB IN thread started: 16384-byte buffer, "
                    + "500 ms read timeout, bounded packet queue");
            // Allow the receiver to consume the device's unsolicited iAP2
            // connect token before the FC identity request.
            SystemClock.sleep(80);
            trace.log("Pre-auth packets discarded: " + receiver.discardPending());
            Handshake identity = handshake(connection, port,
                    device.getProductId(), trace, receiver);
            String type = identity.model;
            // Let firmware settle after acknowledging the FC D552 identity
            // BEFORE starting FE learning. This response is metadata, not an
            // infrared packet and must not be answered with another FA.
            String authFollowUp = identity.acknowledged
                    ? readPostAuthVersion(receiver, trace)
                    : "Already received FA firmware-version identity; no FA echo.";
            trace.log("AUTH FOLLOW-UP: " + authFollowUp);
            trace.setPhase(3);
            write(connection, port.out, OcrustarUsbProtocol.START_LEARN,
                    "Start IR learning", trace);
            armed = true;
            if (progress != null) progress.ready(type);
            OcrustarUsbProtocol.Capture capture = new OcrustarUsbProtocol.Capture();
            byte[] buffer = new byte[512];
            try {
                long deadline = SystemClock.elapsedRealtime() + 15_000;
                boolean acknowledged = false;
                int learningPackets = 0;
                while (SystemClock.elapsedRealtime() < deadline && !capture.complete()) {
                    int waitMs = (int) Math.min(300,
                            deadline - SystemClock.elapsedRealtime());
                    if (waitMs <= 0) break;
                    byte[] packet = receiver.poll(waitMs);
                    if (packet == null) continue;
                    learningPackets++;
                    capture.accept(packet, packet.length);
                    if (!acknowledged && capture.sawLearnAck()) {
                        acknowledged = true;
                        if (progress != null) progress.acknowledged(type);
                    }
                }
                trace.log("Learn result: incomingPackets=" + learningPackets
                        + ", captured=" + capture.complete()
                        + ", FE retries=0 (removed)");
                if (!capture.complete()) {
                    if (native0132) {
                        // Android's independently published 0132 driver
                        // waits for FE capture data, without a mandatory FF
                        // acknowledgement packet. No FF alone is not failure.
                        throw new IllegalStateException(
                                "No FE infrared capture packet arrived from "
                                + "Ocrustar 045C:0132 in 15 seconds. "
                                + "Press a physical IR remote button as soon "
                                + "as LISTENING appears. No separate FF "
                                + "learn acknowledgement is required for "
                                + "this Android hardware path.");
                    }
                    if (!capture.sawLearnAck()) {
                        throw new IllegalStateException(
                                "Ocrustar did not confirm the start-learning "
                                + "command (FF FF FF FF). No complete IR "
                                + "signal was received in 15 seconds.");
                    }
                    throw new IllegalStateException(
                            "Ocrustar acknowledged learning, but no complete "
                            + "IR signal arrived in 15 seconds. Aim the "
                            + "physical source remote at the dongle's receiver.");
                }
                pronto = capture.pronto();
            } catch (RuntimeException problem) {
                // A timeout alone cannot distinguish missing IR, unacknowledged
                // learn mode or a payload framing mismatch. Surface the evidence.
                throw new IllegalStateException(
                        problem.getMessage()
                        + "\n\nAUTH FOLLOW-UP\n" + authFollowUp
                        + "\n\nUSB RECEIVE DIAGNOSTICS\n"
                        + capture.diagnostic(), problem);
            }
        } catch (RuntimeException error) {
            failure = error;
        } finally {
            if (armed) {
                try {
                    write(connection, port.out, OcrustarUsbProtocol.STOP_LEARN,
                            "Stop IR learning", trace);
                } catch (RuntimeException stopFailure) {
                    trace.log("Stop command failed: " + stopFailure.getMessage());
                    // Closing the USB session is still mandatory.
                }
            } else {
                trace.log("STOP not sent; START was not successfully written");
            }
            if (receiver != null) {
                receiver.stopAndJoin();
                trace.log("Dedicated USB IN thread stopped");
            }
            if (claimed) {
                try {
                    trace.log("releaseInterface: "
                            + (connection.releaseInterface(port.usbInterface)
                                    ? "SUCCESS" : "FAILED"));
                } catch (RuntimeException releaseError) {
                    trace.log("releaseInterface error: " + releaseError.getMessage());
                }
            }
            connection.close();
            trace.log("USB connection closed");
        }
        if (failure != null)
            throw new IllegalStateException(failure.getMessage()
                    + "\n\nUSB TRANSACTION TRACE\n" + trace.report(), failure);
        return pronto;
    }
}
