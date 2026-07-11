package org.maiwithu.maidroid.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
                    color = PlatformCardSurface,
                    contentColor = Color.White,
                    tonalElevation = 8.dp,
                    shadowElevation = 20.dp,
                    border = BorderStroke(1.dp, PlatformBorder)
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

@Composable
internal fun EmbeddedTerminalOutputPanel(
    logs: List<String>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = PlatformCardSurface,
        contentColor = Color.White,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, PlatformBorder)
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
    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()
    val toolbarScrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var autoFollow by rememberSaveable { mutableStateOf(true) }
    var wrapLines by rememberSaveable { mutableStateOf(true) }
    val displayedLogs = remember(logs, query) {
        val keyword = query.trim()
        if (keyword.isEmpty()) {
            logs
        } else {
            logs.filter { line -> line.contains(keyword, ignoreCase = true) }
        }
    }
    val terminalText = remember(displayedLogs) {
        displayedLogs.joinToString(separator = "\n") { line -> line.ifBlank { " " } }
    }

    LaunchedEffect(terminalText, autoFollow, wrapLines) {
        if (autoFollow && displayedLogs.isNotEmpty()) {
            delay(40)
            verticalScrollState.scrollTo(verticalScrollState.maxValue)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        EmbeddedTerminalToolbar(
            totalLineCount = logs.size,
            displayedLineCount = displayedLogs.size,
            query = query,
            onQueryChange = { query = it },
            autoFollow = autoFollow,
            onAutoFollowChange = { enabled ->
                autoFollow = enabled
                if (enabled) {
                    coroutineScope.launch {
                        verticalScrollState.animateScrollTo(verticalScrollState.maxValue)
                    }
                }
            },
            wrapLines = wrapLines,
            onWrapLinesChange = { enabled ->
                wrapLines = enabled
                if (enabled) {
                    coroutineScope.launch { horizontalScrollState.scrollTo(0) }
                }
            },
            canCopy = displayedLogs.isNotEmpty(),
            onCopy = { copyLogsToClipboard(context = context, logs = displayedLogs) },
            toolbarScrollState = toolbarScrollState,
            onDismiss = onDismiss
        )

        HorizontalDivider(color = PlatformBorder)

        TerminalLogViewport(
            terminalText = terminalText,
            hasAnyLogs = logs.isNotEmpty(),
            query = query,
            wrapLines = wrapLines,
            verticalScrollState = verticalScrollState,
            horizontalScrollState = horizontalScrollState,
            onJumpToBottom = {
                autoFollow = true
                coroutineScope.launch {
                    verticalScrollState.animateScrollTo(verticalScrollState.maxValue)
                }
            },
            onUserScrollAwayFromBottom = { autoFollow = false },
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
                .background(PlatformOrange.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Terminal,
                contentDescription = null,
                tint = PlatformOrange,
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
                color = PlatformTextSecondary,
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
                tint = PlatformTextSecondary
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
    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()
    val toolbarScrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var autoFollow by rememberSaveable { mutableStateOf(true) }
    var wrapLines by rememberSaveable { mutableStateOf(true) }
    val displayedLogs = remember(logs, query) {
        val keyword = query.trim()
        if (keyword.isEmpty()) {
            logs
        } else {
            logs.filter { line -> line.contains(keyword, ignoreCase = true) }
        }
    }
    val terminalText = remember(displayedLogs) {
        displayedLogs.joinToString(separator = "\n") { line -> line.ifBlank { " " } }
    }
    LaunchedEffect(terminalText, autoFollow, wrapLines) {
        if (autoFollow && displayedLogs.isNotEmpty()) {
            delay(40)
            verticalScrollState.scrollTo(verticalScrollState.maxValue)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TerminalDialogHeader(
            totalLineCount = logs.size,
            displayedLineCount = displayedLogs.size,
            query = query,
            compact = isShort,
            onDismiss = onDismiss
        )

        HorizontalDivider(color = PlatformBorder)

        TerminalToolbar(
            query = query,
            onQueryChange = { query = it },
            autoFollow = autoFollow,
            onAutoFollowChange = { enabled ->
                autoFollow = enabled
                if (enabled) {
                    coroutineScope.launch {
                        verticalScrollState.animateScrollTo(verticalScrollState.maxValue)
                    }
                }
            },
            wrapLines = wrapLines,
            onWrapLinesChange = { enabled ->
                wrapLines = enabled
                if (enabled) {
                    coroutineScope.launch { horizontalScrollState.scrollTo(0) }
                }
            },
            canCopy = displayedLogs.isNotEmpty(),
            onCopy = {
                copyLogsToClipboard(context = context, logs = displayedLogs)
            },
            inline = useInlineToolbar,
            toolbarScrollState = toolbarScrollState,
            modifier = Modifier.padding(
                horizontal = if (isWide) 24.dp else 16.dp,
                vertical = if (isShort) 10.dp else 14.dp
            )
        )

        TerminalLogViewport(
            terminalText = terminalText,
            hasAnyLogs = logs.isNotEmpty(),
            query = query,
            wrapLines = wrapLines,
            verticalScrollState = verticalScrollState,
            horizontalScrollState = horizontalScrollState,
            onJumpToBottom = {
                autoFollow = true
                coroutineScope.launch {
                    verticalScrollState.animateScrollTo(verticalScrollState.maxValue)
                }
            },
            onUserScrollAwayFromBottom = { autoFollow = false },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = if (isWide) 24.dp else 16.dp)
        )

        if (showFooter) {
            TerminalDialogFooter(
                autoFollow = autoFollow,
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
                .background(PlatformOrange.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Terminal,
                contentDescription = null,
                tint = PlatformOrange,
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
                    color = PlatformTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "关闭终端输出",
                tint = PlatformTextSecondary
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
    toolbarScrollState: ScrollState,
    modifier: Modifier = Modifier
) {
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
                modifier = Modifier
                    .weight(0.62f)
                    .horizontalScroll(toolbarScrollState)
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
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(toolbarScrollState)
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
                color = PlatformTextDim,
                style = MaterialTheme.typography.bodyMedium
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                tint = PlatformTextSecondary,
                modifier = Modifier.size(19.dp)
            )
        },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "清除筛选条件",
                        tint = PlatformTextSecondary,
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
            cursorColor = PlatformOrange,
            focusedBorderColor = PlatformOrange.copy(alpha = 0.8f),
            unfocusedBorderColor = PlatformBorder,
            focusedContainerColor = Color.Black.copy(alpha = 0.16f),
            unfocusedContainerColor = Color.Black.copy(alpha = 0.12f)
        )
    )
}

@Composable
private fun TerminalActions(
    autoFollow: Boolean,
    onAutoFollowChange: (Boolean) -> Unit,
    wrapLines: Boolean,
    onWrapLinesChange: (Boolean) -> Unit,
    canCopy: Boolean,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = autoFollow,
            onClick = { onAutoFollowChange(!autoFollow) },
            label = { Text("自动跟随", maxLines = 1) },
            colors = terminalFilterChipColors()
        )
        FilterChip(
            selected = wrapLines,
            onClick = { onWrapLinesChange(!wrapLines) },
            label = { Text("自动换行", maxLines = 1) },
            colors = terminalFilterChipColors()
        )
        OutlinedButton(
            onClick = onCopy,
            enabled = canCopy,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = Color.White,
                disabledContentColor = PlatformTextDim
            )
        ) {
            Icon(
                imageVector = Icons.Outlined.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(17.dp)
            )
            Spacer(modifier = Modifier.size(6.dp))
            Text("复制", maxLines = 1)
        }
    }
}

