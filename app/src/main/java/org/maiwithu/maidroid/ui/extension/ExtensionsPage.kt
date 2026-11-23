package org.maiwithu.maidroid.ui.extension

import android.widget.Toast
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
import org.maiwithu.maidroid.ui.component.LocalBottomNavigationClearance
import org.maiwithu.maidroid.ui.component.computeHeaderSubtitleAlpha
import org.maiwithu.maidroid.ui.component.computeScrollHeaderCollapseProgress
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
    heightDp < 560f && widthDp >= 520f -> ExtensionsLayout.TwoPane
    widthDp < 600f -> ExtensionsLayout.Compact
    else -> ExtensionsLayout.Wide
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
        val verticalExpansion = ((maxHeight.value - 480f) / (720f - 480f)).coerceIn(0f, 1f)
        val topSpacing = 8.dp + 32.dp * verticalExpansion

        val headerSubtitle: @Composable (Boolean, Float) -> Unit = { compact, subtitleAlpha ->
            Column {
                Spacer(modifier = Modifier.height(if (compact) 38.dp else 44.dp))
                Text(
                    text = subtitleText,
                    color = TextMuted,
                    fontSize = if (compact) TextSizes.Body else TextSizes.BodyLarge,
                    fontWeight = FontWeight.Bold,
                    lineHeight = if (compact) 17.sp else 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.graphicsLayer { alpha = subtitleAlpha }
                )
            }
        }

        val petSection: @Composable (Boolean) -> Unit = { compact ->
            ExtensionSection(title = "已集成组件") {
                FloatingPetExtensionCard(
                    entry = ExtensionCatalog.primaryEntry,
                    petState = petState,
                    webUiOnline = webUiOnline,
                    overlayGranted = permissionState.overlayGranted,
                    compact = compact,
                    onToggle = handleStartOrToggle,
                    onRequestPermission = { showOverlayPermissionDialog = true },
                    onOpenLogin = { showLoginDialog = true },
                    onEnableRemoteMode = desktopPet::enableRemoteModeAndStart,
                    onSelectBot = desktopPet::selectBot,
                    onToggleVisibility = desktopPet::toggleVisibility,
                    onOpenRecords = desktopPet::openRecords,
                    onReconnect = handleReconnect
                )
            }
        }

        val ecosystemSection: @Composable (Boolean) -> Unit = { compact ->
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

        when (extensionsLayout(maxWidth.value, maxHeight.value)) {
            ExtensionsLayout.Compact -> {
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
                    horizontalPadding = 16.dp,
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
                            .padding(horizontal = 16.dp)
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
                            petSection(false)
                            ecosystemSection(false)
                        }
                    }
                }
            }

            ExtensionsLayout.TwoPane -> {
                // 手机横屏宽矮窗口：左栏承载标题与悬浮桌宠主控卡，右栏承载生态扩展清单，各自独立滚动不挤占
                val leftPaneWidth = (maxWidth * 0.56f).coerceIn(280.dp, 460.dp)
                val leftScrollState = rememberScrollState()
                val leftOverscroll = rememberSplitHeaderOverscrollEffect()
                val leftCollapseDistancePx = with(density) { 40.dp.toPx() }
                val leftCollapseProgress = computeScrollHeaderCollapseProgress(
                    scrollOffsetPx = leftScrollState.value,
                    collapseDistancePx = leftCollapseDistancePx
                )
                val leftSubtitleAlpha = computeHeaderSubtitleAlpha(leftCollapseProgress)

                Row(modifier = Modifier.fillMaxSize()) {
                    CollapsingGlassHeaderContainer(
                        title = "扩展组件",
                        collapseProgress = leftCollapseProgress,
                        statusBarTop = statusBarTop,
                        horizontalPadding = 16.dp,
                        expandedTopOffset = topSpacing,
                        expandedFontSize = TextSizes.DisplayCompact,
                        collapsedFontSize = 18.sp,
                        topBarHeight = 46.dp,
                        headerOverscrollPx = leftOverscroll.headerOverscrollPx,
                        modifier = Modifier
                            .width(leftPaneWidth)
                            .fillMaxHeight()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(leftScrollState, overscrollEffect = leftOverscroll)
                                .padding(start = 16.dp, end = 8.dp)
                                .padding(top = statusBarTop + topSpacing, bottom = bottomClearance),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Box(modifier = Modifier.overscrollHeader(leftOverscroll)) {
                                headerSubtitle(true, leftSubtitleAlpha)
                            }
                            Box(modifier = Modifier.overscrollBody(leftOverscroll)) {
                                petSection(true)
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(start = 8.dp, end = 16.dp)
                            .padding(top = statusBarTop + topSpacing, bottom = bottomClearance)
                    ) {
                        ecosystemSection(true)
                    }
                }
            }

            ExtensionsLayout.Wide -> {
                // 平板或折叠屏大屏：顶部统一吸顶渐进玻璃标题，下方双列仪表盘合理分配空间
                val wideScrollState = rememberScrollState()
                val wideOverscroll = rememberSplitHeaderOverscrollEffect()
                val collapseDistancePx = with(density) { 52.dp.toPx() }
                val collapseProgress = computeScrollHeaderCollapseProgress(
                    scrollOffsetPx = wideScrollState.value,
                    collapseDistancePx = collapseDistancePx
                )
                val subtitleAlpha = computeHeaderSubtitleAlpha(collapseProgress)

                CollapsingGlassHeaderContainer(
                    title = "扩展组件",
                    collapseProgress = collapseProgress,
                    statusBarTop = statusBarTop,
                    horizontalPadding = 24.dp,
                    expandedTopOffset = topSpacing,
                    expandedFontSize = TextSizes.Display,
                    collapsedFontSize = 19.sp,
                    topBarHeight = 52.dp,
                    headerOverscrollPx = wideOverscroll.headerOverscrollPx,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(wideScrollState, overscrollEffect = wideOverscroll)
                            .padding(horizontal = 24.dp)
                            .padding(top = statusBarTop + topSpacing, bottom = bottomClearance),
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        Box(modifier = Modifier.overscrollHeader(wideOverscroll)) {
                            headerSubtitle(false, subtitleAlpha)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .overscrollBody(wideOverscroll),
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Box(modifier = Modifier.weight(1.15f)) {
                                petSection(false)
                            }
                            Box(modifier = Modifier.weight(0.85f)) {
                                ecosystemSection(false)
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

/** 规划中的外部麦系生态组件列表行：统一使用双层圆标徽章与右侧规划胶囊，点击弹出温和预告提示。 */
@Composable
private fun UpcomingExtensionRow(
    entry: ExtensionEntry,
    compact: Boolean,
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
                maxLines = 1,
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
        }

        Spacer(modifier = Modifier.width(10.dp))

        ExtensionTagPill(text = entry.badge, color = TextMuted)
    }
}

private fun resolveUpcomingIcon(id: ExtensionId): Pair<ImageVector, Color> = when (id) {
    ExtensionId.DesktopPet -> FlatFeatureIcons.DesktopPet to IconPeachOrange
    ExtensionId.TtsAutoDeploy -> FlatFeatureIcons.TtsVoice to IconSkyBlue
    ExtensionId.AmaidesuBridge -> FlatFeatureIcons.EcosystemBridge to IconIrisBlue
}
