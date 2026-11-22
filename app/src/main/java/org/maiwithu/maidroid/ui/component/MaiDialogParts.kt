package org.maiwithu.maidroid.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measured
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ParentDataModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardBorder
import org.maiwithu.maidroid.ui.theme.CardDivider
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.DialogInnerSurface
import org.maiwithu.maidroid.ui.theme.JetBrainsMono
import org.maiwithu.maidroid.ui.theme.OnBrandOrange
import org.maiwithu.maidroid.ui.theme.PageBackground
import org.maiwithu.maidroid.ui.theme.StatusDanger
import org.maiwithu.maidroid.ui.theme.StatusWarning
import org.maiwithu.maidroid.ui.theme.TextFaint
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes
import kotlin.math.roundToInt

/**
 * 弹窗内部通用积木：分隔线、分组卡片、提示卡、折叠面板、输入框、胶囊、动作按钮及自适应按钮行。
 * 从 [MaiDialog] 拆出以便窗口外壳治理与内部积木各自保持精简聚焦。
 */

/** 弹窗内部细分隔线。 */
@Composable
internal fun MaiDialogDivider(horizontalPadding: Dp = 14.dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding)
            .height(0.5.dp)
            .background(CardDivider)
    )
}

/** 弹窗内的暗色内嵌分组卡片，用于集中承载版本号、状态明细或表单项。 */
@Composable
internal fun MaiDialogSectionCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(14.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(8.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corners.Panel))
            .background(DialogInnerSurface)
            .border(0.8.dp, CardDivider, RoundedCornerShape(Corners.Panel))
            .padding(contentPadding),
        verticalArrangement = verticalArrangement,
        content = content
    )
}

/** 带语义色调微描边的提示卡：用于安全风险提示、前置注意或收集出错说明。 */
@Composable
internal fun MaiDialogCallout(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    accentColor: Color = StatusWarning,
    trailingHeader: (@Composable () -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null
) {
    val shape = RoundedCornerShape(Corners.Panel)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(accentColor.copy(alpha = 0.09f))
            .border(0.8.dp, accentColor.copy(alpha = 0.25f), shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                color = accentColor,
                fontSize = TextSizes.Body,
                fontWeight = FontWeight.Bold,
                lineHeight = 18.sp,
                modifier = Modifier.weight(1f)
            )
            trailingHeader?.invoke()
        }
        if (body.isNotBlank()) {
            Text(
                text = body,
                color = TextPrimary.copy(alpha = 0.88f),
                fontSize = TextSizes.Caption,
                lineHeight = 17.sp
            )
        }
        footer?.invoke(this)
    }
}

/** 单级动画折叠面板：将长诊断报告、说明详情等次要内容默认收起，保持弹窗首屏简洁。 */
@Composable
internal fun MaiDialogCollapsible(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by rememberSaveable(initiallyExpanded) { mutableStateOf(initiallyExpanded) }
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 170, easing = FastOutSlowInEasing),
        label = "MaiDialogCollapseArrow"
    )
    val shape = RoundedCornerShape(Corners.Panel)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(DialogInnerSurface)
            .border(0.8.dp, CardDivider, shape)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 11.dp),
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
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowDown,
                contentDescription = if (expanded) "收起 $title" else "展开 $title",
                tint = TextMuted,
                modifier = Modifier
                    .size(19.dp)
                    .graphicsLayer { rotationZ = arrowRotation }
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(150)) + expandVertically(tween(180, easing = FastOutSlowInEasing)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(160, easing = FastOutSlowInEasing))
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                MaiDialogDivider()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = content
                )
            }
        }
    }
}

/** 弹窗内统一规格的圆角输入框，支持错误态、占位提示与密码明文切换；横屏适当缩减垂直边距省出高度。 */
@Composable
internal fun MaiDialogTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    isPassword: Boolean = false,
    errorText: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default
) {
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var isFocused by remember { mutableStateOf(false) }
    val compactLandscape = LocalMaiDialogPhoneLandscape.current
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)

    // 软键盘弹出或切换焦点时：先等这一帧布局与 BasicTextField 内部滚入结算完，再把含顶部浮动标签与留白在内的整块输入框滚入视口；
    // 最后补一次顶部 44dp 保护带滚入，确保哪怕超矮视口放不下整框，浮动标签和输入光标也绝不被滚区顶边切半。
    LaunchedEffect(isFocused, imeBottomPx) {
        if (isFocused) {
            withFrameNanos { }
            bringIntoViewRequester.bringIntoView()
            val topGuardHeightPx = with(density) { 44.dp.toPx() }
            bringIntoViewRequester.bringIntoView(
                Rect(left = 0f, top = 0f, right = 1f, bottom = topGuardHeightPx)
            )
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .bringIntoViewRequester(bringIntoViewRequester)
                .padding(top = 2.dp)
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { isFocused = it.hasFocus }
                    .then(if (compactLandscape) Modifier.heightIn(min = 48.dp) else Modifier),
                singleLine = singleLine,
                isError = errorText != null,
                label = { Text(text = label) },
                placeholder = placeholder?.let { hint -> { Text(text = hint, color = TextFaint) } },
                visualTransformation = if (isPassword && !passwordVisible) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                trailingIcon = if (isPassword) {
                    {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (passwordVisible) "隐藏$label" else "显示$label",
                                tint = TextMuted,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                } else {
                    null
                },
                keyboardOptions = keyboardOptions,
                shape = RoundedCornerShape(Corners.Control),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = BrandOrange,
                    focusedBorderColor = BrandOrange,
                    unfocusedBorderColor = CardBorder,
                    errorBorderColor = StatusDanger,
                    focusedLabelColor = BrandOrange,
                    unfocusedLabelColor = TextMuted,
                    errorLabelColor = StatusDanger,
                    focusedContainerColor = PageBackground,
                    unfocusedContainerColor = PageBackground,
                    errorContainerColor = PageBackground
                )
            )
        }
        if (!errorText.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = errorText,
                color = StatusDanger,
                fontSize = TextSizes.Caption,
                lineHeight = 15.sp
            )
        }
    }
}

