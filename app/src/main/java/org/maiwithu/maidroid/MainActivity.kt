package org.maiwithu.maidroid

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.maiwithu.maidroid.container.MaiBotContainerConfig
import org.maiwithu.maidroid.oobe.OobeSetupManager
import org.maiwithu.maidroid.process.TerminalLogRepository
import org.maiwithu.maidroid.repository.SettingsRepository
import org.maiwithu.maidroid.service.ChatbotService
import org.maiwithu.maidroid.ui.screen.HomeScreen
import org.maiwithu.maidroid.ui.screen.OobeFlowScreen
import org.maiwithu.maidroid.ui.theme.MaiDroidTheme
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {
    private var storagePermissionGranted by mutableStateOf(false)
    private var notificationPermissionGranted by mutableStateOf(false)
    private var batteryOptimizationGranted by mutableStateOf(false)

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        storagePermissionGranted = results.values.all { it } && isStoragePermissionGranted()
        Toast.makeText(
            this,
            if (storagePermissionGranted) "存储权限已授权" else "存储权限未授权",
            Toast.LENGTH_SHORT
        ).show()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationPermissionGranted = granted && isNotificationPermissionGranted()
        Toast.makeText(
            this,
            if (notificationPermissionGranted) "通知权限已授权" else "通知权限未授权",
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        refreshPermissionState()

        setContent {
            MaiDroidTheme {
                val settingsRepository = remember { SettingsRepository() }
                var setupComplete by remember {
                    mutableStateOf(settingsRepository.isSetupComplete())
                }
                var webUiOnline by remember { mutableStateOf(false) }
                val versionName = remember { getVersionName() }
                val terminalLogs by TerminalLogRepository.logs.collectAsState()

                LaunchedEffect(setupComplete) {
                    configureSystemBars(setupComplete)
                }

                LaunchedEffect(setupComplete) {
                    if (!setupComplete) return@LaunchedEffect

                    startMaiBotService()
                    while (true) {
                        webUiOnline = isWebUiReachable()
                        delay(if (webUiOnline) 5_000L else 1_500L)
                    }
                }

                if (setupComplete) {
                    HomeScreen(
                        webUiOnline = webUiOnline,
                        versionName = versionName,
                        terminalLogs = terminalLogs,
                        onWakeMai = {
                            if (webUiOnline) {
                                openWebUi()
                            } else {
                                startMaiBotService()
                                Toast.makeText(this, "正在唤醒麦麦...", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                    return@MaiDroidTheme
                }

                var oobeStep by remember { mutableIntStateOf(0) }
                val setupManager = remember { OobeSetupManager(applicationContext) }
                val setupState by setupManager.state.collectAsState()

                BackHandler(enabled = oobeStep > 0) {
                    oobeStep -= 1
                }

                LaunchedEffect(oobeStep) {
                    when (oobeStep) {
                        1 -> setupManager.prepareContainer()
                        2 -> setupManager.startInstall()
                    }
                }

                OobeFlowScreen(
                    currentStep = oobeStep,
                    setupState = setupState,
                    storagePermissionGranted = storagePermissionGranted,
                    notificationPermissionGranted = notificationPermissionGranted,
                    batteryOptimizationGranted = batteryOptimizationGranted,
                    onStorageAuthorize = {
                        requestStoragePermission()
                    },
                    onNotificationAuthorize = {
                        requestNotificationPermission()
                    },
                    onBatteryOptimizationAuthorize = {
                        requestIgnoreBatteryOptimizations()
                    },
                    onAutoStartAuthorize = {
                        openAppDetails("请在系统设置中为 MaiDroid 开启自启动和后台活动")
                    },
                    onTaskLockAuthorize = {
                        Toast.makeText(this, "请在系统多任务界面将 MaiDroid 加锁", Toast.LENGTH_LONG).show()
                    },
                    onAccessibilityAuthorize = {
                        openAccessibilitySettings()
                    },
                    onDeviceAdminAuthorize = {
                        openDeviceAdminSettings()
                    },
                    onNext = {
                        when (oobeStep) {
                            0 -> {
                                storagePermissionGranted = isStoragePermissionGranted()
                                if (storagePermissionGranted) {
                                    oobeStep = 1
                                } else {
                                    Toast.makeText(this, "请先授予必选存储权限", Toast.LENGTH_SHORT).show()
                                    requestStoragePermission()
                                }
                            }
                            1 -> {
                                if (setupState.canInstall) {
                                    oobeStep = 2
                                } else {
                                    setupManager.prepareContainer()
                                }
                            }
                            else -> {
                                if (setupState.isComplete) {
                                    setupComplete = true
                                } else {
                                    setupManager.startInstall()
                                }
                            }
                        }
                    },
                    onRetry = {
                        if (oobeStep == 1) {
                            setupManager.prepareContainer()
                        } else {
                            setupManager.startInstall()
                        }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
    }

    private fun refreshPermissionState() {
        storagePermissionGranted = isStoragePermissionGranted()
        notificationPermissionGranted = isNotificationPermissionGranted()
        batteryOptimizationGranted = isBatteryOptimizationGranted()
    }

    private fun isStoragePermissionGranted(): Boolean =
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                        checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            }
            else -> true
        }

    private fun isNotificationPermissionGranted(): Boolean {
        val runtimeGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return runtimeGranted && NotificationManagerCompat.from(this).areNotificationsEnabled()
    }

    private fun isBatteryOptimizationGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val powerManager = getSystemService(PowerManager::class.java)
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                storagePermissionGranted = true
                Toast.makeText(this, "存储权限已授权", Toast.LENGTH_SHORT).show()
                return
            }

            val packageUri = Uri.parse("package:$packageName")
            val appSettingsIntent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                packageUri
            )
            val allFilesSettingsIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)

            startSettingsActivity(appSettingsIntent, allFilesSettingsIntent)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            storagePermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        } else {
            storagePermissionGranted = true
            Toast.makeText(this, "存储权限已授权", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            batteryOptimizationGranted = true
            Toast.makeText(this, "后台权限已授权", Toast.LENGTH_SHORT).show()
            return
        }

        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            batteryOptimizationGranted = true
            Toast.makeText(this, "后台权限已授权", Toast.LENGTH_SHORT).show()
            return
        }

        val packageUri = Uri.parse("package:$packageName")
        val requestIntent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            packageUri
        )
        val settingsIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

        startSettingsActivity(requestIntent, settingsIntent)
    }

    private fun requestNotificationPermission() {
        if (isNotificationPermissionGranted()) {
            notificationPermissionGranted = true
            Toast.makeText(this, "通知权限已授权", Toast.LENGTH_SHORT).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        openNotificationSettings()
    }

    private fun startSettingsActivity(primaryIntent: Intent, fallbackIntent: Intent) {
        try {
            startActivity(primaryIntent)
        } catch (_: ActivityNotFoundException) {
            startActivity(fallbackIntent)
        }
    }

    private fun openNotificationSettings() {
        val notificationIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
        val fallbackIntent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName")
        )
        startSettingsActivity(notificationIntent, fallbackIntent)
    }

    private fun openAppDetails(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun openAccessibilitySettings() {
        Toast.makeText(this, "无障碍权限风险较高，请确认确实需要后再开启", Toast.LENGTH_LONG).show()
        startSettingsActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
    }

    private fun openDeviceAdminSettings() {
        Toast.makeText(this, "设备管理员权限风险较高，当前请在系统安全设置中手动检查", Toast.LENGTH_LONG).show()
        startSettingsActivity(
            Intent(Settings.ACTION_SECURITY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
    }

    private fun configureSystemBars(setupComplete: Boolean) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        controller.show(WindowInsetsCompat.Type.statusBars())

        if (setupComplete) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.navigationBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun openWebUi() {
        startActivity(
            Intent(this, WebUiActivity::class.java).apply {
                putExtra(WebUiActivity.EXTRA_URL, MaiBotContainerConfig.WEB_UI_URL)
            }
        )
    }

    private fun startMaiBotService() {
        val intent = Intent(this, ChatbotService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private suspend fun isWebUiReachable(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL(MaiBotContainerConfig.WEB_UI_URL)
                .openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 1_000
                connection.readTimeout = 1_000
                connection.responseCode in 200..499
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    private fun getVersionName(): String =
        runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        }.getOrDefault("1.0")
}
