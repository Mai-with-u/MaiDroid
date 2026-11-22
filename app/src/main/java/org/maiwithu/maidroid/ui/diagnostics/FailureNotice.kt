package org.maiwithu.maidroid.ui.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.maiwithu.maidroid.appDependencies
import org.maiwithu.maidroid.diagnostics.Failure
import org.maiwithu.maidroid.diagnostics.FailureAction
import org.maiwithu.maidroid.ui.cleanup.StorageCleanupDialog
import org.maiwithu.maidroid.ui.component.MaiDialogInfoChip
import org.maiwithu.maidroid.ui.remote.RemoteActionDialogHost
import org.maiwithu.maidroid.ui.remote.defaultRemoteActionLabel
import org.maiwithu.maidroid.ui.remote.dispatchRemoteAction
import org.maiwithu.maidroid.ui.remote.parseRemoteActionTarget
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardBorder
import org.maiwithu.maidroid.ui.theme.CardDivider
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.StatusDanger
import org.maiwithu.maidroid.ui.theme.StatusWarning
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes

private const val FailureSummaryCollapseThreshold = 88

/**
 * 统一的故障提醒卡片：将错误码、原因诊断、原文摘要折叠及修复/求助入口收敛进同一语义卡片中。
 * 上层主按钮承载直接排障动作与“生成求助码”，下层轻量入口跳转“诊断详情”与“QQ 群求助”，避免多个大胶囊按钮乱换行。
 */
@Composable
internal fun FailureNotice(
    failure: Failure,
    modifier: Modifier = Modifier,
    textColor: Color = TextMuted,
    summary: String? = null
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
    var summaryExpanded by rememberSaveable(failure.code, summary) { mutableStateOf(false) }

    val remoteHint = failure.remoteHint
    val remoteActionTarget = remember(remoteHint?.actionTarget) { parseRemoteActionTarget(remoteHint?.actionTarget) }
    val cause = failure.cause
    val diagnosisTitle = remoteHint?.title ?: cause?.title ?: "操作未能完成"
    val diagnosisAdvice = remoteHint?.advice ?: cause?.advice
    val accentColor = if (remoteHint != null || cause != null) StatusWarning else StatusDanger
    val cleanSummary = summary?.trim()?.takeIf { it.isNotBlank() && it != diagnosisTitle && it != diagnosisAdvice }
    val cardShape = RoundedCornerShape(Corners.Panel)

    // 失败遥测与自动报错提取已统一在业务层写出 failure.logLine() 时抓当下现场上报，UI 层只做展示与手动求助码上传。
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(accentColor.copy(alpha = 0.08f))
            .border(0.8.dp, accentColor.copy(alpha = 0.24f), cardShape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = diagnosisTitle,
                color = TextPrimary,
                fontSize = TextSizes.Body,
                fontWeight = FontWeight.Bold,
                lineHeight = 18.sp,
                modifier = Modifier.weight(1f)
            )
            MaiDialogInfoChip(
                text = failure.code,
                color = accentColor,
                monospace = true,
                onClick = { copyErrorCode(context, failure.code) }
            )
        }

        if (!diagnosisAdvice.isNullOrBlank()) {
            Text(
                text = diagnosisAdvice,
                color = textColor,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }

        if (cleanSummary != null) {
            val isLong = cleanSummary.length > FailureSummaryCollapseThreshold
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = cleanSummary,
                    color = TextPrimary.copy(alpha = 0.85f),
                    fontSize = TextSizes.Caption,
                    lineHeight = 16.sp,
                    maxLines = if (isLong && !summaryExpanded) 2 else Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis
                )
                if (isLong) {
                    Text(
                        text = if (summaryExpanded) "收起详情" else "展开详情",
                        color = BrandOrange,
                        fontSize = TextSizes.Caption,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(Corners.Tag))
                            .clickable(role = Role.Button) { summaryExpanded = !summaryExpanded }
                            .padding(vertical = 2.dp, horizontal = 2.dp)
                    )
                }
            }
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
                        when (action) {
                            FailureAction.FreeUpStorage -> showCleanup = true
                            FailureAction.MaiBotUpdate ->
                                dispatchRemoteAction(context, "open:maibot_update") { remoteModalKey = it }
                            FailureAction.NetworkSettings,
                            FailureAction.DateSettings -> openSystemSettings(context, action)
                        }
                    }
                }
            }

            if (helpCode == null) {
                NoticeButton(
                    text = if (generatingCode) "生成求助码中..." else "生成求助码",
                    highlighted = false,
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
        }

        // 次要排查与进群动作收敛成轻量文字链接，不和主修复钮争抢视觉焦点。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NoticeTextLink(text = "诊断详情", onClick = { showFeedback = true })
            Text(text = "·", color = CardDivider, fontSize = TextSizes.Caption, modifier = Modifier.padding(horizontal = 8.dp))
            NoticeTextLink(text = "QQ 群求助", onClick = { showQqGroup = true })
            Spacer(modifier = Modifier.weight(1f))
            Text(text = "点错误码可复制", color = TextMuted.copy(alpha = 0.7f), fontSize = 11.sp)
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
        modifier = Modifier.heightIn(min = 34.dp),
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (highlighted) BrandOrange else CardBorder,
            contentColor = TextPrimary
        )
    ) {
        Text(text = text, fontSize = TextSizes.Caption, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun NoticeTextLink(text: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(Corners.Tag))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            color = BrandOrange,
            fontSize = TextSizes.Caption,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun copyErrorCode(context: Context, code: String) {
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText("MaiDroid 错误码", code))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, "已复制错误码 $code", Toast.LENGTH_SHORT).show()
    }
}

// 原因带的跳转里打开系统设置的那几种：网络、日期时间（清理空间和 MaiBot 更新走应用内弹窗）。
private fun openSystemSettings(context: Context, action: FailureAction) {
    val intentAction = when (action) {
        FailureAction.NetworkSettings -> Settings.ACTION_WIRELESS_SETTINGS
        FailureAction.DateSettings -> Settings.ACTION_DATE_SETTINGS
        FailureAction.FreeUpStorage -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
        FailureAction.MaiBotUpdate -> return
    }
    runCatching {
        context.startActivity(Intent(intentAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        Toast.makeText(context, "打不开系统设置，请手动打开“设置”", Toast.LENGTH_SHORT).show()
    }
}
