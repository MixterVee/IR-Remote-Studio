package com.mixtervee.irstudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.ConsumerIrManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
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
import java.util.Locale;

/** First Android test build: import, view, export and transmit raw PRONTO_HEX. */
public class MainActivity extends Activity {
    private static final int IMPORT_DEVICE = 1001;
    private static final int EXPORT_DEVICE = 1002;
    private static final int DARK = Color.rgb(9, 18, 34);
    private static final int SURFACE = Color.rgb(24, 40, 64);
    private static final int WHITE = Color.rgb(244, 247, 252);
    private static final int MUTED = Color.rgb(183, 201, 225);
    private static final int BLUE = Color.rgb(23, 103, 190);
    private static final int ORANGE = Color.rgb(241, 147, 29);

    private final List<IrplusCodec.Remote> remotes = new ArrayList<>();
    private ConsumerIrManager irManager;
    private LinearLayout remoteLayout;
    private TextView status;
    private Spinner deviceSpinner;
    private Button exportButton;
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
        titles.addView(text("Android USB lab · v0.1.8-alpha", 12, MUTED, false));
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(header);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(9), 0, dp(9));
        Button importBtn = actionButton("Import .irplus");
        importBtn.setOnClickListener(v -> openImport());
        actions.addView(importBtn, new LinearLayout.LayoutParams(0, dp(50), 1));
        exportButton = actionButton("Export device");
        exportButton.setOnClickListener(v -> openExport());
        LinearLayout.LayoutParams exportParams = new LinearLayout.LayoutParams(0, dp(50), 1);
        exportParams.setMargins(dp(8), 0, 0, 0);
        actions.addView(exportButton, exportParams);
        root.addView(actions);

        // Read-only USB inspection is a prerequisite for future Harmony learning.
        Button usbButton = actionButton("Detect Harmony USB (experimental)");
        usbButton.setOnClickListener(v -> UsbHarmonyInspector.show(this));
        LinearLayout.LayoutParams usbParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        usbParams.setMargins(0, 0, 0, dp(9));
        root.addView(usbButton, usbParams);

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

        TextView footer = text("Commands use the phone's built-in IR transmitter where Android exposes it.",
                11, MUTED, false);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(dp(6), dp(9), dp(6), dp(5));
        root.addView(footer);
    }

    private Button actionButton(String value) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(value);
        b.setTextSize(14);
        b.setTextColor(WHITE);
        b.setBackground(background(BLUE, 9));
        return b;
    }

    private void setStatus(String message) {
        if (status != null) status.setText(message);
    }

    private void refreshDevices() {
        List<String> names = new ArrayList<>();
        for (IrplusCodec.Remote remote : remotes) names.add(remote.displayName());
        if (names.isEmpty()) names.add("No remote loaded — import an .irplus file");
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
            TextView empty = text("Import a remote exported from IR Remote Studio on Windows.\n\n"
                    + "The buttons will appear here in their saved layout.",
                    16, MUTED, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(18), dp(75), dp(18), dp(30));
            remoteLayout.addView(empty);
            return;
        }
        TextView heading = text(remote.displayName(), 19, WHITE, true);
        heading.setGravity(Gravity.CENTER);
        heading.setPadding(dp(5), dp(8), dp(5), dp(13));
        remoteLayout.addView(heading);

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
                b.setOnClickListener(v -> send(item));
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
