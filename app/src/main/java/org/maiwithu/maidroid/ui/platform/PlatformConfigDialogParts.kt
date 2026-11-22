package org.maiwithu.maidroid.ui.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.maiwithu.maidroid.ui.component.FlatFeatureIconBadge
import org.maiwithu.maidroid.ui.component.FlatFeatureIcons
import org.maiwithu.maidroid.ui.component.LocalMaiDialogPhoneLandscape
import org.maiwithu.maidroid.ui.component.MaiDialog
import org.maiwithu.maidroid.ui.component.MaiDialogSize
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardBorder
import org.maiwithu.maidroid.ui.theme.CardDivider
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.IconSkyBlue
import org.maiwithu.maidroid.ui.theme.PageBackground
import org.maiwithu.maidroid.ui.theme.StatusDanger
import org.maiwithu.maidroid.ui.theme.StatusOnline
import org.maiwithu.maidroid.ui.theme.StatusWarning
import org.maiwithu.maidroid.ui.theme.TextFaint
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes

// 消息平台各配置弹窗共用的扁平外壳与结构化小部件。

private val SectionInnerSurface = PageBackground.copy(alpha = 0.55f)

/**
 * 协议端配置详情的统一卡片弹窗壳：
 * 基于 [MaiDialog] 的 [MaiDialogSize.Expanded] 规范构建，竖屏单列、手机横屏及平板自动分列；
 * 顶栏右侧统一并列刷新与关闭圆形按钮，左下固定承接危险操作以免在滚动区被裁切。
 */
@Composable
internal fun PlatformConfigDialogShell(
    title: String,
    statusText: String,
    statusOnline: Boolean,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    secondaryContent: (@Composable ColumnScope.() -> Unit)? = null,
    footerStart: (@Composable RowScope.() -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val scope = rememberCoroutineScope()
    val refreshSpin = remember { Animatable(0f) }

    MaiDialog(
        title = title,
        icon = FlatFeatureIcons.Platforms,
        iconBackground = IconSkyBlue,
        size = MaiDialogSize.Expanded,
        headerBadge = {
            ShellStatusBadge(statusText = statusText, statusOnline = statusOnline)
        },
        headerActions = {
            val compactLandscape = LocalMaiDialogPhoneLandscape.current
            val btnSize = if (compactLandscape) 30.dp else 32.dp
            val iconSize = if (compactLandscape) 16.dp else 17.dp
            DialogHeaderIconButton(
                icon = Icons.Outlined.Refresh,
                contentDescription = "刷新",
                buttonSize = btnSize,
                iconSize = iconSize,
                iconModifier = Modifier.graphicsLayer { rotationZ = refreshSpin.value },
                onClick = {
                    scope.launch {
                        refreshSpin.snapTo(0f)
                        refreshSpin.animateTo(360f, animationSpec = tween(420))
                    }
                    onRefresh()
                }
            )
        },
        onDismissRequest = onDismiss,
        footerStart = footerStart,
        footer = footer,
        secondaryContent = secondaryContent,
        content = content
    )
}

@Composable
private fun ShellStatusBadge(statusText: String, statusOnline: Boolean) {
    val accent = if (statusOnline) StatusOnline else StatusWarning
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(Corners.Tag))
            .background(accent.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(accent)
        )
        Text(
            text = statusText,
            color = accent,
            fontSize = TextSizes.Caption,
            fontWeight = FontWeight.Bold,
            lineHeight = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DialogHeaderIconButton(
    icon: ImageVector,
    contentDescription: String,
    buttonSize: Dp,
    iconSize: Dp,
    onClick: () -> Unit,
    iconModifier: Modifier = Modifier
) {
    Box(
        modifier = Modifier
            .size(buttonSize)
            .clip(CircleShape)
            .background(CardBorder)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = TextPrimary,
            modifier = iconModifier.size(iconSize)
        )
    }
}

/** 协议端弹窗固定底栏左侧用的轻量危险文字操作（如“退出 QQ 登录”“重启 QQ”），始终可见不被滚动裁剪。 */
@Composable
internal fun ConfigFooterDangerButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        shape = RoundedCornerShape(Corners.Control),
        colors = ButtonDefaults.textButtonColors(
            contentColor = StatusDanger,
            disabledContentColor = TextFaint
        )
    ) {
        Text(
            text = text,
            fontSize = TextSizes.Caption,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 置顶快捷操作卡片与摘要信息卡共用的内嵌容器。 */
@Composable
internal fun ConfigCardGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corners.Chip))
            .background(SectionInnerSurface),
        content = content
    )
}

