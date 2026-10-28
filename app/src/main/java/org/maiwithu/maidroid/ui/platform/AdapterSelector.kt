package org.maiwithu.maidroid.ui.platform

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maiwithu.maidroid.ui.theme.CardBorder
import org.maiwithu.maidroid.ui.theme.TextMuted

private val AdapterSelectorItemWidth = 72.dp

private val AdapterSelectorItemSpacing = 2.dp

private val AdapterSelectorPadding = 2.dp

/**
 * 选择器按选项数定宽：每格 72dp 正好放下 12sp 的“官方机器人”五个字不折行。
 * 三个选项时约 224dp，卡片最窄 272dp 减去内边距还剩 240dp，放得下。
 */
internal fun adapterSelectorWidth(adapterCount: Int): Dp {
    val count = adapterCount.coerceAtLeast(1)
    return AdapterSelectorItemWidth * count.toFloat() +
        AdapterSelectorItemSpacing * (count - 1).toFloat() +
        AdapterSelectorPadding * 2f
}

/** 胶囊形的接入方式选择器，选中项的底色块带弹性动画滑到对应位置。 */
@Composable
internal fun AdapterSelector(
    adapters: List<PlatformAdapter>,
    selectedAdapter: PlatformAdapter?,
    onAdapterSelected: (PlatformAdapter) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedIndex = adapters.indexOf(selectedAdapter).coerceAtLeast(0)
    val itemSpacing = AdapterSelectorItemSpacing
    val horizontalPadding = AdapterSelectorPadding

    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(21.dp))
            .background(CardBorder)
            .border(1.dp, CardBorder, RoundedCornerShape(21.dp))
    ) {
        val itemWidth = if (adapters.isNotEmpty()) {
            (maxWidth - horizontalPadding * 2f - itemSpacing * (adapters.size - 1).toFloat()) /
                adapters.size.toFloat()
        } else {
            0.dp
        }
        val selectedOffset by animateDpAsState(
            targetValue = horizontalPadding + (itemWidth + itemSpacing) * selectedIndex.toFloat(),
            animationSpec = spring(
                dampingRatio = 0.76f,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "AdapterSelectorOffset"
        )

        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset(selectedOffset.roundToPx(), 0) }
                .width(itemWidth)
                .height(38.dp)
                .clip(RoundedCornerShape(19.dp))
                .background(Color(0xFF3C3C3E))
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding, vertical = horizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(itemSpacing)
        ) {
            adapters.forEach { adapter ->
                val selected = adapter == selectedAdapter
                val textColor by animateColorAsState(
                    targetValue = if (selected) Color.White else TextMuted,
                    animationSpec = spring(
                        dampingRatio = 1f,
                        stiffness = Spring.StiffnessMediumLow
                    ),
                    label = "AdapterSelectorTextColor"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(19.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onAdapterSelected(adapter) }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    // 字号被系统放大时宁可截断也不折成两行，避免撑破 38dp 高的胶囊。
                    Text(
                        text = adapter.label,
                        color = textColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 14.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}
