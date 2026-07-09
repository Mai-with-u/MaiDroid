package org.maiwithu.maidroid.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import kotlinx.coroutines.delay

internal const val WakeRetryIntervalMillis = 30_000L

@Composable
fun HomeScreen(
    webUiOnline: Boolean,
    versionName: String,
    terminalLogs: List<String> = emptyList(),
    permissionState: PermissionManagementState = PermissionManagementState(),
    permissionActions: PermissionManagementActions = PermissionManagementActions(),
    onWakeMai: (showToast: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var dashboardVisible by rememberSaveable { mutableStateOf(false) }
    var wakeCycle by rememberSaveable { mutableIntStateOf(0) }
    var selectedTab by rememberSaveable { mutableStateOf(MainTab.WebUi) }
    var showTerminalOutput by remember { mutableStateOf(false) }

    LaunchedEffect(webUiOnline) {
        if (webUiOnline) {
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
            .background(MainSurface),
        transitionSpec = { entryTransition() },
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

@Composable
private fun DashboardShell(
    selectedTab: MainTab,
    webUiOnline: Boolean,
    versionName: String,
    terminalLogs: List<String>,
    permissionState: PermissionManagementState,
    permissionActions: PermissionManagementActions,
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
            .background(MainSurface)
    ) {
        BlurTargetHost(
            modifier = Modifier
                .fillMaxSize()
                .background(MainSurface),
            onTargetChanged = { blurTarget = it }
        ) {
            AnimatedContent(
                targetState = selectedTab,
                modifier = Modifier
                    .fillMaxSize()
                    .background(MainSurface),
                transitionSpec = { tabTransition() },
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

private fun AnimatedContentTransitionScope<Boolean>.entryTransition() =
    slideInHorizontally(
        animationSpec = spring(
            dampingRatio = 0.86f,
            stiffness = Spring.StiffnessMediumLow
        )
    ) { fullWidth ->
        if (targetState) fullWidth else -fullWidth
    } togetherWith slideOutHorizontally(
        animationSpec = spring(
            dampingRatio = 0.86f,
            stiffness = Spring.StiffnessMediumLow
        )
    ) { fullWidth ->
        if (targetState) -fullWidth else fullWidth
    } using SizeTransform(clip = true)

private fun AnimatedContentTransitionScope<MainTab>.tabTransition() =
    slideInHorizontally(
        animationSpec = spring(
            dampingRatio = 0.82f,
            stiffness = Spring.StiffnessMediumLow
        )
    ) { fullWidth ->
        val direction = targetState.ordinal - initialState.ordinal
        if (direction >= 0) fullWidth else -fullWidth
    } togetherWith slideOutHorizontally(
        animationSpec = spring(
            dampingRatio = 0.82f,
            stiffness = Spring.StiffnessMediumLow
        )
    ) { fullWidth ->
        val direction = targetState.ordinal - initialState.ordinal
        if (direction >= 0) -fullWidth else fullWidth
    } using SizeTransform(clip = true)
