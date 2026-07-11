package org.maiwithu.maidroid.ui.screen

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView
import org.maiwithu.maidroid.ui.theme.DouyinSans
import org.maiwithu.maidroid.ui.theme.HarmonySans

private const val UserAgreementUrl = "https://github.com/Mai-with-u/MaiBot/blob/main/EULA.md"
private const val PrivacyAgreementUrl = "https://github.com/Mai-with-u/MaiBot/blob/main/PRIVACY.md"

@Composable
internal fun OobeAuthorizationDialog(
    visible: Boolean,
    blurTarget: BlurTarget?,
    onAgree: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uriHandler = LocalUriHandler.current
    val interactionSource = remember { MutableInteractionSource() }
    val bodyText = buildAnnotatedString {
        append("使用即表示您同意并知悉")
        pushStringAnnotation(tag = "url", annotation = UserAgreementUrl)
        withStyle(
            SpanStyle(
                color = PlatformOrange,
                textDecoration = TextDecoration.Underline
            )
        ) {
            append("「用户协议」")
        }
        pop()
        append("和")
        pushStringAnnotation(tag = "url", annotation = PrivacyAgreementUrl)
        withStyle(
            SpanStyle(
                color = PlatformOrange,
                textDecoration = TextDecoration.Underline
            )
        ) {
            append("「隐私协议」")
        }
        pop()
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
                        onBodyTextClick = { offset ->
                            bodyText
                                .getStringAnnotations(tag = "url", start = offset, end = offset)
                                .firstOrNull()
                                ?.let { uriHandler.openUri(it.item) }
                        },
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
                    onBodyTextClick = { offset ->
                        bodyText
                            .getStringAnnotations(tag = "url", start = offset, end = offset)
                            .firstOrNull()
                            ?.let { uriHandler.openUri(it.item) }
                    },
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
    onBodyTextClick: (Int) -> Unit,
    onAgree: () -> Unit,
    onExit: () -> Unit
) {
    val cardShape = RoundedCornerShape(32.dp)

    Box(
        modifier = Modifier
            .width(326.dp)
            .height(168.dp)
            .clip(cardShape)
            .border(1.dp, GlassStroke, cardShape)
    ) {
        AuthorizationBlurLayer(
            blurTarget = blurTarget,
            modifier = Modifier.matchParentSize()
        )

        Text(
            text = "需要授权",
            color = PlatformOrange,
            fontFamily = DouyinSans,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 25.sp,
            modifier = Modifier
                .offset(x = 22.dp, y = 21.dp)
                .width(120.dp)
        )

        ClickableText(
            text = bodyText,
            style = TextStyle(
                color = Color.White,
                fontFamily = HarmonySans,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 19.sp
            ),
            onClick = onBodyTextClick,
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
    onBodyTextClick: (Int) -> Unit,
    onAgree: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardShape = RoundedCornerShape(32.dp)
    val scrollState = rememberScrollState()

    Box(
        modifier = modifier
            .clip(cardShape)
            .border(1.dp, GlassStroke, cardShape)
    ) {
        AuthorizationBlurLayer(
            blurTarget = blurTarget,
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
                    onBodyTextClick = onBodyTextClick,
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
                    onBodyTextClick = onBodyTextClick,
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
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 20.sp
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "需要授权",
            color = PlatformOrange,
            fontFamily = DouyinSans,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 36.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "继续使用 MaiSaka 前，请先确认相关条款。",
            color = Color.White.copy(alpha = 0.78f),
            fontFamily = HarmonySans,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 22.sp
        )
    }
}

@Composable
private fun AuthorizationDetails(
    bodyText: AnnotatedString,
    onBodyTextClick: (Int) -> Unit,
    onAgree: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        ClickableText(
            text = bodyText,
            style = TextStyle(
                color = Color.White,
                fontFamily = HarmonySans,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 25.sp
            ),
            onClick = onBodyTextClick,
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
            .clip(RoundedCornerShape(28.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontFamily = DouyinSans,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 24.sp
        )
    }
}

@Composable
private fun AuthorizationBlurLayer(
    blurTarget: BlurTarget?,
    modifier: Modifier = Modifier
) {
    val overlayColor = SubtleGlassSurface.toArgb()

    AndroidView(
        factory = { context -> BlurView(context) },
        update = { view ->
            if (blurTarget != null && view.tag !== blurTarget) {
                view.tag = blurTarget
                view.setupWith(blurTarget, 5f, false)
                    .setBlurRadius(SubtleGlassBlurRadius)
                    .setOverlayColor(overlayColor)
                    .setBlurAutoUpdate(true)
            } else if (blurTarget != null) {
                view.setBlurEnabled(true)
                view.setBlurAutoUpdate(true)
                view.setBlurRadius(SubtleGlassBlurRadius)
                view.setOverlayColor(overlayColor)
            } else {
                view.setBlurEnabled(false)
                view.setOverlayColor(overlayColor)
            }
        },
        modifier = modifier
    )
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
            .clip(RoundedCornerShape(25.dp))
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontFamily = DouyinSans,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 24.sp
        )
    }
}
