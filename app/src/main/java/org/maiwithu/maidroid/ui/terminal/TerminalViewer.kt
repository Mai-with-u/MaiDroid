package org.maiwithu.maidroid.ui.terminal

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.WrapText
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardBorder
import org.maiwithu.maidroid.ui.theme.CardSurface
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.OnBrandOrange
import org.maiwithu.maidroid.ui.theme.StatusOnline
import org.maiwithu.maidroid.ui.theme.StatusWarning
import org.maiwithu.maidroid.ui.theme.TerminalBackground
import org.maiwithu.maidroid.ui.theme.TextFaint
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes

/**
 * 全屏终端输出页：主页的终端按钮、手机上 OOBE 的终端按钮打开它，盖在当前页面上，按返回键或左上角返回关闭。
 * 只有一行顶栏，其余整块屏幕都给日志；顶栏和日志让开状态栏、挖孔和输入法。
 *
 * 放在页面最外层 Box 的最后（不是独立的对话框窗口），这样沿用 Activity 的系统栏设置：横屏时状态栏和导航栏照样隐藏。
 */
@Composable
internal fun TerminalOutputScreen(
    visible: Boolean,
    logs: List<String>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier.fillMaxSize(),
        enter = fadeIn(tween(180)) + slideInVertically(tween(240)) { it / 16 },
        exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { it / 16 }
    ) {
        TerminalViewer(
            logs = logs,
            onDismiss = onDismiss,
            embedded = false,
            modifier = Modifier
                .fillMaxSize()
                .background(TerminalBackground)
                // 只登记命中、不消费事件：落在终端页上的点按和滑动不会漏到下面的网页和导航栏，子控件照常处理。
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent()
                    }
                }
        )
    }
}

