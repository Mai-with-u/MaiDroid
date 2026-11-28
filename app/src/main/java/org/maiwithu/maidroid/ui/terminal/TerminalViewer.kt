package org.maiwithu.maidroid.ui.terminal

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.WrapText
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.launch
import org.maiwithu.maidroid.appDependencies
import org.maiwithu.maidroid.terminal.ContainerShellPhase
import org.maiwithu.maidroid.terminal.TerminalLog
import org.maiwithu.maidroid.ui.component.LiquidButton
import org.maiwithu.maidroid.ui.component.LiquidButtonStyle
import org.maiwithu.maidroid.ui.component.LiquidSegmentItem
import org.maiwithu.maidroid.ui.component.LiquidSegmentedSelector
import org.maiwithu.maidroid.ui.component.LocalLiquidBackdrop
import org.maiwithu.maidroid.ui.component.appPageBackground
import org.maiwithu.maidroid.ui.component.rememberSurfaceBackdrop
import org.maiwithu.maidroid.ui.diagnostics.FeedbackDialog
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardBorder
import org.maiwithu.maidroid.ui.theme.CardSurface
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.StatusDanger
import org.maiwithu.maidroid.ui.theme.StatusOnline
import org.maiwithu.maidroid.ui.theme.StatusWarning
import org.maiwithu.maidroid.ui.theme.TerminalBackground
import org.maiwithu.maidroid.ui.theme.TextFaint
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes

/**
 * 全屏终端页：主页的终端按钮或 OOBE 的终端按钮打开它，盖在当前页面上，按返回键或左上角返回关闭。
 * 主页模式下支持向右滑动或点击顶栏「输出 | 终端」切换至本地交互式容器 Shell，并在顶栏下方提供可收展操作栏。
 *
 * 放在页面最外层 Box 的最后（不是独立的对话框窗口），这样沿用 Activity 的系统栏设置：横屏时状态栏和导航栏照样隐藏。
 */
@Composable
internal fun TerminalOutputScreen(
    visible: Boolean,
    logs: List<String>? = null,
    enableInteractiveShell: Boolean = true,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier.fillMaxSize(),
        enter = fadeIn(tween(180)) + slideInVertically(tween(240)) { it / 16 },
        exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { it / 16 }
    ) {
        // 主页仅在终端输出层真正展开时订阅全局日志流，避免后台每新增一行日志触发根界面整树重组。
        val activeLogs = logs ?: TerminalLog.logs.collectAsState().value
        TerminalViewer(
            logs = activeLogs,
            onDismiss = onDismiss,
            embedded = false,
            enableInteractiveShell = enableInteractiveShell,
            active = visible,
            modifier = Modifier
                .fillMaxSize()
                .appPageBackground(TerminalBackground)
                // 只登记命中、不消费事件：落在终端页上的点按和滑动不会漏到下面的网页和导航栏，子控件照常处理。
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent()
                    }
                }
        )
    }
}

