package org.maiwithu.maidroid.ui.diagnostics

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maiwithu.maidroid.diagnostics.Failure
import org.maiwithu.maidroid.diagnostics.FailureAction
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardBorder
import org.maiwithu.maidroid.ui.theme.JetBrainsMono
import org.maiwithu.maidroid.ui.theme.StatusWarning
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes

/**
 * 一处失败的“怎么办”：错误码 -> 认出原因时的标题和建议 -> 按钮（原因带的跳转，以及“反馈问题”）。
 * 首次引导的页脚、更新弹窗、消息平台的安装弹窗都放这一块，失败说明本身仍由各处自己显示。
 * “反馈问题”打开的反馈弹窗由这里自己管，调用方不用再接。
 * onFreeUpStorage 为空时，“清理空间”按钮改为打开系统的存储设置。
 */
@Composable
internal fun FailureNotice(
    failure: Failure,
    modifier: Modifier = Modifier,
    textColor: Color = TextMuted,
    onFreeUpStorage: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var showFeedback by rememberSaveable { mutableStateOf(false) }
    val cause = failure.cause

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "错误码 ${failure.code}",
            color = StatusWarning,
            fontFamily = JetBrainsMono,
            fontSize = TextSizes.Caption,
            fontWeight = FontWeight.Bold,
            lineHeight = 16.sp
        )
        if (cause != null) {
            Text(
                text = "可能的原因：${cause.title}",
                color = TextPrimary,
                fontSize = TextSizes.Body,
                fontWeight = FontWeight.Bold,
                lineHeight = 18.sp
            )
            Text(text = cause.advice, color = textColor, fontSize = TextSizes.Body, lineHeight = 19.sp)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            cause?.action?.let { action ->
                NoticeButton(text = action.label, highlighted = true) {
                    runFailureAction(context, action, onFreeUpStorage)
                }
            }
            NoticeButton(text = "反馈问题", highlighted = false) { showFeedback = true }
        }
    }

    if (showFeedback) {
        FeedbackDialog(failure = failure, onDismiss = { showFeedback = false })
    }
}

@Composable
private fun NoticeButton(text: String, highlighted: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.height(36.dp),
        contentPadding = PaddingValues(horizontal = 14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (highlighted) BrandOrange.copy(alpha = 0.22f) else CardBorder,
            contentColor = TextPrimary
        )
    ) {
        Text(text = text, fontSize = TextSizes.Body, fontWeight = FontWeight.Bold)
    }
}

// 原因带的跳转：网络、日期时间直接打开系统设置；清理空间优先用应用里的清理，没有时打开系统存储设置。
private fun runFailureAction(context: Context, action: FailureAction, onFreeUpStorage: (() -> Unit)?) {
    val intentAction = when (action) {
        FailureAction.NetworkSettings -> Settings.ACTION_WIRELESS_SETTINGS
        FailureAction.DateSettings -> Settings.ACTION_DATE_SETTINGS
        FailureAction.FreeUpStorage -> {
            if (onFreeUpStorage != null) {
                onFreeUpStorage()
                return
            }
            Settings.ACTION_INTERNAL_STORAGE_SETTINGS
        }
    }
    runCatching {
        context.startActivity(Intent(intentAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        Toast.makeText(context, "打不开系统设置，请手动打开“设置”", Toast.LENGTH_SHORT).show()
    }
}
