package org.maiwithu.maidroid.ui.oobe

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maiwithu.maidroid.permission.PermissionActions
import org.maiwithu.maidroid.permission.PermissionState
import org.maiwithu.maidroid.ui.component.FlatFeatureIconBadge
import org.maiwithu.maidroid.ui.component.FlatFeatureIcons
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.IconPeachOrange
import org.maiwithu.maidroid.ui.theme.IconSkyBlue
import org.maiwithu.maidroid.ui.theme.IconWarmCoral
import org.maiwithu.maidroid.ui.theme.OnBrandOrange
import org.maiwithu.maidroid.ui.theme.OobeSurface
import org.maiwithu.maidroid.ui.theme.OobeSurfaceVariant
import org.maiwithu.maidroid.ui.theme.OobeTextPrimary
import org.maiwithu.maidroid.ui.theme.OobeTextSecondary
import org.maiwithu.maidroid.ui.theme.StatusDanger
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextSizes

// OOBE 第一步“授予权限”的内容：必选的消息通知权限卡片 + 可选的外部存储权限卡片 + 可选的后台保活卡片（四项保活设置）。

/** 手机竖屏：三张卡片上下排列，整列可滚动。 */
@Composable
internal fun CompactOobePermissionList(
    state: PermissionState,
    actions: PermissionActions
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        NotificationPermissionCard(granted = state.notificationGranted, onAuthorize = actions.enableNotification)
        Spacer(modifier = Modifier.height(14.dp))
        StoragePermissionCard(granted = state.storageGranted, onAuthorize = actions.requestStorage)
        Spacer(modifier = Modifier.height(14.dp))
        BackgroundKeepAliveCard(state = state, actions = actions)
        Spacer(modifier = Modifier.height(12.dp))
    }
}

