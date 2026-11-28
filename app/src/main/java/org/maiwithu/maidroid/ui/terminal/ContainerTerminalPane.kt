package org.maiwithu.maidroid.ui.terminal

import android.content.Context
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import com.kyant.shapes.RoundedRectangle
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import org.maiwithu.maidroid.R
import org.maiwithu.maidroid.terminal.ContainerShellManager
import org.maiwithu.maidroid.terminal.ContainerShellPhase
import org.maiwithu.maidroid.ui.component.LiquidButton
import org.maiwithu.maidroid.ui.component.LiquidButtonStyle
import org.maiwithu.maidroid.ui.component.rememberSurfaceBackdrop
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardBorder
import org.maiwithu.maidroid.ui.theme.CardSurface
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.DarkMaiDroidColors
import org.maiwithu.maidroid.ui.theme.JetBrainsMono
import org.maiwithu.maidroid.ui.theme.LightMaiDroidColors
import org.maiwithu.maidroid.ui.theme.LocalMaiDroidColors
import org.maiwithu.maidroid.ui.theme.StatusWarning
import org.maiwithu.maidroid.ui.theme.TerminalBackground
import org.maiwithu.maidroid.ui.theme.TextFaint
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes
import kotlin.math.roundToInt

/**
 * 本地容器交互终端面板（嵌入终端页第 2 页）：
 * - 首次切到此页才触发 ContainerShellManager 懒启动交互式 bash；
 * - 跟随全应用深浅模式动态更新 TerminalColors 调色板与字体渲染；
 * - 切离此页时主动清焦点并收起软键盘，避免按键漏给底层页面。
 */
