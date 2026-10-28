package com.gongyu.eyecare

/** Uses elapsedRealtime so wall-clock changes cannot change usage. */
internal class UsageTimer {
    data class Snapshot(val usedMillis: Long, val inactiveSince: Long?)
    private var activeSince: Long? = null
    private var accumulated = 0L
    private var inactiveSince: Long? = null

    fun reset() {
        activeSince = null
        accumulated = 0L
        inactiveSince = null
    }

    fun restore(snapshot: Snapshot, now: Long) {
        reset()
        accumulated = snapshot.usedMillis.coerceAtLeast(0L)
        inactiveSince = snapshot.inactiveSince?.takeIf { it in 0..now }
        // Do not count an unobserved process-death interval as screen use.
        if (inactiveSince?.let { now - it >= LOCK_RESET_MILLIS } == true) accumulated = 0L
    }

    fun update(now: Long, available: Boolean) {
        if (available) {
            // Check on unlock too; Handler callbacks may not run in deep sleep.
            if (inactiveSince?.let { now - it >= LOCK_RESET_MILLIS } == true) accumulated = 0L
            inactiveSince = null
            if (activeSince == null) activeSince = now
        } else {
            activeSince?.let { accumulated += (now - it).coerceAtLeast(0L) }
            activeSince = null
            if (inactiveSince == null) inactiveSince = now
            if (now - inactiveSince!! >= LOCK_RESET_MILLIS) accumulated = 0L
        }
    }

    fun elapsed(now: Long): Long = accumulated +
        (activeSince?.let { (now - it).coerceAtLeast(0L) } ?: 0L)

    fun snapshot(now: Long) = Snapshot(elapsed(now), inactiveSince)

    // Reset only after the rest page acknowledges the reminder.
    fun isDue(now: Long, interval: Long): Boolean = activeSince != null && elapsed(now) >= interval

    companion object {
        const val LOCK_RESET_MILLIS = 3 * 60_000L
    }
}
