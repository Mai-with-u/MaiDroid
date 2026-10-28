package org.maiwithu.maidroid.ui.oobe

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eightbitlab.com.blurview.BlurTarget
import org.maiwithu.maidroid.ui.component.BackdropBlurView
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.DouyinSans
import org.maiwithu.maidroid.ui.theme.GlassStroke
import org.maiwithu.maidroid.ui.theme.HarmonySans
import org.maiwithu.maidroid.ui.theme.SubtleGlassBlurRadius
import org.maiwithu.maidroid.ui.theme.SubtleGlassSurface
import org.maiwithu.maidroid.ui.theme.TextSizes

private const val UserAgreementUrl = "https://github.com/Mai-with-u/MaiBot/blob/main/EULA.md"
private const val PrivacyAgreementUrl = "https://github.com/Mai-with-u/MaiBot/blob/main/PRIVACY.md"

/**
 * 首次启动的授权弹窗：启动页结束后浮在 OOBE 上，背景毛玻璃模糊 OOBE 画面。
 * 同意后不再出现；点“退出”关闭应用。手机竖屏保持设计稿的固定尺寸，平板和横屏按可用空间自适应。
 */
@Composable
internal fun OobeAuthorizationDialog(
    visible: Boolean,
    blurTarget: BlurTarget?,
    onAgree: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    // 协议名做成可点的链接，点了用系统浏览器打开 MaiBot 仓库里的协议原文。
    val linkStyle = TextLinkStyles(
        style = SpanStyle(color = BrandOrange, textDecoration = TextDecoration.Underline)
    )
    val bodyText = buildAnnotatedString {
        append("使用即表示您同意并知悉")
        withLink(LinkAnnotation.Url(UserAgreementUrl, linkStyle)) { append("「用户协议」") }
        append("和")
        withLink(LinkAnnotation.Url(PrivacyAgreementUrl, linkStyle)) { append("「隐私协议」") }
        append("的相关内容。")
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(
            animationSpec = spring(
                dampingRatio = 1f,
                stiffness = Spring.StiffnessMediumLow
            )
        ) + scaleIn(
            initialScale = 0.92f,
            animationSpec = spring(
                dampingRatio = 0.78f,
                stiffness = Spring.StiffnessMediumLow
            )
        ),
        exit = fadeOut(
            animationSpec = spring(
                dampingRatio = 1f,
                stiffness = Spring.StiffnessMediumLow
            )
        ) + scaleOut(
            targetScale = 0.94f,
            animationSpec = spring(
                dampingRatio = 0.9f,
                stiffness = Spring.StiffnessMediumLow
            )
        ),
        modifier = modifier
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {}
            ),
            contentAlignment = Alignment.Center
        ) {
            val useAdaptiveLayout = maxWidth >= 600.dp || maxWidth > maxHeight

            if (useAdaptiveLayout) {
                val isShort = maxHeight < 420.dp
                val screenPadding = if (isShort) 10.dp else 24.dp

                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(screenPadding),
                    contentAlignment = Alignment.Center
                ) {
                    val fontScale = LocalDensity.current.fontScale
                    val useSideBySide = maxWidth >= 560.dp * fontScale.coerceAtMost(1.6f)
                    val cardHeightLimit = minOf(maxHeight, if (isShort) 340.dp else 420.dp)

                    AdaptiveAuthorizationCard(
                        blurTarget = blurTarget,
                        bodyText = bodyText,
                        useSideBySide = useSideBySide,
                        onAgree = onAgree,
                        onExit = onExit,
                        modifier = Modifier
                            .widthIn(max = 720.dp)
                            .fillMaxWidth()
                            .heightIn(max = cardHeightLimit)
                    )
                }
            } else {
                PhonePortraitAuthorizationCard(
                    blurTarget = blurTarget,
                    bodyText = bodyText,
                    onAgree = onAgree,
                    onExit = onExit
                )
            }
        }
    }
}

/**
 * Keep the original phone portrait composition untouched. Besides preserving the intended
 * appearance, this prevents the expanded-screen layout from subtly changing the OOBE on phones.
 */
