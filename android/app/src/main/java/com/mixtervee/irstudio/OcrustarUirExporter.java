package com.mixtervee.irstudio;

import java.util.ArrayList;
import java.util.List;

/**
 * Data-only, EXPERIMENTAL conversion into the publicly observed ElkSmart
 * UirUploadParam shape. It is NOT a verified file-import format supported
 * by the official Ocrustar app and DOES NOT call any vendor network API.
 *
 * Source: independent APK analysis of com.payne.okux, UirUploadParam:
 *   {"frequency":38000,"irkeys":[
 *       {"keyId":123,"keyName":"power","irData":[9000,4500,...]}]}
 *
 * keyId values below are local sequential placeholders, NOT official IDs.
 * Multiple carrier frequencies cannot safely fit in the documented structure
 * because it has only one top-level frequency.
 */
public final class OcrustarUirExporter {
    private OcrustarUirExporter() {}

    private static final class Key {
        final String label;
        final IrplusCodec.Signal signal;
        Key(String label, IrplusCodec.Signal signal) {
            this.label = label;
            this.signal = signal;
        }
    }

    public static String export(IrplusCodec.Remote remote) {
        if (remote == null) throw new IllegalArgumentException("No remote selected.");
        List<Key> keys = new ArrayList<>();
        int frequency = 0;
        for (IrplusCodec.Item item : remote.items) {
            // Spacers and buttons that have not yet been learned have no IR.
            if (item.space || item.prontoHex == null
                    || item.prontoHex.trim().isEmpty()) continue;
            final IrplusCodec.Signal signal;
            try {
                signal = IrplusCodec.decodePronto(item.prontoHex);
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("Button '" + item.label
                        + "' cannot be converted: " + error.getMessage(), error);
            }
            if (frequency == 0) {
                frequency = signal.carrierHz;
            } else if (Math.abs((long) signal.carrierHz - frequency)
                    > Math.round(frequency * 0.02)) {
                throw new IllegalArgumentException(
                        "This remote has different IR carrier frequencies ("
                        + frequency + " and " + signal.carrierHz + " Hz). "
                        + "The documented experimental Ocrustar UIR record "
                        + "supports only one carrier frequency; no keys were exported.");
            }
            keys.add(new Key(item.label, signal));
            if (keys.size() > 200) {
                throw new IllegalArgumentException("Too many learned buttons (limit 200).");
            }
        }
        if (keys.isEmpty()) {
            throw new IllegalArgumentException(
                    "This remote has no learned IR commands to export.");
        }

        StringBuilder out = new StringBuilder(1024);
        out.append("{\n  \"frequency\": ").append(frequency)
                .append(",\n  \"irkeys\": [\n");
        for (int i = 0; i < keys.size(); i++) {
            Key key = keys.get(i);
            if (i != 0) out.append(",\n");
            // The app's actual keyId mapping is undocumented. Do not claim
            // these placeholders are accepted by Ocrustar.
            out.append("    {\"keyId\": ").append(i + 1)
                    .append(", \"keyName\": ")
                    .append(jsonString(key.label))
                    .append(", \"irData\": [");
            int[] timings = key.signal.microseconds;
            for (int j = 0; j < timings.length; j++) {
                if (j > 0) out.append(", ");
                out.append(timings[j]);
            }
            out.append("]}");
        }
        out.append("\n  ]\n}\n");
        if (out.length() > 1_000_000) {
            throw new IllegalArgumentException("Export data is too large.");
        }
        return out.toString();
    }

    private static String jsonString(String value) {
        StringBuilder out = new StringBuilder("\"");
        String src = value == null ? "" : value;
        for (int i = 0; i < src.length(); i++) {
            char ch = src.charAt(i);
            switch (ch) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (ch < 0x20) {
                        out.append(String.format(java.util.Locale.ROOT,
                                "\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
            }
        }
        return out.append('"').toString();
    }
}
