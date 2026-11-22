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
private val SplitLeftPaneBackdrop = PageBackground.copy(alpha = 0.24f)

private val LocalMaiDialogLandscapeSplit = compositionLocalOf { false }

/** 弹窗自适应形态：竖屏单栏、手机横屏左右分栏、平板宽屏居中扩展。 */
internal enum class MaiDialogLayoutMode {
    Portrait,
    LandscapeSplit,
    Expanded
}

/** 弹窗外框及横屏左栏尺寸规格（单位为 dp）。 */
internal data class MaiDialogLayoutSpec(
    val mode: MaiDialogLayoutMode,
    val maxWidthDp: Float,
    val maxHeightDp: Float,
    val leftPaneWidthDp: Float = 0f
)

/** 根据安全区与软键盘扣除后的可用宽高，决定弹窗采用竖屏单栏、横屏左右双栏或平板宽屏排版。 */
internal fun resolveMaiDialogLayout(availableWidthDp: Float, availableHeightDp: Float): MaiDialogLayoutSpec {
    val safeW = availableWidthDp.coerceAtLeast(0f)
    val safeH = availableHeightDp.coerceAtLeast(0f)
    return when {
        // 手机横屏高矮较局促（高 < 480dp 且明显宽于高）：放宽弹窗总宽并拆成左控制栏 + 右滚动区，避免上下挤成窄缝。
        safeW >= 500f && safeH < 480f && safeW > safeH * 1.15f -> {
            val width = (safeW * 0.94f).coerceAtMost(660f)
            val height = (safeH * 0.94f).coerceAtMost(380f)
            val leftPane = (width * 0.36f).coerceIn(208f, 244f)
            MaiDialogLayoutSpec(
                mode = MaiDialogLayoutMode.LandscapeSplit,
                maxWidthDp = width,
                maxHeightDp = height,
                leftPaneWidthDp = leftPane
            )
        }
        // 平板等大屏设备在垂直方向同样舒展时保持单栏流式居中，并将上限拓宽到 520dp。
        safeW >= 600f && safeH >= 480f -> MaiDialogLayoutSpec(
            mode = MaiDialogLayoutMode.Expanded,
            maxWidthDp = (safeW * 0.80f).coerceIn(440f, 520f),
            maxHeightDp = (safeH * 0.84f).coerceAtMost(680f)
        )
        else -> MaiDialogLayoutSpec(
            mode = MaiDialogLayoutMode.Portrait,
            maxWidthDp = (safeW * 0.92f).coerceAtMost(420f),
            // 取消保底最小高度限制，防止软键盘弹起后将弹窗撑出剩余屏幕范围。
            maxHeightDp = safeH * 0.86f
        )
    }
}

/**
 * 判定底部动作按钮行在当前宽度与权重下是否应切换为垂直堆叠，
 * 确保横屏窄栏或大字号下不会把较长文案挤成省略号。
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
 * 固定满屏容器承载安全区避让与键盘避让，消除内容折叠或状态切换导致的 WindowManager 抖动重排；
 * 在竖屏与平板上采用“顶栏 + 滚动体 + 底栏”，在手机横屏自动切换为“左栏信息/按钮 + 右侧全高内容区”双栏排版。
 */
