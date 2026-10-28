package com.gongyu.eyecare

import android.app.KeyguardManager
import android.app.AlarmManager
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlin.math.abs

class EyeCareService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val timer = UsageTimer()
    private lateinit var store: EyeCareStore
    private var observedRevision = -1L
    private var lastAlertAt: Long? = null
    private var lastNotificationKey = ""
    private var lastChronometerBase: Long? = null
    private var scheduledDeadline: Long? = null
    private var scheduledExact = false
    private var lastOverlayAttempt: Long? = null
    private var status = "starting"
    private var overlayId: String? = null
    private var overlayRoot: View? = null
    private var overlayCountdown: TextView? = null
    private var overlayWindowManager: WindowManager? = null

    private val overlayTick = object : Runnable {
        override fun run() {
            val id = overlayId ?: return
            val rest = store.rest(SystemClock.elapsedRealtime())
            if (rest?.id != id) {
                dismissReminderOverlay()
                return
            }
            val remaining = rest.remaining(SystemClock.elapsedRealtime())
            val seconds = (remaining + 999L) / 1_000L
            overlayCountdown?.text = "休息剩余 ${seconds / 60} 分 ${seconds % 60} 秒"
            if (remaining == 0L) {
                store.finishRest(id)
                dismissReminderOverlay()
                requestTick()
            } else {
                handler.postDelayed(this, 1_000L)
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Freeze at the event, even for off/on entirely between ticks.
            evaluate(screenOff = intent.action == Intent.ACTION_SCREEN_OFF)
            requestTick()
        }
    }
    private val preferencesListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "enabled" -> {
                if (store.enabled) {
                    requestTick()
                } else {
                    // A disabled reminder should not leave a foreground service or
                    // its persistent notification running in the background.
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    dismissReminderOverlay()
                    cancelDeadline()
                    stopSelf()
                }
            }
            "sessionRevision", "minutes" -> requestTick()
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            try {
                evaluate()
            } finally {
                handler.removeCallbacks(this)
                handler.postDelayed(this, 1_000L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = EyeCareStore(this)
        val now = SystemClock.elapsedRealtime()
        observedRevision = store.revision
        cancelDeadline()
        store.restoreTimer(now)?.let { timer.restore(it, now) }
        createChannels()
        if (store.prefs.getString("pendingReminderId", null) == null && store.rest() == null) {
            getSystemService(NotificationManager::class.java).cancel(ALERT_ID)
        }
        startForeground(FOREGROUND_ID, notification("护眼计时已启动"))
        store.prefs.registerOnSharedPreferenceChangeListener(preferencesListener)
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TIMER_DEADLINE) scheduledDeadline = null
        if (intent?.action == ACTION_REST_FINISHED) {
            intent.getStringExtra(ReminderActivity.EXTRA_REST_ID)?.let { store.finishRest(it) }
        }
        // Also resumes a killed/recreated service immediately; no delayed reset.
        evaluate()
        requestTick()
        return START_STICKY
    }

    private fun requestTick() {
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun evaluate(screenOff: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        store.rest(now)?.takeIf { it.remaining(now) == 0L }?.let {
            store.finishRest(it.id)
            dismissReminderOverlay()
        }
        if (store.pauseUntil in 1..System.currentTimeMillis()) store.cancelPause()
        if (observedRevision != store.revision) {
            observedRevision = store.revision
            timer.reset()
            store.restoreTimer(now)?.let { timer.restore(it, now) }
            lastAlertAt = null
            getSystemService(NotificationManager::class.java).cancel(ALERT_ID)
        }
        val rest = store.rest(now)
        val available = !screenOff && getSystemService(PowerManager::class.java).isInteractive &&
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked
        status = when {
            !store.enabled -> { timer.reset(); "disabled" }
            store.pauseUntil > System.currentTimeMillis() -> { timer.reset(); dismissReminderOverlay(); "paused" }
            rest != null -> {
                timer.reset()
                if (Settings.canDrawOverlays(this) && overlayId != rest.id &&
                    lastOverlayAttempt?.let { now - it < 30_000L } != true) {
                    lastOverlayAttempt = now
                    showReminderOverlay(rest.id)
                }
                "resting"
            }
            else -> {
                timer.update(now, available)
                if (timer.isDue(now, store.minutes * 60_000L)) {
                    if (lastAlertAt?.let { now - it >= 30_000L } != false) {
                        lastAlertAt = now
                        sendReminder(store.pendingReminderId())
                    }
                    if (store.rest(now) != null) { timer.reset(); "resting" } else "awaiting_reminder"
                } else if (available) "counting" else "screen_off"
            }
        }
        store.saveTimer(timer, now, status)
        val currentRest = store.rest(now)
        val used = timer.elapsed(now)
        val text = when (status) {
            "disabled" -> "护眼提醒已关闭"
            "paused" -> "护眼提醒已临时暂停"
            "resting" -> "正在休息，结束后自动开始下一轮计时"
            "screen_off" -> "${formatUsage(used, store.minutes)}；息屏满 3 分钟后清零"
            "awaiting_reminder" -> "已到提醒时间，等待打开休息页面"
            else -> "连续使用计时，满 ${store.minutes} 分钟提醒"
        }
        val chronometerBase = when (status) {
            "counting" -> now - used
            "resting" -> currentRest?.endElapsed
            else -> null
        }
        val notificationKey = "$status:$text:${currentRest?.id}"
        if (notificationKey != lastNotificationKey ||
            chronometerBase != null && lastChronometerBase?.let { abs(it - chronometerBase) > 1_000L } != false) {
            lastNotificationKey = notificationKey
            lastChronometerBase = chronometerBase
            getSystemService(NotificationManager::class.java).notify(FOREGROUND_ID,
                notification(text, chronometerBase, status == "resting"))
        }
        scheduleDeadline(now, when (status) {
            "paused" -> now + (store.pauseUntil - System.currentTimeMillis()).coerceAtLeast(1_000L)
            "resting" -> currentRest?.endElapsed
            "counting" -> now + (store.minutes * 60_000L - used).coerceAtLeast(1_000L)
            "awaiting_reminder" -> now + 30_000L
            else -> null
        })
    }

    private fun sendReminder(id: String) {
        val intent = Intent(this, ReminderActivity::class.java)
            .putExtra(ReminderActivity.EXTRA_REST_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val backgroundStartMode = if (Build.VERSION.SDK_INT >= 36) {
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
        } else {
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        }
        val pending = if (Build.VERSION.SDK_INT >= 34) {
            // Android 14+ requires the creator opt-in when a PendingIntent is
            // intended to bring an Activity to the foreground from a service.
            val creatorOptions = ActivityOptions.makeBasic()
                .setPendingIntentCreatorBackgroundActivityStartMode(backgroundStartMode)
            PendingIntent.getActivity(this, ALERT_ID, intent, pendingFlags, creatorOptions.toBundle())
        } else {
            PendingIntent.getActivity(this, ALERT_ID, intent, pendingFlags)
        }
        var overlayShown = false
        try {
            overlayShown = Settings.canDrawOverlays(this) && showReminderOverlay(id)
            // A successful overlay owns this rest; notification taps can still
            // open the Activity without creating a second automatic surface.
            if (!overlayShown && Build.VERSION.SDK_INT >= 34) {
                // Android 14+ requires an explicit opt-in when a PendingIntent
                // starts an Activity from a background service. Without this,
                // the service keeps timing but the rest page only appears after
                // the user opens the app manually.
                val options = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(backgroundStartMode)
                pending.send(this, 0, null, null, null, null, options.toBundle())
            } else if (!overlayShown) {
                startActivity(intent)
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "Rest Activity launch failed; keeping elapsed usage for retry", error)
        }
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            val manager = getSystemService(NotificationManager::class.java)
            val builder = NotificationCompat.Builder(this, "eye_alert")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("护眼提醒")
                .setContentText(if (overlayShown) "正在休息，点击查看剩余时间" else "已到休息时间，点击进入 15 分钟休息")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setContentIntent(pending).setOnlyAlertOnce(true).setAutoCancel(true)
            if (!overlayShown && (Build.VERSION.SDK_INT < 34 || manager.canUseFullScreenIntent())) {
                builder.setFullScreenIntent(pending, true)
            }
            try {
                manager.notify(ALERT_ID, builder.build())
            } catch (error: SecurityException) {
                Log.w(TAG, "Notification permission changed during delivery", error)
            }
        }
    }

    private fun showReminderOverlay(id: String): Boolean {
        if (overlayId == id) return true
        if (overlayId != null) return false
        if (store.beginRest(id) == null) return false
        lastOverlayAttempt = SystemClock.elapsedRealtime()

        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val root = LinearLayout(this).apply {
            contentDescription = "eye_care_rest_overlay"
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(Color.rgb(247, 250, 247))
        }
        root.addView(TextView(this).apply {
            text = "该休息一下了"
            textSize = 32f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(30, 90, 55))
        })
        root.addView(TextView(this).apply {
            text = "请放下手机休息\n15 分钟后自动结束并开始下一轮计时"
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(0, dp(32), 0, dp(16))
        })
        overlayCountdown = TextView(this).apply {
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(30, 90, 55))
        }
        root.addView(overlayCountdown)
        root.addView(Button(this).apply {
            this.id = android.R.id.button1
            text = "紧急退出"
            setOnClickListener { finishOverlayRest(id) }
        })

        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
        return try {
            val manager = getSystemService(WindowManager::class.java)
            manager.addView(root, params)
            overlayId = id
            overlayRoot = root
            overlayWindowManager = manager
            handler.removeCallbacks(overlayTick)
            handler.post(overlayTick)
            true
        } catch (error: RuntimeException) {
            Log.w(TAG, "Reminder overlay could not be shown", error)
            // A failed window is not a completed rest. Keep its id/deadline so
            // the Activity fallback and a later window retry can still show it.
            overlayCountdown = null
            false
        }
    }

    private fun finishOverlayRest(id: String) {
        if (overlayId != id) return
        store.finishRest(id)
        dismissReminderOverlay()
        requestTick()
    }

    private fun dismissReminderOverlay() {
        handler.removeCallbacks(overlayTick)
        val root = overlayRoot
        if (root != null) {
            try {
                overlayWindowManager?.removeView(root)
            } catch (error: RuntimeException) {
                Log.w(TAG, "Reminder overlay removal failed", error)
            }
        }
        overlayId = null
        overlayRoot = null
        overlayCountdown = null
        overlayWindowManager = null
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).apply {
                createNotificationChannel(NotificationChannel("eye", "护眼后台", NotificationManager.IMPORTANCE_LOW))
                createNotificationChannel(NotificationChannel("eye_alert", "全屏护眼提醒", NotificationManager.IMPORTANCE_HIGH))
            }
        }
    }

    private fun notification(text: String, baseElapsed: Long? = null, countDown: Boolean = false): Notification = NotificationCompat.Builder(this, "eye")
        .setSmallIcon(android.R.drawable.ic_menu_info_details)
        .setContentTitle("护眼提醒").setContentText(text)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .setOnlyAlertOnce(true).setOngoing(true)
        .setShowWhen(baseElapsed != null).setUsesChronometer(baseElapsed != null)
        .setChronometerCountDown(countDown)
        .apply {
            if (baseElapsed != null) setWhen(System.currentTimeMillis() + baseElapsed - SystemClock.elapsedRealtime())
        }.build()

    private fun deadlineIntent(): PendingIntent {
        val intent = Intent(this, EyeCareService::class.java).setAction(ACTION_TIMER_DEADLINE)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (Build.VERSION.SDK_INT >= 26) PendingIntent.getForegroundService(this, 1, intent, flags)
        else PendingIntent.getService(this, 1, intent, flags)
    }

    private fun scheduleDeadline(now: Long, deadline: Long?) {
        if (deadline == null) {
            if (scheduledDeadline != null) cancelDeadline()
            return
        }
        val manager = getSystemService(AlarmManager::class.java)
        val exact = Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()
        if (scheduledDeadline?.let { abs(it - deadline) <= 1_000L } == true && scheduledExact == exact) return
        val trigger = deadline.coerceAtLeast(now + 500L)
        try {
            if (exact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, deadlineIntent())
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, deadlineIntent())
            }
            scheduledDeadline = trigger
            scheduledExact = exact
        } catch (error: SecurityException) {
            Log.w(TAG, "Exact alarm access changed; using an inexact reminder deadline", error)
            manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, deadlineIntent())
            scheduledDeadline = trigger
            scheduledExact = false
        }
    }

    private fun cancelDeadline() {
        getSystemService(AlarmManager::class.java).cancel(deadlineIntent())
        scheduledDeadline = null
    }

    private fun formatUsage(usedMillis: Long, thresholdMinutes: Int): String {
        val totalSeconds = usedMillis.coerceAtLeast(0L) / 1_000L
        return "连续使用 ${totalSeconds / 60} 分 ${totalSeconds % 60} 秒 / ${thresholdMinutes} 分钟"
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        dismissReminderOverlay()
        cancelDeadline()
        if (observedRevision == store.revision) {
            val finalStatus = if (store.enabled) status else "disabled"
            store.saveTimer(timer, SystemClock.elapsedRealtime(), finalStatus)
        }
        store.prefs.unregisterOnSharedPreferenceChangeListener(preferencesListener)
        unregisterReceiver(screenReceiver)
        super.onDestroy()
    }

    companion object {
        const val ACTION_REST_FINISHED = "com.gongyu.eyecare.REST_FINISHED"
        private const val ACTION_TIMER_DEADLINE = "com.gongyu.eyecare.TIMER_DEADLINE"
        private const val FOREGROUND_ID = 7
        private const val ALERT_ID = 9
        private const val TAG = "EyeCareService"

        fun ensureRunning(context: Context, finishedRestId: String? = null): Boolean {
            val intent = Intent(context, EyeCareService::class.java)
            if (!EyeCareStore(context).enabled) {
                // Keep callers idempotent: stopping an already running service is
                // safe and avoids restarting it after the user disables reminders.
                context.stopService(intent)
                return false
            }
            if (finishedRestId != null) {
                intent.action = ACTION_REST_FINISHED
                intent.putExtra(ReminderActivity.EXTRA_REST_ID, finishedRestId)
            }
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (error: RuntimeException) {
                Log.e(TAG, "Cannot start timer service in the current app state", error)
                false
            }
        }
    }
}
