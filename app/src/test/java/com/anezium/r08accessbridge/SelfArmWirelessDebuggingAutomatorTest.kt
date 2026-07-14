package com.anezium.r08accessbridge

import org.junit.Assert.assertEquals
import org.junit.Test

class SelfArmWirelessDebuggingAutomatorTest {
    @Test
    fun repeatedLaterRequestsDoNotPostponePendingStep() {
        var now = 1_000L
        val posted = mutableListOf<Long>()
        var removes = 0
        val scheduler = EarliestDeadlineScheduler(
            now = { now },
            postAt = { posted += it },
            remove = { removes++ },
        )

        scheduler.schedule(450L)
        now += 100L
        scheduler.schedule(450L)
        now += 100L
        scheduler.schedule(450L)

        assertEquals(listOf(1_450L), posted)
        assertEquals(1, removes)
    }

    @Test
    fun earlierRequestAdvancesPendingStep() {
        var now = 1_000L
        val posted = mutableListOf<Long>()
        var removes = 0
        val scheduler = EarliestDeadlineScheduler(
            now = { now },
            postAt = { posted += it },
            remove = { removes++ },
        )

        scheduler.schedule(900L)
        now += 100L
        scheduler.schedule(180L)

        assertEquals(listOf(1_900L, 1_280L), posted)
        assertEquals(2, removes)
    }

    @Test
    fun firedAndCancelAllowAFreshDeadline() {
        var now = 1_000L
        val posted = mutableListOf<Long>()
        var removes = 0
        val scheduler = EarliestDeadlineScheduler(
            now = { now },
            postAt = { posted += it },
            remove = { removes++ },
        )

        scheduler.schedule(200L)
        scheduler.fired()
        now = 1_250L
        scheduler.schedule(300L)
        scheduler.cancel()
        now = 2_000L
        scheduler.schedule(100L)

        assertEquals(listOf(1_200L, 1_550L, 2_100L), posted)
        assertEquals(4, removes)
    }
}
