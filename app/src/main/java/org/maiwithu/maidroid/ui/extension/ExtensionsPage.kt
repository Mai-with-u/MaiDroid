package org.maiwithu.maidroid.ui.extension

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
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
import org.maiwithu.maidroid.ui.component.CollapsingGlassHeaderContainer
import org.maiwithu.maidroid.ui.component.LocalBottomNavigationClearance
import org.maiwithu.maidroid.ui.component.computeLazyHeaderCollapseProgress
import org.maiwithu.maidroid.ui.component.overscrollBody
import org.maiwithu.maidroid.ui.component.rememberSplitHeaderOverscrollEffect
import org.maiwithu.maidroid.ui.component.statusBarTopInset
import org.maiwithu.maidroid.ui.theme.CardSurface
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.IconCyanBlue
import org.maiwithu.maidroid.ui.theme.IconPeachOrange
import org.maiwithu.maidroid.ui.theme.IconSkyBlue
import org.maiwithu.maidroid.ui.theme.PageBackground
import org.maiwithu.maidroid.ui.theme.TextFaint
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes

/**
 * 扩展组件页：
 * - 第一栏集中承载本机 MaiBot 悬浮桌宠的启停、悬浮权限引导、登录弹窗接管、远端模式一键开启与多机器人切换；
 * - 下方保留麦系生态外部扩展目录位，后续 TTS 自动部署、Amaidesu 联动等只需向 ExtensionCatalog 注册即可平铺接入。
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

    val statusBarTop = statusBarTopInset()
    val overscroll = rememberSplitHeaderOverscrollEffect()
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val collapseDistancePx = with(density) { 52.dp.toPx() }
    val collapseProgress = computeLazyHeaderCollapseProgress(
        firstVisibleItemIndex = listState.firstVisibleItemIndex,
        firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
        collapseDistancePx = collapseDistancePx
    )

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

    CollapsingGlassHeaderContainer(
        title = "扩展组件",
        collapseProgress = collapseProgress,
        statusBarTop = statusBarTop,
        horizontalPadding = 16.dp,
        expandedTopOffset = 40.dp,
        expandedFontSize = TextSizes.Display,
        collapsedFontSize = 19.sp,
        topBarHeight = 52.dp,
        headerOverscrollPx = overscroll.headerOverscrollPx,
        modifier = modifier
            .fillMaxSize()
            .background(PageBackground)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            overscrollEffect = overscroll,
            contentPadding = PaddingValues(
                top = statusBarTop + 40.dp,
                start = 16.dp,
                end = 16.dp,
                bottom = LocalBottomNavigationClearance.current
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(50.dp))
            }

            item {
                FloatingPetExtensionCard(
                    entry = ExtensionCatalog.primaryEntry,
                    petState = petState,
                    webUiOnline = webUiOnline,
                    overlayGranted = permissionState.overlayGranted,
                    onToggle = handleStartOrToggle,
                    onRequestPermission = { showOverlayPermissionDialog = true },
                    onOpenLogin = { showLoginDialog = true },
                    onEnableRemoteMode = desktopPet::enableRemoteModeAndStart,
                    onSelectBot = desktopPet::selectBot,
                    onToggleVisibility = desktopPet::toggleVisibility,
                    onOpenRecords = desktopPet::openRecords,
                    onReconnect = {
                        if (!permissionState.overlayGranted) {
                            showOverlayPermissionDialog = true
                        } else if (!webUiOnline && !petState.overlayActive) {
                            Toast.makeText(context, "本机 MaiBot WebUI 尚未启动，请先在首页拉起服务", Toast.LENGTH_SHORT).show()
                        } else {
                            desktopPet.reconnect()
                        }
                    },
                    modifier = Modifier.overscrollBody(overscroll)
                )
            }

            item {
                Text(
                    text = "麦麦生态扩展（预留接入）",
                    color = TextMuted,
                    fontSize = TextSizes.Caption,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .overscrollBody(overscroll)
                        .padding(start = 4.dp, top = 6.dp)
                )
            }

            items(ExtensionCatalog.upcomingEntries, key = { it.id.name }) { entry ->
                UpcomingExtensionCard(
                    entry = entry,
                    modifier = Modifier.overscrollBody(overscroll)
                )
            }

            item {
                Text(
                    text = "扩展组件页独立于底部四栏导航，后续接入 TTS 自动部署、Amaidesu 联动等外部程序时将直接在此平铺呈现。",
                    color = TextFaint,
                    fontSize = TextSizes.Caption,
                    lineHeight = 18.sp,
                    modifier = Modifier
                        .overscrollBody(overscroll)
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                )
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

/** 规划中的外部麦系生态组件卡片：保持安静弱化视觉，不干扰当前可用桌宠入口。 */
@Composable
private fun UpcomingExtensionCard(
    entry: ExtensionEntry,
    modifier: Modifier = Modifier
) {
    val badgeColor = when (entry.id) {
        ExtensionId.TtsAutoDeploy -> IconPeachOrange
        ExtensionId.AmaidesuBridge -> IconSkyBlue
        ExtensionId.DesktopPet -> IconCyanBlue
    }
    val shortCode = when (entry.id) {
        ExtensionId.TtsAutoDeploy -> "TTS"
        ExtensionId.AmaidesuBridge -> "AMA"
        ExtensionId.DesktopPet -> "PET"
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corners.Chip))
            .background(CardSurface.copy(alpha = 0.72f))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(Corners.Tag))
                .background(badgeColor.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = shortCode,
                color = badgeColor.copy(alpha = 0.85f),
                fontFamily = FontFamily.Monospace,
                fontSize = TextSizes.Caption,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.title,
                    color = TextPrimary.copy(alpha = 0.88f),
                    fontSize = TextSizes.BodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(6.dp))
                ExtensionTagPill(text = entry.badge, color = TextMuted)
            }
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = entry.subtitle,
                color = TextMuted,
                fontSize = TextSizes.Caption,
                lineHeight = 16.sp
            )
        }
    }
}
