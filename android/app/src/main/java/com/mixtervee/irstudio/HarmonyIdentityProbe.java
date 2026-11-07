package com.mixtervee.irstudio;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.util.Locale;

/**
 * Exact, user-initiated, READ-ONLY Harmony GET_VERSION request (0x10).
 *
 * Sending an interrupt OUT request is a USB write, but the command itself
 * only queries firmware/device identity. No flash/program/reset/IR commands
 * are issued here. Hardware access is restricted to 046D:C124.
 */
public final class HarmonyIdentityProbe {
    private HarmonyIdentityProbe() {}
    private static final int GET_VERSION = 0x10;
    private static final int VERSION_DATA = 0x20;

    static String decodeResponse(byte[] response, int count) {
        if (count <= 0) return "No response bytes received.";
        int header = response[0] & 0xFF;
        int dataLength = header & 0x0F;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT,
            "Response header: 0x%02X, payload length: %d\\n", header, dataLength));
        if ((header & 0xF0) != VERSION_DATA) {
            sb.append("Unexpected response type (expected 0x2x).\\n");
        } else if (dataLength != 5 && dataLength != 7 && dataLength != 8) {
            sb.append("Unexpected version payload length.\\n");
        } else if (count >= dataLength + 1 && count >= 3) {
            int fw = response[1] & 0xFF;
            int hw = response[2] & 0xFF;
            sb.append("Firmware version: ").append(fw >>> 4)
              .append('.').append(fw & 0x0F).append("\\n");
            sb.append("Hardware revision: ").append(hw >>> 4)
              .append('.').append(hw & 0x0F).append("\\n");
            sb.append("Version response format: valid\\n");
        } else {
            sb.append("Short version response.\\n");
        }
        int shown = Math.min(count, 32);
        sb.append("Response bytes (").append(shown).append(" shown): ");
        for (int i = 0; i < shown; i++) {
            sb.append(String.format(Locale.ROOT,"%02X",response[i]&0xFF));
            if (i < shown-1) sb.append(' ');
        }
        if(count>shown)sb.append(" ...");
        return sb.toString();
    }

    public static String read(UsbManager manager, UsbDevice device) {
        StringBuilder log = new StringBuilder();
        log.append("IR Remote Studio — Harmony identity test\\n");
        log.append("Single GET_VERSION (0x10) query; no configuration/firmware writes\\n\\n");
        if (manager == null || device == null) return log.append("No device").toString();
        if (!HarmonyUsbIds.isHarmony300Or350(device.getVendorId(),device.getProductId())) {
            return log.append("Wrong USB ID; request not sent.").toString();
        }
        if (!manager.hasPermission(device)) {
            return log.append("USB permission not granted; request not sent.").toString();
        }

        UsbInterface hid = null;
        UsbEndpoint in = null, out = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface candidate = device.getInterface(i);
            if (candidate.getInterfaceClass() != UsbConstants.USB_CLASS_HID) continue;
            UsbEndpoint candidateIn = null, candidateOut = null;
            for (int e=0;e<candidate.getEndpointCount();e++){
                UsbEndpoint ep = candidate.getEndpoint(e);
                if (ep.getType()!=UsbConstants.USB_ENDPOINT_XFER_INT
                        || ep.getMaxPacketSize()!=64) continue;
                if (ep.getDirection()==UsbConstants.USB_DIR_IN) candidateIn=ep;
                else if (ep.getDirection()==UsbConstants.USB_DIR_OUT) candidateOut=ep;
            }
            if (candidateIn!=null && candidateOut!=null){
                hid=candidate;in=candidateIn;out=candidateOut;break;
            }
        }
        if (hid==null || in==null || out==null) {
            return log.append("No 64-byte input/output HID endpoint pair; request not sent.")
                .toString();
        }
        UsbDeviceConnection connection=null;
        boolean claimed=false;
        try {
            connection=manager.openDevice(device);
            if(connection==null)return log.append("openDevice FAILED").toString();
            log.append("openDevice: OK\\n");
            claimed=connection.claimInterface(hid,false);
            log.append("claimInterface(force=false): ")
               .append(claimed?"SUCCESS":"FAILED").append("\\n");
            if(!claimed)return log.append("USB HID interface unavailable; nothing sent.").toString();

            byte[] request=new byte[64];
            request[0]=(byte)GET_VERSION;
            // This is a protocol query: byte 0 = 0x10, remaining bytes = 0.
            // Do not send any other Harmony command in this diagnostic.
            log.append("Sending single 64-byte GET_VERSION (0x10) HID OUT report...\\n");
            int written=connection.bulkTransfer(out,request,request.length,1800);
            log.append("OUT bytes sent: ").append(written).append("\\n");
            if(written!=64){
                return log.append("GET_VERSION request incomplete; stopping.").toString();
            }
            byte[] response=new byte[64];
            int got=connection.bulkTransfer(in,response,response.length,2200);
            log.append("IN bytes received: ").append(got).append("\\n");
            if(got<0)return log.append("No version response (timeout/error).").toString();
            log.append(decodeResponse(response,got));
        } catch(SecurityException error){
            log.append("USB permission error: ").append(error.getClass().getSimpleName());
        } catch(RuntimeException error){
            log.append("USB transfer error: ").append(error.getClass().getSimpleName())
                .append(": ").append(error.getMessage());
        } finally {
            if(connection!=null){
                if(claimed){
                    try{connection.releaseInterface(hid);log.append("\\nHID interface released");}
                    catch(RuntimeException ignored){log.append("\\nWarning: interface release failed");}
                }
                connection.close();
                log.append("\\nUSB device closed");
            }
        }
        return log.toString();
    }
}