/** 嵌在 OOBE 平板布局右下方的终端面板，仅展示安装/启动日志流，右上角的叉收起面板。 */
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
            enableInteractiveShell = false,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun TerminalViewer(
    logs: List<String>,
    onDismiss: () -> Unit,
    embedded: Boolean,
    enableInteractiveShell: Boolean,
    active: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val viewer = rememberTerminalViewerState()
    val shellManager = remember(context, enableInteractiveShell) {
        if (enableInteractiveShell) context.appDependencies.containerShell else null
    }
    val shellState = shellManager?.state?.collectAsState()?.value

    // 顶栏的“反馈问题”：看着日志或终端发现不对劲时，直接从这里导出诊断包
    var showFeedback by rememberSaveable { mutableStateOf(false) }
    var toolsExpanded by rememberSaveable { mutableStateOf(false) }
    var showExtraKeys by rememberSaveable { mutableStateOf(true) }
    var shellSelectingText by remember { mutableStateOf(false) }
    var shellMouseTracking by remember { mutableStateOf(false) }

    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { if (enableInteractiveShell) 2 else 1 }
    )
    val currentPage = if (enableInteractiveShell && pagerState.currentPage == 1) {
        TerminalPage.Shell
    } else {
        TerminalPage.Output
    }

    // 实时 -> 显示最新日志；暂停 -> 显示冻结的快照。解析颜色、按关键字筛选都基于当前显示的这份。
    val shownLogs = viewer.shownLogs(logs)
    val lines = rememberParsedLines(shownLogs)
    val rows = remember(lines, viewer.query) { filterTerminalRows(lines, viewer.query) }
    val frozenLogs = viewer.frozenLogs
    // 暂停以来的新日志行数；null 表示多到快照里的行已经全被挤出去了。
    val newLineCount: Int? = remember(frozenLogs, logs) { if (frozenLogs == null) 0 else countNewLines(frozenLogs, logs) }

    // 收起搜索或关闭全屏终端页前先主动清焦点并收起软键盘，避免终端视图卸载时键盘残留或焦点落回底层页面。
    val closeSearch = {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        viewer.closeSearch()
    }
    val dismissTerminal = {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        onDismiss()
    }

    // 切向「终端」页时自动收起日志搜索与多选状态，避免旧页状态阻塞输入或返回键
    LaunchedEffect(currentPage) {
        if (currentPage == TerminalPage.Shell) {
            if (viewer.selecting) viewer.clearSelection()
            if (viewer.searchOpen) closeSearch()
        }
    }

    // 返回键按层级退出：先退出选择 -> 收起搜索 -> 收起顶部展开工具栏 -> 关闭全屏终端页
    BackHandler(enabled = viewer.selecting, onBack = viewer::clearSelection)
    BackHandler(enabled = !viewer.selecting && viewer.searchOpen, onBack = closeSearch)
    BackHandler(enabled = !viewer.selecting && !viewer.searchOpen && toolsExpanded) {
        toolsExpanded = false
    }
    BackHandler(enabled = !embedded && !viewer.selecting && !viewer.searchOpen && !toolsExpanded, onBack = dismissTerminal)

    if (showFeedback) {
        FeedbackDialog(failure = null, onDismiss = { showFeedback = false })
    }

    val topInsets = if (embedded) null else WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    val barExtrasInsets = if (embedded) null else WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)

    BoxWithConstraints(modifier = modifier) {
        val compact = embedded || maxHeight < 480.dp
        val wide = maxWidth >= 600.dp
        Column(modifier = Modifier.fillMaxSize()) {
            TerminalTopBar(
                viewer = viewer,
                currentPage = currentPage,
                enableInteractiveShell = enableInteractiveShell,
                toolsExpanded = toolsExpanded,
                shellPhase = shellState?.phase,
                rowCount = rows.size,
                totalLineCount = shownLogs.size,
                compact = compact,
                embedded = embedded,
                insets = topInsets,
                onDismiss = dismissTerminal,
                onCloseSearch = closeSearch,
                onSelectPage = { page ->
                    scope.launch {
                        pagerState.animateScrollToPage(if (page == TerminalPage.Shell) 1 else 0)
                    }
                },
                onToggleTools = { toolsExpanded = !toolsExpanded },
                onSelectAll = { viewer.selectAll(rows) },
                onCopySelection = {
                    copyTerminalText(context, selectedText(lines, viewer.selection), viewer.selection.size)
                    viewer.clearSelection()
                }
            )

            TerminalExpandableToolbar(
                visible = toolsExpanded && !viewer.selecting && !viewer.searchOpen,
                currentPage = currentPage,
                viewer = viewer,
                hasCopyableRows = rows.isNotEmpty(),
                showExtraKeys = showExtraKeys,
                insets = barExtrasInsets,
                onOpenSearch = {
                    toolsExpanded = false
                    viewer.openSearch()
                },
                onTogglePause = { viewer.togglePause(logs) },
                onToggleWrap = { viewer.changeWrapLines(!viewer.wrapLines) },
                onCopyAll = {
                    copyTerminalText(
                        context = context,
                        text = rows.joinToString(separator = "\n") { it.line.text },
                        lineCount = rows.size,
                        hint = "长按日志行可以只复制其中几行"
                    )
                },
                onClearShell = { shellManager?.clearScreen() },
                onToggleExtraKeys = { showExtraKeys = !showExtraKeys },
                onPasteShell = {
                    if (shellManager?.pasteFromClipboard() == false) {
                        Toast.makeText(context, "剪贴板为空或终端未运行", Toast.LENGTH_SHORT).show()
                    }
                },
                onRestartShell = { shellManager?.restart() },
                onFeedback = { showFeedback = true }
            )

            HorizontalDivider(color = CardBorder)

            val outputPageModifier = if (embedded) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                    )
            }

            if (enableInteractiveShell && shellManager != null) {
                HorizontalPager(
                    state = pagerState,
                    beyondViewportPageCount = 1,
                    // 日志拖选、终端选字复制或 TUI 鼠标追踪激活时锁定横滑，防止误切页
                    userScrollEnabled = !viewer.selecting && !shellSelectingText && !shellMouseTracking,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) { pageIndex ->
                    if (pageIndex == 0) {
                        TerminalLogList(
                            viewer = viewer,
                            liveLogs = logs,
                            rows = rows,
                            hasAnyLogs = shownLogs.isNotEmpty(),
                            newLineCount = newLineCount,
                            horizontalPadding = if (wide) 16.dp else 6.dp,
                            modifier = outputPageModifier
                        )
                    } else {
                        ContainerTerminalPane(
                            shellManager = shellManager,
                            isActivePage = active && currentPage == TerminalPage.Shell,
                            showExtraKeys = showExtraKeys,
                            onSelectingTextChange = { shellSelectingText = it },
                            onMouseTrackingChange = { shellMouseTracking = it },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            } else {
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
}

/**
 * 终端顶栏，三种状态：
 * - 平时：返回（全屏页）、当前页标题/状态、2 选项「输出 | 终端」切换器（主页全屏模式）、展开工具栏按钮、收起（嵌入面板）；
 * - 搜索：输入框占满顶栏，右边显示匹配行数；
 * - 选择：已选几行、全选、复制。
 */
@Composable
private fun TerminalTopBar(
    viewer: TerminalViewerState,
    currentPage: TerminalPage,
    enableInteractiveShell: Boolean,
    toolsExpanded: Boolean,
    shellPhase: ContainerShellPhase?,
    rowCount: Int,
    totalLineCount: Int,
    compact: Boolean,
    embedded: Boolean,
    insets: WindowInsets?,
    onDismiss: () -> Unit,
    onCloseSearch: () -> Unit,
    onSelectPage: (TerminalPage) -> Unit,
    onToggleTools: () -> Unit,
    onSelectAll: () -> Unit,
    onCopySelection: () -> Unit
) {
    val selectionBarColor = BrandOrange.copy(alpha = 0.18f).compositeOver(CardSurface)
    val barColor by animateColorAsState(
        targetValue = if (viewer.selecting) selectionBarColor else CardSurface,
        label = "TerminalTopBarColor"
    )
    val pageItems = remember {
        listOf(LiquidSegmentItem(TerminalPage.Output.label), LiquidSegmentItem(TerminalPage.Shell.label))
    }
    val expandRotation by animateFloatAsState(
        targetValue = if (toolsExpanded) 180f else 0f,
        animationSpec = tween(200),
        label = "TerminalToolsExpandRotation"
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
                    BarIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "关闭终端页", onDismiss)
                } else {
                    Spacer(modifier = Modifier.width(8.dp))
                }
                TerminalTitle(
                    currentPage = currentPage,
                    paused = viewer.paused,
                    totalLineCount = totalLineCount,
                    shellPhase = shellPhase,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp)
                )
                if (enableInteractiveShell) {
                    CompositionLocalProvider(LocalLiquidBackdrop provides rememberSurfaceBackdrop(barColor)) {
                        LiquidSegmentedSelector(
                            items = pageItems,
                            selectedIndex = if (currentPage == TerminalPage.Shell) 1 else 0,
                            onSelect = { idx ->
                                onSelectPage(if (idx == 1) TerminalPage.Shell else TerminalPage.Output)
                            },
                            height = if (compact) 32.dp else 34.dp,
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .width(104.dp)
                        )
                    }
                }
                IconButton(
                    onClick = onToggleTools,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (toolsExpanded) BrandOrange.copy(alpha = 0.12f) else Color.Transparent,
                        contentColor = if (toolsExpanded) BrandOrange else TextPrimary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ExpandMore,
                        contentDescription = if (toolsExpanded) "收起工具栏" else "展开工具栏",
                        modifier = Modifier
                            .size(22.dp)
                            .rotate(expandRotation)
                    )
                }
                if (embedded) {
                    BarIconButton(Icons.Outlined.Close, "收起终端输出", onDismiss)
                }
            }
        }
    }
}

