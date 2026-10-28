package org.maiwithu.maidroid

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maiwithu.maidroid.webui.MaiBotWebUi
import org.maiwithu.maidroid.log.TerminalLog
import org.maiwithu.maidroid.oobe.OobeStep
import org.maiwithu.maidroid.permission.SystemPermissionController
import org.maiwithu.maidroid.settings.AppSettings
import org.maiwithu.maidroid.service.MaiBotRuntimeService
import org.maiwithu.maidroid.ui.component.BlurTargetHost
import org.maiwithu.maidroid.ui.screen.HomeScreen
import org.maiwithu.maidroid.ui.screen.OobeAuthorizationDialog
import org.maiwithu.maidroid.ui.screen.OobeFlowScreen
import org.maiwithu.maidroid.ui.screen.StartupSplashPage
import org.maiwithu.maidroid.ui.theme.MaiDroidTheme
import org.maiwithu.maidroid.webui.MaiBotWebUiProbe
import eightbitlab.com.blurview.BlurTarget

class MainActivity : ComponentActivity() {
    private val permissions = SystemPermissionController(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        permissions.refresh()

        setContent {
            MaiDroidTheme {
                val activityViewModel: MainActivityViewModel = viewModel()
                val settingsRepository = remember { AppSettings() }
                var setupComplete by remember {
                    mutableStateOf(settingsRepository.setupComplete)
                }
                var authorizationAccepted by remember {
                    mutableStateOf(settingsRepository.authorizationAccepted)
                }
                var webUiOnline by remember { mutableStateOf(false) }
                var showStartupSplash by rememberSaveable {
                    mutableStateOf(savedInstanceState == null)
                }
                var showAuthorizationDialog by rememberSaveable { mutableStateOf(false) }
                var oobeBlurTarget by remember { mutableStateOf<BlurTarget?>(null) }
                val uiScope = rememberCoroutineScope()
                val versionName = remember { getVersionName() }
                val terminalLogs by TerminalLog.logs.collectAsState()

                LaunchedEffect(showStartupSplash) {
                    if (showStartupSplash) {
                        delay(1_200L)
                        showStartupSplash = false
                    }
                }

                LaunchedEffect(showStartupSplash, setupComplete, authorizationAccepted) {
                    when {
                        showStartupSplash || setupComplete || authorizationAccepted -> {
                            showAuthorizationDialog = false
                        }

                        !showAuthorizationDialog -> {
                            delay(420L)
                            showAuthorizationDialog = true
                        }
                    }
                }

                LaunchedEffect(setupComplete) {
                    configureSystemBars(setupComplete)
                }

                LaunchedEffect(setupComplete) {
                    if (!setupComplete) return@LaunchedEffect

                    startMaiBotService()
                    while (true) {
                        webUiOnline = MaiBotWebUiProbe.isReachable()
                        delay(if (webUiOnline) 5_000L else 1_500L)
                    }
                }

                var oobeStep by rememberSaveable { mutableStateOf(OobeStep.Permissions) }
                val setupManager = activityViewModel.setupManager
                val setupState by setupManager.state.collectAsState()

                BackHandler(enabled = !showStartupSplash && !setupComplete && oobeStep != OobeStep.Permissions) {
                    oobeStep = OobeStep.entries[oobeStep.ordinal - 1]
                }

                LaunchedEffect(oobeStep, setupComplete) {
                    if (setupComplete) return@LaunchedEffect
                    when (oobeStep) {
                        OobeStep.Permissions -> Unit
                        OobeStep.Container -> setupManager.prepareContainer()
                        OobeStep.Install -> setupManager.startInstall()
                    }
                }

                AnimatedContent(
                    targetState = showStartupSplash,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        slideInHorizontally(
                            animationSpec = spring(
                                dampingRatio = 0.86f,
                                stiffness = Spring.StiffnessMediumLow
                            )
                        ) { fullWidth ->
                            if (targetState) -fullWidth else fullWidth
                        } togetherWith slideOutHorizontally(
                            animationSpec = spring(
                                dampingRatio = 0.86f,
                                stiffness = Spring.StiffnessMediumLow
                            )
                        ) { fullWidth ->
                            if (targetState) fullWidth else -fullWidth
                        } using SizeTransform(clip = true)
                    },
                    label = "StartupSplashTransition"
                ) { showingSplash ->
                    when {
                        showingSplash -> StartupSplashPage(modifier = Modifier.fillMaxSize())

                        setupComplete -> HomeScreen(
                            webUiOnline = webUiOnline,
                            versionName = versionName,
                            terminalLogs = terminalLogs,
                            permissionState = permissions.state,
                            permissionActions = permissions.settingsActions,
                            onWakeMai = { showToast ->
                                if (webUiOnline) {
                                    openWebUi()
                                } else {
                                    startMaiBotService()
                                    if (showToast) {
                                        Toast.makeText(this@MainActivity, "正在唤醒麦麦...", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )

                        else -> Box(modifier = Modifier.fillMaxSize()) {
                            BlurTargetHost(
                                onTargetChanged = { oobeBlurTarget = it },
                                modifier = Modifier.fillMaxSize()
                            ) {
                                OobeFlowScreen(
                                    currentStep = oobeStep,
                                    setupState = setupState,
                                    permissionState = permissions.state,
                                    permissionActions = permissions.oobeActions,
                                    onNext = {
                                        when (oobeStep) {
                                            OobeStep.Permissions -> {
                                                permissions.refresh()
                                                if (permissions.isStorageGranted()) {
                                                    oobeStep = OobeStep.Container
                                                } else {
                                                    Toast.makeText(
                                                        this@MainActivity,
                                                        "请先授予必选存储权限",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                    permissions.oobeActions.requestStorage()
                                                }
                                            }
                                            OobeStep.Container -> {
                                                if (setupState.canInstall) {
                                                    oobeStep = OobeStep.Install
                                                } else {
                                                    setupManager.prepareContainer()
                                                }
                                            }
                                            OobeStep.Install -> {
                                                if (setupState.isComplete) {
                                                    setupComplete = true
                                                } else {
                                                    setupManager.startInstall()
                                                }
                                            }
                                        }
                                    },
                                    onRetry = {
                                        if (oobeStep == OobeStep.Container) {
                                            setupManager.prepareContainer()
                                        } else {
                                            setupManager.startInstall()
                                        }
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }

                            OobeAuthorizationDialog(
                                visible = showAuthorizationDialog,
                                blurTarget = oobeBlurTarget,
                                onAgree = {
                                    settingsRepository.authorizationAccepted = true
                                    authorizationAccepted = true
                                    showAuthorizationDialog = false
                                },
                                onExit = {
                                    showAuthorizationDialog = false
                                    uiScope.launch {
                                        delay(260L)
                                        finish()
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permissions.refresh()
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
                putExtra(WebUiActivity.EXTRA_URL, MaiBotWebUi.URL)
            }
        )
    }

    // 唤醒麦麦：拉起前台服务，服务内部对已在运行的 MaiBot 不会重复启动。
    private fun startMaiBotService() {
        MaiBotRuntimeService.start(this)
    }

    @Suppress("DEPRECATION")
    private fun getVersionName(): String =
        runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        }.getOrDefault("1.0")
}
