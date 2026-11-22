package org.maiwithu.maidroid.ui.component

import android.content.res.Configuration
import android.os.Build
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.shapes.RoundedRectangle
import org.maiwithu.maidroid.LocalHideNavigationBar
import org.maiwithu.maidroid.applySystemBars
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardSurface
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.DialogBackdropScrim
import org.maiwithu.maidroid.ui.theme.TextFaint
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes

/** 供子弹窗感知当前是否正处于手机横屏矮宽视口，以便决定是否展开详细预览或调整按钮排布。 */
internal val LocalMaiDialogPhoneLandscape = compositionLocalOf { false }

/** 标记当前正在渲染手机横屏紧凑单行底栏，供按钮行与按钮自动靠右紧排并收紧规格。 */
internal val LocalMaiDialogLandscapeBar = compositionLocalOf { false }

/** 标记手机横屏下软键盘弹出等原因导致可用高度极矮（< 240dp），此时进一步收紧顶底栏与渐隐高度给编辑区让位。 */
private val LocalMaiDialogTightLandscape = compositionLocalOf { false }

/** 弹窗内容量分档：短确认保持紧凑小窗，单列列表用中等宽度，复杂双列信息/表单用扩展宽度。 */
internal enum class MaiDialogSize {
    Compact,
    Medium,
    Expanded
}

/** 弹窗容器的响应式规格（单位均为 dp）。 */
internal data class MaiDialogLayoutSpec(
    val isPhoneLandscape: Boolean,
    val maxWidthDp: Float,
    val maxHeightDp: Float,
    val useTwoColumns: Boolean
)

/**
 * 根据安全区与软键盘扣除后的可用宽高、弹窗分档及是否存在第二列内容，
 * 计算容器最大宽高与是否启用内容双列布局。设计原则是“永远看得出这是一张浮起来的卡片”：
 * - 手机横屏（矮宽视口）：宽度按比例收敛并封顶（Compact 400 / Medium 500 / Expanded 620），
 *   左右各留出明显的背景呼吸区；高度上下各让出最多 24dp 边距并封顶 320dp，软键盘挤占高度时边距自动收窄让位给内容；
 * - 平板：按比例收敛（Expanded 封顶 640dp），宽度足够时才启用双列；
 * - 手机竖屏：保持 92% 宽度、86% 高度的成熟比例，与此前视觉一致。
 */
internal fun resolveMaiDialogLayout(
    availableWidthDp: Float,
    availableHeightDp: Float,
    size: MaiDialogSize = MaiDialogSize.Compact,
    hasSecondaryContent: Boolean = false
): MaiDialogLayoutSpec {
    val safeW = availableWidthDp.coerceAtLeast(0f)
    val safeH = availableHeightDp.coerceAtLeast(0f)
    val isPhoneLandscape = safeW > safeH && safeH < 480f && safeW >= 500f
    val isTablet = !isPhoneLandscape && safeW >= 600f && safeH >= 480f

    val maxWidthDp = when {
        isPhoneLandscape -> when (size) {
            MaiDialogSize.Compact -> minOf(safeW * 0.56f, 400f)
            MaiDialogSize.Medium -> minOf(safeW * 0.70f, 500f)
            MaiDialogSize.Expanded -> minOf(safeW * 0.82f, 620f)
        }
        isTablet -> when (size) {
            MaiDialogSize.Compact -> minOf(safeW * 0.92f, 380f)
            MaiDialogSize.Medium -> minOf(safeW * 0.70f, 520f)
            MaiDialogSize.Expanded -> minOf(safeW * 0.80f, 640f)
        }
        else -> when (size) {
            MaiDialogSize.Compact -> minOf(safeW * 0.92f, 380f)
            MaiDialogSize.Medium, MaiDialogSize.Expanded -> minOf(safeW * 0.92f, 420f)
        }
    }

    val maxHeightDp = when {
        isPhoneLandscape -> {
            val margin = landscapeVerticalMarginDp(safeH)
            minOf(safeH - margin * 2f, 320f).coerceAtLeast(0f)
        }
        isTablet -> minOf(safeH * 0.84f, 680f)
        else -> safeH * 0.86f
    }

    val useTwoColumns = hasSecondaryContent && when {
        isPhoneLandscape -> maxWidthDp >= 500f
        isTablet -> maxWidthDp >= 560f
        else -> false
    }

    return MaiDialogLayoutSpec(
        isPhoneLandscape = isPhoneLandscape,
        maxWidthDp = maxWidthDp,
        maxHeightDp = maxHeightDp,
        useTwoColumns = useTwoColumns
    )
}

