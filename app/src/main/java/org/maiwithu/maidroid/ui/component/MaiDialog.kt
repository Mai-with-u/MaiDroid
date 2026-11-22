package org.maiwithu.maidroid.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measured
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ParentDataModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardBorder
import org.maiwithu.maidroid.ui.theme.CardDivider
import org.maiwithu.maidroid.ui.theme.CardSurface
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.JetBrainsMono
import org.maiwithu.maidroid.ui.theme.OnBrandOrange
import org.maiwithu.maidroid.ui.theme.PageBackground
import org.maiwithu.maidroid.ui.theme.StatusDanger
import org.maiwithu.maidroid.ui.theme.StatusWarning
import org.maiwithu.maidroid.ui.theme.TextFaint
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes
import kotlin.math.roundToInt

private val DialogSurfaceStroke = Color(0x1EFFFFFF)
private val DialogInnerSurface = PageBackground.copy(alpha = 0.56f)
private val DialogBackdropScrim = Color.Black.copy(alpha = 0.62f)

/** 供子弹窗感知当前是否正处于手机横屏矮宽视口，以便决定是否展开详细预览或调整按钮排布。 */
internal val LocalMaiDialogPhoneLandscape = compositionLocalOf { false }

/** 标记当前正在渲染手机横屏紧凑单行底栏，供按钮行与按钮自动靠右紧排并收紧规格。 */
private val LocalMaiDialogLandscapeBar = compositionLocalOf { false }

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
 * 计算容器最大宽高与是否启用内容双列布局：
 * - Compact 横竖屏保持小巧卡片，避免短确认框拉成大黑板；
 * - Medium 适度加宽，适合多行清理列表与扫码并排弹窗；
 * - Expanded 在横屏/平板上拓宽到最多 680dp，由主列与次列在内容区各自独立滚动。
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
    val isTablet = safeW >= 600f && safeH >= 480f

    val maxWidthDp = when (size) {
        MaiDialogSize.Compact -> minOf(safeW * 0.92f, 380f)
        MaiDialogSize.Medium -> if (isPhoneLandscape || isTablet) {
            minOf((safeW - 32f).coerceAtLeast(0f), 520f)
                .coerceAtLeast(minOf(safeW * 0.92f, 420f))
        } else {
            minOf(safeW * 0.92f, 420f)
        }
        MaiDialogSize.Expanded -> when {
            isPhoneLandscape -> minOf((safeW - 32f).coerceAtLeast(0f), 680f)
            isTablet -> minOf(safeW * 0.84f, 600f)
            else -> minOf(safeW * 0.92f, 420f)
        }
    }

    val maxHeightDp = when {
        // 外层 BoxWithConstraints 已留出上下 10.dp 边距，横屏不再二次扣减，尽量保留双列内容可读高度。
        isPhoneLandscape -> minOf(safeH, 348f)
        isTablet -> minOf(safeH * 0.84f, 680f)
        // 竖屏跟随剩余高度按比例收缩，避免弹出软键盘时把弹窗顶出屏幕范围。
        else -> safeH * 0.86f
    }

    val useTwoColumns = hasSecondaryContent &&
        ((isPhoneLandscape && maxWidthDp >= 500f) || (safeW >= 640f && maxWidthDp >= 540f))

    return MaiDialogLayoutSpec(
        isPhoneLandscape = isPhoneLandscape,
        maxWidthDp = maxWidthDp,
        maxHeightDp = maxHeightDp,
        useTwoColumns = useTwoColumns
    )
}

/**
 * 判定固定宽度按钮行是否需要从并排切换为垂直全宽堆叠，
 * 确保大字号或窄宽弹窗中较长动作文案绝不会挤成省略号。
 */
