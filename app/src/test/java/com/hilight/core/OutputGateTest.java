package com.hilight.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class OutputGateTest {
    private static final long TIMEOUT = 30_000;

    @Test
    public void ambientBlanksOnceWhenItsWindowExpires() {
        OutputGate gate = new OutputGate();
        gate.armAmbient(0, TIMEOUT);

        assertEquals(OutputGate.Layer.AMBIENT, gate.next(1_000));
        assertEquals(OutputGate.Layer.BLANK, gate.next(TIMEOUT + 1));

        assertEquals(OutputGate.Layer.IDLE, gate.next(TIMEOUT + 100));
        assertTrue(gate.isAmbientHeld());
    }

    @Test
    public void alertWinsWhileItLastsThenBlanksWhenAmbientIsAlreadyExpired() {
        OutputGate gate = new OutputGate();
        gate.armAmbient(0, TIMEOUT);
        gate.next(TIMEOUT + 1);
        assertEquals(OutputGate.Layer.IDLE, gate.next(TIMEOUT + 100));

        long fired = TIMEOUT + 1_000;
        gate.startAlert(fired, 10_000);
        assertEquals(OutputGate.Layer.ALERT, gate.next(fired));
        assertEquals(OutputGate.Layer.ALERT, gate.next(fired + 9_999));

        assertEquals(OutputGate.Layer.BLANK, gate.next(fired + 10_000));
        assertEquals(OutputGate.Layer.IDLE, gate.next(fired + 10_033));
    }

    @Test
    public void cancellingAnAlertEarlyStillBlanksTheArray() {
        OutputGate gate = new OutputGate();
        gate.armAmbient(0, TIMEOUT);
        gate.next(TIMEOUT + 1);

        long fired = TIMEOUT + 1_000;
        gate.startAlert(fired, 10_000);
        assertEquals(OutputGate.Layer.ALERT, gate.next(fired));

        gate.clearAlert();
        assertFalse(gate.isAlertHeld());
        assertEquals(OutputGate.Layer.BLANK, gate.next(fired + 2_000));
        assertEquals(OutputGate.Layer.IDLE, gate.next(fired + 2_033));
    }

    @Test
    public void anAlertFallsBackToAmbientWhileItsWindowIsStillOpen() {
        OutputGate gate = new OutputGate();
        gate.armAmbient(0, TIMEOUT);

        gate.startAlert(1_000, 5_000);
        assertEquals(OutputGate.Layer.ALERT, gate.next(2_000));
        assertEquals(OutputGate.Layer.AMBIENT, gate.next(6_000));
    }

    @Test
    public void anAlertDoesNotExtendTheAmbientWindow() {
        OutputGate gate = new OutputGate();
        gate.armAmbient(0, TIMEOUT);

        gate.startAlert(TIMEOUT - 1_000, 10_000);
        assertEquals(OutputGate.Layer.ALERT, gate.next(TIMEOUT - 1_000));

        assertEquals(OutputGate.Layer.BLANK, gate.next(TIMEOUT + 9_000));
    }

    @Test
    public void armingAgainReopensTheWindowAndClearsTheLatch() {
        OutputGate gate = new OutputGate();
        gate.armAmbient(0, TIMEOUT);
        gate.next(TIMEOUT + 1);
        assertTrue(gate.isAmbientHeld());

        gate.armAmbient(TIMEOUT + 100, TIMEOUT);
        assertFalse(gate.isAmbientHeld());
        assertEquals(OutputGate.Layer.AMBIENT, gate.next(TIMEOUT + 200));
    }

    @Test
    public void alertElapsedIsMeasuredFromWhenItStarted() {
        OutputGate gate = new OutputGate();
        gate.startAlert(5_000, 10_000);

        assertEquals(0, gate.alertElapsed(5_000));
        assertEquals(2_500, gate.alertElapsed(7_500));
    }

    @Test
    public void anOpenEndedAlertIsHeldUntilItIsCleared() {
        OutputGate gate = new OutputGate();
        gate.startAlert(0, 0);

        assertTrue(gate.isAlertOpenEnded());
        assertEquals(OutputGate.Layer.ALERT, gate.next(0));
        assertEquals(OutputGate.Layer.ALERT, gate.next(TIMEOUT + 1));
        assertEquals(OutputGate.Layer.ALERT, gate.next(10 * 60_000));
    }

    @Test
    public void anOpenEndedAlertOutranksAnExpiringAmbientWindow() {
        OutputGate gate = new OutputGate();
        gate.armAmbient(0, TIMEOUT);
        gate.startAlert(0, 0);

        assertEquals(OutputGate.Layer.ALERT, gate.next(10_000));
        assertEquals(OutputGate.Layer.ALERT, gate.next(TIMEOUT + 15_000));
    }

    @Test
    public void clearingAnOpenEndedAlertHandsTheArrayBack() {
        OutputGate gate = new OutputGate();
        gate.startAlert(0, 0);
        assertEquals(OutputGate.Layer.ALERT, gate.next(TIMEOUT + 30_000));

        gate.clearAlert();

        assertFalse(gate.isAlertHeld());
        assertFalse(gate.isAlertOpenEnded());
        assertEquals(OutputGate.Layer.BLANK, gate.next(TIMEOUT + 30_001));
        assertEquals(OutputGate.Layer.IDLE, gate.next(TIMEOUT + 30_034));
    }

    @Test
    public void clearingAnOpenEndedAlertFallsBackToAStillOpenWindow() {
        OutputGate gate = new OutputGate();
        gate.armAmbient(0, TIMEOUT);
        gate.startAlert(0, 0);
        assertEquals(OutputGate.Layer.ALERT, gate.next(5_000));

        gate.clearAlert();

        assertEquals(OutputGate.Layer.AMBIENT, gate.next(6_000));
    }

    @Test
    public void aTimedAlertReplacesAnOpenEndedOne() {
        OutputGate gate = new OutputGate();
        gate.startAlert(0, 0);
        assertTrue(gate.isAlertOpenEnded());

        gate.startAlert(1_000, 10_000);

        assertFalse(gate.isAlertOpenEnded());
        assertEquals(OutputGate.Layer.ALERT, gate.next(10_999));
        assertEquals(OutputGate.Layer.BLANK, gate.next(11_000));
    }
}
