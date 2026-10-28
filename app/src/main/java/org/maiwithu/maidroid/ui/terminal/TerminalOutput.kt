package org.maiwithu.maidroid.ui.terminal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardBorder
import org.maiwithu.maidroid.ui.theme.CardSurface
import org.maiwithu.maidroid.ui.theme.StatusOnline
import org.maiwithu.maidroid.ui.theme.TextFaint
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextSizes

/**
 * 全屏终端输出弹窗：显示 TerminalLog（或 OOBE 的命令日志），支持筛选、自动跟随、自动换行、复制。
 * 按窗口宽高调整边距和工具栏排布，点弹窗外部关闭。
 */
@Composable
internal fun TerminalOutputDialog(
    logs: List<String>,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
        ) {
            val isWide = maxWidth >= 700.dp
            val isShort = maxHeight < 520.dp
            val isVeryShort = maxHeight < 300.dp
            val useInlineToolbar = maxWidth >= 600.dp
            val horizontalMargin = when {
                isWide -> 32.dp
                maxWidth < 360.dp -> 12.dp
                else -> 16.dp
            }
            val verticalMargin = when {
                isVeryShort -> 4.dp
                isShort -> 8.dp
                else -> 20.dp
            }

            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = horizontalMargin, vertical = verticalMargin),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier
                        .widthIn(max = 960.dp)
                        .fillMaxWidth()
                        .heightIn(max = 720.dp)
                        .fillMaxHeight()
                        // Keep taps inside the dialog from falling through to the dismiss scrim
                        // without competing with selection, scrolling, or child click handlers.
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent()
                                }
                            }
                        },
                    shape = RoundedCornerShape(if (isWide) 28.dp else 24.dp),
                    color = CardSurface,
                    contentColor = Color.White,
                    tonalElevation = 8.dp,
                    shadowElevation = 20.dp,
                    border = BorderStroke(1.dp, CardBorder)
                ) {
                    TerminalDialogContent(
                        logs = logs,
                        isWide = isWide,
                        isShort = isShort,
                        useInlineToolbar = useInlineToolbar,
                        showFooter = !isVeryShort,
                        onDismiss = onDismiss
                    )
                }
            }
        }
    }
}

/** 嵌入在 OOBE 平板布局右下方的终端面板，功能同弹窗，工具栏压缩成一行。 */
@Composable
internal fun EmbeddedTerminalOutputPanel(
    logs: List<String>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = CardSurface,
        contentColor = Color.White,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, CardBorder)
    ) {
        EmbeddedTerminalContent(
            logs = logs,
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun EmbeddedTerminalContent(
    logs: List<String>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val viewer = rememberTerminalOutputState()
    val displayedLogs = rememberDisplayedLines(logs, viewer.query)
    val terminalText = remember(displayedLogs) { displayedLogs.toTerminalText() }
    FollowLatestOutputEffect(viewer, terminalText)

    Column(modifier = Modifier.fillMaxSize()) {
        EmbeddedTerminalToolbar(
            totalLineCount = logs.size,
            displayedLineCount = displayedLogs.size,
            query = viewer.query,
            onQueryChange = { viewer.query = it },
            autoFollow = viewer.autoFollow,
            onAutoFollowChange = viewer::changeAutoFollow,
            wrapLines = viewer.wrapLines,
            onWrapLinesChange = viewer::changeWrapLines,
            canCopy = displayedLogs.isNotEmpty(),
            onCopy = { copyTerminalLines(context, displayedLogs) },
            toolbarScrollState = viewer.toolbarScroll,
            onDismiss = onDismiss
        )

        HorizontalDivider(color = CardBorder)

        TerminalLogViewport(
            terminalText = terminalText,
            hasAnyLogs = logs.isNotEmpty(),
            query = viewer.query,
            wrapLines = viewer.wrapLines,
            verticalScrollState = viewer.verticalScroll,
            horizontalScrollState = viewer.horizontalScroll,
            onJumpToBottom = viewer::jumpToLatest,
            onUserScrollAwayFromBottom = viewer::pauseFollow,
            compactEmptyState = true,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
        )
    }
}

@Composable
private fun EmbeddedTerminalToolbar(
    totalLineCount: Int,
    displayedLineCount: Int,
    query: String,
    onQueryChange: (String) -> Unit,
    autoFollow: Boolean,
    onAutoFollowChange: (Boolean) -> Unit,
    wrapLines: Boolean,
    onWrapLinesChange: (Boolean) -> Unit,
    canCopy: Boolean,
    onCopy: () -> Unit,
    toolbarScrollState: ScrollState,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(BrandOrange.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Terminal,
                contentDescription = null,
                tint = BrandOrange,
                modifier = Modifier.size(18.dp)
            )
        }

        Column(
            modifier = Modifier
                .widthIn(min = 92.dp, max = 122.dp)
                .padding(start = 8.dp, end = 8.dp)
        ) {
            Text(
                text = "终端输出",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1
            )
            Text(
                text = buildString {
                    append(displayedLineCount)
                    append(" / ")
                    append(totalLineCount)
                    append(if (autoFollow) " 行 · 跟随" else " 行 · 已暂停")
                },
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(toolbarScrollState),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TerminalSearchField(
                query = query,
                onQueryChange = onQueryChange,
                modifier = Modifier.width(180.dp)
            )
            TerminalActions(
                autoFollow = autoFollow,
                onAutoFollowChange = onAutoFollowChange,
                wrapLines = wrapLines,
                onWrapLinesChange = onWrapLinesChange,
                canCopy = canCopy,
                onCopy = onCopy
            )
        }

        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "收起终端输出",
                tint = TextMuted
            )
        }
    }
}

