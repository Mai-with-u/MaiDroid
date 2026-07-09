package org.maiwithu.maidroid.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class PermissionManagementState(
    val storageGranted: Boolean = false,
    val notificationGranted: Boolean = false,
    val batteryOptimizationGranted: Boolean = false
)

data class PermissionManagementActions(
    val onStorageAuthorize: () -> Unit = {},
    val onNotificationEnable: () -> Unit = {},
    val onNotificationDisable: () -> Unit = {},
    val onBatteryOptimizationEnable: () -> Unit = {},
    val onBatteryOptimizationDisable: () -> Unit = {},
    val onAutoStartSettings: () -> Unit = {},
    val onTaskLockSettings: () -> Unit = {},
    val onAccessibilitySettings: () -> Unit = {},
    val onDeviceAdminSettings: () -> Unit = {}
)

@Composable
internal fun PermissionManagementPage(
    state: PermissionManagementState,
    actions: PermissionManagementActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MainSurface)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 64.dp, start = 16.dp, end = 16.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(PlatformCardSurface)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Text(
                        text = "权限管理",
                        color = Color.White,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 32.sp
                    )
                }
            }

            item {
                Text(
                    text = "必选权限",
                    color = PlatformTextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 4.dp, start = 2.dp)
                )
            }

            item {
                PermissionActionCard(
                    icon = Icons.Outlined.Storage,
                    title = "存储权限",
                    description = "解包容器、保存 MaiBot 配置和访问外部文件。",
                    badgeText = "必须",
                    granted = state.storageGranted,
                    actionText = if (state.storageGranted) "已授权" else "去授权",
                    onAction = actions.onStorageAuthorize
                )
            }

            item {
                Text(
                    text = "可选权限",
                    color = PlatformTextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 8.dp, start = 2.dp)
                )
            }

            item {
                PermissionSwitchCard(
                    icon = Icons.Outlined.Notifications,
                    title = "通知保活",
                    description = "允许前台服务通知，提升后台运行稳定性。",
                    checked = state.notificationGranted,
                    onCheckedChange = { checked ->
                        if (checked) {
                            actions.onNotificationEnable()
                        } else {
                            actions.onNotificationDisable()
                        }
                    }
                )
            }

            item {
                PermissionSwitchCard(
                    icon = Icons.Outlined.BatterySaver,
                    title = "关闭电池优化",
                    description = "减少系统在后台挂起 MaiDroid 的机会。",
                    checked = state.batteryOptimizationGranted,
                    onCheckedChange = { checked ->
                        if (checked) {
                            actions.onBatteryOptimizationEnable()
                        } else {
                            actions.onBatteryOptimizationDisable()
                        }
                    }
                )
            }

            item {
                PermissionSystemSettingsCard(
                    icon = Icons.Outlined.Settings,
                    title = "开启自启动&后台活动",
                    description = "在系统应用详情或厂商后台设置中管理。",
                    onClick = actions.onAutoStartSettings
                )
            }

            item {
                PermissionSystemSettingsCard(
                    icon = Icons.Outlined.Lock,
                    title = "多任务加锁",
                    description = "在系统多任务界面中手动开启或关闭。",
                    onClick = actions.onTaskLockSettings
                )
            }

            item {
                PermissionSystemSettingsCard(
                    icon = Icons.Outlined.TouchApp,
                    title = "无障碍服务",
                    description = "高风险权限，仅在确实需要时开启。",
                    badgeText = "不建议",
                    destructive = true,
                    onClick = actions.onAccessibilitySettings
                )
            }

            item {
                PermissionSystemSettingsCard(
                    icon = Icons.Outlined.AdminPanelSettings,
                    title = "设备管理员",
                    description = "高风险权限，请在系统安全设置中管理。",
                    badgeText = "不建议",
                    destructive = true,
                    onClick = actions.onDeviceAdminSettings
                )
            }
        }
    }
}

@Composable
internal fun PermissionManagementEntryCard(
    state: PermissionManagementState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val readableOptionalCount = listOf(
        state.notificationGranted,
        state.batteryOptimizationGranted
    ).count { it }
    val statusText = buildString {
        append(if (state.storageGranted) "存储已授权" else "存储未授权")
        append(" · 可检测可选项 ")
        append(readableOptionalCount)
        append("/2")
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(88.dp)
            .clip(RoundedCornerShape(24.dp))
            .border(1.dp, PlatformBorder, RoundedCornerShape(24.dp))
            .background(PlatformCardSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(if (state.storageGranted) PlatformOnline else HomeOffline)
        )

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "权限管理",
                color = PlatformTextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 15.sp
            )

            Spacer(modifier = Modifier.height(5.dp))

            Text(
                text = statusText,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 21.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = PlatformTextSecondary,
            modifier = Modifier.size(28.dp)
        )
    }
}

@Composable
private fun PermissionActionCard(
    icon: ImageVector,
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
        title = title,
        description = description,
        badgeText = badgeText,
        statusText = if (granted) "已开启" else "未开启",
        statusColor = if (granted) PlatformOnline else HomeOffline,
        modifier = modifier
    ) {
        Button(
            onClick = onAction,
            enabled = !granted,
            modifier = Modifier.height(34.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = PlatformOrange,
                contentColor = Color.White,
                disabledContainerColor = PlatformBorder,
                disabledContentColor = PlatformTextSecondary
            ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
        ) {
            Text(
                text = actionText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun PermissionSwitchCard(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    PermissionBaseCard(
        icon = icon,
        title = title,
        description = description,
        badgeText = "可选",
        statusText = if (checked) "已开启" else "未开启",
        statusColor = if (checked) PlatformOnline else PlatformTextSecondary,
        modifier = modifier
    ) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun PermissionSystemSettingsCard(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badgeText: String = "可选",
    destructive: Boolean = false
) {
    PermissionBaseCard(
        icon = icon,
        title = title,
        description = description,
        badgeText = badgeText,
        statusText = "系统设置",
        statusColor = if (destructive) HomeOffline else PlatformTextSecondary,
        badgeColor = if (destructive) Color(0xFFE5484D) else PlatformOrange,
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = PlatformTextSecondary,
            modifier = Modifier.size(28.dp)
        )
    }
}

@Composable
private fun PermissionBaseCard(
    icon: ImageVector,
    title: String,
    description: String,
    badgeText: String,
    statusText: String,
    statusColor: Color,
    modifier: Modifier = Modifier,
    badgeColor: Color = PlatformOrange,
    trailingContent: @Composable () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 92.dp)
            .clip(RoundedCornerShape(24.dp))
            .border(1.dp, PlatformBorder, RoundedCornerShape(24.dp))
            .background(PlatformCardSurface)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(PlatformOrange.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = PlatformOrange,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 19.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                Spacer(modifier = Modifier.width(6.dp))

                Text(
                    text = badgeText,
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(badgeColor)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = description,
                color = PlatformTextSecondary,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = statusText,
                color = statusColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 14.sp,
                maxLines = 1
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        trailingContent()
    }
}
