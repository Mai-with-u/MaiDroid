package org.maiwithu.maidroid.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.maiwithu.maidroid.backup.formatNextScheduleHint
import org.maiwithu.maidroid.backup.formatScheduleTime
import org.maiwithu.maidroid.backup.nextScheduledBackupMillis
import org.maiwithu.maidroid.ui.component.LocalMaiDialogPhoneLandscape
import org.maiwithu.maidroid.ui.component.MaiDialog
import org.maiwithu.maidroid.ui.component.MaiDialogButton
import org.maiwithu.maidroid.ui.component.MaiDialogButtonRow
import org.maiwithu.maidroid.ui.component.MaiDialogButtonStyle
import org.maiwithu.maidroid.ui.component.MaiDialogSize
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardBorder
import org.maiwithu.maidroid.ui.theme.CardDivider
import org.maiwithu.maidroid.ui.theme.CardSurfaceSelected
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.DialogInnerSurface
import org.maiwithu.maidroid.ui.theme.TextFaint
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

private val PICKER_PRESET_TIMES = listOf(2 to 0, 4 to 0, 6 to 0, 23 to 0)

/** 计算贴近无限列表中央、且恰好对应数值 0 的基准下标，使循环滚轮双向滑动都不会触顶或触底。 */
internal fun wheelLoopBaseIndex(valueCount: Int): Int {
    require(valueCount > 0) { "valueCount must be positive" }
    val half = Int.MAX_VALUE / 2
    return half - (half % valueCount)
}

/** 将循环滚轮上的列表下标折叠回 `[0, valueCount)` 区间内的实际数值。 */
internal fun wheelIndexToValue(index: Int, valueCount: Int): Int {
    require(valueCount > 0) { "valueCount must be positive" }
    return ((index % valueCount) + valueCount) % valueCount
}

/** 在循环滚轮中找到距离当前下标步数最短的目标下标（例如从 23 切到 0 时向前走 1 格而非倒退 23 格）。 */
internal fun nearestWheelTargetIndex(currentIndex: Int, targetValue: Int, valueCount: Int): Int {
    require(valueCount > 0) { "valueCount must be positive" }
    val normalizedTarget = wheelIndexToValue(targetValue, valueCount)
    val currentValue = wheelIndexToValue(currentIndex, valueCount)
    var delta = normalizedTarget - currentValue
    val half = valueCount / 2
    if (delta > half) {
        delta -= valueCount
    } else if (delta < -half) {
        delta += valueCount
    }
    val candidate = currentIndex.toLong() + delta.toLong()
    return candidate.coerceIn(0L, (Int.MAX_VALUE - 1).toLong()).toInt()
}

/** 根据小时返回时段说明文字，帮助用户避开聊天活跃时段挑选备份时刻。 */
internal fun backupTimePeriodLabel(hour: Int): String = when (hour.coerceIn(0, 23)) {
    in 0..5 -> "凌晨低峰期"
    in 6..11 -> "上午"
    in 12..17 -> "下午"
    else -> "夜间"
}

/**
 * 液态玻璃风格的 24 小时制每日备份时间选择器：
 * 采用双列循环吸附滚轮、实时时段与下次触发预览以及常用快捷时段胶囊，取代原生系统时间弹窗。
 */
