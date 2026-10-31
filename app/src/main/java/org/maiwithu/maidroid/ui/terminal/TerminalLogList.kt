package org.maiwithu.maidroid.ui.terminal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.maiwithu.maidroid.terminal.AnsiLine
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardBorder
import org.maiwithu.maidroid.ui.theme.CardSurface
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.OnBrandOrange
import org.maiwithu.maidroid.ui.theme.TerminalText
import org.maiwithu.maidroid.ui.theme.TextFaint
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes

// 日志正文：等宽小字；自动换行时折下来的续行缩进一点，一眼能看出和上一行是同一条日志。
private val TerminalLineStyle = TextStyle(
    color = TerminalText,
    fontFamily = FontFamily.Monospace,
    fontSize = TextSizes.Caption,
    lineHeight = 17.sp,
    textIndent = TextIndent(restLine = 1.5.em)
)

// 命中筛选关键字的位置：品牌橙底色。
private val SearchHighlight = SpanStyle(background = BrandOrange.copy(alpha = 0.38f), color = Color.White)

// 长按拖选时手指离列表上下边缘不到这个距离就自动滚动，越靠边越快。
private val AutoScrollEdge = 56.dp
private const val AUTO_SCROLL_MAX_PX_PER_FRAME = 28f

/**
 * 终端日志列表：每条日志一行，暂停、选择、自动跟随的规则见 TerminalViewerState。
 *
 * 手势：普通拖动是滚动；往回翻（看更早的日志）时自动暂停，滑回最底部时自动回到实时。
 * 长按某行 -> 震动一下并选中它 -> 不松手上下拖动连选一段，拖到边缘会自动滚动；选择模式下单击某行增减这一行。
 * 自动换行关掉时整块列表可以左右滑动看长行。
 */
