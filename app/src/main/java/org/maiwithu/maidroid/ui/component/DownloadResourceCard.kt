package org.maiwithu.maidroid.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maiwithu.maidroid.oobe.OobeTaskStatus
import org.maiwithu.maidroid.ui.theme.MaiDroidTheme
import org.maiwithu.maidroid.ui.theme.Orange400
import org.maiwithu.maidroid.ui.theme.Orange500
import org.maiwithu.maidroid.ui.theme.SurfaceDark
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSecondary

@Composable
fun DownloadResourceCard(
    icon: ImageVector,
    title: String,
    description: String,
    progress: Float,
    progressText: String,
    statusText: String,
    modifier: Modifier = Modifier,
    height: Dp = 125.dp,
    status: OobeTaskStatus = OobeTaskStatus.Waiting
) {
    val isDone = status == OobeTaskStatus.Done
    val isRunning = status == OobeTaskStatus.Running
    val isFailed = status == OobeTaskStatus.Failed
    val clampedProgress = if (isDone) 1f else progress.coerceIn(0f, 1f)
    val cardColor = if (isDone) SurfaceDark.copy(alpha = 0.58f) else SurfaceDark
    val iconBackground = when {
        isDone -> Color.White.copy(alpha = 0.1f)
        isFailed -> Color(0xFF7A2F2F)
        isRunning -> Orange400
        else -> Color.White.copy(alpha = 0.08f)
    }
    val titleColor = if (isDone) TextSecondary else TextPrimary
    val bodyColor = if (isDone) TextSecondary.copy(alpha = 0.66f) else TextSecondary
    val accentColor = when {
        isDone -> TextSecondary
        isFailed -> Color(0xFFFF7777)
        isRunning -> Orange500
        else -> TextSecondary
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(19.dp))
            .background(cardColor)
            .padding(horizontal = 14.dp, vertical = 13.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(iconBackground),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isRunning -> CircularProgressIndicator(
                        modifier = Modifier.size(27.dp),
                        color = Color.White,
                        strokeWidth = 3.dp
                    )
                    isDone -> Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = title,
                        modifier = Modifier.size(28.dp),
                        tint = TextSecondary
                    )
                    isFailed -> Icon(
                        imageVector = Icons.Outlined.ErrorOutline,
                        contentDescription = title,
                        modifier = Modifier.size(28.dp),
                        tint = Color.White
                    )
                    else -> Icon(
                        imageVector = icon,
                        contentDescription = title,
                        modifier = Modifier.size(28.dp),
                        tint = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.size(13.dp))

            Column(verticalArrangement = Arrangement.Center) {
                Text(
                    text = title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = titleColor,
                    lineHeight = 20.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = description,
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal,
            color = bodyColor,
            lineHeight = 15.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(10.dp))

        DownloadProgressBar(progress = clampedProgress, dimmed = isDone)

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = statusText,
                modifier = Modifier.weight(1f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal,
                color = accentColor,
                lineHeight = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = progressText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = accentColor,
                textAlign = TextAlign.End,
                lineHeight = 15.sp,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun DownloadProgressBar(progress: Float, dimmed: Boolean) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        val knobSize = 8.dp
        val knobOffset = (maxWidth - knobSize) * progress
        val fillBrush = Brush.horizontalGradient(
            colors = if (dimmed) {
                listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.16f))
            } else {
                listOf(Orange400, Orange500)
            }
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.1f))
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(progress)
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(fillBrush)
        )
        Box(
            modifier = Modifier
                .offset(x = knobOffset)
                .size(knobSize)
                .clip(CircleShape)
                .background(if (dimmed) TextSecondary else Color.White)
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF121212, widthDp = 360)
@Composable
private fun DownloadResourceCardPreview() {
    MaiDroidTheme {
        Box(modifier = Modifier.padding(24.dp)) {
            DownloadResourceCard(
                icon = Icons.Outlined.Inventory2,
                title = "Termux 容器",
                description = "正在准备本地运行环境",
                progress = 0.62f,
                progressText = "进行中",
                statusText = "187 MB / 302 MB",
                status = OobeTaskStatus.Running
            )
        }
    }
}