@Composable
private fun TerminalDialogContent(
    logs: List<String>,
    isWide: Boolean,
    isShort: Boolean,
    useInlineToolbar: Boolean,
    showFooter: Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val viewer = rememberTerminalOutputState()
    val displayedLogs = rememberDisplayedLines(logs, viewer.query)
    val terminalText = remember(displayedLogs) { displayedLogs.toTerminalText() }
    FollowLatestOutputEffect(viewer, terminalText)

    Column(modifier = Modifier.fillMaxSize()) {
        TerminalDialogHeader(
            totalLineCount = logs.size,
            displayedLineCount = displayedLogs.size,
            query = viewer.query,
            compact = isShort,
            onDismiss = onDismiss
        )

        HorizontalDivider(color = CardBorder)

        TerminalToolbar(
            query = viewer.query,
            onQueryChange = { viewer.query = it },
            autoFollow = viewer.autoFollow,
            onAutoFollowChange = viewer::changeAutoFollow,
            wrapLines = viewer.wrapLines,
            onWrapLinesChange = viewer::changeWrapLines,
            canCopy = displayedLogs.isNotEmpty(),
            onCopy = { copyTerminalLines(context, displayedLogs) },
            inline = useInlineToolbar,
            modifier = Modifier.padding(
                horizontal = if (isWide) 24.dp else 16.dp,
                vertical = if (isShort) 10.dp else 14.dp
            )
        )

        TerminalLogViewport(
            terminalText = terminalText,
            hasAnyLogs = logs.isNotEmpty(),
            query = viewer.query,
            wrapLines = viewer.wrapLines,
            verticalScrollState = viewer.verticalScroll,
            horizontalScrollState = viewer.horizontalScroll,
            onJumpToBottom = viewer::jumpToLatest,
            onUserScrollAwayFromBottom = viewer::pauseFollow,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = if (isWide) 24.dp else 16.dp)
        )

        if (showFooter) {
            TerminalDialogFooter(
                autoFollow = viewer.autoFollow,
                displayedLineCount = displayedLogs.size,
                totalLineCount = logs.size,
                onDismiss = onDismiss,
                compact = isShort,
                modifier = Modifier.padding(
                    start = if (isWide) 24.dp else 16.dp,
                    end = if (isWide) 24.dp else 16.dp,
                    top = if (isShort) 8.dp else 12.dp,
                    bottom = if (isShort) 10.dp else 16.dp
                )
            )
        }
    }
}