internal fun shouldStackDialogButtons(
    intrinsicWidthsPx: List<Int>,
    weights: List<Float>,
    spacingPx: Int,
    maxWidthPx: Int
): Boolean {
    if (intrinsicWidthsPx.size <= 1) return false
    if (maxWidthPx <= 0) return true
    val available = maxWidthPx - spacingPx.coerceAtLeast(0) * (intrinsicWidthsPx.size - 1)
    if (available <= 0) return true
    if (intrinsicWidthsPx.sum() > available) return true

    val positiveWeights = weights.map { if (it > 0f) it else 1f }
    val totalWeight = positiveWeights.sum().coerceAtLeast(1f)
    return intrinsicWidthsPx.indices.any { index ->
        val allocated = available * (positiveWeights[index] / totalWeight)
        allocated + 0.5f < intrinsicWidthsPx[index]
    }
}

/** 全应用弹窗通用的按钮视觉风格：按语义区分实色主操作、柔色调与危险操作。 */
internal enum class MaiDialogButtonStyle {
    Primary,
    Secondary,
    SoftAccent,
    Danger,
    SoftDanger
}

/**
 * 全应用统一的现代暗色圆角弹窗外壳：
 * - 全屏深色遮罩覆盖含导航栏与挖孔边缘的整个窗口，点击弹窗外部可关闭；
 * - 手机横屏切换为全宽紧凑顶栏 + 可按需拆成左右独立滚动两列的内容区（附可滚渐隐与细滚动条）+ 靠右统一单行底栏；
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(DialogBackdropScrim)
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
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .imePadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                val layoutSpec = resolveMaiDialogLayout(
                    availableWidthDp = maxWidth.value,
                    availableHeightDp = maxHeight.value,
                    size = size,
                    hasSecondaryContent = secondaryContent != null
                )

                CompositionLocalProvider(LocalMaiDialogPhoneLandscape provides layoutSpec.isPhoneLandscape) {
                    Surface(
                        modifier = modifier
                            .fillMaxWidth()
                            .widthIn(max = layoutSpec.maxWidthDp.dp)
                            .heightIn(max = layoutSpec.maxHeightDp.dp)
                            .pointerInput(Unit) { detectTapGestures { } },
                        shape = RoundedCornerShape(Corners.Card),
                        color = CardSurface,
                        contentColor = TextPrimary,
                        border = BorderStroke(1.dp, DialogSurfaceStroke)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
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
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f, fill = false)
                                        .padding(horizontal = 18.dp),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    MaiDialogScrollColumn(
                                        scrollable = scrollable,
                                        contentPadding = PaddingValues(vertical = 10.dp),
                                        verticalSpacing = 8.dp,
                                        modifier = Modifier.weight(1.12f),
                                        content = content
                                    )
                                    MaiDialogScrollColumn(
                                        scrollable = scrollable,
                                        contentPadding = PaddingValues(vertical = 10.dp),
                                        verticalSpacing = 8.dp,
                                        modifier = Modifier.weight(1f),
                                        content = secondaryContent
                                    )
                                }
                            } else {
                                val verticalPadding = if (layoutSpec.isPhoneLandscape) 12.dp else 14.dp
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
        // 横屏使用单行紧凑顶栏：图标 + 标题/副标题 + 状态胶囊横排，刷新与关闭钮统一靠最右。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
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
                            fontSize = 12.sp,
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
        // 横屏固定单行底栏：左侧放置危险操作或短提示，右侧紧凑靠右对齐，省出垂直空间给内容区。
        CompositionLocalProvider(LocalMaiDialogLandscapeBar provides true) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (footerStart != null) {
                    Row(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        content = footerStart
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                if (landscapeFooter != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        content = landscapeFooter
                    )
                } else if (footer != null) {
                    Column(
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
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(20.dp)
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

/** 弹窗内部细分隔线。 */
@Composable
internal fun MaiDialogDivider(horizontalPadding: Dp = 14.dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding)
            .height(0.5.dp)
            .background(CardDivider)
    )
}

