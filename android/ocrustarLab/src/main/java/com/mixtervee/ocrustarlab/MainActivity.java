package com.mixtervee.ocrustarlab;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Separate, narrowly scoped USB diagnostic for the known working Ocrustar
 * 045C:0132 dongle. Does not use the IR Remote Studio package or storage.
 *
 * No firmware updates, persistent configuration commands or IR playback.
 */
public final class MainActivity extends Activity {
    private static final int VENDOR_ID = 0x045C;
    private static final int PRODUCT_ID = 0x0132;
    private static final int BULK_OUT = 0x02;
    private static final int BULK_IN = 0x82;

    private static final byte[] HELLO = {(byte) 0xFC, (byte) 0xFC, (byte) 0xFC, (byte) 0xFC};
    private static final byte[] HELLO_ACK = {(byte) 0xFA, (byte) 0xFA, (byte) 0xFA, (byte) 0xFA};
    private static final byte[] LEARN_START = {(byte) 0xFE, (byte) 0xFE, (byte) 0xFE, (byte) 0xFE};
    private static final byte[] LEARN_STOP = {(byte) 0xFD, (byte) 0xFD, (byte) 0xFD, (byte) 0xFD};

    private static final long CAPTURE_MS = 15_000L;
    private static final int READ_TIMEOUT_MS = 400;
    private static final int MAX_LOG_CHARS = 160_000;
    private static final String PERMISSION_ACTION =
            "com.mixtervee.ocrustarlab.action.USB_PERMISSION";

    private UsbManager usbManager;
    private TextView reportView;
    private Button withHandshakeButton;
    private Button withoutHandshakeButton;
    private Button stopButton;
    private Button replayButton;
    private volatile int[] lastTimings;

