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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {}
                ),
            contentAlignment = Alignment.Center
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
                    onClick = { offset ->
                        bodyText
                            .getStringAnnotations(tag = "url", start = offset, end = offset)
                            .firstOrNull()
                            ?.let { uriHandler.openUri(it.item) }
                    },
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
