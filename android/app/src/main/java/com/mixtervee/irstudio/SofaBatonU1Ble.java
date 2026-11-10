package com.mixtervee.irstudio;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/** Persistent Android BLE FFF0 session for the observed SofaBaton U1.
 *
 * The scanner never shows unrelated BLE devices. GATT service and 0x02
 * identity replies must both verify before the UI can report CONNECTED.
 * Learning is serialized with read/backup and uses the EXACT Windows v0.3.3
 * 0x04 start -> ready -> 0x06 completion -> 0x0D data -> 0x05 finish.
 * There are NO firmware, profile, write-IR or U1-restore commands here.
 */
@SuppressWarnings("deprecation")
public final class SofaBatonU1Ble {
    public interface Listener {
        void status(String message, boolean connected);
        void chooseCandidate(List<BluetoothDevice> devices, List<String> labels);
        void learnReady();
        void learned(U1Protocol.Signal signal, File backup);
        void error(String detail);
    }

    private static final Pattern U1_NAME =
            Pattern.compile("(?i)SB[0-9A-F]{6,20}");
    private static final UUID SERVICE = UUID.fromString(U1Protocol.SERVICE);
    private static final UUID NOTIFY = UUID.fromString(U1Protocol.NOTIFY);
    private static final UUID WRITE = UUID.fromString(U1Protocol.WRITE);
    private static final UUID CCCD = UUID.fromString(U1Protocol.CCCD);
    private final Context appContext;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "SofaBatonU1-Gatt-Worker");
        thread.setDaemon(true);
        return thread;
    });
    private final LinkedBlockingQueue<byte[]> inbox = new LinkedBlockingQueue<>();
    private final Map<String, BluetoothDevice> discovered = new LinkedHashMap<>();
    private final Map<String, String> labels = new LinkedHashMap<>();
    private final Object scanLock = new Object();
    private final AtomicBoolean learning = new AtomicBoolean();
    private volatile BluetoothGatt gatt;
    private volatile BluetoothGattCharacteristic writeCharacteristic;
    private volatile boolean gattConnected;
    private volatile boolean identityVerified;
    private volatile boolean closed;
    private volatile BluetoothLeScanner scanner;
    private volatile ScanCallback scanCallback;
    private volatile Runnable stopScanning;
    private volatile String deviceName = "";
    private int scanGeneration;

    public SofaBatonU1Ble(Context context, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
    }

    public boolean isConnected() { return identityVerified && gattConnected; }
    public boolean isLearning() { return learning.get(); }
    public String deviceName() { return deviceName; }

    private void state(String description) {
        main.post(() -> {
            if (!closed) listener.status(description, isConnected());
        });
    }

    private void error(String message) {
        main.post(() -> {
            if (!closed) listener.error(message);
        });
    }

    private BluetoothAdapter adapter() {
        BluetoothManager manager = (BluetoothManager) appContext.getSystemService(
                Context.BLUETOOTH_SERVICE);
        return manager == null ? null : manager.getAdapter();
    }

    /** Scan ONLY unique SB-prefixed U1 candidates; UI never lists other radios. */
    public void scan() {
        if (closed) return;
        if (isConnected()) {
            state("CONNECTED — " + deviceName);
            return;
        }
        stopScan();
        BluetoothAdapter radio = adapter();
        if (radio == null || !radio.isEnabled()) {
            error("Enable Bluetooth in Android settings, then scan again.");
            return;
        }
        BluetoothLeScanner found = radio.getBluetoothLeScanner();
        if (found == null) {
            error("Android BLE scanner is unavailable.");
            return;
        }
        synchronized (scanLock) {
            discovered.clear();
            labels.clear();
            scanGeneration++;
        }
        int currentGeneration = scanGeneration;
        ScanCallback callback = new ScanCallback() {
            @Override public void onScanResult(int type, ScanResult result) {
                addCandidate(result);
            }
            @Override public void onBatchScanResults(List<ScanResult> results) {
                for (ScanResult result : results) addCandidate(result);
            }
            @Override public void onScanFailed(int code) {
                main.post(() -> {
                    if (currentGeneration != scanGeneration || closed) return;
                    stopScan();
                    error("U1 Bluetooth scan failed (Android error " + code + ").");
                });
            }
        };
        scanner = found;
        scanCallback = callback;
        state("SEARCHING FOR SOFABATON U1...");
        try {
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            found.startScan(null, settings, callback);
            stopScanning = () -> {
                if (currentGeneration != scanGeneration || closed) return;
                stopScan();
                finishScan();
            };
            main.postDelayed(stopScanning, 7000);
        } catch (SecurityException failure) {
            stopScan();
            error("Bluetooth scan permission missing: " + failure.getMessage());
        } catch (RuntimeException failure) {
            stopScan();
            error("Unable to start U1 scan: " + failure.getMessage());
        }
    }

    private void addCandidate(ScanResult result) {
        if (result == null || result.getDevice() == null) return;
        ScanRecord record = result.getScanRecord();
        String name = record == null ? null : record.getDeviceName();
        if (name == null || !U1_NAME.matcher(name.trim()).matches()) return;
        List<ParcelUuid> advertised = record.getServiceUuids();
        if (advertised != null && !advertised.isEmpty()) {
            boolean correctService = false;
            for (ParcelUuid uuid : advertised) {
                if (SERVICE.equals(uuid.getUuid())) correctService = true;
            }
            if (!correctService) return;
        }
        BluetoothDevice device = result.getDevice();
        synchronized (scanLock) {
            discovered.put(device.getAddress(), device);
            labels.put(device.getAddress(), name.trim());
        }
    }

    private void stopScan() {
        if (stopScanning != null) main.removeCallbacks(stopScanning);
        stopScanning = null;
        BluetoothLeScanner localScanner = scanner;
        ScanCallback callback = scanCallback;
        scanner = null;
        scanCallback = null;
        scanGeneration++;
        if (localScanner != null && callback != null) {
            try {
                localScanner.stopScan(callback);
            } catch (RuntimeException ignored) {
                // Android may already have cancelled scan on Bluetooth reset.
            }
        }
    }

    private void finishScan() {
        final List<BluetoothDevice> options;
        final List<String> names;
        synchronized (scanLock) {
            options = new ArrayList<>(discovered.values());
            names = new ArrayList<>(labels.values());
        }
        if (options.size() == 1) {
            connect(options.get(0), names.get(0));
        } else if (options.size() > 1) {
            state("MULTIPLE SOFABATON U1 REMOTES FOUND");
            main.post(() -> listener.chooseCandidate(options, names));
        } else {
            state("NO U1 FOUND — Check Pairing, then Scan again");
        }
    }

    public void connect(BluetoothDevice device, String name) {
        if (closed || device == null) return;
        if (isConnected()) {
            state("CONNECTED — " + deviceName);
            return;
        }
        stopScan();
        disconnect();
        deviceName = name;
        state("CONNECTING TO SOFABATON U1...");
        try {
            gatt = device.connectGatt(appContext, false, callback,
                    BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) throw new IllegalStateException("Android did not open BLE");
        } catch (RuntimeException failure) {
            error("Cannot connect to U1: " + failure.getMessage());
        }
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(
                BluetoothGatt connection, int status, int newState) {
            if (connection != gatt || closed) return;
            if (status == BluetoothGatt.GATT_SUCCESS
                    && newState == BluetoothProfile.STATE_CONNECTED) {
                gattConnected = true;
                state("VERIFYING SOFABATON U1...");
                if (!connection.discoverServices())
                    failConnection("Cannot discover U1 Bluetooth services");
            } else {
                boolean wasConnected = identityVerified;
                gattConnected = false;
                identityVerified = false;
                inbox.offer(new byte[0]); // wake any waiting read/learn
                if (wasConnected) state("U1 DISCONNECTED — Scan again");
                else if (newState != BluetoothProfile.STATE_DISCONNECTED
                        || status != BluetoothGatt.GATT_SUCCESS)
                    failConnection("U1 Bluetooth connection failed (GATT " + status + ")");
                try { connection.close(); } catch (RuntimeException ignored) {}
            }
        }

        @Override public void onServicesDiscovered(
                BluetoothGatt connection, int status) {
            if (connection != gatt || closed) return;
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failConnection("Unable to verify U1 Bluetooth services");
                return;
            }
            BluetoothGattService service = connection.getService(SERVICE);
            BluetoothGattCharacteristic notify =
                    service == null ? null : service.getCharacteristic(NOTIFY);
            BluetoothGattCharacteristic write =
                    service == null ? null : service.getCharacteristic(WRITE);
            if (notify == null || write == null
                    || (notify.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0
                    || (write.getProperties() & (BluetoothGattCharacteristic.PROPERTY_WRITE
                    | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) == 0) {
                failConnection("Device does not expose U1 FFF0/FFF1/FFF2 services");
                return;
            }
            writeCharacteristic = write;
            if (!connection.setCharacteristicNotification(notify, true)) {
                failConnection("Cannot subscribe to U1 notifications");
                return;
            }
            BluetoothGattDescriptor cccd = notify.getDescriptor(CCCD);
            if (cccd == null) {
                failConnection("U1 notification descriptor not found");
                return;
            }
            cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            if (!connection.writeDescriptor(cccd)) {
                failConnection("Cannot enable U1 notifications");
            }
        }

        @Override public void onDescriptorWrite(BluetoothGatt connection,
                                                 BluetoothGattDescriptor desc, int status) {
            if (connection != gatt || closed) return;
            if (status != BluetoothGatt.GATT_SUCCESS || !CCCD.equals(desc.getUuid())) {
                failConnection("U1 notifications were not enabled");
                return;
            }
            worker.execute(() -> {
                try {
                    inbox.clear();
                    send(U1Protocol.HELLO, new byte[0]);
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
                    while (System.nanoTime() < deadline) {
                        U1Protocol.Frame message = receiveUntil(deadline);
                        if (message.command == U1Protocol.BANNER) {
                            U1Protocol.verifyBanner(message);
                            if (!gattConnected)
                                throw new IllegalStateException("U1 disconnected during identity check");
                            identityVerified = true;
                            state("CONNECTED — " + deviceName);
                            return;
                        }
                    }
                    throw new IllegalStateException("U1 identification timed out");
                } catch (Exception failure) {
                    failConnection("U1 identity check failed: " + failure.getMessage());
                }
            });
        }

        @Override public void onCharacteristicChanged(
                BluetoothGatt connection, BluetoothGattCharacteristic character) {
            if (connection == gatt && NOTIFY.equals(character.getUuid())) {
                byte[] value = character.getValue();
                if (value != null) inbox.offer(Arrays.copyOf(value, value.length));
            }
        }

        @Override public void onCharacteristicChanged(
                BluetoothGatt connection, BluetoothGattCharacteristic character,
                byte[] value) {
            if (connection == gatt && NOTIFY.equals(character.getUuid()) && value != null)
                inbox.offer(Arrays.copyOf(value, value.length));
        }
    };

    private void failConnection(String reason) {
        gattConnected = false;
        identityVerified = false;
        state("CONNECTION FAILED — Scan again");
        error(reason);
        main.post(this::disconnect);
    }

    private void send(int command, byte[] payload) throws Exception {
        BluetoothGatt conn = gatt;
        BluetoothGattCharacteristic characteristic = writeCharacteristic;
        if (closed || !gattConnected || conn == null || characteristic == null)
            throw new IllegalStateException("SofaBaton U1 is not connected");
        int properties = characteristic.getProperties();
        characteristic.setWriteType(
                (properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        characteristic.setValue(U1Protocol.encode(command, payload));
        if (!conn.writeCharacteristic(characteristic))
            throw new IllegalStateException("U1 BLE write was rejected");
        // Allow Android GATT to queue the short, observed single-frame write.
        Thread.sleep(45);
    }

    private U1Protocol.Frame receiveUntil(long deadline) throws Exception {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new IllegalStateException("Timed out waiting for U1");
        byte[] raw = inbox.poll(remaining, TimeUnit.NANOSECONDS);
        if (raw == null)
            throw new IllegalStateException("Timed out waiting for U1 response");
        if (!gattConnected)
            throw new IllegalStateException("U1 disconnected while waiting for IR");
        return U1Protocol.decode(raw);
    }

    private U1Protocol.Signal readSlot(int slot) throws Exception {
        if (!isConnected()) throw new IllegalStateException("U1 not verified/connected");
        if (slot < 1 || slot > 8) throw new IllegalArgumentException("Invalid stored U1 slot");
        inbox.clear();
        send(U1Protocol.READ_IR, new byte[]{(byte) slot, (byte) 0xFF});
        U1Protocol.Assembler assembler = new U1Protocol.Assembler(slot);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime() < deadline) {
            U1Protocol.Frame frame = receiveUntil(deadline);
            if (frame.command == U1Protocol.IR_DATA) {
                U1Protocol.Signal result = assembler.add(frame.payload);
                if (result != null) return result;
            }
        }
        throw new IllegalStateException("No complete IR from U1 slot " + slot);
    }

    /** Back up slot 8 to persistent local JSON BEFORE any learning write. */
    public void learn(File backupFolder) {
        if (!isConnected()) {
            error("Connect and verify your U1 before learning.");
            return;
        }
        if (!learning.compareAndSet(false, true)) {
            error("A U1 learning operation is already running.");
            return;
        }
        worker.execute(() -> {
            boolean started = false;
            File backup = null;
            U1Protocol.Signal captured = null;
            String failure = null;
            try {
                state("BACKING UP STORED U1 IR...");
                U1Protocol.Signal previous = readSlot(U1Protocol.SLOT);
                backup = U1BackupStore.save(backupFolder, previous);
                // Explicit conversion ensures capture can be recovered in IRplus.
                U1Protocol.pronto(U1BackupStore.read(backup));
                inbox.clear();
                state("LEARNING — Wait for U1 READY...");
                send(U1Protocol.LEARN_START, U1Protocol.START_PAYLOAD);
                started = true;
                U1Protocol.Assembler assembly =
                        new U1Protocol.Assembler(U1Protocol.SLOT);
                boolean ready = false;
                boolean completed = false;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
                while (System.nanoTime() < deadline) {
                    U1Protocol.Frame frame = receiveUntil(deadline);
                    if (frame.command == U1Protocol.LEARN_READY
                            && frame.payload.length == 0) {
                        ready = true;
                        main.post(() -> listener.learnReady());
                    } else if (frame.command == U1Protocol.LEARN_CAPTURED
                            && Arrays.equals(frame.payload, new byte[]{0})) {
                        if (!ready)
                            throw new IllegalStateException("U1 reported capture without READY");
                        completed = true;
                    } else if (frame.command == U1Protocol.IR_DATA && completed) {
                        captured = assembly.add(frame.payload);
                        if (captured != null) {
                            // Verify exportability BEFORE reporting learned.
                            U1Protocol.pronto(captured);
                            break;
                        }
                    }
                }
                if (captured == null) {
                    throw new IllegalStateException("No complete verified IR captured. "
                            + "Check the original remote's button press.");
                }
            } catch (Exception issue) {
                failure = issue.getMessage() == null
                        ? issue.getClass().getSimpleName() : issue.getMessage();
            } finally {
                if (started && gattConnected) {
                    try {
                        send(U1Protocol.LEARN_FINISH, U1Protocol.FINISH_PAYLOAD);
                    } catch (Exception finish) {
                        if (failure == null) failure = "U1 finish command failed: "
                                + finish.getMessage();
                    }
                }
                learning.set(false);
            }
            if (failure != null) {
                String detail = failure
                        + (backup == null
                        ? ". Learning was NOT started if preflight backup failed."
                        : ". Previous slot-8 IR saved in " + backup.getName()
                        + " (raw waveform; not a U1 programming restore).");
                error(detail);
            } else {
                final U1Protocol.Signal result = captured;
                final File prior = backup;
                state("CONNECTED — " + deviceName);
                main.post(() -> listener.learned(result, prior));
            }
        });
    }

    public void disconnect() {
        stopScan();
        identityVerified = false;
        gattConnected = false;
        writeCharacteristic = null;
        inbox.offer(new byte[0]);
        BluetoothGatt conn = gatt;
        gatt = null;
        if (conn != null) {
            try { conn.disconnect(); } catch (RuntimeException ignored) {}
            try { conn.close(); } catch (RuntimeException ignored) {}
        }
    }

    public void close() {
        closed = true;
        disconnect();
        worker.shutdownNow();
    }
}
