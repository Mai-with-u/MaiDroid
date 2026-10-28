package org.maiwithu.maidroid.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import eightbitlab.com.blurview.BlurTarget
import kotlinx.coroutines.delay
import org.maiwithu.maidroid.permission.PermissionActions
import org.maiwithu.maidroid.permission.PermissionState
import org.maiwithu.maidroid.ui.component.BlurTargetHost
import org.maiwithu.maidroid.ui.component.horizontalSlideTransition
import org.maiwithu.maidroid.ui.platform.MessagePlatformsPage
import org.maiwithu.maidroid.ui.settings.SettingsPage
import org.maiwithu.maidroid.ui.terminal.TerminalOutputDialog
import org.maiwithu.maidroid.ui.theme.PageBackground

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
    onWakeMai: (showToast: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    terminalLogs: List<String> = emptyList(),
    permissionState: PermissionState = PermissionState(),
    permissionActions: PermissionActions = PermissionActions()
) {
    var tabsVisible by rememberSaveable { mutableStateOf(false) }
    var wakeCycle by rememberSaveable { mutableIntStateOf(0) }
    var selectedTab by rememberSaveable { mutableStateOf(HomeTab.WebUi) }
    var showTerminalOutput by remember { mutableStateOf(false) }

    LaunchedEffect(webUiOnline) {
        if (webUiOnline && !tabsVisible) {
            tabsVisible = true
            selectedTab = HomeTab.WebUi
        }
    }

    LaunchedEffect(tabsVisible, webUiOnline) {
        if (tabsVisible || webUiOnline) return@LaunchedEffect

        while (true) {
            wakeCycle += 1
            onWakeMai(false)
            delay(WakeRetryIntervalMillis)
        }
    }

    AnimatedContent(
        targetState = tabsVisible,
        modifier = modifier
            .fillMaxSize()
            .background(PageBackground),
        transitionSpec = { horizontalSlideTransition(forward = targetState, dampingRatio = 0.86f) },
        label = "HomeEntryTransition"
    ) { showTabs ->
        if (showTabs) {
            HomeTabsShell(
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
            WakeMaiPage(
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
private fun HomeTabsShell(
    selectedTab: HomeTab,
    webUiOnline: Boolean,
    versionName: String,
    terminalLogs: List<String>,
    permissionState: PermissionState,
    permissionActions: PermissionActions,
    showTerminalOutput: Boolean,
    onTabSelected: (HomeTab) -> Unit,
    onWakeMai: (showToast: Boolean) -> Unit,
    onTerminalClick: () -> Unit,
    onTerminalDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var blurTarget by remember { mutableStateOf<BlurTarget?>(null) }
    val webUiTabState = rememberWebUiTabState()

    // WebUI 页签里网页自带底部导航，APP 底栏再叠一层会占掉约五分之一屏幕：
    // 在 WebUI 页签默认把 APP 底栏收成右下角的小按钮 -> 点开后盖一层暗色遮罩并弹出完整底栏 ->
    // 点遮罩、按返回键、切换标签或打开终端都会收起。消息平台、设置页照常显示完整底栏。
    var navExpanded by rememberSaveable { mutableStateOf(false) }
    val navCollapsible = selectedTab == HomeTab.WebUi
    BackHandler(enabled = navCollapsible && navExpanded) { navExpanded = false }

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
                    HomeTab.WebUi -> WebUiTabPage(
                        webUiOnline = webUiOnline,
                        onWakeMai = { onWakeMai(true) },
                        state = webUiTabState,
                        terminalLogs = terminalLogs,
                        modifier = Modifier.fillMaxSize()
                    )

                    HomeTab.Platforms -> MessagePlatformsPage(modifier = Modifier.fillMaxSize())
                    HomeTab.Settings -> SettingsPage(
                        webUiOnline = webUiOnline,
                        versionName = versionName,
                        permissionState = permissionState,
                        permissionActions = permissionActions,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // 展开时的遮罩：挡住网页的点击，点一下就收起底栏。
        AnimatedVisibility(
            visible = navCollapsible && navExpanded,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.matchParentSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { navExpanded = false }
                    )
            )
        }

        AnimatedVisibility(
            visible = !navCollapsible || navExpanded,
            enter = fadeIn() + slideInVertically { height -> height / 2 },
            exit = fadeOut() + slideOutVertically { height -> height / 2 },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            HomeBottomNavigation(
                selectedTab = selectedTab,
                blurTarget = blurTarget,
                onTabSelected = { tab ->
                    navExpanded = false
                    onTabSelected(tab)
                },
                onTerminalClick = {
                    navExpanded = false
                    onTerminalClick()
                }
            )
        }

        AnimatedVisibility(
            visible = navCollapsible && !navExpanded,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = CollapsedHomeNavigationBottomPadding)
        ) {
            CollapsedHomeNavigationButton(
                blurTarget = blurTarget,
                onClick = { navExpanded = true }
            )
        }

        if (showTerminalOutput) {
            TerminalOutputDialog(
                logs = terminalLogs,
                onDismiss = onTerminalDismiss
            )
        }
    }
}