    private final StringBuilder report = new StringBuilder(8192);
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);

    private boolean permissionPending;
    private boolean pendingHandshake;
    private long traceStart;

    private static final class Port {
        final UsbInterface usbInterface;
        final UsbEndpoint input;
        final UsbEndpoint output;

        Port(UsbInterface usbInterface, UsbEndpoint input, UsbEndpoint output) {
            this.usbInterface = usbInterface;
            this.input = input;
            this.output = output;
        }
    }

    private final BroadcastReceiver permissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!PERMISSION_ACTION.equals(intent.getAction())) return;
            permissionPending = false;
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean granted = intent.getBooleanExtra(
                    UsbManager.EXTRA_PERMISSION_GRANTED, false);
            if (!granted || device == null ||
                    device.getVendorId() != VENDOR_ID ||
                    device.getProductId() != PRODUCT_ID) {
                log("USB permission not granted, or unexpected device. Nothing sent.");
                setIdle();
                return;
            }
            log("Android granted USB access.");
            runTest(device, pendingHandshake);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        IntentFilter filter = new IntentFilter(PERMISSION_ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(permissionReceiver, filter);
        }
        makeUi();
        resetReport();
        log("Ocrustar IR Lab — replay suspended pending hardware investigation");
        log("Expected hardware 045C:0132, bulk OUT 0x02 / IN 0x82.");
        log("Press Scan first. Start Learning is an explicit USB command test.");
        scan();
    }

    private Button button(String title, LinearLayout parent) {
        Button button = new Button(this);
        button.setText(title);
        button.setAllCaps(false);
        parent.addView(button);
        return button;
    }

    private void makeUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(10), dp(12), dp(12));
        setContentView(root);

        TextView title = new TextView(this);
        title.setText("Ocrustar IR Lab");
        title.setTextSize(23);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Isolated learn and test replay; no firmware or saved remote changes.");
        sub.setTextSize(13);
        root.addView(sub);

        Button scanButton = button("1  Scan USB (read-only)", root);
        scanButton.setOnClickListener(view -> scan());

        withHandshakeButton = button("2  Learn IR — handshake + capture", root);
        withHandshakeButton.setOnClickListener(view -> startTest(true));

        withoutHandshakeButton = button("Alternative: Learn IR — no handshake", root);
        withoutHandshakeButton.setOnClickListener(view -> startTest(false));

        replayButton = button("3  Replay SUSPENDED — receiver investigation", root);
        replayButton.setEnabled(false);
        replayButton.setOnClickListener(view -> {
            log("Replay is suspended pending receiver fault investigation.");
            if (true) return;
            if (lastTimings == null || busy.get()) return;
            new AlertDialog.Builder(this)
                .setTitle("Transmit learned IR command?")
                .setMessage("Send this capture ONCE through the Ocrustar dongle, assuming a 38 kHz carrier. Aim it at a device you own. The carrier frequency was not measured.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Transmit once", (dialog, choice) -> startReplay())
                .show();
        });

        stopButton = button("STOP learning", root);
        stopButton.setEnabled(false);
        stopButton.setOnClickListener(view -> {
            stopRequested.set(true);
            log("STOP requested; worker will send FD FD FD FD, then close.");
        });

        Button copyButton = button("Copy full report", root);
        copyButton.setOnClickListener(view -> {
            ClipboardManager clipboard =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(
                    ClipData.newPlainText("Ocrustar USB capture", report.toString()));
            Toast.makeText(this, "Diagnostic report copied", Toast.LENGTH_SHORT).show();
        });

        ScrollView scroller = new ScrollView(this);
        reportView = new TextView(this);
        reportView.setTextSize(12);
        reportView.setTypeface(Typeface.MONOSPACE);
        reportView.setTextIsSelectable(true);
        scroller.addView(reportView);
        root.addView(scroller, new LinearLayout.LayoutParams(0, 0, 1));
        // Replace the zero-width temporary layout with full-width scroll area.
        LinearLayout.LayoutParams fill = (LinearLayout.LayoutParams) scroller.getLayoutParams();
        fill.width = LinearLayout.LayoutParams.MATCH_PARENT;
        scroller.setLayoutParams(fill);
    }

    private int dp(int value) {
        return (int) (getResources().getDisplayMetrics().density * value + 0.5f);
    }

    private void resetReport() {
        report.setLength(0);
        traceStart = SystemClock.elapsedRealtime();
        reportView.setText("");
    }

    private void log(String message) {
        runOnUiThread(() -> {
            if (report.length() >= MAX_LOG_CHARS) return;
            double seconds = (SystemClock.elapsedRealtime() - traceStart) / 1000.0;
            String line = String.format(Locale.US, "[%7.3f] %s\n", seconds, message);
            if (report.length() + line.length() > MAX_LOG_CHARS) {
                line = "[LOG LIMIT REACHED — stop and copy the report]\n";
            }
            report.append(line);
            reportView.append(line);
        });
    }

    private UsbDevice findTarget() {
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            if (device.getVendorId() == VENDOR_ID &&
                    device.getProductId() == PRODUCT_ID) {
                return device;
            }
        }
        return null;
    }

    private Port findPort(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            UsbEndpoint in = null;
            UsbEndpoint out = null;
            for (int j = 0; j < intf.getEndpointCount(); j++) {
                UsbEndpoint ep = intf.getEndpoint(j);
                if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                if (ep.getAddress() == BULK_IN) in = ep;
                if (ep.getAddress() == BULK_OUT) out = ep;
            }
            if (in != null && out != null) return new Port(intf, in, out);
        }
        return null;
    }

    private void scan() {
        if (busy.get() || permissionPending) {
            log("A test is in progress; scan after it ends.");
            return;
        }
        UsbDevice target = findTarget();
        log("USB device count: " + usbManager.getDeviceList().size());
        if (target == null) {
            log("No 045C:0132 dongle present. Insert Ocrustar, then tap Scan.");
            return;
        }
        log(String.format(Locale.US, "Found %04X:%04X, device ID %d, interfaces %d",
                target.getVendorId(), target.getProductId(),
                target.getDeviceId(), target.getInterfaceCount()));
        log("USB permission: " + (usbManager.hasPermission(target) ? "granted" : "not granted"));
        Port port = findPort(target);
        if (port == null) {
            log("Expected bulk IN 0x82 and OUT 0x02 NOT found. No commands will be sent.");
        } else {
            log("Matching bulk interface " + port.usbInterface.getId() +
                    " found, IN 0x82 / OUT 0x02.");
        }
    }

    private void startTest(boolean withHandshake) {
        if (busy.get() || permissionPending) {
            log("Another operation is already in progress.");
            return;
        }
        UsbDevice device = findTarget();
        if (device == null) {
            log("No matching Ocrustar inserted.");
            return;
        }
        if (findPort(device) == null) {
            log("The expected bulk endpoints are missing; refusing USB commands.");
            return;
        }
        if (!usbManager.hasPermission(device)) {
            pendingHandshake = withHandshake;
            permissionPending = true;
            log("Requesting Android USB permission.");
            Intent intent = new Intent(PERMISSION_ACTION).setPackage(getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags |= PendingIntent.FLAG_MUTABLE;
            }
            PendingIntent reply = PendingIntent.getBroadcast(this, 0, intent, flags);
            usbManager.requestPermission(device, reply);
            return;
        }
        runTest(device, withHandshake);
    }

    private void setIdle() {
        busy.set(false);
        withHandshakeButton.setEnabled(true);
        withoutHandshakeButton.setEnabled(true);
        stopButton.setEnabled(false);
        replayButton.setEnabled(false); // Deliberately disabled after receiver failures.
    }

    private void runTest(UsbDevice device, boolean handshake) {
        if (!busy.compareAndSet(false, true)) return;
        stopRequested.set(false);
        lastTimings = null;
        replayButton.setEnabled(false);
        withHandshakeButton.setEnabled(false);
        withoutHandshakeButton.setEnabled(false);
        stopButton.setEnabled(true);

        resetReport();
        log("Capture mode: " + (handshake ? "FC/FA handshake then FE capture" :
                "FE capture without handshake"));
        log("USB target 045C:0132, 15-second maximum, raw RX packets preserved.");

        Thread worker = new Thread(() -> capture(device, handshake), "OcrustarUsbCapture");
        worker.start();
    }

    private static String hex(byte[] bytes, int length) {
        StringBuilder s = new StringBuilder(length * 3);
        for (int i = 0; i < length; i++) {
            if (i != 0) s.append(' ');
            s.append(String.format(Locale.US, "%02X", bytes[i] & 0xFF));
        }
        return s.toString();
    }

    private void send(UsbDeviceConnection connection, UsbEndpoint endpoint,
                      byte[] payload, String label) {
        int sent = connection.bulkTransfer(endpoint, payload, payload.length, 1500);
        log("TX " + label + ": " + hex(payload, payload.length) + " (" + sent + " bytes)");
        if (sent != payload.length) {
            throw new IllegalStateException("USB write incomplete: " +
                    sent + "/" + payload.length);
        }
    }

    private byte[] read(UsbDeviceConnection connection, UsbEndpoint endpoint,
                        int timeoutMs, String label) {
        byte[] buffer = new byte[1024];
        int got = connection.bulkTransfer(endpoint, buffer, buffer.length, timeoutMs);
        if (got <= 0) return null;
        byte[] response = new byte[got];
        System.arraycopy(buffer, 0, response, 0, got);
        log("RX " + label + " (" + got + " bytes): " + hex(response, response.length));
        return response;
    }

    private static boolean prefix(byte[] bytes, int value) {
        if (bytes == null || bytes.length < 4) return false;
        for (int i = 0; i < 4; i++) {
            if ((bytes[i] & 0xFF) != value) return false;
        }
        return true;
    }

    private void describe(byte[] packet) {
        if (packet == null) return;
        if (prefix(packet, 0xFE)) {
            if (packet.length >= 6) {
                int be = ((packet[4] & 0xFF) << 8) | (packet[5] & 0xFF);
                int le = ((packet[5] & 0xFF) << 8) | (packet[4] & 0xFF);
                log("POSSIBLE IR capture header: payload length BE=" + be +
                        " / LE=" + le + "; raw frame retained (not yet decoded).");
            } else {
                log("IR capture prefix FE FE FE FE observed.");
            }
        } else if (prefix(packet, 0xFA)) {
            log("FA firmware/ack prefix received.");
        } else if (prefix(packet, 0xFC)) {
            log("FC identity prefix received.");
        }
    }

    private void capture(UsbDevice device, boolean handshake) {
        UsbDeviceConnection connection = null;
        Port port = null;
        boolean claimed = false;
        boolean learningStarted = false;
        int rxPackets = 0;
        int rxBytes = 0;
        try {
            port = findPort(device);
            if (port == null) {
                log("Expected bulk interface disappeared. Aborting.");
                return;
            }
            connection = usbManager.openDevice(device);
            if (connection == null) {
                log("openDevice failed — Android USB permission or connection issue.");
                return;
            }
            claimed = connection.claimInterface(port.usbInterface, false);
            if (!claimed) {
                log("Could not claim bulk interface without forcing. Aborting.");
                return;
            }
            log("USB bulk interface claimed without detaching other drivers.");

            if (handshake && !stopRequested.get()) {
                send(connection, port.output, HELLO, "HELLO");
                byte[] greeting = read(connection, port.input, 1500, "HELLO reply");
                if (greeting != null) describe(greeting);
                if (prefix(greeting, 0xFC) || prefix(greeting, 0xFA)) {
                    send(connection, port.output, HELLO_ACK, "HELLO ACK");
                } else {
                    log("No recognized FC/FA greeting; skipping ACK (capture will still be tested).");
                }
            }

            if (stopRequested.get()) {
                log("Stopped before learning started.");
                return;
            }

            // Mark true before sending, to guarantee STOP in finally even if
            // the USB call returns an error after the command reaches hardware.
            learningStarted = true;
            send(connection, port.output, LEARN_START, "LEARN START");
            log("Point another IR remote toward the dongle and press a button.");

            OcrustarCaptureAssembler assembler = new OcrustarCaptureAssembler();
            long deadline = SystemClock.elapsedRealtime() + CAPTURE_MS;
            int firstExpected = -1;
            while (!stopRequested.get() && SystemClock.elapsedRealtime() < deadline) {
                long remaining = deadline - SystemClock.elapsedRealtime();
                byte[] packet = read(connection, port.input,
                        (int) Math.min(READ_TIMEOUT_MS, Math.max(1L, remaining)),
                        "LEARN");
                if (packet != null) {
                    rxPackets++;
                    rxBytes += packet.length;
                    describe(packet);
                    assembler.accept(packet);
                    if (firstExpected < 0 && assembler.hasHeader()) {
                        firstExpected = assembler.getExpectedLength();
                        log("Detected complete FE header: big-endian payload length = "
                                + firstExpected + " bytes.");
                    }
                    if (assembler.isComplete()) {
                        log("COMPLETE IR PAYLOAD: " + assembler.getReceivedLength()
                                + " / " + assembler.getExpectedLength() + " bytes.");
                        byte[] raw = assembler.getPayload();
                        log("Payload first 32 bytes: "
                                + hex(OcrustarCaptureAssembler.firstBytes(raw, 32),
                                Math.min(32, raw.length)));
                        log("Payload last 32 bytes: "
                                + hex(OcrustarCaptureAssembler.lastBytes(raw, 32),
                                Math.min(32, raw.length)));
                        log(OcrustarCaptureAssembler.timingSummary(raw));
                        try {
                            int[] valid = OcrustarD552Encoder.decodeStrict(raw);
                            lastTimings = valid;
                            log("REPLAY READY: " + valid.length
                                + " strictly validated mark/space durations.");
                        } catch (IllegalArgumentException reject) {
                            log("REPLAY DISABLED: " + reject.getMessage()
                                + " Learn again with a short, clean button press.");
                        }
                        log("Captured USB data successfully; stopping learning early.");
                        break;
                    }
                }
            }
            log("Learning window ended. Received " + rxPackets +
                    " USB packet(s), " + rxBytes + " byte(s).");
            log("Reassembly: header=" + assembler.hasHeader()
                    + ", expected=" + assembler.getExpectedLength()
                    + ", collected=" + assembler.getReceivedLength()
                    + ", complete=" + assembler.isComplete()
                    + ", ignored prefix=" + assembler.getIgnoredBeforeHeader()
                    + ", extra bytes=" + assembler.getTrailingBytes()
                    + ", payload chunks=" + assembler.getDataChunks() + ".");
            if (!assembler.isComplete()) {
                log("No complete payload yet. This does NOT mean the receiver is broken.");
            }
        } catch (Exception error) {
            log("USB exception " + error.getClass().getSimpleName() +
                    ": " + error.getMessage());
        } finally {
            if (connection != null) {
                if (learningStarted && port != null) {
                    try {
                        send(connection, port.output, LEARN_STOP, "LEARN STOP");
                    } catch (Exception stopError) {
                        log("STOP write problem: " + stopError.getMessage());
                    }
                }
                if (claimed && port != null) {
                    try {
                        connection.releaseInterface(port.usbInterface);
                    } catch (Exception ignored) {
                        log("releaseInterface raised an error.");
                    }
                }
                connection.close();
            }
            log("USB session closed; no commands stored on the dongle.");
            runOnUiThread(this::setIdle);
        }
    }


    /**
     * Controlled single playback of the last strictly valid capture.
     * Requires successful FC 70 01 handshake; no learning commands sent.
     */
    private void startReplay() {
        if (true) {
            log("Replay suspended pending receiver hardware investigation.");
            return;
        }
        if (busy.get() || permissionPending || lastTimings == null) {
            log("Replay not ready; capture a valid signal first.");
            return;
        }
        UsbDevice device = findTarget();
        if (device == null || findPort(device) == null) {
            log("Replay blocked: matching 045C:0132 bulk device is missing.");
            return;
        }
        if (!usbManager.hasPermission(device)) {
            log("Replay blocked: USB permission missing. Run a capture and approve permission.");
            return;
        }
        if (!busy.compareAndSet(false, true)) return;
        final int[] copy = lastTimings.clone();
        withHandshakeButton.setEnabled(false);
        withoutHandshakeButton.setEnabled(false);
        replayButton.setEnabled(false);
        stopButton.setEnabled(false);
        log("REPLAY requested explicitly: one D552 command at assumed 38000 Hz.");
        new Thread(() -> replayOnUsb(device, copy), "OcrustarD552Replay").start();
    }

    private void replayOnUsb(UsbDevice device, int[] timings) {
        UsbDeviceConnection connection = null;
        Port port = null;
        boolean claimed = false;
        try {
            // Encode and validate BEFORE any USB transmission.
            List<byte[]> frames = OcrustarD552Encoder.encode(
                    timings, OcrustarD552Encoder.DEFAULT_CARRIER_HZ);
            if (frames.isEmpty() || frames.size() > 64) {
                log("Replay aborted: unexpected frame count.");
                return;
            }
            int totalBytes = 0;
            for (byte[] frame : frames) {
                if (frame.length == 0 || frame.length > 63) {
                    log("Replay aborted: invalid USB frame length.");
                    return;
                }
                totalBytes += frame.length;
            }
            log("D552 encoded command: " + frames.size()
                    + " frame(s), " + totalBytes + " USB bytes.");
            port = findPort(device);
            if (port == null) {
                log("Replay blocked: bulk interface not found.");
                return;
            }
            connection = usbManager.openDevice(device);
            if (connection == null) {
                log("Replay blocked: openDevice failed.");
                return;
            }
            claimed = connection.claimInterface(port.usbInterface, false);
            if (!claimed) {
                log("Replay blocked: USB interface busy.");
                return;
            }
            send(connection, port.output, HELLO, "REPLAY HELLO");
            byte[] greeting = read(connection, port.input, 1500, "REPLAY HELLO reply");
            if (!prefix(greeting, 0xFC) ||
                greeting.length < 6 ||
                (greeting[4] & 0xFF) != 0x70 ||
                (greeting[5] & 0xFF) != 0x01) {
                log("REPLAY BLOCKED: firmware identity not FC FC FC FC 70 01.");
                return;
            }
            send(connection, port.output, HELLO_ACK, "REPLAY HELLO ACK");
            for (int i = 0; i < frames.size(); i++) {
                byte[] frame = frames.get(i);
                int sent = connection.bulkTransfer(port.output, frame, frame.length, 1500);
                if (sent != frame.length) {
                    throw new IllegalStateException("Replay packet " + (i + 1)
                            + " incomplete (" + sent + "/" + frame.length + ")");
                }
                log("TX D552 frame " + (i + 1) + "/" + frames.size()
                        + ", bytes=" + frame.length);
                SystemClock.sleep(2);
            }
            byte[] acknowledgement = read(connection, port.input, 300, "REPLAY ACK");
            if (prefix(acknowledgement, 0xFF)) {
                log("Firmware acknowledged FF FF FF FF; confirm actual device reaction.");
            } else {
                log("No recognized FF ACK. Observe target device; transmission unverified.");
            }
            log("Single replay attempt finished. No command stored in the dongle.");
        } catch (Exception error) {
            log("REPLAY FAILED: " + error.getClass().getSimpleName()
                    + ": " + error.getMessage());
        } finally {
            if (connection != null) {
                if (claimed && port != null) {
                    try { connection.releaseInterface(port.usbInterface); }
                    catch (Exception ignored) { log("Replay interface release error."); }
                }
                connection.close();
            }
            log("Replay USB session closed.");
            runOnUiThread(this::setIdle);
        }
    }

    @Override
    protected void onDestroy() {
        stopRequested.set(true);
        try {
            unregisterReceiver(permissionReceiver);
        } catch (IllegalArgumentException ignored) {
            // Receiver already removed.
        }
        super.onDestroy();
    }
}
