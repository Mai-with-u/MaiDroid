package org.maiwithu.maidroid.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardDivider
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.DialogInnerSurface
import org.maiwithu.maidroid.ui.theme.IconCyanBlue
import org.maiwithu.maidroid.ui.theme.IconIrisBlue
import org.maiwithu.maidroid.ui.theme.TextFaint
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes
import org.maiwithu.maidroid.webui.WebUiChoiceMode
import org.maiwithu.maidroid.webui.WebUiChoiceRow
import org.maiwithu.maidroid.webui.WebUiPromptRequest
import org.maiwithu.maidroid.webui.filterChoiceRows
import org.maiwithu.maidroid.webui.normalizeHexColor
import org.maiwithu.maidroid.webui.selectedChoiceIdsInOrder
import android.graphics.Color as AndroidColor

private const val CHOICE_SEARCH_THRESHOLD = 8
private const val COLOR_SWATCH_COLUMNS = 4
private val ChoiceBadgeForeground = TextPrimary
private val ChoiceBadgeSecondary = TextPrimary.copy(alpha = 0.56f)

/**
 * 网页原生表单弹窗统一 Compose 宿主：
 * 将 GeckoView 的 `<select>`、`alert`、`confirm`、`prompt` 与颜色选择器统一渲染为居中液态玻璃 `MaiDialog` 卡片，
 * 与全应用视觉、触觉和自适应布局保持一致。
 */
@Composable
internal fun WebUiPromptDialogHost(prompt: WebUiPromptRequest?) {
    if (prompt == null) return
    key(prompt.id) {
        when (prompt) {
            is WebUiPromptRequest.Choice -> WebUiChoicePromptDialog(prompt)
            is WebUiPromptRequest.Alert -> WebUiAlertPromptDialog(prompt)
            is WebUiPromptRequest.Confirm -> WebUiConfirmPromptDialog(prompt)
            is WebUiPromptRequest.TextInput -> WebUiTextInputPromptDialog(prompt)
            is WebUiPromptRequest.ColorPicker -> WebUiColorPickerPromptDialog(prompt)
        }
    }
}

