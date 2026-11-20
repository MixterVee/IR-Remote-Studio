package com.mixtervee.irstudio;

/** Explicit capture source selection, independent of connection state. */
public final class LearnerSource {
    public static final int SOFABATON_U1 = 0;
    public static final int HARMONY_USB = 1;
    public static final int OCRUSTAR_USB = 2;

    public enum Route { U1_BLUETOOTH, CONNECT_U1_FIRST, HARMONY_USB, OCRUSTAR_USB }

    private LearnerSource() {}

    public static int normalize(int selected) {
        return selected == HARMONY_USB || selected == OCRUSTAR_USB
                ? selected : SOFABATON_U1;
    }

    /**
     * Never route an explicitly selected but disconnected U1 to Harmony,
     * and never override an explicitly selected Harmony because U1 is online.
     */
    public static Route captureRoute(int selected, boolean u1Connected) {
        if (normalize(selected) == HARMONY_USB) return Route.HARMONY_USB;
        if (normalize(selected) == OCRUSTAR_USB) return Route.OCRUSTAR_USB;
        return u1Connected ? Route.U1_BLUETOOTH : Route.CONNECT_U1_FIRST;
    }

    public static String learnLabel(int selected) {
        if (normalize(selected) == HARMONY_USB) return "◉  Learn IR • Harmony USB";
        if (normalize(selected) == OCRUSTAR_USB) return "◉  Learn IR • Ocrustar USB";
        return "◉  Learn IR • SofaBaton U1";
    }

    /**
     * Two short, actionable instructions for the selected hardware. Keep
     * the other learner's setup details off the main screen.
     */
    public static String connectionHint(int selected, boolean ready) {
        if (normalize(selected) == OCRUSTAR_USB) {
            return ready
                    ? "Ocrustar USB access granted. Select a button and tap Learn."
                    : "Insert your Ocrustar USB-C IR dongle. Tap USB Setup to "
                      + "identify it and grant Android USB access. Close the "
                      + "official Ocrustar app before learning.";
        }
        if (normalize(selected) == HARMONY_USB) {
            return ready
                    ? "Harmony USB ready. Select a button and tap Learn."
                    : "Connect the Harmony 300/350 using a USB-C OTG adapter "
                      + "and a data-capable USB cable. Tap USB Setup and grant access.";
        }
        return ready
                ? "U1 connected. Select a button and tap Learn."
                : "On the U1, hold – and E together to enter Bluetooth pairing. "
                  + "Close the SofaBaton app, then tap Connect U1.";
    }

    public static String connectionLabel(int selected, boolean u1Connected) {
        if (normalize(selected) == HARMONY_USB
                || normalize(selected) == OCRUSTAR_USB) return "USB Setup";
        return u1Connected ? "Connected" : "Connect U1";
    }
}
