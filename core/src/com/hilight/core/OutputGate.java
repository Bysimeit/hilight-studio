package com.hilight.core;

final class OutputGate {
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
    }

    void armAmbient(long now, long timeoutMs) {
        ambientDeadline = now + timeoutMs;
        blanked = false;
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

    Layer next(long now) {
        if (alertHeld) {
            if (alertOpenEnded || now < alertEnd) return Layer.ALERT;
            clearAlert();
        }
        if (now > ambientDeadline) {
            if (blanked) return Layer.IDLE;
            blanked = true;
            return Layer.BLANK;
        }
        return Layer.AMBIENT;
    }
}
