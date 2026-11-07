package com.mixtervee.irstudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Read-only hardware discovery; deliberately does not claim interfaces or send IR commands. */
public final class UsbHarmonyInspector {
    private UsbHarmonyInspector() {}

    private static String hex(int number) {
        return String.format(Locale.ROOT, "%04X", number & 0xFFFF);
    }

    private static String report(Activity context, UsbManager manager) {
        StringBuilder sb = new StringBuilder();
        sb.append("IR Remote Studio · Android USB discovery\n");
        sb.append("Read-only test: no device writes or commands\n\n");
        boolean supported = context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_USB_HOST);
        sb.append("USB host feature: ").append(supported ? "Yes" : "No").append("\n");
        if (manager == null) {
            sb.append("USB service unavailable.\n");
            return sb.toString();
        }
        Map<String, UsbDevice> map = manager.getDeviceList();
        sb.append("Visible USB devices: ").append(map.size()).append("\n");
        if (map.isEmpty()) {
            sb.append("\nNothing detected. Check batteries, OTG adapter and USB DATA cable.\n");
            return sb.toString();
        }
        List<String> keys = new ArrayList<>(map.keySet());
        Collections.sort(keys);
        int index = 0;
        for (String key : keys) {
            UsbDevice dev = map.get(key);
            if (dev == null) continue;
            sb.append("\nDevice ").append(++index).append("\n");
            sb.append("VID:PID ").append(hex(dev.getVendorId()))
                    .append(":").append(hex(dev.getProductId())).append("\n");
            sb.append(HarmonyUsbIds.identify(dev.getVendorId(), dev.getProductId())).append("\n");
            boolean permission = manager.hasPermission(dev);
            sb.append("USB permission: ").append(permission ? "Granted" : "Not granted").append("\n");
            sb.append("Device class: ").append(dev.getDeviceClass())
                    .append(" / subclass: ").append(dev.getDeviceSubclass())
                    .append(" / protocol: ").append(dev.getDeviceProtocol()).append("\n");
            sb.append("Interfaces: ").append(dev.getInterfaceCount()).append("\n");
            for (int i = 0; i < dev.getInterfaceCount() && i < 16; i++) {
                UsbInterface iface = dev.getInterface(i);
                sb.append("  Interface ").append(i)
                        .append(" class=").append(iface.getInterfaceClass())
                        .append(" subclass=").append(iface.getInterfaceSubclass())
                        .append(" protocol=").append(iface.getInterfaceProtocol())
                        .append(" endpoints=").append(iface.getEndpointCount()).append("\n");
                for (int j = 0; j < iface.getEndpointCount() && j < 16; j++) {
                    UsbEndpoint ep = iface.getEndpoint(j);
                    sb.append("    EP ").append(j)
                            .append(" address=0x").append(hex(ep.getAddress()))
                            .append(" type=").append(ep.getType())
                            .append(" direction=").append(ep.getDirection() == UsbConstants.USB_DIR_IN ? "IN" : "OUT")
                            .append(" maxPacket=").append(ep.getMaxPacketSize()).append("\n");
                }
            }
            if (permission) {
                // Open + read local descriptors only; no claimInterface, controlTransfer,
                // bulkTransfer, HID report writes, or device configuration changes.
                UsbDeviceConnection conn = null;
                try {
                    conn = manager.openDevice(dev);
                    if (conn == null) {
                        sb.append("Open for descriptor inspection: failed\n");
                    } else {
                        byte[] descriptor = conn.getRawDescriptors();
                        sb.append("Open for descriptor inspection: OK");
                        sb.append("; raw descriptor bytes=")
                          .append(descriptor == null ? 0 : descriptor.length).append("\n");
                    }
                } catch (RuntimeException ex) {
                    sb.append("Read-only open failed: ")
                            .append(ex.getClass().getSimpleName()).append("\n");
                } finally {
                    if (conn != null) conn.close();
                }
            }
        }
        return sb.toString();
    }

    public static void show(Activity context) {
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        String text;
        try {
            text = report(context, manager);
        } catch (RuntimeException exception) {
            text = "USB diagnostics unavailable: " + exception.getClass().getSimpleName()
                    + ": " + exception.getMessage();
        }
        final String copyText = text;
        UsbDevice request = null;
        if (manager != null) {
            for (UsbDevice dev : manager.getDeviceList().values()) {
                if (HarmonyUsbIds.isLikelyHarmony(dev.getVendorId(), dev.getProductId())
                        && !manager.hasPermission(dev)) {
                    request = dev;
                    break;
                }
            }
        }
        // A normal AlertDialog with three horizontal action buttons can extend
        // beneath Android's navigation area with large display/font settings.
        // Put the permission action ABOVE a bounded scrollable report instead.
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(context, 14);
        body.setPadding(padding, dp(context, 4), padding, dp(context, 4));

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle("Harmony USB detection")
                .setView(body)
                .setPositiveButton("Close", (d, w) -> {})
                .create();

        if (request != null) {
            UsbDevice target = request;
            Button grant = new Button(context);
            grant.setAllCaps(false);
            grant.setText("Grant Harmony USB access");
            body.addView(grant, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 52)));
            grant.setOnClickListener(v -> {
                dialog.dismiss();
                requestPermission(context, manager, target);
            });
        }

        // Explicit, user-initiated, read-only GET_DESCRIPTOR check.
        UsbDevice accessibleHarmony = null;
        if (manager != null) {
            for (UsbDevice dev : manager.getDeviceList().values()) {
                if (HarmonyUsbIds.isHarmony300Or350(dev.getVendorId(), dev.getProductId())
                        && manager.hasPermission(dev)) {
                    accessibleHarmony = dev;
                    break;
                }
            }
        }
        if (accessibleHarmony != null) {
            final UsbDevice target = accessibleHarmony;
            Button probe = new Button(context);
            probe.setAllCaps(false);
            probe.setText("Test HID interface (read-only)");
            body.addView(probe, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 52)));
            probe.setOnClickListener(v -> {
                probe.setEnabled(false);
                probe.setText("Inspecting HID interface...");
                new Thread(() -> {
                    String result = HarmonyHidProbe.inspect(manager, target);
                    context.runOnUiThread(() -> {
                        if (context.isFinishing() || context.isDestroyed()) return;
                        dialog.dismiss();
                        AlertDialog diag = new AlertDialog.Builder(context)
                                .setTitle("Harmony HID interface results")
                                .setMessage(result)
                                .setPositiveButton("Close", (d, w) -> {})
                                .setNeutralButton("Copy results", (d, w) -> {
                                    ClipboardManager clipboard = (ClipboardManager)
                                            context.getSystemService(Context.CLIPBOARD_SERVICE);
                                    if (clipboard != null) {
                                        clipboard.setPrimaryClip(ClipData.newPlainText(
                                                "Harmony HID results", result));
                                        Toast.makeText(context, "HID report copied",
                                                Toast.LENGTH_SHORT).show();
                                    }
                                }).create();
                        diag.show();
                    });
                }, "HarmonyHidReadOnlyProbe").start();
            });
        }

        // The only Harmony-specific request allowed by this experimental
        // build is GET_VERSION (0x10). It reads identity and never accesses
        // flash, changes setup, resets the remote, or starts IR learning.
        if (accessibleHarmony != null) {
            final UsbDevice identityTarget = accessibleHarmony;
            Button identity = new Button(context);
            identity.setAllCaps(false);
            identity.setText("Read Harmony identity (0x10)");
            body.addView(identity, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 52)));
            identity.setOnClickListener(v -> {
                new AlertDialog.Builder(context)
                    .setTitle("Read Harmony identity")
                    .setMessage("This sends one documented GET_VERSION (0x10) USB "
                         + "HID request to your connected Harmony and waits for "
                         + "its reply. It does not write device settings, update "
                         + "firmware, reset the remote, or start IR learning. "
                         + "Continue?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Read identity", (d, w) -> {
                        identity.setEnabled(false);
                        identity.setText("Reading Harmony identity...");
                        new Thread(() -> {
                            String result = HarmonyIdentityProbe.read(manager, identityTarget);
                            context.runOnUiThread(() -> {
                                if (context.isFinishing() || context.isDestroyed()) return;
                                dialog.dismiss();
                                new AlertDialog.Builder(context)
                                    .setTitle("Harmony identity result")
                                    .setMessage(result)
                                    .setPositiveButton("Close", (d1, w1) -> {})
                                    .setNeutralButton("Copy result", (d1, w1) -> {
                                        ClipboardManager clipboard = (ClipboardManager)
                                                context.getSystemService(
                                                    Context.CLIPBOARD_SERVICE);
                                        if (clipboard != null) {
                                            clipboard.setPrimaryClip(ClipData.newPlainText(
                                                "Harmony identity report", result));
                                            Toast.makeText(context,
                                                "Identity result copied",
                                                Toast.LENGTH_SHORT).show();
                                        }
                                    }).show();
                            });
                        }, "HarmonyIdentityReadOnly").start();
                    }).show();
            });
        }

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(false);
        TextView contents = new TextView(context);
        contents.setText(copyText);
        contents.setTextSize(13);
        contents.setTextIsSelectable(true);
        contents.setGravity(Gravity.TOP | Gravity.START);
        contents.setPadding(dp(context, 6), dp(context, 8),
                dp(context, 6), dp(context, 8));
        scroll.addView(contents);
        int maxReportHeight = Math.min(dp(context, 370),
                (int) (context.getResources().getDisplayMetrics().heightPixels * 0.42));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, maxReportHeight);
        scrollParams.topMargin = dp(context, 6);
        body.addView(scroll, scrollParams);

        Button copy = new Button(context);
        copy.setAllCaps(false);
        copy.setText("Copy full USB report");
        body.addView(copy, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 48)));
        copy.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager)
                    context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText(
                        "Harmony USB report", copyText));
                Toast.makeText(context, "Full USB report copied", Toast.LENGTH_SHORT).show();
            }
        });
        dialog.show();
    }

    private static int dp(Context context, int dp) {
        return Math.round(context.getResources().getDisplayMetrics().density * dp);
    }

    private static void requestPermission(Activity context, UsbManager manager, UsbDevice device) {
        // Explicit receiver + mutable PendingIntent: Android fills the permission
        // result extras, while an explicit component avoids intent hijacking.
        Intent result = new Intent(context, UsbAccessReceiver.class);
        result.setAction(UsbAccessReceiver.ACTION_USB_GRANT);
        PendingIntent pending = PendingIntent.getBroadcast(context, 7, result,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        try {
            manager.requestPermission(device, pending);
        } catch (RuntimeException exception) {
            Toast.makeText(context, "USB permission request failed: "
                    + exception.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
        }
    }
}
