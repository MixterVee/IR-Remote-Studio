package com.mixtervee.irstudio;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.util.Locale;

/**
 * Experimental standard USB HID descriptor read: no Harmony commands.
 * A successful claim/descriptor read is a prerequisite to future IR learning.
 */
public final class HarmonyHidProbe {
    private HarmonyHidProbe() {}

    public static String inspect(UsbManager manager, UsbDevice device) {
        StringBuilder log = new StringBuilder("IR Remote Studio — USB HID test\n");
        log.append("No IR capture, feature reports, writes or remote programming\n\n");
        if (manager == null || device == null) return log.append("No connected USB device").toString();
        if (!HarmonyUsbIds.isHarmony300Or350(device.getVendorId(), device.getProductId())) {
            return log.append("Unsupported test target; expected 046D:C124").toString();
        }
        if (!manager.hasPermission(device)) {
            return log.append("USB permission: not granted").toString();
        }
        UsbInterface targetInterface = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface candidate = device.getInterface(i);
            if (candidate.getInterfaceClass() == UsbConstants.USB_CLASS_HID) {
                targetInterface = candidate;
                break;
            }
        }
        if (targetInterface == null) {
            return log.append("No HID interface found").toString();
        }
        log.append("USB permission: Granted\n");
        log.append("HID interface ID: ").append(targetInterface.getId()).append("\n");
        UsbDeviceConnection connection = null;
        boolean claimed = false;
        try {
            connection = manager.openDevice(device);
            if (connection == null) {
                return log.append("openDevice: FAILED").toString();
            }
            log.append("openDevice: OK\n");
            claimed = connection.claimInterface(targetInterface, false);
            log.append("claimInterface(force=false): ")
                    .append(claimed ? "SUCCESS" : "FAILED").append("\n");
            if (!claimed) {
                return log.append("Likely interface already bound to Android HID driver. "
                        + "No forced takeover was attempted.").toString();
            }
            // Standard GET_DESCRIPTOR(Report, type 0x22), device -> host only.
            // RequestType: IN | STANDARD | INTERFACE = 0x81.
            byte[] descriptor = new byte[1024];
            int length = connection.controlTransfer(
                    UsbConstants.USB_DIR_IN | UsbConstants.USB_TYPE_STANDARD
                            | UsbConstants.USB_RECIP_INTERFACE,
                    0x06, 0x2200, targetInterface.getId(),
                    descriptor, descriptor.length, 1500);
            log.append("HID report descriptor request: ");
            if (length <= 0) {
                log.append("UNAVAILABLE (").append(length).append(" bytes)\n");
                log.append("Interface claim worked; some Android HID stacks block descriptor reads.");
            } else {
                log.append("SUCCESS (").append(length).append(" bytes)\n");
                int shown = Math.min(length, 64);
                log.append("First ").append(shown).append(" descriptor bytes (hex):\n");
                for (int i = 0; i < shown; i++) {
                    log.append(String.format(Locale.ROOT, "%02X", descriptor[i] & 0xFF));
                    if (i + 1 < shown) log.append(i % 16 == 15 ? "\n" : " ");
                }
                if (length > shown) log.append("\n... (truncated for display)");
            }
        } catch (SecurityException exception) {
            log.append("USB security error: ").append(exception.getClass().getSimpleName());
        } catch (RuntimeException exception) {
            log.append("USB exception: ").append(exception.getClass().getSimpleName())
                    .append(": ").append(exception.getMessage());
        } finally {
            if (connection != null) {
                if (claimed) {
                    try {
                        connection.releaseInterface(targetInterface);
                        log.append("\nHID interface: released");
                    } catch (RuntimeException ignored) {
                        log.append("\nWarning: releaseInterface failed");
                    }
                }
                connection.close();
                log.append("\nUSB device: closed");
            }
        }
        return log.toString();
    }
}
