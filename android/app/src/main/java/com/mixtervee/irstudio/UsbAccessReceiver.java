package com.mixtervee.irstudio;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbManager;
import android.widget.Toast;

/** Receives the one-time USB grant result; no USB communication occurs here. */
public final class UsbAccessReceiver extends BroadcastReceiver {
    public static final String ACTION_USB_GRANT = "com.mixtervee.irstudio.USB_GRANT";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_USB_GRANT.equals(intent.getAction())) return;
        boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
        Toast.makeText(context, granted
                ? "USB access granted — tap Detect Harmony USB again."
                : "USB access declined.", Toast.LENGTH_LONG).show();
    }
}
