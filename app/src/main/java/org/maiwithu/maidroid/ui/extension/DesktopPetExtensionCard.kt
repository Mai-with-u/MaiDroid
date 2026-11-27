package org.maiwithu.maidroid.ui.extension

import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.kyant.shapes.Capsule
import org.maiwithu.maidroid.pet.DesktopPetBot
import org.maiwithu.maidroid.pet.DesktopPetPhase
import org.maiwithu.maidroid.pet.DesktopPetScaleSpec
import org.maiwithu.maidroid.pet.DesktopPetUiState
import org.maiwithu.maidroid.pet.normalizeDesktopPetScale
import org.maiwithu.maidroid.ui.component.FlatFeatureIconBadge
import org.maiwithu.maidroid.ui.component.FlatFeatureIcons
import org.maiwithu.maidroid.ui.component.FlatSwitch
import org.maiwithu.maidroid.ui.component.LiquidButton
import org.maiwithu.maidroid.ui.component.LiquidButtonStyle
import org.maiwithu.maidroid.ui.component.LiquidSlider
import org.maiwithu.maidroid.ui.component.MaiDialog
import org.maiwithu.maidroid.ui.component.MaiDialogButton
import org.maiwithu.maidroid.ui.component.MaiDialogButtonRow
import org.maiwithu.maidroid.ui.component.MaiDialogButtonStyle
import org.maiwithu.maidroid.ui.component.MaiDialogSize
import org.maiwithu.maidroid.ui.component.rememberLiquidHaptics
import org.maiwithu.maidroid.ui.component.rememberSurfaceBackdrop
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardDivider
import org.maiwithu.maidroid.ui.theme.CardSurface
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.DarkMaiDroidColors
import org.maiwithu.maidroid.ui.theme.IconCyanBlue
import org.maiwithu.maidroid.ui.theme.IconPeachOrange
import org.maiwithu.maidroid.ui.theme.IconSkyBlue
import org.maiwithu.maidroid.ui.theme.LocalMaiDroidColors
import org.maiwithu.maidroid.ui.theme.MaiDroidColors
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
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 扩展组件总览列表中的悬浮桌宠精简入口卡：
 * - 展示当前运行徽章、状态摘要与快捷总开关；
 * - 点击整张卡片可进入悬浮桌宠子页面调节缩放大小、切换机器人与执行快捷操作，维持主页列表轻量克制。
 */
@Composable
internal fun FloatingPetSummaryCard(
    entry: ExtensionEntry,
    petState: DesktopPetUiState,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    exposesSelection: Boolean = false,
    compact: Boolean = false
) {
    val switchChecked = petState.desiredEnabled || petState.overlayActive
    val (statusBadgeText, statusBadgeColor) = resolvePetHeaderBadge(
        defaultBadge = entry.badge,
        petState = petState,
        colors = LocalMaiDroidColors.current
    )
    val scalePercent = (normalizeDesktopPetScale(petState.petScale) * 100f).roundToInt()
    val summarySubtitle = if (switchChecked) {
        "${petState.statusText} · 大小 $scalePercent%"
    } else {
        entry.subtitle
    }
    val clickModifier = if (exposesSelection) {
        Modifier.selectable(
            selected = selected,
            onClick = onClick,
            role = Role.Tab
        )
    } else {
        Modifier.clickable(onClick = onClick)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corners.Chip))
            .background(CardSurface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (selected) BrandOrange.copy(alpha = 0.08f) else Color.Transparent)
                .then(clickModifier)
                .padding(horizontal = if (compact) 14.dp else 16.dp, vertical = if (compact) 12.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FlatFeatureIconBadge(
                icon = FlatFeatureIcons.DesktopPet,
                backgroundColor = IconPeachOrange,
                size = if (compact) 38.dp else 42.dp,
                iconSize = if (compact) 24.dp else 26.dp
            )

            Spacer(modifier = Modifier.width(if (compact) 10.dp else 13.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.title,
                        color = TextPrimary,
                        fontSize = if (compact) TextSizes.BodyLarge else TextSizes.Title,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    ExtensionTagPill(text = statusBadgeText, color = statusBadgeColor)
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = summarySubtitle,
                    color = TextMuted,
                    fontSize = TextSizes.Caption,
                    lineHeight = if (compact) 15.sp else 17.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (!compact) {
                Spacer(modifier = Modifier.width(10.dp))
                FlatSwitch(
                    checked = switchChecked,
                    onCheckedChange = onToggle
                )
            }

            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "进入悬浮桌宠设置",
                tint = if (selected) BrandOrange else TextMuted.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp)
            )
        }

        if (compact) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .height(0.5.dp)
                    .background(CardDivider)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (selected) BrandOrange.copy(alpha = 0.08f) else Color.Transparent)
                    .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "启用悬浮桌宠",
                    color = TextMuted,
                    fontSize = TextSizes.Caption,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                FlatSwitch(
                    checked = switchChecked,
                    onCheckedChange = onToggle
                )
            }
        }
    }
}