@Composable
internal fun BackupTimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    onConfirm: (hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit
) {
    val safeInitialHour = initialHour.coerceIn(0, 23)
    val safeInitialMinute = initialMinute.coerceIn(0, 59)
    val hourBase = remember { wheelLoopBaseIndex(24) }
    val minuteBase = remember { wheelLoopBaseIndex(60) }

    val hourListState = rememberLazyListState(initialFirstVisibleItemIndex = hourBase + safeInitialHour)
    val minuteListState = rememberLazyListState(initialFirstVisibleItemIndex = minuteBase + safeInitialMinute)
    val coroutineScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    val selectedHourIndex by remember {
        derivedStateOf {
            resolveCenteredWheelIndex(hourListState.layoutInfo, hourListState.firstVisibleItemIndex)
        }
    }
    val selectedMinuteIndex by remember {
        derivedStateOf {
            resolveCenteredWheelIndex(minuteListState.layoutInfo, minuteListState.firstVisibleItemIndex)
        }
    }
    val selectedHour by remember {
        derivedStateOf { wheelIndexToValue(selectedHourIndex, 24) }
    }
    val selectedMinute by remember {
        derivedStateOf { wheelIndexToValue(selectedMinuteIndex, 60) }
    }

    var previousHour by remember { mutableIntStateOf(safeInitialHour) }
    var previousMinute by remember { mutableIntStateOf(safeInitialMinute) }
    var initializedHaptics by remember { mutableStateOf(false) }

    LaunchedEffect(selectedHour, selectedMinute) {
        if (!initializedHaptics) {
            initializedHaptics = true
            previousHour = selectedHour
            previousMinute = selectedMinute
            return@LaunchedEffect
        }
        if (selectedHour != previousHour || selectedMinute != previousMinute) {
            previousHour = selectedHour
            previousMinute = selectedMinute
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    val isPhoneLandscape = LocalMaiDialogPhoneLandscape.current
    val visibleRows = if (isPhoneLandscape) 3 else 5
    val itemHeight = if (isPhoneLandscape) 36.dp else 40.dp
    val wheelHeight = itemHeight * visibleRows

    val nowMillis = remember(selectedHour, selectedMinute) { System.currentTimeMillis() }
    val nextTriggerMillis = remember(nowMillis, selectedHour, selectedMinute) {
        nextScheduledBackupMillis(
            nowMillis = nowMillis,
            zoneId = ZoneId.systemDefault(),
            hour = selectedHour,
            minute = selectedMinute
        )
    }
    val nextHint = remember(nowMillis, nextTriggerMillis) {
        formatNextScheduleHint(nowMillis, nextTriggerMillis, ZoneId.systemDefault())
    }

    MaiDialog(
        title = "选择每日备份时间",
        subtitle = "24 小时制 · 同一天最多自动备份一次",
        icon = Icons.Outlined.Schedule,
        iconBackground = BrandOrange,
        size = MaiDialogSize.Compact,
        scrollable = false,
        onDismissRequest = onDismiss,
        footer = {
            MaiDialogButtonRow {
                MaiDialogButton(
                    text = "取消",
                    style = MaiDialogButtonStyle.Secondary,
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss
                )
                MaiDialogButton(
                    text = "确定",
                    style = MaiDialogButtonStyle.Primary,
                    modifier = Modifier.weight(1f),
                    onClick = { onConfirm(selectedHour, selectedMinute) }
                )
            }
        }
    ) {
        // 实时时间与下次触发预览卡
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Corners.Chip))
                .background(DialogInnerSurface)
                .border(0.8.dp, CardBorder, RoundedCornerShape(Corners.Chip))
                .padding(horizontal = 14.dp, vertical = if (isPhoneLandscape) 8.dp else 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = formatScheduleTime(selectedHour, selectedMinute),
                color = BrandOrange,
                fontSize = if (isPhoneLandscape) 26.sp else 32.sp,
                fontWeight = FontWeight.Bold,
                style = TextStyle(fontFeatureSettings = "tnum")
            )
            Text(
                text = "${backupTimePeriodLabel(selectedHour)} · 下次计划：$nextHint",
                color = TextMuted,
                fontSize = TextSizes.Caption,
                textAlign = TextAlign.Center
            )
        }

        // 双列循环吸附滚轮容器
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Corners.Chip))
                .background(DialogInnerSurface)
                .border(0.8.dp, CardBorder, RoundedCornerShape(Corners.Chip))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "时",
                    color = TextFaint,
                    fontSize = TextSizes.Caption,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(20.dp))
                Text(
                    text = "分",
                    color = TextFaint,
                    fontSize = TextSizes.Caption,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(wheelHeight),
                contentAlignment = Alignment.Center
            ) {
                // 居中选中高亮带与冒号分隔符，透出液态玻璃容器背景
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeight)
                        .clip(RoundedCornerShape(Corners.Control))
                        .background(CardSurfaceSelected.copy(alpha = 0.78f))
                        .border(0.8.dp, BrandOrange.copy(alpha = 0.45f), RoundedCornerShape(Corners.Control)),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = ":",
                        color = BrandOrange,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    WheelNumberColumn(
                        listState = hourListState,
                        valueCount = 24,
                        selectedIndex = selectedHourIndex,
                        visibleRows = visibleRows,
                        itemHeight = itemHeight,
                        onItemTap = { targetIndex ->
                            coroutineScope.launch { hourListState.animateScrollToItem(targetIndex) }
                        },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(20.dp))
                    WheelNumberColumn(
                        listState = minuteListState,
                        valueCount = 60,
                        selectedIndex = selectedMinuteIndex,
                        visibleRows = visibleRows,
                        itemHeight = itemHeight,
                        onItemTap = { targetIndex ->
                            coroutineScope.launch { minuteListState.animateScrollToItem(targetIndex) }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 常用时段快捷胶囊：同时沿最短路径平滑滚动小时与分钟两列
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PICKER_PRESET_TIMES.forEach { (hour, minute) ->
                val active = selectedHour == hour && selectedMinute == minute
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(Corners.Control))
                        .background(if (active) CardSurfaceSelected else CardDivider.copy(alpha = 0.45f))
                        .border(
                            width = 0.8.dp,
                            color = if (active) BrandOrange else CardBorder,
                            shape = RoundedCornerShape(Corners.Control)
                        )
                        .clickable {
                            val targetHourIdx = nearestWheelTargetIndex(selectedHourIndex, hour, 24)
                            val targetMinuteIdx = nearestWheelTargetIndex(selectedMinuteIndex, minute, 60)
                            coroutineScope.launch { hourListState.animateScrollToItem(targetHourIdx) }
                            coroutineScope.launch { minuteListState.animateScrollToItem(targetMinuteIdx) }
                        }
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = formatScheduleTime(hour, minute),
                        color = if (active) BrandOrange else TextMuted,
                        fontSize = TextSizes.Caption,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        style = TextStyle(fontFeatureSettings = "tnum")
                    )
                }
            }
        }
    }
}

