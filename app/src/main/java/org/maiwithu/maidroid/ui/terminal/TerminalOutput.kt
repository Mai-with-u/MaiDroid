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
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.StatusOnline
import org.maiwithu.maidroid.ui.theme.TerminalBackground
import org.maiwithu.maidroid.ui.theme.TerminalText
import org.maiwithu.maidroid.ui.theme.TextFaint
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes

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
        shape = RoundedCornerShape(Corners.Control),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
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
    val shape = RoundedCornerShape(Corners.Control)
    val contentColor = when {
        !enabled -> TextFaint
        selected == false -> TextMuted
        else -> TextPrimary
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
            .clip(RoundedCornerShape(Corners.Panel))
            .background(TerminalBackground)
            .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(Corners.Panel))
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
                        color = TerminalText,
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
                    contentColor = TextPrimary,
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
            color = TextPrimary.copy(alpha = 0.86f),
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
