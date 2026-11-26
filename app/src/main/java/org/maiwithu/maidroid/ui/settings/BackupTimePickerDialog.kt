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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.maiwithu.maidroid.backup.BackupFrequency
import org.maiwithu.maidroid.backup.DEFAULT_SCHEDULE_DAY_OF_WEEK
import org.maiwithu.maidroid.backup.formatDailyTriggerSlots
import org.maiwithu.maidroid.backup.formatNextScheduleHint
import org.maiwithu.maidroid.backup.formatScheduleTime
import org.maiwithu.maidroid.backup.formatWeekdayLabel
import org.maiwithu.maidroid.backup.nextScheduledBackupMillis
import org.maiwithu.maidroid.ui.component.LocalMaiDialogPhoneLandscape
import org.maiwithu.maidroid.ui.component.MaiDialog
import org.maiwithu.maidroid.ui.component.MaiDialogButton
import org.maiwithu.maidroid.ui.component.MaiDialogButtonRow
import org.maiwithu.maidroid.ui.component.MaiDialogButtonStyle
import org.maiwithu.maidroid.ui.component.MaiDialogSize
import org.maiwithu.maidroid.ui.component.rememberLiquidHaptics
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardBorder
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

/** 根据小时返回时段说明文字，帮助用户避开聊天活跃时段挑选备份时刻。 */
internal fun backupTimePeriodLabel(hour: Int): String = when (hour.coerceIn(0, 23)) {
    in 0..5 -> "凌晨低峰期"
    in 6..11 -> "上午"
    in 12..17 -> "下午"
    else -> "夜间"
}

internal data class TimePickerWheelSpec(
    val visibleRows: Int,
    val itemHeightDp: Int
)

/** 依据是否手机横屏矮视口决定滚轮可见行数与行高，确保横屏也能完整显示 3 行而非被压成单行。 */
internal fun resolveTimePickerWheelSpec(isPhoneLandscape: Boolean): TimePickerWheelSpec =
    if (isPhoneLandscape) {
        TimePickerWheelSpec(visibleRows = 3, itemHeightDp = 36)
    } else {
        TimePickerWheelSpec(visibleRows = 5, itemHeightDp = 40)
    }

/** 限制时间选择器最大浮窗宽度：手机横屏收敛在 456dp，留足背景呼吸区避免几乎铺满屏幕。 */
internal fun resolveTimePickerDialogMaxWidthDp(isPhoneLandscape: Boolean): Int =
    if (isPhoneLandscape) 456 else 380

/**
 * 液态玻璃风格的 24 小时制自动备份时间选择器：
 * - 采用双列循环吸附滚轮与实时频率触发预览（一日多次时同步计算当天全部分段触发点）；
 * - 手机横屏下以紧凑 456dp 浮窗承载左右等高双卡，并将「下次计划」收入底栏左侧以消除空挡；
 * - 横竖屏切换导致滚轮可见行数变化（5 行 ↔ 3 行）时保持当前已选时分不漂移。
 */
