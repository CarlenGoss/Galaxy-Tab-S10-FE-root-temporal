package com.lenkosln.tabs10root;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-process state shared between {@link RootService} (producer) and
 * {@link MainActivity} (consumer).  The service runs in the same process as
 * the activity, so a small static holder is enough and avoids binding IPC.
 */
public final class RootState {

    public enum Phase { IDLE, RUNNING, SUCCESS, FAILED }

    public interface Listener {
        void onChanged();
    }

    private static final Object LOCK = new Object();
    private static final StringBuilder LOG = new StringBuilder();
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final int LOG_LIMIT = 200_000;

    public static volatile Phase phase = Phase.IDLE;
    public static volatile String stage = "Listo";
    public static volatile String uid = "—";
    public static volatile String kernelsu = "—";
    public static volatile String selinux = "—";
    public static volatile String error = "";

    private RootState() {}

    public static void reset() {
        synchronized (LOCK) {
            LOG.setLength(0);
        }
        phase = Phase.IDLE;
        stage = "Listo";
        uid = "—";
        kernelsu = "—";
        selinux = "—";
        error = "";
        notifyListeners();
    }

    public static void begin() {
        synchronized (LOCK) {
            LOG.setLength(0);
        }
        phase = Phase.RUNNING;
        stage = "Preparando…";
        uid = "—";
        kernelsu = "—";
        selinux = "—";
        error = "";
        notifyListeners();
    }

    public static void finish(boolean success, String reason) {
        phase = success ? Phase.SUCCESS : Phase.FAILED;
        if (!success) {
            error = reason == null ? "" : reason;
        }
        notifyListeners();
    }

    public static void setStage(String value) {
        if (value == null || value.isEmpty()) return;
        stage = value;
        notifyListeners();
    }

    public static void setUid(String value) {
        uid = value == null || value.isEmpty() ? "—" : value;
        notifyListeners();
    }

    public static void setKernelsu(String value) {
        kernelsu = value == null || value.isEmpty() ? "—" : value;
        notifyListeners();
    }

    public static void setSelinux(String value) {
        selinux = value == null || value.isEmpty() ? "—" : value;
        notifyListeners();
    }

    public static void setError(String value) {
        error = value == null ? "" : value;
        notifyListeners();
    }

    public static void appendLog(String line) {
        if (line == null) return;
        synchronized (LOCK) {
            LOG.append(line).append('\n');
            if (LOG.length() > LOG_LIMIT) {
                LOG.delete(0, LOG.length() - LOG_LIMIT);
            }
        }
        notifyListeners();
    }

    public static String logText() {
        synchronized (LOCK) {
            return LOG.length() == 0 ? "Esperando instrucciones…" : LOG.toString();
        }
    }

    public static boolean logEmpty() {
        synchronized (LOCK) {
            return LOG.length() == 0;
        }
    }

    public static void addListener(Listener listener) {
        if (listener != null && !LISTENERS.contains(listener)) {
            LISTENERS.add(listener);
        }
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    private static void notifyListeners() {
        for (Listener listener : LISTENERS) {
            try {
                listener.onChanged();
            } catch (Throwable ignored) {
                // A broken listener must never break the producer.
            }
        }
    }
}
