package com.mixtervee.irstudio;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class HarmonyProntoConverterTest {
    // Actual captured Harmony 300 (arch 0x10) sample from v0.1.17-alpha.
    // Word 54 is the first burst of a repeated frame.
    private static List<Integer> sample() {
        return new ArrayList<>(Arrays.asList(
                9, 4007, 153, 7996, 482, 2472, 482, 2472, 483, 2472,
                483, 2473, 482, 1499, 482, 1499, 482, 2472, 483, 1499,
                483, 1499, 483, 2473, 482, 2472, 482, 2472, 483, 1499,
                483, 1499, 483, 1499, 483, 1500, 482, 2472, 482, 2472,
                483, 1499, 483, 2472, 483, 2473, 482, 1499, 482, 1499,
                482, 1499, 483, 8771, 4007, 7996, 482
        ));
    }

    @Test public void sampleWaveformMakesDecodableOneShotPronto() {
        List<Integer> words = sample();
        assertEquals(57, words.size());
        assertEquals(54, HarmonyIrCaptureProbe.firstRepeatBoundary(words));
        String pronto = HarmonyProntoConverter.preview(words, 54);
        assertTrue(pronto.startsWith("0000 006D 001A 0000 "));
        assertEquals(56, pronto.split(" ").length);
        assertTrue(pronto.endsWith("0012 013B"));

        IrplusCodec.Signal decoded = IrplusCodec.decodePronto(pronto);
        assertEquals(52, decoded.microseconds.length);
        assertTrue(Math.abs(decoded.carrierHz - 38183) < 300);
        assertTrue(Math.abs(decoded.microseconds[0] - 4007) <= 20);
        assertTrue(Math.abs(decoded.microseconds[1] - 3989) <= 20);
        assertTrue(Math.abs(decoded.microseconds[2] - 482) <= 20);
        assertTrue(Math.abs(decoded.microseconds[3] - 1990) <= 20);
        assertTrue(Math.abs(decoded.microseconds[51] - 8288) <= 20);
    }

    @Test(expected = IllegalArgumentException.class)
    public void neverExportsWhenNoRepeatBoundaryIsKnown() {
        HarmonyProntoConverter.preview(sample(), -1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnalignedRepeatIndex() {
        HarmonyProntoConverter.preview(sample(), 53);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidMarkOrSpaceTiming() {
        List<Integer> words = sample();
        words.set(3, 3500); // shorter than the first 4007us mark.
        HarmonyProntoConverter.preview(words, 54);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnsupportedCarrierFrequency() {
        List<Integer> words = sample();
        words.set(2, 1); // 250 Hz instead of ~38 kHz.
        HarmonyProntoConverter.preview(words, 54);
    }

    @Test public void ignoresWordsInSecondRepeatedFrame() {
        List<Integer> words = sample();
        String first = HarmonyProntoConverter.preview(words, 54);
        words.set(54, 1000);
        words.set(55, 1900);
        words.set(56, 150);
        assertEquals(first, HarmonyProntoConverter.preview(words, 54));
    }
}
