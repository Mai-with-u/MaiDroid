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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
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

private val DialogSurfaceStroke = Color(0x1EFFFFFF)
private val DialogInnerSurface = PageBackground.copy(alpha = 0.56f)

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
 * 固定满屏容器承载键盘避让，消除内容折叠或状态切换导致的 WindowManager 抖动重排；
 * 由固定顶栏（图标、标题、副标题/徽章、关闭钮）+ 自适应滚动内容体 + 固定底栏组成。
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
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentAlignment = Alignment.Center
        ) {
            // 透明全屏点击层仅用于点卡片外部关闭，避免无障碍树和按压反馈干扰。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { if (dismissOnClickOutside) onDismissRequest() }
                    )
            )
            val maxDialogHeight = (maxHeight * 0.86f).coerceAtLeast(300.dp)
            Surface(
                modifier = modifier
                    .fillMaxWidth(0.92f)
                    .widthIn(max = 420.dp)
                    .heightIn(max = maxDialogHeight)
                    .pointerInput(Unit) { detectTapGestures { } },
                shape = RoundedCornerShape(Corners.Card),
                color = CardSurface,
                contentColor = TextPrimary,
                border = BorderStroke(1.dp, DialogSurfaceStroke)
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
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(CardBorder)
                                    .clickable(role = Role.Button, onClickLabel = "关闭", onClick = onDismissRequest),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = "关闭",
                                    tint = TextMuted,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
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
        }
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

/** 弹窗底栏统一的 44dp 动作按钮，单行显示、统一圆角与禁用态度。 */
@Composable
internal fun MaiDialogButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: MaiDialogButtonStyle = MaiDialogButtonStyle.Primary,
    enabled: Boolean = true
) {
    val (container, content) = when (style) {
        MaiDialogButtonStyle.Primary -> BrandOrange to OnBrandOrange
        MaiDialogButtonStyle.Secondary -> CardBorder to TextPrimary
        MaiDialogButtonStyle.SoftAccent -> BrandOrange.copy(alpha = 0.18f) to BrandOrange
        MaiDialogButtonStyle.Danger -> StatusDanger to TextPrimary
        MaiDialogButtonStyle.SoftDanger -> StatusDanger.copy(alpha = 0.14f) to StatusDanger
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 44.dp),
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = CardBorder.copy(alpha = 0.55f),
            disabledContentColor = TextFaint
        )
    ) {
        Text(
            text = text,
            fontSize = TextSizes.Body,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 等宽并排动作按钮行，用于取消/确认或两枚次要操作对齐。 */
@Composable
internal fun MaiDialogButtonRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}
