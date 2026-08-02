package com.anezium.ringhealth;

/** Persistent step total for one local calendar day. */
public record StepDay(
        long id,
        String ringId,
        String localDate,
        int steps,
        int runningSteps,
        int calories,
        int distance,
        int activitySeconds,
        long updatedAtEpochMs) {}
