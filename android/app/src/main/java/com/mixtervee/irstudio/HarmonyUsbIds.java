package com.mixtervee.irstudio;

/** ID matching is kept pure Java for local regression tests. */
public final class HarmonyUsbIds {
    private HarmonyUsbIds() {}

    public static boolean isLikelyHarmony(int vendor, int product) {
        return (vendor == 0x046D && product >= 0xC110 && product <= 0xC14F)
                || (vendor == 0x0400 && product == 0xC359);
    }

    public static boolean isHarmony300Or350(int vendor, int product) {
        return vendor == 0x046D && product == 0xC124;
    }

    public static String identify(int vendor, int product) {
        if (isHarmony300Or350(vendor, product)) {
            return "Harmony 300/350 USB ID (model not independently verified)";
        }
        if (isLikelyHarmony(vendor, product)) {
            return "Possible Harmony (known Concordance USB ID range)";
        }
        return "Other USB device";
    }
}
