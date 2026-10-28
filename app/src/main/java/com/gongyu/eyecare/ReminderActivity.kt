package com.gongyu.eyecare

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class ReminderActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: EyeCareStore
    private lateinit var countdownView: TextView
    private lateinit var titleView: TextView
    private lateinit var messageView: TextView
    private lateinit var exitButton: Button
    private var restId: String? = null
    private var preview = false
    private var previewEnd = 0L
    private var ended = false
    private val preferencesListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (preview && ::countdownView.isInitialized && (key == "restId" || key == "sessionRevision")) {
            store.rest()?.let { adoptRest(it.id) }
        }
    }

    private val countdown = object : Runnable {
        override fun run() {
            if (ended) return
            val now = SystemClock.elapsedRealtime()
            val rest = if (preview) null else store.rest(now)
            if (!preview && rest?.id != restId) {
                // Another owner finished this rest. Never clear a newer session.
                ended = true
                finish()
                return
            }
            val remaining = if (preview) (previewEnd - now).coerceAtLeast(0L) else rest!!.remaining(now)
            val seconds = (remaining + 999L) / 1000L
            countdownView.text = "休息剩余 ${seconds / 60} 分 ${seconds % 60} 秒"
            if (remaining == 0L) endRest() else handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = EyeCareStore(this)
        preview = intent.getBooleanExtra(EXTRA_PREVIEW, false)
        if (preview) {
            previewEnd = savedInstanceState?.getLong("previewEnd") ?: (SystemClock.elapsedRealtime() + EyeCareStore.REST_MILLIS)
        } else {
            restId = intent.getStringExtra(EXTRA_REST_ID)
            if (restId == null || store.beginRest(restId!!) == null) {
                finish()
                return
            }
            getSystemService(android.app.NotificationManager::class.java).cancel(9)
            EyeCareService.ensureRunning(this)
        }
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        enterImmersive()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.rgb(247, 250, 247))
        }
        titleView = TextView(this).apply {
            text = if (preview) "休息页面预览" else "该休息一下了"
            textSize = 32f
            setTextColor(Color.rgb(30, 90, 55))
            gravity = Gravity.CENTER
        }
        messageView = TextView(this).apply {
            text = if (preview) "预览不会清零或暂停真实计时" else "请放下手机休息\n15 分钟后自动结束并开始下一轮计时"
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 16)
        }
        countdownView = TextView(this).apply {
            textSize = 28f
            setTextColor(Color.rgb(30, 90, 55))
            gravity = Gravity.CENTER
        }
        exitButton = Button(this).apply {
            id = android.R.id.button1
            text = if (preview) "返回" else "紧急退出"
            setOnClickListener { endRest() }
        }
        box.addView(titleView)
        box.addView(messageView)
        box.addView(countdownView)
        box.addView(exitButton)
        setContentView(box)
        store.prefs.registerOnSharedPreferenceChangeListener(preferencesListener)
        if (preview) store.rest()?.let { adoptRest(it.id) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (preview) endRest() }
        })
        handler.removeCallbacks(countdown)
        handler.post(countdown)
    }

    override fun onResume() {
        super.onResume()
        if (!preview && !isFinishing) EyeCareService.ensureRunning(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_PREVIEW, false)) return
        val incomingId = intent.getStringExtra(EXTRA_REST_ID) ?: return
        if (!preview && incomingId == restId) return
        if (store.beginRest(incomingId) == null) return
        setIntent(intent)
        adoptRest(incomingId)
    }

    private fun adoptRest(id: String) {
        // A real overlay also replaces a preview underneath it. Completing the
        // shared rest then closes both surfaces without leaving a second timer.
        handler.removeCallbacks(countdown)
        preview = false
        restId = id
        ended = false
        titleView.text = "该休息一下了"
        messageView.text = "请放下手机休息\n15 分钟后自动结束并开始下一轮计时"
        exitButton.text = "紧急退出"
        getSystemService(android.app.NotificationManager::class.java).cancel(9)
        EyeCareService.ensureRunning(this)
        handler.post(countdown)
    }

    private fun endRest() {
        if (ended) return
        ended = true
        handler.removeCallbacks(countdown)
        if (!preview) {
            restId?.let { id ->
                store.finishRest(id)
                EyeCareService.ensureRunning(this, id)
            }
        }
        finish()
    }

    private fun enterImmersive() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("previewEnd", previewEnd)
        super.onSaveInstanceState(outState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    override fun onDestroy() {
        handler.removeCallbacks(countdown)
        if (::store.isInitialized) store.prefs.unregisterOnSharedPreferenceChangeListener(preferencesListener)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_REST_ID = "rest_id"
        const val EXTRA_PREVIEW = "preview"
    }
}
