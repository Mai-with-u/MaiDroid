package org.maiwithu.maidroid.ui.extension

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maiwithu.maidroid.appDependencies
import org.maiwithu.maidroid.permission.PermissionActions
import org.maiwithu.maidroid.permission.PermissionState
import org.maiwithu.maidroid.pet.DesktopPetPhase
import org.maiwithu.maidroid.pet.DesktopPetUiState
import org.maiwithu.maidroid.ui.component.CollapsingGlassHeaderContainer
import org.maiwithu.maidroid.ui.component.FlatFeatureIconBadge
import org.maiwithu.maidroid.ui.component.FlatFeatureIcons
import org.maiwithu.maidroid.ui.component.HomeTwoPaneSpec
import org.maiwithu.maidroid.ui.component.LocalBottomNavigationClearance
import org.maiwithu.maidroid.ui.component.computeHeaderSubtitleAlpha
import org.maiwithu.maidroid.ui.component.computeScrollHeaderCollapseProgress
import org.maiwithu.maidroid.ui.component.horizontalSlideTransition
import org.maiwithu.maidroid.ui.component.overscrollBody
import org.maiwithu.maidroid.ui.component.overscrollHeader
import org.maiwithu.maidroid.ui.component.rememberSplitHeaderOverscrollEffect
import org.maiwithu.maidroid.ui.component.statusBarTopInset
import org.maiwithu.maidroid.ui.theme.CardDivider
import org.maiwithu.maidroid.ui.theme.CardSurface
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.IconIrisBlue
import org.maiwithu.maidroid.ui.theme.IconPeachOrange
import org.maiwithu.maidroid.ui.theme.IconSkyBlue
import org.maiwithu.maidroid.ui.theme.PageBackground
import org.maiwithu.maidroid.ui.theme.TextFaint
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes

/** 扩展组件页在不同窗口宽高下的排版策略：手机竖屏单列、手机横屏双栏、平板双列仪表盘。 */
internal enum class ExtensionsLayout {
    Compact,
    TwoPane,
    Wide
}

/** 对齐消息平台页与设置页的响应式断点：矮宽横屏切左右双栏，窄竖屏用单列，宽大屏展双列仪表盘。 */
internal fun extensionsLayout(widthDp: Float, heightDp: Float): ExtensionsLayout = when {
    heightDp < HomeTwoPaneSpec.CompactTwoPaneMaxHeightDp &&
        widthDp >= HomeTwoPaneSpec.CompactTwoPaneMinWidthDp -> ExtensionsLayout.TwoPane
    widthDp < HomeTwoPaneSpec.SingleColumnMaxWidthDp -> ExtensionsLayout.Compact
    else -> ExtensionsLayout.Wide
}

/** 决定当前激活的子页面标识：竖屏单列支持钻入与退回列表，双栏/宽屏始终常驻展示悬浮桌宠配置。 */
internal fun resolveActiveExtensionDetail(
    selectedDetail: ExtensionId?,
    layout: ExtensionsLayout
): ExtensionId? = when {
    layout == ExtensionsLayout.Compact -> selectedDetail
    selectedDetail != null -> selectedDetail
    else -> ExtensionId.DesktopPet
}

/** 顶部大标题下方的实时状态副标题：一眼掌握当前桌宠运行状态与本机服务衔接情况。 */
internal fun extensionsHeaderSubtitle(
    petState: DesktopPetUiState,
    webUiOnline: Boolean
): String = when {
    petState.overlayActive && petState.connected && petState.overlayVisible ->
        "悬浮桌宠运行中 · 已接入本机 MaiBot"
    petState.overlayActive && petState.connected ->
        "悬浮桌宠已隐藏 · 点击下方即可恢复显示"
    petState.phase == DesktopPetPhase.Connecting ->
        "正在连接本机悬浮桌宠服务…"
    petState.phase == DesktopPetPhase.AuthRequired ->
        "需要完成本机 WebUI 登录以同步桌宠会话"
    petState.phase == DesktopPetPhase.ConfigRequired ->
        "需先一键开启远端桌宠配置"
    petState.phase == DesktopPetPhase.Error ->
        "悬浮桌宠连接异常，可点击重连恢复"
    webUiOnline ->
        "本机 WebUI 已在线，随时可唤起悬浮桌宠"
    else ->
        "管理本机扩展组件与麦麦生态外部工具"
}

