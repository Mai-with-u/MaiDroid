package org.maiwithu.maidroid.ui.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maiwithu.maidroid.diagnostics.CrashRecord
import org.maiwithu.maidroid.diagnostics.Failure
import org.maiwithu.maidroid.diagnostics.FailureStep
import org.maiwithu.maidroid.ui.component.FlatFeatureIcons
import org.maiwithu.maidroid.ui.component.MaiDialog
import org.maiwithu.maidroid.ui.component.MaiDialogButton
import org.maiwithu.maidroid.ui.component.MaiDialogButtonRow
import org.maiwithu.maidroid.ui.component.MaiDialogButtonStyle
import org.maiwithu.maidroid.ui.component.MaiDialogInfoChip
import org.maiwithu.maidroid.ui.component.MaiDialogSectionCard
import org.maiwithu.maidroid.ui.component.MaiDialogSize
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.IconRoseAlert
import org.maiwithu.maidroid.ui.theme.JetBrainsMono
import org.maiwithu.maidroid.ui.theme.StatusWarning
import org.maiwithu.maidroid.ui.theme.TerminalBackground
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes

/** 上次闪退对应的失败：错误码 MD-CRASH，说明里带异常那一行，反馈弹窗和预填的 Issue 标题都用它。 */
internal fun crashFailure(crash: CrashRecord): Failure =
    Failure(FailureStep.AppCrash, "MaiDroid 上次闪退：${crash.summary}")

/**
 * 打开 MaiDroid 时发现上次闪退过、用户还没看过：用统一弹窗展示错误码与异常摘要，
 * 提供主操作“反馈问题”以及次要“知道了”“QQ 群反馈”，点击均将本次闪退记为已看过。
 */
@Composable
internal fun CrashPromptDialog(
    crash: CrashRecord,
    onFeedback: () -> Unit,
    onQqFeedback: () -> Unit,
    onDismiss: () -> Unit
) {
    val failure = crashFailure(crash)
    MaiDialog(
        title = "上次意外退出",
        subtitle = "已保存异常摘要与现场记录",
        icon = FlatFeatureIcons.Telemetry,
        iconBackground = IconRoseAlert,
        size = MaiDialogSize.Expanded,
        headerBadge = {
            MaiDialogInfoChip(
                text = failure.code,
                color = StatusWarning,
                monospace = true
            )
        },
        secondaryContent = {
            // 横屏将闪退说明收进右列卡片，与左侧终端报错框视觉等齐。
            MaiDialogSectionCard {
                // 自己记下的闪退有调用栈和闪退前的终端输出；系统记的（原生代码闪退、无响应）只有系统的退出记录
                Text(
                    text = if (crash.fromSystem) {
                        "系统记下了这次退出。反馈给我们能帮忙更快修好，诊断包会带上系统退出记录和之前的终端输出。"
                    } else {
                        "当时的报错已经记下来了。反馈给我们能帮忙更快修好，诊断包会带上这份闪退记录。"
                    },
                    color = TextMuted,
                    fontSize = TextSizes.Body,
                    lineHeight = 19.sp
                )
            }
        },
        onDismissRequest = onDismiss,
        landscapeFooter = {
            MaiDialogButton(
                text = "知道了",
                style = MaiDialogButtonStyle.Secondary,
                onClick = onDismiss
            )
            MaiDialogButton(
                text = "QQ 群反馈",
                style = MaiDialogButtonStyle.SoftAccent,
                onClick = onQqFeedback
            )
            MaiDialogButton(
                text = "反馈问题",
                style = MaiDialogButtonStyle.Primary,
                onClick = onFeedback
            )
        },
        footer = {
            MaiDialogButton(
                text = "反馈问题",
                style = MaiDialogButtonStyle.Primary,
                modifier = Modifier.fillMaxWidth(),
                onClick = onFeedback
            )
            MaiDialogButtonRow {
                MaiDialogButton(
                    text = "知道了",
                    style = MaiDialogButtonStyle.Secondary,
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss
                )
                MaiDialogButton(
                    text = "QQ 群反馈",
                    style = MaiDialogButtonStyle.SoftAccent,
                    modifier = Modifier.weight(1f),
                    onClick = onQqFeedback
                )
            }
        }
    ) {
        Text(
            text = crash.summary.ifBlank { failure.message },
            color = TextPrimary,
            fontFamily = JetBrainsMono,
            fontSize = TextSizes.Caption,
            lineHeight = 16.sp,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 160.dp)
                .clip(RoundedCornerShape(Corners.Control))
                .background(TerminalBackground)
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        )
    }
}
