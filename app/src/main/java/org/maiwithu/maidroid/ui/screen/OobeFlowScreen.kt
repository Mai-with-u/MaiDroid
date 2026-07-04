package org.maiwithu.maidroid.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maiwithu.maidroid.R
import org.maiwithu.maidroid.ui.component.DownloadResourceCard
import org.maiwithu.maidroid.ui.component.OobeStepIndicator
import org.maiwithu.maidroid.ui.component.PermissionCard
import org.maiwithu.maidroid.ui.theme.BackgroundDark
import org.maiwithu.maidroid.ui.theme.Orange400
import org.maiwithu.maidroid.ui.theme.Orange500
import org.maiwithu.maidroid.ui.theme.SurfaceDark
import org.maiwithu.maidroid.ui.theme.TextOnOrange
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSecondary

@Composable
fun OobeFlowScreen(
    currentStep: Int,
    onStorageAuthorize: () -> Unit = {},
    onBackgroundAuthorize: () -> Unit = {},
    onNext: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundDark)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
        ) {
            Image(
                painter = painterResource(R.drawable.oobe_mascot),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .offset(y = (-24).dp)
                .clip(RoundedCornerShape(topStart = 48.dp, topEnd = 48.dp))
                .background(BackgroundDark)
                .padding(horizontal = 24.dp)
        ) {
            Spacer(modifier = Modifier.height(36.dp))

            OobeStepIndicator(currentStep = currentStep, modifier = Modifier.fillMaxWidth())

            Spacer(modifier = Modifier.height(22.dp))

            AnimatedContent(
                targetState = currentStep,
                modifier = Modifier.fillMaxWidth(),
                transitionSpec = { oobeStepTransition() },
                label = "OobeStepHeader"
            ) { step ->
                when (step) {
                    0 -> StepHeaderContent(
                        title = "授予权限",
                        description = "MaiSaka需要以下权限才能运行~\n可选权限也可以不给，如果你确定你在做什么的话"
                    )

                    else -> StepHeaderContent(
                        title = "下载资源",
                        description = "正在自动下载运行环境和源码，请保持网络连接~\n下载完成后将自动进行安装"
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            AnimatedContent(
                targetState = currentStep,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                transitionSpec = { oobeStepTransition() },
                label = "OobeStepCards"
            ) { step ->
                when (step) {
                    0 -> PermissionCards(
                        onStorageAuthorize = onStorageAuthorize,
                        onBackgroundAuthorize = onBackgroundAuthorize
                    )

                    else -> DownloadCards()
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            AnimatedContent(
                targetState = currentStep,
                modifier = Modifier.fillMaxWidth(),
                transitionSpec = { oobeStepTransition() },
                label = "OobeStepBottom"
            ) { step ->
                when (step) {
                    0 -> NextButton(onNext = onNext)
                    else -> DownloadFooter()
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StepHeaderContent(
    title: String,
    description: String
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Orange500,
            lineHeight = 27.sp
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = description,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            lineHeight = 17.sp
        )
    }
}

@Composable
private fun PermissionCards(
    onStorageAuthorize: () -> Unit,
    onBackgroundAuthorize: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        PermissionCard(
            iconRes = R.drawable.ic_storage_hdd,
            title = "存储权限",
            description = "MaiSaka需要存储权限才能安装，运行，和保存配置文件",
            required = true,
            onAuthorize = onStorageAuthorize
        )

        Spacer(modifier = Modifier.height(20.dp))

        PermissionCard(
            iconRes = R.drawable.ic_job_run,
            title = "后台权限",
            description = "MaiSaka需要后台权限才能在切换到其他应用时保持后台运行",
            required = false,
            onAuthorize = onBackgroundAuthorize
        )

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun DownloadCards() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {

        DownloadResourceCard(
            icon = Icons.Outlined.Inventory2,
            title = "Termux 容器",
            description = "正在下载运行环境容器\nTermux-alpine-aarch64.tar.gz",
            progress = 0.62f,
            progressText = "62%",
            statusText = "187 MB / 302 MB  •  3.2 MB/s"
        )

        Spacer(modifier = Modifier.height(14.dp))

        DownloadResourceCard(
            icon = Icons.Outlined.Code,
            title = "应用源码",
            description = "正在从 GitHub 克隆仓库\nhttps://github.com/Mai-with-u/MaiBot",
            progress = 0.18f,
            progressText = "18%",
            statusText = "等待 Termux 容器完成...",
            height = 121.dp
        )

        Spacer(modifier = Modifier.height(12.dp))
    }
}

private fun androidx.compose.animation.AnimatedContentTransitionScope<Int>.oobeStepTransition() =
    if (targetState > initialState) {
        (slideInHorizontally { it / 4 } + fadeIn())
            .togetherWith(slideOutHorizontally { -it / 4 } + fadeOut())
    } else {
        (slideInHorizontally { -it / 4 } + fadeIn())
            .togetherWith(slideOutHorizontally { it / 4 } + fadeOut())
    }

@Composable
private fun NextButton(onNext: () -> Unit) {
    Button(
        onClick = onNext,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        shape = RoundedCornerShape(22.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Orange400,
            contentColor = TextOnOrange
        )
    ) {
        Text(
            text = "下一步",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            modifier = Modifier.size(32.dp)
        )
    }
}

@Composable
private fun DownloadFooter() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(SurfaceDark)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        WaitingRing()

        Spacer(modifier = Modifier.size(14.dp))

        Text(
            text = "下载完成后将自动继续，请稍后……",
            fontSize = 14.sp,
            fontWeight = FontWeight.Normal,
            color = TextSecondary,
            lineHeight = 17.sp
        )
    }
}

@Composable
private fun WaitingRing() {
    Canvas(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
    ) {
        val stroke = 2.dp.toPx()
        val arcSize = Size(size.width - stroke, size.height - stroke)
        val arcTopLeft = Offset(stroke / 2, stroke / 2)

        drawArc(
            color = Orange500,
            startAngle = -20f,
            sweepAngle = 72f,
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
        drawArc(
            color = Orange500,
            startAngle = 160f,
            sweepAngle = 72f,
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
    }
}