/** 通用全圆胶囊徽章：用于卡片标题旁的状态标识与规划中扩展标记。 */
@Composable
internal fun ExtensionTagPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        color = color,
        fontSize = TextSizes.Tag,
        fontWeight = FontWeight.Medium,
        lineHeight = 12.sp,
        maxLines = 1,
        softWrap = false,
        modifier = modifier
            .clip(Capsule())
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
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
                .clip(RoundedCornerShape(Corners.Control))
                .background(PageBackground)
        )
    }
}

/** 运行时摘要区：16dp 内嵌圆角面板呈现当前状态文本、悬浮权限引导胶囊以及双列清晰指标。 */
@Composable
internal fun PetRuntimeSummaryBox(
    petState: DesktopPetUiState,
    phaseColor: Color,
    webUiOnline: Boolean,
    overlayGranted: Boolean,
    compact: Boolean,
    onRequestPermission: () -> Unit,
    containerColor: Color = PageBackground.copy(alpha = 0.62f)
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corners.Panel))
            .background(containerColor)
            .padding(horizontal = 14.dp, vertical = if (compact) 10.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp)
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
                Row(
                    modifier = Modifier
                        .clip(Capsule())
                        .background(StatusWarning.copy(alpha = 0.16f))
                        .clickable(role = Role.Button, onClick = onRequestPermission)
                        .padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "去授权",
                        color = StatusWarning,
                        fontSize = TextSizes.Caption,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = StatusWarning,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PetStatusMetricItem(
                label = "WebUI 服务",
                value = if (webUiOnline) "在线" else "离线",
                valueColor = if (webUiOnline) StatusOnline else StatusWarning,
                dotColor = if (webUiOnline) StatusOnline else StatusWarning,
                modifier = Modifier.weight(1f)
            )

            Box(
                modifier = Modifier
                    .padding(horizontal = 10.dp)
                    .width(0.5.dp)
                    .height(22.dp)
                    .background(CardDivider)
            )

            PetStatusMetricItem(
                label = "前端资源",
                value = petState.assetSource.label,
                valueColor = if (petState.containerHasDesktopPet) IconCyanBlue else TextMuted,
                dotColor = if (petState.containerHasDesktopPet) IconCyanBlue else null,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun PetStatusMetricItem(
    label: String,
    value: String,
    valueColor: Color,
    dotColor: Color?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            color = TextFaint,
            fontSize = TextSizes.Tag,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (dotColor != null) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
            }
            Text(
                text = value,
                color = valueColor,
                fontSize = TextSizes.Caption,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 桌宠缩放比例控制面板：采用连续无顿挫液态玻璃滑块（每 2% 清晰刻度震感）与液态玻璃快捷档位，拖动时以桌宠脚底居中实时生效。 */
@Composable
internal fun PetScaleControlPanel(
    scale: Float,
    compact: Boolean,
    onScaleChange: (Float) -> Unit
) {
    val normalizedScale = normalizeDesktopPetScale(scale)
    val percent = (normalizedScale * 100f).roundToInt()
    val isDefault = abs(normalizedScale - DesktopPetScaleSpec.DEFAULT) < 0.005f
    val haptics = rememberLiquidHaptics()
    val cardBackdrop = rememberSurfaceBackdrop(CardSurface)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corners.Chip))
            .background(CardSurface)
            .padding(horizontal = if (compact) 14.dp else 16.dp, vertical = if (compact) 12.dp else 14.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "桌宠大小",
                    color = TextPrimary,
                    fontSize = TextSizes.BodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "拖动实时预览，自动保存",
                    color = TextMuted,
                    fontSize = TextSizes.Caption,
                    lineHeight = 16.sp,
                    maxLines = 2
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AnimatedVisibility(
                    visible = !isDefault,
                    enter = fadeIn() + scaleIn(initialScale = 0.85f),
                    exit = fadeOut() + scaleOut(targetScale = 0.85f)
                ) {
                    LiquidButton(
                        onClick = {
                            haptics.performTabStep()
                            onScaleChange(DesktopPetScaleSpec.DEFAULT)
                        },
                        backdrop = cardBackdrop,
                        style = LiquidButtonStyle.SoftAccent,
                        shape = { Capsule() },
                        minHeight = 30.dp,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.RestartAlt,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "重置",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
                ExtensionTagPill(
                    text = "$percent%",
                    color = if (isDefault) TextMuted else BrandOrange
                )
            }
        }

        LiquidSlider(
            value = { normalizedScale },
            onValueChange = onScaleChange,
            valueRange = DesktopPetScaleSpec.MIN..DesktopPetScaleSpec.MAX,
            hapticStep = DesktopPetScaleSpec.HAPTIC_STEP,
            backdrop = cardBackdrop,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DesktopPetScaleSpec.PRESETS.forEach { (presetScale, presetTitle) ->
                val selected = abs(normalizedScale - presetScale) < 0.005f
                val presetPercent = (presetScale * 100f).roundToInt()
                LiquidButton(
                    onClick = {
                        if (!selected) {
                            haptics.performTabStep()
                            onScaleChange(presetScale)
                        }
                    },
                    backdrop = cardBackdrop,
                    style = if (selected) LiquidButtonStyle.SoftAccent else LiquidButtonStyle.Secondary,
                    shape = { Capsule() },
                    minHeight = if (compact) 34.dp else 38.dp,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 7.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "$presetTitle $presetPercent%",
                        fontSize = TextSizes.Caption,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** 多实例机器人胶囊选择器：仅在宿主机托管了两个及以上机器人时展示。 */
@Composable
internal fun PetBotSelectorRow(
    bots: List<DesktopPetBot>,
    selectedBotId: String,
    onSelectBot: (String) -> Unit
) {
    val cardBackdrop = rememberSurfaceBackdrop(CardSurface)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                val displayName = bot.name.ifBlank { bot.botId.take(8) }
                LiquidButton(
                    onClick = { onSelectBot(bot.botId) },
                    backdrop = cardBackdrop,
                    style = if (selected) LiquidButtonStyle.SoftAccent else LiquidButtonStyle.Secondary,
                    shape = { Capsule() },
                    minHeight = 34.dp,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (selected) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(BrandOrange)
                            )
                        }
                        Text(
                            text = displayName,
                            fontSize = TextSizes.Caption,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/** 根据当前连接/配置/错误阶段按需展示对应内联引导卡片。 */
@Composable
internal fun PetActionPromptBanner(
    petState: DesktopPetUiState,
    webUiOnline: Boolean,
    onOpenLogin: () -> Unit,
    onEnableRemoteMode: () -> Unit,
    onReconnect: () -> Unit
) {
    when {
        petState.phase == DesktopPetPhase.AuthRequired -> {
            PhaseCalloutBox(
                icon = Icons.Outlined.Lock,
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
                icon = Icons.Outlined.Tune,
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
                icon = Icons.Outlined.ErrorOutline,
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
                icon = Icons.Outlined.Info,
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
    icon: ImageVector,
    title: String,
    body: String,
    accentColor: Color,
    actionLabel: String?,
    actionEnabled: Boolean,
    onAction: () -> Unit
) {
    val panelShape = RoundedCornerShape(Corners.Panel)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(panelShape)
            .background(accentColor.copy(alpha = 0.12f))
            .border(BorderStroke(1.dp, accentColor.copy(alpha = 0.30f)), panelShape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = title,
                color = accentColor,
                fontSize = TextSizes.Body,
                fontWeight = FontWeight.SemiBold
            )
        }
        Text(
            text = body,
            color = TextPrimary.copy(alpha = 0.90f),
            fontSize = TextSizes.Caption,
            lineHeight = 18.sp
        )
        if (!actionLabel.isNullOrBlank()) {
            LiquidButton(
                onClick = onAction,
                enabled = actionEnabled,
                tint = accentColor,
                contentColor = OnBrandOrange,
                shape = { Capsule() },
                minHeight = 38.dp,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = actionLabel,
                    fontSize = TextSizes.Caption,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
internal fun CompactActionButton(
    icon: ImageVector,
    label: String,
    style: LiquidButtonStyle,
    enabled: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    LiquidButton(
        onClick = onClick,
        enabled = enabled,
        style = style,
        shape = { Capsule() },
        minHeight = if (compact) 36.dp else 38.dp,
        contentPadding = PaddingValues(horizontal = if (compact) 6.dp else 8.dp, vertical = 7.dp),
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(if (compact) 14.dp else 15.dp)
            )
            Text(
                text = label,
                fontSize = if (compact) 11.sp else TextSizes.Caption,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

internal fun resolvePetHeaderBadge(
    defaultBadge: String,
    petState: DesktopPetUiState,
    colors: MaiDroidColors = DarkMaiDroidColors
): Pair<String, Color> = when {
    petState.overlayActive && petState.connected && !petState.overlayVisible -> "已隐藏" to BrandOrange
    petState.overlayActive && petState.connected -> "运行中" to colors.statusOnline
    petState.phase == DesktopPetPhase.Connecting -> "连接中" to BrandOrange
    petState.phase == DesktopPetPhase.AuthRequired -> "待登录" to colors.statusWarning
    petState.phase == DesktopPetPhase.ConfigRequired -> "待配置" to colors.statusWarning
    petState.phase == DesktopPetPhase.Error -> "异常" to colors.statusDanger
    else -> defaultBadge to colors.textMuted
}

internal fun resolvePetPhaseColor(
    petState: DesktopPetUiState,
    colors: MaiDroidColors = DarkMaiDroidColors
): Color = when (petState.phase) {
    DesktopPetPhase.Running -> if (petState.connected) colors.statusOnline else BrandOrange
    DesktopPetPhase.Connecting -> BrandOrange
    DesktopPetPhase.AuthRequired, DesktopPetPhase.ConfigRequired -> colors.statusWarning
    DesktopPetPhase.Error -> colors.statusDanger
    DesktopPetPhase.Idle -> colors.textMuted
}
