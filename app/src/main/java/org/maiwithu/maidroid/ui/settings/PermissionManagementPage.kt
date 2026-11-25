package org.maiwithu.maidroid.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.shapes.RoundedRectangle
import org.maiwithu.maidroid.permission.PermissionActions
import org.maiwithu.maidroid.permission.PermissionState
import org.maiwithu.maidroid.ui.component.CollapsingGlassHeaderContainer
import org.maiwithu.maidroid.ui.component.FlatFeatureIconBadge
import org.maiwithu.maidroid.ui.component.FlatFeatureIcons
import org.maiwithu.maidroid.ui.component.FlatSwitch
import org.maiwithu.maidroid.ui.component.GlassHeaderCircleButton
import org.maiwithu.maidroid.ui.component.HomeTwoPaneSpec
import org.maiwithu.maidroid.ui.component.LiquidButton
import org.maiwithu.maidroid.ui.component.LiquidButtonStyle
import org.maiwithu.maidroid.ui.component.LocalBottomNavigationClearance
import org.maiwithu.maidroid.ui.component.appPageBackground
import org.maiwithu.maidroid.ui.component.computeLazyHeaderCollapseProgress
import org.maiwithu.maidroid.ui.component.overscrollBody
import org.maiwithu.maidroid.ui.component.rememberSplitHeaderOverscrollEffect
import org.maiwithu.maidroid.ui.component.statusBarTopInset
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardBorder
import org.maiwithu.maidroid.ui.theme.CardSurface
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.IconCyanBlue
import org.maiwithu.maidroid.ui.theme.IconFreshGreen
import org.maiwithu.maidroid.ui.theme.IconIrisBlue
import org.maiwithu.maidroid.ui.theme.IconRoseAlert
import org.maiwithu.maidroid.ui.theme.IconSkyBlue
import org.maiwithu.maidroid.ui.theme.IconSunnyAmber
import org.maiwithu.maidroid.ui.theme.IconWarmCoral
import org.maiwithu.maidroid.ui.theme.PageBackground
import org.maiwithu.maidroid.ui.theme.StatusDanger
import org.maiwithu.maidroid.ui.theme.StatusOnline
import org.maiwithu.maidroid.ui.theme.StatusWarning
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes

/**
 * 设置页里的权限管理：必选的存储权限，可选的通知、电池优化（开关），以及只能跳转系统设置的几项。
 * 竖屏时从设置列表点进来（左上角返回），横屏时作为右侧详情常驻显示，并随左侧主导航对齐顶部标题基线与紧凑顶栏尺寸。
 */
