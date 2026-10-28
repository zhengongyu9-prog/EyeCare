# 护眼提醒（EyeCare）

一个基于 Android 前台服务的连续使用提醒应用。达到设定时长后，应用会在其他应用上层显示休息页面，并在休息结束或紧急退出后开始下一轮计时。

## 主要功能

- 仅在亮屏且解锁时累计使用时间。
- 息屏不足 3 分钟时保留累计时间，连续息屏达到 3 分钟后清零。
- 支持 5～60 分钟的提醒间隔。
- 使用悬浮窗显示全屏休息提醒，避免 Android 16/HyperOS 后台 Activity 不可见的问题。
- 休息期间重启服务会恢复原来的休息截止时间。
- 通知栏使用系统计时器显示计时或休息倒计时。
- 通过 AlarmManager 在后台唤醒服务，降低厂商冻结对提醒触发的影响。

## 开发环境

- Android Studio
- JDK 11
- Android SDK 37
- Kotlin / Jetpack Compose

## 构建

```powershell
.\gradlew.bat assembleDebug
```

生成的 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 首次使用权限

在小米/HyperOS 等系统上，需要在系统设置中允许：

1. 显示在其他应用的上层（悬浮窗）。
2. 后台弹出界面或对应的自启动权限。
3. 电池策略设为“无限制”。
4. 通知权限。
5. 需要准时后台提醒时，允许“闹钟和提醒”。

## 测试

```powershell
.\gradlew.bat testDebugUnitTest lintDebug
```

真机测试需要连接 Android 设备后运行：

```powershell
.\gradlew.bat connectedDebugAndroidTest
```