/**
 * 扩展组件页：
 * - 第一栏集中承载本机 MaiBot 悬浮桌宠的启停、悬浮权限引导、登录弹窗接管、远端模式一键开启与多机器人切换；
 * - 生态扩展分组采用全应用统一的分组圆角卡片与双层软萌矢量图标，支持竖屏、手机横屏双栏及平板宽屏自适应。
 */
@Composable
internal fun ExtensionsPage(
    webUiOnline: Boolean,
    permissionState: PermissionState,
    permissionActions: PermissionActions,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val desktopPet = remember(context) { context.appDependencies.desktopPet }
    val petState by desktopPet.state.collectAsState()

    var showOverlayPermissionDialog by rememberSaveable { mutableStateOf(false) }
    var pendingStartAfterOverlay by rememberSaveable { mutableStateOf(false) }
    var showLoginDialog by rememberSaveable { mutableStateOf(false) }
    var selectedDetail by rememberSaveable { mutableStateOf<ExtensionId?>(null) }

    // 进入页面与系统返回前台时重新检查容器前端目录，确保本机 MaiBot 升级后状态指示立即同步。
    LaunchedEffect(desktopPet) {
        desktopPet.refreshLocalAssetInfo()
    }
    DisposableEffect(lifecycleOwner, desktopPet) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                desktopPet.refreshLocalAssetInfo()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 用户从系统悬浮窗授权页返回且权限就绪时，自动续接先前待开启的桌宠连接流程。
    LaunchedEffect(permissionState.overlayGranted, pendingStartAfterOverlay) {
        if (permissionState.overlayGranted && pendingStartAfterOverlay) {
            pendingStartAfterOverlay = false
            showOverlayPermissionDialog = false
            if (webUiOnline) {
                desktopPet.start()
            } else {
                Toast.makeText(context, "悬浮窗权限已就绪，请先启动 MaiBot 服务", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 需要用户手动输入 WebUI 密码时自动弹出内置认证窗；认证结束后立刻关闭并把视图交还给后台锚点。
    LaunchedEffect(petState.phase, petState.desiredEnabled) {
        if (petState.phase == DesktopPetPhase.AuthRequired && petState.desiredEnabled) {
            showLoginDialog = true
        } else if (petState.phase != DesktopPetPhase.AuthRequired && showLoginDialog) {
            showLoginDialog = false
        }
    }

    val handleStartOrToggle: (Boolean) -> Unit = { enabled ->
        when {
            !enabled -> desktopPet.stop()
            !permissionState.overlayGranted -> showOverlayPermissionDialog = true
            !webUiOnline && !petState.overlayActive -> {
                Toast.makeText(context, "本机 MaiBot WebUI 尚未启动，请先在首页拉起服务", Toast.LENGTH_SHORT).show()
            }
            else -> desktopPet.start()
        }
    }

    val handleReconnect = {
        when {
            !permissionState.overlayGranted -> showOverlayPermissionDialog = true
            !webUiOnline && !petState.overlayActive -> {
                Toast.makeText(context, "本机 MaiBot WebUI 尚未启动，请先在首页拉起服务", Toast.LENGTH_SHORT).show()
            }
            else -> desktopPet.reconnect()
        }
    }

    val subtitleText = extensionsHeaderSubtitle(petState = petState, webUiOnline = webUiOnline)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(PageBackground)
    ) {
        val statusBarTop = statusBarTopInset()
        val bottomClearance = LocalBottomNavigationClearance.current
        val density = LocalDensity.current
        val topSpacing = HomeTwoPaneSpec.headerTopSpacing(maxHeight.value)
        val layout = extensionsLayout(maxWidth.value, maxHeight.value)
        val compactTwoPane = maxHeight.value < HomeTwoPaneSpec.CompactTwoPaneMaxHeightDp
        val activeDetail = resolveActiveExtensionDetail(selectedDetail = selectedDetail, layout = layout)

        // 手机竖屏钻入悬浮桌宠子页面时，拦截系统返回键平滑退回扩展组件总览列表。
        BackHandler(enabled = layout == ExtensionsLayout.Compact && selectedDetail != null) {
            selectedDetail = null
        }

        val headerSubtitle: @Composable (Boolean, Float) -> Unit = { compact, subtitleAlpha ->
            Column {
                Spacer(modifier = Modifier.height(if (compact) 38.dp else 44.dp))
                Text(
                    text = subtitleText,
                    color = TextMuted,
                    fontSize = if (compact) TextSizes.Body else TextSizes.BodyLarge,
                    fontWeight = FontWeight.Bold,
                    lineHeight = if (compact) 17.sp else 18.sp,
                    // 38% 窄左栏下允许副标题最多换到两行，防止实时状态说明过早被截断。
                    maxLines = if (compact) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.graphicsLayer { alpha = subtitleAlpha }
                )
            }
        }

        val petSummarySection: @Composable (Boolean, Boolean, Boolean) -> Unit = { compact, selected, exposesSelection ->
            ExtensionSection(title = "已集成组件") {
                FloatingPetSummaryCard(
                    entry = ExtensionCatalog.primaryEntry,
                    petState = petState,
                    selected = selected,
                    exposesSelection = exposesSelection,
                    compact = compact,
                    onClick = { selectedDetail = ExtensionId.DesktopPet },
                    onToggle = handleStartOrToggle
                )
            }
        }

        val ecosystemSection: @Composable (Boolean, Boolean) -> Unit = { compact, stackBadge ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ExtensionSection(title = "麦麦生态扩展") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Corners.Chip))
                            .background(CardSurface)
                    ) {
                        ExtensionCatalog.upcomingEntries.forEachIndexed { index, entry ->
                            if (index > 0) {
                                ExtensionRowDivider(compact = compact)
                            }
                            UpcomingExtensionRow(
                                entry = entry,
                                compact = compact,
                                stackBadge = stackBadge,
                                onClick = {
                                    Toast.makeText(
                                        context,
                                        "${entry.title}正在适配中，后续版本开放接入",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            )
                        }
                    }
                }
                Text(
                    text = "后续接入更多本地服务与外部麦系协同组件时，将自动在此平铺扩展。",
                    color = TextFaint,
                    fontSize = TextSizes.Caption,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(horizontal = 6.dp)
                )
            }
        }

        val petDetailPage: @Composable (Boolean, Boolean, Modifier) -> Unit = { showBack, compactDetail, detailModifier ->
            DesktopPetSettingsPage(
                petState = petState,
                webUiOnline = webUiOnline,
                overlayGranted = permissionState.overlayGranted,
                showBackButton = showBack,
                compact = compactDetail,
                expandedTopOffset = topSpacing,
                onBack = { selectedDetail = null },
                onToggle = handleStartOrToggle,
                onRequestPermission = { showOverlayPermissionDialog = true },
                onOpenLogin = { showLoginDialog = true },
                onEnableRemoteMode = desktopPet::enableRemoteModeAndStart,
                onSelectBot = desktopPet::selectBot,
                onToggleVisibility = desktopPet::toggleVisibility,
                onOpenRecords = desktopPet::openRecords,
                onReconnect = handleReconnect,
                onPetScaleChange = desktopPet::setPetScale,
                modifier = detailModifier
            )
        }

        when (layout) {
            ExtensionsLayout.Compact -> {
                AnimatedContent(
                    targetState = activeDetail,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PageBackground),
                    transitionSpec = { horizontalSlideTransition(forward = targetState != null, dampingRatio = 0.82f) },
                    label = "ExtensionsCompactDetailTransition"
                ) { detail ->
                    if (detail == ExtensionId.DesktopPet) {
                        petDetailPage(true, false, Modifier.fillMaxSize())
                    } else {
                        val scrollState = rememberScrollState()
                        val overscroll = rememberSplitHeaderOverscrollEffect()
                        val collapseDistancePx = with(density) { 48.dp.toPx() }
                        val collapseProgress = computeScrollHeaderCollapseProgress(
                            scrollOffsetPx = scrollState.value,
                            collapseDistancePx = collapseDistancePx
                        )
                        val subtitleAlpha = computeHeaderSubtitleAlpha(collapseProgress)

                        CollapsingGlassHeaderContainer(
                            title = "扩展组件",
                            collapseProgress = collapseProgress,
                            statusBarTop = statusBarTop,
                            horizontalPadding = HomeTwoPaneSpec.OuterHorizontalPadding,
                            expandedTopOffset = topSpacing,
                            expandedFontSize = TextSizes.Display,
                            collapsedFontSize = 19.sp,
                            topBarHeight = 52.dp,
                            headerOverscrollPx = overscroll.headerOverscrollPx,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(scrollState, overscrollEffect = overscroll)
                                    .padding(horizontal = HomeTwoPaneSpec.OuterHorizontalPadding)
                                    .padding(top = statusBarTop + topSpacing, bottom = bottomClearance),
                                verticalArrangement = Arrangement.spacedBy(20.dp)
                            ) {
                                Box(modifier = Modifier.overscrollHeader(overscroll)) {
                                    headerSubtitle(false, subtitleAlpha)
                                }
                                Column(
                                    modifier = Modifier.overscrollBody(overscroll),
                                    verticalArrangement = Arrangement.spacedBy(20.dp)
                                ) {
                                    petSummarySection(false, false, false)
                                    ecosystemSection(false, false)
                                }
                            }
                        }
                    }
                }
            }

            ExtensionsLayout.TwoPane, ExtensionsLayout.Wide -> {
                // 横屏与宽屏对齐设置页统一 38% : 62% 主从分栏：左栏放精简目录与生态扩展，右栏常驻展示悬浮桌宠子页面。
                val leftScrollState = rememberScrollState()
                val leftOverscroll = rememberSplitHeaderOverscrollEffect()
                val isWide = layout == ExtensionsLayout.Wide
                val leftPaddingStart = if (isWide) HomeTwoPaneSpec.WideOuterHorizontalPadding else HomeTwoPaneSpec.OuterHorizontalPadding
                val leftPaddingEnd = if (isWide) HomeTwoPaneSpec.WidePaneGap / 2 else 12.dp
                val leftCollapseDistancePx = with(density) { (if (compactTwoPane) 40.dp else 52.dp).toPx() }
                val leftCollapseProgress = computeScrollHeaderCollapseProgress(
                    scrollOffsetPx = leftScrollState.value,
                    collapseDistancePx = leftCollapseDistancePx
                )
                val leftSubtitleAlpha = computeHeaderSubtitleAlpha(leftCollapseProgress)

                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PageBackground)
                ) {
                    CollapsingGlassHeaderContainer(
                        title = "扩展组件",
                        collapseProgress = leftCollapseProgress,
                        statusBarTop = statusBarTop,
                        horizontalPadding = leftPaddingStart,
                        expandedTopOffset = topSpacing,
                        expandedFontSize = if (compactTwoPane) TextSizes.DisplayCompact else TextSizes.Display,
                        collapsedFontSize = if (compactTwoPane) 18.sp else 19.sp,
                        topBarHeight = if (compactTwoPane) 46.dp else 52.dp,
                        headerOverscrollPx = leftOverscroll.headerOverscrollPx,
                        modifier = Modifier
                            .weight(HomeTwoPaneSpec.LeftPaneWeight)
                            .fillMaxHeight()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(leftScrollState, overscrollEffect = leftOverscroll)
                                .padding(start = leftPaddingStart, end = leftPaddingEnd)
                                .padding(top = statusBarTop + topSpacing, bottom = bottomClearance),
                            verticalArrangement = Arrangement.spacedBy(if (compactTwoPane) 14.dp else 18.dp)
                        ) {
                            Box(modifier = Modifier.overscrollHeader(leftOverscroll)) {
                                headerSubtitle(true, leftSubtitleAlpha)
                            }
                            Column(
                                modifier = Modifier.overscrollBody(leftOverscroll),
                                verticalArrangement = Arrangement.spacedBy(if (compactTwoPane) 14.dp else 18.dp)
                            ) {
                                petSummarySection(true, activeDetail == ExtensionId.DesktopPet, true)
                                ecosystemSection(true, true)
                            }
                        }
                    }

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
                            targetState = activeDetail,
                            modifier = Modifier.fillMaxSize(),
                            transitionSpec = {
                                fadeIn(tween(durationMillis = 220)) togetherWith
                                    fadeOut(tween(durationMillis = 180))
                            },
                            label = "ExtensionsTwoPaneDetail"
                        ) { detail ->
                            when (detail) {
                                ExtensionId.DesktopPet, null -> petDetailPage(false, compactTwoPane, Modifier.fillMaxSize())
                                ExtensionId.TtsAutoDeploy, ExtensionId.AmaidesuBridge ->
                                    petDetailPage(false, compactTwoPane, Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
        }
    }

    if (showOverlayPermissionDialog) {
        OverlayPermissionGuideDialog(
            onDismiss = {
                pendingStartAfterOverlay = false
                showOverlayPermissionDialog = false
            },
            onConfirmGrant = {
                pendingStartAfterOverlay = true
                showOverlayPermissionDialog = false
                permissionActions.requestOverlay()
            }
        )
    }

    if (showLoginDialog) {
        WebUiLoginDialog(
            onDismiss = { showLoginDialog = false },
            onReconnect = desktopPet::reconnect,
            attachAuthView = desktopPet::attachAuthView,
            detachAuthView = desktopPet::detachAuthView
        )
    }
}

