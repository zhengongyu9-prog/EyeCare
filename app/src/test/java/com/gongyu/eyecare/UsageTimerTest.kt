package com.gongyu.eyecare

import org.junit.Assert.*
import org.junit.Test

class UsageTimerTest {
    @Test fun nextRoundStartsImmediatelyAfterResetAndRepeats() {
        val timer = UsageTimer()
        var now = 1_000L
        repeat(3) {
            timer.update(now, true)
            assertFalse(timer.isDue(now + 299_999L, 300_000L))
            assertTrue(timer.isDue(now + 300_000L, 300_000L))
            timer.reset()
            now += 300_000L
            timer.update(now, true)
            assertEquals(1_000L, timer.elapsed(now + 1_000L))
        }
    }

    @Test fun shortLockPreservesUsageButDoesNotCountLockedTime() {
        val timer = UsageTimer()
        timer.update(0L, true)
        timer.update(600_000L, false)
        timer.update(720_000L, true)
        assertEquals(600_000L, timer.elapsed(720_000L))
        assertEquals(601_000L, timer.elapsed(721_000L))
    }

    @Test fun rapidOffOnBetweenTicksStillPauses() {
        val timer = UsageTimer()
        timer.update(0L, true)
        timer.update(50_000L, false)
        timer.update(51_000L, true)
        assertEquals(59_000L, timer.elapsed(60_000L))
    }

    @Test fun threeMinuteLockResetsEvenWithoutASingleSleepingTick() {
        val timer = UsageTimer()
        timer.update(0L, true)
        timer.update(600_000L, false)
        timer.update(780_000L, true)
        assertEquals(0L, timer.elapsed(780_000L))
        assertEquals(1_000L, timer.elapsed(781_000L))
    }

    @Test fun justUnderThreeMinutesDoesNotReset() {
        val timer = UsageTimer()
        timer.update(0L, true)
        timer.update(600_000L, false)
        timer.update(779_999L, true)
        assertEquals(600_000L, timer.elapsed(779_999L))
    }

    @Test fun checkpointRestoresUsageWithoutAddingUnknownDowntime() {
        val first = UsageTimer()
        first.update(0L, true)
        val restored = UsageTimer()
        restored.restore(first.snapshot(600_000L), 900_000L)
        restored.update(900_000L, true)
        assertEquals(600_000L, restored.elapsed(900_000L))
        assertEquals(601_000L, restored.elapsed(901_000L))
    }

    @Test fun checkpointPreservesShortLockAcrossServiceRestart() {
        val first = UsageTimer()
        first.update(0L, true)
        first.update(600_000L, false)
        val restored = UsageTimer()
        restored.restore(first.snapshot(620_000L), 630_000L)
        restored.update(640_000L, true)
        assertEquals(600_000L, restored.elapsed(640_000L))
    }

    @Test fun checkpointDetectsLongLockAcrossServiceRestart() {
        val timer = UsageTimer()
        timer.restore(UsageTimer.Snapshot(600_000L, 600_000L), 800_000L)
        timer.update(800_000L, true)
        assertEquals(0L, timer.elapsed(800_000L))
    }

    @Test fun changingThirtyToFifteenMinutesKeepsTenAlreadyUsed() {
        val timer = UsageTimer()
        timer.update(0L, true)
        assertFalse(timer.isDue(600_000L, 30 * 60_000L))
        assertFalse(timer.isDue(600_000L, 15 * 60_000L))
        assertTrue(timer.isDue(900_000L, 15 * 60_000L))
    }

    @Test fun blockedReminderDoesNotEraseTheDueUsage() {
        val timer = UsageTimer()
        timer.update(0L, true)
        assertTrue(timer.isDue(300_000L, 300_000L))
        assertTrue(timer.isDue(330_000L, 300_000L))
        assertEquals(330_000L, timer.elapsed(330_000L))
    }

    @Test fun repeatedServiceStartsDoNotResetActiveUsage() {
        val timer = UsageTimer()
        timer.update(0L, true)
        timer.update(10_000L, true)
        timer.update(20_000L, true)
        assertEquals(30_000L, timer.elapsed(30_000L))
    }
}
