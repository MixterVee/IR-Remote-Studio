package com.mixtervee.irstudio;

import java.util.List;
import java.util.Locale;

/**
 * Local-only experimental conversion of one MH IR frame to raw Pronto 0000.
 * No device I/O, file writes, or IR transmission.
 */
final class HarmonyProntoConverter {
    private HarmonyProntoConverter() {}

    /**
     * Concordance MH IR stream:
     *   word 0: ignored synchronization word
     *   word 1: first mark time (microseconds)
     *   word 2: carrier cycles in that first mark
     *   word 3: first total mark+space period
     *   word 4: second mark, word 5: second total period, ...
     *
     * repeatStart is the index of the first mark in the next frame,
     * detected only after a sufficiently long trailing space.
     * A repeated header is evidence, not a guarantee, of a complete frame.
     */
    static String preview(List<Integer> words, int repeatStart) {
        if (words == null || repeatStart < 26 || repeatStart > words.size() - 2
                || (repeatStart & 1) != 0) {
            throw new IllegalArgumentException("No bounded complete-looking frame");
        }
        int firstMarkUs = words.get(1);
        int carrierCycles = words.get(2);
        if (firstMarkUs < 100 || firstMarkUs > 20000
                || carrierCycles < 1 || carrierCycles > 5000) {
            throw new IllegalArgumentException("Missing valid IR carrier header");
        }
        double measuredHz = carrierCycles * 1_000_000.0 / firstMarkUs;
        if (measuredHz < 18000 || measuredHz > 80000)
            throw new IllegalArgumentException("IR carrier estimate out of range");
        int frequencyWord = (int) Math.round(1_000_000.0 /
                (measuredHz * 0.241246));
        if (frequencyWord < 1 || frequencyWord > 65535)
            throw new IllegalArgumentException("Pronto frequency divider invalid");

        // Use Pronto's quantized carrier for conversion to cycle counts.
        double encodedHz = 1_000_000.0 / (frequencyWord * 0.241246);
        int pairCount = (repeatStart - 2) / 2;
        if (pairCount < 12 || pairCount > 200)
            throw new IllegalArgumentException("Unexpected number of IR timing pairs");

        StringBuilder result = new StringBuilder("0000");
        appendHex(result, frequencyWord);
        appendHex(result, pairCount);
        appendHex(result, 0);  // No separate repeat sequence; one-time frame preview.
        long durationUs = 0;
        int markUs = firstMarkUs;
        for (int periodIndex = 3; periodIndex < repeatStart; periodIndex += 2) {
            int periodUs = words.get(periodIndex);
            int spaceUs = periodUs - markUs;
            if (markUs < 50 || markUs > 100000 || spaceUs < 50 || spaceUs > 100000)
                throw new IllegalArgumentException("Invalid mark/space period");
            durationUs += (long) markUs + spaceUs;
            if (durationUs >= 2_000_000)
                throw new IllegalArgumentException("IR waveform exceeds two seconds");
            int markCycles = (int) Math.round(markUs * encodedHz / 1_000_000.0);
            int spaceCycles = (int) Math.round(spaceUs * encodedHz / 1_000_000.0);
            if (markCycles < 1 || spaceCycles < 1
                    || markCycles > 65535 || spaceCycles > 65535)
                throw new IllegalArgumentException("IR waveform cannot fit Pronto");
            appendHex(result, markCycles);
            appendHex(result, spaceCycles);

            // The last period ends before the next frame's starting mark.
            if (periodIndex + 1 < repeatStart)
                markUs = words.get(periodIndex + 1);
        }
        // Verify compatibility with this app's own import/decoding logic.
        IrplusCodec.Signal validation = IrplusCodec.decodePronto(result.toString());
        if (validation.microseconds.length != pairCount * 2)
            throw new IllegalArgumentException("Pronto decoder rejected pair count");
        return result.toString();
    }

    private static void appendHex(StringBuilder result, int word) {
        result.append(' ').append(String.format(Locale.ROOT, "%04X", word));
    }
}
