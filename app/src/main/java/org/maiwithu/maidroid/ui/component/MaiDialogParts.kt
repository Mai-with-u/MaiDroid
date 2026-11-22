package org.maiwithu.maidroid.ui.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measured
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ParentDataModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardBorder
import org.maiwithu.maidroid.ui.theme.OnBrandOrange
import org.maiwithu.maidroid.ui.theme.StatusDanger
import org.maiwithu.maidroid.ui.theme.TextFaint
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes
import kotlin.math.roundToInt

/**
 * 弹窗内部动作按钮与自适应按钮行积木：从 [MaiDialog] 拆出以便窗口外壳与内部控件各自精简聚焦。
 */

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
