package org.maiwithu.maidroid.ui.settings

import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import org.maiwithu.maidroid.service.SafeExitService
import org.maiwithu.maidroid.service.SafeExitStage
import org.maiwithu.maidroid.service.SafeExitState
import org.maiwithu.maidroid.service.describe
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardBorder
import org.maiwithu.maidroid.ui.theme.CardSurface
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.StatusDanger
import org.maiwithu.maidroid.ui.theme.StatusOnline
import org.maiwithu.maidroid.ui.theme.StatusWarning
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes

// 安全退出的三件界面：设置页最底下的按钮、点按钮后的二次确认、退出过程中的进度弹窗。

/** 设置页列表最底下的“安全退出”按钮，下面一行小字说明会做什么。 */
@Composable
internal fun SafeExitButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(
            onClick = onClick,
            shape = RoundedCornerShape(Corners.Card),
            colors = ButtonDefaults.buttonColors(
                containerColor = StatusDanger.copy(alpha = 0.14f),
                contentColor = StatusDanger
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .border(1.dp, StatusDanger.copy(alpha = 0.4f), RoundedCornerShape(Corners.Card))
        ) {
            Icon(
                imageVector = Icons.Outlined.PowerSettingsNew,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "安全退出", fontSize = TextSizes.BodyLarge, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 居中排在按钮下面；文字压在一行左右，手机竖屏不会只折下一两个字
        Text(
            text = "各服务保存好数据、正常退出后，再关闭 MaiDroid",
            color = TextMuted,
            fontSize = TextSizes.Caption,
            lineHeight = 16.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
}

/** 点“安全退出”后的二次确认：说明会停掉什么、要等多久，以及退出期间可以离开应用。 */
@Composable
internal fun SafeExitConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "安全退出 MaiDroid？",
                color = TextPrimary,
                fontSize = TextSizes.TitleLarge,
                fontWeight = FontWeight.Bold,
                lineHeight = 22.sp
            )
        },
        text = {
            Text(
                text = "将通知麦麦、QQ 和容器 SSH 正常退出：麦麦先保存好记忆和数据（通常几秒，最长约一分半钟），" +
                    "QQ 随之下线。全部停下后 MaiDroid 自动关闭，再次打开时会重新唤醒麦麦。\n\n" +
                    "退出期间可以切到后台或划掉 MaiDroid，通知栏会显示进度，请不要强行停止。",
                color = TextMuted,
                fontSize = TextSizes.Body,
                lineHeight = 18.sp
            )
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = CardBorder,
                    contentColor = TextPrimary
                )
            ) {
                Text(text = "取消", fontWeight = FontWeight.Bold)
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = StatusDanger,
                    contentColor = TextPrimary
                )
            ) {
                Text(text = "安全退出", fontWeight = FontWeight.Bold)
            }
        },
        containerColor = CardSurface,
        titleContentColor = TextPrimary,
        textContentColor = TextMuted
    )
}

/**
 * 退出过程中的进度弹窗：每项服务一行，正在退出的转圈、停下的打勾（强制停止的标黄）。
 * 退出开始后就没有回头路，返回键和点空白处都关不掉它；唯一的按钮是“切到后台”，退出照样在前台服务里做完。
 */
@Composable
internal fun SafeExitProgressDialog(
    state: SafeExitState,
    onMoveToBackground: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = {
            Text(
                text = if (state.finished) "正在关闭 MaiDroid" else "正在安全退出",
                color = TextPrimary,
                fontSize = TextSizes.TitleLarge,
                fontWeight = FontWeight.Bold,
                lineHeight = 22.sp
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SafeExitService.entries.forEach { service ->
                    SafeExitServiceRow(service, state.stages[service] ?: SafeExitStage.Stopping)
                }
                Text(
                    text = "可以离开应用，全部停下后 MaiDroid 会自动关闭。",
                    color = TextMuted,
                    fontSize = TextSizes.Caption,
                    lineHeight = 16.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onMoveToBackground,
                colors = ButtonDefaults.buttonColors(
                    containerColor = CardBorder,
                    contentColor = TextPrimary
                )
            ) {
                Text(text = "切到后台", fontWeight = FontWeight.Bold)
            }
        },
        containerColor = CardSurface,
        titleContentColor = TextPrimary,
        textContentColor = TextMuted
    )
}

@Composable
private fun SafeExitServiceRow(service: SafeExitService, stage: SafeExitStage) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            when {
                !stage.done -> CircularProgressIndicator(
                    color = BrandOrange,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp)
                )
                stage == SafeExitStage.ForceStopped -> Icon(
                    imageVector = Icons.Outlined.WarningAmber,
                    contentDescription = null,
                    tint = StatusWarning,
                    modifier = Modifier.size(20.dp)
                )
                else -> Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = if (stage == SafeExitStage.Exited) StatusOnline else TextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Column {
            Text(
                text = service.label,
                color = TextPrimary,
                fontSize = TextSizes.BodyLarge,
                fontWeight = FontWeight.Bold,
                lineHeight = 18.sp
            )
            Text(
                text = stage.describe(service),
                color = TextMuted,
                fontSize = TextSizes.Caption,
                lineHeight = 16.sp
            )
        }
    }
}