/**
 * 手机横屏下弹窗上下各保留的背景边距：常规 360dp 高的横屏留满 24dp，
 * 视口被软键盘压矮时线性收窄，最低 6dp，把有限高度优先让给输入框与内容。
 */
internal fun landscapeVerticalMarginDp(availableHeightDp: Float): Float =
    ((availableHeightDp - 240f) * 0.2f).coerceIn(6f, 24f)

/**
 * 全应用统一的液态玻璃浮窗外壳：
 * - 当处于 [LocalLiquidModalHost] 内部时直接注册进同窗口模态栈，实时模糊并折射底层应用画面与导航栏，
 *   且免去独立 Dialog 窗口拉出系统栏带来的横屏闪烁问题；
 * - 弹窗本体进一步通过 `exportedBackdrop` 把自身玻璃背景暴露给内部的 `LiquidButton` 与 `LiquidToggle`；
 * - 在 Compose 预览等无宿主环境下自动回退至标准 `Dialog` 窗口与 `rememberSurfaceBackdrop`。
 */
@Composable
internal fun MaiDialog(
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconBackground: Color = BrandOrange,
    size: MaiDialogSize = MaiDialogSize.Compact,
    headerBadge: (@Composable () -> Unit)? = null,
    headerActions: (@Composable RowScope.() -> Unit)? = null,
    showCloseButton: Boolean = true,
    dismissOnBackPress: Boolean = true,
    dismissOnClickOutside: Boolean = true,
    scrollable: Boolean = true,
    footerStart: (@Composable RowScope.() -> Unit)? = null,
    landscapeFooter: (@Composable RowScope.() -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    secondaryContent: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val modalHost = LocalLiquidModalHost.current
    val landscapeWindow = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val contentInsets = if (landscapeWindow) {
        WindowInsets.displayCutout.union(WindowInsets.ime)
    } else {
        WindowInsets.safeDrawing
    }

    if (modalHost != null) {
        val latestRenderer = rememberUpdatedState<@Composable (Backdrop) -> Unit> { backdrop ->
            BackHandler(enabled = dismissOnBackPress) { onDismissRequest() }
            MaiDialogOverlaySurface(
                backdrop = backdrop,
                contentInsets = contentInsets,
                title = title,
                onDismissRequest = onDismissRequest,
                modifier = modifier,
                subtitle = subtitle,
                icon = icon,
                iconBackground = iconBackground,
                size = size,
                headerBadge = headerBadge,
                headerActions = headerActions,
                showCloseButton = showCloseButton,
                dismissOnClickOutside = dismissOnClickOutside,
                scrollable = scrollable,
                footerStart = footerStart,
                landscapeFooter = landscapeFooter,
                footer = footer,
                secondaryContent = secondaryContent,
                content = content
            )
        }
        val modalId = remember(modalHost) { modalHost.allocateId() }
        DisposableEffect(modalHost, modalId) {
            modalHost.register(modalId) { backdrop -> latestRenderer.value(backdrop) }
            onDispose { modalHost.unregister(modalId) }
        }
        SideEffect {
            modalHost.update(modalId) { backdrop -> latestRenderer.value(backdrop) }
        }
        return
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = dismissOnBackPress,
            dismissOnClickOutside = dismissOnClickOutside
        )
    ) {
        MaiDialogWindowChrome()
        val fallbackBackdrop = rememberSurfaceBackdrop(CardSurface)
        MaiDialogOverlaySurface(
            backdrop = fallbackBackdrop,
            contentInsets = contentInsets,
            title = title,
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            subtitle = subtitle,
            icon = icon,
            iconBackground = iconBackground,
            size = size,
            headerBadge = headerBadge,
            headerActions = headerActions,
            showCloseButton = showCloseButton,
            dismissOnClickOutside = dismissOnClickOutside,
            scrollable = scrollable,
            footerStart = footerStart,
            landscapeFooter = landscapeFooter,
            footer = footer,
            secondaryContent = secondaryContent,
            content = content
        )
    }
}

