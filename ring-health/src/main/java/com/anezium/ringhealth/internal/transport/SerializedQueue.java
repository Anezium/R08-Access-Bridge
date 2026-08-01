package com.anezium.ringhealth.internal.transport;

import java.util.ArrayDeque;
import java.util.Queue;

public final class SerializedQueue<T> {
    public interface Starter<T> { boolean start(T item); }

    private final Queue<T> queue = new ArrayDeque<>();
    private final Starter<T> starter;
    private T active;

    public SerializedQueue(Starter<T> starter) { this.starter = starter; }

    public synchronized void add(T item) {
        queue.add(item);
        drain();
    }

    public synchronized T active() { return active; }
    public synchronized int pendingCount() { return queue.size(); }

    public synchronized void complete() {
        active = null;
        drain();
    }

    public synchronized void clear() {
        queue.clear();
        active = null;
    }

    private void drain() {
        if (active != null) return;
        while (!queue.isEmpty()) {
            T next = queue.remove();
            active = next;
            if (starter.start(next)) return;
            active = null;
        }
    }
}