@Composable
internal fun PermissionManagementPage(
    state: PermissionState,
    actions: PermissionActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    showBackButton: Boolean = true,
    compact: Boolean = false,
    expandedTopOffset: Dp = 40.dp
) {
    val statusBarTop = statusBarTopInset()
    // 顶部下拉到顶时，大标题微幅跟随后停稳、卡片列表继续下沉拉开呼吸空间，营造自然视差弹性
    val overscroll = rememberSplitHeaderOverscrollEffect()
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val collapseDistancePx = with(density) { (if (compact) 40.dp else 52.dp).toPx() }
    val collapseProgress = computeLazyHeaderCollapseProgress(
        firstVisibleItemIndex = listState.firstVisibleItemIndex,
        firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
        collapseDistancePx = collapseDistancePx
    )
    // 单一“权限管理”标题与返回按钮随滚动平滑缩小吸顶，卡片穿过状态栏时提供渐进毛玻璃模糊。
    CollapsingGlassHeaderContainer(
        title = "权限管理",
        collapseProgress = collapseProgress,
        statusBarTop = statusBarTop,
        horizontalPadding = HomeTwoPaneSpec.OuterHorizontalPadding,
        expandedTopOffset = expandedTopOffset,
        expandedFontSize = if (compact) TextSizes.DisplayCompact else TextSizes.Display,
        collapsedFontSize = if (compact) 18.sp else 19.sp,
        topBarHeight = if (compact) 46.dp else 52.dp,
        headerOverscrollPx = overscroll.headerOverscrollPx,
        leadingAction = if (showBackButton) {
            {
                GlassHeaderCircleButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    onClick = onBack
                )
            }
        } else {
            null
        },
        modifier = modifier
            .fillMaxSize()
            .appPageBackground(PageBackground)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            overscrollEffect = overscroll,
            contentPadding = PaddingValues(
                top = statusBarTop + expandedTopOffset,
                start = HomeTwoPaneSpec.OuterHorizontalPadding,
                end = HomeTwoPaneSpec.OuterHorizontalPadding,
                bottom = LocalBottomNavigationClearance.current
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                // 顶部标题与返回钮已由外层连续缩放顶栏承接，列表首项只保留占位呼吸留白。
                Spacer(modifier = Modifier.height(if (compact) 40.dp else 50.dp))
            }

            item {
                Text(
                    text = "必选权限",
                    color = TextMuted,
                    fontSize = TextSizes.Caption,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 14.sp,
                    modifier = Modifier
                        .overscrollBody(overscroll)
                        .padding(top = 4.dp, start = 4.dp)
                )
            }

            item {
                PermissionActionCard(
                    icon = FlatFeatureIcons.Storage,
                    iconColor = IconSkyBlue,
                    title = "存储权限",
                    description = "解包容器、保存 MaiBot 配置和访问外部文件。",
                    badgeText = "必须",
                    granted = state.storageGranted,
                    actionText = if (state.storageGranted) "已授权" else "去授权",
                    onAction = actions.requestStorage,
                    modifier = Modifier.overscrollBody(overscroll)
                )
            }

            item {
                Text(
                    text = "可选权限",
                    color = TextMuted,
                    fontSize = TextSizes.Caption,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 14.sp,
                    modifier = Modifier
                        .overscrollBody(overscroll)
                        .padding(top = 8.dp, start = 4.dp)
                )
            }

            item {
                PermissionSwitchCard(
                    icon = FlatFeatureIcons.NotificationKeepAlive,
                    iconColor = IconWarmCoral,
                    title = "通知保活",
                    description = "允许前台服务通知，提升后台运行稳定性。",
                    checked = state.notificationGranted,
                    onCheckedChange = { checked ->
                        if (checked) {
                            actions.enableNotification()
                        } else {
                            actions.disableNotification()
                        }
                    },
                    modifier = Modifier.overscrollBody(overscroll)
                )
            }

            item {
                PermissionSwitchCard(
                    icon = FlatFeatureIcons.BatteryOptimization,
                    iconColor = IconFreshGreen,
                    title = "关闭电池优化",
                    description = "减少系统在后台挂起 MaiDroid 的机会。",
                    checked = state.batteryOptimizationIgnored,
                    onCheckedChange = { checked ->
                        if (checked) {
                            actions.ignoreBatteryOptimization()
                        } else {
                            actions.restoreBatteryOptimization()
                        }
                    },
                    modifier = Modifier.overscrollBody(overscroll)
                )
            }

            item {
                PermissionSwitchCard(
                    icon = FlatFeatureIcons.DesktopWindow,
                    iconColor = IconCyanBlue,
                    title = "悬浮窗权限",
                    description = "允许「扩展组件」里的悬浮桌宠显示在其他应用上层。",
                    checked = state.overlayGranted,
                    onCheckedChange = { actions.requestOverlay() },
                    modifier = Modifier.overscrollBody(overscroll)
                )
            }

            item {
                PermissionSystemSettingsCard(
                    icon = FlatFeatureIcons.AutoStart,
                    iconColor = IconIrisBlue,
                    title = "自启动和后台活动",
                    description = "在系统应用详情或厂商后台设置中管理。",
                    onClick = actions.openAutoStartSettings,
                    modifier = Modifier.overscrollBody(overscroll)
                )
            }

            item {
                PermissionSystemSettingsCard(
                    icon = FlatFeatureIcons.TaskLock,
                    iconColor = IconSunnyAmber,
                    title = "多任务加锁",
                    description = "在系统多任务界面中手动开启或关闭。",
                    onClick = actions.showTaskLockHint,
                    modifier = Modifier.overscrollBody(overscroll)
                )
            }

            item {
                PermissionSystemSettingsCard(
                    icon = FlatFeatureIcons.Accessibility,
                    iconColor = IconRoseAlert,
                    title = "无障碍服务",
                    description = "高风险权限，仅在确实需要时开启。",
                    badgeText = "不建议",
                    destructive = true,
                    onClick = actions.openAccessibilitySettings,
                    modifier = Modifier.overscrollBody(overscroll)
                )
            }

            item {
                PermissionSystemSettingsCard(
                    icon = FlatFeatureIcons.DeviceAdmin,
                    iconColor = IconRoseAlert,
                    title = "设备管理员",
                    description = "高风险权限，请在系统安全设置中管理。",
                    badgeText = "不建议",
                    destructive = true,
                    onClick = actions.openDeviceAdminSettings,
                    modifier = Modifier.overscrollBody(overscroll)
                )
            }
        }
    }
}

