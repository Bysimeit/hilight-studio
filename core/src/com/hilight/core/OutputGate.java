package com.hilight.core;

final class OutputGate {
    static final int BLANK_FRAMES = 3;

    enum Layer {
        ALERT,

        AMBIENT,

        BLANK,

        IDLE,
    }

    private long alertStart;
    private long alertEnd;
    private boolean alertHeld;
    private boolean alertOpenEnded;

    private long ambientDeadline;

    private boolean blanked;
    private int blankFramesLeft = BLANK_FRAMES;

    void startAlert(long now, long durationMs) {
        alertHeld = true;
        alertOpenEnded = durationMs <= 0;
        alertStart = now;
        alertEnd = now + durationMs;
    }

    void clearAlert() {
        if (!alertHeld) return;
        alertHeld = false;
        alertOpenEnded = false;
        blanked = false;
        blankFramesLeft = BLANK_FRAMES;
    }

    void armAmbient(long now, long timeoutMs) {
        ambientDeadline = now + timeoutMs;
        blanked = false;
        blankFramesLeft = BLANK_FRAMES;
    }

    boolean isAlertHeld() {
        return alertHeld;
    }

    boolean isAlertOpenEnded() {
        return alertOpenEnded;
    }

    long alertElapsed(long now) {
        return now - alertStart;
    }

    long ambientRemainingMs(long now) {
        return Math.max(0, ambientDeadline - now);
    }

    boolean isAmbientHeld() {
        return blanked;
    }

    boolean isBlankingDone() {
        return blanked;
    }

    Layer next(long now) {
        if (alertHeld) {
            if (alertOpenEnded || now < alertEnd) return Layer.ALERT;
            clearAlert();
        }
        if (now > ambientDeadline) {
            if (blanked) return Layer.IDLE;
            if (--blankFramesLeft <= 0) blanked = true;
            return Layer.BLANK;
        }
        return Layer.AMBIENT;
    }
}
