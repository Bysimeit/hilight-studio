package com.hilight.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Engine {
    public static final long FRAME_MS = SafetyGuard.FRAME_MS;

    public static final long FRAME_HEADROOM_MS = 5;

    public static final long ALERT_MAX_MS = 60_000;
    public static final long DEFAULT_AMBIENT_TIMEOUT_MS = 30_000;

    public static final long DUTY_WINDOW_MS = SafetyGuard.DUTY_WINDOW_MS;
    public static final double MAX_DUTY = SafetyGuard.MAX_DUTY;

    public static final long TAPER_AFTER_MS = SafetyGuard.TAPER_AFTER_MS;
    public static final long TAPER_RAMP_MS = SafetyGuard.TAPER_RAMP_MS;
    public static final double TAPER_FLOOR = SafetyGuard.TAPER_FLOOR;

    private final LightsBackend lights = new LightsBackend();
    private final Renderer renderer = new Renderer();
    private SafetyGuard safety = new SafetyGuard();
    private final OutputGate gate = new OutputGate();
    private final Object lock = new Object();
    private final PrivacyScheduler privacyScheduler = new PrivacyScheduler();
    private final Map<String, JSONObject> privacyConfigs = new HashMap<>();
    private final AppOpsWatcher privacyWatcher;

    private Thread thread;
    private volatile boolean running;

    private JSONObject state = new JSONObject();
    private JSONObject alert;
    private long alertId = -1;
    private boolean lastFrameWasAlert;
    private boolean renderingPrivacy;
    private String renderedPrivacyRule;
    private PrivacyScheduler.Phase privacyPhase = PrivacyScheduler.Phase.INACTIVE;
    private long appliedStateRevision;
    private int blankFramesOwed;

    private double dim = 1.0;
    private long ambientTimeoutMs = DEFAULT_AMBIENT_TIMEOUT_MS;
    private volatile long framePeriodMs = FRAME_MS;

    public Engine() {
        privacyWatcher = new AppOpsWatcher(active -> {
            synchronized (lock) {
                privacyScheduler.updateActive(active, android.os.SystemClock.elapsedRealtime());
            }
        });
    }

    public void start() throws Exception {
        lights.connect();
        framePeriodMs = Math.max(FRAME_MS, lights.minUpdatePeriodMs() + FRAME_HEADROOM_MS);
        safety = new SafetyGuard(
                framePeriodMs, DUTY_WINDOW_MS, MAX_DUTY, TAPER_AFTER_MS, TAPER_RAMP_MS, TAPER_FLOOR);
        Log.i("connected: " + lights.ledCount() + " HiLight LEDs"
                + " (min update " + lights.minUpdatePeriodMs() + "ms, driving at " + framePeriodMs + "ms)");
        running = true;
        thread = new Thread(this::loop, "hilight-render");
        thread.setDaemon(false);
        thread.start();
    }

    public void stop() {
        synchronized (lock) {
            running = false;
            privacyWatcher.stop();
            lights.push(new int[]{0});
            lights.closeSession();
        }
    }

    public int ledCount() { return lights.ledCount(); }

    public void setState(String json) {
        JSONObject o;
        try {
            o = new JSONObject(json);
        } catch (Exception e) {
            Log.w("bad state json: " + e);
            return;
        }
        synchronized (lock) {
            state = o;
            readPrivacyRules(o.optJSONArray("privacyRules"));
            if (o.optBoolean("privacyObserverEnabled", false)) {
                privacyWatcher.start();
            } else {
                privacyWatcher.stop();
                privacyScheduler.clearActive();
                privacyPhase = PrivacyScheduler.Phase.INACTIVE;
                renderingPrivacy = false;
                renderedPrivacyRule = null;
            }
            ambientTimeoutMs = Math.max(1_000, o.optLong("ambientTimeoutMs", DEFAULT_AMBIENT_TIMEOUT_MS));
            dim = Math.max(0.02, Math.min(1.0, o.optDouble("dim", 1.0)));

            if (o.optBoolean("arm", false)) gate.armAmbient(System.currentTimeMillis(), ambientTimeoutMs);
            JSONObject a = o.optJSONObject("alert");
            if (a == null) {
                if (alert != null) Log.i("alert cleared");
                alert = null;
                alertId = -1;

                gate.clearAlert();
                renderer.reset();
            } else {
                long id = a.optLong("id", -1);
                if (id != alertId) {
                    alertId = id;
                    alert = a;
                    long asked = a.optLong("durationMs", 4000);

                    long dur = asked <= 0 ? 0 : Math.min(asked, ALERT_MAX_MS);
                    gate.startAlert(System.currentTimeMillis(), dur);
                    renderer.reset();
                    Log.i("alert " + id + " " + a.optString("pattern", "pulse")
                            + (dur <= 0 ? " held until cleared" : " for " + dur + "ms")
                            + (dur > 0 && dur != asked ? " (asked " + asked + ", capped)" : ""));
                }
            }
            appliedStateRevision = o.optLong("stateRevision", appliedStateRevision);
        }
    }

    public String status() {
        JSONObject o = new JSONObject();
        try {
            synchronized (lock) {
                o.put("pid", android.os.Process.myPid());
                o.put("uid", android.os.Process.myUid());
                o.put("ts", System.currentTimeMillis());
                o.put("ledCount", lights.ledCount());
                o.put("session", lights.isSessionOpen());
                o.put("priority", lights.sessionPriority());
                JSONObject amb = state.optJSONObject("ambient");
                o.put("mode", amb == null ? "off" : amb.optString("mode", "off"));
                o.put("alertId", alertId);
                o.put("timeoutMs", ambientTimeoutMs);
                o.put("dim", dim);
                o.put("ambientRemainingMs", gate.ambientRemainingMs(System.currentTimeMillis()));
                o.put("ambientHeld", gate.isAmbientHeld());
                o.put("alertHeld", gate.isAlertHeld());
                o.put("alertOpenEnded", gate.isAlertOpenEnded());
                o.put("resting", safety.isResting());
                o.put("dutyPct", safety.dutyPercent());
                o.put("framePeriodMs", framePeriodMs);
                o.put("appliedStateRevision", appliedStateRevision);
                o.put("privacyObserverEnabled", state.optBoolean("privacyObserverEnabled", false));
                o.put("privacyObserverState", privacyWatcher.state().name().toLowerCase());
                o.put("privacyPhase", privacyPhase.name().toLowerCase());
                o.put("version", 2);
            }
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    private void loop() {
        long due = System.currentTimeMillis();
        while (running) {
            try {
                tick();
                due += framePeriodMs;
                long wait = due - System.currentTimeMillis();
                if (wait < 1) {
                    wait = 1;
                    due = System.currentTimeMillis();
                }
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                Log.w("frame failed: " + t);
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    return;
                }
                due = System.currentTimeMillis();
            }
        }
    }

    private void tick() {
        synchronized (lock) {
            if (!running) return;
            boolean enabled = state.optBoolean("enabled", false);
            boolean privacyOutputEnabled = state.optBoolean("privacyOutputEnabled", false);
            int priority = state.optInt("priority", 0);

            long now = System.currentTimeMillis();
            OutputGate.Layer layer = gate.next(now);
            PrivacyScheduler.Decision privacy =
                    privacyScheduler.decision(android.os.SystemClock.elapsedRealtime());
            privacyPhase = privacy.phase;

            boolean privacyOwnsOutput = privacyOutputEnabled &&
                    (privacy.phase == PrivacyScheduler.Phase.LIT ||
                            privacy.phase == PrivacyScheduler.Phase.COOLDOWN);
            if (!enabled && !privacyOwnsOutput) {
                leavePrivacyRenderer();
                blankAndRelease(now, "released HiLight to the system");
                return;
            }

            if (lastFrameWasAlert && layer != OutputGate.Layer.ALERT) {
                alert = null;
                renderer.reset();

            }
            lastFrameWasAlert = layer == OutputGate.Layer.ALERT;

            // Existing state documents had no source field, so they retain the old alert-first
            // behavior. New foreground holds identify themselves and yield to privacy activity.
            boolean finiteAlert = layer == OutputGate.Layer.ALERT &&
                    !"foreground".equals(alert == null ? "" : alert.optString("source", "legacy"));

            if (!finiteAlert && privacyOutputEnabled &&
                    privacy.phase == PrivacyScheduler.Phase.COOLDOWN) {
                renderingPrivacy = false;
                renderedPrivacyRule = null;
                renderer.reset();
                blankAndRelease(now, "privacy cooldown — released HiLight to the system");
                return;
            }

            JSONObject cfg;
            long t;
            if (!finiteAlert && privacyOutputEnabled && privacy.phase == PrivacyScheduler.Phase.LIT) {
                cfg = privacyConfigs.get(privacy.ruleId);
                if (cfg == null) {
                    renderingPrivacy = false;
                    renderedPrivacyRule = null;
                    return;
                }
                if (!renderingPrivacy || !privacy.ruleId.equals(renderedPrivacyRule)) renderer.reset();
                renderingPrivacy = true;
                renderedPrivacyRule = privacy.ruleId;
                t = privacy.phaseElapsedMs;
            } else switch (layer) {
                case ALERT:
                    leavePrivacyRenderer();
                    cfg = alert;
                    t = gate.alertElapsed(now);
                    break;
                case AMBIENT:
                    leavePrivacyRenderer();
                    cfg = state.optJSONObject("ambient");
                    t = now;
                    break;
                case BLANK:
                default:
                    leavePrivacyRenderer();
                    blankAndRelease(now, "nothing left to show — released HiLight to the system");
                    return;
            }

            if (!lights.isSessionOpen() || priority != lights.sessionPriority()) {
                if (lights.isSessionOpen()) lights.closeSession();
                lights.openSession(priority);
            }
            int[] frame = renderer.frame(cfg, t, Math.max(1, lights.ledCount()));
            lights.push(protect(frame, now));
            blankFramesOwed = OutputGate.BLANK_FRAMES;
        }
    }

    private int[] protect(int[] frame, long now) {
        return safety.apply(frame, now, dim);
    }

    private void noteDark(long now) {
        safety.apply(BLANK, now, dim);
    }

    private void release(String why) {
        if (!lights.isSessionOpen()) return;
        lights.closeSession();
        Log.i(why);
    }

    private void leavePrivacyRenderer() {
        if (renderingPrivacy) renderer.reset();
        renderingPrivacy = false;
        renderedPrivacyRule = null;
    }

    private void blankAndRelease(long now, String why) {
        if (lights.isSessionOpen()) {
            lights.push(protect(BLANK, now));
            if (--blankFramesOwed <= 0) release(why);
        }
        noteDark(now);
    }

    private void readPrivacyRules(JSONArray array) {
        List<PrivacyScheduler.Rule> rules = new ArrayList<>();
        privacyConfigs.clear();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject cfg = array.optJSONObject(i);
                if (cfg == null) continue;
                String id = cfg.optString("id", "");
                PrivacyScheduler.Activity activity = privacyActivity(cfg.optString("activity", ""));
                String pkg = cfg.optString("pkg", PrivacyScheduler.ANY_APP);
                if (id.isEmpty() || activity == null) continue;
                long lightMs = Math.max(1_000, Math.min(60_000, cfg.optLong("lightMs", 10_000)));
                long cooldownMs = Math.max(1_000, Math.min(60_000, cfg.optLong("cooldownMs", 10_000)));
                rules.add(new PrivacyScheduler.Rule(id, activity, pkg, lightMs, cooldownMs));
                privacyConfigs.put(id, cfg);
            }
        }
        privacyScheduler.setRules(rules);
    }

    private static PrivacyScheduler.Activity privacyActivity(String key) {
        if ("microphone".equals(key)) return PrivacyScheduler.Activity.MICROPHONE;
        if ("camera".equals(key)) return PrivacyScheduler.Activity.CAMERA;
        return null;
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static final int[] BLANK = {0xFF000000};
}
