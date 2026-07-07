package org.maiwithu.maidroid.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import eightbitlab.com.blurview.BlurTarget

@Composable
fun HomeScreen(
    webUiOnline: Boolean,
    versionName: String,
    terminalLogs: List<String> = emptyList(),
    onWakeMai: () -> Unit,
    modifier: Modifier = Modifier
) {
    var dashboardVisible by rememberSaveable { mutableStateOf(false) }
    var wakeRequested by rememberSaveable { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableStateOf(MainTab.WebUi) }
    var showTerminalOutput by remember { mutableStateOf(false) }

    LaunchedEffect(webUiOnline, wakeRequested) {
        if (wakeRequested && webUiOnline) {
            dashboardVisible = true
            selectedTab = MainTab.WebUi
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
                onWakeClick = {
                    if (webUiOnline) {
                        dashboardVisible = true
                        selectedTab = MainTab.WebUi
                    } else {
                        wakeRequested = true
                        onWakeMai()
                    }
                },
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
    showTerminalOutput: Boolean,
    onTabSelected: (MainTab) -> Unit,
    onWakeMai: () -> Unit,
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
                        onWakeMai = onWakeMai,
                        state = webUiTabState,
                        terminalLogs = terminalLogs,
                        modifier = Modifier.fillMaxSize()
                    )

                    MainTab.Platforms -> MessagePlatformsPage(modifier = Modifier.fillMaxSize())
                    MainTab.Settings -> SettingsPage(
                        webUiOnline = webUiOnline,
                        versionName = versionName,
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
    (fadeIn(animationSpec = tween(220)) +
        slideInHorizontally(animationSpec = tween(220)) { fullWidth ->
            if (targetState) fullWidth / 5 else -fullWidth / 5
        }) togetherWith
        (fadeOut(animationSpec = tween(160)) +
            slideOutHorizontally(animationSpec = tween(200)) { fullWidth ->
                if (targetState) -fullWidth / 5 else fullWidth / 5
            }) using SizeTransform(clip = false)

private fun AnimatedContentTransitionScope<MainTab>.tabTransition() =
    (fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing)) +
        slideInHorizontally(animationSpec = tween(260, easing = FastOutSlowInEasing)) { fullWidth ->
            val direction = targetState.ordinal - initialState.ordinal
            if (direction >= 0) fullWidth / 5 else -fullWidth / 5
        }) togetherWith
        (fadeOut(animationSpec = tween(180, easing = FastOutSlowInEasing)) +
            slideOutHorizontally(animationSpec = tween(240, easing = FastOutSlowInEasing)) { fullWidth ->
                val direction = targetState.ordinal - initialState.ordinal
                if (direction >= 0) -fullWidth / 5 else fullWidth / 5
            }) using SizeTransform(clip = false)