@Composable
private fun MaiDialogOverlaySurface(
    backdrop: Backdrop,
    contentInsets: WindowInsets,
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier,
    subtitle: String?,
    icon: ImageVector?,
    iconBackground: Color,
    size: MaiDialogSize,
    headerBadge: (@Composable () -> Unit)?,
    headerActions: (@Composable RowScope.() -> Unit)?,
    showCloseButton: Boolean,
    dismissOnClickOutside: Boolean,
    scrollable: Boolean,
    footerStart: (@Composable RowScope.() -> Unit)?,
    landscapeFooter: (@Composable RowScope.() -> Unit)?,
    footer: (@Composable ColumnScope.() -> Unit)?,
    secondaryContent: (@Composable ColumnScope.() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit
) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        appear.animateTo(1f, animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind { drawRect(DialogBackdropScrim.copy(alpha = DialogBackdropScrim.alpha * appear.value)) }
    ) {
        // 全屏点击遮罩阻断触控穿透至下方 GeckoView/列表；允许外点关闭时触发收起
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { if (dismissOnClickOutside) onDismissRequest() }
                )
        )
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(contentInsets),
            contentAlignment = Alignment.Center
        ) {
            val layoutSpec = resolveMaiDialogLayout(
                availableWidthDp = maxWidth.value,
                availableHeightDp = maxHeight.value,
                size = size,
                hasSecondaryContent = secondaryContent != null
            )
            val cardShape = { RoundedRectangle(Corners.Card) }
            val dialogSurfaceBackdrop = rememberLayerBackdrop()
            val tightLandscape = layoutSpec.isPhoneLandscape && maxHeight.value < 240f

            CompositionLocalProvider(
                LocalMaiDialogPhoneLandscape provides layoutSpec.isPhoneLandscape,
                LocalMaiDialogTightLandscape provides tightLandscape,
                LocalContentColor provides TextPrimary,
                LocalLiquidBackdrop provides dialogSurfaceBackdrop
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = layoutSpec.maxWidthDp.dp)
                        .heightIn(max = layoutSpec.maxHeightDp.dp)
                        .then(modifier)
                        .fillMaxWidth()
                        .liquidGlassDialogSurface(
                            backdrop = backdrop,
                            shape = cardShape,
                            layerBlock = {
                                val scale = 0.96f + 0.04f * appear.value
                                scaleX = scale
                                scaleY = scale
                                alpha = appear.value
                            },
                            exportedBackdrop = dialogSurfaceBackdrop
                        )
                        .pointerInput(Unit) { detectTapGestures { } }
                ) {
                    MaiDialogHeaderBar(
                        title = title,
                        subtitle = subtitle,
                        icon = icon,
                        iconBackground = iconBackground,
                        compactLandscape = layoutSpec.isPhoneLandscape,
                        headerBadge = headerBadge,
                        headerActions = headerActions,
                        showCloseButton = showCloseButton,
                        onDismissRequest = onDismissRequest
                    )

                    val hideHeaderDivider = layoutSpec.isPhoneLandscape &&
                        size == MaiDialogSize.Compact &&
                        !layoutSpec.useTwoColumns
                    if (!hideHeaderDivider) {
                        MaiDialogDivider(horizontalPadding = 0.dp)
                    }

                    if (layoutSpec.useTwoColumns && secondaryContent != null) {
                        val columnVerticalPadding = if (tightLandscape) 6.dp else 10.dp
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                                .padding(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            MaiDialogScrollColumn(
                                scrollable = scrollable,
                                contentPadding = PaddingValues(vertical = columnVerticalPadding),
                                verticalSpacing = 8.dp,
                                modifier = Modifier.weight(1.12f),
                                content = content
                            )
                            MaiDialogScrollColumn(
                                scrollable = scrollable,
                                contentPadding = PaddingValues(vertical = columnVerticalPadding),
                                verticalSpacing = 8.dp,
                                modifier = Modifier.weight(1f),
                                content = secondaryContent
                            )
                        }
                    } else {
                        val verticalPadding = when {
                            tightLandscape -> 6.dp
                            layoutSpec.isPhoneLandscape -> 12.dp
                            else -> 14.dp
                        }
                        val verticalSpacing = if (layoutSpec.isPhoneLandscape) 10.dp else 12.dp
                        MaiDialogScrollColumn(
                            scrollable = scrollable,
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = verticalPadding),
                            verticalSpacing = verticalSpacing,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                        ) {
                            content()
                            secondaryContent?.invoke(this)
                        }
                    }

                    val hasLandscapeActions = landscapeFooter != null || footer != null
                    val hasFooter = footerStart != null ||
                        (if (layoutSpec.isPhoneLandscape) hasLandscapeActions else footer != null)

                    if (hasFooter) {
                        MaiDialogFooterBar(
                            compactLandscape = layoutSpec.isPhoneLandscape,
                            footerStart = footerStart,
                            landscapeFooter = landscapeFooter,
                            footer = footer
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MaiDialogWindowChrome() {
    val view = LocalView.current
    val dialogWindow = (view.parent as? DialogWindowProvider)?.window ?: return
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val hideNavigationBar = LocalHideNavigationBar.current

    DisposableEffect(dialogWindow, landscape, hideNavigationBar) {
        dialogWindow.prepareImmersiveDialogWindow()
        applySystemBars(dialogWindow, landscape = landscape, hideNavigationBar = hideNavigationBar)

        val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (hasFocus) {
                applySystemBars(dialogWindow, landscape = landscape, hideNavigationBar = hideNavigationBar)
            }
        }
        val observer = view.viewTreeObserver
        observer.addOnWindowFocusChangeListener(focusListener)
        onDispose {
            val activeObserver = if (observer.isAlive) observer else view.viewTreeObserver
            activeObserver.removeOnWindowFocusChangeListener(focusListener)
        }
    }
}

@Suppress("DEPRECATION")
private fun Window.prepareImmersiveDialogWindow() {
    WindowCompat.setDecorFitsSystemWindows(this, false)
    clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    setDimAmount(0f)
    statusBarColor = Color.Transparent.toArgb()
    navigationBarColor = Color.Transparent.toArgb()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        isStatusBarContrastEnforced = false
        isNavigationBarContrastEnforced = false
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val cutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (attributes.layoutInDisplayCutoutMode != cutoutMode) {
            attributes = attributes.apply { layoutInDisplayCutoutMode = cutoutMode }
        }
    }
    WindowInsetsControllerCompat(this, decorView).apply {
        isAppearanceLightStatusBars = false
        isAppearanceLightNavigationBars = false
    }
}

