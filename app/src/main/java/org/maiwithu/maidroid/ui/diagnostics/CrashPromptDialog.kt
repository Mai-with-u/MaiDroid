package org.maiwithu.maidroid.ui.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maiwithu.maidroid.diagnostics.CrashRecord
import org.maiwithu.maidroid.diagnostics.Failure
import org.maiwithu.maidroid.diagnostics.FailureStep
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardBorder
import org.maiwithu.maidroid.ui.theme.CardSurface
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes

/** 上次闪退对应的失败：错误码 MD-CRASH，说明里带异常那一行，反馈弹窗和预填的 Issue 标题都用它。 */
internal fun crashFailure(crash: CrashRecord): Failure =
    Failure(FailureStep.AppCrash, "MaiDroid 上次闪退：${crash.summary}")

/**
 * 打开 MaiDroid 时发现上次闪退过、用户还没看过：说明已经记下了报错，问要不要反馈。
 * “反馈问题”接着打开反馈弹窗（带上闪退记录）；“知道了”只是不再提示。两个按钮都会把这次闪退记为已看过。
 */
@Composable
internal fun CrashPromptDialog(crash: CrashRecord, onFeedback: () -> Unit, onDismiss: () -> Unit) {
    val failure = crashFailure(crash)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "MaiDroid 上次意外退出了",
                color = TextPrimary,
                fontSize = TextSizes.TitleLarge,
                fontWeight = FontWeight.Bold,
                lineHeight = 22.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FailureCodeLine(failure)
                Text(
                    text = "当时的报错已经记下来了。反馈给我们能帮忙更快修好，诊断包会带上这份闪退记录。",
                    color = TextMuted,
                    fontSize = TextSizes.Body,
                    lineHeight = 19.sp
                )
            }
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = CardBorder, contentColor = TextPrimary)
            ) {
                Text(text = "知道了", fontWeight = FontWeight.Bold)
            }
        },
        confirmButton = {
            Button(
                onClick = onFeedback,
                colors = ButtonDefaults.buttonColors(containerColor = BrandOrange, contentColor = TextPrimary)
            ) {
                Text(text = "反馈问题", fontWeight = FontWeight.Bold)
            }
        },
        containerColor = CardSurface,
        titleContentColor = TextPrimary,
        textContentColor = TextMuted
    )
}