@Composable
internal fun TerminalLogList(
    viewer: TerminalViewerState,
    liveLogs: List<String>,
    rows: List<TerminalRow>,
    hasAnyLogs: Boolean,
    newLineCount: Int?,
    horizontalPadding: Dp,
    modifier: Modifier = Modifier
) {
    val listState = viewer.listState
    val currentLiveLogs by rememberUpdatedState(liveLogs)
    val currentRows by rememberUpdatedState(rows)
    val haptics = LocalHapticFeedback.current

    // 暂停时改筛选条件：停在原来看的那一行（被筛掉了就是它后面最近的一行）。LazyList 自己按 key 找旧位置时只在
    // 当前下标附近找，筛选前后同一行的下标差得远就找不到，会按下标跳到毫不相干的地方，所以这里自己定位。
    // 换算要用变化前的列表和第一行可见位置，在组合阶段算好，SideEffect 里赶在这一帧排版前交给 LazyList。
    val keeper = remember { PausedPositionKeeper() }
    if (keeper.rows !== rows) {
        val previous = keeper.rows
        keeper.rows = rows
        keeper.pendingIndex = if (previous != null && viewer.paused) {
            val firstVisible = Snapshot.withoutReadObservation { listState.firstVisibleItemIndex }
            previous.getOrNull(firstVisible)?.let { rowIndexAtOrAfter(rows, it.sourceIndex) }
        } else {
            null
        }
    }
    SideEffect {
        keeper.pendingIndex?.let { index ->
            listState.requestScrollToItem(index)
            keeper.pendingIndex = null
        }
    }

    // 实时模式：日志一变（新行、改筛选、切换换行）就跳到最底部。列表最后有一个零高度的锚点项，
    // 滚到它时列表会自动退回到“最后一行贴着底边”的位置，最后一条日志再长也能完整露出来。
    LaunchedEffect(rows, viewer.paused, viewer.wrapLines) {
        if (!viewer.paused && rows.isNotEmpty()) listState.scrollToItem(rows.size)
    }

    val scrollConnection = remember(viewer) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // 手指往下拖 = 往回翻更早的日志：先冻结再滚，免得新日志把人拽回底部。
                if (source == NestedScrollSource.UserInput && available.y > 0f && listState.canScrollBackward) {
                    viewer.pause(currentLiveLogs)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // 往新的方向滑（含惯性）并且到底了：回到实时。
                if (consumed.y < 0f && !listState.canScrollForward) viewer.onUserReachedLatest()
                return Offset.Zero
            }
        }
    }

    // 长按拖选时手指的位置（相对列表），为 null 表示没在拖选；自动滚动靠它判断方向和速度。
    var dragPointerY by remember { mutableStateOf<Float?>(null) }
    val autoScrollEdgePx = with(LocalDensity.current) { AutoScrollEdge.toPx() }
    LaunchedEffect(dragPointerY != null) {
        while (true) {
            val y = dragPointerY ?: break
            val height = listState.layoutInfo.viewportSize.height.toFloat()
            val speed = when {
                y < autoScrollEdgePx -> -(autoScrollEdgePx - y) / autoScrollEdgePx
                y > height - autoScrollEdgePx -> (y - (height - autoScrollEdgePx)) / autoScrollEdgePx
                else -> 0f
            }.coerceIn(-1f, 1f) * AUTO_SCROLL_MAX_PX_PER_FRAME
            if (speed != 0f) {
                listState.scrollBy(speed)
                listState.rowIndexAt(y, currentRows.size, clamp = true)?.let { viewer.extendDragSelection(currentRows, it) }
            }
            withFrameNanos { }
        }
    }

    BoxWithConstraints(modifier = modifier) {
        val viewportWidth = maxWidth
        if (rows.isEmpty()) {
            TerminalLogEmptyState(
                filtered = hasAnyLogs && viewer.query.isNotBlank(),
                modifier = Modifier.fillMaxSize()
            )
        } else {
            val contentWidth = rememberNoWrapContentWidth(rows, horizontalPadding, enabled = !viewer.wrapLines)
            val horizontalScroll = rememberScrollState()
            LaunchedEffect(viewer.wrapLines) {
                if (viewer.wrapLines) horizontalScroll.scrollTo(0)
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (viewer.wrapLines) Modifier else Modifier.horizontalScroll(horizontalScroll))
            ) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = 8.dp),
                    modifier = Modifier
                        .then(
                            if (viewer.wrapLines) {
                                Modifier.fillMaxSize()
                            } else {
                                Modifier
                                    .width(maxOf(contentWidth, viewportWidth))
                                    .fillMaxHeight()
                            }
                        )
                        .nestedScroll(scrollConnection)
                        .pointerInput(viewer) {
                            detectLineGestures(
                                onTap = { y ->
                                    if (viewer.selecting) {
                                        listState.rowIndexAt(y, currentRows.size)
                                            ?.let { currentRows.getOrNull(it) }
                                            ?.let(viewer::toggleRow)
                                    }
                                },
                                onLongPress = { y ->
                                    val index = listState.rowIndexAt(y, currentRows.size)
                                    if (index != null) {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        viewer.startDragSelection(currentLiveLogs, currentRows, index)
                                        dragPointerY = y
                                    }
                                },
                                onDrag = { y ->
                                    if (dragPointerY != null) {
                                        dragPointerY = y
                                        listState.rowIndexAt(y, currentRows.size, clamp = true)
                                            ?.let { viewer.extendDragSelection(currentRows, it) }
                                    }
                                },
                                onDragEnd = {
                                    dragPointerY = null
                                    viewer.endDragSelection()
                                }
                            )
                        }
                ) {
                    items(items = rows, key = { it.sourceIndex }, contentType = { 0 }) { row ->
                        TerminalLogLine(
                            line = row.line,
                            query = viewer.query,
                            selected = row.sourceIndex in viewer.selection,
                            wrap = viewer.wrapLines
                        )
                    }
                    // 自动跟随用的锚点，见上面的 LaunchedEffect。
                    item(key = END_ANCHOR_KEY) { Spacer(modifier = Modifier.height(0.dp)) }
                }
            }
        }

        AnimatedVisibility(
            visible = viewer.paused && !viewer.selecting,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
        ) {
            ResumeLivePill(newLineCount = newLineCount, onClick = viewer::resume)
        }
    }
}

private const val END_ANCHOR_KEY = "end"

// 上一次组合时的行，以及暂停时改筛选后要恢复到的位置。
private class PausedPositionKeeper {
    var rows: List<TerminalRow>? = null
    var pendingIndex: Int? = null
}

@Composable
private fun TerminalLogLine(
    line: AnsiLine,
    query: String,
    selected: Boolean,
    wrap: Boolean
) {
    val text: AnnotatedString = remember(line, query) { line.toDisplayText(query, SearchHighlight) }
    Text(
        text = text,
        style = TerminalLineStyle,
        softWrap = wrap,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selected) {
                    // 选中的行：浅橙底色，左边一道橙色竖条。
                    Modifier.drawBehind {
                        drawRect(BrandOrange.copy(alpha = 0.2f))
                        drawRect(BrandOrange, size = Size(3.dp.toPx(), size.height))
                    }
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 6.dp, vertical = 1.dp)
    )
}

