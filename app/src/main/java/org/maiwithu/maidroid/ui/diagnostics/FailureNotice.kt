package org.maiwithu.maidroid.ui.diagnostics

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.maiwithu.maidroid.appDependencies
import org.maiwithu.maidroid.diagnostics.Failure
import org.maiwithu.maidroid.diagnostics.FailureAction
import org.maiwithu.maidroid.ui.cleanup.StorageCleanupDialog
import org.maiwithu.maidroid.ui.remote.RemoteActionDialogHost
import org.maiwithu.maidroid.ui.remote.defaultRemoteActionLabel
import org.maiwithu.maidroid.ui.remote.dispatchRemoteAction
import org.maiwithu.maidroid.ui.remote.parseRemoteActionTarget
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardBorder
import org.maiwithu.maidroid.ui.theme.JetBrainsMono
import org.maiwithu.maidroid.ui.theme.StatusWarning
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes

/**
 * 一处失败的“怎么办”：错误码 -> 认出原因时的标题和建议（优先采用远端排障热规则，其次内置规则）->
 * 按钮（原因带的跳转、“生成求助码”、“反馈问题”和“QQ 群反馈”）。
 * 点“生成求助码”会上传脱敏快照并在原地显示 6 位短码，用户截屏或复制发群即可完成远程排障。
 */
@Composable
internal fun FailureNotice(
    failure: Failure,
    modifier: Modifier = Modifier,
    textColor: Color = TextMuted
) {
    val context = LocalContext.current
    val remoteOps = remember(context) { context.appDependencies.remoteOps }
    val scope = rememberCoroutineScope()
    var showFeedback by rememberSaveable { mutableStateOf(false) }
    var showQqGroup by rememberSaveable { mutableStateOf(false) }
    var showCleanup by rememberSaveable { mutableStateOf(false) }
    var remoteModalKey by rememberSaveable { mutableStateOf<String?>(null) }
    var helpCode by rememberSaveable(failure.code) { mutableStateOf<String?>(null) }
    var generatingCode by remember { mutableStateOf(false) }

    val remoteHint = failure.remoteHint
    val remoteActionTarget = remember(remoteHint?.actionTarget) { parseRemoteActionTarget(remoteHint?.actionTarget) }
    val cause = failure.cause

    LaunchedEffect(failure.code) {
        remoteOps.reportFailure(failure)
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "错误码 ${failure.code}",
            color = StatusWarning,
            fontFamily = JetBrainsMono,
            fontSize = TextSizes.Caption,
            fontWeight = FontWeight.Bold,
            lineHeight = 16.sp
        )
        if (remoteHint != null) {
            Text(
                text = "可能的原因：${remoteHint.title}",
                color = TextPrimary,
                fontSize = TextSizes.Body,
                fontWeight = FontWeight.Bold,
                lineHeight = 18.sp
            )
            Text(text = remoteHint.advice, color = textColor, fontSize = TextSizes.Body, lineHeight = 19.sp)
        } else if (cause != null) {
            Text(
                text = "可能的原因：${cause.title}",
                color = TextPrimary,
                fontSize = TextSizes.Body,
                fontWeight = FontWeight.Bold,
                lineHeight = 18.sp
            )
            Text(text = cause.advice, color = textColor, fontSize = TextSizes.Body, lineHeight = 19.sp)
        }

        helpCode?.let { code ->
            HelpCodeCard(code = code, errorCode = failure.code)
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (remoteHint != null && remoteActionTarget != null) {
                NoticeButton(text = defaultRemoteActionLabel(remoteActionTarget), highlighted = true) {
                    dispatchRemoteAction(context, remoteHint.actionTarget) { remoteModalKey = it }
                }
            } else {
                cause?.action?.let { action ->
                    NoticeButton(text = action.label, highlighted = true) {
                        if (action == FailureAction.FreeUpStorage) showCleanup = true else openSystemSettings(context, action)
                    }
                }
            }

            if (helpCode == null) {
                NoticeButton(
                    text = if (generatingCode) "生成中..." else "生成求助码",
                    highlighted = true,
                    enabled = !generatingCode
                ) {
                    generatingCode = true
                    scope.launch {
                        val res = remoteOps.generateHelpCode(failure)
                        generatingCode = false
                        res.onSuccess { code ->
                            helpCode = code
                            copyHelpCodeToClipboard(context, code, failure.code)
                        }.onFailure { err ->
                            Toast.makeText(context, err.message ?: "生成求助码失败", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }

            NoticeButton(text = "反馈问题", highlighted = false) { showFeedback = true }
            NoticeButton(text = "QQ 群反馈", highlighted = false) { showQqGroup = true }
        }
    }

    if (showFeedback) {
        FeedbackDialog(failure = failure, onDismiss = { showFeedback = false })
    }
    if (showQqGroup) {
        QqFeedbackGroupDialog(onDismiss = { showQqGroup = false })
    }
    if (showCleanup) {
        StorageCleanupDialog(onDismiss = { showCleanup = false })
    }
    RemoteActionDialogHost(activeModalKey = remoteModalKey, onDismiss = { remoteModalKey = null })
}

@Composable
private fun NoticeButton(
    text: String,
    highlighted: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
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

// 原因带的跳转里打开系统设置的那几种：网络、日期时间（清理空间用应用自己的清理弹窗，那里还能再去系统存储设置）。
private fun openSystemSettings(context: Context, action: FailureAction) {
    val intentAction = when (action) {
        FailureAction.NetworkSettings -> Settings.ACTION_WIRELESS_SETTINGS
        FailureAction.DateSettings -> Settings.ACTION_DATE_SETTINGS
        FailureAction.FreeUpStorage -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
    }
    runCatching {
        context.startActivity(Intent(intentAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        Toast.makeText(context, "打不开系统设置，请手动打开“设置”", Toast.LENGTH_SHORT).show()
    }
}