@Composable
private fun TerminalDialogHeader(
    totalLineCount: Int,
    displayedLineCount: Int,
    query: String,
    compact: Boolean,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = 20.dp,
                end = 10.dp,
                top = if (compact) 10.dp else 16.dp,
                bottom = if (compact) 10.dp else 14.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(if (compact) 38.dp else 44.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(BrandOrange.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Terminal,
                contentDescription = null,
                tint = BrandOrange,
                modifier = Modifier.size(if (compact) 20.dp else 23.dp)
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "终端输出",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1
            )
            if (!compact) {
                val detail = when {
                    query.isNotBlank() -> "匹配 $displayedLineCount / $totalLineCount 行"
                    else -> "实时日志 · $totalLineCount 行"
                }
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "关闭终端输出",
                tint = TextMuted
            )
        }
    }
}

@Composable
private fun TerminalToolbar(
    query: String,
    onQueryChange: (String) -> Unit,
    autoFollow: Boolean,
    onAutoFollowChange: (Boolean) -> Unit,
    wrapLines: Boolean,
    onWrapLinesChange: (Boolean) -> Unit,
    canCopy: Boolean,
    onCopy: () -> Unit,
    inline: Boolean,
    modifier: Modifier = Modifier
) {
    // 搜索框和三个按钮：宽屏排成一行，手机竖屏搜索框在上、按钮在下；按钮都平分可用宽度，不再横向滚动。
    if (inline) {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TerminalSearchField(
                query = query,
                onQueryChange = onQueryChange,
                modifier = Modifier
                    .weight(0.42f)
            )
            TerminalActions(
                autoFollow = autoFollow,
                onAutoFollowChange = onAutoFollowChange,
                wrapLines = wrapLines,
                onWrapLinesChange = onWrapLinesChange,
                canCopy = canCopy,
                onCopy = onCopy,
                fillWidth = true,
                modifier = Modifier.weight(0.58f)
            )
        }
    } else {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TerminalSearchField(
                query = query,
                onQueryChange = onQueryChange,
                modifier = Modifier.fillMaxWidth()
            )
            TerminalActions(
                autoFollow = autoFollow,
                onAutoFollowChange = onAutoFollowChange,
                wrapLines = wrapLines,
                onWrapLinesChange = onWrapLinesChange,
                canCopy = canCopy,
                onCopy = onCopy,
                fillWidth = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun TerminalSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        placeholder = {
            Text(
                text = "筛选输出",
                color = TextFaint,
                style = MaterialTheme.typography.bodyMedium
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(19.dp)
            )
        },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "清除筛选条件",
                        tint = TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        } else {
            null
        },
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            cursorColor = BrandOrange,
            focusedBorderColor = BrandOrange.copy(alpha = 0.8f),
            unfocusedBorderColor = CardBorder,
            focusedContainerColor = Color.Black.copy(alpha = 0.16f),
            unfocusedContainerColor = Color.Black.copy(alpha = 0.12f)
        )
    )
}

// fillWidth 为 true 时三个按钮平分整行（弹窗工具栏）；嵌入面板的工具栏本身可以横向滚动，按内容宽度排列。
@Composable
private fun TerminalActions(
    autoFollow: Boolean,
    onAutoFollowChange: (Boolean) -> Unit,
    wrapLines: Boolean,
    onWrapLinesChange: (Boolean) -> Unit,
    canCopy: Boolean,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
    fillWidth: Boolean = false
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val buttonModifier = if (fillWidth) Modifier.weight(1f) else Modifier
        TerminalToolbarButton(
            text = "自动跟随",
            selected = autoFollow,
            onClick = { onAutoFollowChange(!autoFollow) },
            modifier = buttonModifier
        )
        TerminalToolbarButton(
            text = "自动换行",
            selected = wrapLines,
            onClick = { onWrapLinesChange(!wrapLines) },
            modifier = buttonModifier
        )
        TerminalToolbarButton(
            text = "复制",
            icon = Icons.Outlined.ContentCopy,
            enabled = canCopy,
            onClick = onCopy,
            modifier = buttonModifier
        )
    }
}

/**
 * 终端工具栏的按钮。selected 不为 null 时是开关：开着填品牌橙底色、白字，关着只有描边、灰字；
 * selected 为 null 时是普通动作按钮（复制），橙色描边。没有可复制的内容时置灰、不可点。
 */
