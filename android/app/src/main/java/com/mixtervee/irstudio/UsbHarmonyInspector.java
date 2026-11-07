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
                try (UsbDeviceConnection conn = manager.openDevice(dev)) {
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
        AlertDialog.Builder dialog = new AlertDialog.Builder(context)
                .setTitle("Harmony USB detection")
                .setMessage(text)
                .setPositiveButton("Close", (d, w) -> {})
                .setNeutralButton("Copy report", (d, w) -> {
                    ClipboardManager clipboard = (ClipboardManager)
                            context.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("Harmony USB report", copyText));
                        Toast.makeText(context, "USB report copied", Toast.LENGTH_SHORT).show();
                    }
                });
        if (request != null) {
            UsbDevice selected = request;
            dialog.setNegativeButton("Request USB access", (d, w) -> requestPermission(context, manager, selected));
        }
        dialog.show();
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