/** 嵌在 OOBE 平板布局右下方的终端面板，功能和全屏终端页相同，右上角的叉收起面板。 */
@Composable
internal fun TerminalOutputPanel(
    logs: List<String>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(Corners.Panel),
        color = TerminalBackground,
        contentColor = TextPrimary,
        border = BorderStroke(1.dp, CardBorder)
    ) {
        TerminalViewer(
            logs = logs,
            onDismiss = onDismiss,
            embedded = true,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun TerminalViewer(
    logs: List<String>,
    onDismiss: () -> Unit,
    embedded: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val viewer = rememberTerminalViewerState()
    // 实时 -> 显示最新日志；暂停 -> 显示冻结的快照。解析颜色、按关键字筛选都基于当前显示的这份。
    val shownLogs = viewer.shownLogs(logs)
    val lines = rememberParsedLines(shownLogs)
    val rows = remember(lines, viewer.query) { filterTerminalRows(lines, viewer.query) }
    val frozenLogs = viewer.frozenLogs
    // 暂停以来的新日志行数；null 表示多到快照里的行已经全被挤出去了。
    val newLineCount: Int? = remember(frozenLogs, logs) { if (frozenLogs == null) 0 else countNewLines(frozenLogs, logs) }

    // 返回键按层级退出：先退出选择，再收起搜索，最后关闭全屏终端页（嵌入面板不响应，返回键留给 OOBE 回上一步）。
    BackHandler(enabled = viewer.selecting, onBack = viewer::clearSelection)
    // 收起搜索前先主动清掉输入框的焦点：终端页和主页在同一个 ComposeView 里，聚焦的输入框直接被移除时，
    // Compose 会把焦点交给下面的 WebView，网页随即显示键盘导航才有的“跳过导航”链接（真机复现过）。
    // 先 clearFocus 的话，触摸模式下系统不会再把焦点分给别的 View；接了实体键盘（非触摸模式）时
    // 系统仍会把焦点交给 WebView，那属于正常的键盘导航。
    val closeSearch = {
        focusManager.clearFocus(force = true)
        viewer.closeSearch()
    }
    BackHandler(enabled = !viewer.selecting && viewer.searchOpen, onBack = closeSearch)
    BackHandler(enabled = !embedded && !viewer.selecting && !viewer.searchOpen, onBack = onDismiss)

    BoxWithConstraints(modifier = modifier) {
        val compact = embedded || maxHeight < 480.dp
        val wide = maxWidth >= 600.dp
        Column(modifier = Modifier.fillMaxSize()) {
            TerminalTopBar(
                viewer = viewer,
                rowCount = rows.size,
                totalLineCount = shownLogs.size,
                compact = compact,
                embedded = embedded,
                insets = if (embedded) null else WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                onDismiss = onDismiss,
                onCloseSearch = closeSearch,
                onTogglePause = { viewer.togglePause(logs) },
                onCopyAll = {
                    copyTerminalText(
                        context = context,
                        text = rows.joinToString(separator = "\n") { it.line.text },
                        lineCount = rows.size,
                        hint = "长按日志行可以只复制其中几行"
                    )
                },
                onSelectAll = { viewer.selectAll(rows) },
                onCopySelection = {
                    copyTerminalText(context, selectedText(lines, viewer.selection), viewer.selection.size)
                    viewer.clearSelection()
                }
            )
            HorizontalDivider(color = CardBorder)
            TerminalLogList(
                viewer = viewer,
                liveLogs = logs,
                rows = rows,
                hasAnyLogs = shownLogs.isNotEmpty(),
                newLineCount = newLineCount,
                horizontalPadding = if (wide) 16.dp else 6.dp,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .then(
                        if (embedded) {
                            Modifier
                        } else {
                            Modifier.windowInsetsPadding(
                                WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                            )
                        }
                    )
            )
        }
    }
}

// 选择模式下顶栏换成浅橙色，提醒现在点日志是在选行。
private val SelectionBarColor = BrandOrange.copy(alpha = 0.18f).compositeOver(CardSurface)

/**
 * 终端顶栏，三种状态：
 * - 平时：返回（全屏页）、标题和实时/暂停状态、搜索、暂停/继续、自动换行、复制全部、收起（嵌入面板）；
 * - 搜索：输入框占满顶栏，右边显示匹配行数；
 * - 选择：已选几行、全选、复制。
 */
@Composable
private fun TerminalTopBar(
    viewer: TerminalViewerState,
    rowCount: Int,
    totalLineCount: Int,
    compact: Boolean,
    embedded: Boolean,
    insets: WindowInsets?,
    onDismiss: () -> Unit,
    onCloseSearch: () -> Unit,
    onTogglePause: () -> Unit,
    onCopyAll: () -> Unit,
    onSelectAll: () -> Unit,
    onCopySelection: () -> Unit
) {
    val barColor by animateColorAsState(
        targetValue = if (viewer.selecting) SelectionBarColor else CardSurface,
        label = "TerminalTopBarColor"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(barColor)
            .then(if (insets != null) Modifier.windowInsetsPadding(insets) else Modifier)
            .height(if (compact) 48.dp else 56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when {
            viewer.selecting -> SelectionBarContent(
                selectedCount = viewer.selection.size,
                onCancel = viewer::clearSelection,
                onSelectAll = onSelectAll,
                onCopy = onCopySelection
            )
            viewer.searchOpen -> SearchBarContent(
                query = viewer.query,
                onQueryChange = { viewer.query = it },
                rowCount = rowCount,
                totalLineCount = totalLineCount,
                onClose = onCloseSearch
            )
            else -> {
                if (!embedded) {
                    BarIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "关闭终端输出", onDismiss)
                } else {
                    Spacer(modifier = Modifier.width(8.dp))
                }
                TerminalTitle(
                    paused = viewer.paused,
                    totalLineCount = totalLineCount,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp)
                )
                BarIconButton(Icons.Outlined.Search, "筛选输出", viewer::openSearch)
                BarIconButton(
                    icon = if (viewer.paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                    description = if (viewer.paused) "回到实时" else "暂停滚动",
                    onClick = onTogglePause,
                    active = viewer.paused
                )
                BarIconButton(
                    icon = Icons.AutoMirrored.Outlined.WrapText,
                    description = if (viewer.wrapLines) "关闭自动换行" else "打开自动换行",
                    onClick = { viewer.changeWrapLines(!viewer.wrapLines) },
                    active = viewer.wrapLines
                )
                BarIconButton(Icons.Outlined.ContentCopy, "复制全部输出", onCopyAll, enabled = rowCount > 0)
                if (embedded) {
                    BarIconButton(Icons.Outlined.Close, "收起终端输出", onDismiss)
                }
            }
        }
    }
}

// 标题和状态：实时时绿点“实时 · N 行”，暂停时橙点并提示可以长按多选。
@Composable
private fun TerminalTitle(paused: Boolean, totalLineCount: Int, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.Center) {
        Text(
            text = "终端输出",
            fontSize = TextSizes.BodyLarge,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            maxLines = 1
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (paused) StatusWarning else StatusOnline)
            )
            Spacer(modifier = Modifier.width(6.dp))
            // 暂停时顺带提示长按多选：从长到短挑一条放得下的，竖屏窄的时候只剩“已暂停 · N 行”。
            val candidates = if (paused) {
                listOf("已暂停 · 长按日志行可多选", "已暂停 · 长按可多选", "已暂停 · $totalLineCount 行")
            } else {
                listOf("实时 · $totalLineCount 行")
            }
            var candidate by remember(paused) { mutableIntStateOf(0) }
            Text(
                text = candidates[candidate.coerceAtMost(candidates.lastIndex)],
                fontSize = TextSizes.Caption,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { layout ->
                    if (layout.hasVisualOverflow && candidate < candidates.lastIndex) candidate++
                }
            )
        }
    }
}