@Composable
private fun TerminalToolbarButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean? = null,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    val shape = RoundedCornerShape(12.dp)
    val contentColor = when {
        !enabled -> TextFaint
        selected == false -> TextMuted
        else -> Color.White
    }
    val borderColor = when {
        !enabled -> CardBorder
        selected == null -> BrandOrange.copy(alpha = 0.7f)
        selected -> Color.Transparent
        else -> CardBorder
    }
    Row(
        modifier = modifier
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(if (selected == true) BrandOrange.copy(alpha = 0.16f) else Color.Transparent)
            .border(1.dp, borderColor, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
            text = text,
            color = contentColor,
            fontSize = TextSizes.Body,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TerminalLogViewport(
    terminalText: AnnotatedString,
    hasAnyLogs: Boolean,
    query: String,
    wrapLines: Boolean,
    verticalScrollState: ScrollState,
    horizontalScrollState: ScrollState,
    onJumpToBottom: () -> Unit,
    onUserScrollAwayFromBottom: () -> Unit,
    modifier: Modifier = Modifier,
    compactEmptyState: Boolean = false
) {
    val userScrollConnection = remember(onUserScrollAwayFromBottom) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f) {
                    onUserScrollAwayFromBottom()
                }
                return Offset.Zero
            }
        }
    }

    Box(
        modifier = modifier
            .nestedScroll(userScrollConnection)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0B0B0C))
            .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(16.dp))
    ) {
        if (terminalText.isEmpty()) {
            TerminalEmptyState(
                hasAnyLogs = hasAnyLogs,
                query = query,
                compact = compactEmptyState,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(verticalScrollState)
            ) {
                SelectionContainer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (wrapLines) {
                                Modifier
                            } else {
                                Modifier.horizontalScroll(horizontalScrollState)
                            }
                        )
                ) {
                    Text(
                        text = terminalText,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        color = Color(0xFFD0D0D4),
                        fontFamily = FontFamily.Monospace,
                        fontSize = TextSizes.Caption,
                        lineHeight = 17.sp,
                        softWrap = wrapLines
                    )
                }
            }

            if (verticalScrollState.canScrollForward) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp),
                    shape = CircleShape,
                    color = CardSurface.copy(alpha = 0.96f),
                    contentColor = Color.White,
                    border = BorderStroke(1.dp, CardBorder),
                    shadowElevation = 6.dp
                ) {
                    IconButton(
                        onClick = onJumpToBottom,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.KeyboardArrowDown,
                            contentDescription = "跳到最新输出"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalEmptyState(
    hasAnyLogs: Boolean,
    query: String,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    val title: String
    val detail: String
    when {
        query.isNotBlank() && hasAnyLogs -> {
            title = "没有匹配的输出"
            detail = "请尝试其他关键词"
        }
        else -> {
            title = "暂无命令输出"
            detail = "平台安装或 MaiBot 启动后，实时日志会显示在这里"
        }
    }

    Column(
        modifier = modifier.padding(if (compact) 8.dp else 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.Terminal,
            contentDescription = null,
            tint = TextFaint,
            modifier = Modifier.size(if (compact) 20.dp else 30.dp)
        )
        Spacer(modifier = Modifier.size(if (compact) 4.dp else 10.dp))
        Text(
            text = title,
            style = if (compact) {
                MaterialTheme.typography.labelLarge
            } else {
                MaterialTheme.typography.titleSmall
            },
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = 0.86f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.size(if (compact) 2.dp else 4.dp))
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
            maxLines = if (compact) 1 else Int.MAX_VALUE,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TerminalDialogFooter(
    autoFollow: Boolean,
    displayedLineCount: Int,
    totalLineCount: Int,
    onDismiss: () -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(
                        if (autoFollow) StatusOnline else TextFaint
                    )
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = buildString {
                    append(if (autoFollow) "正在跟随" else "跟随已暂停")
                    if (!compact) {
                        append(" · 显示 ")
                        append(displayedLineCount)
                        append(" / ")
                        append(totalLineCount)
                        append(" 行")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Button(
            onClick = onDismiss,
            shape = RoundedCornerShape(13.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = BrandOrange,
                contentColor = Color.White
            )
        ) {
            Text(text = "关闭", fontWeight = FontWeight.Bold)
        }
    }
}
