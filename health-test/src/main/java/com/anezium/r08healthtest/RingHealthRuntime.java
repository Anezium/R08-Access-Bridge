package com.anezium.r08healthtest;

import android.content.Context;

public final class RingHealthRuntime {
    private static volatile DebugRingHealthBackend repository;

    private RingHealthRuntime() {}

    public static DebugRingHealthBackend repository(Context context) {
        if (repository == null) {
            synchronized (RingHealthRuntime.class) {
                if (repository == null) {
                    repository = new DebugRingHealthBackend(context.getApplicationContext());
                }
            }
        }
        return repository;
    }
}
