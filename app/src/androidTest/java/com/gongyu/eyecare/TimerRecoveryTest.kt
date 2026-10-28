package com.gongyu.eyecare

import android.content.Context
import android.content.Intent
import android.app.ActivityManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real service and the real emergency-exit button on the phone. */
@RunWith(AndroidJUnit4::class)
class TimerRecoveryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val store = EyeCareStore(context)
    private lateinit var home: ActivityScenario<MainActivity>
    private var original: Map<String, *> = emptyMap<String, Any>()

    @Before fun prepare() {
        sharedHome?.let { home = it }
        ensureHome()
        stopAndWait()
        original = store.prefs.all.toMap()
        store.prefs.edit().clear().putBoolean("enabled", true).putInt("minutes", 5).commit()
        home.onActivity { assertTrue(EyeCareService.ensureRunning(it)) }
        await("unlocked phone starts counting") { store.prefs.getString("timerStatus", "") == "counting" }
    }

    @After fun restoreUserSettings() {
        instrumentation.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<ReminderActivity>().forEach { it.finish() }
        }
        if (::home.isInitialized) ensureHome()
        stopAndWait()
        val editor = store.prefs.edit().clear()
        original.forEach { (key, value) ->
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
            }
        }
        editor.commit()
        if (::home.isInitialized) {
            ensureHome()
            home.onActivity { EyeCareService.ensureRunning(it) }
        }
    }

    @Test fun emergencyExitStartsCountingAndNextBackgroundReminderStillAppears() {
        repeat(3) {
            seedTimer(298_000L)
            // The service must launch the page while the home screen is stopped.
            home.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            await("next rest opens from background") { realReminderVisible() }
            clickReminderExit()
            await("emergency exit restarts counting") {
                store.rest() == null && store.prefs.getString("timerStatus", "") == "counting" &&
                    store.prefs.getLong("usedMillis", 0L) >= 1_000L
            }
            home.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        }
    }

    @Test fun overlayEmergencyExitRemovesWindowAndRestartsCounting() {
        requireInspectableOverlayPermission()
        seedTimer(298_000L)
        home.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        await("background reminder attaches its overlay") { overlay() != null && store.rest() != null }
        val id = store.rest()!!.id
        clickReminderExit(requireOverlay = true)
        await("overlay exit removes the window and starts the next round") {
            overlay() == null && store.rest() == null &&
                store.prefs.getString("timerStatus", "") == "counting" &&
                store.prefs.getLong("usedMillis", 0L) >= 1_000L
        }
        assertEquals(id, store.prefs.getString("lastFinishedRestId", null))
    }

    @Test fun serviceRecreationRestoresActiveOverlayWithoutExtendingDeadline() {
        requireInspectableOverlayPermission()
        seedTimer(298_000L)
        await("first rest overlay is visible") { overlay() != null && store.rest() != null }
        val originalRest = store.rest()!!
        stopAndWait()
        assertNull("stopping the service removes its old overlay", overlay())
        assertEquals(originalRest, store.rest())
        home.onActivity { assertTrue(EyeCareService.ensureRunning(it)) }
        await("recreated service restores the active rest overlay") { overlay() != null }
        assertEquals("service recreation preserves the rest ID and deadline", originalRest, store.rest())
        clickReminderExit(requireOverlay = true)
        await("restored overlay exit resumes usage") {
            overlay() == null && store.rest() == null && store.prefs.getLong("usedMillis", 0L) >= 1_000L
        }
    }

    @Test fun overlayDeadlineRemovesWindowAndStartsNextRound() {
        requireInspectableOverlayPermission()
        seedTimer(298_000L)
        home.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        await("rest overlay appears before its deadline") { overlay() != null && store.rest() != null }
        val id = store.rest()!!.id
        instrumentation.runOnMainSync {
            store.prefs.edit().putLong("restEndElapsed", SystemClock.elapsedRealtime() + 1_200L)
                .putLong("restUntil", System.currentTimeMillis() + 1_200L).commit()
        }
        await("overlay deadline removes the window and resumes counting") {
            overlay() == null && store.rest() == null &&
                store.prefs.getString("timerStatus", "") == "counting" &&
                store.prefs.getLong("usedMillis", 0L) >= 1_000L
        }
        assertEquals(id, store.prefs.getString("lastFinishedRestId", null))
    }

    @Test fun automaticRestCompletionAlsoStartsNextRound() {
        val id = store.pendingReminderId()
        ActivityScenario.launch<ReminderActivity>(Intent(context, ReminderActivity::class.java)
            .putExtra(ReminderActivity.EXTRA_REST_ID, id)).use { rest ->
            rest.onActivity {
                store.prefs.edit().putLong("restEndElapsed", SystemClock.elapsedRealtime() + 1_200L)
                    .putLong("restUntil", System.currentTimeMillis() + 1_200L).commit()
            }
            await("rest deadline completes and usage resumes") {
                store.rest() == null && store.prefs.getLong("usedMillis", 0L) >= 1_000L
            }
        }
    }

    @Test fun serviceRecreationPreservesAccumulatedUsage() {
        seedTimer(600_000L, 30)
        await("timer advances") { store.prefs.getLong("usedMillis", 0L) >= 601_000L }
        val before = store.prefs.getLong("usedMillis", 0L)
        stopAndWait()
        home.onActivity { EyeCareService.ensureRunning(it) }
        await("service restores and continues") { store.prefs.getLong("usedMillis", 0L) >= before + 1_000L }
    }

    @Test fun changingReminderThresholdKeepsUsageAndCanTriggerImmediately() {
        seedTimer(600_000L, 30)
        home.onActivity { store.prefs.edit().putInt("minutes", 15).commit() }
        await("threshold update leaves elapsed intact") {
            store.prefs.getLong("usedMillis", 0L) >= 600_000L && store.rest() == null
        }
        home.onActivity { store.prefs.edit().putInt("minutes", 5).commit() }
        await("already reached shorter threshold opens rest") { realReminderVisible() }
        clickReminderExit()
    }

    @Test fun previewDoesNotResetOrPauseTheRealCounter() {
        seedTimer(60_000L, 30)
        val revision = store.revision
        ActivityScenario.launch<ReminderActivity>(Intent(context, ReminderActivity::class.java)
            .putExtra(ReminderActivity.EXTRA_PREVIEW, true)).use { preview ->
            await("usage continues under preview") { store.prefs.getLong("usedMillis", 0L) >= 61_000L }
            assertNull(store.rest())
            assertEquals(revision, store.revision)
            preview.onActivity { it.findViewById<Button>(android.R.id.button1).performClick() }
        }
        assertEquals(revision, store.revision)
        assertTrue(store.prefs.getLong("usedMillis", 0L) >= 61_000L)
    }

    @Test fun recreationDoesNotExtendRestAndStaleExitCannotEndNewRest() {
        val first = store.pendingReminderId()
        ActivityScenario.launch<ReminderActivity>(Intent(context, ReminderActivity::class.java)
            .putExtra(ReminderActivity.EXTRA_REST_ID, first)).use { rest ->
            val deadline = store.rest()!!.endElapsed
            rest.recreate()
            assertEquals(deadline, store.rest()!!.endElapsed)
            rest.onActivity { it.findViewById<Button>(android.R.id.button1).performClick() }
        }
        ensureHome()
        home.onActivity {
            val second = store.pendingReminderId()
            assertNotNull(store.beginRest(second))
            val revision = store.revision
            assertFalse(store.finishRest(first))
            assertEquals(second, store.rest()!!.id)
            assertEquals(revision, store.revision)
            assertNull(store.beginRest(first))
            assertTrue(store.finishRest(second))
        }
    }

    @Test fun pauseExpiresWithoutOpeningAppAgain() {
        home.onActivity { store.pause("测试暂停", System.currentTimeMillis() + 1_200L) }
        await("pause clears after its deadline") {
            store.pauseUntil == 0L && store.prefs.getString("timerStatus", "") == "counting" &&
                store.prefs.getLong("usedMillis", 0L) >= 1_000L
        }
    }

    private fun seedTimer(usedMillis: Long, minutes: Int = 5) {
        ensureHome()
        home.onActivity {
            store.prefs.edit().putInt("minutes", minutes)
                .putLong("sessionRevision", store.revision + 1L).remove("timerRevision").commit()
            val now = SystemClock.elapsedRealtime()
            val timer = UsageTimer().apply { restore(UsageTimer.Snapshot(usedMillis, null), now) }
            store.saveTimer(timer, now, "counting")
            assertTrue(EyeCareService.ensureRunning(it))
        }
    }

    @Suppress("DEPRECATION")
    private fun stopAndWait() {
        instrumentation.runOnMainSync { context.stopService(Intent(context, EyeCareService::class.java)) }
        await("service completely stops") {
            context.getSystemService(ActivityManager::class.java).getRunningServices(100)
                .none { it.service.className == EyeCareService::class.java.name }
        }
        instrumentation.waitForIdleSync()
    }

    @Test fun duplicateCompletionCommandDoesNotResetAlreadyResumedCounter() {
        val id = store.pendingReminderId()
        home.onActivity { assertNotNull(store.beginRest(id)); assertTrue(store.finishRest(id)) }
        await("usage starts after rest") { store.prefs.getLong("usedMillis", 0L) >= 1_000L }
        val before = store.prefs.getLong("usedMillis", 0L)
        home.onActivity { EyeCareService.ensureRunning(it, id); EyeCareService.ensureRunning(it, id) }
        await("duplicate completion keeps counting") { store.prefs.getLong("usedMillis", 0L) >= before + 1_000L }
        assertEquals(id, store.prefs.getString("lastFinishedRestId", null))
    }

    @Test fun realReminderReplacesPreviewAndEmergencyExitStillResumes() {
        seedTimer(297_000L)
        ActivityScenario.launch<ReminderActivity>(Intent(context, ReminderActivity::class.java)
            .putExtra(ReminderActivity.EXTRA_PREVIEW, true)).use { preview ->
            await("real reminder takes over preview") { store.rest() != null && realReminderVisible() }
            clickReminderExit()
            await("real reminder exit resumes counter") {
                store.rest() == null && overlay() == null &&
                    preview.state == androidx.lifecycle.Lifecycle.State.DESTROYED &&
                    store.prefs.getLong("usedMillis", 0L) >= 1_000L
            }
        }
    }

    private fun ensureHome() {
        // Bring the task forward before ActivityScenario waits for lifecycle
        // changes that an OEM may block while this process is in the background.
        instrumentation.uiAutomation.executeShellCommand(
            "am start -W --activity-reorder-to-front --activity-single-top -n ${context.packageName}/.MainActivity"
        ).let { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
        if (!::home.isInitialized || home.state == androidx.lifecycle.Lifecycle.State.DESTROYED) {
            home = ActivityScenario.launch(Intent(context, MainActivity::class.java))
            home.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        } else {
            home.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        }
        sharedHome = home
    }

    private fun reminderOnMain(): ReminderActivity? = ActivityLifecycleMonitorRegistry.getInstance()
        .getActivitiesInStage(Stage.RESUMED).filterIsInstance<ReminderActivity>().firstOrNull()

    private fun requireInspectableOverlayPermission() {
        assumeTrue("overlay inspection requires API 29+ and the overlay permission",
            Build.VERSION.SDK_INT >= 29 && Settings.canDrawOverlays(context))
    }

    private fun overlayOnMain(): View? {
        if (Build.VERSION.SDK_INT < 29) return null
        return WindowInspector.getGlobalWindowViews().firstOrNull {
            it.contentDescription?.toString() == "eye_care_rest_overlay" &&
                it.isAttachedToWindow && it.isShown
        }
    }

    private fun overlay(): View? {
        var result: View? = null
        instrumentation.runOnMainSync { result = overlayOnMain() }
        return result
    }

    private fun realReminderVisible(): Boolean {
        var visible = false
        instrumentation.runOnMainSync {
            visible = overlayOnMain() != null || reminderOnMain()
                ?.findViewById<Button>(android.R.id.button1)?.text?.toString() == "紧急退出"
        }
        return visible
    }

    private fun clickReminderExit(requireOverlay: Boolean = false) {
        instrumentation.runOnMainSync {
            val overlay = overlayOnMain()
            if (requireOverlay) assertNotNull("the real overlay must own the exit button", overlay)
            val button = overlay?.findViewById<Button>(android.R.id.button1)
                ?: reminderOnMain()?.findViewById<Button>(android.R.id.button1)
            assertNotNull("a visible real reminder has an exit button", button)
            assertEquals("紧急退出", button!!.text.toString())
            assertTrue("the visible reminder exit handles the click", button.performClick())
        }
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(100L)
        }
        assertTrue(description + "; status=" + store.prefs.getString("timerStatus", "") +
            ", used=" + store.prefs.getLong("usedMillis", 0L), condition())
    }

    companion object {
        private var sharedHome: ActivityScenario<MainActivity>? = null

        @JvmStatic @AfterClass fun restoreRequestedUserInterval() {
            try {
                val minutes = InstrumentationRegistry.getArguments().getString("userMinutes")?.toIntOrNull()
                if (minutes != null) {
                    val context = InstrumentationRegistry.getInstrumentation().targetContext
                    context.getSharedPreferences("eye", Context.MODE_PRIVATE).edit().putInt("minutes", minutes).commit()
                }
            } finally {
                sharedHome?.close()
                sharedHome = null
            }
        }
    }
}