@Composable
private fun MaiDialogHeaderBar(
    title: String,
    subtitle: String?,
    icon: ImageVector?,
    iconBackground: Color,
    compactLandscape: Boolean,
    headerBadge: (@Composable () -> Unit)?,
    headerActions: (@Composable RowScope.() -> Unit)?,
    showCloseButton: Boolean,
    onDismissRequest: () -> Unit
) {
    if (compactLandscape) {
        val tightLandscape = LocalMaiDialogTightLandscape.current
        val verticalPadding = if (tightLandscape) 6.dp else 10.dp
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 12.dp, top = verticalPadding, bottom = verticalPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                FlatFeatureIconBadge(
                    icon = icon,
                    backgroundColor = iconBackground,
                    size = 28.dp,
                    iconSize = 18.dp
                )
                Spacer(modifier = Modifier.width(10.dp))
            }

            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        color = TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 21.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = " · $subtitle",
                            color = TextMuted,
                            fontSize = TextSizes.Caption,
                            lineHeight = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
                headerBadge?.invoke()
            }

            if (headerActions != null || showCloseButton) {
                Spacer(modifier = Modifier.width(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    headerActions?.invoke(this)
                    if (showCloseButton) {
                        DialogCloseButton(size = 30.dp, iconSize = 16.dp, onClick = onDismissRequest)
                    }
                }
            }
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 14.dp, top = 16.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                FlatFeatureIconBadge(
                    icon = icon,
                    backgroundColor = iconBackground,
                    size = 36.dp,
                    iconSize = 23.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = TextPrimary,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 23.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank() || headerBadge != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!subtitle.isNullOrBlank()) {
                            Text(
                                text = subtitle,
                                color = TextMuted,
                                fontSize = TextSizes.Caption,
                                lineHeight = 15.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }
                        headerBadge?.invoke()
                    }
                }
            }

            if (headerActions != null || showCloseButton) {
                Spacer(modifier = Modifier.width(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    headerActions?.invoke(this)
                    if (showCloseButton) {
                        DialogCloseButton(size = 32.dp, iconSize = 17.dp, onClick = onDismissRequest)
                    }
                }
            }
        }
    }
}

