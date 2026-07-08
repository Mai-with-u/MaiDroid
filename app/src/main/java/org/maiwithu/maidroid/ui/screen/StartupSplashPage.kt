package org.maiwithu.maidroid.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView
import org.maiwithu.maidroid.R
import org.maiwithu.maidroid.ui.theme.DouyinSans
import org.maiwithu.maidroid.ui.theme.HarmonySans

@Composable
internal fun StartupSplashPage(modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier = modifier
            .background(Color(0xFF1C1C1E))
            .clip(RoundedCornerShape(48.dp))
    ) {
        val sx = maxWidth.value / HomeDesignWidth
        val sy = maxHeight.value / HomeDesignHeight
        val textScale = minOf(sx, sy)
        fun x(value: Float): Dp = (value * sx).dp
        fun y(value: Float): Dp = (value * sy).dp
        fun d(value: Float): Dp = (value * textScale).dp
        fun sp(value: Float) = (value * textScale).sp
        var blurTarget by remember { mutableStateOf<BlurTarget?>(null) }

        BlurTargetHost(
            onTargetChanged = { blurTarget = it },
            modifier = Modifier.matchParentSize()
        ) {
            Image(
                painter = painterResource(R.drawable.startup_splash_background),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .offset(x = x(-6f), y = y(0f))
                    .width(x(424f))
                    .height(y(917f))
            )
        }

        val infoShape = RoundedCornerShape(d(24f))

        Box(
            modifier = Modifier
                .offset(x = x(49f), y = y(773f))
                .clip(infoShape)
                .border(
                    width = 1.dp,
                    color = Color(0x542B2B2B),
                    shape = infoShape
                )
        ) {
            StartupInfoBlurLayer(
                blurTarget = blurTarget,
                modifier = Modifier.matchParentSize()
            )

            Row(
                modifier = Modifier
                    .padding(d(16f)),
                horizontalArrangement = Arrangement.spacedBy(d(16f)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(R.drawable.startup_splash_app_icon),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(d(64f))
                        .clip(RoundedCornerShape(d(16f)))
                )

                Column(
                    modifier = Modifier
                        .width(x(202f))
                        .height(y(43f))
                ) {
                    Text(
                        text = "MaiDroid × MaiSaka",
                        color = Color.White,
                        fontFamily = DouyinSans,
                        fontSize = sp(20f),
                        fontWeight = FontWeight.Bold,
                        lineHeight = sp(21f),
                        maxLines = 1,
                        modifier = Modifier.width(x(202f))
                    )

                    Text(
                        text = "一个正在努力成为人类的BOT",
                        color = Color.White,
                        fontFamily = HarmonySans,
                        fontSize = sp(12f),
                        fontWeight = FontWeight.Medium,
                        lineHeight = sp(13f),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier
                            .offset(y = y(9f))
                            .width(x(200f))
                    )
                }
            }
        }
    }
}

@Composable
private fun StartupInfoBlurLayer(
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