@Composable
internal fun ContainerTerminalPane(
    shellManager: ContainerShellManager,
    isActivePage: Boolean,
    showExtraKeys: Boolean,
    onSelectingTextChange: (Boolean) -> Unit,
    onMouseTrackingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val isDark = LocalMaiDroidColors.current.isDark
    val shellState by shellManager.state.collectAsState()

    // 粘滞修饰键（CTRL / ALT）：按一次高亮等待下一字符生效后自动复位，双击/再次点按取消
    var ctrlSticky by remember { mutableStateOf(false) }
    var altSticky by remember { mutableStateOf(false) }

    val baseFontPx = remember(density) { with(density) { 13.sp.toPx().roundToInt() }.coerceAtLeast(18) }
    val minFontPx = remember(density) { with(density) { 9.sp.toPx().roundToInt() }.coerceAtLeast(12) }
    val maxFontPx = remember(density) { with(density) { 24.sp.toPx().roundToInt() }.coerceAtLeast(36) }
    var fontSizePx by remember(baseFontPx) { mutableIntStateOf(baseFontPx) }

    val terminalViewHolder = remember { TerminalViewHolder() }

    // 切到此页时懒启动容器 shell 并恢复光标闪烁；滑回日志输出页或关闭全屏终端页时停闪烁、清焦点并收起软键盘，同时释放 Pager 锁
    LaunchedEffect(isActivePage) {
        if (isActivePage) {
            shellManager.ensureStarted()
            terminalViewHolder.view?.setTerminalCursorBlinkerState(true, true)
        } else {
            onSelectingTextChange(false)
            onMouseTrackingChange(false)
            terminalViewHolder.view?.let { view ->
                view.setTerminalCursorBlinkerState(false, false)
                if (view.isSelectingText) view.stopTextSelectionMode()
                view.clearFocus()
                hideSoftKeyboard(context, view)
            }
        }
    }

    // 主题或会话代数变化时，同步写入 Termux 调色板并强制重绘终端视图
    LaunchedEffect(isDark, shellState.session, shellState.sessionGeneration) {
        applyTerminalThemeColors(shellState.session, isDark)
        terminalViewHolder.view?.let { view ->
            view.setBackgroundColor(terminalBackgroundColorInt(isDark))
            view.onScreenUpdated()
            onMouseTrackingChange(shellState.session?.emulator?.isMouseTrackingActive == true)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
            )
    ) {
        when (val phase = shellState.phase) {
            is ContainerShellPhase.NotReady -> {
                ShellNotReadyState(
                    reason = phase.reason,
                    onRetry = shellManager::restart,
                    modifier = Modifier.weight(1f)
                )
            }

            ContainerShellPhase.Idle -> {
                ShellPlaceholderState(modifier = Modifier.weight(1f))
            }

            ContainerShellPhase.Running,
            is ContainerShellPhase.Exited -> {
                val activeSession = shellState.session
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(TerminalBackground)
                ) {
                    if (activeSession != null) {
                        TerminalSessionAndroidView(
                            shellManager = shellManager,
                            session = activeSession,
                            sessionGeneration = shellState.sessionGeneration,
                            isActivePage = isActivePage,
                            isDark = isDark,
                            fontSizePx = fontSizePx,
                            onFontSizeChanged = { fontSizePx = it },
                            minFontPx = minFontPx,
                            maxFontPx = maxFontPx,
                            baseFontPx = baseFontPx,
                            readControlSticky = {
                                val current = ctrlSticky
                                if (current) ctrlSticky = false
                                current
                            },
                            readAltSticky = {
                                val current = altSticky
                                if (current) altSticky = false
                                current
                            },
                            onSelectingTextChange = onSelectingTextChange,
                            onMouseTrackingChange = onMouseTrackingChange,
                            viewHolder = terminalViewHolder,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                if (phase is ContainerShellPhase.Exited) {
                    ShellExitedBanner(
                        exitCode = phase.exitCode,
                        onRestart = shellManager::restart
                    )
                }

                AnimatedVisibility(
                    visible = showExtraKeys && phase == ContainerShellPhase.Running,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    TerminalExtraKeysBar(
                        ctrlActive = ctrlSticky,
                        altActive = altSticky,
                        onKeyClick = { key ->
                            when (key) {
                                TerminalExtraKey.Ctrl -> ctrlSticky = !ctrlSticky
                                TerminalExtraKey.Alt -> altSticky = !altSticky
                                else -> {
                                    val appCursor = activeSession?.emulator?.isCursorKeysApplicationMode == true
                                    resolveExtraKeySequence(key, appCursor)?.let(shellManager::sendText)
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

private class TerminalViewHolder {
    var view: TerminalView? = null
}

@Composable
private fun TerminalSessionAndroidView(
    shellManager: ContainerShellManager,
    session: TerminalSession,
    sessionGeneration: Int,
    isActivePage: Boolean,
    isDark: Boolean,
    fontSizePx: Int,
    onFontSizeChanged: (Int) -> Unit,
    minFontPx: Int,
    maxFontPx: Int,
    baseFontPx: Int,
    readControlSticky: () -> Boolean,
    readAltSticky: () -> Boolean,
    onSelectingTextChange: (Boolean) -> Unit,
    onMouseTrackingChange: (Boolean) -> Unit,
    viewHolder: TerminalViewHolder,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val latestSession by rememberUpdatedState(session)
    val latestIsActivePage by rememberUpdatedState(isActivePage)
    val latestIsDark by rememberUpdatedState(isDark)
    val latestFontSizePx by rememberUpdatedState(fontSizePx)
    val latestOnFontSizeChanged by rememberUpdatedState(onFontSizeChanged)
    val latestReadCtrl by rememberUpdatedState(readControlSticky)
    val latestReadAlt by rememberUpdatedState(readAltSticky)
    val latestOnSelectingChange by rememberUpdatedState(onSelectingTextChange)
    val latestOnMouseTrackingChange by rememberUpdatedState(onMouseTrackingChange)

    val typeface = remember(context) {
        ResourcesCompat.getFont(context, R.font.jetbrains_mono_regular) ?: Typeface.MONOSPACE
    }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            TerminalView(viewContext, null).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                isFocusable = true
                isFocusableInTouchMode = true
                // Termux TerminalView 初始化顺序强约束：
                // 1) 先绑定 Client（updateSize / 光标闪烁 / 按键回调都直接解引用 mClient）；
                // 2) 再调 setTextSize 创建 mRenderer，随后才能调 setTypeface（其内部直接读 mRenderer.mTextSize）；
                // 3) 最后再设置背景、调色板并 attachSession。
                setTerminalViewClient(object : TerminalViewClient {
                    override fun onScale(scale: Float): Float {
                        val rawNext = (baseFontPx * scale).roundToInt()
                        val clamped = rawNext.coerceIn(minFontPx, maxFontPx)
                        if (clamped != latestFontSizePx) {
                            latestOnFontSizeChanged(clamped)
                            setTextSize(clamped)
                        }
                        return clamped.toFloat() / baseFontPx.toFloat()
                    }

                    override fun onSingleTapUp(e: MotionEvent?) {
                        if (!isFocused) requestFocus()
                        showSoftKeyboard(viewContext, this@apply)
                        latestOnMouseTrackingChange(latestSession.emulator?.isMouseTrackingActive == true)
                    }

                    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

                    // 必须返回 false（即 InputType.TYPE_NULL，也是 Termux 默认行为）：
                    // 返回 true 会让 TerminalView 声明 TYPE_TEXT_VARIATION_VISIBLE_PASSWORD，
                    // 在荣耀/华为等系统上会直接拉起密码安全键盘（屏蔽截屏、限制输入法）。
                    override fun shouldEnforceCharBasedInput(): Boolean = false

                    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

                    override fun isTerminalViewSelected(): Boolean = latestIsActivePage

                    override fun copyModeChanged(copyMode: Boolean) {
                        latestOnSelectingChange(copyMode)
                    }

                    override fun onKeyDown(keyCode: Int, e: KeyEvent?, currentSession: TerminalSession?): Boolean = false

                    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false

                    override fun onLongPress(event: MotionEvent?): Boolean = false

                    override fun readControlKey(): Boolean = latestReadCtrl()

                    override fun readAltKey(): Boolean = latestReadAlt()

                    override fun readShiftKey(): Boolean = false

                    override fun readFnKey(): Boolean = false

                    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, currentSession: TerminalSession?): Boolean {
                        latestOnMouseTrackingChange(currentSession?.emulator?.isMouseTrackingActive == true)
                        return false
                    }

                    override fun onEmulatorSet() {
                        applyTerminalThemeColors(latestSession, latestIsDark)
                        setBackgroundColor(terminalBackgroundColorInt(latestIsDark))
                        setTerminalCursorBlinkerRate(500)
                        setTerminalCursorBlinkerState(latestIsActivePage, true)
                        onScreenUpdated()
                    }

                    override fun logError(tag: String?, message: String?) = Unit
                    override fun logWarn(tag: String?, message: String?) = Unit
                    override fun logInfo(tag: String?, message: String?) = Unit
                    override fun logDebug(tag: String?, message: String?) = Unit
                    override fun logVerbose(tag: String?, message: String?) = Unit
                    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
                    override fun logStackTrace(tag: String?, e: Exception?) = Unit
                })
                setTextSize(latestFontSizePx)
                setTypeface(typeface)
                setBackgroundColor(terminalBackgroundColorInt(latestIsDark))
                applyTerminalThemeColors(latestSession, latestIsDark)
                attachSession(latestSession)
                // 在节点创建阶段用普通引用持有视图并绑定会话刷新器，不触发额外的 Compose 状态写入。
                viewHolder.view = this
                shellManager.attachView(this)
            }
        },
        update = { view ->
            if (view.currentSession !== session) {
                applyTerminalThemeColors(session, isDark)
                view.attachSession(session)
            }
            view.setBackgroundColor(terminalBackgroundColorInt(isDark))
            if (view.tag != sessionGeneration) {
                view.tag = sessionGeneration
                view.onScreenUpdated()
            }
            latestOnMouseTrackingChange(session.emulator?.isMouseTrackingActive == true)
        },
        onRelease = { view ->
            // 视图从组合树卸载时停止光标闪烁定时器、收起软键盘、退出选字并解绑容器会话，防止定时器与视图泄漏。
            view.setTerminalCursorBlinkerState(false, false)
            if (view.isSelectingText) view.stopTextSelectionMode()
            hideSoftKeyboard(context, view)
            shellManager.detachView(view)
            if (viewHolder.view === view) {
                viewHolder.view = null
            }
        }
    )
}

@Composable
private fun TerminalExtraKeysBar(
    ctrlActive: Boolean,
    altActive: Boolean,
    onKeyClick: (TerminalExtraKey) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(color = CardBorder)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CardSurface)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TerminalExtraKey.entries.forEach { key ->
                val active = (key == TerminalExtraKey.Ctrl && ctrlActive) ||
                    (key == TerminalExtraKey.Alt && altActive)
                val shape = RoundedCornerShape(8.dp)
                val container = if (active) BrandOrange.copy(alpha = 0.16f) else TerminalBackground
                val borderColor = if (active) BrandOrange.copy(alpha = 0.65f) else CardBorder
                val textColor = if (active) BrandOrange else TextPrimary
                Box(
                    modifier = Modifier
                        .height(30.dp)
                        .clip(shape)
                        .background(container)
                        .border(0.8.dp, borderColor, shape)
                        .clickable(role = Role.Button) { onKeyClick(key) }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = key.label,
                        color = textColor,
                        fontFamily = JetBrainsMono,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun ShellExitedBanner(
    exitCode: Int,
    onRestart: () -> Unit
) {
    Surface(
        color = StatusWarning.copy(alpha = 0.12f),
        border = BorderStroke(0.8.dp, StatusWarning.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(Corners.Control)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "终端会话已退出（退出码 $exitCode）",
                color = TextPrimary,
                fontSize = TextSizes.Caption,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            LiquidButton(
                onClick = onRestart,
                backdrop = rememberSurfaceBackdrop(CardSurface),
                style = LiquidButtonStyle.Primary,
                shape = { RoundedRectangle(Corners.Tag) },
                minHeight = 32.dp,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(text = "重新启动", fontSize = TextSizes.Caption, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ShellNotReadyState(
    reason: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
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
            text = "容器尚未就绪",
            fontSize = TextSizes.Body,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.size(4.dp))
        Text(
            text = reason,
            fontSize = TextSizes.Caption,
            color = TextMuted
        )
        Spacer(modifier = Modifier.size(14.dp))
        LiquidButton(
            onClick = onRetry,
            backdrop = rememberSurfaceBackdrop(TerminalBackground),
            style = LiquidButtonStyle.Secondary,
            shape = { RoundedRectangle(Corners.Control) },
            minHeight = 36.dp,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Text(text = "重新检测", fontSize = TextSizes.Caption, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ShellPlaceholderState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
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
            text = "正在进入容器终端...",
            fontSize = TextSizes.Caption,
            color = TextMuted
        )
    }
}

private fun terminalBackgroundColorInt(isDark: Boolean): Int =
    (if (isDark) DarkMaiDroidColors else LightMaiDroidColors).terminalBackground.toArgb()

private fun showSoftKeyboard(context: Context, view: View) {
    val imm = context.getSystemService(InputMethodManager::class.java) ?: return
    view.post { imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT) }
}

private fun hideSoftKeyboard(context: Context, view: View) {
    val imm = context.getSystemService(InputMethodManager::class.java) ?: return
    imm.hideSoftInputFromWindow(view.windowToken, 0)
}
