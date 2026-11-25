package org.maiwithu.maidroid.ui.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maiwithu.maidroid.appDependencies
import org.maiwithu.maidroid.appupdate.AppUpdateAction
import org.maiwithu.maidroid.appupdate.AppUpdatePhase
import org.maiwithu.maidroid.appupdate.AppUpdateState
import org.maiwithu.maidroid.files.openMaiBotInFileManager
import org.maiwithu.maidroid.maibot.update.MaiBotUpdatePhase
import org.maiwithu.maidroid.maibot.update.MaiBotUpdateState
import org.maiwithu.maidroid.permission.PermissionActions
import org.maiwithu.maidroid.permission.PermissionState
import org.maiwithu.maidroid.ssh.ContainerSshState
import org.maiwithu.maidroid.ui.cleanup.StorageCleanupDialog
import org.maiwithu.maidroid.ui.component.CollapsingGlassHeaderContainer
import org.maiwithu.maidroid.ui.component.FlatFeatureIconBadge
import org.maiwithu.maidroid.ui.component.FlatFeatureIcons
import org.maiwithu.maidroid.ui.component.FlatSwitch
import org.maiwithu.maidroid.ui.component.HomeTwoPaneSpec
import org.maiwithu.maidroid.ui.component.LocalBottomNavigationClearance
import org.maiwithu.maidroid.ui.component.computeHeaderSubtitleAlpha
import org.maiwithu.maidroid.ui.component.computeLazyHeaderCollapseProgress
import org.maiwithu.maidroid.ui.component.horizontalSlideTransition
import org.maiwithu.maidroid.ui.component.overscrollBody
import org.maiwithu.maidroid.ui.component.overscrollHeader
import org.maiwithu.maidroid.ui.component.rememberSplitHeaderOverscrollEffect
import org.maiwithu.maidroid.ui.component.statusBarTopInset
import org.maiwithu.maidroid.ui.diagnostics.FeedbackDialog
import org.maiwithu.maidroid.ui.diagnostics.QQ_FEEDBACK_GROUP_NAME
import org.maiwithu.maidroid.ui.diagnostics.QQ_FEEDBACK_GROUP_NUMBER
import org.maiwithu.maidroid.ui.diagnostics.QqFeedbackGroupDialog
import org.maiwithu.maidroid.ui.platform.installedPlatformCards
import org.maiwithu.maidroid.ui.remote.AnnouncementBannerSection
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardDivider
import org.maiwithu.maidroid.ui.theme.CardSurface
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.IconCoralPink
import org.maiwithu.maidroid.ui.theme.IconCyanBlue
import org.maiwithu.maidroid.ui.theme.IconFreshGreen
import org.maiwithu.maidroid.ui.theme.IconIrisBlue
import org.maiwithu.maidroid.ui.theme.IconMintGreen
import org.maiwithu.maidroid.ui.theme.IconPeachOrange
import org.maiwithu.maidroid.ui.theme.IconSkyBlue
import org.maiwithu.maidroid.ui.theme.IconSunnyAmber
import org.maiwithu.maidroid.ui.theme.IconTealMint
import org.maiwithu.maidroid.ui.theme.PageBackground
import org.maiwithu.maidroid.ui.theme.StatusOnline
import org.maiwithu.maidroid.ui.theme.StatusWarning
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes

private const val PermissionSettingsDetail = "permissions"
internal const val MirrorSettingsDetail = "mirrors"

/**
 * 设置页：服务（WebUI 状态、MaiBot 在线更新、容器 SSH、MaiBot 文件）、系统（权限管理）、
 * 应用（消息平台、应用版本与 MaiDroid 自身的更新）、帮助（反馈问题、QQ 反馈群、清理空间），最底下是“安全退出”按钮。
 * 横屏或宽屏时左右分栏：右侧默认只居中显示一枚液态玻璃应用图标，点击左侧“权限管理”后再展示权限详情（再点或返回恢复默认图标）；
 * 竖屏时点“权限管理”推入详情页，返回键退回列表。
 */
