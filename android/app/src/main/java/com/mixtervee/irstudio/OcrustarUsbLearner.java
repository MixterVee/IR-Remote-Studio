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
        void ready(String model);
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
                              byte[] bytes, String label) {
        int sent = conn.bulkTransfer(out, bytes, bytes.length, 1000);
        if (sent != bytes.length)
            throw new IllegalStateException(label + " USB write failed ("
                    + sent + "/" + bytes.length + ").");
    }

    private static String handshake(UsbDeviceConnection conn, Port port) {
        // Stale IN data from a previous vendor app session must not be
        // mistaken for a valid reply. Read only, bounded to five packets.
        byte[] packet = new byte[512];
        for (int i = 0; i < 5; i++) {
            if (conn.bulkTransfer(port.in, packet, packet.length, 15) <= 0) break;
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            write(conn, port.out, OcrustarUsbProtocol.HELLO, "Identity");
            long deadline = SystemClock.elapsedRealtime() + 1000;
            while (SystemClock.elapsedRealtime() < deadline) {
                int received = conn.bulkTransfer(port.in, packet, packet.length, 200);
                if (received <= 0) continue;
                // Do not acknowledge or arm an unrecognized reply.
                String type = OcrustarUsbProtocol.identifyHello(packet, received);
                write(conn, port.out, OcrustarUsbProtocol.ACK, "Identity acknowledgment");
                return type;
            }
        }
        throw new IllegalStateException(
                "No D226/D552 identity reply. Close the Ocrustar app, "
                + "unplug/reinsert the dongle and try again.");
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
        try {
            claimed = connection.claimInterface(port.usbInterface, false);
            if (!claimed)
                throw new IllegalStateException(
                        "USB interface busy. Close the Ocrustar app.");
            String type = handshake(connection, port);
            write(connection, port.out, OcrustarUsbProtocol.START_LEARN,
                    "Start IR learning");
            armed = true;
            if (progress != null) progress.ready(type);
            OcrustarUsbProtocol.Capture capture = new OcrustarUsbProtocol.Capture();
            byte[] buffer = new byte[512];
            try {
                long deadline = SystemClock.elapsedRealtime() + 15_000;
                while (SystemClock.elapsedRealtime() < deadline && !capture.complete()) {
                    int count = connection.bulkTransfer(port.in, buffer, buffer.length, 300);
                    if (count > 0) capture.accept(buffer, count);
                }
                if (!capture.complete())
                    throw new IllegalStateException(
                            "No complete IR signal in 15 seconds. "
                            + "Aim a separate physical remote at the dongle "
                            + "and press once immediately after Listening appears.");
                return capture.pronto();
            } catch (RuntimeException problem) {
                // A timeout alone cannot distinguish missing IR, unacknowledged
                // learn mode or a payload framing mismatch. Surface the evidence.
                throw new IllegalStateException(
                        problem.getMessage() + "\n\nUSB RECEIVE DIAGNOSTICS\n"
                        + capture.diagnostic(), problem);
            }
        } finally {
            if (armed) {
                try {
                    write(connection, port.out, OcrustarUsbProtocol.STOP_LEARN,
                            "Stop IR learning");
                } catch (RuntimeException ignored) {
                    // Closing the USB session is still mandatory.
                }
            }
            if (claimed) connection.releaseInterface(port.usbInterface);
            connection.close();
        }
    }
}
