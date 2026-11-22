package org.maiwithu.maidroid.ui.component

import android.content.res.Configuration
import android.os.Build
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
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
import org.maiwithu.maidroid.LocalHideNavigationBar
import org.maiwithu.maidroid.applySystemBars
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardBorder
import org.maiwithu.maidroid.ui.theme.CardSurface
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.DialogBackdropScrim
import org.maiwithu.maidroid.ui.theme.DialogFooterTray
import org.maiwithu.maidroid.ui.theme.TextFaint
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes

/** 卡片外描边：顶部偏亮、底部收暗，模拟光从上方打下的细高光边。 */
private val DialogEdgeHighlight = Brush.verticalGradient(
    0f to Color.White.copy(alpha = 0.16f),
    0.45f to Color.White.copy(alpha = 0.07f),
    1f to Color.White.copy(alpha = 0.04f)
)

/** 卡片顶部极淡的一抹光泽，与描边高光呼应，让顶栏区域略微“抬起”。 */
private val DialogTopSheen = Brush.verticalGradient(
    0f to Color.White.copy(alpha = 0.035f),
    0.32f to Color.Transparent
)

/** 卡片投影高度：深色背景下靠较大的柔和投影营造悬浮感。 */
private val DialogShadowElevation = 22.dp

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
        // 横屏短确认只用一半出头的宽度，复杂双列弹窗也给左右各留 ~70dp 背景，避免铺满整屏。
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
        // 竖屏跟随剩余高度按比例收缩，避免弹出软键盘时把弹窗顶出屏幕范围。
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
 * 全应用统一的现代暗色圆角悬浮弹窗外壳：
 * - 弹窗窗口与主界面共用沉浸式系统栏策略（横屏隐藏状态栏/导航栏），关闭系统自带压暗，只保留一层半透明遮罩，
 *   背后页面轮廓隐约可见；点击卡片外部可关闭；
 * - 卡片按视口比例收敛尺寸并四周留出呼吸区，配高光细描边、顶部光泽与柔和投影，入场轻微放大淡入；
 * - 手机横屏切换为紧凑单行顶栏 + 可拆成左右独立滚动两列的内容区（附可滚渐隐与细滚动条）+ 左槽右按钮的单行底栏；
 * - 竖屏与平板保持舒展顶栏 + 独立滚动体 + 固定底栏，[secondaryContent] 自动顺接在主内容下方。
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
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = dismissOnBackPress,
            dismissOnClickOutside = dismissOnClickOutside
        )
    ) {
        // 弹窗是独立的系统窗口：先让它和主界面采用同一套沉浸式系统栏策略，横屏才不会一弹窗就掉出状态栏。
        MaiDialogWindowChrome()

        // 入场：遮罩淡入，卡片从 96% 轻微放大并淡入，约 220ms，给出“浮起”的过渡而不拖沓。
        val appear = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            appear.animateTo(1f, animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing))
        }
        val landscapeWindow = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        // 横屏系统栏已隐藏（下拉呼出时只是临时盖在上面），只需避开挖孔与软键盘，绝不再为状态栏预留空隙；
        // 竖屏状态栏常显，沿用完整安全区。
        val contentInsets = if (landscapeWindow) {
            WindowInsets.displayCutout.union(WindowInsets.ime)
        } else {
            WindowInsets.safeDrawing
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind { drawRect(DialogBackdropScrim.copy(alpha = DialogBackdropScrim.alpha * appear.value)) }
        ) {
            // 透明全屏点击层铺在安全区外部，保证横屏两侧与导航栏上方也能点空白收起弹窗。
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
                // 外围呼吸留白全部由布局规格按视口比例给出，这里不再叠加固定边距。
                val layoutSpec = resolveMaiDialogLayout(
                    availableWidthDp = maxWidth.value,
                    availableHeightDp = maxHeight.value,
                    size = size,
                    hasSecondaryContent = secondaryContent != null
                )
                val cardShape = RoundedCornerShape(Corners.Card)
                val tightLandscape = layoutSpec.isPhoneLandscape && maxHeight.value < 240f

                CompositionLocalProvider(
                    LocalMaiDialogPhoneLandscape provides layoutSpec.isPhoneLandscape,
                    LocalMaiDialogTightLandscape provides tightLandscape
                ) {
                    Surface(
                        // 宽高上限必须排在 fillMaxWidth 之前：fillMaxWidth 在前会把最小宽度锁成整个视口，
                        // 之后的 widthIn(max) 被入参约束覆盖而失效，卡片就会横向撑满整屏。
                        modifier = Modifier
                            .widthIn(max = layoutSpec.maxWidthDp.dp)
                            .heightIn(max = layoutSpec.maxHeightDp.dp)
                            .then(modifier)
                            .fillMaxWidth()
                            .graphicsLayer {
                                val scale = 0.96f + 0.04f * appear.value
                                scaleX = scale
                                scaleY = scale
                                alpha = appear.value
                            }
                            .pointerInput(Unit) { detectTapGestures { } },
                        shape = cardShape,
                        color = CardSurface,
                        contentColor = TextPrimary,
                        shadowElevation = DialogShadowElevation,
                        border = BorderStroke(1.dp, DialogEdgeHighlight)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(DialogTopSheen)
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
                                // 双列：左右各 20dp 内边距、列间 16dp，主列略宽承载核心信息，次列放折叠详情与说明；
                                // 横屏弹起软键盘时进一步收紧上下内边距，把高度让给正在编辑的输入框。
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
    }
}

/**
 * 弹窗窗口的系统栏同步：Compose 的 Dialog 会新建一个独立 Android 窗口，系统栏显隐跟随“当前获焦窗口”，
 * 不做处理时横屏一弹窗状态栏就会掉下来盖在页面上，还会把安全区往下挤。
 * 这里在首帧与窗口每次重新获焦时：
 * 窗口铺满全屏并延伸进挖孔区 -> 系统栏透明、关掉系统压暗（只留 Compose 遮罩一层）-> 按主界面同一套策略设置系统栏
 * （横屏全隐藏；竖屏状态栏常显，主页隐藏导航栏、OOBE 全显示）。只动系统栏，不碰软键盘模式，输入框焦点不受影响。
 */
@Composable
private fun MaiDialogWindowChrome() {
    val view = LocalView.current
    val dialogWindow = (view.parent as? DialogWindowProvider)?.window ?: return
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val hideNavigationBar = LocalHideNavigationBar.current

    DisposableEffect(dialogWindow, landscape, hideNavigationBar) {
        dialogWindow.prepareImmersiveDialogWindow()
        applySystemBars(dialogWindow, landscape = landscape, hideNavigationBar = hideNavigationBar)

        // 下拉临时呼出系统栏、切去别的应用再回来等情况下，窗口重新获焦时再按同一策略收一次。
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

/** 把弹窗窗口调成与主界面一致的边到边形态：透明系统栏、延伸进挖孔、无系统压暗、深色界面配浅色系统栏图标。 */
// 系统栏颜色接口在 API 35 起标记废弃（边到边强制生效后本就透明），低版本仍需显式设为透明。
@Suppress("DEPRECATION")
private fun Window.prepareImmersiveDialogWindow() {
    WindowCompat.setDecorFitsSystemWindows(this, false)
    // 系统自带的窗口压暗会与 Compose 遮罩叠成两层，把背后页面糊成纯黑，这里关掉只留一层。
    clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    setDimAmount(0f)
    statusBarColor = Color.Transparent.toArgb()
    navigationBarColor = Color.Transparent.toArgb()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // 关掉系统给三键导航栏自动加的半透明底衬，避免竖屏底部出现一条灰带。
        isStatusBarContrastEnforced = false
        isNavigationBarContrastEnforced = false
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        // 与主界面 enableEdgeToEdge 的挖孔策略保持一致，横屏遮罩才能盖住挖孔一侧的整条边。
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
        // 横屏使用单行紧凑顶栏：图标 + 标题/副标题 + 状态胶囊横排，刷新与关闭钮统一靠最右。
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
    MaiDialogDivider(horizontalPadding = 0.dp)
    if (compactLandscape) {
        val tightLandscape = LocalMaiDialogTightLandscape.current
        val verticalPadding = if (tightLandscape) 4.dp else 6.dp
        // 横屏固定单行底栏：左槽（危险文字操作或短提示）独占剩余宽度并靠左，右侧动作组按固有宽度紧贴最右。
        // 只让左槽一个元素吃权重：此前左槽与占位 Spacer 各持一份权重平分空间，左槽又不撑满，按钮就悬在了中间偏右。
        CompositionLocalProvider(LocalMaiDialogLandscapeBar provides true) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DialogFooterTray)
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
                    // 宽度收敛到子项固有宽度：即便子弹窗传入了 fillMaxWidth 的按钮，也只按文字宽度靠右，不会挤占左槽。
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
        // 竖屏/平板：动作按钮整行铺满，左槽内容（危险文字操作等）居中放在按钮下方。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(DialogFooterTray)
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
 * 当内容超出一屏时在底边叠加 20dp 柔色渐隐遮罩并在右侧绘制细圆角滚动条，给出明确的可滚动视觉线索。
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

    Box(
        modifier = modifier.drawWithContent {
            drawContent()
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
    ) {
        val scrollMod = if (scrollable) Modifier.verticalScroll(scrollState) else Modifier
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(scrollMod)
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(verticalSpacing),
            content = content
        )
        if (showBottomFade) {
            val fadeHeight = if (LocalMaiDialogTightLandscape.current) 10.dp else 20.dp
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(fadeHeight)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to CardSurface
                        )
                    )
            )
        }
    }
}

@Composable
private fun DialogCloseButton(size: Dp, iconSize: Dp, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(CardBorder)
            .clickable(role = Role.Button, onClickLabel = "关闭", onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.Close,
            contentDescription = "关闭",
            tint = TextMuted,
            modifier = Modifier.size(iconSize)
        )
    }
}