@Composable
internal fun SettingsPage(
    webUiOnline: Boolean,
    versionName: String,
    modifier: Modifier = Modifier,
    permissionState: PermissionState = PermissionState(),
    permissionActions: PermissionActions = PermissionActions()
) {
    val context = LocalContext.current
    val sshManager = remember(context) { context.appDependencies.containerSsh }
    val sshState by sshManager.state.collectAsState()
    val napCatManager = remember(context) { context.appDependencies.napCat }
    val napCatState by napCatManager.state.collectAsState()
    val snowLumaManager = remember(context) { context.appDependencies.snowLuma }
    val snowLumaState by snowLumaManager.state.collectAsState()
    val updateManager = remember(context) { context.appDependencies.maiBotUpdater }
    val updateState by updateManager.state.collectAsState()
    val appUpdater = remember(context) { context.appDependencies.appUpdater }
    val appUpdateState by appUpdater.state.collectAsState()
    val qqOfficialManager = remember(context) { context.appDependencies.qqOfficial }
    val qqOfficialState by qqOfficialManager.state.collectAsState()
    val mirrorManager = remember(context) { context.appDependencies.mirrorManager }
    val mirrorState by mirrorManager.state.collectAsState()
    val mirrorSummaryText = remember(mirrorState) { mirrorSettingsSummaryText(mirrorState) }
    // 设置页的“运行中平台”与消息平台页同一口径：NapCat 或 SnowLuma 在线、官方机器人已启用且无连接报错都算。
    val runningPlatformCount = remember(napCatState, snowLumaState, qqOfficialState) {
        installedPlatformCards(napCatState, snowLumaState, qqOfficialState).count { it.running }
    }
    var showSshDialog by rememberSaveable { mutableStateOf(false) }
    // 安全退出的二次确认；转屏重建时保留，免得用户刚点开就没了
    var showSafeExitConfirm by rememberSaveable { mutableStateOf(false) }
    var showUpdateDialog by rememberSaveable { mutableStateOf(false) }
    // 反馈弹窗里要去系统“另存为”选位置、QQ 群弹窗要切去手机 QQ 加群，回来时 Activity 可能被重建，开关都要保存下来
    var showFeedbackDialog by rememberSaveable { mutableStateOf(false) }
    var showQqGroupDialog by rememberSaveable { mutableStateOf(false) }
    var showCleanupDialog by rememberSaveable { mutableStateOf(false) }
    // 去系统里开“安装未知应用”再回来时 Activity 可能被重建，弹窗开关要能保存下来
    var showAppUpdateDialog by rememberSaveable { mutableStateOf(false) }
    var selectedDetail by rememberSaveable { mutableStateOf<String?>(null) }
    var sshPortText by rememberSaveable { mutableStateOf(sshState.port.toString()) }
    var sshPasswordText by rememberSaveable { mutableStateOf("") }

    // 进入设置页时读取一次当前 MaiBot 版本，供“MaiBot 更新”一行显示。
    LaunchedEffect(updateManager) {
        updateManager.loadInstalledRevision()
    }

    // 设置页的更新弹窗开着时，启动时的自动检查（还没出结果的话）改在这个弹窗里显示结果，不再另弹一个提醒。
    LaunchedEffect(showAppUpdateDialog) {
        if (showAppUpdateDialog) appUpdater.dismissLaunchPrompt()
    }
    LaunchedEffect(showUpdateDialog) {
        if (showUpdateDialog) updateManager.dismissLaunchPrompt()
    }

    // 点“应用版本”就开始检查：已经有结果（有新版本、下载中、待安装）时只打开弹窗，不重新检查。
    fun openAppUpdateDialog() {
        showAppUpdateDialog = true
        val state = appUpdater.state.value
        val needsCheck = state.phase == AppUpdatePhase.Idle ||
            state.phase == AppUpdatePhase.UpToDate ||
            (state.phase == AppUpdatePhase.Failed && state.failedAction == AppUpdateAction.Check)
        if (needsCheck) appUpdater.checkForUpdate()
    }

    fun openSshDialog() {
        sshPortText = sshState.port.toString()
        sshPasswordText = ""
        showSshDialog = true
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(PageBackground)
    ) {
        // 横屏挖孔的左右安全区由主页外壳统一让开，这里拿到的宽度已经是安全区内的宽度。
        val useTwoPane = settingsUseTwoPane(
            widthDp = maxWidth.value,
            heightDp = maxHeight.value
        )
        // 左右栏共用的顶部标题展开留白与手机横屏紧凑态开关，确保主从两栏标题基线与字号严丝合缝。
        val topSpacing = HomeTwoPaneSpec.headerTopSpacing(maxHeight.value)
        val compactTwoPane = maxHeight.value < HomeTwoPaneSpec.CompactTwoPaneMaxHeightDp

        // 无论是竖屏推入还是横屏在右栏点开了权限管理，按系统返回键都可以退回默认列表或右栏液态玻璃图标空态。
        BackHandler(enabled = selectedDetail != null) {
            selectedDetail = null
        }

        // 用系统“文件”应用打开 MaiBot 目录；没有文件应用接手时告诉用户去别的文件管理器里添加
        val onOpenFilesClick: () -> Unit = {
            if (!openMaiBotInFileManager(context)) {
                Toast.makeText(
                    context,
                    "没有找到系统文件应用，可在 MT 管理器等文件管理器中添加“MaiBot”存储",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        val onSshEnabledChange: (Boolean) -> Unit = { enabled ->
            if (enabled && !sshState.configured) {
                Toast.makeText(context, "请先设置端口和 root 密码", Toast.LENGTH_SHORT).show()
                openSshDialog()
            } else {
                sshManager.setEnabled(enabled)
                if (enabled) {
                    requestContainerSshSync(context)
                }
            }
        }

        if (useTwoPane) {
            // 横屏与宽屏采用全站统一的 38% : 62% 分栏，中间保留 0.5dp 细竖线凸显主从设置与详情层级。
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(PageBackground)
            ) {
                SettingsNavigationPane(
                    webUiOnline = webUiOnline,
                    versionName = versionName,
                    permissionState = permissionState,
                    sshState = sshState,
                    updateState = updateState,
                    runningPlatformCount = runningPlatformCount,
                    permissionSelected = selectedDetail == PermissionSettingsDetail,
                    mirrorSummaryText = mirrorSummaryText,
                    mirrorSelected = selectedDetail == MirrorSettingsDetail,
                    compact = true,
                    expandedTopOffset = topSpacing,
                    onPermissionClick = {
                        selectedDetail = resolveSettingsDetailSelection(
                            currentDetail = selectedDetail,
                            targetDetail = PermissionSettingsDetail,
                            useTwoPane = true
                        )
                    },
                    onMirrorClick = {
                        selectedDetail = resolveSettingsDetailSelection(
                            currentDetail = selectedDetail,
                            targetDetail = MirrorSettingsDetail,
                            useTwoPane = true
                        )
                    },
                    onSshConfigureClick = { openSshDialog() },
                    onSshEnabledChange = onSshEnabledChange,
                    onUpdateClick = { showUpdateDialog = true },
                    onOpenFilesClick = onOpenFilesClick,
                    appUpdateState = appUpdateState,
                    onAppUpdateClick = { openAppUpdateDialog() },
                    onSafeExitClick = { showSafeExitConfirm = true },
                    onFeedbackClick = { showFeedbackDialog = true },
                    onQqGroupClick = { showQqGroupDialog = true },
                    onCleanupClick = { showCleanupDialog = true },
                    modifier = Modifier
                        .weight(HomeTwoPaneSpec.LeftPaneWeight)
                        .fillMaxHeight()
                )

                Box(
                    modifier = Modifier
                        .width(HomeTwoPaneSpec.MasterDetailDividerThickness)
                        .fillMaxHeight()
                        .background(CardDivider)
                )

                Box(
                    modifier = Modifier
                        .weight(HomeTwoPaneSpec.RightPaneWeight)
                        .fillMaxHeight()
                        .background(PageBackground)
                ) {
                    AnimatedContent(
                        targetState = selectedDetail,
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = {
                            fadeIn(tween(durationMillis = 220)) togetherWith
                                fadeOut(tween(durationMillis = 180))
                        },
                        label = "SettingsTwoPaneDetail"
                    ) { detail ->
                        when (detail) {
                            PermissionSettingsDetail -> PermissionManagementPage(
                                state = permissionState,
                                actions = permissionActions,
                                onBack = { selectedDetail = null },
                                showBackButton = false,
                                compact = compactTwoPane,
                                expandedTopOffset = topSpacing,
                                modifier = Modifier.fillMaxSize()
                            )
                            MirrorSettingsDetail -> MirrorSettingsPage(
                                manager = mirrorManager,
                                state = mirrorState,
                                onBack = { selectedDetail = null },
                                showBackButton = false,
                                compact = compactTwoPane,
                                expandedTopOffset = topSpacing,
                                modifier = Modifier.fillMaxSize()
                            )
                            else -> {
                                // 横屏/宽屏默认不展示任何具体设置详情或文字提示，仅在右栏居中显示纯净的液态玻璃应用图标。
                                SettingsEmptyDetailPane(
                                    compact = compactTwoPane,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            }
        } else {
            AnimatedContent(
                targetState = selectedDetail,
                modifier = Modifier
                    .fillMaxSize()
                    .background(PageBackground),
                transitionSpec = { horizontalSlideTransition(forward = targetState != null, dampingRatio = 0.82f) },
                label = "SettingsPermissionTransition"
            ) { detail ->
                when (detail) {
                    PermissionSettingsDetail -> PermissionManagementPage(
                        state = permissionState,
                        actions = permissionActions,
                        onBack = { selectedDetail = null },
                        expandedTopOffset = topSpacing,
                        modifier = Modifier.fillMaxSize()
                    )
                    MirrorSettingsDetail -> MirrorSettingsPage(
                        manager = mirrorManager,
                        state = mirrorState,
                        onBack = { selectedDetail = null },
                        expandedTopOffset = topSpacing,
                        modifier = Modifier.fillMaxSize()
                    )
                    else -> {
                        SettingsNavigationPane(
                            webUiOnline = webUiOnline,
                            versionName = versionName,
                            permissionState = permissionState,
                            sshState = sshState,
                            updateState = updateState,
                            runningPlatformCount = runningPlatformCount,
                            permissionSelected = false,
                            mirrorSummaryText = mirrorSummaryText,
                            mirrorSelected = false,
                            compact = false,
                            expandedTopOffset = topSpacing,
                            onPermissionClick = {
                                selectedDetail = resolveSettingsDetailSelection(
                                    currentDetail = selectedDetail,
                                    targetDetail = PermissionSettingsDetail,
                                    useTwoPane = false
                                )
                            },
                            onMirrorClick = {
                                selectedDetail = resolveSettingsDetailSelection(
                                    currentDetail = selectedDetail,
                                    targetDetail = MirrorSettingsDetail,
                                    useTwoPane = false
                                )
                            },
                            onSshConfigureClick = { openSshDialog() },
                            onSshEnabledChange = onSshEnabledChange,
                            onUpdateClick = { showUpdateDialog = true },
                            onOpenFilesClick = onOpenFilesClick,
                            appUpdateState = appUpdateState,
                            onAppUpdateClick = { openAppUpdateDialog() },
                            onSafeExitClick = { showSafeExitConfirm = true },
                            onFeedbackClick = { showFeedbackDialog = true },
                            onQqGroupClick = { showQqGroupDialog = true },
                            onCleanupClick = { showCleanupDialog = true },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }

        if (showUpdateDialog) {
            // 设置页点开的 MaiBot 更新弹窗底部带“版本通道”选择器（正式版 / 测试版）；启动时自动弹的提醒不带
            MaiBotUpdateDialogHost(onDismiss = { showUpdateDialog = false }, showChannelSelector = true)
        }

        if (showAppUpdateDialog) {
            // 设置页点开的更新弹窗底部带“关闭 MaiBot 自动更新”开关；启动时自动弹的提醒不带
            AppUpdateDialogHost(onDismiss = { showAppUpdateDialog = false }, showMaiBotAutoUpdateSwitch = true)
        }

        if (showFeedbackDialog) {
            FeedbackDialog(failure = null, onDismiss = { showFeedbackDialog = false })
        }

        if (showQqGroupDialog) {
            QqFeedbackGroupDialog(onDismiss = { showQqGroupDialog = false })
        }

        if (showCleanupDialog) {
            StorageCleanupDialog(onDismiss = { showCleanupDialog = false })
        }

        if (showSafeExitConfirm) {
            SafeExitConfirmDialog(
                onConfirm = {
                    showSafeExitConfirm = false
                    context.appDependencies.safeExit.request(context)
                },
                onDismiss = { showSafeExitConfirm = false }
            )
        }

        if (showSshDialog) {
            ContainerSshConfigDialog(
                portText = sshPortText,
                passwordText = sshPasswordText,
                configured = sshState.configured,
                onPortTextChange = { value ->
                    sshPortText = value.filter(Char::isDigit).take(5)
                },
                onPasswordTextChange = { sshPasswordText = it },
                onDismiss = { showSshDialog = false },
                onConfirm = { port, password ->
                    sshManager.saveConfig(port, password)
                    showSshDialog = false
                    Toast.makeText(context, "容器 SSH 配置已保存", Toast.LENGTH_SHORT).show()
                    if (sshState.enabled) {
                        requestContainerSshSync(context)
                    }
                }
            )
        }
    }
}

/** 横屏窗口（至少 520dp 宽以免极窄分屏挤爆两栏）与平板大屏启用左右主从双栏。 */
internal fun settingsUseTwoPane(widthDp: Float, heightDp: Float): Boolean =
    (widthDp > heightDp && widthDp >= HomeTwoPaneSpec.CompactTwoPaneMinWidthDp) || widthDp >= 960f

/** 点击设置详情项时的目标状态：双栏模式再次点击当前已选项可收起回到居中图标空态，竖屏单列则始终进入目标详情。 */
internal fun resolveSettingsDetailSelection(
    currentDetail: String?,
    targetDetail: String,
    useTwoPane: Boolean
): String? = if (useTwoPane && currentDetail == targetDetail) null else targetDetail

internal fun settingsNavigationWidthDp(availableWidthDp: Float): Float =
    HomeTwoPaneSpec.leftPaneWidthDp(availableWidthDp)

@Composable
private fun SettingsNavigationPane(
    webUiOnline: Boolean,
    versionName: String,
    permissionState: PermissionState,
    sshState: ContainerSshState,
    updateState: MaiBotUpdateState,
    runningPlatformCount: Int,
    permissionSelected: Boolean,
    mirrorSummaryText: String,
    mirrorSelected: Boolean,
    compact: Boolean,
    expandedTopOffset: Dp,
    onPermissionClick: () -> Unit,
    onMirrorClick: () -> Unit,
    onSshConfigureClick: () -> Unit,
    onSshEnabledChange: (Boolean) -> Unit,
    onUpdateClick: () -> Unit,
    onOpenFilesClick: () -> Unit,
    appUpdateState: AppUpdateState,
    onAppUpdateClick: () -> Unit,
    onSafeExitClick: () -> Unit,
    onFeedbackClick: () -> Unit,
    onQqGroupClick: () -> Unit,
    onCleanupClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val statusBarTop = statusBarTopInset()
    val context = LocalContext.current
    val settings = remember(context) { context.appDependencies.settings }
    val remoteOps = remember(context) { context.appDependencies.remoteOps }
    val bannerAnnouncements by remoteOps.bannerAnnouncements.collectAsState()
    var telemetryEnabled by remember { mutableStateOf(settings.telemetryEnabled) }
    val keepScreenOn by settings.keepScreenOnFlow.collectAsState()
    val wifiLockEnabled by settings.wifiLockEnabledFlow.collectAsState()
    val onKeepScreenOnChange: (Boolean) -> Unit = { enabled ->
        settings.keepScreenOn = enabled
    }
    val onWifiLockChange: (Boolean) -> Unit = { enabled ->
        settings.wifiLockEnabled = enabled
    }
    val onTelemetryChange: (Boolean) -> Unit = { enabled ->
        settings.telemetryEnabled = enabled
        telemetryEnabled = enabled
        if (enabled && settings.authorizationAccepted) {
            remoteOps.reportOnlineHeartbeat(force = true)
        }
    }
    // 顶部下拉到顶时大标题轻度跟随下沉、下方卡片区大幅弹性拉开留白，替代生硬的拉伸变形
    val overscroll = rememberSplitHeaderOverscrollEffect()
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val collapseDistancePx = with(density) { (if (compact) 40.dp else 56.dp).toPx() }
    val collapseProgress = computeLazyHeaderCollapseProgress(
        firstVisibleItemIndex = listState.firstVisibleItemIndex,
        firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
        collapseDistancePx = collapseDistancePx
    )
    val subtitleAlpha = computeHeaderSubtitleAlpha(collapseProgress)
    val horizontalPadding = if (compact) HomeTwoPaneSpec.OuterHorizontalPadding else 20.dp

    // 单一“设置”标题随滚动平滑缩小吸顶，卡片在状态栏后穿过时呈现渐进羽化毛玻璃效果。
    CollapsingGlassHeaderContainer(
        title = "设置",
        collapseProgress = collapseProgress,
        statusBarTop = statusBarTop,
        horizontalPadding = horizontalPadding,
        expandedTopOffset = expandedTopOffset,
        expandedFontSize = if (compact) TextSizes.DisplayCompact else TextSizes.Display,
        collapsedFontSize = if (compact) 18.sp else 19.sp,
        topBarHeight = if (compact) 46.dp else 52.dp,
        headerOverscrollPx = overscroll.headerOverscrollPx,
        modifier = modifier.background(PageBackground)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            overscrollEffect = overscroll,
            contentPadding = PaddingValues(
                top = statusBarTop + expandedTopOffset,
                start = if (compact) HomeTwoPaneSpec.OuterHorizontalPadding else 18.dp,
                end = if (compact) 12.dp else 18.dp,
                bottom = LocalBottomNavigationClearance.current
            ),
            verticalArrangement = Arrangement.spacedBy(if (compact) 14.dp else 18.dp)
        ) {
            item {
                Column(
                    modifier = Modifier
                        .overscrollHeader(overscroll)
                        .padding(horizontal = if (compact) 0.dp else 2.dp)
                ) {
                    // 顶部主标题改由连续缩小顶栏直接渲染，首项仅留标题占位与副标题。
                    Spacer(modifier = Modifier.height(if (compact) 38.dp else 44.dp))

                    Text(
                        text = if (compact) "MaiDroid 控制中心" else "管理服务、权限和应用状态",
                        color = TextMuted,
                        fontSize = TextSizes.Body,
                        lineHeight = 17.sp,
                        modifier = Modifier.graphicsLayer { alpha = subtitleAlpha }
                    )
                }
            }

        if (bannerAnnouncements.isNotEmpty()) {
            item {
                AnnouncementBannerSection(modifier = Modifier.overscrollBody(overscroll))
            }
        }

        item {
            SettingsSection(
                title = "服务",
                modifier = Modifier.overscrollBody(overscroll)
            ) {
                SettingsNavigationRow(
                    icon = FlatFeatureIcons.WebUi,
                    iconColor = IconPeachOrange,
                    title = "WebUI",
                    supportingText = if (webUiOnline) "服务在线" else "服务离线",
                    trailing = if (compact) null else {
                        {
                            SettingsStatusDot(if (webUiOnline) StatusOnline else StatusWarning)
                        }
                    }
                )

                SettingsDivider()

                SettingsNavigationRow(
                    icon = FlatFeatureIcons.MaiBotUpdate,
                    iconColor = IconSkyBlue,
                    title = "MaiBot 更新",
                    supportingText = maiBotUpdateSummaryText(updateState),
                    supportingColor = maiBotUpdateSummaryColor(updateState),
                    onClick = onUpdateClick,
                    trailing = if (compact) null else {
                        {
                            SettingsRowArrow(contentDescription = "打开 MaiBot 更新")
                        }
                    }
                )

                SettingsDivider()

                SettingsNavigationRow(
                    icon = FlatFeatureIcons.ContainerSsh,
                    iconColor = IconMintGreen,
                    title = "容器 SSH",
                    supportingText = containerSshSummaryText(sshState),
                    onClick = onSshConfigureClick,
                    trailing = if (compact) {
                        null
                    } else {
                        {
                            FlatSwitch(
                                checked = sshState.enabled,
                                onCheckedChange = onSshEnabledChange,
                                enabled = !sshState.isStarting
                            )
                        }
                    }
                )

                if (compact) {
                    CompactSshSwitchRow(
                        checked = sshState.enabled,
                        enabled = !sshState.isStarting,
                        onCheckedChange = onSshEnabledChange
                    )
                }

                SettingsDivider()

                // 容器在应用私有目录里，普通文件管理器看不到；这里经 ContainerDocumentsProvider 打开
                SettingsNavigationRow(
                    icon = FlatFeatureIcons.MaiBotFiles,
                    iconColor = IconSunnyAmber,
                    title = "MaiBot 文件",
                    supportingText = "在文件管理器中查看日志、插件和配置",
                    onClick = onOpenFilesClick,
                    trailing = if (compact) null else {
                        {
                            SettingsRowArrow(contentDescription = "在文件管理器中打开 MaiBot 目录")
                        }
                    }
                )
            }
        }

        item {
            SettingsSection(
                title = "系统",
                modifier = Modifier.overscrollBody(overscroll)
            ) {
                SettingsNavigationRow(
                    icon = FlatFeatureIcons.Permissions,
                    iconColor = IconFreshGreen,
                    title = "权限管理",
                    supportingText = permissionSummary(permissionState),
                    selected = permissionSelected,
                    exposesSelection = true,
                    onClick = onPermissionClick,
                    trailing = if (compact) null else {
                        {
                            SettingsRowArrow(
                                contentDescription = "进入权限管理",
                                tint = if (permissionSelected) BrandOrange else TextMuted.copy(alpha = 0.6f)
                            )
                        }
                    }
                )

                SettingsDivider()

                SettingsNavigationRow(
                    icon = FlatFeatureIcons.MirrorSources,
                    iconColor = IconCyanBlue,
                    title = "镜像源设置",
                    supportingText = mirrorSummaryText,
                    selected = mirrorSelected,
                    exposesSelection = true,
                    onClick = onMirrorClick,
                    trailing = if (compact) null else {
                        {
                            SettingsRowArrow(
                                contentDescription = "进入镜像源设置",
                                tint = if (mirrorSelected) BrandOrange else TextMuted.copy(alpha = 0.6f)
                            )
                        }
                    }
                )

                SettingsDivider()

                // 引导完成后按用户开关控制窗口是否保持常亮，避免大屏高刷机型长时间挂在前台发热掉电。
                SettingsNavigationRow(
                    icon = FlatFeatureIcons.KeepScreenOn,
                    iconColor = IconSunnyAmber,
                    title = "屏幕常亮",
                    supportingText = "前台打开界面时阻止自动息屏，关闭可降低发热与耗电",
                    onClick = { onKeepScreenOnChange(!keepScreenOn) },
                    trailing = if (compact) null else {
                        {
                            FlatSwitch(
                                checked = keepScreenOn,
                                onCheckedChange = onKeepScreenOnChange
                            )
                        }
                    }
                )
                if (compact) {
                    CompactSettingSwitchRow(
                        label = "启用屏幕常亮",
                        checked = keepScreenOn,
                        enabled = true,
                        onCheckedChange = onKeepScreenOnChange
                    )
                }

                SettingsDivider()

                // 默认关闭低延迟 Wi-Fi 锁降低息屏射频能耗，仅部分旧机型息屏断连或 QQBot 频繁 4009 时按需开启。
                SettingsNavigationRow(
                    icon = FlatFeatureIcons.WifiLock,
                    iconColor = IconTealMint,
                    title = "Wi-Fi 低延迟保活",
                    supportingText = "防止息屏后 Wi-Fi 休眠导致 QQ 官方机器人 4009 掉线或断连；关闭可降低后台射频耗电与发热",
                    onClick = { onWifiLockChange(!wifiLockEnabled) },
                    trailing = if (compact) null else {
                        {
                            FlatSwitch(
                                checked = wifiLockEnabled,
                                onCheckedChange = onWifiLockChange
                            )
                        }
                    }
                )
                if (compact) {
                    CompactSettingSwitchRow(
                        label = "启用 Wi-Fi 低延迟保活",
                        checked = wifiLockEnabled,
                        enabled = true,
                        onCheckedChange = onWifiLockChange
                    )
                }
            }
        }

        item {
            SettingsSection(
                title = "应用",
                modifier = Modifier.overscrollBody(overscroll)
            ) {
                SettingsNavigationRow(
                    icon = FlatFeatureIcons.Platforms,
                    iconColor = IconIrisBlue,
                    title = "消息平台",
                    supportingText = if (runningPlatformCount > 0) {
                        "$runningPlatformCount 个平台正在运行"
                    } else {
                        "暂无平台正在运行"
                    },
                    trailing = if (compact) null else {
                        {
                            SettingsStatusDot(
                                if (runningPlatformCount > 0) StatusOnline else StatusWarning
                            )
                        }
                    }
                )

                SettingsDivider()

                // 点这一行检查 MaiDroid 自身的更新（不是容器里的 MaiBot），副标题跟着检查、下载进度变化
                SettingsNavigationRow(
                    icon = FlatFeatureIcons.AppVersion,
                    iconColor = IconCyanBlue,
                    title = "应用版本",
                    supportingText = appUpdateSummaryText(appUpdateState.copy(currentVersion = versionName)),
                    supportingColor = appUpdateSummaryColor(appUpdateState),
                    onClick = onAppUpdateClick,
                    trailing = if (compact) null else {
                        {
                            SettingsRowArrow(contentDescription = "检查 MaiDroid 更新")
                        }
                    }
                )
            }
        }

        // 出了问题时的入口：去 GitHub 提带脱敏诊断包的 Issue、加 QQ 用户群交流互助；手机空间不够时清掉下载缓存
        item {
            SettingsSection(
                title = "帮助",
                modifier = Modifier.overscrollBody(overscroll)
            ) {
                SettingsNavigationRow(
                    icon = FlatFeatureIcons.Feedback,
                    iconColor = IconCoralPink,
                    title = "反馈问题",
                    supportingText = "自动收集诊断信息，一键去 GitHub 提交问题",
                    onClick = onFeedbackClick,
                    trailing = if (compact) null else {
                        {
                            SettingsRowArrow(contentDescription = "反馈问题")
                        }
                    }
                )
                SettingsDivider()
                SettingsNavigationRow(
                    icon = FlatFeatureIcons.QqGroup,
                    iconColor = IconSkyBlue,
                    title = "QQ 反馈群",
                    supportingText = "加入群聊【$QQ_FEEDBACK_GROUP_NAME】（$QQ_FEEDBACK_GROUP_NUMBER）交流互助",
                    onClick = onQqGroupClick,
                    trailing = if (compact) null else {
                        {
                            SettingsRowArrow(contentDescription = "打开 QQ 反馈群")
                        }
                    }
                )
                SettingsDivider()
                SettingsNavigationRow(
                    icon = FlatFeatureIcons.Cleanup,
                    iconColor = IconTealMint,
                    title = "清理空间",
                    supportingText = "清掉依赖包、软件源的下载缓存和用不到的文件",
                    onClick = onCleanupClick,
                    trailing = if (compact) null else {
                        {
                            SettingsRowArrow(contentDescription = "清理空间")
                        }
                    }
                )
                SettingsDivider()
                SettingsNavigationRow(
                    icon = FlatFeatureIcons.Telemetry,
                    iconColor = IconIrisBlue,
                    title = "匿名运行统计",
                    supportingText = "上报匿名版本、在线状态、电池温耗与脱敏报错以改进稳定性，绝不含聊天记录、QQ 号与密钥",
                    onClick = { onTelemetryChange(!telemetryEnabled) },
                    trailing = if (compact) null else {
                        {
                            FlatSwitch(
                                checked = telemetryEnabled,
                                onCheckedChange = onTelemetryChange
                            )
                        }
                    }
                )
                if (compact) {
                    CompactSettingSwitchRow(
                        label = "启用匿名统计",
                        checked = telemetryEnabled,
                        enabled = true,
                        onCheckedChange = onTelemetryChange
                    )
                }
            }
        }

        // 设置页最底下：安全退出。点了先弹二次确认，确认后各服务正常退出、MaiDroid 自动关闭。
        item {
            SafeExitButton(
                onClick = onSafeExitClick,
                modifier = Modifier
                    .overscrollBody(overscroll)
                    .padding(top = if (compact) 4.dp else 8.dp)
            )
        }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            color = TextMuted,
            fontSize = TextSizes.Caption,
            fontWeight = FontWeight.Medium,
            lineHeight = 14.sp,
            modifier = Modifier.padding(start = 6.dp)
        )

        // 取消外圈硬描边，用圆润扁平卡片底色与深色背景自然拉开层级
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Corners.Chip))
                .background(CardSurface),
            content = content
        )
    }
}

@Composable
private fun SettingsNavigationRow(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    supportingText: String,
    modifier: Modifier = Modifier,
    supportingColor: Color = TextMuted,
    selected: Boolean = false,
    exposesSelection: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val clickModifier = when {
        onClick == null -> Modifier
        exposesSelection -> Modifier.selectable(
            selected = selected,
            onClick = onClick,
            role = Role.Tab
        )
        else -> Modifier.clickable(onClick = onClick)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (selected) BrandOrange.copy(alpha = 0.08f) else Color.Transparent)
            .then(clickModifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FlatFeatureIconBadge(
            icon = icon,
            backgroundColor = iconColor,
            size = 38.dp,
            iconSize = 25.dp
        )

        Spacer(modifier = Modifier.width(13.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = TextPrimary,
                fontSize = TextSizes.BodyLarge,
                fontWeight = FontWeight.Medium,
                lineHeight = 19.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(3.dp))

            Text(
                text = supportingText,
                color = supportingColor,
                fontSize = TextSizes.Caption,
                lineHeight = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (trailing != null) {
            Spacer(modifier = Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
private fun SettingsRowArrow(
    contentDescription: String,
    tint: Color = TextMuted.copy(alpha = 0.6f)
) {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = contentDescription,
        tint = tint,
        modifier = Modifier.size(20.dp)
    )
}

@Composable
private fun SettingsDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 67.dp, end = 16.dp)
            .height(0.5.dp)
            .background(CardDivider)
    )
}

@Composable
private fun SettingsStatusDot(color: Color) {
    Box(
        modifier = Modifier
            .size(9.dp)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
private fun CompactSshSwitchRow(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    CompactSettingSwitchRow(
        label = "启用 SSH",
        checked = checked,
        enabled = enabled,
        onCheckedChange = onCheckedChange
    )
}

@Composable
private fun CompactSettingSwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    // 左右交互彻底分离：左侧占满剩余宽度防止窄屏挤压，右侧开关独立承接点击与液态拖拽，避免整行按压蒙层盖在开关上方。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .defaultMinSize(minHeight = 40.dp)
                .clickable(
                    interactionSource = null,
                    indication = null,
                    enabled = enabled,
                    role = Role.Switch,
                    onClick = { onCheckedChange(!checked) }
                ),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = label,
                color = TextMuted,
                fontSize = TextSizes.Caption,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        FlatSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

// 失败标醒目暖橙提醒，平时保持柔和次要灰字，避免喧宾夺主。
private fun maiBotUpdateSummaryColor(state: MaiBotUpdateState): Color =
    when (state.phase) {
        MaiBotUpdatePhase.Failed -> StatusWarning
        MaiBotUpdatePhase.Available -> BrandOrange
        else -> TextMuted
    }

// 失败标醒目暖橙提醒，发现新版本标品牌橙提醒，其余保持次要灰字。
private fun appUpdateSummaryColor(state: AppUpdateState): Color =
    when (state.phase) {
        AppUpdatePhase.Failed -> StatusWarning
        AppUpdatePhase.Available -> BrandOrange
        else -> TextMuted
    }

private fun permissionSummary(state: PermissionState): String {
    val optionalGranted = listOf(
        state.notificationGranted,
        state.batteryOptimizationIgnored
    ).count { it }
    val storageStatus = if (state.storageGranted) "存储已授权" else "存储未授权"
    return "$storageStatus · 可选项 $optionalGranted/2"
}