@Composable
internal fun BackupTimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    frequency: BackupFrequency = BackupFrequency.Daily,
    scheduleDayOfWeek: Int = DEFAULT_SCHEDULE_DAY_OF_WEEK,
    onConfirm: (hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit
) {
    val safeInitialHour = initialHour.coerceIn(0, 23)
    val safeInitialMinute = initialMinute.coerceIn(0, 59)
    val hourBase = remember { wheelLoopBaseIndex(24) }
    val minuteBase = remember { wheelLoopBaseIndex(60) }

    var persistedHour by rememberSaveable { mutableIntStateOf(safeInitialHour) }
    var persistedMinute by rememberSaveable { mutableIntStateOf(safeInitialMinute) }

    val hourListState = rememberLazyListState(initialFirstVisibleItemIndex = hourBase + persistedHour)
    val minuteListState = rememberLazyListState(initialFirstVisibleItemIndex = minuteBase + persistedMinute)
    val coroutineScope = rememberCoroutineScope()
    val liquidHaptics = rememberLiquidHaptics()
    var suppressWheelSync by remember { mutableStateOf(false) }

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

    // 滚轮跨过小时或分钟刻度时更新已选时分并触发线性马达震动；横竖屏行数切换重定位期间跳过同步，避免刻度漂移。
    LaunchedEffect(liquidHaptics) {
        snapshotFlow { selectedHour to selectedMinute }
            .distinctUntilChanged()
            .drop(1)
            .collect { (hour, minute) ->
                if (!suppressWheelSync) {
                    persistedHour = hour
                    persistedMinute = minute
                    liquidHaptics.performTabStep()
                }
            }
    }

    val configuration = LocalConfiguration.current
    val isPhoneLandscapeWindow = configuration.screenWidthDp > configuration.screenHeightDp &&
        configuration.screenHeightDp < 480 &&
        configuration.screenWidthDp >= 500

    val nowMillis = remember(persistedHour, persistedMinute, frequency, scheduleDayOfWeek) {
        System.currentTimeMillis()
    }
    val nextTriggerMillis = remember(nowMillis, persistedHour, persistedMinute, frequency, scheduleDayOfWeek) {
        nextScheduledBackupMillis(
            nowMillis = nowMillis,
            zoneId = ZoneId.systemDefault(),
            hour = persistedHour,
            minute = persistedMinute,
            frequency = frequency,
            dayOfWeek = scheduleDayOfWeek
        )
    }
    val nextHint = remember(nowMillis, nextTriggerMillis) {
        formatNextScheduleHint(nowMillis, nextTriggerMillis, ZoneId.systemDefault())
    }

    val dialogTitle = when (frequency) {
        BackupFrequency.Every6Hours,
        BackupFrequency.Every12Hours -> "选择基准备份时间"
        BackupFrequency.Daily -> "选择每日备份时间"
        BackupFrequency.Weekly -> "选择每周备份时间"
    }
    val dialogSubtitle = when (frequency) {
        BackupFrequency.Every6Hours -> "24 小时制 · 每 6 小时按基准偏移时刻触发"
        BackupFrequency.Every12Hours -> "24 小时制 · 每 12 小时按基准偏移时刻触发"
        BackupFrequency.Daily -> "24 小时制 · 同一天最多自动备份一次"
        BackupFrequency.Weekly -> "24 小时制 · 每${formatWeekdayLabel(scheduleDayOfWeek)}触发"
    }

    MaiDialog(
        title = dialogTitle,
        subtitle = dialogSubtitle,
        icon = Icons.Outlined.Schedule,
        iconBackground = BrandOrange,
        size = if (isPhoneLandscapeWindow) MaiDialogSize.Medium else MaiDialogSize.Compact,
        modifier = Modifier.widthIn(max = resolveTimePickerDialogMaxWidthDp(isPhoneLandscapeWindow).dp),
        scrollable = false,
        onDismissRequest = onDismiss,
        footerStart = if (isPhoneLandscapeWindow) {
            {
                Text(
                    text = "下次计划：$nextHint",
                    color = TextMuted,
                    fontSize = TextSizes.Caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else {
            null
        },
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
                    onClick = { onConfirm(persistedHour, persistedMinute) }
                )
            }
        }
    ) {
        // 必须在 MaiDialog 内部读取 LocalMaiDialogPhoneLandscape，才能拿到弹窗实际测算的矮宽视口标志。
        val phoneLandscape = LocalMaiDialogPhoneLandscape.current
        val wheelSpec = resolveTimePickerWheelSpec(phoneLandscape)
        val itemHeight = wheelSpec.itemHeightDp.dp
        val wheelHeight = itemHeight * wheelSpec.visibleRows

        // 当横竖屏切换导致滚轮可见行数（5 行 <-> 3 行）变化时，保持已选时分居中不偏移。
        var previousPhoneLandscape by remember { mutableStateOf<Boolean?>(null) }
        LaunchedEffect(phoneLandscape) {
            val prev = previousPhoneLandscape
            previousPhoneLandscape = phoneLandscape
            if (prev != null && prev != phoneLandscape) {
                suppressWheelSync = true
                try {
                    hourListState.scrollToItem(hourBase + persistedHour)
                    minuteListState.scrollToItem(minuteBase + persistedMinute)
                } finally {
                    suppressWheelSync = false
                }
            }
        }

        val periodDesc = when (frequency) {
            BackupFrequency.Weekly -> "每${formatWeekdayLabel(scheduleDayOfWeek)} ${backupTimePeriodLabel(persistedHour)}"
            BackupFrequency.Every6Hours,
            BackupFrequency.Every12Hours,
            BackupFrequency.Daily -> backupTimePeriodLabel(persistedHour)
        }
        val multiSlotText = if (frequency == BackupFrequency.Every6Hours || frequency == BackupFrequency.Every12Hours) {
            "每日触发：${formatDailyTriggerSlots(frequency, persistedHour, persistedMinute)}"
        } else {
            null
        }

        if (phoneLandscape) {
            // 右栏包含 LazyColumn 滚轮（SubcomposeLayout），不能使用 IntrinsicSize.Min；统一给定等高 146dp 确保双卡底边齐平。
            val landscapePanelHeight = wheelHeight + 38.dp
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(0.92f)
                        .height(landscapePanelHeight)
                        .clip(RoundedCornerShape(Corners.Chip))
                        .background(DialogInnerSurface)
                        .border(0.8.dp, CardBorder, RoundedCornerShape(Corners.Chip))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = formatScheduleTime(persistedHour, persistedMinute),
                        color = BrandOrange,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        style = TextStyle(fontFeatureSettings = "tnum")
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = multiSlotText ?: periodDesc,
                        color = if (multiSlotText != null) TextPrimary else TextMuted,
                        fontSize = TextSizes.Caption,
                        lineHeight = 16.sp,
                        textAlign = TextAlign.Center,
                        style = if (multiSlotText != null) TextStyle(fontFeatureSettings = "tnum") else TextStyle.Default
                    )
                }

                WheelContainerCard(
                    hourListState = hourListState,
                    minuteListState = minuteListState,
                    selectedHourIndex = selectedHourIndex,
                    selectedMinuteIndex = selectedMinuteIndex,
                    visibleRows = wheelSpec.visibleRows,
                    itemHeight = itemHeight,
                    wheelHeight = wheelHeight,
                    compactPadding = true,
                    onHourTap = { target -> coroutineScope.launch { hourListState.animateScrollToItem(target) } },
                    onMinuteTap = { target -> coroutineScope.launch { minuteListState.animateScrollToItem(target) } },
                    modifier = Modifier
                        .weight(1.08f)
                        .height(landscapePanelHeight)
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Corners.Chip))
                    .background(DialogInnerSurface)
                    .border(0.8.dp, CardBorder, RoundedCornerShape(Corners.Chip))
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = formatScheduleTime(persistedHour, persistedMinute),
                    color = BrandOrange,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    style = TextStyle(fontFeatureSettings = "tnum")
                )
                if (multiSlotText != null) {
                    Text(
                        text = multiSlotText,
                        color = TextPrimary,
                        fontSize = TextSizes.Caption,
                        textAlign = TextAlign.Center,
                        style = TextStyle(fontFeatureSettings = "tnum")
                    )
                    Text(
                        text = "下次计划：$nextHint",
                        color = TextMuted,
                        fontSize = TextSizes.Caption,
                        textAlign = TextAlign.Center
                    )
                } else {
                    Text(
                        text = "$periodDesc · 下次计划：$nextHint",
                        color = TextMuted,
                        fontSize = TextSizes.Caption,
                        textAlign = TextAlign.Center
                    )
                }
            }

            WheelContainerCard(
                hourListState = hourListState,
                minuteListState = minuteListState,
                selectedHourIndex = selectedHourIndex,
                selectedMinuteIndex = selectedMinuteIndex,
                visibleRows = wheelSpec.visibleRows,
                itemHeight = itemHeight,
                wheelHeight = wheelHeight,
                compactPadding = false,
                onHourTap = { target -> coroutineScope.launch { hourListState.animateScrollToItem(target) } },
                onMinuteTap = { target -> coroutineScope.launch { minuteListState.animateScrollToItem(target) } },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun WheelContainerCard(
    hourListState: LazyListState,
    minuteListState: LazyListState,
    selectedHourIndex: Int,
    selectedMinuteIndex: Int,
    visibleRows: Int,
    itemHeight: Dp,
    wheelHeight: Dp,
    compactPadding: Boolean,
    onHourTap: (Int) -> Unit,
    onMinuteTap: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Corners.Chip))
            .background(DialogInnerSurface)
            .border(0.8.dp, CardBorder, RoundedCornerShape(Corners.Chip))
            .padding(
                horizontal = if (compactPadding) 12.dp else 14.dp,
                vertical = if (compactPadding) 6.dp else 10.dp
            ),
        verticalArrangement = Arrangement.spacedBy(if (compactPadding) 4.dp else 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "时",
                color = TextFaint,
                fontSize = TextSizes.Caption,
                lineHeight = 14.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(20.dp))
            Text(
                text = "分",
                color = TextFaint,
                fontSize = TextSizes.Caption,
                lineHeight = 14.sp,
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
                    onItemTap = onHourTap,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(20.dp))
                WheelNumberColumn(
                    listState = minuteListState,
                    valueCount = 60,
                    selectedIndex = selectedMinuteIndex,
                    visibleRows = visibleRows,
                    itemHeight = itemHeight,
                    onItemTap = onMinuteTap,
                    modifier = Modifier.weight(1f)
                )
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