@Composable
private fun PermissionActionCard(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    description: String,
    badgeText: String,
    granted: Boolean,
    actionText: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    PermissionBaseCard(
        icon = icon,
        iconColor = iconColor,
        title = title,
        description = description,
        badgeText = badgeText,
        statusText = if (granted) "已开启" else "未开启",
        statusColor = if (granted) StatusOnline else StatusWarning,
        modifier = modifier
    ) {
        LiquidButton(
            onClick = onAction,
            enabled = !granted,
            style = LiquidButtonStyle.Primary,
            shape = { RoundedRectangle(Corners.Control) },
            minHeight = 34.dp,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = actionText,
                fontSize = TextSizes.Caption,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun PermissionSwitchCard(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    PermissionBaseCard(
        icon = icon,
        iconColor = iconColor,
        title = title,
        description = description,
        badgeText = "可选",
        statusText = if (checked) "已开启" else "未开启",
        statusColor = if (checked) StatusOnline else TextMuted,
        modifier = modifier
    ) {
        FlatSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun PermissionSystemSettingsCard(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badgeText: String = "可选",
    destructive: Boolean = false
) {
    PermissionBaseCard(
        icon = icon,
        iconColor = iconColor,
        title = title,
        description = description,
        badgeText = badgeText,
        statusText = "系统设置",
        statusColor = if (destructive) StatusWarning else TextMuted,
        badgeColor = if (destructive) StatusDanger else BrandOrange,
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = TextMuted.copy(alpha = 0.6f),
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun PermissionBaseCard(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    description: String,
    badgeText: String,
    statusText: String,
    statusColor: Color,
    modifier: Modifier = Modifier,
    badgeColor: Color = BrandOrange,
    trailingContent: @Composable () -> Unit
) {
    // 取消硬质边框改用纯平圆角卡片，角标换为柔色底块避免塑料方砖感
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp)
            .clip(RoundedCornerShape(Corners.Chip))
            .background(CardSurface)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FlatFeatureIconBadge(
            icon = icon,
            backgroundColor = iconColor,
            size = 40.dp,
            iconSize = 26.dp
        )

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    color = TextPrimary,
                    fontSize = TextSizes.BodyLarge,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 19.sp,
                    // 标题较长时换到第二行，不截断成省略号。
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                Spacer(modifier = Modifier.width(6.dp))

                Text(
                    text = badgeText,
                    color = badgeColor,
                    fontSize = TextSizes.Tag,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(Corners.Tag))
                        .background(badgeColor.copy(alpha = 0.16f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = description,
                color = TextMuted,
                fontSize = TextSizes.Caption,
                lineHeight = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = statusText,
                color = statusColor,
                fontSize = TextSizes.Caption,
                fontWeight = FontWeight.Medium,
                lineHeight = 14.sp,
                maxLines = 1
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        trailingContent()
    }
}
