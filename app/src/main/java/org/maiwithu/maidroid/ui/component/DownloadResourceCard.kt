package org.maiwithu.maidroid.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maiwithu.maidroid.R
import org.maiwithu.maidroid.oobe.OobeTaskStatus
import org.maiwithu.maidroid.ui.theme.MaiDroidTheme
import org.maiwithu.maidroid.ui.theme.Orange400
import org.maiwithu.maidroid.ui.theme.SurfaceDark
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSecondary

@Composable
fun DownloadResourceCard(
    @DrawableRes iconRes: Int,
    title: String,
    description: String,
    status: OobeTaskStatus,
    modifier: Modifier = Modifier,
    height: Dp = 76.dp
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(24.dp))
            .background(SurfaceDark)
            .padding(start = 12.dp, end = 26.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Orange400),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = title,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(32.dp)
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .height(52.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                lineHeight = 23.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = description,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = TextSecondary,
                lineHeight = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        TaskStatusIcon(status = status)
    }
}

@Composable
private fun TaskStatusIcon(status: OobeTaskStatus) {
    val statusIconRes = when (status) {
        OobeTaskStatus.Running -> R.drawable.oobe_status_running
        OobeTaskStatus.Done -> R.drawable.oobe_status_done
        OobeTaskStatus.Failed -> R.drawable.oobe_status_failed
        OobeTaskStatus.Waiting,
        OobeTaskStatus.Blocked -> null
    }

    if (statusIconRes == null) {
        Spacer(modifier = Modifier.size(32.dp))
        return
    }
    val rotation = if (status == OobeTaskStatus.Running) {
        val animatedRotation by rememberInfiniteTransition(
            label = "OobeCardStatusTransition"
        ).animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "OobeCardStatusRotation"
        )
        animatedRotation
    } else {
        0f
    }

    Image(
        painter = painterResource(statusIconRes),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .size(32.dp)
            .rotate(rotation)
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF121212, widthDp = 360)
@Composable
private fun DownloadResourceCardPreview() {
    MaiDroidTheme {
        Box(modifier = Modifier.padding(24.dp)) {
            DownloadResourceCard(
                iconRes = R.drawable.oobe_icon_termux_bootstrap,
                title = "Termux 容器",
                description = "正在准备本地运行环境",
                status = OobeTaskStatus.Running
            )
        }
    }
}
