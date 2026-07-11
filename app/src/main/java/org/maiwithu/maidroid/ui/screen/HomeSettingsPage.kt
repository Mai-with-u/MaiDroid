package org.maiwithu.maidroid.ui.screen

import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maiwithu.maidroid.container.ContainerSshManager
import org.maiwithu.maidroid.container.ContainerSshPhase
import org.maiwithu.maidroid.container.ContainerSshState
import org.maiwithu.maidroid.platform.NapCatPlatformManager
import org.maiwithu.maidroid.service.ChatbotService

private const val PermissionSettingsDetail = "permissions"

@Composable
internal fun SettingsPage(
    webUiOnline: Boolean,
    versionName: String,
    modifier: Modifier = Modifier,
    permissionState: PermissionManagementState = PermissionManagementState(),
    permissionActions: PermissionManagementActions = PermissionManagementActions()
) {
    val context = LocalContext.current
    val sshManager = remember(context) { ContainerSshManager.get(context) }
    val sshState by sshManager.state.collectAsState()
    val napCatManager = remember(context) { NapCatPlatformManager.get(context) }
    val napCatState by napCatManager.state.collectAsState()
    val runningPlatformCount = remember(napCatState) {
        installedPlatformCards(napCatState).count { it.running }
    }
    var showSshDialog by remember { mutableStateOf(false) }
    var selectedDetail by rememberSaveable { mutableStateOf<String?>(null) }
    var sshPortText by remember { mutableStateOf(sshState.port.toString()) }
    var sshPasswordText by remember { mutableStateOf("") }

    fun openSshDialog() {
        sshPortText = sshState.port.toString()
        sshPasswordText = ""
        showSshDialog = true
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MainSurface)
    ) {
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        val safeDrawingLeft = with(density) {
            WindowInsets.safeDrawing.getLeft(this, layoutDirection).toDp()
        }
        val safeDrawingRight = with(density) {
            WindowInsets.safeDrawing.getRight(this, layoutDirection).toDp()
        }
        val useTwoPane = settingsUseTwoPane(
            widthDp = maxWidth.value,
            heightDp = maxHeight.value
        )
        val safeContentWidth = (maxWidth - safeDrawingLeft - safeDrawingRight)
            .coerceAtLeast(0.dp)
        val navigationWidth = settingsNavigationWidthDp(safeContentWidth.value).dp

        BackHandler(enabled = !useTwoPane && selectedDetail != null) {
            selectedDetail = null
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
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MainSurface)
                    .absolutePadding(left = safeDrawingLeft, right = safeDrawingRight)
            ) {
                SettingsNavigationPane(
                    webUiOnline = webUiOnline,
                    versionName = versionName,
                    permissionState = permissionState,
                    sshState = sshState,
                    runningPlatformCount = runningPlatformCount,
                    permissionSelected = true,
                    compact = true,
                    onPermissionClick = {},
                    onSshConfigureClick = { openSshDialog() },
                    onSshEnabledChange = onSshEnabledChange,
                    modifier = Modifier
                        .width(navigationWidth)
                        .fillMaxHeight()
                )

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(PlatformBorder.copy(alpha = 0.8f))
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .background(MainSurface)
                ) {
                    PermissionManagementPage(
                        state = permissionState,
                        actions = permissionActions,
                        onBack = {},
                        showBackButton = false,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        } else {
            AnimatedContent(
                targetState = selectedDetail != null,
                modifier = Modifier
                    .fillMaxSize()
                    .background(MainSurface),
                transitionSpec = { settingsDetailTransition() },
                label = "SettingsPermissionTransition"
            ) { showingPermissionPage ->
                if (showingPermissionPage) {
                    PermissionManagementPage(
                        state = permissionState,
                        actions = permissionActions,
                        onBack = { selectedDetail = null },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    SettingsNavigationPane(
                        webUiOnline = webUiOnline,
                        versionName = versionName,
                        permissionState = permissionState,
                        sshState = sshState,
                        runningPlatformCount = runningPlatformCount,
                        permissionSelected = false,
                        compact = false,
                        onPermissionClick = { selectedDetail = PermissionSettingsDetail },
                        onSshConfigureClick = { openSshDialog() },
                        onSshEnabledChange = onSshEnabledChange,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
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

internal fun settingsUseTwoPane(widthDp: Float, heightDp: Float): Boolean =
    widthDp > heightDp || widthDp >= 960f

internal fun settingsNavigationWidthDp(availableWidthDp: Float): Float =
    availableWidthDp.coerceAtLeast(0f) / 3f

@Composable
private fun SettingsNavigationPane(
    webUiOnline: Boolean,
    versionName: String,
    permissionState: PermissionManagementState,
    sshState: ContainerSshState,
    runningPlatformCount: Int,
    permissionSelected: Boolean,
    compact: Boolean,
    onPermissionClick: () -> Unit,
    onSshConfigureClick: () -> Unit,
    onSshEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val statusBarTop = statusBarTopInset()

    LazyColumn(
        modifier = modifier
            .background(MainSurface)
            .padding(top = statusBarTop),
        contentPadding = PaddingValues(
            top = if (compact) 4.dp else 44.dp,
            start = if (compact) 12.dp else 18.dp,
            end = if (compact) 12.dp else 18.dp,
            bottom = 112.dp
        ),
        verticalArrangement = Arrangement.spacedBy(if (compact) 14.dp else 18.dp)
    ) {
        item {
            Column(modifier = Modifier.padding(horizontal = if (compact) 4.dp else 2.dp)) {
                Text(
                    text = "设置",
                    color = Color.White,
                    fontSize = if (compact) 27.sp else 34.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = if (compact) 30.sp else 38.sp
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = if (compact) "MaiDroid 控制中心" else "管理服务、权限和应用状态",
                    color = PlatformTextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 17.sp
                )
            }
        }

        item {
            SettingsSection(title = "服务") {
                SettingsNavigationRow(
                    icon = Icons.Outlined.Settings,
                    title = "WebUI",
                    supportingText = if (webUiOnline) "服务在线" else "服务离线",
                    accent = if (webUiOnline) PlatformOnline else HomeOffline,
                    trailing = if (compact) null else {
                        {
                            SettingsStatusDot(if (webUiOnline) PlatformOnline else HomeOffline)
                        }
                    }
                )

                SettingsDivider()

                SettingsNavigationRow(
                    icon = Icons.Outlined.Lock,
                    title = "容器 SSH",
                    supportingText = containerSshSummaryText(sshState),
                    accent = when {
                        sshState.phase == ContainerSshPhase.Failed -> HomeOffline
                        sshState.isRunning -> PlatformOnline
                        else -> PlatformOrange
                    },
                    onClick = onSshConfigureClick,
                    trailing = if (compact) {
                        null
                    } else {
                        {
                            Switch(
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
            }
        }

        item {
            SettingsSection(title = "系统") {
                SettingsNavigationRow(
                    icon = Icons.Outlined.Security,
                    title = "权限管理",
                    supportingText = permissionSummary(permissionState),
                    accent = if (permissionState.storageGranted) PlatformOnline else HomeOffline,
                    selected = permissionSelected,
                    exposesSelection = true,
                    onClick = onPermissionClick,
                    trailing = if (compact) null else {
                        {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = "进入权限管理",
                                tint = if (permissionSelected) {
                                    PlatformOrange
                                } else {
                                    PlatformTextSecondary
                                },
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                )
            }
        }

        item {
            SettingsSection(title = "应用") {
                SettingsNavigationRow(
                    icon = Icons.Outlined.Notifications,
                    title = "消息平台",
                    supportingText = if (runningPlatformCount > 0) {
                        "$runningPlatformCount 个平台正在运行"
                    } else {
                        "暂无平台正在运行"
                    },
                    accent = if (runningPlatformCount > 0) PlatformOnline else HomeOffline,
                    trailing = if (compact) null else {
                        {
                            SettingsStatusDot(
                                if (runningPlatformCount > 0) PlatformOnline else HomeOffline
                            )
                        }
                    }
                )

                SettingsDivider()

                SettingsNavigationRow(
                    icon = Icons.Outlined.Info,
                    title = "应用版本",
                    supportingText = "v$versionName",
                    accent = PlatformOrange
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            color = PlatformTextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 14.sp,
            modifier = Modifier.padding(start = 6.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .border(1.dp, PlatformBorder, RoundedCornerShape(24.dp))
                .background(PlatformCardSurface),
            content = content
        )
    }
}

@Composable
private fun SettingsNavigationRow(
    icon: ImageVector,
    title: String,
    supportingText: String,
    accent: Color,
    modifier: Modifier = Modifier,
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
            .background(if (selected) PlatformOrange.copy(alpha = 0.10f) else Color.Transparent)
            .then(clickModifier)
            .padding(horizontal = 13.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(21.dp)
            )
        }

        Spacer(modifier = Modifier.width(11.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(3.dp))

            Text(
                text = supportingText,
                color = PlatformTextSecondary,
                fontSize = 11.sp,
                lineHeight = 14.sp,
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
private fun SettingsDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 62.dp, end = 14.dp)
            .height(1.dp)
            .background(PlatformBorder.copy(alpha = 0.72f))
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .padding(start = 13.dp, end = 13.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "启用 SSH",
            color = PlatformTextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            modifier = Modifier.clearAndSetSemantics {}
        )
    }
}

private fun permissionSummary(state: PermissionManagementState): String {
    val optionalGranted = listOf(
        state.notificationGranted,
        state.batteryOptimizationGranted
    ).count { it }
    val storageStatus = if (state.storageGranted) "存储已授权" else "存储未授权"
    return "$storageStatus · 可选项 $optionalGranted/2"
}

private fun AnimatedContentTransitionScope<Boolean>.settingsDetailTransition() =
    slideInHorizontally(
        animationSpec = spring(
            dampingRatio = 0.82f,
            stiffness = Spring.StiffnessMediumLow
        )
    ) { fullWidth ->
        if (targetState) fullWidth else -fullWidth
    } togetherWith slideOutHorizontally(
        animationSpec = spring(
            dampingRatio = 0.82f,
            stiffness = Spring.StiffnessMediumLow
        )
    ) { fullWidth ->
        if (targetState) -fullWidth else fullWidth
    } using SizeTransform(clip = true)

@Composable
private fun ContainerSshConfigDialog(
    portText: String,
    passwordText: String,
    configured: Boolean,
    onPortTextChange: (String) -> Unit,
    onPasswordTextChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (port: Int, password: String?) -> Unit
) {
    val parsedPort = portText.toIntOrNull()
    val errorText = when {
        parsedPort == null -> "端口需要是数字"
        parsedPort !in 1024..65535 -> "端口范围为 1024-65535"
        !configured && passwordText.isBlank() -> "root 密码不能为空"
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "容器 SSH 设置",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 22.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = portText,
                    onValueChange = onPortTextChange,
                    singleLine = true,
                    label = { Text("端口") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                OutlinedTextField(
                    value = passwordText,
                    onValueChange = onPasswordTextChange,
                    singleLine = true,
                    label = { Text("root 密码") },
                    placeholder = {
                        Text(if (configured) "留空则保持当前密码" else "输入 root 密码")
                    },
                    visualTransformation = PasswordVisualTransformation()
                )

                if (errorText != null) {
                    Text(
                        text = errorText,
                        color = HomeOffline,
                        fontSize = 12.sp,
                        lineHeight = 14.sp
                    )
                }
            }
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = PlatformBorder,
                    contentColor = Color.White
                )
            ) {
                Text(text = "取消", fontWeight = FontWeight.Bold)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val port = parsedPort ?: return@Button
                    onConfirm(port, passwordText.takeIf { it.isNotBlank() })
                },
                enabled = errorText == null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = PlatformOrange,
                    contentColor = Color.White
                )
            ) {
                Text(text = "确定", fontWeight = FontWeight.Bold)
            }
        },
        containerColor = PlatformCardSurface,
        titleContentColor = Color.White,
        textContentColor = PlatformTextSecondary
    )
}

private fun containerSshStatusText(state: ContainerSshState): String =
    when {
        !state.configured -> "未配置"
        state.isRunning -> "运行中 · 端口 ${state.port}"
        state.isStarting -> "启动中 · 端口 ${state.port}"
        state.phase == ContainerSshPhase.Failed -> "启动失败 · 端口 ${state.port}"
        state.enabled -> "已开启 · 端口 ${state.port}"
        else -> "已关闭 · 端口 ${state.port}"
    }

private fun containerSshSummaryText(state: ContainerSshState): String {
    val status = containerSshStatusText(state)
    val error = state.errorMessage?.takeIf {
        state.phase == ContainerSshPhase.Failed && it.isNotBlank()
    }
    return if (error == null) status else "$status\n$error"
}

private fun requestContainerSshSync(context: Context) {
    val intent = Intent(context, ChatbotService::class.java).apply {
        action = ChatbotService.ACTION_SYNC_CONTAINER_SSH
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
}