/** 暂停时浮在列表底部的提示，点一下回到实时。有新日志时用品牌橙底色提醒，没有时只是一个低调的“回到实时”。 */
@Composable
private fun ResumeLivePill(newLineCount: Int?, onClick: () -> Unit) {
    val hasNew = newLineCount != 0
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(Corners.Pill),
        color = if (hasNew) BrandOrange else CardSurface,
        contentColor = if (hasNew) OnBrandOrange else TextPrimary,
        border = if (hasNew) null else BorderStroke(1.dp, CardBorder),
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.ArrowDownward,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = when (newLineCount) {
                    null -> "大量新输出"
                    0 -> "回到实时"
                    else -> "$newLineCount 条新输出"
                },
                fontSize = TextSizes.Body,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun TerminalLogEmptyState(filtered: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.Terminal,
            contentDescription = null,
            tint = TextFaint,
            modifier = Modifier.size(28.dp)
        )
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = if (filtered) "没有匹配的输出" else "暂无输出",
            fontSize = TextSizes.Body,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary.copy(alpha = 0.86f)
        )
        Spacer(modifier = Modifier.size(4.dp))
        Text(
            text = if (filtered) "换个关键词试试" else "安装平台或启动 MaiBot 后，实时日志会显示在这里",
            fontSize = TextSizes.Caption,
            color = TextMuted
        )
    }
}

/**
 * 关掉自动换行时列表要多宽才放得下最长的一行。挑出估算最长的几行（中文按两个字宽算）实际量一下，
 * 不逐行测量，日志滚动时也不卡。
 */
@Composable
private fun rememberNoWrapContentWidth(rows: List<TerminalRow>, horizontalPadding: Dp, enabled: Boolean): Dp {
    if (!enabled) return 0.dp
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(rows, horizontalPadding, density) {
        val candidates = rows
            .flatMap { row -> row.line.text.split('\n') }
            .sortedByDescending(::estimatedColumns)
            .take(NO_WRAP_MEASURE_CANDIDATES)
        val widest = candidates.maxOfOrNull { text ->
            measurer.measure(text, TerminalLineStyle, softWrap = false, maxLines = 1).size.width
        } ?: 0
        // 每行自己左右各 6dp，列表左右各 horizontalPadding，再多留一点余量。
        with(density) { widest.toDp() } + horizontalPadding * 2 + 12.dp + 8.dp
    }
}

private const val NO_WRAP_MEASURE_CANDIDATES = 4

// 估算一行占几列：中日韩文字和全角符号按两列算。
private fun estimatedColumns(text: String): Int = text.sumOf { if (it.code >= 0x2E80) 2 else 1 }

/**
 * 列表里位于纵坐标 y（相对列表顶边）的那一行在 rows 里的位置；末尾的锚点项不算。
 * clamp 为 true 时（拖选），手指在第一行上方或最后一行下方取最近的一行，方便拖过头；点按和长按要正好落在某一行上。
 * LazyList 的条目偏移以内容区起点为 0，要先扣掉顶部内边距（viewportStartOffset 就是负的顶部内边距）。
 */
private fun LazyListState.rowIndexAt(y: Float, rowCount: Int, clamp: Boolean = false): Int? {
    if (rowCount == 0) return null
    val info = layoutInfo
    val visible = info.visibleItemsInfo.filter { it.index < rowCount }
    if (visible.isEmpty()) return null
    val target = y.toInt() + info.viewportStartOffset
    val hit = visible.firstOrNull { target >= it.offset && target < it.offset + it.size }
    return when {
        hit != null -> hit.index
        !clamp -> null
        target < visible.first().offset -> visible.first().index
        else -> visible.last().index
    }
}

/**
 * 日志行的点按和长按拖选。滚动由 LazyColumn 自己处理：手指移动超过触摸阈值或事件被滚动消费，就不再算点按或长按。
 * 长按成立后在 Initial 阶段就消费后续的移动，LazyColumn 和外层的横向滚动都看不到这些事件，拖选时列表不会跟着手指滚。
 */
private suspend fun PointerInputScope.detectLineGestures(
    onTap: (y: Float) -> Unit,
    onLongPress: (y: Float) -> Unit,
    onDrag: (y: Float) -> Unit,
    onDragEnd: () -> Unit
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var tapped = false
        // 长按时长内松手或变成滚动就提前结束（返回 true）；等满时长返回 null，即长按成立。
        val endedEarly = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    tapped = !change.isConsumed
                    break
                }
                if (change.isConsumed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
            }
            true
        }
        if (endedEarly == null) {
            onLongPress(down.position.y)
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes
                    .firstOrNull { it.id == down.id } ?: break
                change.consume()
                if (!change.pressed) break
                onDrag(change.position.y)
            }
            onDragEnd()
        } else if (tapped) {
            onTap(down.position.y)
        }
    }
}
