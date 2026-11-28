package org.maiwithu.maidroid.ui.terminal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.shapes.RoundedRectangle
import org.maiwithu.maidroid.terminal.ContainerShellPhase
import org.maiwithu.maidroid.ui.component.LiquidButton
import org.maiwithu.maidroid.ui.component.LiquidButtonStyle
import org.maiwithu.maidroid.ui.component.rememberSurfaceBackdrop
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

/** 顶栏下方可展开/收起的二级工具栏，按当前所在页面呈现对应的高频控制项。 */
@Composable
internal fun TerminalExpandableToolbar(
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

/** 标题和状态：输出页显示“终端输出”+ 实时/暂停状态；本地终端页显示“本地终端”+ 会话运行阶段。 */
@Composable
internal fun TerminalTitle(
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

/** 终端输出页顶栏筛选输入区，打开时自动聚焦并拦截返回键收起搜索。 */
@Composable
internal fun RowScope.SearchBarContent(
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

/** 终端输出页多选模式下的顶栏操作区（已选行数、全选与一键复制）。 */
@Composable
internal fun RowScope.SelectionBarContent(
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
internal fun BarIconButton(
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