/** 弹窗内的暗色内嵌分组卡片，用于集中承载版本号、状态明细或表单项。 */
@Composable
internal fun MaiDialogSectionCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(14.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(8.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corners.Panel))
            .background(DialogInnerSurface)
            .border(0.8.dp, CardDivider, RoundedCornerShape(Corners.Panel))
            .padding(contentPadding),
        verticalArrangement = verticalArrangement,
        content = content
    )
}

/** 带语义色调微描边的提示卡：用于安全风险提示、前置注意或收集出错说明。 */
@Composable
internal fun MaiDialogCallout(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    accentColor: Color = StatusWarning,
    trailingHeader: (@Composable () -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null
) {
    val shape = RoundedCornerShape(Corners.Panel)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(accentColor.copy(alpha = 0.09f))
            .border(0.8.dp, accentColor.copy(alpha = 0.25f), shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                color = accentColor,
                fontSize = TextSizes.Body,
                fontWeight = FontWeight.Bold,
                lineHeight = 18.sp,
                modifier = Modifier.weight(1f)
            )
            trailingHeader?.invoke()
        }
        if (body.isNotBlank()) {
            Text(
                text = body,
                color = TextPrimary.copy(alpha = 0.88f),
                fontSize = TextSizes.Caption,
                lineHeight = 17.sp
            )
        }
        footer?.invoke(this)
    }
}

/** 单级动画折叠面板：将长诊断报告、说明详情等次要内容默认收起，保持弹窗首屏简洁。 */
@Composable
internal fun MaiDialogCollapsible(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by rememberSaveable(initiallyExpanded) { mutableStateOf(initiallyExpanded) }
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 170, easing = FastOutSlowInEasing),
        label = "MaiDialogCollapseArrow"
    )
    val shape = RoundedCornerShape(Corners.Panel)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(DialogInnerSurface)
            .border(0.8.dp, CardDivider, shape)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = TextPrimary,
                fontSize = TextSizes.Body,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            if (!expanded && !summary.isNullOrBlank()) {
                Text(
                    text = summary,
                    color = TextMuted,
                    fontSize = TextSizes.Caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowDown,
                contentDescription = if (expanded) "收起 $title" else "展开 $title",
                tint = TextMuted,
                modifier = Modifier
                    .size(19.dp)
                    .graphicsLayer { rotationZ = arrowRotation }
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(150)) + expandVertically(tween(180, easing = FastOutSlowInEasing)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(160, easing = FastOutSlowInEasing))
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                MaiDialogDivider()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = content
                )
            }
        }
    }
}

/** 弹窗内统一规格的圆角输入框，支持错误态、占位提示与密码明文切换；横屏适当缩减垂直边距省出高度。 */
@Composable
internal fun MaiDialogTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    isPassword: Boolean = false,
    errorText: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default
) {
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    val compactLandscape = LocalMaiDialogPhoneLandscape.current
    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (compactLandscape) Modifier.heightIn(min = 48.dp) else Modifier),
            singleLine = singleLine,
            isError = errorText != null,
            label = { Text(text = label) },
            placeholder = placeholder?.let { hint -> { Text(text = hint, color = TextFaint) } },
            visualTransformation = if (isPassword && !passwordVisible) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            trailingIcon = if (isPassword) {
                {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (passwordVisible) "隐藏$label" else "显示$label",
                            tint = TextMuted,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            } else {
                null
            },
            keyboardOptions = keyboardOptions,
            shape = RoundedCornerShape(Corners.Control),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = BrandOrange,
                focusedBorderColor = BrandOrange,
                unfocusedBorderColor = CardBorder,
                errorBorderColor = StatusDanger,
                focusedLabelColor = BrandOrange,
                unfocusedLabelColor = TextMuted,
                errorLabelColor = StatusDanger,
                focusedContainerColor = PageBackground,
                unfocusedContainerColor = PageBackground,
                errorContainerColor = PageBackground
            )
        )
        if (!errorText.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = errorText,
                color = StatusDanger,
                fontSize = TextSizes.Caption,
                lineHeight = 15.sp
            )
        }
    }
}

