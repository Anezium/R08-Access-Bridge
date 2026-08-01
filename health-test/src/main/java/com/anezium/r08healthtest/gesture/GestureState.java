package com.anezium.r08healthtest.gesture;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

/** App-local diagnostics state; gesture recognition remains independent from Health. */
public final class GestureState {
    public interface Listener { void onGestureSnapshot(Snapshot snapshot); }

    public record Snapshot(String event, int keyCode, long count, long observedAtEpochMs) {}

    private static final GestureState INSTANCE = new GestureState();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private Snapshot snapshot = new Snapshot("—", -1, 0L, 0L);

    private GestureState() {}

    public static GestureState get() { return INSTANCE; }

    public synchronized void record(String event, int keyCode) {
        snapshot = new Snapshot(event, keyCode, snapshot.count() + 1L,
                System.currentTimeMillis());
        Snapshot next = snapshot;
        main.post(() -> {
            for (Listener listener : listeners) listener.onGestureSnapshot(next);
        });
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
        Snapshot current;
        synchronized (this) { current = snapshot; }
        main.post(() -> listener.onGestureSnapshot(current));
    }

    public void removeListener(Listener listener) { listeners.remove(listener); }
}
