package org.maiwithu.maidroid.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import eightbitlab.com.blurview.BlurTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maiwithu.maidroid.MainViewModel
import org.maiwithu.maidroid.appDependencies
import org.maiwithu.maidroid.oobe.OobeStep
import org.maiwithu.maidroid.permission.SystemPermissionController
import org.maiwithu.maidroid.terminal.TerminalLog
import org.maiwithu.maidroid.ui.component.BlurTargetHost
import org.maiwithu.maidroid.ui.component.horizontalSlideTransition
import org.maiwithu.maidroid.ui.home.HomeScreen
import org.maiwithu.maidroid.ui.oobe.OobeAuthorizationDialog
import org.maiwithu.maidroid.ui.oobe.OobeFlowScreen
import org.maiwithu.maidroid.ui.settings.AppUpdateDialogHost
import org.maiwithu.maidroid.ui.settings.MaiBotUpdateDialogHost
import org.maiwithu.maidroid.ui.splash.StartupSplashPage

// 启动页停留时长；之后若还没同意过协议，再等一小会儿弹出授权弹窗，让页面切换动画先走完。
private const val STARTUP_SPLASH_DURATION_MS = 1_200L
private const val AUTHORIZATION_DIALOG_DELAY_MS = 420L

// 点“退出”后先让授权弹窗的收起动画播完再关闭 Activity。
private const val EXIT_ANIMATION_MS = 260L

/**
 * 应用的根界面：启动页 ->（OOBE 没完成时）OOBE 三步 + 首次授权弹窗 ->（完成后）主页，
 * 主页上启动时查到 MaiDroid 或 MaiBot 的新版本会弹更新提醒。
 *
 * 流程状态在 MainViewModel 里，权限状态和跳转在 SystemPermissionController 里；
 * 这里只负责按状态切换页面，以及处理需要 Activity 的动作（打开 WebUI、退出应用）。
 */
@Composable
internal fun MaiDroidApp(
    viewModel: MainViewModel,
    permissions: SystemPermissionController,
    versionName: String,
    onOpenWebUi: () -> Unit,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val uiScope = rememberCoroutineScope()
    val showStartupSplash by viewModel.showStartupSplash.collectAsState()
    val setupComplete by viewModel.setupComplete.collectAsState()
    val authorizationAccepted by viewModel.authorizationAccepted.collectAsState()
    val oobeStep by viewModel.oobeStep.collectAsState()
    val webUiOnline by viewModel.webUiOnline.collectAsState()
    val maiBotStatus by viewModel.maiBotStatus.collectAsState()
    val setupState by viewModel.setupManager.state.collectAsState()
    val terminalLogs by TerminalLog.logs.collectAsState()
    var showAuthorizationDialog by rememberSaveable { mutableStateOf(false) }
    var oobeBlurTarget by remember { mutableStateOf<BlurTarget?>(null) }
    val appUpdater = remember(context) { context.appDependencies.appUpdater }
    val appUpdatePrompt by appUpdater.launchPrompt.collectAsState()
    val maiBotUpdater = remember(context) { context.appDependencies.maiBotUpdater }
    val maiBotUpdatePrompt by maiBotUpdater.launchPrompt.collectAsState()
    val maiBotUpdateState by maiBotUpdater.state.collectAsState()

    LaunchedEffect(showStartupSplash) {
        if (showStartupSplash) {
            delay(STARTUP_SPLASH_DURATION_MS)
            viewModel.finishStartupSplash()
        }
    }

    // 启动页结束、还在 OOBE、又没同意过协议时弹出授权弹窗。
    LaunchedEffect(showStartupSplash, setupComplete, authorizationAccepted) {
        when {
            showStartupSplash || setupComplete || authorizationAccepted -> showAuthorizationDialog = false
            !showAuthorizationDialog -> {
                delay(AUTHORIZATION_DIALOG_DELAY_MS)
                showAuthorizationDialog = true
            }
        }
    }

    BackHandler(enabled = !showStartupSplash && !setupComplete && oobeStep != OobeStep.Permissions) {
        viewModel.goBackOobeStep()
    }

    LaunchedEffect(oobeStep, setupComplete) {
        if (!setupComplete) viewModel.onOobeStepShown(oobeStep)
    }

    AnimatedContent(
        targetState = showStartupSplash,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = { horizontalSlideTransition(forward = !targetState, dampingRatio = 0.86f) },
        label = "StartupSplashTransition"
    ) { showingSplash ->
        when {
            showingSplash -> StartupSplashPage(modifier = Modifier.fillMaxSize())

            // 主页上的“唤醒”：WebUI 在线就全屏打开它，否则拉起 MaiBot（手动点按时再提示一句）。
            setupComplete -> HomeScreen(
                webUiOnline = webUiOnline,
                versionName = versionName,
                maiBotStatus = maiBotStatus,
                // MaiDroid 更新后首次启动：唤醒页上显示 MaiBot 自动升级的进度
                maiBotUpdate = if (viewModel.autoUpdatingMaiBot) maiBotUpdateState else null,
                terminalLogs = terminalLogs,
                permissionState = permissions.state,
                permissionActions = permissions.settingsActions,
                onWakeMai = { showToast ->
                    if (webUiOnline) {
                        onOpenWebUi()
                    } else {
                        viewModel.wakeMaiBot()
                        if (showToast) {
                            Toast.makeText(context, "正在唤醒麦麦...", Toast.LENGTH_SHORT).show()
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
                            // 权限步骤的“下一步”现查一次存储权限：用户可能刚从系统设置页回来。
                            permissions.refresh()
                            if (!viewModel.onOobeNext(storageGranted = permissions.isStorageGranted())) {
                                Toast.makeText(context, "请先授予必选存储权限", Toast.LENGTH_SHORT).show()
                                permissions.oobeActions.requestStorage()
                            }
                        },
                        onRetry = viewModel::retryOobeStep,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                OobeAuthorizationDialog(
                    visible = showAuthorizationDialog,
                    blurTarget = oobeBlurTarget,
                    onAgree = {
                        viewModel.acceptAuthorization()
                        showAuthorizationDialog = false
                    },
                    onExit = {
                        showAuthorizationDialog = false
                        uiScope.launch {
                            delay(EXIT_ANIMATION_MS)
                            onExit()
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    // 启动时自动检查查到新版本：启动页播完、进了主页再弹更新弹窗提醒，用户可以直接下载安装或更新麦麦；
    // 关掉后本次不再弹，下次打开应用重新检查、重新提醒。结果出来时人在全屏 WebUI 里的话，回到主页再弹。
    // MaiDroid 本体和 MaiBot 都有新版本时不叠着弹：先弹 MaiDroid，关掉后再弹 MaiBot。
    if (setupComplete && !showStartupSplash) {
        when {
            appUpdatePrompt -> AppUpdateDialogHost(onDismiss = appUpdater::dismissLaunchPrompt)
            maiBotUpdatePrompt -> MaiBotUpdateDialogHost(onDismiss = maiBotUpdater::dismissLaunchPrompt)
        }
    }
}