@Composable
private fun WheelNumberColumn(
    listState: LazyListState,
    valueCount: Int,
    selectedIndex: Int,
    visibleRows: Int,
    itemHeight: Dp,
    onItemTap: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val snapBehavior = rememberSnapFlingBehavior(lazyListState = listState)
    val verticalPadding = itemHeight * (visibleRows / 2)

    LazyColumn(
        state = listState,
        flingBehavior = snapBehavior,
        contentPadding = PaddingValues(vertical = verticalPadding),
        modifier = modifier.height(itemHeight * visibleRows)
    ) {
        items(count = Int.MAX_VALUE) { index ->
            val value = wheelIndexToValue(index, valueCount)
            val isSelected = index == selectedIndex
            val interactionSource = remember { MutableInteractionSource() }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(itemHeight)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null
                    ) { onItemTap(index) }
                    .graphicsLayer {
                        val layoutInfo = listState.layoutInfo
                        val itemInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                        val viewportHeight = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
                        if (itemInfo != null && viewportHeight > 0) {
                            val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2f
                            val itemCenter = itemInfo.offset + itemInfo.size / 2f
                            val maxDistance = (viewportHeight / 2f).coerceAtLeast(1f)
                            val fraction = (abs(itemCenter - viewportCenter) / maxDistance).coerceIn(0f, 1f)
                            val scale = 1.0f - fraction * 0.16f
                            scaleX = scale
                            scaleY = scale
                            alpha = 1.0f - fraction * 0.70f
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = String.format(Locale.US, "%02d", value),
                    color = if (isSelected) TextPrimary else TextMuted,
                    fontSize = if (isSelected) 22.sp else 18.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    style = TextStyle(fontFeatureSettings = "tnum")
                )
            }
        }
    }
}

private fun resolveCenteredWheelIndex(
    layoutInfo: LazyListLayoutInfo,
    fallbackIndex: Int
): Int {
    val visible = layoutInfo.visibleItemsInfo
    if (visible.isEmpty()) return fallbackIndex
    val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
    return visible.minByOrNull { item ->
        val itemCenter = item.offset + item.size / 2
        abs(itemCenter - viewportCenter)
    }?.index ?: fallbackIndex
}
