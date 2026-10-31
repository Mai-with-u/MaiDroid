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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import eightbitlab.com.blurview.BlurTarget
import kotlinx.coroutines.delay
import org.maiwithu.maidroid.maibot.MaiBotProcessStatus
import org.maiwithu.maidroid.permission.PermissionActions
import org.maiwithu.maidroid.permission.PermissionState
import org.maiwithu.maidroid.ui.component.BlurTargetHost
import org.maiwithu.maidroid.ui.component.LocalBottomNavigationClearance
import org.maiwithu.maidroid.ui.component.horizontalSlideTransition
import org.maiwithu.maidroid.ui.platform.MessagePlatformsPage
import org.maiwithu.maidroid.ui.settings.SettingsPage
import org.maiwithu.maidroid.ui.terminal.TerminalOutputScreen
import org.maiwithu.maidroid.ui.theme.PageBackground

// 唤醒页还在时（WebUI 离线），每隔这么久自动唤醒一次 MaiBot。
internal const val WakeRetryIntervalMillis = 30_000L

/**
 * OOBE 完成后的主页。WebUI 第一次上线前盖着唤醒页（开机自检画面，每 30 秒自动唤醒一次 MaiBot），
 * 自检走完或点 SKIP 后切到三栏外壳：WebUI、消息平台、设置；竖屏导航在底部（右下角按钮打开终端输出），横屏导航在左侧。
 */
@Composable
internal fun HomeScreen(
    webUiOnline: Boolean,
    versionName: String,
    onWakeMai: (showToast: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    maiBotStatus: MaiBotProcessStatus = MaiBotProcessStatus(),
    terminalLogs: List<String> = emptyList(),
    permissionState: PermissionState = PermissionState(),
    permissionActions: PermissionActions = PermissionActions()
) {
    // 唤醒页盖在三栏外壳上面。tabsVisible：自检走完或点了 SKIP，唤醒页开始收尾、自动唤醒停止；
    // 等唤醒页的快门完全拉开，wakePageVisible 变 false，把这一层移除。
    var tabsVisible by rememberSaveable { mutableStateOf(false) }
    var wakePageVisible by rememberSaveable { mutableStateOf(true) }
    // WebUI 一上线就先把外壳组合在唤醒页底下：WebView 趁自检收尾的一两秒把网页加载好，快门拉开时直接是加载完的页面。
    // 组合后不再撤掉，WebUI 短暂掉线也不会让 WebView 重新加载。
    var shellComposed by rememberSaveable { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableStateOf(HomeTab.WebUi) }
    var showTerminalOutput by remember { mutableStateOf(false) }

    LaunchedEffect(webUiOnline) {
        if (webUiOnline) shellComposed = true
    }

    LaunchedEffect(tabsVisible, webUiOnline) {
        if (tabsVisible || webUiOnline) return@LaunchedEffect

        while (true) {
            onWakeMai(false)
            delay(WakeRetryIntervalMillis)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PageBackground)
    ) {
        if (shellComposed || tabsVisible) {
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
        }

        if (wakePageVisible) {
            WakeMaiPage(
                webUiOnline = webUiOnline,
                maiBotStatus = maiBotStatus,
                versionName = versionName,
                leaving = tabsVisible,
                // 自检走完（WebUI 已上线）或点了 SKIP：外壳先在底下就位并停在 WebUI 页签，唤醒页开始收尾。
                // 点 SKIP 进来时 WebUI 可能还没上线，WebUI 页签会显示离线状态，自动唤醒也随之停止，改由那里的按钮手动唤醒。
                onFinished = {
                    if (!tabsVisible) {
                        selectedTab = HomeTab.WebUi
                        tabsVisible = true
                    }
                },
                onLeft = { wakePageVisible = false },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * 三栏外壳：页面内容包在 BlurTargetHost 里，竖屏底部悬浮导航栏据此做毛玻璃；WebView 跨标签保留，切回来不重新加载。
 * 窗口宽大于高时导航改为左侧竖栏（见 homeUsesNavigationRail），页面让出这一列，不再被导航盖住。
 * 横屏摄像头挖孔转到屏幕左右两侧，整个外壳左右让开安全区：导航栏和页面都不会画进挖孔。
 */
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

    // 竖屏时 WebUI 页签里网页自带底部导航，APP 底栏再叠一层会占掉约五分之一屏幕：
    // 在 WebUI 页签默认把 APP 底栏收成右下角的小按钮 -> 点开后盖一层暗色遮罩并弹出完整底栏 ->
    // 点遮罩、按返回键、切换标签或打开终端都会收起。消息平台、设置页照常显示完整底栏。
    // 横屏侧栏不挡网页，不需要收起。
    var navExpanded by rememberSaveable { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(PageBackground)
    ) {
        val useRail = homeUsesNavigationRail(maxWidth.value, maxHeight.value)
        val navCollapsible = !useRail && selectedTab == HomeTab.WebUi
        BackHandler(enabled = navCollapsible && navExpanded) { navExpanded = false }

        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        val safeLeft = with(density) { WindowInsets.safeDrawing.getLeft(this, layoutDirection).toDp() }
        val safeRight = with(density) { WindowInsets.safeDrawing.getRight(this, layoutDirection).toDp() }

        val selectTab: (HomeTab) -> Unit = { tab ->
            navExpanded = false
            onTabSelected(tab)
        }
        val openTerminal = {
            navExpanded = false
            onTerminalClick()
        }

        // 三个页面；横竖屏共用，只是摆放的位置和列表末尾留白不同。
        val tabPages: @Composable (Modifier) -> Unit = { pagesModifier ->
            BlurTargetHost(
                modifier = pagesModifier.background(PageBackground),
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
                            showRefreshButton = !useRail,
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
        }

        if (useRail) {
            // 横屏：左侧竖栏 -> 右边页面；页面不被导航盖住，列表末尾只留一点呼吸空间。
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .absolutePadding(left = safeLeft, right = safeRight)
            ) {
                HomeNavigationRail(
                    selectedTab = selectedTab,
                    onTabSelected = selectTab,
                    onTerminalClick = openTerminal,
                    onRefreshWebUi = if (selectedTab == HomeTab.WebUi) webUiTabState::reload else null
                )

                CompositionLocalProvider(LocalBottomNavigationClearance provides RailBottomClearance) {
                    tabPages(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .absolutePadding(left = safeLeft, right = safeRight)
            ) {
                tabPages(Modifier.fillMaxSize())

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
                            // 遮罩只负责收起底栏，不需要按压反馈。
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
                        onTabSelected = selectTab,
                        onTerminalClick = openTerminal
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
            }
        }

        // 终端输出页盖在整个外壳最上层（含导航），自己让开系统栏和挖孔。
        TerminalOutputScreen(
            visible = showTerminalOutput,
            logs = terminalLogs,
            onDismiss = onTerminalDismiss
        )
    }
}

/** 横屏侧栏模式下列表末尾的留白：导航不盖住页面，只留一点让最后一项不贴底。 */
private val RailBottomClearance = 16.dp