/** 扩展页分组区块头与容器包装，视觉层级与消息平台、设置页保持一致。 */
@Composable
private fun ExtensionSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth(),
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
        Column(
            modifier = Modifier.fillMaxWidth(),
            content = content
        )
    }
}

/** 生态扩展分组卡行间细分隔线：对齐文字起点，不贯穿左侧圆形图标徽章。 */
@Composable
private fun ExtensionRowDivider(compact: Boolean) {
    val startPadding = if (compact) 64.dp else 69.dp
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = startPadding, end = 16.dp)
            .height(0.5.dp)
            .background(CardDivider)
    )
}

/** 规划中的外部麦系生态组件列表行：统一使用双层圆标徽章与状态胶囊；窄左栏下胶囊沉到说明下方防标题截断。 */
@Composable
private fun UpcomingExtensionRow(
    entry: ExtensionEntry,
    compact: Boolean,
    stackBadge: Boolean = false,
    onClick: () -> Unit
) {
    val (icon, badgeColor) = resolveUpcomingIcon(entry.id)
    val badgeSize = if (compact) 36.dp else 40.dp
    val iconSize = if (compact) 23.dp else 25.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = if (compact) 14.dp else 16.dp, vertical = if (compact) 12.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FlatFeatureIconBadge(
            icon = icon,
            backgroundColor = badgeColor,
            size = badgeSize,
            iconSize = iconSize
        )

        Spacer(modifier = Modifier.width(if (compact) 12.dp else 13.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                color = TextPrimary,
                fontSize = TextSizes.BodyLarge,
                fontWeight = FontWeight.Medium,
                lineHeight = 19.sp,
                maxLines = if (stackBadge) 2 else 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = entry.subtitle,
                color = TextMuted,
                fontSize = TextSizes.Caption,
                lineHeight = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (stackBadge) {
                // 38% 窄左栏下将“规划中/即将支持”胶囊沉到说明下方，释放右侧整列宽度给标题和说明。
                Spacer(modifier = Modifier.height(6.dp))
                ExtensionTagPill(text = entry.badge, color = TextMuted)
            }
        }

        if (!stackBadge) {
            Spacer(modifier = Modifier.width(10.dp))

            ExtensionTagPill(text = entry.badge, color = TextMuted)
        }
    }
}

private fun resolveUpcomingIcon(id: ExtensionId): Pair<ImageVector, Color> = when (id) {
    ExtensionId.DesktopPet -> FlatFeatureIcons.DesktopPet to IconPeachOrange
    ExtensionId.TtsAutoDeploy -> FlatFeatureIcons.TtsVoice to IconSkyBlue
    ExtensionId.AmaidesuBridge -> FlatFeatureIcons.EcosystemBridge to IconIrisBlue
}
