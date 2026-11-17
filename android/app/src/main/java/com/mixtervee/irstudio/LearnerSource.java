package com.mixtervee.irstudio;

/** Explicit capture source selection, independent of connection state. */
public final class LearnerSource {
    public static final int SOFABATON_U1 = 0;
    public static final int HARMONY_USB = 1;

    public enum Route { U1_BLUETOOTH, CONNECT_U1_FIRST, HARMONY_USB }

    private LearnerSource() {}

    public static int normalize(int selected) {
        return selected == HARMONY_USB ? HARMONY_USB : SOFABATON_U1;
    }

    /**
     * Never route an explicitly selected but disconnected U1 to Harmony,
     * and never override an explicitly selected Harmony because U1 is online.
     */
    public static Route captureRoute(int selected, boolean u1Connected) {
        if (normalize(selected) == HARMONY_USB) return Route.HARMONY_USB;
        return u1Connected ? Route.U1_BLUETOOTH : Route.CONNECT_U1_FIRST;
    }

    public static String learnLabel(int selected) {
        return normalize(selected) == HARMONY_USB
                ? "◉  Learn IR • Harmony USB"
                : "◉  Learn IR • SofaBaton U1";
    }

    /**
     * Two short, actionable instructions for the selected hardware. Keep
     * the other learner's setup details off the main screen.
     */
    public static String connectionHint(int selected, boolean ready) {
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
        if (normalize(selected) == HARMONY_USB) return "USB Setup";
        return u1Connected ? "Connected" : "Connect U1";
    }
}