@Composable
internal fun MaiDialog(
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconBackground: Color = BrandOrange,
    headerBadge: (@Composable () -> Unit)? = null,
    showCloseButton: Boolean = true,
    dismissOnBackPress: Boolean = true,
    dismissOnClickOutside: Boolean = true,
    scrollable: Boolean = true,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = dismissOnBackPress,
            dismissOnClickOutside = dismissOnClickOutside
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 透明全屏点击层放在安全区缩进之外，确保横屏两侧刘海安全区边缘同样支持点击关闭。
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
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                val layoutSpec = resolveMaiDialogLayout(maxWidth.value, maxHeight.value)
                val surfaceSizeModifier = if (layoutSpec.mode == MaiDialogLayoutMode.LandscapeSplit) {
                    Modifier
                        .width(layoutSpec.maxWidthDp.dp)
                        .heightIn(max = layoutSpec.maxHeightDp.dp)
                } else {
                    Modifier
                        .fillMaxWidth()
                        .widthIn(max = layoutSpec.maxWidthDp.dp)
                        .heightIn(max = layoutSpec.maxHeightDp.dp)
                }
                Surface(
                    modifier = modifier
                        .then(surfaceSizeModifier)
                        .pointerInput(Unit) { detectTapGestures { } },
                    shape = RoundedCornerShape(Corners.Card),
                    color = CardSurface,
                    contentColor = TextPrimary,
                    border = BorderStroke(1.dp, DialogSurfaceStroke)
                ) {
                    if (layoutSpec.mode == MaiDialogLayoutMode.LandscapeSplit) {
                        SplitDialogBodyLayout(
                            leftPaneWidth = layoutSpec.leftPaneWidthDp.dp,
                            title = title,
                            subtitle = subtitle,
                            icon = icon,
                            iconBackground = iconBackground,
                            headerBadge = headerBadge,
                            showCloseButton = showCloseButton,
                            onDismissRequest = onDismissRequest,
                            scrollable = scrollable,
                            footer = footer,
                            content = content
                        )
                    } else {
                        PortraitDialogBodyLayout(
                            title = title,
                            subtitle = subtitle,
                            icon = icon,
                            iconBackground = iconBackground,
                            headerBadge = headerBadge,
                            showCloseButton = showCloseButton,
                            onDismissRequest = onDismissRequest,
                            scrollable = scrollable,
                            footer = footer,
                            content = content
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PortraitDialogBodyLayout(
    title: String,
    subtitle: String?,
    icon: ImageVector?,
    iconBackground: Color,
    headerBadge: (@Composable () -> Unit)?,
    showCloseButton: Boolean,
    onDismissRequest: () -> Unit,
    scrollable: Boolean,
    footer: (@Composable ColumnScope.() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
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

            if (showCloseButton) {
                Spacer(modifier = Modifier.width(8.dp))
                DialogCloseButton(size = 32.dp, iconSize = 17.dp, onClick = onDismissRequest)
            }
        }

        MaiDialogDivider(horizontalPadding = 0.dp)

        val bodyScroll = if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .then(bodyScroll)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )

        if (footer != null) {
            MaiDialogDivider(horizontalPadding = 0.dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = footer
            )
        }
    }
}

/**
 * 手机横屏双栏容器：用自定义 Layout 协调左栏（微暗背景 + 头部信息 + 沉底动作按钮）与右侧全高内容区，
 * 避免使用 IntrinsicSize 与子项 BoxWithConstraints（如二维码自适应排版）产生测量冲突。
 */
@Composable
private fun SplitDialogBodyLayout(
    leftPaneWidth: Dp,
    title: String,
    subtitle: String?,
    icon: ImageVector?,
    iconBackground: Color,
    headerBadge: (@Composable () -> Unit)?,
    showCloseButton: Boolean,
    onDismissRequest: () -> Unit,
    scrollable: Boolean,
    footer: (@Composable ColumnScope.() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit
) {
    CompositionLocalProvider(LocalMaiDialogLandscapeSplit provides true) {
        Layout(
            content = {
                Box(modifier = Modifier.background(SplitLeftPaneBackdrop))

                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (icon != null || showCloseButton) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (icon != null) {
                                FlatFeatureIconBadge(
                                    icon = icon,
                                    backgroundColor = iconBackground,
                                    size = 34.dp,
                                    iconSize = 22.dp
                                )
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            if (showCloseButton) {
                                DialogCloseButton(size = 30.dp, iconSize = 16.dp, onClick = onDismissRequest)
                            }
                        }
                    }

                    Text(
                        text = title,
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 22.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            color = TextMuted,
                            fontSize = TextSizes.Caption,
                            lineHeight = 15.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    headerBadge?.invoke()
                }

                if (footer != null) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        MaiDialogDivider(horizontalPadding = 0.dp)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            content = footer
                        )
                    }
                } else {
                    Box(modifier = Modifier)
                }

                Box(modifier = Modifier.background(CardDivider))

                val bodyScroll = if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier
                Column(
                    modifier = Modifier
                        .then(bodyScroll)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = content
                )
            }
        ) { measurables, constraints ->
            val maxW = constraints.maxWidth.coerceAtLeast(3)
            val maxH = constraints.maxHeight.coerceAtLeast(1)
            val leftW = leftPaneWidth.roundToPx().coerceIn(1, maxW / 2)
            val divW = 0.5.dp.roundToPx().coerceAtLeast(1)
            val rightW = (maxW - leftW - divW).coerceAtLeast(1)

            val footerPlaceable = measurables[2].measure(
                Constraints(minWidth = leftW, maxWidth = leftW, minHeight = 0, maxHeight = maxH)
            )
            val headerMaxH = (maxH - footerPlaceable.height).coerceAtLeast(0)
            val headerPlaceable = measurables[1].measure(
                Constraints(minWidth = leftW, maxWidth = leftW, minHeight = 0, maxHeight = headerMaxH)
            )
            val rightPlaceable = measurables[4].measure(
                Constraints(minWidth = rightW, maxWidth = rightW, minHeight = 0, maxHeight = maxH)
            )

            val finalH = maxOf(rightPlaceable.height, headerPlaceable.height + footerPlaceable.height)
                .coerceIn(1, maxH)
            val backdropPlaceable = measurables[0].measure(Constraints.fixed(leftW, finalH))
            val dividerPlaceable = measurables[3].measure(Constraints.fixed(divW, finalH))

            layout(leftW + divW + rightW, finalH) {
                backdropPlaceable.placeRelative(0, 0)
                headerPlaceable.placeRelative(0, 0)
                footerPlaceable.placeRelative(0, finalH - footerPlaceable.height)
                dividerPlaceable.placeRelative(leftW, 0)
                rightPlaceable.placeRelative(leftW + divW, 0)
            }
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
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
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

/** 弹窗内统一规格的圆角输入框，支持错误态、占位提示与密码明文切换。 */
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
    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
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

/** 弹窗底栏动作按钮：横屏双栏左栏中自动收紧高度、圆角与字号以适应窄栏宽度。 */
@Composable
internal fun MaiDialogButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: MaiDialogButtonStyle = MaiDialogButtonStyle.Primary,
    enabled: Boolean = true
) {
    val compactSplit = LocalMaiDialogLandscapeSplit.current
    val (container, content) = when (style) {
        MaiDialogButtonStyle.Primary -> BrandOrange to OnBrandOrange
        MaiDialogButtonStyle.Secondary -> CardBorder to TextPrimary
        MaiDialogButtonStyle.SoftAccent -> BrandOrange.copy(alpha = 0.18f) to BrandOrange
        MaiDialogButtonStyle.Danger -> StatusDanger to TextPrimary
        MaiDialogButtonStyle.SoftDanger -> StatusDanger.copy(alpha = 0.14f) to StatusDanger
    }
    val minHeight = if (compactSplit) 38.dp else 44.dp
    val cornerRadius = if (compactSplit) 12.dp else 14.dp
    val contentPadding = if (compactSplit) {
        PaddingValues(horizontal = 10.dp, vertical = 7.dp)
    } else {
        PaddingValues(horizontal = 14.dp, vertical = 10.dp)
    }
    val labelSize = if (compactSplit) 13.sp else TextSizes.Body

    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = minHeight),
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
 * 自适应动作按钮行：宽屏或短标签时按权重水平并排；在横屏窄栏等放不下全部按钮固有宽度时自动转为竖向全宽堆叠，
 * 确保任意按钮文字都不会被强行裁切或挤出省略号。
 */
@Composable
internal fun MaiDialogButtonRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    val spacingDp = if (LocalMaiDialogLandscapeSplit.current) 8.dp else 10.dp
    Layout(
        content = { MaiDialogButtonRowScope.content() },
        modifier = modifier.fillMaxWidth()
    ) { measurables, constraints ->
        if (measurables.isEmpty()) {
            return@Layout layout(0, 0) {}
        }
        val spacingPx = spacingDp.roundToPx()
        val maxW = if (constraints.hasBoundedWidth) constraints.maxWidth else 0
        val weights = measurables.map { (it.parentData as? Float) ?: 1f }
        val intrinsicWidths = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity) }
        val stackVertically = shouldStackDialogButtons(
            intrinsicWidthsPx = intrinsicWidths,
            weights = weights,
            spacingPx = spacingPx,
            maxWidthPx = maxW
        )

        if (stackVertically) {
            val itemConstraints = Constraints(
                minWidth = maxW.coerceAtLeast(0),
                maxWidth = maxW.coerceAtLeast(0),
                minHeight = 0,
                maxHeight = constraints.maxHeight
            )
            val placeables = measurables.map { it.measure(itemConstraints) }
            val totalH = placeables.sumOf { it.height } + spacingPx * (placeables.size - 1)
            val finalH = totalH.coerceAtMost(constraints.maxHeight)
            layout(maxW, finalH) {
                var y = 0
                placeables.forEach { placeable ->
                    placeable.placeRelative(0, y)
                    y += placeable.height + spacingPx
                }
            }
        } else {
            val availableW = (maxW - spacingPx * (measurables.size - 1)).coerceAtLeast(0)
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
            layout(maxW, rowHeight) {
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
