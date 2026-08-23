package com.hilight.core;

import android.app.AppOpsManager;
import android.content.Context;
import android.os.IBinder;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Watches active camera/microphone AppOps from the privileged renderer process. */
final class AppOpsWatcher {

    interface Listener {
        void onSnapshot(Set<PrivacyScheduler.Use> active);
    }

    enum State { STOPPED, STARTING, ACTIVE, RETRYING }

    private static final String[] OPS = {
            AppOpsManager.OPSTR_RECORD_AUDIO,
            AppOpsManager.OPSTR_CAMERA,
    };
    private static final long RETRY_MIN_MS = 1_000;
    private static final long RETRY_MAX_MS = 30_000;

    private final Listener listener;
    private final Object lock = new Object();

    private ScheduledExecutorService worker;
    private boolean desired;
    private long retryMs = RETRY_MIN_MS;
    private AppOpsManager manager;
    private AppOpsManager.OnOpActiveChangedListener callback;
    private IBinder binder;
    private IBinder.DeathRecipient deathRecipient;
    private volatile State state = State.STOPPED;

    AppOpsWatcher(Listener listener) {
        this.listener = listener;
    }

    State state() {
        return state;
    }

    void start() {
        synchronized (lock) {
            if (desired) return;
            desired = true;
            retryMs = RETRY_MIN_MS;
            worker = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "hilight-appops");
                t.setDaemon(true);
                return t;
            });
            state = State.STARTING;
            worker.execute(this::connect);
        }
    }

    void stop() {
        ScheduledExecutorService toStop;
        synchronized (lock) {
            if (!desired && state == State.STOPPED) return;
            desired = false;
            disconnectLocked();
            state = State.STOPPED;
            toStop = worker;
            worker = null;
        }
        listener.onSnapshot(Collections.emptySet());
        if (toStop != null) toStop.shutdownNow();
    }

    private void connect() {
        synchronized (lock) {
            if (!desired) return;
            disconnectLocked();
            state = State.STARTING;
        }
        try {
            IBinder serviceBinder = serviceBinder();
            AppOpsManager appOps = createManager(serviceBinder);
            AppOpsManager.OnOpActiveChangedListener activeListener =
                    (op, uid, packageName, active) -> requestRefresh();
            IBinder.DeathRecipient died = this::requestReconnect;
            serviceBinder.linkToDeath(died, 0);
            appOps.startWatchingActive(OPS, command -> request(command), activeListener);

            synchronized (lock) {
                if (!desired) {
                    runCatchingStop(appOps, activeListener, serviceBinder, died);
                    return;
                }
                manager = appOps;
                callback = activeListener;
                binder = serviceBinder;
                deathRecipient = died;
                retryMs = RETRY_MIN_MS;
                state = State.ACTIVE;
            }
            refresh();
            Log.i("privacy activity watcher active");
        } catch (Throwable t) {
            retry(t);
        }
    }

    /** Callbacks only invalidate the snapshot; they are not reference counts. */
    private void refresh() {
        AppOpsManager appOps;
        synchronized (lock) {
            if (!desired || manager == null) return;
            appOps = manager;
        }
        try {
            Method getPackages = AppOpsManager.class.getDeclaredMethod(
                    "getPackagesForOps", String[].class);
            getPackages.setAccessible(true);
            Object raw = getPackages.invoke(appOps, (Object) OPS);
            Set<PrivacyScheduler.Use> active = new HashSet<>();
            if (raw instanceof List<?>) {
                for (Object packageOps : (List<?>) raw) readPackage(packageOps, active);
            }
            listener.onSnapshot(Collections.unmodifiableSet(active));
        } catch (Throwable t) {
            retry(t);
        }
    }

    private static void readPackage(Object packageOps, Set<PrivacyScheduler.Use> out) throws Exception {
        Class<?> type = packageOps.getClass();
        String pkg = (String) invoke(type, packageOps, "getPackageName");
        int uid = (Integer) invoke(type, packageOps, "getUid");
        Object entries = invoke(type, packageOps, "getOps");
        if (!(entries instanceof List<?>)) return;
        for (Object entry : (List<?>) entries) {
            Class<?> entryType = entry.getClass();
            boolean running = (Boolean) invoke(entryType, entry, "isRunning");
            if (!running) continue;
            String op = (String) invoke(entryType, entry, "getOpStr");
            PrivacyScheduler.Activity activity = activity(op);
            if (activity != null) out.add(new PrivacyScheduler.Use(activity, uid, pkg));
        }
    }

    private static Object invoke(Class<?> type, Object receiver, String name) throws Exception {
        Method method = type.getMethod(name);
        method.setAccessible(true);
        return method.invoke(receiver);
    }

    private static PrivacyScheduler.Activity activity(String op) {
        if (AppOpsManager.OPSTR_RECORD_AUDIO.equals(op)) return PrivacyScheduler.Activity.MICROPHONE;
        if (AppOpsManager.OPSTR_CAMERA.equals(op)) return PrivacyScheduler.Activity.CAMERA;
        return null;
    }

    private void requestRefresh() {
        request(this::refresh);
    }

    private void requestReconnect() {
        request(() -> retry(new IllegalStateException("appops binder died")));
    }

    private void request(Runnable command) {
        ScheduledExecutorService current;
        synchronized (lock) { current = worker; }
        if (current == null || current.isShutdown()) return;
        try {
            current.execute(command);
        } catch (RuntimeException ignored) {
            // stop() won the race; there is no watcher left to notify
        }
    }

    private void retry(Throwable failure) {
        long delay;
        ScheduledExecutorService current;
        synchronized (lock) {
            if (!desired) return;
            disconnectLocked();
            state = State.RETRYING;
            delay = retryMs;
            retryMs = Math.min(RETRY_MAX_MS, retryMs * 2);
            current = worker;
        }
        listener.onSnapshot(Collections.emptySet());
        Log.w("privacy watcher unavailable (" + rootType(failure) + "), retrying");
        if (current != null && !current.isShutdown()) {
            current.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
        }
    }

    private void disconnectLocked() {
        if (manager != null && callback != null) {
            try { manager.stopWatchingActive(callback); } catch (Throwable ignored) { }
        }
        if (binder != null && deathRecipient != null) {
            try { binder.unlinkToDeath(deathRecipient, 0); } catch (Throwable ignored) { }
        }
        manager = null;
        callback = null;
        binder = null;
        deathRecipient = null;
    }

    private static void runCatchingStop(
            AppOpsManager manager,
            AppOpsManager.OnOpActiveChangedListener callback,
            IBinder binder,
            IBinder.DeathRecipient deathRecipient) {
        try { manager.stopWatchingActive(callback); } catch (Throwable ignored) { }
        try { binder.unlinkToDeath(deathRecipient, 0); } catch (Throwable ignored) { }
    }

    private static IBinder serviceBinder() throws Exception {
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        Method getService = serviceManager.getDeclaredMethod("getService", String.class);
        getService.setAccessible(true);
        IBinder binder = (IBinder) getService.invoke(null, Context.APP_OPS_SERVICE);
        if (binder == null) throw new IllegalStateException("appops service missing");
        return binder;
    }

    private static AppOpsManager createManager(IBinder binder) throws Exception {
        Class<?> stub = Class.forName("com.android.internal.app.IAppOpsService$Stub");
        Method asInterface = stub.getDeclaredMethod("asInterface", IBinder.class);
        asInterface.setAccessible(true);
        Object service = asInterface.invoke(null, binder);

        for (Constructor<?> constructor : AppOpsManager.class.getDeclaredConstructors()) {
            Class<?>[] p = constructor.getParameterTypes();
            if (p.length != 2 || p[0] != Context.class || !p[1].isInstance(service)) continue;
            constructor.setAccessible(true);
            return (AppOpsManager) constructor.newInstance(null, service);
        }
        throw new NoSuchMethodException("AppOpsManager(Context,IAppOpsService)");
    }

    private static String rootType(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null) current = current.getCause();
        return current.getClass().getSimpleName();
    }
}
