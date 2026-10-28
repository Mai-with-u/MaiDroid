package org.maiwithu.maidroid.permission

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat

/** MaiDroid 用到的、能读出授予情况的系统权限。 */
data class PermissionState(
    /** 所有文件访问（Android 11+）或读写外部存储：解包容器、让容器访问 /sdcard，必需。 */
    val storageGranted: Boolean = false,
    /** 允许发通知：前台服务的常驻通知靠它，可选但推荐。 */
    val notificationGranted: Boolean = false,
    /** 已加入电池优化白名单：减少系统在后台挂起 MaiBot，可选但推荐。 */
    val batteryOptimizationIgnored: Boolean = false
)

/**
 * 界面上所有“去授权 / 去系统设置”的入口。
 * OOBE 第一步只用开启方向的动作；设置页的权限管理开关还会用到关闭方向（跳到系统设置让用户自己关）。
 * 自启动、多任务加锁、无障碍、设备管理员没有可读的授予状态，只能引导用户去系统里操作。
 */
data class PermissionActions(
    val requestStorage: () -> Unit = {},
    val enableNotification: () -> Unit = {},
    val disableNotification: () -> Unit = {},
    val ignoreBatteryOptimization: () -> Unit = {},
    val restoreBatteryOptimization: () -> Unit = {},
    val openAutoStartSettings: () -> Unit = {},
    val showTaskLockHint: () -> Unit = {},
    val openAccessibilitySettings: () -> Unit = {},
    val openDeviceAdminSettings: () -> Unit = {}
)

/**
 * 绑定在 MainActivity 上的系统权限检查与跳转。
 *
 * 授予情况保存在 Compose 状态里，Activity 每次回到前台调 refresh 重新检查，界面随之刷新。
 * 必须在 Activity 构造期间创建（作为属性初始化），因为权限结果回调要在 Activity 启动前注册；
 * 构造时 Activity 还没有 Context，所以初始状态全为未授予，由 onCreate 里的 refresh 读出真实值。
 */
internal class SystemPermissionController(private val activity: ComponentActivity) {

    var state by mutableStateOf(PermissionState())
        private set

    // Android 10 及以下走运行时读写权限；11+ 走“所有文件访问”设置页，不经过这个回调。
    private val storagePermissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.all { it } && isStorageGranted()
        state = state.copy(storageGranted = granted)
        toast(if (granted) "存储权限已授权" else "存储权限未授权")
    }

    private val notificationPermissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val enabled = granted && isNotificationGranted()
        state = state.copy(notificationGranted = enabled)
        toast(if (enabled) "通知权限已授权" else "通知权限未授权")
    }

    /** OOBE 第一步的动作：都是“去开启”。 */
    val oobeActions = PermissionActions(
        requestStorage = ::requestStorage,
        enableNotification = ::requestNotification,
        ignoreBatteryOptimization = ::requestIgnoreBatteryOptimizations,
        openAutoStartSettings = { openAppDetails("请在系统设置中为 MaiDroid 开启自启动和后台活动") },
        showTaskLockHint = { toast("请在系统多任务界面将 MaiDroid 加锁", long = true) },
        openAccessibilitySettings = ::openAccessibilitySettings,
        openDeviceAdminSettings = ::openDeviceAdminSettings
    )

    /** 设置页权限管理的动作：开关可以开也可以关，关闭方向一律跳到系统设置页。 */
    val settingsActions = PermissionActions(
        requestStorage = ::requestStorage,
        enableNotification = ::requestNotification,
        disableNotification = ::openNotificationSettings,
        ignoreBatteryOptimization = ::requestIgnoreBatteryOptimizations,
        restoreBatteryOptimization = ::openBatteryOptimizationSettings,
        openAutoStartSettings = { openAppDetails("请在系统设置中为 MaiDroid 开启或关闭自启动和后台活动") },
        showTaskLockHint = { toast("请在系统多任务界面为 MaiDroid 开启或关闭加锁", long = true) },
        openAccessibilitySettings = ::openAccessibilitySettings,
        openDeviceAdminSettings = ::openDeviceAdminSettings
    )

    /** 重新读一遍三项可检测的权限。onCreate 和每次 onResume（可能刚从系统设置页回来）调用。 */
    fun refresh() {
        state = PermissionState(
            storageGranted = isStorageGranted(),
            notificationGranted = isNotificationGranted(),
            batteryOptimizationIgnored = isBatteryOptimizationIgnored()
        )
    }

    /** 当下的存储权限（OOBE 点“下一步”时现查，不依赖可能过时的状态）。 */
    fun isStorageGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            isGranted(Manifest.permission.READ_EXTERNAL_STORAGE) &&
                isGranted(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

    private fun isNotificationGranted(): Boolean {
        val runtimeGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            isGranted(Manifest.permission.POST_NOTIFICATIONS)
        return runtimeGranted && NotificationManagerCompat.from(activity).areNotificationsEnabled()
    }

    private fun isBatteryOptimizationIgnored(): Boolean =
        activity.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(activity.packageName)

    // Android 11+ 跳到本应用的“所有文件访问”设置页（个别 ROM 没有就退到总列表）；10 及以下弹运行时权限框。
    private fun requestStorage() {
        if (isStorageGranted()) {
            state = state.copy(storageGranted = true)
            toast("存储权限已授权")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startSettingsActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri()),
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            )
        } else {
            storagePermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

    // Android 13+ 先弹运行时通知权限；已授予运行时权限但通知被关了，就跳到应用通知设置。
    private fun requestNotification() {
        if (isNotificationGranted()) {
            state = state.copy(notificationGranted = true)
            toast("通知权限已授权")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !isGranted(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        openNotificationSettings()
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (isBatteryOptimizationIgnored()) {
            state = state.copy(batteryOptimizationIgnored = true)
            toast("后台权限已授权")
            return
        }
        startSettingsActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri()),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        )
    }

    private fun openBatteryOptimizationSettings() {
        startSettingsActivity(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri())
        )
    }

    private fun openNotificationSettings() {
        startSettingsActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri())
        )
    }

    // 自启动和后台活动是厂商自己的设置，没有统一入口，只能打开应用详情页并提示用户自己找。
    private fun openAppDetails(message: String) {
        toast(message, long = true)
        activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri()))
    }

    private fun openAccessibilitySettings() {
        toast("无障碍权限风险较高，请确认确实需要后再开启", long = true)
        startSettingsActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), Intent(Settings.ACTION_SETTINGS))
    }

    private fun openDeviceAdminSettings() {
        toast("设备管理员权限风险较高，当前请在系统安全设置中手动检查", long = true)
        startSettingsActivity(Intent(Settings.ACTION_SECURITY_SETTINGS), Intent(Settings.ACTION_SETTINGS))
    }

    // 部分 ROM 精简了某些设置页，打不开时退到备用页面。
    private fun startSettingsActivity(primaryIntent: Intent, fallbackIntent: Intent) {
        try {
            activity.startActivity(primaryIntent)
        } catch (_: ActivityNotFoundException) {
            activity.startActivity(fallbackIntent)
        }
    }

    private fun isGranted(permission: String): Boolean =
        activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun packageUri(): Uri = Uri.parse("package:${activity.packageName}")

    private fun toast(message: String, long: Boolean = false) {
        Toast.makeText(activity, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }
}
