package org.maiwithu.maidroid.ui.oobe

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maiwithu.maidroid.R
import org.maiwithu.maidroid.permission.PermissionActions
import org.maiwithu.maidroid.permission.PermissionState
import org.maiwithu.maidroid.ui.theme.Orange400
import org.maiwithu.maidroid.ui.theme.Orange500
import org.maiwithu.maidroid.ui.theme.SurfaceDark
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSecondary

// OOBE 第一步“授予权限”的内容：必需的存储权限卡片 + 可选的后台保活卡片（六项保活设置）。

/** 手机竖屏：两张卡片上下排列，整列可滚动。 */
@Composable
internal fun CompactOobePermissionCards(
    state: PermissionState,
    actions: PermissionActions
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        StoragePermissionCard(granted = state.storageGranted, onAuthorize = actions.requestStorage)
        Spacer(modifier = Modifier.height(20.dp))
        BackgroundKeepAliveCard(state = state, actions = actions)
        Spacer(modifier = Modifier.height(12.dp))
    }
}

/** 平板和横屏：右侧工作区宽于 760dp 时左右两栏（存储 4 : 保活 6，各自滚动），否则上下排列。 */
@Composable
internal fun AdaptiveOobePermissionCards(
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
                StoragePermissionCard(granted = state.storageGranted, onAuthorize = actions.requestStorage)
                Spacer(modifier = Modifier.height(14.dp))
                BackgroundKeepAliveCard(state = state, actions = actions)
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun StoragePermissionCard(
    granted: Boolean,
    onAuthorize: () -> Unit
) {
    OobePermissionCard(
        iconRes = R.drawable.ic_storage_hdd,
        title = "存储权限",
        description = "用于解包容器、保存 MaiBot 配置和访问外部文件。",
        required = true,
        granted = granted,
        onAuthorize = onAuthorize
    )
}

/**
 * 后台保活卡片：通知和电池优化能读出是否已授予；自启动、多任务加锁只能引导去系统设置；
 * 无障碍、设备管理员风险高，用红色“不建议”标签和“你确定？！”提醒。
 */
@Composable
private fun BackgroundKeepAliveCard(
    state: PermissionState,
    actions: PermissionActions
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(SurfaceDark)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(71.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Orange400),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_job_run),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(33.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "后台保活",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        lineHeight = 24.sp
                    )

                    Spacer(modifier = Modifier.width(5.dp))

                    KeepAliveTag(
                        text = "可选",
                        backgroundColor = Orange400,
                        width = 48.dp,
                        textSize = 12
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "MaiSaka 需要后台权限才能在切换到其他应用时保持后台运行；这个设置是可选项，你可以视情况开启一部分。",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextSecondary,
                    lineHeight = 15.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(272.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1C1C1E))
                .padding(8.dp)
        ) {
            KeepAlivePermissionRow(
                tagText = "推荐",
                title = "通知保活",
                granted = state.notificationGranted,
                onAuthorize = actions.enableNotification
            )
            KeepAliveDivider()
            KeepAlivePermissionRow(
                tagText = "推荐",
                title = "关闭电池优化",
                granted = state.batteryOptimizationIgnored,
                onAuthorize = actions.ignoreBatteryOptimization
            )
            KeepAliveDivider()
            KeepAlivePermissionRow(
                tagText = "推荐",
                title = "开启自启动&后台活动",
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
            KeepAliveDivider()
            KeepAlivePermissionRow(
                tagText = "不建议",
                title = "设备管理员",
                actionText = "你确定？！",
                destructive = true,
                onAuthorize = actions.openDeviceAdminSettings
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
    val actionColor = if (granted) Orange500 else Color(0xFF808080)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = !granted, onClick = onAuthorize)
            .padding(horizontal = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        KeepAliveTag(
            text = tagText,
            backgroundColor = if (destructive) Color(0xFFE90F0F) else Orange500,
            width = if (destructive) 42.dp else 32.dp,
            textSize = 10
        )

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = title,
            modifier = Modifier.weight(1f),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
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
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal,
                color = actionColor,
                maxLines = 1,
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
    HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 1.dp)
    Spacer(modifier = Modifier.height(3.dp))
}

@Composable
private fun KeepAliveTag(
    text: String,
    backgroundColor: Color,
    width: Dp,
    textSize: Int
) {
    Box(
        modifier = Modifier
            .size(width = width, height = 18.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = textSize.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
            lineHeight = textSize.sp
        )
    }
}