@Composable
private fun RowScope.SearchBarContent(
    query: String,
    onQueryChange: (String) -> Unit,
    rowCount: Int,
    totalLineCount: Int,
    onClose: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // 打开搜索栏就聚焦输入框、弹出键盘。
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    BarIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "收起搜索", onClose)
    Box(
        modifier = Modifier
            .weight(1f)
            .padding(start = 4.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (query.isEmpty()) {
            Text(text = "筛选输出", fontSize = TextSizes.BodyLarge, color = TextFaint, maxLines = 1)
        }
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = TextStyle(color = TextPrimary, fontSize = TextSizes.BodyLarge),
            cursorBrush = SolidColor(BrandOrange),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                // 键盘收起后再按返回键，按键先送到聚焦的输入框：在这里拦下，同样走“收起搜索”（先清焦点再收起），
                // 不让输入框自己处理返回键。不发按键的返回方式（Android 13 起的预测性返回）由上面的 BackHandler 处理。
                .onPreviewKeyEvent { event ->
                    if (event.key != Key.Back) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyUp) onClose()
                    true
                }
        )
    }
    if (query.isNotBlank()) {
        Text(
            text = "$rowCount / $totalLineCount",
            fontSize = TextSizes.Caption,
            color = TextMuted,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
    if (query.isNotEmpty()) {
        BarIconButton(Icons.Outlined.Close, "清除筛选条件", { onQueryChange("") })
    }
}

@Composable
private fun RowScope.SelectionBarContent(
    selectedCount: Int,
    onCancel: () -> Unit,
    onSelectAll: () -> Unit,
    onCopy: () -> Unit
) {
    BarIconButton(Icons.Outlined.Close, "取消选择", onCancel)
    Text(
        text = "已选 $selectedCount 行",
        fontSize = TextSizes.BodyLarge,
        fontWeight = FontWeight.Bold,
        color = TextPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .weight(1f)
            .padding(start = 4.dp)
    )
    BarIconButton(Icons.Outlined.SelectAll, "全选", onSelectAll)
    Button(
        onClick = onCopy,
        shape = RoundedCornerShape(Corners.Control),
        colors = ButtonDefaults.buttonColors(containerColor = BrandOrange, contentColor = OnBrandOrange),
        contentPadding = PaddingValues(horizontal = 14.dp),
        modifier = Modifier
            .padding(start = 4.dp, end = 8.dp)
            .height(36.dp)
    ) {
        Icon(imageVector = Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = "复制", fontSize = TextSizes.Body, fontWeight = FontWeight.Bold)
    }
}

/** 顶栏的图标按钮。active 为 true 时是“开着”的开关（自动换行、已暂停）：橙色图标加浅橙圆底。 */
@Composable
private fun BarIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    active: Boolean = false,
    enabled: Boolean = true
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (active) BrandOrange.copy(alpha = 0.12f) else Color.Transparent,
            contentColor = if (active) BrandOrange else TextPrimary,
            disabledContentColor = TextFaint
        )
    ) {
        Icon(imageVector = icon, contentDescription = description, modifier = Modifier.size(22.dp))
    }
}