@Composable
internal fun ConfigGroupDivider(startPadding: Dp = 14.dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = startPadding, end = 14.dp)
            .height(0.5.dp)
            .background(CardDivider)
    )
}

/** 快捷操作行：左侧彩色扁平圆形图标 + 标题说明 + 右箭头。 */
@Composable
internal fun ConfigQuickActionRow(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FlatFeatureIconBadge(
            icon = icon,
            backgroundColor = iconColor,
            size = 34.dp,
            iconSize = 22.dp
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = TextPrimary,
                fontSize = TextSizes.Body,
                fontWeight = FontWeight.Medium,
                lineHeight = 18.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = TextMuted,
                fontSize = TextSizes.Caption,
                lineHeight = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = TextMuted.copy(alpha = 0.6f),
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * 紧凑左右键值行：左标签、右数值。
 * 当 [maskableSecret] 为 true 且值有效时，默认打码展示并提供明文切换与一键复制，避免 Token 长串裸露。
 */
@Composable
internal fun ConfigKeyValueRow(
    label: String,
    value: String,
    valueColor: Color = TextPrimary,
    maskableSecret: Boolean = false
) {
    val context = LocalContext.current
    val hasSecret = maskableSecret && value != "-" && value != "未设置" && value.isNotBlank()
    var revealed by rememberSaveable(value) { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = TextMuted,
            fontSize = TextSizes.Caption,
            fontWeight = FontWeight.Medium,
            lineHeight = 16.sp,
            modifier = Modifier.padding(end = 12.dp)
        )

        if (hasSecret) {
            Text(
                text = if (revealed) value else "••••••••••••",
                color = valueColor,
                fontSize = TextSizes.Caption,
                fontWeight = FontWeight.Medium,
                lineHeight = 16.sp,
                textAlign = TextAlign.End,
                maxLines = if (revealed) 3 else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(6.dp))
            SmallInlineIconButton(
                icon = if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                contentDescription = if (revealed) "隐藏 $label" else "显示 $label",
                onClick = { revealed = !revealed }
            )
            Spacer(modifier = Modifier.width(4.dp))
            SmallInlineIconButton(
                icon = Icons.Outlined.ContentCopy,
                contentDescription = "复制 $label",
                onClick = {
                    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    manager?.setPrimaryClip(ClipData.newPlainText(label, value))
                    Toast.makeText(context, "已复制 $label", Toast.LENGTH_SHORT).show()
                }
            )
        } else {
            Text(
                text = value,
                color = valueColor,
                fontSize = TextSizes.Caption,
                fontWeight = FontWeight.Medium,
                lineHeight = 16.sp,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SmallInlineIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(CardBorder)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = TextMuted,
            modifier = Modifier.size(14.dp)
        )
    }
}

/** 可折叠次级配置区块：默认折叠以保持首屏清爽，点击整行展开查看或编辑细节。 */
@Composable
internal fun ConfigFoldableSection(
    title: String,
    summary: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 170, easing = FastOutSlowInEasing),
        label = "ConfigFoldArrow"
    )

    // 仅由单级 AnimatedVisibility(expandVertically/shrinkVertically) 驱动高度变化，
    // 移除外层 animateContentSize 与慢弹簧，消除双弹簧串联带来的近 1 秒收起长尾。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corners.Chip))
            .background(SectionInnerSurface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = TextPrimary,
                fontSize = TextSizes.Body,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            if (!expanded && !summary.isNullOrBlank()) {
                Text(
                    text = summary,
                    color = TextMuted,
                    fontSize = TextSizes.Caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 140.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowDown,
                contentDescription = if (expanded) "收起 $title" else "展开 $title",
                tint = TextMuted,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { rotationZ = arrowRotation }
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(150)) + expandVertically(
                animationSpec = tween(durationMillis = 190, easing = FastOutSlowInEasing),
                expandFrom = Alignment.Top
            ),
            exit = fadeOut(tween(120)) + shrinkVertically(
                animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
                shrinkTowards = Alignment.Top
            )
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                ConfigGroupDivider()
                content()
            }
        }
    }
}

/** 低饱和柔和红危险动作按钮，与设置页“安全退出”视觉一致。 */
@Composable
internal fun ConfigSoftDangerButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(Corners.Chip),
        colors = ButtonDefaults.buttonColors(
            containerColor = StatusDanger.copy(alpha = 0.12f),
            contentColor = StatusDanger,
            disabledContainerColor = CardBorder,
            disabledContentColor = TextFaint
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
    ) {
        Text(
            text = text,
            fontSize = TextSizes.Body,
            fontWeight = FontWeight.Medium
        )
    }
}