/** 顶栏下方可展开/收起的二级工具栏，按当前所在页面呈现对应的高频控制项。 */
@Composable
private fun TerminalExpandableToolbar(
    visible: Boolean,
    currentPage: TerminalPage,
    viewer: TerminalViewerState,
    hasCopyableRows: Boolean,
    showExtraKeys: Boolean,
    insets: WindowInsets?,
    onOpenSearch: () -> Unit,
    onTogglePause: () -> Unit,
    onToggleWrap: () -> Unit,
    onCopyAll: () -> Unit,
    onClearShell: () -> Unit,
    onToggleExtraKeys: () -> Unit,
    onPasteShell: () -> Unit,
    onRestartShell: () -> Unit,
    onFeedback: () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(tween(200)) + fadeIn(tween(150)),
        exit = shrinkVertically(tween(180)) + fadeOut(tween(120))
    ) {
        Column(modifier = Modifier.fillMaxWidth().background(CardSurface)) {
            HorizontalDivider(color = CardBorder)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (insets != null) Modifier.windowInsetsPadding(insets) else Modifier)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (currentPage) {
                    TerminalPage.Output -> {
                        ToolbarActionChip(
                            icon = Icons.Outlined.Search,
                            label = "筛选",
                            onClick = onOpenSearch
                        )
                        ToolbarActionChip(
                            icon = if (viewer.paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                            label = if (viewer.paused) "继续" else "暂停",
                            active = viewer.paused,
                            onClick = onTogglePause
                        )
                        ToolbarActionChip(
                            icon = Icons.AutoMirrored.Outlined.WrapText,
                            label = "换行",
                            active = viewer.wrapLines,
                            onClick = onToggleWrap
                        )
                        ToolbarActionChip(
                            icon = Icons.Outlined.ContentCopy,
                            label = "复制全部",
                            enabled = hasCopyableRows,
                            onClick = onCopyAll
                        )
                        ToolbarActionChip(
                            icon = Icons.Outlined.BugReport,
                            label = "反馈",
                            onClick = onFeedback
                        )
                    }

                    TerminalPage.Shell -> {
                        ToolbarActionChip(
                            icon = Icons.Outlined.CleaningServices,
                            label = "清屏",
                            onClick = onClearShell
                        )
                        ToolbarActionChip(
                            icon = Icons.Outlined.Keyboard,
                            label = "快捷键",
                            active = showExtraKeys,
                            onClick = onToggleExtraKeys
                        )
                        ToolbarActionChip(
                            icon = Icons.Outlined.ContentPaste,
                            label = "粘贴",
                            onClick = onPasteShell
                        )
                        ToolbarActionChip(
                            icon = Icons.Outlined.Refresh,
                            label = "重启",
                            onClick = onRestartShell
                        )
                        ToolbarActionChip(
                            icon = Icons.Outlined.BugReport,
                            label = "反馈",
                            onClick = onFeedback
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolbarActionChip(
    icon: ImageVector,
    label: String,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(Corners.Control)
    val backgroundColor = when {
        !enabled -> CardSurface
        active -> BrandOrange.copy(alpha = 0.14f)
        else -> TerminalBackground
    }
    val borderColor = if (active && enabled) BrandOrange.copy(alpha = 0.55f) else CardBorder
    val contentColor = when {
        !enabled -> TextFaint
        active -> BrandOrange
        else -> TextPrimary
    }
    Row(
        modifier = Modifier
            .height(34.dp)
            .clip(shape)
            .background(backgroundColor)
            .border(0.8.dp, borderColor, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(16.dp))
        Text(
            text = label,
            color = contentColor,
            fontSize = TextSizes.Caption,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

// 标题和状态：输出页显示“终端输出”+ 实时/暂停状态；本地终端页显示“本地终端”+ 会话运行阶段。
@Composable
private fun TerminalTitle(
    currentPage: TerminalPage,
    paused: Boolean,
    totalLineCount: Int,
    shellPhase: ContainerShellPhase?,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.Center) {
        Text(
            text = if (currentPage == TerminalPage.Shell) "本地终端" else "终端输出",
            fontSize = TextSizes.BodyLarge,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            maxLines = 1
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            val dotColor = when (currentPage) {
                TerminalPage.Output -> if (paused) StatusWarning else StatusOnline
                TerminalPage.Shell -> when (shellPhase) {
                    ContainerShellPhase.Running -> StatusOnline
                    is ContainerShellPhase.Exited -> StatusWarning
                    is ContainerShellPhase.NotReady -> StatusDanger
                    ContainerShellPhase.Idle, null -> TextFaint
                }
            }
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(modifier = Modifier.width(6.dp))
            val candidates = when (currentPage) {
                TerminalPage.Output -> if (paused) {
                    listOf("已暂停 · 长按日志行可多选", "已暂停 · 长按可多选", "已暂停 · $totalLineCount 行")
                } else {
                    listOf("实时 · $totalLineCount 行")
                }

                TerminalPage.Shell -> when (shellPhase) {
                    ContainerShellPhase.Running -> listOf("容器 bash · 运行中", "运行中")
                    is ContainerShellPhase.Exited -> listOf("已退出（${shellPhase.exitCode}）")
                    is ContainerShellPhase.NotReady -> listOf("容器未就绪")
                    ContainerShellPhase.Idle, null -> listOf("准备启动...")
                }
            }
            var candidate by remember(currentPage, paused, shellPhase) { mutableIntStateOf(0) }
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
    LiquidButton(
        onClick = onCopy,
        backdrop = rememberSurfaceBackdrop(TerminalBackground),
        style = LiquidButtonStyle.Primary,
        shape = { RoundedRectangle(Corners.Control) },
        minHeight = 36.dp,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        modifier = Modifier.padding(start = 4.dp, end = 8.dp)
    ) {
        Icon(imageVector = Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = "复制", fontSize = TextSizes.Body, fontWeight = FontWeight.Bold)
    }
}

/** 顶栏的图标按钮。active 为 true 时是“开着”的开关：橙色图标加浅橙圆底。 */
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
