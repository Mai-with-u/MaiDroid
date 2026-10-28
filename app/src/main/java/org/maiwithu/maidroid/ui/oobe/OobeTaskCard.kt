package org.maiwithu.maidroid.ui.oobe

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
import org.maiwithu.maidroid.oobe.OobeTask
import org.maiwithu.maidroid.oobe.OobeTaskKind
import org.maiwithu.maidroid.oobe.OobeTaskStatus
import org.maiwithu.maidroid.ui.theme.MaiDroidTheme
import org.maiwithu.maidroid.ui.theme.Orange400
import org.maiwithu.maidroid.ui.theme.SurfaceDark
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSecondary

/** 安装页的一张任务卡片：图标和说明按任务种类选，右侧显示进行中（旋转）、完成、失败图标。 */
@Composable
internal fun OobeTaskCard(task: OobeTask, modifier: Modifier = Modifier) {
    OobeTaskCard(
        iconRes = task.kind.iconRes,
        title = task.title,
        description = task.kind.description,
        status = task.status,
        modifier = modifier
    )
}

@get:DrawableRes
private val OobeTaskKind.iconRes: Int
    get() = when (this) {
        OobeTaskKind.DebianRootfs -> R.drawable.oobe_icon_debian_rootfs
        OobeTaskKind.TermuxBootstrap -> R.drawable.oobe_icon_termux_bootstrap
        OobeTaskKind.Proot -> R.drawable.oobe_icon_proot
        OobeTaskKind.Git -> R.drawable.oobe_icon_git
        OobeTaskKind.CurlCa -> R.drawable.oobe_icon_curl_ca
        OobeTaskKind.MaiBotSource -> R.drawable.oobe_icon_git
        OobeTaskKind.PythonDependencies -> R.drawable.oobe_icon_python
        OobeTaskKind.Agreement -> R.drawable.oobe_icon_curl_ca
        OobeTaskKind.WebUi -> R.drawable.oobe_icon_webui
    }

private val OobeTaskKind.description: String
    get() = when (this) {
        OobeTaskKind.DebianRootfs -> "准备 Debian 根文件系统"
        OobeTaskKind.TermuxBootstrap -> "准备基础运行环境"
        OobeTaskKind.Proot -> "准备容器启动器"
        OobeTaskKind.Git -> "安装源码下载工具"
        OobeTaskKind.CurlCa -> "配置 HTTPS 下载能力"
        OobeTaskKind.MaiBotSource -> "下载 MaiBot 源码"
        OobeTaskKind.PythonDependencies -> "安装 Python 运行依赖"
        OobeTaskKind.Agreement -> "自动确认运行协议"
        OobeTaskKind.WebUi -> "启动 MaiBot WebUI"
    }

@Composable
private fun OobeTaskCard(
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
private fun OobeTaskCardPreview() {
    MaiDroidTheme {
        Box(modifier = Modifier.padding(24.dp)) {
            OobeTaskCard(
                iconRes = R.drawable.oobe_icon_termux_bootstrap,
                title = "Termux 容器",
                description = "正在准备本地运行环境",
                status = OobeTaskStatus.Running
            )
        }
    }
}
