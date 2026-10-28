package com.gongyu.eyecare

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.edit
import java.util.UUID

/** Owns rest completion, session resets, and durable timer checkpoints. */
internal class EyeCareStore(context: Context) {
    val prefs: SharedPreferences = context.getSharedPreferences("eye", Context.MODE_PRIVATE)
    val bootCount = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    val revision: Long get() = prefs.getLong("sessionRevision", 0L)
    val enabled: Boolean get() = prefs.getBoolean("enabled", true)
    val pauseUntil: Long get() = prefs.getLong("pauseUntil", 0L)
    val minutes: Int get() = prefs.getInt("minutes", 30).coerceIn(5, 60)

    data class Rest(val id: String, val endElapsed: Long) {
        fun remaining(now: Long) = (endElapsed - now).coerceAtLeast(0L)
    }

    fun rest(now: Long = SystemClock.elapsedRealtime()): Rest? {
        val wallEnd = prefs.getLong("restUntil", 0L)
        if (wallEnd == 0L) return null
        val id = prefs.getString("restId", null) ?: "legacy-$wallEnd"
        val end = if (prefs.getInt("restBoot", -2) == bootCount) {
            prefs.getLong("restEndElapsed", now)
        } else {
            now + (wallEnd - System.currentTimeMillis()).coerceIn(0L, REST_MILLIS)
        }
        return Rest(id, end)
    }

    fun beginRest(requestId: String): Rest? {
        rest()?.let { return it.takeIf { current -> current.id == requestId } }
        // An old notification/restored Activity must never resurrect a finished rest.
        if (prefs.getString("pendingReminderId", null) != requestId) return null
        val now = SystemClock.elapsedRealtime()
        prefs.edit {
            putString("restId", requestId)
            putLong("restUntil", System.currentTimeMillis() + REST_MILLIS)
            putLong("restEndElapsed", now + REST_MILLIS)
            putInt("restBoot", bootCount)
            remove("pendingReminderId")
            resetUsage()
        }
        return Rest(requestId, now + REST_MILLIS)
    }

    fun finishRest(expectedId: String): Boolean {
        if (rest()?.id != expectedId) return false
        prefs.edit {
            clearRest()
            putString("lastFinishedRestId", expectedId)
            resetUsage()
        }
        return true
    }

    fun pendingReminderId(): String {
        prefs.getString("pendingReminderId", null)?.let { return it }
        return UUID.randomUUID().toString().also { id -> prefs.edit { putString("pendingReminderId", id) } }
    }

    fun setEnabled(value: Boolean) {
        prefs.edit {
            putBoolean("enabled", value)
            putString("timerStatus", if (value) "starting" else "disabled")
            remove("pauseUntil")
            remove("pauseLabel")
            clearRest()
            resetUsage()
        }
    }

    fun pause(label: String, until: Long) {
        prefs.edit {
            putBoolean("enabled", true)
            putString("pauseLabel", label)
            putLong("pauseUntil", until)
            clearRest()
            resetUsage()
        }
    }

    fun cancelPause() {
        if (pauseUntil == 0L && enabled) return
        prefs.edit {
            putBoolean("enabled", true)
            remove("pauseUntil")
            remove("pauseLabel")
            resetUsage()
        }
    }

    fun restoreTimer(now: Long): UsageTimer.Snapshot? {
        if (prefs.getInt("timerBoot", -2) != bootCount ||
            prefs.getLong("timerRevision", -1L) != revision ||
            prefs.getLong("timerCheckpoint", Long.MAX_VALUE) > now) return null
        return UsageTimer.Snapshot(
            prefs.getLong("usedMillis", 0L),
            prefs.getLong("inactiveSinceElapsed", -1L).takeIf { it >= 0L }
        )
    }

    fun saveTimer(timer: UsageTimer, now: Long, status: String) {
        val snapshot = timer.snapshot(now)
        prefs.edit {
            putLong("usedMillis", snapshot.usedMillis)
            putLong("usedSeconds", snapshot.usedMillis / 1000L)
            putLong("inactiveSinceElapsed", snapshot.inactiveSince ?: -1L)
            putLong("timerCheckpoint", now)
            putInt("timerBoot", bootCount)
            putLong("timerRevision", revision)
            putLong("serviceHeartbeat", System.currentTimeMillis())
            putString("timerStatus", status)
        }
    }

    private fun SharedPreferences.Editor.clearRest() {
        remove("restId")
        remove("restUntil")
        remove("restEndElapsed")
        remove("restBoot")
        remove("pendingReminderId")
    }

    private fun SharedPreferences.Editor.resetUsage() {
        putLong("sessionRevision", revision + 1L)
        putLong("usedMillis", 0L)
        putLong("usedSeconds", 0L)
        remove("timerRevision")
        remove("pendingReminderId")
    }

    companion object {
        const val REST_MILLIS = 15 * 60_000L
    }
}
