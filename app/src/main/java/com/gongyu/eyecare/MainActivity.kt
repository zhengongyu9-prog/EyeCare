package com.gongyu.eyecare

import android.os.Bundle
import android.content.Intent
import android.os.Build
import android.app.NotificationManager
import android.app.AlarmManager
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri
import kotlin.time.Duration.Companion.seconds
import kotlin.math.roundToInt
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.delay
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import com.gongyu.eyecare.ui.theme.护眼提醒Theme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        EyeCareService.ensureRunning(this)
        setContent {
            护眼提醒Theme {
                EyeCareScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        EyeCareService.ensureRunning(this)
    }
}

@Composable
fun EyeCareScreen() {
    val context = LocalContext.current
    val store = remember { EyeCareStore(context) }
    val prefs = store.prefs
    var enabled by remember { mutableStateOf(prefs.getBoolean("enabled", true)) }
    var reminderMinutes by remember { mutableIntStateOf(prefs.getInt("minutes", 30)) }
    var usedSeconds by remember { mutableLongStateOf(prefs.getLong("usedSeconds", 0L)) }
    var pausedUntil by remember { mutableStateOf(if (prefs.getLong("pauseUntil", 0L) > System.currentTimeMillis()) prefs.getString("pauseLabel", null) else null) }
    var showPauseMenu by remember { mutableStateOf(false) }
    var notificationsAllowed by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    var fullScreenAllowed by remember { mutableStateOf(true) }
    var overlayAllowed by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var exactAlarmsAllowed by remember { mutableStateOf(true) }
    var timerStatus by remember { mutableStateOf(prefs.getString("timerStatus", "starting")) }
    var heartbeat by remember { mutableLongStateOf(prefs.getLong("serviceHeartbeat", 0L)) }
    var batteryUnrestricted by remember { mutableStateOf(false) }
    var restSeconds by remember { mutableLongStateOf(0L) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    LaunchedEffect(prefs) {
        while (true) {
            enabled = prefs.getBoolean("enabled", true)
            pausedUntil = if (prefs.getLong("pauseUntil", 0L) > System.currentTimeMillis()) prefs.getString("pauseLabel", "临时暂停") else null
            notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
            fullScreenAllowed = Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
            overlayAllowed = Settings.canDrawOverlays(context)
            exactAlarmsAllowed = Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
            usedSeconds = prefs.getLong("usedSeconds", 0L)
            timerStatus = prefs.getString("timerStatus", "starting")
            heartbeat = prefs.getLong("serviceHeartbeat", 0L)
            restSeconds = (store.rest()?.remaining(android.os.SystemClock.elapsedRealtime()) ?: 0L) / 1000L
            batteryUnrestricted = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
            delay(1.seconds)
        }
    }
    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF7F7F7)) {
        Column(modifier = Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("护眼提醒", fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("连续使用手机达到设定时间后全屏提醒", color = Color.Gray)
            Text("版本 ${BuildConfig.VERSION_NAME}", color = Color.Gray)
            Text("本次连续使用：${usedSeconds / 60} 分 ${usedSeconds % 60} 秒", color = Color(0xFF2878E8), fontSize = 18.sp)
            Text(when {
                !enabled -> "提醒已关闭"
                timerStatus == "counting" -> "正在计时"
                timerStatus == "resting" -> "正在休息：剩余 ${restSeconds / 60} 分 ${restSeconds % 60} 秒"
                timerStatus == "paused" -> "临时暂停中，到期后自动开始计时"
                timerStatus == "screen_off" -> "息屏暂停计时，累计时间保留至满 3 分钟"
                timerStatus == "awaiting_reminder" -> "已到提醒时间，正在打开休息页面；请检查后台弹出界面权限"
                else -> "正在启动计时"
            }, color = Color.Gray)
            if (enabled && System.currentTimeMillis() - heartbeat > 15_000L) {
                Button(onClick = { EyeCareService.ensureRunning(context) }) { Text("恢复后台计时") }
            }
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(Color.White)) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("护眼模式", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(
                            pausedUntil?.let { "已暂停：$it" } ?: if (enabled) "正在保护你的眼睛" else "已关闭提醒",
                            color = Color.Gray
                        )
                    }
                    Switch(checked = enabled, onCheckedChange = { enabled = it; pausedUntil = null; store.setEnabled(it); EyeCareService.ensureRunning(context) })
                }
            }
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(Color.White)) {
                Row(
                    Modifier.fillMaxWidth().clickable { showPauseMenu = true }.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("临时暂停", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("考试、开会或需要连续使用时可暂时关闭提醒", color = Color.Gray)
                    }
                    Text("选择 ›", color = Color(0xFF2878E8))
                }
            }
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(Color.White)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("连续使用提醒", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("达到 $reminderMinutes 分钟后弹出全屏休息提醒", color = Color.Gray)
                    Slider(value = reminderMinutes.toFloat(), onValueChange = { value ->
                        val selected = (value / 5f).roundToInt() * 5
                        reminderMinutes = selected.coerceIn(5, 60)
                    }, onValueChangeFinished = {
                        prefs.edit { putInt("minutes", reminderMinutes) }
                    }, valueRange = 5f..60f, steps = 10)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(5, 15, 30, 45, 60).forEach { option ->
                            TextButton(onClick = {
                                reminderMinutes = option
                                prefs.edit { putInt("minutes", option) }
                            }, modifier = Modifier.weight(1f)) { Text("$option 分") }
                        }
                    }
                    Text("当前设置：$reminderMinutes 分钟", color = Color(0xFF2878E8))
                }
            }
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(Color.White)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("工作原理", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("仅在亮屏且解锁时计时。息屏不足 3 分钟保留累计时间，满 3 分钟才清零；修改提醒时间保留已用时间。休息结束或紧急退出后立即开始下一轮。", color = Color.Gray)
                }
            }
            Button(onClick = { context.startActivity(Intent(context, ReminderActivity::class.java).putExtra(ReminderActivity.EXTRA_PREVIEW, true)) }, modifier = Modifier.fillMaxWidth()) {
                Text("预览休息页面（不验证后台提醒）")
            }
            Text("休息时长：15 分钟；紧急退出后重新计时。", color = Color.Gray)
            if (!notificationsAllowed) {
                Button(onClick = {
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    } else if (Build.VERSION.SDK_INT >= 26) {
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    } else {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
                    }
                }) { Text("开启通知权限") }
            }
            if (!fullScreenAllowed && Build.VERSION.SDK_INT >= 34) {
                Button(onClick = { context.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, "package:${context.packageName}".toUri())) }) { Text("允许全屏通知") }
            }
            if (!overlayAllowed) {
                Button(onClick = { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri())) }) {
                    Text("允许后台弹出休息页")
                }
                Text("小米/Android 16 需要此权限，才能在你使用其他 App 时直接打开休息页面。", color = Color.Gray)
            }
            if (!batteryUnrestricted) {
                Button(onClick = { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) { Text("设置后台电池优化") }
            }
            if (!exactAlarmsAllowed && Build.VERSION.SDK_INT >= 31) {
                Button(onClick = {
                    context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri()))
                }) { Text("允许准时提醒") }
                Text("用于在后台计时被系统暂停时，仍能按设定时间触发休息提醒。", color = Color.Gray)
            }
            Text("小米手机请保留自启动、后台弹出界面权限，并把省电策略设为无限制。", color = Color.Gray)
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())) }) { Text("打开应用后台运行设置") }
            Text("后台测试：开启提醒并取消暂停，设置 5 分钟，返回桌面保持手机解锁使用。到时检查是否收到提醒；仅预览页面不能验证后台功能。", color = Color.Gray)
        }
    }
    fun pauseFor(label: String, durationMillis: Long?) {
        val until = if (durationMillis == null) {
                java.util.Calendar.getInstance().apply {
                    add(java.util.Calendar.DAY_OF_YEAR, 1)
                    set(java.util.Calendar.HOUR_OF_DAY, 0)
                    set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }.timeInMillis
            } else {
                System.currentTimeMillis() + durationMillis
            }
        store.pause(label, until)
        EyeCareService.ensureRunning(context)
        pausedUntil = label
        enabled = true
        showPauseMenu = false
    }
    if (showPauseMenu) {
        AlertDialog(
            onDismissRequest = { showPauseMenu = false },
            title = { Text("暂停护眼提醒") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text("暂停后本轮计时清零，暂停到期自动开始新一轮。请选择：")
                    listOf("15 分钟" to 15L, "30 分钟" to 30L, "1 小时" to 60L,
                        "2 小时" to 120L, "3 小时" to 180L, "4 小时" to 240L,
                        "5 小时" to 300L, "8 小时" to 480L).forEach { (label, minutes) ->
                        TextButton(onClick = { pauseFor(label, minutes * 60_000L) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
                    }
                    TextButton(onClick = { pauseFor("今天结束", null) }, modifier = Modifier.fillMaxWidth()) { Text("今天结束") }
                }
            },
            confirmButton = {
                TextButton(onClick = { store.cancelPause(); EyeCareService.ensureRunning(context); showPauseMenu = false }) { Text("取消暂停") }
            },
            dismissButton = { TextButton(onClick = { showPauseMenu = false }) { Text("返回") } }
        )
    }
}

@Preview(showBackground = true)
@Composable
fun HomePreview() {
    护眼提醒Theme {
        EyeCareScreen()
    }
}