@Composable
private fun PhonePortraitAuthorizationCard(
    blurTarget: BlurTarget?,
    bodyText: AnnotatedString,
    onAgree: () -> Unit,
    onExit: () -> Unit
) {
    val cardShape = RoundedCornerShape(Corners.Pill)

    Box(
        modifier = Modifier
            .width(326.dp)
            .height(168.dp)
            .clip(cardShape)
            .border(1.dp, GlassStroke, cardShape)
    ) {
        BackdropBlurView(
            blurTarget = blurTarget,
            blurRadius = SubtleGlassBlurRadius,
            overlayColor = SubtleGlassSurface,
            modifier = Modifier.matchParentSize()
        )

        Text(
            text = "需要授权",
            color = BrandOrange,
            fontFamily = DouyinSans,
            fontSize = TextSizes.Headline,
            fontWeight = FontWeight.Bold,
            lineHeight = 25.sp,
            modifier = Modifier
                .offset(x = 22.dp, y = 21.dp)
                .width(120.dp)
        )

        Text(
            text = bodyText,
            style = TextStyle(
                color = Color.White,
                fontFamily = HarmonySans,
                fontSize = TextSizes.BodyLarge,
                fontWeight = FontWeight.Bold,
                lineHeight = 19.sp
            ),
            modifier = Modifier
                .offset(x = 22.dp, y = 53.dp)
                .width(271.dp)
                .height(42.dp)
        )

        Row(
            modifier = Modifier
                .offset(x = 16.dp, y = 101.dp)
                .width(294.dp)
                .height(50.dp)
        ) {
            DialogActionButton(
                text = "同意",
                background = Color(0x99E97F0F),
                onClick = onAgree
            )

            DialogActionButton(
                text = "退出",
                background = Color(0x992C2C2E),
                onClick = onExit,
                modifier = Modifier.offset(x = 14.dp)
            )
        }
    }
}

@Composable
private fun AdaptiveAuthorizationCard(
    blurTarget: BlurTarget?,
    bodyText: AnnotatedString,
    useSideBySide: Boolean,
    onAgree: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardShape = RoundedCornerShape(Corners.Pill)
    val scrollState = rememberScrollState()

    Box(
        modifier = modifier
            .clip(cardShape)
            .border(1.dp, GlassStroke, cardShape)
    ) {
        BackdropBlurView(
            blurTarget = blurTarget,
            blurRadius = SubtleGlassBlurRadius,
            overlayColor = SubtleGlassSurface,
            modifier = Modifier.matchParentSize()
        )

        if (useSideBySide) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 32.dp, vertical = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(36.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AuthorizationHeading(
                    modifier = Modifier.weight(0.72f)
                )

                AuthorizationDetails(
                    bodyText = bodyText,
                    onAgree = onAgree,
                    onExit = onExit,
                    modifier = Modifier.weight(1.28f)
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 24.dp, vertical = 22.dp)
            ) {
                AuthorizationHeading()

                Spacer(modifier = Modifier.height(18.dp))

                AuthorizationDetails(
                    bodyText = bodyText,
                    onAgree = onAgree,
                    onExit = onExit
                )
            }
        }
    }
}

@Composable
private fun AuthorizationHeading(modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = "首次使用",
            color = Color.White.copy(alpha = 0.64f),
            fontFamily = HarmonySans,
            fontSize = TextSizes.Body,
            fontWeight = FontWeight.Bold,
            lineHeight = 20.sp
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "需要授权",
            color = BrandOrange,
            fontFamily = DouyinSans,
            fontSize = TextSizes.Display,
            fontWeight = FontWeight.Bold,
            lineHeight = 36.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "继续使用 MaiSaka 前，请先确认相关条款。",
            color = Color.White.copy(alpha = 0.78f),
            fontFamily = HarmonySans,
            fontSize = TextSizes.BodyLarge,
            fontWeight = FontWeight.Medium,
            lineHeight = 22.sp
        )
    }
}

@Composable
private fun AuthorizationDetails(
    bodyText: AnnotatedString,
    onAgree: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = bodyText,
            style = TextStyle(
                color = Color.White,
                fontFamily = HarmonySans,
                fontSize = TextSizes.Title,
                fontWeight = FontWeight.Bold,
                lineHeight = 25.sp
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(22.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AdaptiveDialogActionButton(
                text = "同意",
                background = Color(0xCCE97F0F),
                onClick = onAgree,
                modifier = Modifier.weight(1f)
            )

            AdaptiveDialogActionButton(
                text = "退出",
                background = Color(0xCC2C2C2E),
                onClick = onExit,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun AdaptiveDialogActionButton(
    text: String,
    background: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Corners.Card))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontFamily = DouyinSans,
            fontSize = TextSizes.TitleLarge,
            fontWeight = FontWeight.Bold,
            lineHeight = 24.sp
        )
    }
}

@Composable
private fun DialogActionButton(
    text: String,
    background: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .width(140.dp)
            .height(50.dp)
            .clip(RoundedCornerShape(Corners.Card))
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontFamily = DouyinSans,
            fontSize = TextSizes.TitleLarge,
            fontWeight = FontWeight.Bold,
            lineHeight = 24.sp
        )
    }
}