@Composable
private fun terminalFilterChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = Color.Transparent,
    labelColor = PlatformTextSecondary,
    selectedContainerColor = PlatformOrange.copy(alpha = 0.16f),
    selectedLabelColor = Color.White
)

@Composable
private fun TerminalLogViewport(
    terminalText: String,
    hasAnyLogs: Boolean,
    query: String,
    wrapLines: Boolean,
    verticalScrollState: ScrollState,
    horizontalScrollState: ScrollState,
    onJumpToBottom: () -> Unit,
    onUserScrollAwayFromBottom: () -> Unit,
    compactEmptyState: Boolean = false,
    modifier: Modifier = Modifier
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
                        fontSize = 12.sp,
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
                    color = PlatformCardSurface.copy(alpha = 0.96f),
                    contentColor = Color.White,
                    border = BorderStroke(1.dp, PlatformBorder),
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
            tint = PlatformTextDim,
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
            color = PlatformTextSecondary,
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
                        if (autoFollow) PlatformOnline else PlatformTextDim
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
                color = PlatformTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Button(
            onClick = onDismiss,
            shape = RoundedCornerShape(13.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = PlatformOrange,
                contentColor = Color.White
            )
        ) {
            Text(text = "关闭", fontWeight = FontWeight.Bold)
        }
    }
}

private fun copyLogsToClipboard(context: Context, logs: List<String>) {
    if (logs.isEmpty()) return
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard?.setPrimaryClip(
        ClipData.newPlainText("MaiDroid 终端输出", logs.joinToString(separator = "\n"))
    )
    Toast.makeText(context, "已复制 ${logs.size} 行输出", Toast.LENGTH_SHORT).show()
}
