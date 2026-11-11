package com.mixtervee.irstudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.content.DialogInterface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.ConsumerIrManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/** Learn, organize, replay and export raw Pronto IR commands on Android. */
public class MainActivity extends Activity {
    private static final int IMPORT_DEVICE = 1001;
    private static final int EXPORT_DEVICE = 1002;
    private static final int BACKUP_LIBRARY = 1003;
    private static final int RESTORE_LIBRARY = 1004;
    private static final int EXPORT_U1_BACKUP = 1005;
    private static final int IMPORT_U1_BACKUP = 1006;
    private static final int BLE_PERMISSION_REQUEST = 2001;
    private static final int DARK = Color.rgb(9, 18, 34);
    private static final int SURFACE = Color.rgb(24, 40, 64);
    private static final int WHITE = Color.rgb(244, 247, 252);
    private static final int MUTED = Color.rgb(183, 201, 225);
    private static final int BLUE = Color.rgb(23, 103, 190);
    private static final int ORANGE = Color.rgb(241, 147, 29);
    private static final int TEAL = Color.rgb(12, 164, 146);
    private static final int PURPLE = Color.rgb(83, 81, 167);

    private final List<IrplusCodec.Remote> remotes = new ArrayList<>();
    private ConsumerIrManager irManager;
    private LinearLayout remoteLayout;
    private TextView status;
    private Spinner deviceSpinner;
    private Button learnButton;
    private Button u1ConnectionButton;
    private Button u1SourceButton;
    private Button harmonySourceButton;
    private TextView u1Status;
    private int selectedLearner = LearnerSource.SOFABATON_U1;
    private String lastU1Status = "NOT CONNECTED — Tap Connect U1";
    private SofaBatonU1Ble u1Ble;
    private IrplusCodec.Remote u1TargetRemote;
    private IrplusCodec.Item u1TargetButton;
    private File pendingU1BackupExport;
    private boolean u1LearningConsent;
    private int selectedDevice = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        selectedLearner = LearnerSource.normalize(
                getPreferences(MODE_PRIVATE).getInt("selected_learner",
                        LearnerSource.SOFABATON_U1));
        irManager = (ConsumerIrManager) getSystemService(CONSUMER_IR_SERVICE);
        u1Ble = new SofaBatonU1Ble(this, new SofaBatonU1Ble.Listener() {
            @Override public void status(String message, boolean connected) {
                if (isFinishing() || isDestroyed()) return;
                lastU1Status = message;
                // U1 state changes must not hijack a selected Harmony learner.
                updateLearnerPanel();
            }

            @Override public void chooseCandidate(
                    List<BluetoothDevice> choices, List<String> names) {
                if (isFinishing() || isDestroyed()) return;
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Choose your SofaBaton U1")
                        .setItems(names.toArray(new String[0]), (d, which) ->
                                u1Ble.connect(choices.get(which), names.get(which)))
                        .setNegativeButton("Cancel", null)
                        .show();
            }

            @Override public void learnReady() {
                if (isFinishing() || isDestroyed()) return;
                if (learnButton != null)
                    learnButton.setText("READY — Press source remote button");
                setStatus("U1 READY. Press and release the original IR remote's button.");
            }

            @Override public void learned(U1Protocol.Signal signal, File previousBackup) {
                if (isFinishing() || isDestroyed()) return;
                restoreLearnButton();
                try {
                    if (u1TargetRemote == null || u1TargetButton == null) {
                        throw new IllegalStateException("No destination button selected");
                    }
                    saveExistingCapture(
                            u1TargetRemote, u1TargetButton, U1Protocol.pronto(signal));
                    // Always mention where the previous IR waveform was backed up.
                    toast("U1 previous slot-8 waveform backed up: "
                            + previousBackup.getName());
                } catch (Exception failure) {
                    toast("U1 capture could not be saved: " + failure.getMessage());
                } finally {
                    u1TargetRemote = null;
                    u1TargetButton = null;
                }
            }

            @Override public void error(String reason) {
                if (isFinishing() || isDestroyed()) return;
                restoreLearnButton();
                setStatus("SofaBaton U1: " + reason);
                toast("U1: " + reason);
            }
        });
        readSavedRemotes();
        makeUi();
        refreshDevices();
        String availability = irManager != null && irManager.hasIrEmitter()
                ? "Android IR emitter detected. Import a .irplus remote and select a button."
                : "Android's public IR emitter API is unavailable. Import/export still works; transmission requires a supported phone.";
        setStatus(availability);
    }

    @Override protected void onResume() {
        super.onResume();
        updateLearnerPanel();
    }

    @Override protected void onDestroy() {
        if (u1Ble != null) u1Ble.close();
        super.onDestroy();
    }

    private int dp(float value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private GradientDrawable background(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    /** Compact, full-page-scrolling home; learning protocols are unchanged. */
    private void makeUi() {
        ScrollView page = new ScrollView(this);
        page.setFillViewport(true);
        page.setBackgroundColor(DARK);
        page.setVerticalScrollBarEnabled(true);
        setContentView(page);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(11), dp(14), dp(28));
        page.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ir_remote_studio);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setContentDescription("IR Remote Studio");
        header.addView(logo, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(11), 0, 0, 0);
        titles.addView(text("IR Remote Studio", 21, WHITE, true));
        titles.addView(text("Android " + BuildConfig.VERSION_NAME +
                "  ·  Learn  ·  Organize", 11, MUTED, false));
        header.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(header);

        // Connection remains prominent; the old side-by-side backup button
        // moved to Tools, preventing clipped labels on smaller phones.
        LinearLayout connection = new LinearLayout(this);
        connection.setOrientation(LinearLayout.VERTICAL);
        connection.setPadding(dp(13), dp(10), dp(13), dp(10));
        connection.setBackground(background(SURFACE, 12));
        LinearLayout.LayoutParams connectionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        connectionParams.setMargins(0, dp(11), 0, dp(9));
        root.addView(connection, connectionParams);
        LinearLayout connectionRow = new LinearLayout(this);
        connectionRow.setOrientation(LinearLayout.HORIZONTAL);
        connectionRow.setGravity(Gravity.CENTER_VERTICAL);
        connectionRow.addView(text("SOFABATON U1", 13, WHITE, true),
                new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        u1ConnectionButton = actionButton("Connect U1", TEAL);
        u1ConnectionButton.setTextSize(13);
        u1ConnectionButton.setOnClickListener(v -> openU1Connection());
        connectionRow.addView(u1ConnectionButton,
                new LinearLayout.LayoutParams(dp(122), dp(42)));
        connection.addView(connectionRow);
        u1Status = text("NOT CONNECTED — Tap Connect U1", 12, MUTED, false);
        u1Status.setPadding(dp(1), dp(5), dp(1), 0);
        u1Status.setMaxLines(2);
        connection.addView(u1Status);

        learnButton = actionButton("◉  Learn IR Button", TEAL);
        learnButton.setOnClickListener(v -> startLearning());
        LinearLayout.LayoutParams learnParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        learnParams.setMargins(0, 0, 0, dp(12));
        root.addView(learnButton, learnParams);

        LinearLayout remotesHeader = new LinearLayout(this);
        remotesHeader.setGravity(Gravity.CENTER_VERTICAL);
        remotesHeader.setOrientation(LinearLayout.HORIZONTAL);
        remotesHeader.addView(text("MY REMOTES", 13, WHITE, true),
                new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button newRemoteButton = actionButton("+ New Remote", PURPLE);
        newRemoteButton.setTextSize(12);
        newRemoteButton.setOnClickListener(v -> createRemote());
        remotesHeader.addView(newRemoteButton,
                new LinearLayout.LayoutParams(dp(136), dp(42)));
        root.addView(remotesHeader);

        deviceSpinner = new Spinner(this);
        deviceSpinner.setBackground(background(SURFACE, 9));
        deviceSpinner.setPadding(dp(11), dp(6), dp(11), dp(6));
        LinearLayout.LayoutParams pickerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        pickerParams.setMargins(0, dp(6), 0, dp(8));
        root.addView(deviceSpinner, pickerParams);
        deviceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(
                    AdapterView<?> parent, View view, int pos, long id) {
                selectedDevice = pos;
                renderRemote();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(actions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
        Button add = actionButton("+ Add Button", PURPLE);
        add.setOnClickListener(v -> createButton());
        actions.addView(add, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1));
        Button tools = actionButton("Tools & Settings", Color.rgb(34, 75, 113));
        tools.setOnClickListener(v -> showToolsMenu());
        LinearLayout.LayoutParams toolsParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        toolsParams.setMargins(dp(8), 0, 0, 0);
        actions.addView(tools, toolsParams);

        // Latest capture/connection status has a compact two-line preview.
        // Tap it for the FULL details; no fixed footer obscures remote buttons.
        status = text("", 12, MUTED, false);
        status.setMaxLines(2);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setBackground(background(SURFACE, 9));
        status.setPadding(dp(10), dp(8), dp(10), dp(8));
        status.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Latest status")
                .setMessage(status.getText().toString())
                .setPositiveButton("Close", null).show());
        status.setContentDescription("Latest status. Tap to view full message.");
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.setMargins(0, dp(9), 0, dp(8));
        root.addView(status, statusParams);

        // All contents scroll together: remote list can never be squeezed
        // into a tiny internal scroll view on short-screen Android devices.
        remoteLayout = new LinearLayout(this);
        remoteLayout.setOrientation(LinearLayout.VERTICAL);
        remoteLayout.setPadding(dp(2), dp(2), dp(2), dp(18));
        root.addView(remoteLayout, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void showToolsMenu() {
        // Important: never combine setMessage() with setItems(); Android
        // hides list choices when both are specified in the same dialog.
        final String[] options = {
                "Import .irplus remote",
                "Export selected remote (.irplus)",
                "Layout Editor",
                "Manage selected remote",
                "U1 waveform backups / restore",
                "Full library backup / restore",
                "Harmony USB diagnostics",
                "About / Version"
        };
        new AlertDialog.Builder(this)
                .setTitle("Tools & Settings")
                .setItems(options, (d, index) -> {
                    switch (index) {
                        case 0: openImport(); break;
                        case 1:
                            if (current() == null) toast("Create a remote first.");
                            else openExport();
                            break;
                        case 2: showLayoutEditor(); break;
                        case 3:
                            if (current() == null) toast("Select a remote first.");
                            else manageRemote(current());
                            break;
                        case 4: openU1Backups(); break;
                        case 5: showLibraryBackupMenu(); break;
                        case 6: UsbHarmonyInspector.show(this); break;
                        case 7: showAbout(); break;
                        default: break;
                    }
                })
                .setNegativeButton("Close", null).show();
    }

    private Button actionButton(String value) {
        return actionButton(value, BLUE);
    }

    private Button actionButton(String value, int color) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(value);
        b.setTextSize(14);
        b.setTextColor(WHITE);
        b.setBackground(background(color, 12));
        return b;
    }

    private void setStatus(String message) {
        if (status != null) status.setText(message);
    }

    private void refreshDevices() {
        List<String> names = new ArrayList<>();
        for (IrplusCodec.Remote remote : remotes) names.add(remote.displayName());
        if (names.isEmpty()) names.add("No remotes yet — create or learn one");
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        deviceSpinner.setAdapter(adapter);
        selectedDevice = Math.min(Math.max(0, selectedDevice), names.size()-1);
        deviceSpinner.setSelection(selectedDevice);
        renderRemote();
    }

    private IrplusCodec.Remote current() {
        if (remotes.isEmpty() || selectedDevice < 0 || selectedDevice >= remotes.size()) return null;
        return remotes.get(selectedDevice);
    }

    private void renderRemote() {
        if (remoteLayout == null) return;
        remoteLayout.removeAllViews();
        IrplusCodec.Remote remote = current();
        if (remote == null) {
            TextView empty = text("No remotes yet.\nTap + New Remote to begin, or use Tools to import a file.",
                    16, MUTED, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(18), dp(35), dp(18), dp(20));
            remoteLayout.addView(empty);
            return;
        }
        TextView hint = text("Tap a button to send or learn  ·  Hold for options",
                11, MUTED, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(3), dp(7), dp(3), dp(10));
        remoteLayout.addView(hint);

        LinearLayout row = newRow();
        int used = 0;
        int buttons = 0;
        for (IrplusCodec.Item item : remote.items) {
            if (used + item.span > 12) {
                if (used < 12) addSpace(row, 12-used);
                remoteLayout.addView(row);
                row = newRow();
                used = 0;
            }
            if (item.space) {
                addSpace(row, item.span);
            } else {
                buttons++;
                Button b = new Button(this);
                b.setText(item.label);
                b.setTextColor(WHITE);
                b.setTextSize(item.span <= 3 ? 12 : 14);
                b.setAllCaps(false);
                b.setMaxLines(2);
                b.setPadding(dp(2), dp(4), dp(2), dp(4));
                b.setBackground(background(buttonColor(item.label), 9));
                b.setOnClickListener(v -> {
                    if (item.prontoHex.isEmpty()) startLearning(remote, item);
                    else send(item);
                });
                b.setOnLongClickListener(v -> {
                    editLearnedButton(remote, item);
                    return true;
                });
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(53), item.span);
                p.setMargins(dp(2), dp(3), dp(2), dp(3));
                row.addView(b, p);
            }
            used += item.span;
            if (used == 12) {
                remoteLayout.addView(row);
                row = newRow();
                used = 0;
            }
        }
        if (used > 0) {
            addSpace(row, 12-used);
            remoteLayout.addView(row);
        }
        if (buttons == 0) setStatus("This remote has no playable commands.");
    }

    private LinearLayout newRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setWeightSum(12);
        return row;
    }

    private void addSpace(LinearLayout row, int span) {
        View blank = new View(this);
        row.addView(blank, new LinearLayout.LayoutParams(0, dp(53), span));
    }

    private int buttonColor(String label) {
        String name = label.toLowerCase(Locale.ROOT);
        if (name.equals("power") || name.contains("on/off")) return Color.rgb(188, 46, 52);
        if (name.contains("volume") || name.contains("vol")) return Color.rgb(34, 133, 74);
        if (name.contains("channel") || name.equals("ch")) return Color.rgb(151, 112, 12);
        if (name.contains("ok") || name.contains("enter") || name.contains("menu"))
            return ORANGE;
        return Color.rgb(41, 76, 130);
    }

    private void send(IrplusCodec.Item item) {
        if (irManager == null || !irManager.hasIrEmitter()) {
            setStatus("IR unavailable: Android does not expose a compatible built-in emitter.");
            Toast.makeText(this, "This phone's public IR API is unavailable.", Toast.LENGTH_LONG).show();
            return;
        }
        final IrplusCodec.Signal signal;
        try {
            signal = IrplusCodec.decodePronto(item.prontoHex);
        } catch (Exception exception) {
            setStatus("Cannot send " + item.label + ": " + exception.getMessage());
            return;
        }
        setStatus("Transmitting: " + item.label + "...");
        new Thread(() -> {
            try {
                irManager.transmit(signal.carrierHz, signal.microseconds);
                runOnUiThread(() -> setStatus("Transmitted " + item.label
                    + " at " + signal.carrierHz + " Hz. Check the target equipment responded."));
            } catch (Exception exception) {
                runOnUiThread(() -> setStatus("IR transmission failed: " + exception.getMessage()));
            }
        }, "IrStudioTransmit").start();
    }

    private void createRemote() {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setHint("e.g. Living Room TV");
        field.setText("");
        field.setPadding(dp(18), dp(14), dp(18), dp(14));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Create a remote")
                .setMessage("Give this remote a name. Learn its buttons whenever you're ready.")
                .setView(field)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create", null)
                .create();
        dialog.setOnShowListener(v ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w -> {
                    String name = field.getText().toString().trim();
                    if (name.length() < 1 || name.length() > 60) {
                        field.setError("Enter a name up to 60 characters");
                        return;
                    }
                    if (remotes.size() >= 100) {
                        toast("The library supports up to 100 remotes.");
                        return;
                    }
                    IrplusCodec.Remote newRemote = new IrplusCodec.Remote("", name);
                    remotes.add(newRemote);
                    try {
                        saveRemotes();
                        selectedDevice = remotes.size() - 1;
                        refreshDevices();
                        setStatus("Created " + name + ". Add a button or learn a command.");
                        dialog.dismiss();
                    } catch (Exception error) {
                        remotes.remove(newRemote);
                        toast("Could not save remote: " + error.getMessage());
                    }
                }));
        dialog.show();
    }

    private void createButton() {
        createButton(false);
    }

    private void createButton(boolean learnAfterCreation) {
        IrplusCodec.Remote remote = current();
        if (remote == null) {
            new AlertDialog.Builder(this).setTitle("Create a remote first")
                    .setMessage("Choose New Remote before adding buttons.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        EditText nameField = new EditText(this);
        nameField.setSingleLine(true);
        nameField.setHint("Power, Input, Volume Up...");
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Add button to " + remote.displayName())
                .setMessage("Create a named button now; learn its IR code later.")
                .setView(nameField).setNegativeButton("Cancel", null)
                .setPositiveButton("Add button", null).create();
        dialog.setOnShowListener(v -> dialog.getButton(
                AlertDialog.BUTTON_POSITIVE).setOnClickListener(w -> {
            String label = nameField.getText().toString().trim();
            if (label.isEmpty() || label.length() > 40) {
                nameField.setError("Enter 1-40 characters"); return;
            }
            if (remote.items.size() >= 500) {
                toast("This remote already has 500 buttons."); return;
            }
            for (IrplusCodec.Item item : remote.items) {
                if (!item.space && item.label.equalsIgnoreCase(label)) {
                    nameField.setError("Button name already exists."); return;
                }
            }
            IrplusCodec.Item item = new IrplusCodec.Item(label, "", 6, false);
            remote.items.add(item);
            try {
                saveRemotes();
                renderRemote();
                setStatus("Added '" + label + "'. Tap it to learn a signal.");
                dialog.dismiss();
                if (learnAfterCreation) startLearning(remote, item);
            } catch (Exception error) {
                remote.items.remove(item);
                toast("Could not save button: " + error.getMessage());
            }
        }));
        dialog.show();
    }

    private File u1BackupFolder() {
        return new File(getFilesDir(), "U1-Learn-Backups");
    }

    private void restoreLearnButton() {
        if (learnButton == null) return;
        learnButton.setEnabled(true);
        learnButton.setText(u1Ble != null && u1Ble.isConnected()
                ? "◉  Learn IR • U1 Bluetooth" : "◉  Learn IR Button");
    }

    private boolean hasU1Permissions() {
        String[] permissions = Build.VERSION.SDK_INT >= 31
                ? new String[]{Manifest.permission.BLUETOOTH_SCAN,
                               Manifest.permission.BLUETOOTH_CONNECT}
                : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        List<String> missing = new ArrayList<>();
        for (String permission : permissions) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED)
                missing.add(permission);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), BLE_PERMISSION_REQUEST);
            return false;
        }
        return true;
    }

    @Override public void onRequestPermissionsResult(int requestCode,
                                                     String[] permissions,
                                                     int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != BLE_PERMISSION_REQUEST) return;
        boolean granted = results.length > 0;
        for (int result : results) {
            if (result != PackageManager.PERMISSION_GRANTED) granted = false;
        }
        if (granted) openU1Connection();
        else setStatus("Bluetooth scan/connect permission is needed for SofaBaton U1.");
    }

    private void openU1Connection() {
        if (!hasU1Permissions()) return;
        if (u1Ble.isLearning()) {
            toast("Finish the current U1 learning operation first.");
            return;
        }
        if (u1Ble.isConnected()) {
            new AlertDialog.Builder(this).setTitle("SofaBaton U1 connected")
                    .setMessage(u1Ble.deviceName()
                            + "\n\nYou can learn existing or new buttons directly on the main screen. "
                            + "Saved U1 waveforms are managed in U1 Backups / Restore.")
                    .setPositiveButton("Keep connected", null)
                    .setNegativeButton("Disconnect", (d, w) -> {
                        u1Ble.disconnect();
                        u1Status.setText("NOT CONNECTED — Scan again");
                        u1ConnectionButton.setText("Connect U1");
                        restoreLearnButton();
                    }).show();
        } else {
            u1Ble.scan();
        }
    }

    private void learnWithU1(IrplusCodec.Remote remote, IrplusCodec.Item button) {
        if (button.space || !remote.items.contains(button)) {
            toast("Choose an existing named button to learn.");
            return;
        }
        if (!u1LearningConsent) {
            new AlertDialog.Builder(this)
                    .setTitle("SofaBaton U1 learning")
                    .setMessage("Before EVERY capture, IR Remote Studio will read "
                            + "and back up the U1's existing slot-8 IR waveform. "
                            + "Learning can overwrite slot 8. The backup can be "
                            + "restored into the Android library or exported, "
                            + "but it CANNOT reprogram or fully restore the U1.\n\n"
                            + "Proceed using the verified Windows v0.3.3 learning commands?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Start learning", (d, w) -> {
                        u1LearningConsent = true;
                        beginU1Capture(remote, button);
                    }).show();
        } else {
            beginU1Capture(remote, button);
        }
    }

    private void beginU1Capture(IrplusCodec.Remote remote, IrplusCodec.Item button) {
        if (u1Ble == null || !u1Ble.isConnected()) {
            toast("Connect SofaBaton U1 Bluetooth first.");
            return;
        }
        if (u1Ble.isLearning()) {
            toast("SofaBaton U1 is already learning.");
            return;
        }
        u1TargetRemote = remote;
        u1TargetButton = button;
        learnButton.setEnabled(false);
        learnButton.setText("BACKING UP U1 IR...");
        setStatus("Reading, writing and verifying the existing slot-8 backup "
                + "before the U1 is armed.");
        u1Ble.learn(u1BackupFolder());
    }

    private void openU1Backups() {
        File[] backups = U1BackupStore.list(u1BackupFolder());
        List<String> options = new ArrayList<>();
        options.add("Import a Windows/Android U1 waveform JSON...");
        if (backups.length == 0) {
            options.add("No local U1 backups yet — learn a command first");
        } else {
            for (File backup : backups) options.add(backup.getName());
        }
        // Android AlertDialog gives setMessage() priority over setItems():
        // using both hides the choices entirely. Keep this a LIST dialog.
        new AlertDialog.Builder(this)
                .setTitle("U1 backups (restore to APP, not remote)")
                .setItems(options.toArray(new String[0]), (d, selected) -> {
                    if (selected == 0) {
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.setType("*/*");
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        startActivityForResult(intent, IMPORT_U1_BACKUP);
                    } else if (backups.length == 0) {
                        new AlertDialog.Builder(this)
                                .setTitle("No U1 backups found")
                                .setMessage("A slot-8 waveform backup is saved before "
                                        + "each successful U1 learning attempt. "
                                        + "You can also import a previous Windows "
                                        + "or Android U1 waveform JSON backup.")
                                .setPositiveButton("OK", null).show();
                    } else {
                        showU1BackupActions(backups[selected - 1]);
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void showU1BackupActions(File backup) {
        new AlertDialog.Builder(this)
                .setTitle(backup.getName())
                .setItems(new String[]{
                        "Restore IR waveform into Android library button",
                        "Export this backup JSON"
                }, (d, which) -> {
                    if (which == 0) restoreU1WaveformToLibrary(backup);
                    else {
                        pendingU1BackupExport = backup;
                        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                        intent.setType("application/json");
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.putExtra(Intent.EXTRA_TITLE, backup.getName());
                        startActivityForResult(intent, EXPORT_U1_BACKUP);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void restoreU1WaveformToLibrary(File backup) {
        IrplusCodec.Remote remote = current();
        if (remote == null) {
            new AlertDialog.Builder(this)
                    .setTitle("Create a remote")
                    .setMessage("Create/select an Android remote before restoring "
                            + "a saved U1 IR waveform to one of its buttons.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        List<IrplusCodec.Item> buttons = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (IrplusCodec.Item item : remote.items) {
            if (!item.space) {
                buttons.add(item);
                labels.add(item.label);
            }
        }
        if (buttons.isEmpty()) {
            toast("Add a named button before restoring a waveform.");
            return;
        }
        final String pronto;
        try {
            pronto = U1Protocol.pronto(U1BackupStore.read(backup));
        } catch (Exception issue) {
            toast("Backup cannot be converted to IRplus: " + issue.getMessage());
            return;
        }
        // Keep the destination list visible: setMessage() would hide it.
        // This operation changes only Android's app library; no U1 writes.
        new AlertDialog.Builder(this)
                .setTitle("Restore into Android library (NOT U1)")
                .setItems(labels.toArray(new String[0]), (d, index) -> {
                    IrplusCodec.Item item = buttons.get(index);
                    try {
                        IrplusCodec.Item updated = item.addCapture(pronto);
                        if (replaceButtonAndSave(remote, item, updated)) {
                            setStatus("Restored U1 backup into Android button '"
                                    + updated.label + "'. U1 hardware unchanged.");
                        }
                    } catch (Exception e) {
                        toast("Restore failed: " + e.getMessage());
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showAbout() {
        String capabilities = "Android " + BuildConfig.VERSION_NAME
                + "\n\nLearn: compatible Logitech Harmony 300/350 over USB. "
                + "SofaBaton U1 Bluetooth scan, identity verification and "
                + "backup-before-learning are included as an Android test feature. "
                + "U1 Bluetooth capture and IRplus export were successfully tested on a physical remote. "
                + "A waveform backup can restore to the Android library, "
                + "not the U1's internal programming."
                + "\n\nLibrary: named remotes, raw IR captures, comparison, "
                + "layout order and width, full JSON library backup and .irplus import/export."
                + "\n\nLocal data: " + getFilesDir().getAbsolutePath()
                + "\n\nProject: github.com/MixterVee/IR-Remote-Studio"
                + "\nLicense: MIT";
        new AlertDialog.Builder(this).setTitle("About IR Remote Studio")
                .setMessage(capabilities)
                .setNegativeButton("Close", null)
                .setNeutralButton("Library backup...", (d, w) -> showLibraryBackupMenu())
                .setPositiveButton("Project on GitHub", (d, w) -> {
                    Intent open = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/MixterVee/IR-Remote-Studio"));
                    startActivity(open);
                }).show();
    }

    private void showLayoutEditor() {
        IrplusCodec.Remote remote = current();
        if (remote == null) {
            toast("Create or select a remote first."); return;
        }
        RemoteLayoutDraft draft = new RemoteLayoutDraft(remote);
        if (draft.size() == 0) {
            toast("Add a button before arranging the layout."); return;
        }
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(17), dp(6), dp(17), dp(8));
        body.addView(text("Button order and width are changed here, not on "
                + "the main learning screen. Cancel discards all edits.",
                12, MUTED, false));
        Spinner picker = new Spinner(this);
        body.addView(picker, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(53)));
        Spinner widths = new Spinner(this);
        String[] options = new String[12];
        for (int i = 0; i < 12; i++) options[i] = (i + 1) + " columns";
        ArrayAdapter<String> widthAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, options);
        widthAdapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        widths.setAdapter(widthAdapter);
        body.addView(widths, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(49)));
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        Button up = actionButton("Move Up");
        Button down = actionButton("Move Down");
        Button apply = actionButton("Apply width", TEAL);
        controls.addView(up, new LinearLayout.LayoutParams(0, dp(50), 1));
        controls.addView(down, new LinearLayout.LayoutParams(0, dp(50), 1));
        controls.addView(apply, new LinearLayout.LayoutParams(0, dp(50), 1));
        body.addView(controls);
        TextView previewTitle = text("12-COLUMN PREVIEW", 12, MUTED, true);
        previewTitle.setPadding(0, dp(18), 0, dp(7));
        body.addView(previewTitle);
        LinearLayout preview = new LinearLayout(this);
        preview.setOrientation(LinearLayout.VERTICAL);
        preview.setPadding(dp(8), dp(8), dp(8), dp(8));
        preview.setBackground(background(SURFACE, 8));
        body.addView(preview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        final int[] index = {0};
        final Runnable redraw = () -> {
            List<String> names = new ArrayList<>();
            for (IrplusCodec.Item item : draft.items()) {
                names.add((item.space ? "(Space)" : item.label)
                        + "  •  " + item.span + "/12");
            }
            ArrayAdapter<String> adapter = new ArrayAdapter<>(
                    this, android.R.layout.simple_spinner_item, names);
            adapter.setDropDownViewResource(
                    android.R.layout.simple_spinner_dropdown_item);
            picker.setAdapter(adapter);
            if (!names.isEmpty()) {
                index[0] = Math.min(index[0], names.size() - 1);
                picker.setSelection(index[0]);
                widths.setSelection(draft.get(index[0]).span - 1);
            }
            renderLayoutPreview(preview, draft.items());
        };
        picker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(
                    AdapterView<?> parent, View view, int position, long id) {
                index[0] = position;
                widths.setSelection(draft.get(position).span - 1);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        up.setOnClickListener(v -> {
            index[0] = draft.move(index[0], -1);
            redraw.run();
        });
        down.setOnClickListener(v -> {
            index[0] = draft.move(index[0], 1);
            redraw.run();
        });
        apply.setOnClickListener(v -> {
            draft.setWidth(index[0], widths.getSelectedItemPosition() + 1);
            redraw.run();
        });
        redraw.run();
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Layout Editor • " + remote.displayName())
                .setView(scroll).setNegativeButton("Cancel", null)
                .setPositiveButton("Save layout", null).create();
        dialog.setOnShowListener(v ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(w -> {
                    List<IrplusCodec.Item> original = new ArrayList<>(remote.items);
                    draft.applyTo(remote);
                    try {
                        saveRemotes();
                        renderRemote();
                        setStatus("Saved button order and widths.");
                        dialog.dismiss();
                    } catch (Exception error) {
                        remote.items.clear();
                        remote.items.addAll(original);
                        toast("Layout save failed: " + error.getMessage());
                    }
                }));
        dialog.show();
    }

    /** Noninteractive twelve-column preview matches the exported button layout. */
    private void renderLayoutPreview(LinearLayout container,
                                     List<IrplusCodec.Item> items) {
        container.removeAllViews();
        LinearLayout row = newRow();
        int used = 0;
        for (IrplusCodec.Item item : items) {
            if (used + item.span > 12 && used > 0) {
                addSpace(row, 12 - used);
                container.addView(row);
                row = newRow();
                used = 0;
            }
            if (item.space) {
                addSpace(row, item.span);
            } else {
                String label = item.label;
                if (item.prontoHex.isEmpty()) label += " (empty)";
                TextView sample = text(label, item.span <= 3 ? 10 : 12, WHITE, true);
                sample.setGravity(Gravity.CENTER);
                sample.setMaxLines(2);
                sample.setPadding(dp(2), dp(2), dp(2), dp(2));
                sample.setBackground(background(
                        item.prontoHex.isEmpty()
                                ? Color.rgb(80, 90, 105)
                                : buttonColor(item.label), 7));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        0, dp(47), item.span);
                params.setMargins(dp(2), dp(3), dp(2), dp(3));
                row.addView(sample, params);
            }
            used += item.span;
            if (used == 12) {
                container.addView(row);
                row = newRow();
                used = 0;
            }
        }
        if (used > 0) {
            addSpace(row, 12 - used);
            container.addView(row);
        }
    }

    /** Use U1 Bluetooth when connected, otherwise preserve Harmony USB learning. */
    private void startLearning() {
        startLearning(null, null);
    }

    private void startLearning(IrplusCodec.Remote targetRemote,
                               IrplusCodec.Item targetButton) {
        if (u1Ble != null && u1Ble.isConnected()) {
            if (targetRemote == null || targetButton == null) {
                IrplusCodec.Remote currentRemote = current();
                if (currentRemote == null) {
                    new AlertDialog.Builder(this)
                            .setTitle("Create a remote")
                            .setMessage("Create a remote and a named button before U1 learning.")
                            .setPositiveButton("OK", null).show();
                    return;
                }
                boolean hasButtons = false;
                for (IrplusCodec.Item item : currentRemote.items) {
                    if (!item.space) hasButtons = true;
                }
                if (hasButtons) chooseExistingForLearning(currentRemote);
                else createButton(true);
                return;
            }
            learnWithU1(targetRemote, targetButton);
            return;
        }
        UsbManager usb = (UsbManager) getSystemService(USB_SERVICE);
        UsbDevice harmony = null;
        if (usb != null) {
            for (UsbDevice device : usb.getDeviceList().values()) {
                if (HarmonyUsbIds.isHarmony300Or350(device.getVendorId(),
                        device.getProductId())) {
                    harmony = device;
                    break;
                }
            }
        }
        if (harmony == null || !usb.hasPermission(harmony)) {
            new AlertDialog.Builder(this)
                    .setTitle("Connect your Harmony 300")
                    .setMessage("Connect the Harmony to your phone with USB and grant "
                            + "permission in Harmony USB Connection & Diagnostics. "
                            + "Your existing Harmony buttons won't be changed.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Connect / grant permission",
                            (d, w) -> UsbHarmonyInspector.show(this))
                    .show();
            return;
        }
        final UsbDevice target = harmony;
        final UsbManager manager = usb;
        new AlertDialog.Builder(this)
                .setTitle("Learn an IR button")
                .setMessage("Aim another IR remote at the Harmony 300. Tap Start, "
                        + "wait until IR Remote Studio says READY, then press and "
                        + "release ONE button. A compatible capture can be tested "
                        + "and named before it's saved to your phone.\n\n"
                        + "The Harmony's own programming and firmware are untouched.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Start learning", (d, w) -> {
                    learnButton.setEnabled(false);
                    learnButton.setText("Connecting to Harmony...");
                    AtomicReference<String> candidate = new AtomicReference<>();
                    new Thread(() -> {
                        String report = HarmonyMhProbe.runCapture(manager, target,
                                () -> runOnUiThread(() -> {
                                    if (isFinishing() || isDestroyed()) return;
                                    learnButton.setText("READY • Press source button");
                                    toast("READY! Press and release the source remote button.");
                                }),
                                candidate::set);
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            learnButton.setEnabled(true);
                            learnButton.setText("◉  Learn IR Button");
                            String pronto = candidate.get();
                            if (pronto == null) {
                                showFailedLearning(report);
                            } else if (targetRemote != null && targetButton != null) {
                                saveExistingCapture(targetRemote, targetButton, pronto);
                            } else {
                                showCapturePreview(pronto, report);
                            }
                        });
                    }, "HarmonyLearnAndPreview").start();
                }).show();
    }

    private boolean replaceButtonAndSave(IrplusCodec.Remote remote,
                                          IrplusCodec.Item oldItem,
                                          IrplusCodec.Item replacement) {
        int index = remote.items.indexOf(oldItem);
        if (index < 0) {
            toast("The button was removed. No capture was saved.");
            return false;
        }
        remote.items.set(index, replacement);
        try {
            saveRemotes();
            renderRemote();
            return true;
        } catch (Exception error) {
            remote.items.set(index, oldItem);
            toast("Could not save IR library: " + error.getMessage());
            return false;
        }
    }

    private void saveExistingCapture(IrplusCodec.Remote remote,
                                     IrplusCodec.Item item, String pronto) {
        // Validates and saves to the preselected button as soon as capture ends.
        // A failure rolls back and never reports success.
        try {
            IrplusCodec.Item changed = item.addCapture(pronto);
            if (!replaceButtonAndSave(remote, item, changed)) return;
            setStatus("Auto-saved '" + changed.label + "' ("
                    + changed.captures.size() + " samples). "
                    + changed.sampleStatus() + ". Ready for IRplus export.");
            showLearningChoices(remote);
        } catch (IllegalArgumentException error) {
            toast("Invalid IR capture: " + error.getMessage());
        }
    }

    private void showLearningChoices(IrplusCodec.Remote remote) {
        new AlertDialog.Builder(this)
                .setTitle("IR command saved")
                .setMessage("The IR command was saved to this remote. "
                        + "What would you like to learn next?")
                .setPositiveButton("New button",
                        (d, w) -> createButton(true))
                .setNeutralButton("Existing button",
                        (d, w) -> chooseExistingForLearning(remote))
                .setNegativeButton("Finish",
                        (d, w) -> setStatus(
                                "Learning finished. Saved commands are ready for IRplus."))
                .show();
    }

    private void chooseExistingForLearning(IrplusCodec.Remote remote) {
        List<IrplusCodec.Item> eligible = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (IrplusCodec.Item item : remote.items) {
            if (!item.space) {
                eligible.add(item);
                labels.add(item.label + "  •  " + item.sampleStatus());
            }
        }
        if (eligible.isEmpty()) {
            createButton(true);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Learn an existing button")
                .setItems(labels.toArray(new String[0]), (d, which) ->
                        startLearning(remote, eligible.get(which)))
                .setNegativeButton("Cancel", null).show();
    }

    private void showFailedLearning(String report) {
        new AlertDialog.Builder(this)
                .setTitle("No verified IR command yet")
                .setMessage("A command can be saved only when the capture, STOP and "
                        + "CLOSE checks succeed. See the diagnostic below:\n\n" + report)
                .setNegativeButton("Close", null)
                .setNeutralButton("Copy diagnostic",
                        (d, w) -> copyText("Harmony learning diagnostic", report))
                .show();
    }

    private void showCapturePreview(String pronto, String report) {
        final IrplusCodec.Signal signal;
        try {
            signal = IrplusCodec.decodePronto(pronto);
        } catch (IllegalArgumentException bad) {
            showFailedLearning(report + "\nPronto validation error: " + bad.getMessage());
            return;
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(9), dp(20), dp(4));

        TextView success = text("SIGNAL RECEIVED", 13, TEAL, true);
        content.addView(success);
        TextView details = text(signal.carrierHz + " Hz   •   "
                + (signal.microseconds.length / 2)
                + " timing pairs\nTest it against the target equipment before saving.",
                13, MUTED, false);
        details.setPadding(0, dp(4), 0, dp(12));
        content.addView(details);

        TextView labelText = text("BUTTON NAME", 11, MUTED, true);
        content.addView(labelText);
        EditText buttonName = new EditText(this);
        buttonName.setSingleLine(true);
        buttonName.setHint("Power, Volume Up, Input...");
        buttonName.setText("");
        content.addView(buttonName);

        TextView targetText = text("SAVE TO REMOTE", 11, MUTED, true);
        targetText.setPadding(0, dp(14), 0, dp(4));
        content.addView(targetText);
        List<String> targets = new ArrayList<>();
        targets.add("+ Create a new remote");
        for (IrplusCodec.Remote remote : remotes) targets.add(remote.displayName());
        Spinner targetSpinner = new Spinner(this);
        ArrayAdapter<String> pickerAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, targets);
        pickerAdapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        targetSpinner.setAdapter(pickerAdapter);
        content.addView(targetSpinner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(49)));

        EditText remoteName = new EditText(this);
        remoteName.setSingleLine(true);
        remoteName.setHint("New remote name, e.g. Bedroom TV");
        content.addView(remoteName);
        int defaultTarget = remotes.isEmpty() ? 0 : Math.min(selectedDevice + 1,
                remotes.size());
        targetSpinner.setSelection(defaultTarget);
        remoteName.setVisibility(defaultTarget == 0 ? View.VISIBLE : View.GONE);
        targetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view,
                                                  int position, long id) {
                remoteName.setVisibility(position == 0 ? View.VISIBLE : View.GONE);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        Button test = actionButton("▶  Test IR on target device", TEAL);
        test.setOnClickListener(v -> transmitPreview(pronto));
        LinearLayout.LayoutParams testParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(49));
        testParams.setMargins(0, dp(13), 0, dp(5));
        content.addView(test, testParams);

        Button copy = actionButton("Copy Pronto Hex", Color.rgb(39, 72, 113));
        copy.setOnClickListener(v -> copyText("Captured Pronto Hex", pronto));
        content.addView(copy, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));

        TextView note = text("Name this NEW button and save it to your "
                + "library. Existing buttons auto-save immediately after capture. "
                + "No Harmony programming is changed.", 11, MUTED, false);
        note.setPadding(0, dp(12), 0, dp(3));
        content.addView(note);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Learned IR Signal")
                .setView(scroll)
                .setNegativeButton("Discard", null)
                .setNeutralButton("Copy full report",
                        (d, w) -> copyText("Harmony learning report", report))
                .setPositiveButton("Save button", null)
                .create();
        dialog.setOnShowListener(v ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w -> {
                    if (saveLearnedButton(pronto,
                            buttonName.getText().toString().trim(),
                            targetSpinner.getSelectedItemPosition(),
                            remoteName.getText().toString().trim(),
                            buttonName, remoteName)) {
                        dialog.dismiss();
                        IrplusCodec.Remote saved = current();
                        if (saved != null) showLearningChoices(saved);
                    }
                }));
        dialog.show();
    }

    private boolean saveLearnedButton(String pronto, String label, int destination,
                                      String newName, EditText labelField,
                                      EditText remoteField) {
        if (label.length() < 1 || label.length() > 40) {
            labelField.setError("Enter a button name (1-40 characters)");
            return false;
        }
        if (destination == 0 && (newName.length() < 1 || newName.length() > 60)) {
            remoteField.setError("Enter a remote name (1-60 characters)");
            return false;
        }
        if (destination == 0 && remotes.size() >= 100) {
            toast("The library limit is 100 remotes.");
            return false;
        }
        if (destination > remotes.size() || destination < 0) return false;
        // Validate any provided code again before changing local state.
        try {
            IrplusCodec.decodePronto(pronto);
        } catch (IllegalArgumentException bad) {
            toast("Invalid captured code: " + bad.getMessage());
            return false;
        }
        boolean created = destination == 0;
        IrplusCodec.Remote target = created
                ? new IrplusCodec.Remote("", newName)
                : remotes.get(destination - 1);
        if (target.items.size() >= 500) {
            toast("This remote has reached the 500-button limit.");
            return false;
        }
        for (IrplusCodec.Item item : target.items) {
            if (!item.space && item.label.equalsIgnoreCase(label)) {
                labelField.setError("That button name already exists in this remote");
                return false;
            }
        }
        IrplusCodec.Item learned = new IrplusCodec.Item(label, pronto, 6, false);
        if (created) remotes.add(target);
        target.items.add(learned);
        try {
            saveRemotes();
            selectedDevice = remotes.indexOf(target);
            refreshDevices();
            setStatus("Auto-saved '" + label + "' to " + target.displayName()
                    + ". Ready for IRplus export.");
            toast("IR button saved to your phone");
            return true;
        } catch (Exception error) {
            target.items.remove(learned);
            if (created) remotes.remove(target);
            toast("Could not save: " + error.getMessage());
            return false;
        }
    }

    private void transmitPreview(String pronto) {
        if (irManager == null || !irManager.hasIrEmitter()) {
            toast("This phone does not expose an IR transmitter through Android.");
            return;
        }
        final IrplusCodec.Signal signal;
        try {
            signal = IrplusCodec.decodePronto(pronto);
        } catch (IllegalArgumentException bad) {
            toast("Cannot transmit: " + bad.getMessage());
            return;
        }
        new Thread(() -> {
            try {
                irManager.transmit(signal.carrierHz, signal.microseconds);
                runOnUiThread(() -> {
                    setStatus("Test transmitted. Did the target device respond?");
                    toast("IR signal sent — check the target equipment");
                });
            } catch (RuntimeException error) {
                runOnUiThread(() -> toast("IR test failed: " + error.getMessage()));
            }
        }, "IrStudioOneShotTest").start();
    }

    private void manageRemote(IrplusCodec.Remote remote) {
        new AlertDialog.Builder(this)
                .setTitle(remote.displayName())
                .setItems(new String[]{"Rename remote", "Delete remote"}, (d, choice) -> {
                    if (choice == 0) renameRemote(remote);
                    else deleteRemote(remote);
                }).show();
    }

    private void renameRemote(IrplusCodec.Remote remote) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(remote.model);
        input.selectAll();
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Rename remote")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", null).create();
        dialog.setOnShowListener(v ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty() || name.length() > 60) {
                        input.setError("Enter 1-60 characters");
                        return;
                    }
                    int index = remotes.indexOf(remote);
                    if (index < 0) return;
                    IrplusCodec.Remote renamed = new IrplusCodec.Remote(
                            remote.manufacturer, name);
                    renamed.items.addAll(remote.items);
                    remotes.set(index, renamed);
                    try {
                        saveRemotes();
                        selectedDevice = index;
                        refreshDevices();
                        dialog.dismiss();
                    } catch (Exception error) {
                        remotes.set(index, remote);
                        toast("Rename failed: " + error.getMessage());
                    }
                }));
        dialog.show();
    }

    private void deleteRemote(IrplusCodec.Remote remote) {
        new AlertDialog.Builder(this)
                .setTitle("Delete " + remote.displayName() + "?")
                .setMessage("This deletes this remote and its buttons from the "
                        + "phone's local library. You can export a backup first. "
                        + "Nothing is erased from the Harmony 300.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete remote", (d, w) -> {
                    int index = remotes.indexOf(remote);
                    if (index < 0) return;
                    remotes.remove(index);
                    try {
                        saveRemotes();
                        selectedDevice = Math.max(0,
                                Math.min(index, remotes.size() - 1));
                        refreshDevices();
                        setStatus("Remote removed from the local phone library.");
                    } catch (Exception error) {
                        remotes.add(index, remote);
                        toast("Delete failed: " + error.getMessage());
                    }
                }).show();
    }

    private void editLearnedButton(IrplusCodec.Remote remote, IrplusCodec.Item item) {
        if (item.space) {
            showLayoutEditor();
            return;
        }
        String details = item.captures.size() + " capture(s)  •  " + item.sampleStatus();
        new AlertDialog.Builder(this)
                .setTitle(item.label + " — " + details)
                .setItems(new String[]{
                        "Learn / add another sample",
                        "Undo last capture",
                        "Clear all captures (keep button)",
                        "Choose best match",
                        "Rename button",
                        "Delete button"
                }, (d, choice) -> {
                    switch (choice) {
                        case 0: startLearning(remote, item); break;
                        case 1: changeCaptures(remote, item, 1); break;
                        case 2: changeCaptures(remote, item, 2); break;
                        case 3: changeCaptures(remote, item, 3); break;
                        case 4: renameButton(remote, item); break;
                        case 5: deleteButton(remote, item); break;
                        default: break;
                    }
                }).show();
    }

    private void changeCaptures(IrplusCodec.Remote remote,
                                IrplusCodec.Item item, int action) {
        if (item.captures.isEmpty()) {
            toast("This button has no saved samples.");
            return;
        }
        if (action == 2) {
            new AlertDialog.Builder(this)
                    .setTitle("Clear captured signals?")
                    .setMessage("Clear all " + item.captures.size()
                            + " sample(s) from '" + item.label
                            + "'? The button and its layout will stay. "
                            + "This changes only the Android library, not a remote.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Clear samples", (d, w) ->
                            changeCapturesConfirmed(remote, item, action))
                    .show();
        } else if (action == 1) {
            new AlertDialog.Builder(this)
                    .setTitle("Undo last capture?")
                    .setMessage("Remove only the newest sample from '"
                            + item.label + "'?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Undo", (d, w) ->
                            changeCapturesConfirmed(remote, item, action))
                    .show();
        } else changeCapturesConfirmed(remote, item, action);
    }

    private void changeCapturesConfirmed(IrplusCodec.Remote remote,
                                         IrplusCodec.Item item, int action) {
        IrplusCodec.Item replacement;
        switch (action) {
            case 1: replacement = item.removeLastCapture(); break;
            case 2: replacement = item.clearCaptures(); break;
            case 3: replacement = item.selectBestCapture(); break;
            default: return;
        }
        if (replaceButtonAndSave(remote, item, replacement)) {
            setStatus("'" + item.label + "': " + replacement.sampleStatus()
                    + ", " + replacement.captures.size()
                    + " saved sample(s). Button retained.");
        }
    }

    private void renameButton(IrplusCodec.Remote remote, IrplusCodec.Item item) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(item.label);
        input.selectAll();
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Rename button")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", null).create();
        dialog.setOnShowListener(v ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty() || name.length() > 40) {
                        input.setError("Enter 1-40 characters");
                        return;
                    }
                    for (IrplusCodec.Item other : remote.items) {
                        if (other != item && !other.space
                                && other.label.equalsIgnoreCase(name)) {
                            input.setError("This name is already used");
                            return;
                        }
                    }
                    int index = remote.items.indexOf(item);
                    if (index < 0) return;
                    remote.items.set(index, item.withLabel(name));
                    try {
                        saveRemotes();
                        renderRemote();
                        dialog.dismiss();
                    } catch (Exception error) {
                        remote.items.set(index, item);
                        toast("Rename failed: " + error.getMessage());
                    }
                }));
        dialog.show();
    }

    private void deleteButton(IrplusCodec.Remote remote, IrplusCodec.Item item) {
        new AlertDialog.Builder(this)
                .setTitle("Delete " + item.label + "?")
                .setMessage("This removes the button from the phone's library. "
                        + "It does not change the Harmony or other equipment.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> {
                    int index = remote.items.indexOf(item);
                    if (index < 0) return;
                    remote.items.remove(index);
                    try {
                        saveRemotes();
                        renderRemote();
                        setStatus("Deleted " + item.label + " from the local library.");
                    } catch (Exception error) {
                        remote.items.add(index, item);
                        toast("Delete failed: " + error.getMessage());
                    }
                }).show();
    }

    private void copyText(String label, String value) {
        android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, value));
            toast("Copied to clipboard");
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void showLibraryBackupMenu() {
        new AlertDialog.Builder(this)
                .setTitle("IR library backup")
                .setMessage("Save every remote, button, layout width and captured "
                        + "sample as a JSON backup before upgrading or uninstalling. "
                        + "Unlike .irplus, this preserves full capture history.")
                .setPositiveButton("Back up library", (d, w) -> {
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.setType("application/json");
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.putExtra(Intent.EXTRA_TITLE,
                            "IR-Remote-Studio-Android-library.json");
                    startActivityForResult(intent, BACKUP_LIBRARY);
                })
                .setNeutralButton("Restore backup", (d, w) -> {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.setType("*/*");
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(intent, RESTORE_LIBRARY);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void restoreLibraryFromDocument(Uri uri) throws Exception {
        final byte[] raw;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IllegalStateException("Cannot open backup file.");
            raw = readLimited(input, 2_000_000);
        }
        final List<IrplusCodec.Remote> incoming = parseLibraryJson(
                new String(raw, StandardCharsets.UTF_8));
        new AlertDialog.Builder(this)
                .setTitle("Replace local library?")
                .setMessage("This backup contains " + incoming.size()
                        + " remote(s). It will replace the current Android library. "
                        + "A local recovery copy will be written first.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Restore", (d, w) -> {
                    List<IrplusCodec.Remote> old = new ArrayList<>(remotes);
                    int previousSelected = selectedDevice;
                    try {
                        File currentFile = new File(getFilesDir(), "remotes.json");
                        if (currentFile.exists()) {
                            File backup = new File(getFilesDir(),
                                    "remotes-before-restore.json");
                            try (InputStream input = new FileInputStream(currentFile);
                                 OutputStream output = new FileOutputStream(backup)) {
                                byte[] block = new byte[8192];
                                int n;
                                while ((n = input.read(block)) != -1) {
                                    output.write(block, 0, n);
                                }
                                output.flush();
                            }
                        }
                        remotes.clear();
                        remotes.addAll(incoming);
                        selectedDevice = 0;
                        saveRemotes();
                        refreshDevices();
                        setStatus("Restored " + incoming.size()
                                + " remote(s) and all capture samples.");
                    } catch (Exception error) {
                        remotes.clear();
                        remotes.addAll(old);
                        selectedDevice = previousSelected;
                        refreshDevices();
                        toast("Restore failed; previous library retained: "
                                + error.getMessage());
                    }
                }).show();
    }

    private void openImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                "application/xml", "text/xml", "application/octet-stream", "text/plain"
        });
        startActivityForResult(intent, IMPORT_DEVICE);
    }

    private void openExport() {
        IrplusCodec.Remote remote = current();
        if (remote == null) return;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.setType("application/xml");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        String clean = remote.displayName().replaceAll("[^a-zA-Z0-9._ -]", "_").trim();
        intent.putExtra(Intent.EXTRA_TITLE, (clean.isEmpty() ? "Remote" : clean) + ".irplus");
        startActivityForResult(intent, EXPORT_DEVICE);
    }

    @Override @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == IMPORT_DEVICE) {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IllegalArgumentException("Could not open the selected file.");
                byte[] xml = readLimited(input, 2_000_000);
                List<IrplusCodec.Remote> added = IrplusCodec.read(new ByteArrayInputStream(xml));
                int firstNew = remotes.size();
                if (firstNew + added.size() > 100) {
                    throw new IllegalArgumentException("Library limit is 100 imported remotes.");
                }
                remotes.addAll(added);
                selectedDevice = firstNew;
                saveRemotes();
                refreshDevices();
                setStatus("Imported " + added.size() + " remote(s). Tap a button to transmit IR.");
            } catch (Exception exception) {
                String reason = exception.getMessage();
                if (reason == null || reason.trim().isEmpty()) {
                    reason = exception.getClass().getSimpleName();
                }
                setStatus("Import failed: " + reason);
                new AlertDialog.Builder(this)
                    .setTitle("Could not import remote")
                    .setMessage(reason + "\n\nChoose a PRONTO_HEX .irplus file exported from IR Remote Studio on Windows. "
                        + "If it still fails, send us this exact error message.")
                    .setPositiveButton("OK", null)
                    .show();
            }
        } else if (requestCode == IMPORT_U1_BACKUP) {
            File temporary = new File(getCacheDir(), "u1-import-pending.json");
            try {
                try (InputStream input = getContentResolver().openInputStream(uri);
                     FileOutputStream output = new FileOutputStream(temporary)) {
                    if (input == null) throw new IllegalStateException("Cannot open U1 backup");
                    byte[] bytes = readLimited(input, 131072);
                    output.write(bytes);
                    output.flush();
                }
                U1Protocol.Signal restored = U1BackupStore.read(temporary);
                File saved = U1BackupStore.save(u1BackupFolder(), restored);
                setStatus("Imported U1 waveform backup: " + saved.getName()
                        + ". Use U1 Backups / Restore to add it to a library button.");
                showU1BackupActions(saved);
            } catch (Exception failure) {
                toast("U1 waveform import failed: " + failure.getMessage());
            } finally {
                if (temporary.exists()) temporary.delete();
            }
        } else if (requestCode == EXPORT_U1_BACKUP) {
            File backup = pendingU1BackupExport;
            pendingU1BackupExport = null;
            try {
                U1BackupStore.read(backup); // validate before sharing
                try (InputStream input = new FileInputStream(backup);
                     OutputStream output = getContentResolver().openOutputStream(uri)) {
                    if (output == null) throw new IllegalStateException("Cannot write backup");
                    byte[] bytes = new byte[8192];
                    int n;
                    while ((n = input.read(bytes)) != -1) output.write(bytes, 0, n);
                    output.flush();
                }
                setStatus("U1 slot-8 waveform exported to JSON. "
                        + "This is NOT a complete U1 programming restore.");
            } catch (Exception failure) {
                toast("U1 backup export failed: " + failure.getMessage());
            }
        } else if (requestCode == BACKUP_LIBRARY) {
            try (OutputStream output = getContentResolver().openOutputStream(uri)) {
                if (output == null) throw new IllegalStateException("Cannot open backup destination.");
                output.write(libraryJson().getBytes(StandardCharsets.UTF_8));
                output.flush();
                setStatus("Full Android library backup saved, including capture history.");
            } catch (Exception error) {
                setStatus("Library backup failed: " + error.getMessage());
                toast("Backup failed: " + error.getMessage());
            }
        } else if (requestCode == RESTORE_LIBRARY) {
            try {
                restoreLibraryFromDocument(uri);
            } catch (Exception error) {
                new AlertDialog.Builder(this).setTitle("Invalid library backup")
                        .setMessage(error.getMessage()).setPositiveButton("OK", null)
                        .show();
            }
        } else if (requestCode == EXPORT_DEVICE) {
            try (OutputStream output = getContentResolver().openOutputStream(uri)) {
                if (output == null) throw new IllegalArgumentException("Cannot open output file.");
                IrplusCodec.Remote remote = current();
                if (remote == null) return;
                output.write(IrplusCodec.write(remote).getBytes(StandardCharsets.UTF_8));
                setStatus("Exported " + remote.displayName() + " as .irplus.");
            } catch (Exception exception) {
                setStatus("Export failed: " + exception.getMessage());
            }
        }
    }

    private static byte[] readLimited(InputStream input, int maxBytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] block = new byte[8192];
        int count;
        while ((count = input.read(block)) >= 0) {
            if (out.size() + count > maxBytes) {
                throw new IllegalArgumentException("Import is too large (maximum 2 MB).");
            }
            out.write(block, 0, count);
        }
        return out.toByteArray();
    }

    private String libraryJson() throws Exception {
        JSONArray array = new JSONArray();
        for (IrplusCodec.Remote r : remotes) {
            JSONObject entry = new JSONObject();
            entry.put("manufacturer", r.manufacturer);
            entry.put("model", r.model);
            JSONArray buttons = new JSONArray();
            for (IrplusCodec.Item item : r.items) {
                JSONObject b = new JSONObject();
                b.put("label", item.label);
                b.put("prontoHex", item.prontoHex);
                b.put("span", item.span);
                b.put("space", item.space);
                b.put("captures", new JSONArray(item.captures));
                b.put("selectedCapture", item.selectedCapture);
                buttons.put(b);
            }
            entry.put("items", buttons);
            array.put(entry);
        }
        return array.toString();
    }

    private void saveRemotes() throws Exception {
        File file = new File(getFilesDir(), "remotes.json");
        File temp = new File(getFilesDir(), "remotes.tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(libraryJson().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!temp.renameTo(file)) {
            throw new IllegalStateException("Cannot replace remote library file.");
        }
    }

    private List<IrplusCodec.Remote> parseLibraryJson(String raw) throws Exception {
        JSONArray rows = new JSONArray(raw);
        if (rows.length() > 100) {
            throw new IllegalArgumentException("Backup exceeds 100 remotes.");
        }
        List<IrplusCodec.Remote> loaded = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject d = rows.getJSONObject(i);
            IrplusCodec.Remote remote = new IrplusCodec.Remote(
                    d.optString("manufacturer", ""), d.optString("model", ""));
            JSONArray items = d.getJSONArray("items");
            if (items.length() > 500) {
                throw new IllegalArgumentException("Backup exceeds 500 buttons per remote.");
            }
            for (int j = 0; j < items.length(); j++) {
                JSONObject b = items.getJSONObject(j);
                int span = b.optInt("span", 6);
                if (span < 1 || span > 12) {
                    throw new IllegalArgumentException("Invalid button width in backup.");
                }
                String pronto = b.optString("prontoHex", "");
                JSONArray captures = b.optJSONArray("captures");
                if (captures == null) {
                    // Seamlessly migrate prior Android versions.
                    remote.items.add(new IrplusCodec.Item(
                            b.optString("label", ""), pronto, span,
                            b.optBoolean("space", false)));
                } else {
                    if (captures.length() > 100) {
                        throw new IllegalArgumentException("Too many captures on a button.");
                    }
                    List<String> signals = new ArrayList<>();
                    for (int k = 0; k < captures.length(); k++) {
                        String sample = captures.optString(k, "");
                        if (!sample.isEmpty()) {
                            IrplusCodec.decodePronto(sample);
                            signals.add(sample);
                        }
                    }
                    remote.items.add(new IrplusCodec.Item(
                            b.optString("label", ""), pronto, span,
                            b.optBoolean("space", false),
                            signals, b.optInt("selectedCapture", signals.size() - 1)));
                }
            }
            loaded.add(remote);
        }
        return loaded;
    }

    private void readSavedRemotes() {
        File file = new File(getFilesDir(), "remotes.json");
        if (!file.exists()) return;
        try (FileInputStream input = new FileInputStream(file)) {
            String data = new String(readLimited(input, 2_000_000),
                    StandardCharsets.UTF_8);
            remotes.addAll(parseLibraryJson(data));
        } catch (Exception error) {
            // Keep the file intact for recovery rather than overwrite it.
            Toast.makeText(this, "Saved remotes could not be read.",
                    Toast.LENGTH_LONG).show();
        }
    }

}
