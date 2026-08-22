package com.hilight.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SafetyGuardTest {
    private static final long FRAME = 33;
    private static final long WINDOW = 10 * 60_000;
    private static final double DUTY = 0.5;

    private static final int[] LIT = {0xFF00BFFF};

    private static SafetyGuard guard() {
        return new SafetyGuard(FRAME, WINDOW, DUTY, 10_000, 10_000, 0.55);
    }

    private static long burn(SafetyGuard g, long from, long ms) {
        long t = from;
        for (long spent = 0; spent < ms; spent += FRAME, t += FRAME) g.apply(LIT, t, 1.0);
        return t;
    }

    @Test
    public void aLongCallSpendsMostOfTheWindowBudget() {
        SafetyGuard g = guard();

        burn(g, 0, 191_000);

        assertFalse("a single 3-minute call must not exhaust the array", g.isResting());
        assertEquals(63, g.dutyPercent());
    }

    @Test
    public void twoLongCallsInOneWindowPutTheArrayToRest() {
        SafetyGuard g = guard();
        long t = burn(g, 0, 191_000);

        t = burn(g, t + 60_000, 191_000);

        assertTrue("the duty budget is one window wide, not one call wide", g.isResting());
    }

    @Test
    public void aRestingArrayStaysDarkForLaterAlerts() {
        SafetyGuard g = guard();
        long t = burn(g, 0, 310_000);
        assertTrue(g.isResting());

        int[] frame = g.apply(LIT, t + 60_000, 1.0);

        assertEquals("an alert after the budget is spent renders black", 0, frame[0] & 0xFFFFFF);
    }

    @Test
    public void theBudgetComesBackWhenTheWindowRollsOver() {
        SafetyGuard g = guard();
        burn(g, 0, 310_000);
        assertTrue(g.isResting());

        int[] frame = g.apply(LIT, WINDOW + 1, 1.0);

        assertFalse(g.isResting());
        assertTrue("lit again once the window rolls over", (frame[0] & 0xFFFFFF) != 0);
    }

    @Test
    public void goingDarkIsWhatClearsTheTaper() {
        SafetyGuard g = guard();
        long t = burn(g, 0, 60_000);
        assertTrue("tapered while lit", (g.apply(LIT, t, 1.0)[0] & 0xFF) < 0xFF);

        g.apply(new int[]{0xFF000000}, t + FRAME, 1.0);

        assertEquals("full brightness after a dark frame",
                0x00BFFF, g.apply(LIT, t + 2 * FRAME, 1.0)[0] & 0xFFFFFF);
    }

    @Test
    public void sustainedLightTapersButStaysVisible() {
        SafetyGuard g = guard();
        long t = burn(g, 0, 30_000);

        int[] frame = g.apply(LIT, t, 1.0);

        int blue = frame[0] & 0xFF;
        assertTrue("tapered, not extinguished: " + blue, blue > 0 && blue < 0xFF);
    }
}
