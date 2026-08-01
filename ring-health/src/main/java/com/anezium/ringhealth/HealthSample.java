package com.anezium.ringhealth;

import com.anezium.ringhealth.domain.HealthMetric;

/** Immutable health value exposed to host applications. */
public record HealthSample(
        long id,
        String ringId,
        HealthMetric metric,
        Source source,
        long observedAtEpochMs,
        double value,
        Integer rawValue,
        Integer dayIndex,
        Integer intervalMinutes) {

    public enum Source { MANUAL, INTERVAL }
}
