package org.maiwithu.maidroid.ui.home

import org.maiwithu.maidroid.permission.PermissionActions
import org.maiwithu.maidroid.permission.PermissionState
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import eightbitlab.com.blurview.BlurTarget
import org.maiwithu.maidroid.ui.component.BlurTargetHost
import kotlinx.coroutines.delay
import org.maiwithu.maidroid.ui.theme.PageBackground
import org.maiwithu.maidroid.ui.platforms.MessagePlatformsPage
import org.maiwithu.maidroid.ui.settings.SettingsPage
import org.maiwithu.maidroid.ui.terminal.TerminalOutputDialog
import org.maiwithu.maidroid.ui.component.horizontalSlideTransition

// WebUI 离线时每隔这么久自动唤醒一次 MaiBot；唤醒页的进度条也按这个时长走满。
internal const val WakeRetryIntervalMillis = 30_000L

/**
 * OOBE 完成后的主页。WebUI 第一次上线前显示唤醒页（每 30 秒自动唤醒一次 MaiBot），
 * 上线后切到带底部导航的三栏外壳：WebUI、消息平台、设置；右下角按钮打开终端输出。
 */
@Composable
fun HomeScreen(
    webUiOnline: Boolean,
    versionName: String,
    terminalLogs: List<String> = emptyList(),
    permissionState: PermissionState = PermissionState(),
    permissionActions: PermissionActions = PermissionActions(),
    onWakeMai: (showToast: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var dashboardVisible by rememberSaveable { mutableStateOf(false) }
    var wakeCycle by rememberSaveable { mutableIntStateOf(0) }
    var selectedTab by rememberSaveable { mutableStateOf(MainTab.WebUi) }
    var showTerminalOutput by remember { mutableStateOf(false) }

    LaunchedEffect(webUiOnline) {
        if (webUiOnline && !dashboardVisible) {
            dashboardVisible = true
            selectedTab = MainTab.WebUi
        }
    }

    LaunchedEffect(dashboardVisible, webUiOnline) {
        if (dashboardVisible || webUiOnline) return@LaunchedEffect

        while (true) {
            wakeCycle += 1
            onWakeMai(false)
            delay(WakeRetryIntervalMillis)
        }
    }

    AnimatedContent(
        targetState = dashboardVisible,
        modifier = modifier
            .fillMaxSize()
            .background(PageBackground),
        transitionSpec = { horizontalSlideTransition(forward = targetState, dampingRatio = 0.86f) },
        label = "HomeEntryTransition"
    ) { showDashboard ->
        if (showDashboard) {
            DashboardShell(
                selectedTab = selectedTab,
                webUiOnline = webUiOnline,
                versionName = versionName,
                terminalLogs = terminalLogs,
                permissionState = permissionState,
                permissionActions = permissionActions,
                onTabSelected = { selectedTab = it },
                onWakeMai = onWakeMai,
                onTerminalDismiss = { showTerminalOutput = false },
                showTerminalOutput = showTerminalOutput,
                onTerminalClick = { showTerminalOutput = true },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            WakeMaiGatePage(
                webUiOnline = webUiOnline,
                versionName = versionName,
                wakeCycle = wakeCycle,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

// 三栏外壳：页面内容包在 BlurTargetHost 里，底部悬浮导航栏据此做毛玻璃；WebView 跨标签保留，切回来不重新加载。
@Composable
private fun DashboardShell(
    selectedTab: MainTab,
    webUiOnline: Boolean,
    versionName: String,
    terminalLogs: List<String>,
    permissionState: PermissionState,
    permissionActions: PermissionActions,
    showTerminalOutput: Boolean,
    onTabSelected: (MainTab) -> Unit,
    onWakeMai: (showToast: Boolean) -> Unit,
    onTerminalClick: () -> Unit,
    onTerminalDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var blurTarget by remember { mutableStateOf<BlurTarget?>(null) }
    val webUiTabState = rememberWebUiTabState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PageBackground)
    ) {
        BlurTargetHost(
            modifier = Modifier
                .fillMaxSize()
                .background(PageBackground),
            onTargetChanged = { blurTarget = it }
        ) {
            AnimatedContent(
                targetState = selectedTab,
                modifier = Modifier
                    .fillMaxSize()
                    .background(PageBackground),
                transitionSpec = { horizontalSlideTransition(forward = targetState >= initialState, dampingRatio = 0.82f) },
                label = "HomeTabTransition"
            ) { tab ->
                when (tab) {
                    MainTab.WebUi -> WebUiTabPage(
                        webUiOnline = webUiOnline,
                        onWakeMai = { onWakeMai(true) },
                        state = webUiTabState,
                        terminalLogs = terminalLogs,
                        modifier = Modifier.fillMaxSize()
                    )

                    MainTab.Platforms -> MessagePlatformsPage(modifier = Modifier.fillMaxSize())
                    MainTab.Settings -> SettingsPage(
                        webUiOnline = webUiOnline,
                        versionName = versionName,
                        permissionState = permissionState,
                        permissionActions = permissionActions,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        MainBottomNavigation(
            selectedTab = selectedTab,
            blurTarget = blurTarget,
            onTabSelected = onTabSelected,
            onTerminalClick = onTerminalClick,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        if (showTerminalOutput) {
            TerminalOutputDialog(
                logs = terminalLogs,
                onDismiss = onTerminalDismiss
            )
        }
    }
}
