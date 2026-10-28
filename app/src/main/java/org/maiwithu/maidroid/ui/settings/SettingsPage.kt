package org.maiwithu.maidroid.ui.settings

import org.maiwithu.maidroid.permission.PermissionActions
import org.maiwithu.maidroid.permission.PermissionState
import org.maiwithu.maidroid.appContainer
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maiwithu.maidroid.ssh.ContainerSshPhase
import org.maiwithu.maidroid.ssh.ContainerSshState
import org.maiwithu.maidroid.maibot.update.MaiBotUpdatePhase
import org.maiwithu.maidroid.maibot.update.MaiBotUpdateState
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardBorder
import org.maiwithu.maidroid.ui.theme.CardSurface
import org.maiwithu.maidroid.ui.theme.PageBackground
import org.maiwithu.maidroid.ui.theme.StatusOnline
import org.maiwithu.maidroid.ui.theme.StatusWarning
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.component.statusBarTopInset
import org.maiwithu.maidroid.ui.platforms.installedPlatformCards
import org.maiwithu.maidroid.ui.component.horizontalSlideTransition

private const val PermissionSettingsDetail = "permissions"

/**
 * 设置页：服务（WebUI 状态、MaiBot 在线更新、容器 SSH）、系统（权限管理）、应用（消息平台、版本）。
 * 横屏或宽屏时左右分栏，右侧固定显示权限管理；竖屏时点“权限管理”推入详情页，返回键退回列表。
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
    val sshManager = remember(context) { context.appContainer.containerSsh }
    val sshState by sshManager.state.collectAsState()
    val napCatManager = remember(context) { context.appContainer.napCat }
    val napCatState by napCatManager.state.collectAsState()
    val updateManager = remember(context) { context.appContainer.maiBotUpdater }
    val updateState by updateManager.state.collectAsState()
    val qqOfficialManager = remember(context) { context.appContainer.qqOfficial }
    val qqOfficialState by qqOfficialManager.state.collectAsState()
    // 设置页的“运行中平台”与消息平台页同一口径：NapCat 在线、官方机器人已启用且无连接报错都算。
    val runningPlatformCount = remember(napCatState, qqOfficialState) {
        installedPlatformCards(napCatState, qqOfficialState).count { it.running }
    }
    var showSshDialog by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var selectedDetail by rememberSaveable { mutableStateOf<String?>(null) }
    var sshPortText by remember { mutableStateOf(sshState.port.toString()) }
    var sshPasswordText by remember { mutableStateOf("") }

    // 进入设置页时读取一次当前 MaiBot 版本，供“MaiBot 更新”一行显示。
    LaunchedEffect(updateManager) {
        updateManager.loadInstalledRevision()
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
                    .background(PageBackground)
                    .absolutePadding(left = safeDrawingLeft, right = safeDrawingRight)
            ) {
                SettingsNavigationPane(
                    webUiOnline = webUiOnline,
                    versionName = versionName,
                    permissionState = permissionState,
                    sshState = sshState,
                    updateState = updateState,
                    runningPlatformCount = runningPlatformCount,
                    permissionSelected = true,
                    compact = true,
                    onPermissionClick = {},
                    onSshConfigureClick = { openSshDialog() },
                    onSshEnabledChange = onSshEnabledChange,
                    onUpdateClick = { showUpdateDialog = true },
                    modifier = Modifier
                        .width(navigationWidth)
                        .fillMaxHeight()
                )

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(CardBorder.copy(alpha = 0.8f))
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .background(PageBackground)
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
                    .background(PageBackground),
                transitionSpec = { horizontalSlideTransition(forward = targetState, dampingRatio = 0.82f) },
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
                        updateState = updateState,
                        runningPlatformCount = runningPlatformCount,
                        permissionSelected = false,
                        compact = false,
                        onPermissionClick = { selectedDetail = PermissionSettingsDetail },
                        onSshConfigureClick = { openSshDialog() },
                        onSshEnabledChange = onSshEnabledChange,
                        onUpdateClick = { showUpdateDialog = true },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        if (showUpdateDialog) {
            MaiBotUpdateDialog(
                state = updateState,
                onCheck = updateManager::checkForUpdate,
                onApply = updateManager::applyUpdate,
                onDismiss = { showUpdateDialog = false }
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

internal fun settingsUseTwoPane(widthDp: Float, heightDp: Float): Boolean =
    widthDp > heightDp || widthDp >= 960f

internal fun settingsNavigationWidthDp(availableWidthDp: Float): Float =
    availableWidthDp.coerceAtLeast(0f) / 3f

@Composable
private fun SettingsNavigationPane(
    webUiOnline: Boolean,
    versionName: String,
    permissionState: PermissionState,
    sshState: ContainerSshState,
    updateState: MaiBotUpdateState,
    runningPlatformCount: Int,
    permissionSelected: Boolean,
    compact: Boolean,
    onPermissionClick: () -> Unit,
    onSshConfigureClick: () -> Unit,
    onSshEnabledChange: (Boolean) -> Unit,
    onUpdateClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val statusBarTop = statusBarTopInset()

    LazyColumn(
        modifier = modifier
            .background(PageBackground)
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
                    color = TextMuted,
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
                    accent = if (webUiOnline) StatusOnline else StatusWarning,
                    trailing = if (compact) null else {
                        {
                            SettingsStatusDot(if (webUiOnline) StatusOnline else StatusWarning)
                        }
                    }
                )

                SettingsDivider()

                SettingsNavigationRow(
                    icon = Icons.Outlined.SystemUpdate,
                    title = "MaiBot 更新",
                    supportingText = maiBotUpdateSummaryText(updateState),
                    accent = maiBotUpdateAccent(updateState),
                    onClick = onUpdateClick,
                    trailing = if (compact) null else {
                        {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = "打开 MaiBot 更新",
                                tint = TextMuted,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                )

                SettingsDivider()

                SettingsNavigationRow(
                    icon = Icons.Outlined.Lock,
                    title = "容器 SSH",
                    supportingText = containerSshSummaryText(sshState),
                    accent = when {
                        sshState.phase == ContainerSshPhase.Failed -> StatusWarning
                        sshState.isRunning -> StatusOnline
                        else -> BrandOrange
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
                    accent = if (permissionState.storageGranted) StatusOnline else StatusWarning,
                    selected = permissionSelected,
                    exposesSelection = true,
                    onClick = onPermissionClick,
                    trailing = if (compact) null else {
                        {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = "进入权限管理",
                                tint = if (permissionSelected) {
                                    BrandOrange
                                } else {
                                    TextMuted
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
                    accent = if (runningPlatformCount > 0) StatusOnline else StatusWarning,
                    trailing = if (compact) null else {
                        {
                            SettingsStatusDot(
                                if (runningPlatformCount > 0) StatusOnline else StatusWarning
                            )
                        }
                    }
                )

                SettingsDivider()

                SettingsNavigationRow(
                    icon = Icons.Outlined.Info,
                    title = "应用版本",
                    supportingText = "v$versionName",
                    accent = BrandOrange
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
            color = TextMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 14.sp,
            modifier = Modifier.padding(start = 6.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .border(1.dp, CardBorder, RoundedCornerShape(24.dp))
                .background(CardSurface),
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
            .background(if (selected) BrandOrange.copy(alpha = 0.10f) else Color.Transparent)
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
                color = TextMuted,
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
            .background(CardBorder.copy(alpha = 0.72f))
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
            color = TextMuted,
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

// 失败标红，可更新和进行中用主题橙，已是最新或刚更新完标绿。
private fun maiBotUpdateAccent(state: MaiBotUpdateState): Color =
    when (state.phase) {
        MaiBotUpdatePhase.Failed -> StatusWarning
        MaiBotUpdatePhase.UpToDate,
        MaiBotUpdatePhase.Updated -> StatusOnline
        else -> BrandOrange
    }

private fun permissionSummary(state: PermissionState): String {
    val optionalGranted = listOf(
        state.notificationGranted,
        state.batteryOptimizationIgnored
    ).count { it }
    val storageStatus = if (state.storageGranted) "存储已授权" else "存储未授权"
    return "$storageStatus · 可选项 $optionalGranted/2"
}