@Composable
private fun WebUiChoicePromptDialog(prompt: WebUiPromptRequest.Choice) {
    val isMultiple = prompt.mode == WebUiChoiceMode.Multiple
    val haptics = rememberLiquidHaptics()
    var selectedIds by remember(prompt.id) { mutableStateOf(prompt.initialSelectedIds.toSet()) }
    var searchQuery by remember(prompt.id) { mutableStateOf("") }

    val totalOptions = remember(prompt.rows) { prompt.rows.count { it is WebUiChoiceRow.Option } }
    val showSearch = totalOptions >= CHOICE_SEARCH_THRESHOLD
    val displayRows = remember(prompt.rows, searchQuery, showSearch) {
        if (showSearch) filterChoiceRows(prompt.rows, searchQuery) else prompt.rows
    }
    val initialSelectedId = remember(prompt.rows) {
        (prompt.rows.firstOrNull { it is WebUiChoiceRow.Option && it.selected } as? WebUiChoiceRow.Option)?.id
    }
    val selectedScrollTarget = remember(prompt.id) { BringIntoViewRequester() }

    // 弹窗首帧排版后自动把原本已选中的项滚入可视范围，避免长下拉列表打开时还要手动翻找当前选项。
    LaunchedEffect(prompt.id, initialSelectedId) {
        if (initialSelectedId != null) {
            withFrameNanos { }
            runCatching { selectedScrollTarget.bringIntoView() }
        }
    }

    val titleText = prompt.title.ifBlank {
        when (prompt.mode) {
            WebUiChoiceMode.Multiple -> "选择多项"
            WebUiChoiceMode.Menu -> "选择操作"
            WebUiChoiceMode.Single -> "请选择"
        }
    }

    MaiDialog(
        title = titleText,
        icon = if (isMultiple) MultiChoiceBadgeIcon else SingleChoiceBadgeIcon,
        iconBackground = BrandOrange,
        size = MaiDialogSize.Medium,
        headerBadge = if (isMultiple) {
            {
                MaiDialogInfoChip(
                    text = "已选 ${selectedIds.size} 项",
                    color = if (selectedIds.isEmpty()) TextMuted else BrandOrange
                )
            }
        } else {
            null
        },
        onDismissRequest = prompt.onDismiss,
        landscapeFooter = {
            MaiDialogButtonRow {
                MaiDialogButton(
                    text = "取消",
                    style = MaiDialogButtonStyle.Secondary,
                    onClick = prompt.onDismiss
                )
                if (isMultiple) {
                    MaiDialogButton(
                        text = "确定",
                        style = MaiDialogButtonStyle.Primary,
                        onClick = {
                            prompt.onConfirmMultiple(selectedChoiceIdsInOrder(prompt.rows, selectedIds))
                        }
                    )
                }
            }
        },
        footer = {
            if (isMultiple) {
                MaiDialogButtonRow {
                    MaiDialogButton(
                        text = "取消",
                        style = MaiDialogButtonStyle.Secondary,
                        modifier = Modifier.weight(1f),
                        onClick = prompt.onDismiss
                    )
                    MaiDialogButton(
                        text = "确定",
                        style = MaiDialogButtonStyle.Primary,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            prompt.onConfirmMultiple(selectedChoiceIdsInOrder(prompt.rows, selectedIds))
                        }
                    )
                }
            } else {
                MaiDialogButton(
                    text = "取消",
                    style = MaiDialogButtonStyle.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = prompt.onDismiss
                )
            }
        }
    ) {
        if (prompt.message.isNotBlank()) {
            Text(
                text = prompt.message,
                color = TextMuted,
                fontSize = TextSizes.Body,
                lineHeight = 19.sp
            )
        }

        if (showSearch) {
            MaiDialogTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = "筛选选项",
                placeholder = "输入关键字快速定位"
            )
        }

        MaiDialogSectionCard(
            contentPadding = PaddingValues(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (displayRows.none { it is WebUiChoiceRow.Option }) {
                Text(
                    text = "没有匹配的选项",
                    color = TextMuted,
                    fontSize = TextSizes.Body,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp)
                )
            } else {
                displayRows.forEach { row ->
                    when (row) {
                        is WebUiChoiceRow.Separator -> {
                            MaiDialogDivider(horizontalPadding = 8.dp)
                        }

                        is WebUiChoiceRow.GroupHeader -> {
                            Text(
                                text = row.label,
                                color = if (row.disabled) TextFaint else TextMuted,
                                fontSize = TextSizes.Caption,
                                fontWeight = FontWeight.Bold,
                                lineHeight = 16.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .alpha(if (row.disabled) 0.5f else 1f)
                                    .padding(
                                        start = 12.dp + (row.depth * 14).dp,
                                        end = 12.dp,
                                        top = 8.dp,
                                        bottom = 2.dp
                                    )
                            )
                        }

                        is WebUiChoiceRow.Option -> {
                            val selected = row.id in selectedIds
                            val rowModifier = if (row.id == initialSelectedId) {
                                Modifier.bringIntoViewRequester(selectedScrollTarget)
                            } else {
                                Modifier
                            }
                            ChoiceOptionItem(
                                row = row,
                                mode = prompt.mode,
                                selected = selected,
                                modifier = rowModifier,
                                onClick = {
                                    haptics.performTabStep()
                                    if (isMultiple) {
                                        selectedIds = if (row.id in selectedIds) {
                                            selectedIds - row.id
                                        } else {
                                            selectedIds + row.id
                                        }
                                    } else {
                                        // 单选与菜单点选后立即反馈并结算单字符串结果，保持原生下拉的干脆交互。
                                        selectedIds = setOf(row.id)
                                        prompt.onConfirmSingle(row.id)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChoiceOptionItem(
    row: WebUiChoiceRow.Option,
    mode: WebUiChoiceMode,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val itemShape = RoundedCornerShape(Corners.Control)
    val bgColor by animateColorAsState(
        targetValue = if (selected) BrandOrange.copy(alpha = 0.16f) else Color.Transparent,
        animationSpec = tween(durationMillis = 140),
        label = "ChoiceOptionBackground"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) BrandOrange.copy(alpha = 0.55f) else Color.Transparent,
        animationSpec = tween(durationMillis = 140),
        label = "ChoiceOptionBorder"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(itemShape)
            .background(bgColor)
            .then(
                if (selected) Modifier.border(1.dp, borderColor, itemShape) else Modifier
            )
            .alpha(if (row.disabled) 0.42f else 1f)
            .clickable(
                enabled = !row.disabled,
                role = if (mode == WebUiChoiceMode.Multiple) Role.Checkbox else Role.RadioButton,
                onClick = onClick
            )
            .padding(
                start = 12.dp + (row.depth * 14).dp,
                end = 12.dp,
                top = 12.dp,
                bottom = 12.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = row.label,
            color = if (row.disabled) TextFaint else TextPrimary,
            fontSize = TextSizes.BodyLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            lineHeight = 20.sp,
            modifier = Modifier.weight(1f)
        )

        if (mode != WebUiChoiceMode.Menu) {
            Spacer(modifier = Modifier.width(12.dp))
            val indicatorShape = if (mode == WebUiChoiceMode.Multiple) {
                RoundedCornerShape(Corners.Tag)
            } else {
                CircleShape
            }
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(indicatorShape)
                    .then(
                        if (selected) {
                            Modifier.background(BrandOrange)
                        } else {
                            Modifier.border(1.6.dp, TextMuted.copy(alpha = 0.55f), indicatorShape)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = TextPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun WebUiAlertPromptDialog(prompt: WebUiPromptRequest.Alert) {
    MaiDialog(
        title = prompt.title.ifBlank { "页面提示" },
        icon = FlatFeatureIcons.WebUi,
        iconBackground = BrandOrange,
        size = MaiDialogSize.Compact,
        onDismissRequest = prompt.onDismiss,
        landscapeFooter = {
            MaiDialogButton(
                text = "确定",
                style = MaiDialogButtonStyle.Primary,
                onClick = prompt.onDismiss
            )
        },
        footer = {
            MaiDialogButton(
                text = "确定",
                style = MaiDialogButtonStyle.Primary,
                modifier = Modifier.fillMaxWidth(),
                onClick = prompt.onDismiss
            )
        }
    ) {
        if (prompt.message.isNotBlank()) {
            Text(
                text = prompt.message,
                color = TextPrimary,
                fontSize = TextSizes.Body,
                lineHeight = 20.sp
            )
        }
    }
}

@Composable
private fun WebUiConfirmPromptDialog(prompt: WebUiPromptRequest.Confirm) {
    MaiDialog(
        title = prompt.title.ifBlank { "确认操作" },
        icon = FlatFeatureIcons.WebUi,
        iconBackground = BrandOrange,
        size = MaiDialogSize.Compact,
        onDismissRequest = prompt.onDismiss,
        landscapeFooter = {
            MaiDialogButtonRow {
                MaiDialogButton(
                    text = "取消",
                    style = MaiDialogButtonStyle.Secondary,
                    onClick = prompt.onCancel
                )
                MaiDialogButton(
                    text = "确定",
                    style = MaiDialogButtonStyle.Primary,
                    onClick = prompt.onConfirm
                )
            }
        },
        footer = {
            MaiDialogButtonRow {
                MaiDialogButton(
                    text = "取消",
                    style = MaiDialogButtonStyle.Secondary,
                    modifier = Modifier.weight(1f),
                    onClick = prompt.onCancel
                )
                MaiDialogButton(
                    text = "确定",
                    style = MaiDialogButtonStyle.Primary,
                    modifier = Modifier.weight(1f),
                    onClick = prompt.onConfirm
                )
            }
        }
    ) {
        if (prompt.message.isNotBlank()) {
            Text(
                text = prompt.message,
                color = TextPrimary,
                fontSize = TextSizes.Body,
                lineHeight = 20.sp
            )
        }
    }
}

@Composable
private fun WebUiTextInputPromptDialog(prompt: WebUiPromptRequest.TextInput) {
    var inputText by remember(prompt.id) { mutableStateOf(prompt.defaultValue) }
    MaiDialog(
        title = prompt.title.ifBlank { "输入内容" },
        icon = FlatFeatureIcons.WebUi,
        iconBackground = IconCyanBlue,
        size = MaiDialogSize.Compact,
        onDismissRequest = prompt.onDismiss,
        landscapeFooter = {
            MaiDialogButtonRow {
                MaiDialogButton(
                    text = "取消",
                    style = MaiDialogButtonStyle.Secondary,
                    onClick = prompt.onDismiss
                )
                if (prompt.allowClear) {
                    MaiDialogButton(
                        text = "清除",
                        style = MaiDialogButtonStyle.SoftAccent,
                        onClick = { prompt.onConfirm("") }
                    )
                }
                MaiDialogButton(
                    text = "确定",
                    style = MaiDialogButtonStyle.Primary,
                    onClick = { prompt.onConfirm(inputText) }
                )
            }
        },
        footer = {
            MaiDialogButtonRow {
                MaiDialogButton(
                    text = "取消",
                    style = MaiDialogButtonStyle.Secondary,
                    modifier = Modifier.weight(1f),
                    onClick = prompt.onDismiss
                )
                if (prompt.allowClear) {
                    MaiDialogButton(
                        text = "清除",
                        style = MaiDialogButtonStyle.SoftAccent,
                        modifier = Modifier.weight(1f),
                        onClick = { prompt.onConfirm("") }
                    )
                }
                MaiDialogButton(
                    text = "确定",
                    style = MaiDialogButtonStyle.Primary,
                    modifier = Modifier.weight(1f),
                    onClick = { prompt.onConfirm(inputText) }
                )
            }
        }
    ) {
        if (prompt.message.isNotBlank()) {
            Text(
                text = prompt.message,
                color = TextMuted,
                fontSize = TextSizes.Body,
                lineHeight = 19.sp
            )
        }
        MaiDialogTextField(
            value = inputText,
            onValueChange = { inputText = it },
            label = prompt.title.ifBlank { "输入内容" },
            placeholder = prompt.placeholder.ifBlank { null }
        )
    }
}

@Composable
private fun WebUiColorPickerPromptDialog(prompt: WebUiPromptRequest.ColorPicker) {
    val haptics = rememberLiquidHaptics()
    var hexInput by remember(prompt.id) { mutableStateOf(prompt.initialHex) }
    var lastValidHex by remember(prompt.id) { mutableStateOf(prompt.initialHex) }

    val normalized = normalizeHexColor(hexInput)
    if (normalized != null && normalized != lastValidHex) {
        lastValidHex = normalized
    }
    val previewHex = normalized ?: lastValidHex
    val previewColor = remember(previewHex) {
        runCatching { Color(AndroidColor.parseColor(previewHex)) }.getOrDefault(BrandOrange)
    }
    val invalidInputError = if (hexInput.isNotBlank() && normalized == null) {
        "请输入 #rrggbb 或 #rgb 格式"
    } else {
        null
    }

    MaiDialog(
        title = prompt.title.ifBlank { "选择颜色" },
        icon = ColorPickerBadgeIcon,
        iconBackground = IconIrisBlue,
        size = MaiDialogSize.Medium,
        onDismissRequest = prompt.onDismiss,
        landscapeFooter = {
            MaiDialogButtonRow {
                MaiDialogButton(
                    text = "取消",
                    style = MaiDialogButtonStyle.Secondary,
                    onClick = prompt.onDismiss
                )
                MaiDialogButton(
                    text = "确定",
                    style = MaiDialogButtonStyle.Primary,
                    onClick = { prompt.onConfirm(previewHex) }
                )
            }
        },
        footer = {
            MaiDialogButtonRow {
                MaiDialogButton(
                    text = "取消",
                    style = MaiDialogButtonStyle.Secondary,
                    modifier = Modifier.weight(1f),
                    onClick = prompt.onDismiss
                )
                MaiDialogButton(
                    text = "确定",
                    style = MaiDialogButtonStyle.Primary,
                    modifier = Modifier.weight(1f),
                    onClick = { prompt.onConfirm(previewHex) }
                )
            }
        }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val previewShape = RoundedCornerShape(Corners.Control)
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(previewShape)
                    .background(previewColor)
                    .border(1.dp, TextPrimary.copy(alpha = 0.28f), previewShape)
            )
            MaiDialogTextField(
                value = hexInput,
                onValueChange = { hexInput = it },
                label = "颜色值",
                placeholder = "#rrggbb",
                errorText = invalidInputError,
                modifier = Modifier.weight(1f)
            )
        }

        MaiDialogSectionCard(
            contentPadding = PaddingValues(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val swatchShape = RoundedCornerShape(Corners.Control)
            prompt.paletteHexes.chunked(COLOR_SWATCH_COLUMNS).forEach { rowColors ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (columnIndex in 0 until COLOR_SWATCH_COLUMNS) {
                        val swatchHex = rowColors.getOrNull(columnIndex)
                        if (swatchHex != null) {
                            val selected = swatchHex.equals(previewHex, ignoreCase = true)
                            val swatchColor = remember(swatchHex) {
                                runCatching { Color(AndroidColor.parseColor(swatchHex)) }
                                    .getOrDefault(DialogInnerSurface)
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                                    .clip(swatchShape)
                                    .then(
                                        if (selected) {
                                            Modifier.border(2.dp, BrandOrange, swatchShape)
                                        } else {
                                            Modifier.border(1.dp, CardDivider, swatchShape)
                                        }
                                    )
                                    .padding(if (selected) 2.dp else 0.dp)
                                    .clip(swatchShape)
                                    .background(swatchColor)
                                    .then(
                                        if (selected) {
                                            Modifier.border(1.2.dp, TextPrimary.copy(alpha = 0.85f), swatchShape)
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .clickable {
                                        haptics.performTabStep()
                                        hexInput = swatchHex
                                        lastValidHex = swatchHex
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (selected) {
                                    Text(
                                        text = "✓",
                                        color = if (luminanceOf(swatchColor) > 0.6f) Color.Black else TextPrimary,
                                        fontSize = TextSizes.Caption,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Clip
                                    )
                                }
                            }
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

private fun luminanceOf(color: Color): Float =
    0.299f * color.red + 0.587f * color.green + 0.114f * color.blue

// FlatFeatureIconBadge 使用 tint = Color.Unspecified 绘制双层矢量，因此与全应用一致使用半透明柔白+纯白图层。
private val SingleChoiceBadgeIcon: ImageVector by lazy {
    duotoneBadgeVector("WebUiSingleChoice") {
        badgeLayer(ChoiceBadgeSecondary) {
            roundedBadgeRect(3.2f, 4.2f, 20.8f, 19.8f, 3.6f)
        }
        badgeLayer(ChoiceBadgeForeground) {
            badgeCircle(7.0f, 8.8f, 1.3f)
            roundedBadgeRect(9.8f, 7.8f, 17.8f, 9.8f, 1.0f)
            badgeCircle(7.0f, 15.2f, 1.3f)
            roundedBadgeRect(9.8f, 14.2f, 17.8f, 16.2f, 1.0f)
        }
    }
}

private val MultiChoiceBadgeIcon: ImageVector by lazy {
    duotoneBadgeVector("WebUiMultiChoice") {
        badgeLayer(ChoiceBadgeSecondary) {
            roundedBadgeRect(3.2f, 4.2f, 20.8f, 19.8f, 3.6f)
        }
        badgeLayer(ChoiceBadgeForeground) {
            roundedBadgeRect(6.0f, 7.4f, 8.8f, 10.2f, 0.8f)
            roundedBadgeRect(10.2f, 7.8f, 18.0f, 9.8f, 1.0f)
            roundedBadgeRect(6.0f, 13.8f, 8.8f, 16.6f, 0.8f)
            roundedBadgeRect(10.2f, 14.2f, 18.0f, 16.2f, 1.0f)
        }
    }
}

private val ColorPickerBadgeIcon: ImageVector by lazy {
    duotoneBadgeVector("WebUiColorPicker") {
        badgeLayer(ChoiceBadgeSecondary) {
            badgeCircle(12.0f, 12.0f, 8.8f)
        }
        badgeLayer(ChoiceBadgeForeground) {
            badgeCircle(8.8f, 9.2f, 1.6f)
            badgeCircle(14.8f, 9.2f, 1.6f)
            badgeCircle(8.8f, 14.8f, 1.6f)
            badgeCircle(14.8f, 14.8f, 1.6f)
        }
    }
}

private fun duotoneBadgeVector(
    name: String,
    block: ImageVector.Builder.() -> Unit
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).apply(block).build()

private fun ImageVector.Builder.badgeLayer(
    color: Color,
    block: PathBuilder.() -> Unit
) {
    path(
        fill = SolidColor(color),
        pathFillType = PathFillType.NonZero,
        pathBuilder = block
    )
}

private fun PathBuilder.roundedBadgeRect(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    radius: Float
) {
    val r = radius.coerceAtMost(minOf((right - left) / 2f, (bottom - top) / 2f))
    moveTo(left + r, top)
    lineTo(right - r, top)
    quadTo(right, top, right, top + r)
    lineTo(right, bottom - r)
    quadTo(right, bottom, right - r, bottom)
    lineTo(left + r, bottom)
    quadTo(left, bottom, left, bottom - r)
    lineTo(left, top + r)
    quadTo(left, top, left + r, top)
    close()
}

private fun PathBuilder.badgeCircle(cx: Float, cy: Float, radius: Float) {
    moveTo(cx + radius, cy)
    arcTo(radius, radius, 0f, isMoreThanHalf = true, isPositiveArc = false, cx - radius, cy)
    arcTo(radius, radius, 0f, isMoreThanHalf = true, isPositiveArc = false, cx + radius, cy)
    close()
}
