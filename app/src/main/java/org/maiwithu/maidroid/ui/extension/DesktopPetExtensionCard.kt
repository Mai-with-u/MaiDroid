package org.maiwithu.maidroid.ui.extension

import android.widget.FrameLayout
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.maiwithu.maidroid.pet.DesktopPetBot
import org.maiwithu.maidroid.pet.DesktopPetPhase
import org.maiwithu.maidroid.pet.DesktopPetUiState
import org.maiwithu.maidroid.ui.component.FlatFeatureIconBadge
import org.maiwithu.maidroid.ui.component.FlatFeatureIcons
import org.maiwithu.maidroid.ui.component.FlatSwitch
import org.maiwithu.maidroid.ui.component.MaiDialog
import org.maiwithu.maidroid.ui.component.MaiDialogButton
import org.maiwithu.maidroid.ui.component.MaiDialogButtonRow
import org.maiwithu.maidroid.ui.component.MaiDialogButtonStyle
import org.maiwithu.maidroid.ui.component.MaiDialogSize
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardBorder
import org.maiwithu.maidroid.ui.theme.CardSurface
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.IconCyanBlue
import org.maiwithu.maidroid.ui.theme.IconSkyBlue
import org.maiwithu.maidroid.ui.theme.OnBrandOrange
import org.maiwithu.maidroid.ui.theme.PageBackground
import org.maiwithu.maidroid.ui.theme.StatusDanger
import org.maiwithu.maidroid.ui.theme.StatusOnline
import org.maiwithu.maidroid.ui.theme.StatusWarning
import org.maiwithu.maidroid.ui.theme.TextFaint
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes
import org.maiwithu.maidroid.webui.MaiBotWebUi