/** 平板和横屏：右侧工作区宽于 760dp 时左右两栏（左侧通知与存储 4 : 右侧保活 6，各自滚动），否则上下排列。 */
@Composable
internal fun AdaptiveOobePermissionList(
    state: PermissionState,
    actions: PermissionActions
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        if (maxWidth >= 760.dp) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(0.4f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                ) {
                    NotificationPermissionCard(granted = state.notificationGranted, onAuthorize = actions.enableNotification)
                    Spacer(modifier = Modifier.height(14.dp))
                    StoragePermissionCard(granted = state.storageGranted, onAuthorize = actions.requestStorage)
                    Spacer(modifier = Modifier.height(12.dp))
                }

                Column(
                    modifier = Modifier
                        .weight(0.6f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                ) {
                    BackgroundKeepAliveCard(state = state, actions = actions)
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                NotificationPermissionCard(granted = state.notificationGranted, onAuthorize = actions.enableNotification)
                Spacer(modifier = Modifier.height(14.dp))
                StoragePermissionCard(granted = state.storageGranted, onAuthorize = actions.requestStorage)
                Spacer(modifier = Modifier.height(14.dp))
                BackgroundKeepAliveCard(state = state, actions = actions)
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun NotificationPermissionCard(
    granted: Boolean,
    onAuthorize: () -> Unit
) {
    OobePermissionCard(
        icon = FlatFeatureIcons.NotificationKeepAlive,
        iconColor = IconWarmCoral,
        title = "消息通知权限",
        description = "维持前台服务常驻通知，与容器后台保活直接挂钩，必须开启。",
        required = true,
        granted = granted,
        onAuthorize = onAuthorize
    )
}

@Composable
private fun StoragePermissionCard(
    granted: Boolean,
    onAuthorize: () -> Unit
) {
    OobePermissionCard(
        icon = FlatFeatureIcons.Storage,
        iconColor = IconSkyBlue,
        title = "外部存储权限",
        description = "按需让容器访问手机外部文件（/sdcard），不开启不影响安装与运行。",
        required = false,
        granted = granted,
        onAuthorize = onAuthorize
    )
}

/**
 * 后台保活卡片：电池优化能读出是否已授予；自启动、多任务加锁只能引导去系统设置；
 * 无障碍风险较高，用红色“不建议”标签和“你确定？！”提醒（为后续小手机自动化预留）。
 */
@Composable
private fun BackgroundKeepAliveCard(
    state: PermissionState,
    actions: PermissionActions
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corners.Card))
            .background(OobeSurface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FlatFeatureIconBadge(
                icon = FlatFeatureIcons.BackgroundKeepAlive,
                backgroundColor = IconPeachOrange,
                size = 44.dp,
                iconSize = 28.dp
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "后台保活",
                        fontSize = TextSizes.Title,
                        fontWeight = FontWeight.Bold,
                        color = OobeTextPrimary,
                        letterSpacing = 0.sp,
                        lineHeight = 22.sp
                    )

                    OobePermissionTag(
                        text = "可选",
                        backgroundColor = OobeTextPrimary.copy(alpha = 0.14f),
                        textColor = OobeTextPrimary.copy(alpha = 0.82f)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "MaiSaka 需要后台权限才能在切换到其他应用时保持后台运行；这个设置是可选项，你可以视情况开启一部分。",
                    fontSize = TextSizes.Caption,
                    fontWeight = FontWeight.Normal,
                    color = OobeTextSecondary,
                    letterSpacing = 0.sp,
                    lineHeight = 16.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Corners.Panel))
                .background(OobeSurfaceVariant)
                .padding(8.dp)
        ) {
            KeepAlivePermissionRow(
                tagText = "推荐",
                title = "关闭电池优化",
                granted = state.batteryOptimizationIgnored,
                onAuthorize = actions.ignoreBatteryOptimization
            )
            KeepAliveDivider()
            KeepAlivePermissionRow(
                tagText = "推荐",
                title = "自启动和后台活动",
                onAuthorize = actions.openAutoStartSettings
            )
            KeepAliveDivider()
            KeepAlivePermissionRow(
                tagText = "推荐",
                title = "多任务加锁",
                onAuthorize = actions.showTaskLockHint
            )
            KeepAliveDivider()
            KeepAlivePermissionRow(
                tagText = "不建议",
                title = "无障碍服务",
                actionText = "你确定？！",
                destructive = true,
                onAuthorize = actions.openAccessibilitySettings
            )
        }
    }
}

// 保活卡片里的一行：标签 + 名称 + 右侧动作文字。已授予的行不可点，动作文字变成“已授权”。
@Composable
private fun KeepAlivePermissionRow(
    tagText: String,
    title: String,
    onAuthorize: () -> Unit,
    modifier: Modifier = Modifier,
    granted: Boolean = false,
    actionText: String = "去授权",
    destructive: Boolean = false
) {
    val actionLabel = if (granted) "已授权" else actionText
    val actionColor = if (granted) BrandOrange else TextMuted

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(Corners.Control))
            .clickable(enabled = !granted, onClick = onAuthorize)
            .padding(horizontal = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OobePermissionTag(
            text = tagText,
            backgroundColor = if (destructive) StatusDanger else BrandOrange,
            textColor = OnBrandOrange,
            minWidth = 44.dp
        )

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = title,
            modifier = Modifier.weight(1f),
            fontSize = TextSizes.Body,
            fontWeight = FontWeight.Medium,
            color = OobeTextPrimary,
            letterSpacing = 0.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 18.sp
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            Text(
                text = actionLabel,
                fontSize = TextSizes.Caption,
                fontWeight = FontWeight.Normal,
                color = actionColor,
                maxLines = 1,
                softWrap = false,
                letterSpacing = 0.sp,
                lineHeight = 14.sp
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = actionColor,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun KeepAliveDivider() {
    Spacer(modifier = Modifier.height(4.dp))
    // 沿用 OOBE 前景色，确保浅色/深色引导卡片分割线保持柔和对比
    HorizontalDivider(color = OobeTextPrimary.copy(alpha = 0.08f), thickness = 1.dp)
    Spacer(modifier = Modifier.height(3.dp))
}