@Composable
private fun MaiDialogFooterBar(
    compactLandscape: Boolean,
    footerStart: (@Composable RowScope.() -> Unit)?,
    landscapeFooter: (@Composable RowScope.() -> Unit)?,
    footer: (@Composable ColumnScope.() -> Unit)?
) {
    val footerTrayColor = Color.Black.copy(alpha = 0.09f)
    MaiDialogDivider(horizontalPadding = 0.dp)
    if (compactLandscape) {
        val tightLandscape = LocalMaiDialogTightLandscape.current
        val verticalPadding = if (tightLandscape) 4.dp else 6.dp
        CompositionLocalProvider(LocalMaiDialogLandscapeBar provides true) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(footerTrayColor)
                    .padding(start = 18.dp, end = 16.dp, top = verticalPadding, bottom = verticalPadding),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.Start)
                ) {
                    footerStart?.invoke(this)
                }
                if (landscapeFooter != null) {
                    Spacer(modifier = Modifier.width(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        content = landscapeFooter
                    )
                } else if (footer != null) {
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(
                        modifier = Modifier.width(IntrinsicSize.Max),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        content = footer
                    )
                }
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(footerTrayColor)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            footer?.invoke(this)
            if (footerStart != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    content = footerStart
                )
            }
        }
    }
}

/**
 * 弹窗内部可滚动列容器：
 * 改用 DstIn Alpha 渐隐羽化底边替代不透明底色盖块，确保液态玻璃底透光时滚动区边缘自然消隐。
 */
@Composable
private fun MaiDialogScrollColumn(
    scrollable: Boolean,
    contentPadding: PaddingValues,
    verticalSpacing: Dp,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val scrollState = rememberScrollState()
    val showBottomFade = scrollable && scrollState.canScrollForward
    val showScrollbar = scrollable && scrollState.maxValue > 0
    val scrollbarColor = TextFaint.copy(alpha = 0.45f)
    val fadeHeight = if (LocalMaiDialogTightLandscape.current) 10.dp else 18.dp

    val scrollMod = if (scrollable) Modifier.verticalScroll(scrollState) else Modifier
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (showBottomFade) {
                    Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                } else {
                    Modifier
                }
            )
            .drawWithContent {
                drawContent()
                if (showBottomFade && size.height > 1f) {
                    val fadePx = fadeHeight.toPx().coerceAtMost(size.height * 0.45f)
                    val startFraction = ((size.height - fadePx) / size.height).coerceIn(0f, 0.99f)
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Black,
                            startFraction to Color.Black,
                            1f to Color.Transparent
                        ),
                        blendMode = BlendMode.DstIn
                    )
                }
                if (showScrollbar && scrollState.maxValue > 0 && size.height > 16.dp.toPx()) {
                    val trackTop = 8.dp.toPx()
                    val trackBottom = size.height - 8.dp.toPx()
                    val trackHeight = (trackBottom - trackTop).coerceAtLeast(1f)
                    val totalContentHeight = size.height + scrollState.maxValue
                    val thumbHeight = (trackHeight * (size.height / totalContentHeight))
                        .coerceIn(18.dp.toPx(), trackHeight)
                    val scrollFraction = (scrollState.value.toFloat() / scrollState.maxValue.toFloat())
                        .coerceIn(0f, 1f)
                    val thumbTop = trackTop + (trackHeight - thumbHeight) * scrollFraction
                    val barWidth = 2.5.dp.toPx()
                    val barLeft = size.width - barWidth - 3.dp.toPx()
                    drawRoundRect(
                        color = scrollbarColor,
                        topLeft = Offset(barLeft, thumbTop),
                        size = Size(barWidth, thumbHeight),
                        cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                    )
                }
            }
            .then(scrollMod)
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(verticalSpacing),
        content = content
    )
}

@Composable
private fun DialogCloseButton(size: Dp, iconSize: Dp, onClick: () -> Unit) {
    LiquidGlassIconButton(
        icon = Icons.Outlined.Close,
        contentDescription = "关闭",
        onClick = onClick,
        size = size,
        iconSize = iconSize,
        tint = TextMuted
    )
}