/** 弹窗内的小巧信息/错误码胶囊，可选单行等宽字体与点击复制响应。 */
@Composable
internal fun MaiDialogInfoChip(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = TextMuted,
    containerColor: Color = color.copy(alpha = 0.14f),
    monospace: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val clickMod = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Text(
        text = text,
        color = color,
        fontFamily = if (monospace) JetBrainsMono else null,
        fontSize = TextSizes.Caption,
        fontWeight = FontWeight.Bold,
        lineHeight = 15.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(RoundedCornerShape(Corners.Tag))
            .background(containerColor)
            .then(clickMod)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

/** 全应用弹窗通用的按钮视觉风格：按语义区分实色主操作、柔色调与危险操作。 */
internal enum class MaiDialogButtonStyle {
    Primary,
    Secondary,
    SoftAccent,
    Danger,
    SoftDanger
}

/**
 * 判定固定宽度按钮行是否需要从并排切换为垂直全宽堆叠，
 * 确保大字号或窄宽弹窗中较长动作文案绝不会挤成省略号。
 */
internal fun shouldStackDialogButtons(
    intrinsicWidthsPx: List<Int>,
    weights: List<Float>,
    spacingPx: Int,
    maxWidthPx: Int
): Boolean {
    if (intrinsicWidthsPx.size <= 1) return false
    if (maxWidthPx <= 0) return true
    val available = maxWidthPx - spacingPx.coerceAtLeast(0) * (intrinsicWidthsPx.size - 1)
    if (available <= 0) return true
    if (intrinsicWidthsPx.sum() > available) return true

    val positiveWeights = weights.map { if (it > 0f) it else 1f }
    val totalWeight = positiveWeights.sum().coerceAtLeast(1f)
    return intrinsicWidthsPx.indices.any { index ->
        val allocated = available * (positiveWeights[index] / totalWeight)
        allocated + 0.5f < intrinsicWidthsPx[index]
    }
}

/** 弹窗动作按钮：竖屏饱满等高，横屏底栏下按内容定宽且不小于 84dp、高 38dp，保持靠右紧凑利落。 */
@Composable
internal fun MaiDialogButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: MaiDialogButtonStyle = MaiDialogButtonStyle.Primary,
    enabled: Boolean = true
) {
    val compactBar = LocalMaiDialogLandscapeBar.current
    val (container, content) = when (style) {
        MaiDialogButtonStyle.Primary -> BrandOrange to OnBrandOrange
        MaiDialogButtonStyle.Secondary -> CardBorder to TextPrimary
        MaiDialogButtonStyle.SoftAccent -> BrandOrange.copy(alpha = 0.18f) to BrandOrange
        MaiDialogButtonStyle.Danger -> StatusDanger to TextPrimary
        MaiDialogButtonStyle.SoftDanger -> StatusDanger.copy(alpha = 0.14f) to StatusDanger
    }
    val minHeight = if (compactBar) 38.dp else 44.dp
    val cornerRadius = if (compactBar) 11.dp else 14.dp
    val contentPadding = if (compactBar) {
        PaddingValues(horizontal = 14.dp, vertical = 7.dp)
    } else {
        PaddingValues(horizontal = 14.dp, vertical = 10.dp)
    }
    val labelSize = if (compactBar) 13.sp else TextSizes.Body

    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .then(if (compactBar) Modifier.defaultMinSize(minWidth = 84.dp, minHeight = minHeight) else Modifier.heightIn(min = minHeight)),
        shape = RoundedCornerShape(cornerRadius),
        contentPadding = contentPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = CardBorder.copy(alpha = 0.55f),
            disabledContentColor = TextFaint
        )
    ) {
        Text(
            text = text,
            fontSize = labelSize,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private class DialogButtonWeightModifier(val weight: Float) : ModifierNodeElement<DialogButtonWeightNode>() {
    override fun create(): DialogButtonWeightNode = DialogButtonWeightNode(weight)

    override fun update(node: DialogButtonWeightNode) {
        node.weight = weight
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "dialogButtonWeight"
        value = weight
    }

    override fun equals(other: Any?): Boolean = other is DialogButtonWeightModifier && other.weight == weight

    override fun hashCode(): Int = weight.hashCode()
}

private class DialogButtonWeightNode(var weight: Float) : Modifier.Node(), ParentDataModifierNode {
    override fun Density.modifyParentData(parentData: Any?): Any = weight
}

private object MaiDialogButtonRowScope : RowScope {
    override fun Modifier.weight(weight: Float, fill: Boolean): Modifier =
        this.then(DialogButtonWeightModifier(weight.coerceAtLeast(0.01f)))

    override fun Modifier.align(alignment: Alignment.Vertical): Modifier = this
    override fun Modifier.alignBy(alignmentLine: HorizontalAlignmentLine): Modifier = this
    override fun Modifier.alignByBaseline(): Modifier = this
    override fun Modifier.alignBy(alignmentLineBlock: (Measured) -> Int): Modifier = this
}

/**
 * 动作按钮行：
 * - 在横屏单行底栏中自动紧排并靠右对齐（不拉伸占满整宽），避免抢占左侧状态/提示区空间；
 * - 在竖屏或平板底栏中按权重铺满整行，放不下固有宽度时自动转成全宽垂直堆叠防止省略号截断。
 */
@Composable
internal fun MaiDialogButtonRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    val compactBar = LocalMaiDialogLandscapeBar.current
    val spacingDp = if (compactBar) 8.dp else 10.dp
    val rowModifier = if (compactBar) modifier else modifier.fillMaxWidth()

    Layout(
        content = { MaiDialogButtonRowScope.content() },
        modifier = rowModifier
    ) { measurables, constraints ->
        if (measurables.isEmpty()) {
            return@Layout layout(0, 0) {}
        }
        val spacingPx = spacingDp.roundToPx()
        val maxW = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE

        if (compactBar) {
            val childMax = if (maxW == Int.MAX_VALUE) Constraints.Infinity else maxW
            val naturalPlaceables = measurables.map {
                it.measure(
                    Constraints(
                        minWidth = 0,
                        maxWidth = childMax,
                        minHeight = 0,
                        maxHeight = constraints.maxHeight
                    )
                )
            }
            val naturalTotal = naturalPlaceables.sumOf { it.width } + spacingPx * (naturalPlaceables.size - 1)
            if (naturalTotal <= maxW) {
                val rowHeight = naturalPlaceables.maxOfOrNull { it.height } ?: 0
                return@Layout layout(naturalTotal.coerceAtLeast(0), rowHeight) {
                    var x = 0
                    naturalPlaceables.forEach { placeable ->
                        val y = (rowHeight - placeable.height) / 2
                        placeable.placeRelative(x, y)
                        x += placeable.width + spacingPx
                    }
                }
            }
        }

        val boundedMaxW = if (constraints.hasBoundedWidth) constraints.maxWidth else 0
        val weights = measurables.map { (it.parentData as? Float) ?: 1f }
        val intrinsicWidths = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity) }
        val stackVertically = shouldStackDialogButtons(
            intrinsicWidthsPx = intrinsicWidths,
            weights = weights,
            spacingPx = spacingPx,
            maxWidthPx = boundedMaxW
        )

        if (stackVertically) {
            val itemConstraints = Constraints(
                minWidth = boundedMaxW.coerceAtLeast(0),
                maxWidth = boundedMaxW.coerceAtLeast(0),
                minHeight = 0,
                maxHeight = constraints.maxHeight
            )
            val placeables = measurables.map { it.measure(itemConstraints) }
            val totalH = placeables.sumOf { it.height } + spacingPx * (placeables.size - 1)
            val finalH = totalH.coerceAtMost(constraints.maxHeight)
            layout(boundedMaxW, finalH) {
                var y = 0
                placeables.forEach { placeable ->
                    placeable.placeRelative(0, y)
                    y += placeable.height + spacingPx
                }
            }
        } else {
            val availableW = (boundedMaxW - spacingPx * (measurables.size - 1)).coerceAtLeast(0)
            val totalWeight = weights.sum().coerceAtLeast(1f)
            var remainingW = availableW
            val placeables = measurables.mapIndexed { index, measurable ->
                val itemW = if (index == measurables.lastIndex) {
                    remainingW
                } else {
                    (availableW * (weights[index] / totalWeight)).roundToInt().coerceIn(0, remainingW)
                }
                remainingW -= itemW
                measurable.measure(
                    Constraints(
                        minWidth = itemW,
                        maxWidth = itemW,
                        minHeight = 0,
                        maxHeight = constraints.maxHeight
                    )
                )
            }
            val rowHeight = placeables.maxOfOrNull { it.height } ?: 0
            layout(boundedMaxW, rowHeight) {
                var x = 0
                placeables.forEach { placeable ->
                    val y = (rowHeight - placeable.height) / 2
                    placeable.placeRelative(x, y)
                    x += placeable.width + spacingPx
                }
            }
        }
    }
}