/** 弹窗内的小巧信息/错误码胶囊，可选单行等宽字体与点击复制响应。 */
@Composable
internal fun MaiDialogInfoChip(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = TextMuted,
    containerColor: Color = color.copy(alpha = 0.14f),
    monospace: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val clickMod = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Text(
        text = text,
        color = color,
        fontFamily = if (monospace) JetBrainsMono else null,
        fontSize = TextSizes.Caption,
        fontWeight = FontWeight.Bold,
        lineHeight = 15.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(RoundedCornerShape(Corners.Tag))
            .background(containerColor)
            .then(clickMod)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

/** 弹窗动作按钮：竖屏饱满等高，横屏底栏下按内容定宽且不小于 84dp、高 38dp，保持靠右紧凑利落。 */
@Composable
internal fun MaiDialogButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: MaiDialogButtonStyle = MaiDialogButtonStyle.Primary,
    enabled: Boolean = true
) {
    val compactBar = LocalMaiDialogLandscapeBar.current
    val (container, content) = when (style) {
        MaiDialogButtonStyle.Primary -> BrandOrange to OnBrandOrange
        MaiDialogButtonStyle.Secondary -> CardBorder to TextPrimary
        MaiDialogButtonStyle.SoftAccent -> BrandOrange.copy(alpha = 0.18f) to BrandOrange
        MaiDialogButtonStyle.Danger -> StatusDanger to TextPrimary
        MaiDialogButtonStyle.SoftDanger -> StatusDanger.copy(alpha = 0.14f) to StatusDanger
    }
    val minHeight = if (compactBar) 38.dp else 44.dp
    val cornerRadius = if (compactBar) 11.dp else 14.dp
    val contentPadding = if (compactBar) {
        PaddingValues(horizontal = 14.dp, vertical = 7.dp)
    } else {
        PaddingValues(horizontal = 14.dp, vertical = 10.dp)
    }
    val labelSize = if (compactBar) 13.sp else TextSizes.Body

    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .then(if (compactBar) Modifier.defaultMinSize(minWidth = 84.dp, minHeight = minHeight) else Modifier.heightIn(min = minHeight)),
        shape = RoundedCornerShape(cornerRadius),
        contentPadding = contentPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = CardBorder.copy(alpha = 0.55f),
            disabledContentColor = TextFaint
        )
    ) {
        Text(
            text = text,
            fontSize = labelSize,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private class DialogButtonWeightModifier(val weight: Float) : ModifierNodeElement<DialogButtonWeightNode>() {
    override fun create(): DialogButtonWeightNode = DialogButtonWeightNode(weight)

    override fun update(node: DialogButtonWeightNode) {
        node.weight = weight
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "dialogButtonWeight"
        value = weight
    }

    override fun equals(other: Any?): Boolean = other is DialogButtonWeightModifier && other.weight == weight

    override fun hashCode(): Int = weight.hashCode()
}

private class DialogButtonWeightNode(var weight: Float) : Modifier.Node(), ParentDataModifierNode {
    override fun Density.modifyParentData(parentData: Any?): Any = weight
}

private object MaiDialogButtonRowScope : RowScope {
    override fun Modifier.weight(weight: Float, fill: Boolean): Modifier =
        this.then(DialogButtonWeightModifier(weight.coerceAtLeast(0.01f)))

    override fun Modifier.align(alignment: Alignment.Vertical): Modifier = this
    override fun Modifier.alignBy(alignmentLine: HorizontalAlignmentLine): Modifier = this
    override fun Modifier.alignByBaseline(): Modifier = this
    override fun Modifier.alignBy(alignmentLineBlock: (Measured) -> Int): Modifier = this
}

/**
 * 动作按钮行：
 * - 在横屏单行底栏中自动紧排并靠右对齐（不拉伸占满整宽），避免抢占左侧状态/提示区空间；
 * - 在竖屏或平板底栏中按权重铺满整行，放不下固有宽度时自动转成全宽垂直堆叠防止省略号截断。
 */
@Composable
internal fun MaiDialogButtonRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    val compactBar = LocalMaiDialogLandscapeBar.current
    val spacingDp = if (compactBar) 8.dp else 10.dp
    val rowModifier = if (compactBar) modifier else modifier.fillMaxWidth()

    Layout(
        content = { MaiDialogButtonRowScope.content() },
        modifier = rowModifier
    ) { measurables, constraints ->
        if (measurables.isEmpty()) {
            return@Layout layout(0, 0) {}
        }
        val spacingPx = spacingDp.roundToPx()
        val maxW = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE

        if (compactBar) {
            val childMax = if (maxW == Int.MAX_VALUE) Constraints.Infinity else maxW
            val naturalPlaceables = measurables.map {
                it.measure(
                    Constraints(
                        minWidth = 0,
                        maxWidth = childMax,
                        minHeight = 0,
                        maxHeight = constraints.maxHeight
                    )
                )
            }
            val naturalTotal = naturalPlaceables.sumOf { it.width } + spacingPx * (naturalPlaceables.size - 1)
            if (naturalTotal <= maxW) {
                val rowHeight = naturalPlaceables.maxOfOrNull { it.height } ?: 0
                return@Layout layout(naturalTotal.coerceAtLeast(0), rowHeight) {
                    var x = 0
                    naturalPlaceables.forEach { placeable ->
                        val y = (rowHeight - placeable.height) / 2
                        placeable.placeRelative(x, y)
                        x += placeable.width + spacingPx
                    }
                }
            }
        }

        val boundedMaxW = if (constraints.hasBoundedWidth) constraints.maxWidth else 0
        val weights = measurables.map { (it.parentData as? Float) ?: 1f }
        val intrinsicWidths = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity) }
        val stackVertically = shouldStackDialogButtons(
            intrinsicWidthsPx = intrinsicWidths,
            weights = weights,
            spacingPx = spacingPx,
            maxWidthPx = boundedMaxW
        )

        if (stackVertically) {
            val itemConstraints = Constraints(
                minWidth = boundedMaxW.coerceAtLeast(0),
                maxWidth = boundedMaxW.coerceAtLeast(0),
                minHeight = 0,
                maxHeight = constraints.maxHeight
            )
            val placeables = measurables.map { it.measure(itemConstraints) }
            val totalH = placeables.sumOf { it.height } + spacingPx * (placeables.size - 1)
            val finalH = totalH.coerceAtMost(constraints.maxHeight)
            layout(boundedMaxW, finalH) {
                var y = 0
                placeables.forEach { placeable ->
                    placeable.placeRelative(0, y)
                    y += placeable.height + spacingPx
                }
            }
        } else {
            val availableW = (boundedMaxW - spacingPx * (measurables.size - 1)).coerceAtLeast(0)
            val totalWeight = weights.sum().coerceAtLeast(1f)
            var remainingW = availableW
            val placeables = measurables.mapIndexed { index, measurable ->
                val itemW = if (index == measurables.lastIndex) {
                    remainingW
                } else {
                    (availableW * (weights[index] / totalWeight)).roundToInt().coerceIn(0, remainingW)
                }
                remainingW -= itemW
                measurable.measure(
                    Constraints(
                        minWidth = itemW,
                        maxWidth = itemW,
                        minHeight = 0,
                        maxHeight = constraints.maxHeight
                    )
                )
            }
            val rowHeight = placeables.maxOfOrNull { it.height } ?: 0
            layout(boundedMaxW, rowHeight) {
                var x = 0
                placeables.forEach { placeable ->
                    val y = (rowHeight - placeable.height) / 2
                    placeable.placeRelative(x, y)
                    x += placeable.width + spacingPx
                }
            }
        }
    }
}
