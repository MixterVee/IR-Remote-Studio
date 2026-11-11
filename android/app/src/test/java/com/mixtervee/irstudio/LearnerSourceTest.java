package com.mixtervee.irstudio;

import org.junit.Test;

import static org.junit.Assert.*;

/** Proves the selected hardware, not which device happens to be connected,
 * determines the IR learner used by Android. No hardware writes. */
public class LearnerSourceTest {
    @Test public void selectedU1WithVerifiedConnectionUsesBluetooth() {
        assertEquals(LearnerSource.Route.U1_BLUETOOTH,
                LearnerSource.captureRoute(LearnerSource.SOFABATON_U1, true));
        assertEquals("Connected",
                LearnerSource.connectionLabel(LearnerSource.SOFABATON_U1, true));
    }

    @Test public void disconnectedU1NeverSilentlyFallsBackToHarmonyUsb() {
        assertEquals(LearnerSource.Route.CONNECT_U1_FIRST,
                LearnerSource.captureRoute(LearnerSource.SOFABATON_U1, false));
        assertEquals("Connect U1",
                LearnerSource.connectionLabel(LearnerSource.SOFABATON_U1, false));
    }

    @Test public void harmonySelectionWinsEvenWhenU1RemainsConnected() {
        assertEquals(LearnerSource.Route.HARMONY_USB,
                LearnerSource.captureRoute(LearnerSource.HARMONY_USB, true));
        assertEquals(LearnerSource.Route.HARMONY_USB,
                LearnerSource.captureRoute(LearnerSource.HARMONY_USB, false));
        assertEquals("USB Setup",
                LearnerSource.connectionLabel(LearnerSource.HARMONY_USB, true));
        assertTrue(LearnerSource.learnLabel(LearnerSource.HARMONY_USB)
                .contains("Harmony USB"));
    }

    @Test public void invalidStoredReceiverDefaultsToKnownU1() {
        assertEquals(LearnerSource.SOFABATON_U1, LearnerSource.normalize(-1));
        assertEquals(LearnerSource.SOFABATON_U1, LearnerSource.normalize(9));
        assertEquals(LearnerSource.HARMONY_USB,
                LearnerSource.normalize(LearnerSource.HARMONY_USB));
    }
}