/** 悬浮桌宠主控制卡片：状态指示、总开关、宿主与前端同步信息、多 Bot 切换及阶段动作引导。 */
@Composable
internal fun FloatingPetExtensionCard(
    entry: ExtensionEntry,
    petState: DesktopPetUiState,
    webUiOnline: Boolean,
    overlayGranted: Boolean,
    onToggle: (Boolean) -> Unit,
    onRequestPermission: () -> Unit,
    onOpenLogin: () -> Unit,
    onEnableRemoteMode: () -> Unit,
    onSelectBot: (String) -> Unit,
    onToggleVisibility: () -> Unit,
    onOpenRecords: () -> Unit,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val switchChecked = petState.desiredEnabled || petState.overlayActive
    val phaseColor = resolvePetPhaseColor(petState)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corners.Chip))
            .background(CardSurface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FlatFeatureIconBadge(
                icon = FlatFeatureIcons.DesktopWindow,
                backgroundColor = IconCyanBlue,
                size = 42.dp,
                iconSize = 26.dp
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.title,
                        color = TextPrimary,
                        fontSize = TextSizes.Title,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    ExtensionTagPill(text = entry.badge, color = BrandOrange)
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = entry.subtitle,
                    color = TextMuted,
                    fontSize = TextSizes.Caption,
                    lineHeight = 17.sp
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            FlatSwitch(
                checked = switchChecked,
                onCheckedChange = onToggle
            )
        }

        PetRuntimeSummaryBox(
            petState = petState,
            phaseColor = phaseColor,
            webUiOnline = webUiOnline,
            overlayGranted = overlayGranted,
            onRequestPermission = onRequestPermission
        )

        if (petState.bots.size > 1) {
            PetBotSelectorRow(
                bots = petState.bots,
                selectedBotId = petState.selectedBotId,
                onSelectBot = onSelectBot
            )
        }

        PetActionPromptBanner(
            petState = petState,
            webUiOnline = webUiOnline,
            onOpenLogin = onOpenLogin,
            onEnableRemoteMode = onEnableRemoteMode,
            onReconnect = onReconnect
        )

        if (petState.desiredEnabled || petState.overlayActive) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CompactActionButton(
                    label = if (petState.overlayVisible) "隐藏桌宠" else "显示桌宠",
                    enabled = petState.overlayActive,
                    onClick = onToggleVisibility,
                    modifier = Modifier.weight(1f)
                )
                CompactActionButton(
                    label = "探索记录",
                    enabled = petState.overlayActive,
                    onClick = onOpenRecords,
                    modifier = Modifier.weight(1f)
                )
                CompactActionButton(
                    label = "重连",
                    enabled = !petState.updatingConfig,
                    onClick = onReconnect,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** 通用紧凑状态标签胶囊，供悬浮桌宠卡片与预留扩展卡片共享样式。 */
@Composable
internal fun ExtensionTagPill(
    text: String,
    color: Color
) {
    Text(
        text = text,
        color = color,
        fontSize = TextSizes.Tag,
        fontWeight = FontWeight.Medium,
        lineHeight = 12.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(Corners.Tag))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/** 悬浮窗权限引导弹窗：在跳转到系统设置前清晰说明用途。 */
@Composable
internal fun OverlayPermissionGuideDialog(
    onDismiss: () -> Unit,
    onConfirmGrant: () -> Unit
) {
    MaiDialog(
        title = "授予悬浮窗权限",
        onDismissRequest = onDismiss,
        size = MaiDialogSize.Compact,
        footer = {
            MaiDialogButtonRow {
                MaiDialogButton(
                    text = "暂不开启",
                    onClick = onDismiss,
                    style = MaiDialogButtonStyle.Secondary,
                    modifier = Modifier.weight(1f)
                )
                MaiDialogButton(
                    text = "前往系统设置",
                    onClick = onConfirmGrant,
                    style = MaiDialogButtonStyle.Primary,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    ) {
        Text(
            text = "悬浮桌宠需要在系统设置中允许 MaiDroid「显示在其他应用上层」，这样回到桌面或切到其他应用时麦麦也能继续陪伴与互动。",
            color = TextPrimary,
            fontSize = TextSizes.Body,
            lineHeight = 20.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "授予权限后返回本页，应用会自动继续连接本机悬浮桌宠。",
            color = TextMuted,
            fontSize = TextSizes.Caption,
            lineHeight = 17.sp
        )
    }
}

/** 内置本机 WebUI 认证弹窗：关闭外层 scroll 以避免与内嵌 WebView 抢夺垂直滑动手势。 */
@Composable
internal fun WebUiLoginDialog(
    onDismiss: () -> Unit,
    onReconnect: () -> Unit,
    attachAuthView: (FrameLayout) -> Unit,
    detachAuthView: (FrameLayout) -> Unit
) {
    val context = LocalContext.current
    val authContainer = remember(context) { FrameLayout(context) }

    DisposableEffect(authContainer) {
        attachAuthView(authContainer)
        onDispose { detachAuthView(authContainer) }
    }

    MaiDialog(
        title = "登录本机 WebUI",
        onDismissRequest = onDismiss,
        size = MaiDialogSize.Expanded,
        scrollable = false,
        footer = {
            MaiDialogButtonRow {
                MaiDialogButton(
                    text = "暂先关闭",
                    onClick = onDismiss,
                    style = MaiDialogButtonStyle.Secondary,
                    modifier = Modifier.weight(1f)
                )
                MaiDialogButton(
                    text = "重新认证",
                    onClick = onReconnect,
                    style = MaiDialogButtonStyle.SoftAccent,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    ) {
        Text(
            text = "请在下方页面输入本机 MaiBot WebUI 访问凭证，登录成功后将自动切回悬浮桌宠会话。",
            color = TextMuted,
            fontSize = TextSizes.Caption,
            lineHeight = 16.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        AndroidView(
            factory = { authContainer },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .heightIn(min = 180.dp, max = 440.dp)
                .clip(RoundedCornerShape(Corners.Tag))
                .background(PageBackground)
        )
    }
}

/** 运行时摘要区：连接阶段、宿主地址与容器静态前端同步状态一目了然。 */
@Composable
private fun PetRuntimeSummaryBox(
    petState: DesktopPetUiState,
    phaseColor: Color,
    webUiOnline: Boolean,
    overlayGranted: Boolean,
    onRequestPermission: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corners.Tag))
            .background(PageBackground.copy(alpha = 0.65f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(phaseColor)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = petState.statusText,
                    color = TextPrimary,
                    fontSize = TextSizes.Body,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (!overlayGranted) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "待授权悬浮窗",
                    color = StatusWarning,
                    fontSize = TextSizes.Tag,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(Corners.Tag))
                        .background(StatusWarning.copy(alpha = 0.16f))
                        .clickable(role = Role.Button, onClick = onRequestPermission)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "本机服务 ${MaiBotWebUi.URL}",
                color = TextMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = TextSizes.Caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (webUiOnline) "WebUI 在线" else "WebUI 离线",
                color = if (webUiOnline) StatusOnline else StatusWarning,
                fontSize = TextSizes.Caption,
                fontWeight = FontWeight.Medium
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "前端资源",
                color = TextMuted,
                fontSize = TextSizes.Caption
            )
            Text(
                text = petState.assetSource.label,
                color = if (petState.containerHasDesktopPet) IconCyanBlue else TextMuted,
                fontSize = TextSizes.Caption,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/** 多实例机器人选择器：仅在宿主机托管了两个及以上机器人时展示。 */
@Composable
private fun PetBotSelectorRow(
    bots: List<DesktopPetBot>,
    selectedBotId: String,
    onSelectBot: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "联动机器人实例",
            color = TextMuted,
            fontSize = TextSizes.Caption,
            fontWeight = FontWeight.Medium
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            bots.forEach { bot ->
                val selected = bot.botId == selectedBotId
                val bg = if (selected) BrandOrange.copy(alpha = 0.2f) else PageBackground.copy(alpha = 0.6f)
                val border = if (selected) BrandOrange else CardBorder
                val textColor = if (selected) BrandOrange else TextMuted
                val displayName = bot.name.ifBlank { bot.botId.take(8) }
                Text(
                    text = displayName,
                    color = textColor,
                    fontSize = TextSizes.Caption,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(Corners.Tag))
                        .background(bg)
                        .border(BorderStroke(1.dp, border), RoundedCornerShape(Corners.Tag))
                        .clickable(role = Role.RadioButton) { onSelectBot(bot.botId) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}

/** 根据当前连接/配置/错误阶段按需展示对应内联引导卡片。 */
@Composable
private fun PetActionPromptBanner(
    petState: DesktopPetUiState,
    webUiOnline: Boolean,
    onOpenLogin: () -> Unit,
    onEnableRemoteMode: () -> Unit,
    onReconnect: () -> Unit
) {
    when {
        petState.phase == DesktopPetPhase.AuthRequired -> {
            PhaseCalloutBox(
                title = "需要登录本机 WebUI",
                body = "当前本机 MaiBot 开启了自定义访问认证，请点击下方按钮在弹窗内完成一次登录。",
                accentColor = StatusWarning,
                actionLabel = "登录本机 WebUI",
                actionEnabled = true,
                onAction = onOpenLogin
            )
        }

        petState.phase == DesktopPetPhase.ConfigRequired -> {
            val detail = petState.remoteConfig?.error?.takeIf { it.isNotBlank() }
                ?: "需启用当前机器人的 desktop_pet 并切换到 remote 模式，点击即可自动写入配置并热重载。"
            PhaseCalloutBox(
                title = "尚未启用远端桌宠模式",
                body = detail,
                accentColor = BrandOrange,
                actionLabel = if (petState.updatingConfig) "正在启用…" else "一键开启远端模式",
                actionEnabled = !petState.updatingConfig,
                onAction = onEnableRemoteMode
            )
        }

        petState.phase == DesktopPetPhase.Error && !petState.lastError.isNullOrBlank() -> {
            PhaseCalloutBox(
                title = "连接出现异常",
                body = petState.lastError,
                accentColor = StatusDanger,
                actionLabel = "重连悬浮桌宠",
                actionEnabled = !petState.updatingConfig,
                onAction = onReconnect
            )
        }

        !webUiOnline && !petState.overlayActive -> {
            PhaseCalloutBox(
                title = "本机 WebUI 尚未运行",
                body = "请先在首页启动 MaiBot 服务并确认 ${MaiBotWebUi.URL} 在线，随后打开上方开关即可召唤悬浮桌宠。",
                accentColor = IconSkyBlue,
                actionLabel = null,
                actionEnabled = false,
                onAction = {}
            )
        }
    }
}

@Composable
private fun PhaseCalloutBox(
    title: String,
    body: String,
    accentColor: Color,
    actionLabel: String?,
    actionEnabled: Boolean,
    onAction: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corners.Tag))
            .background(accentColor.copy(alpha = 0.12f))
            .border(BorderStroke(1.dp, accentColor.copy(alpha = 0.35f)), RoundedCornerShape(Corners.Tag))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            color = accentColor,
            fontSize = TextSizes.Body,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = body,
            color = TextPrimary,
            fontSize = TextSizes.Caption,
            lineHeight = 17.sp
        )
        if (!actionLabel.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(Corners.Tag))
                    .background(if (actionEnabled) accentColor else CardBorder)
                    .clickable(enabled = actionEnabled, role = Role.Button, onClick = onAction)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = actionLabel,
                    color = if (actionEnabled) OnBrandOrange else TextFaint,
                    fontSize = TextSizes.Caption,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun CompactActionButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Corners.Tag))
            .background(if (enabled) PageBackground.copy(alpha = 0.85f) else PageBackground.copy(alpha = 0.4f))
            .border(
                BorderStroke(1.dp, if (enabled) CardBorder else CardBorder.copy(alpha = 0.4f)),
                RoundedCornerShape(Corners.Tag)
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (enabled) TextPrimary else TextFaint,
            fontSize = TextSizes.Caption,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun resolvePetPhaseColor(petState: DesktopPetUiState): Color = when (petState.phase) {
    DesktopPetPhase.Running -> if (petState.connected) StatusOnline else BrandOrange
    DesktopPetPhase.Connecting -> BrandOrange
    DesktopPetPhase.AuthRequired, DesktopPetPhase.ConfigRequired -> StatusWarning
    DesktopPetPhase.Error -> StatusDanger
    DesktopPetPhase.Idle -> TextMuted
}
