package com.mixtervee.irstudio;

import android.app.Activity;
import android.app.AlertDialog;
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
    private Button exportButton;
    private Button learnButton;
    private int selectedDevice = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        irManager = (ConsumerIrManager) getSystemService(CONSUMER_IR_SERVICE);
        readSavedRemotes();
        makeUi();
        refreshDevices();
        String availability = irManager != null && irManager.hasIrEmitter()
                ? "Android IR emitter detected. Import a .irplus remote and select a button."
                : "Android's public IR emitter API is unavailable. Import/export still works; transmission requires a supported phone.";
        setStatus(availability);
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

    private void makeUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(DARK);
        root.setPadding(dp(13), dp(10), dp(13), dp(8));
        setContentView(root);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ir_remote_studio);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        header.addView(logo, new LinearLayout.LayoutParams(dp(62), dp(62)));
        LinearLayout titles = new LinearLayout(this);
        titles.setPadding(dp(10), 0, 0, 0);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text("IR Remote Studio", 22, WHITE, true));
        titles.addView(text("LEARN  ·  ORGANIZE  ·  REPLAY", 11, MUTED, true));
        titles.addView(text("Android " + BuildConfig.VERSION_NAME + "  •  Harmony USB learner", 11, MUTED, false));
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(header);

        LinearLayout quickActions = new LinearLayout(this);
        quickActions.setOrientation(LinearLayout.HORIZONTAL);
        quickActions.setPadding(0, dp(10), 0, dp(3));
        learnButton = actionButton("◉  Learn IR Button", TEAL);
        learnButton.setOnClickListener(v -> startLearning());
        quickActions.addView(learnButton, new LinearLayout.LayoutParams(0, dp(54), 2));
        Button createRemote = actionButton("+  New Remote", PURPLE);
        createRemote.setOnClickListener(v -> createRemote());
        LinearLayout.LayoutParams newParams = new LinearLayout.LayoutParams(0, dp(54), 1);
        newParams.setMargins(dp(8), 0, 0, 0);
        quickActions.addView(createRemote, newParams);
        root.addView(quickActions);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(9), 0, dp(9));
        Button importBtn = actionButton("↓ Import .irplus");
        importBtn.setOnClickListener(v -> openImport());
        actions.addView(importBtn, new LinearLayout.LayoutParams(0, dp(50), 1));
        exportButton = actionButton("↑ Export Remote");
        exportButton.setOnClickListener(v -> openExport());
        LinearLayout.LayoutParams exportParams = new LinearLayout.LayoutParams(0, dp(50), 1);
        exportParams.setMargins(dp(8), 0, 0, 0);
        actions.addView(exportButton, exportParams);
        root.addView(actions);

        LinearLayout editActions = new LinearLayout(this);
        editActions.setOrientation(LinearLayout.HORIZONTAL);
        Button addButton = actionButton("+ Add Button", PURPLE);
        addButton.setOnClickListener(v -> createButton());
        editActions.addView(addButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button layoutBtn = actionButton("Layout Editor...", Color.rgb(37, 87, 126));
        layoutBtn.setOnClickListener(v -> showLayoutEditor());
        LinearLayout.LayoutParams layoutParams =
                new LinearLayout.LayoutParams(0, dp(48), 1);
        layoutParams.setMargins(dp(8), 0, 0, 0);
        editActions.addView(layoutBtn, layoutParams);
        root.addView(editActions);

        // USB Harmony diagnostic access remains separate from learning.
        Button usbButton = actionButton("Harmony USB • Connect / Troubleshoot", Color.rgb(31, 66, 106));
        usbButton.setOnClickListener(v -> UsbHarmonyInspector.show(this));
        LinearLayout.LayoutParams usbParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        usbParams.setMargins(0, 0, 0, dp(9));
        root.addView(usbButton, usbParams);

        Button about = actionButton("About / Version", Color.rgb(37, 60, 90));
        about.setOnClickListener(v -> showAbout());
        LinearLayout.LayoutParams aboutParams =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        aboutParams.setMargins(0, 0, 0, dp(7));
        root.addView(about, aboutParams);

        TextView section = text("MY REMOTES   •   LONG-PRESS TO LEARN OR MANAGE", 11, MUTED, true);
        section.setPadding(dp(5), dp(4), dp(5), dp(9));
        root.addView(section);
        deviceSpinner = new Spinner(this);
        deviceSpinner.setBackground(background(SURFACE, 8));
        deviceSpinner.setPadding(dp(10), dp(6), dp(10), dp(6));
        deviceSpinner.setMinimumHeight(dp(50));
        root.addView(deviceSpinner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
        deviceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                selectedDevice = pos;
                renderRemote();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        status = text("", 12, MUTED, false);
        status.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams statParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statParams.setMargins(0, dp(10), 0, dp(8));
        status.setBackground(background(SURFACE, 9));
        root.addView(status, statParams);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(true);
        remoteLayout = new LinearLayout(this);
        remoteLayout.setOrientation(LinearLayout.VERTICAL);
        remoteLayout.setPadding(dp(3), dp(9), dp(3), dp(15));
        scroll.addView(remoteLayout, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        TextView footer = text("Valid IR captures save locally. The Harmony's programming is unchanged.",
                11, MUTED, false);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(dp(6), dp(9), dp(6), dp(5));
        root.addView(footer);
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
        exportButton.setEnabled(!remotes.isEmpty());
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
            TextView empty = text("Your library is empty.\n\nCreate a remote, add named buttons, then capture IR commands. You can also import .irplus files.",
                    16, MUTED, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(18), dp(75), dp(18), dp(30));
            remoteLayout.addView(empty);
            return;
        }
        TextView heading = text(remote.displayName(), 19, WHITE, true);
        heading.setGravity(Gravity.CENTER);
        heading.setPadding(dp(5), dp(8), dp(5), dp(13));
        heading.setOnLongClickListener(v -> {
            manageRemote(remote);
            return true;
        });
        remoteLayout.addView(heading);
        TextView hint = text("Long-press remote name to rename or delete", 11, MUTED, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, 0, 0, dp(8));
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

    /** The phone hosts the learning workflow. Harmony never receives a configuration write. */
    private void startLearning() {
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
                            } else {
                                showCapturePreview(pronto, report);
                            }
                        });
                    }, "HarmonyLearnAndPreview").start();
                }).show();
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

        Button saveAndLearnAnother = actionButton(
                "Save & Learn Another Button", PURPLE);
        LinearLayout.LayoutParams nextParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(49));
        nextParams.setMargins(0, dp(10), 0, 0);
        content.addView(saveAndLearnAnother, nextParams);

        TextView note = text("Save the current command before starting a new capture. "
                + "Harmony programming unchanged.", 11, MUTED, false);
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
        saveAndLearnAnother.setOnClickListener(v -> {
            // Never discard the current capture: retry validation in the same
            // local persistence path as the existing Save button.
            if (saveLearnedButton(pronto,
                    buttonName.getText().toString().trim(),
                    targetSpinner.getSelectedItemPosition(),
                    remoteName.getText().toString().trim(),
                    buttonName, remoteName)) {
                dialog.dismiss();
                // Previous capture's STOP/DONE and CLOSE were already
                // verified. Starting another capture requires explicit
                // confirmation in the normal learning flow.
                startLearning();
            }
        });
        dialog.setOnShowListener(v ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w -> {
                    if (saveLearnedButton(pronto,
                            buttonName.getText().toString().trim(),
                            targetSpinner.getSelectedItemPosition(),
                            remoteName.getText().toString().trim(),
                            buttonName, remoteName)) {
                        dialog.dismiss();
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
            setStatus("Saved " + label + " to " + target.displayName()
                    + ". Long-press buttons to rename or delete. Export to back up.");
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
        new AlertDialog.Builder(this)
                .setTitle(item.label)
                .setItems(new String[]{"Rename button", "Delete button"}, (d, choice) -> {
                    if (choice == 0) renameButton(remote, item);
                    else deleteButton(remote, item);
                }).show();
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
                    remote.items.set(index, new IrplusCodec.Item(
                            name, item.prontoHex, item.span, false));
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

    private void saveRemotes() throws Exception {
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
                buttons.put(b);
            }
            entry.put("items", buttons);
            array.put(entry);
        }
        File file = new File(getFilesDir(), "remotes.json");
        File temp = new File(getFilesDir(), "remotes.tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(array.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!temp.renameTo(file)) {
            throw new IllegalStateException("Cannot replace remote library file.");
        }
    }

    private void readSavedRemotes() {
        File file = new File(getFilesDir(), "remotes.json");
        if (!file.exists()) return;
        try (FileInputStream in = new FileInputStream(file)) {
            String data = new String(readLimited(in, 2_000_000), StandardCharsets.UTF_8);
            JSONArray rows = new JSONArray(data);
            for (int i = 0; i < rows.length() && i < 100; i++) {
                JSONObject d = rows.getJSONObject(i);
                IrplusCodec.Remote r = new IrplusCodec.Remote(
                        d.optString("manufacturer", ""), d.optString("model", ""));
                JSONArray items = d.getJSONArray("items");
                for (int j = 0; j < items.length() && j < 500; j++) {
                    JSONObject b = items.getJSONObject(j);
                    int span = b.optInt("span", 6);
                    if (span < 1 || span > 12) continue;
                    r.items.add(new IrplusCodec.Item(
                            b.optString("label", ""), b.optString("prontoHex", ""),
                            span, b.optBoolean("space", false)));
                }
                remotes.add(r);
            }
        } catch (Exception exception) {
            // Keep existing files intact for recovery instead of overwriting on startup.
            Toast.makeText(this, "Saved remotes could not be read.", Toast.LENGTH_LONG).show();
        }
    }
}
