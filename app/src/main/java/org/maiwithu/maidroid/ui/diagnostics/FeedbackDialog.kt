package org.maiwithu.maidroid.ui.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.maiwithu.maidroid.appDependencies
import org.maiwithu.maidroid.diagnostics.Failure
import org.maiwithu.maidroid.diagnostics.FeedbackPackage
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardBorder
import org.maiwithu.maidroid.ui.theme.CardSurface
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.JetBrainsMono
import org.maiwithu.maidroid.ui.theme.OnBrandOrange
import org.maiwithu.maidroid.ui.theme.StatusWarning
import org.maiwithu.maidroid.ui.theme.TerminalBackground
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes

// 收集诊断信息的三种结果：还在收集、收好了、收集出错（极少见，读文件或打码时抛了异常）。
private sealed interface FeedbackPreparation {
    data object Loading : FeedbackPreparation
    data class Ready(val pkg: FeedbackPackage) : FeedbackPreparation
    data class Error(val message: String) : FeedbackPreparation
}

/**
 * “反馈问题”弹窗：一打开就在后台收集并打码诊断信息 -> 显示错误码、诊断包里有什么、报告预览 ->
 * “去 GitHub 反馈”：复制诊断信息、打开预填好版本、机型、错误码的缺陷报告，用户把复制的内容粘贴到“日志”一栏；
 * “QQ 群反馈”：打开 QQ 用户反馈群弹窗，查看验证答案并一键跳转进群交流；
 * “保存诊断包”：系统“另存为”一个 zip（报告 + 终端日志 + MaiBot 日志 + 闪退记录），可以拖进 Issue 附件或发到群里。
 * failure 是从哪个失败点打开的（报告开头写它的错误码），从设置页、终端页打开时为 null。
 */
@Composable
internal fun FeedbackDialog(failure: Failure?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val diagnostics = remember(context) { context.appDependencies.diagnostics }
    val remoteOps = remember(context) { context.appDependencies.remoteOps }
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var generatingCode by remember { mutableStateOf(false) }
    var helpCode by rememberSaveable { mutableStateOf<String?>(null) }
    var showQqGroup by rememberSaveable { mutableStateOf(false) }

    val preparation by produceState<FeedbackPreparation>(FeedbackPreparation.Loading, failure) {
        value = try {
            FeedbackPreparation.Ready(diagnostics.prepare(failure))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FeedbackPreparation.Error(e.message ?: e.javaClass.simpleName)
        }
    }
    val pkg = (preparation as? FeedbackPreparation.Ready)?.pkg

    // 选好保存位置 -> 在 IO 线程写 zip -> 提示结果；用户在“另存为”里取消时 uri 为 null，什么也不做。
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val ready = pkg ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        saving = true
        scope.launch {
            val error = diagnostics.saveBundle(ready, uri)
            saving = false
            Toast.makeText(
                context,
                if (error == null) "诊断包已保存，可以拖进 Issue 的“补充信息”" else "保存失败：$error",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "反馈问题",
                color = TextPrimary,
                fontSize = TextSizes.TitleLarge,
                fontWeight = FontWeight.Bold,
                lineHeight = 22.sp
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                failure?.let { FailureCodeLine(it) }
                when (val state = preparation) {
                    FeedbackPreparation.Loading -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(color = BrandOrange, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        BodyText("正在收集诊断信息并打码...")
                    }
                    is FeedbackPreparation.Error -> BodyText("收集诊断信息失败：${state.message}", color = StatusWarning)
                    is FeedbackPreparation.Ready -> {
                        val currentCode = helpCode
                        if (currentCode != null) {
                            HelpCodeCard(code = currentCode, errorCode = failure?.code)
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(Corners.Control))
                                    .background(TerminalBackground)
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "不方便导诊断包？点右侧上传脱敏快照（保留 7 天自动清理），拿 6 位求助码发进群即可排查。",
                                    color = TextMuted,
                                    fontSize = TextSizes.Caption,
                                    lineHeight = 16.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                FeedbackActionButton(
                                    text = if (generatingCode) "生成中..." else "生成求助码",
                                    enabled = !generatingCode,
                                    containerColor = BrandOrange.copy(alpha = 0.22f),
                                    contentColor = TextPrimary,
                                    onClick = {
                                        generatingCode = true
                                        scope.launch {
                                            val res = remoteOps.generateHelpCode(state.pkg)
                                            generatingCode = false
                                            res.onSuccess { code ->
                                                helpCode = code
                                                copyHelpCodeToClipboard(context, code, failure?.code)
                                            }.onFailure { err ->
                                                Toast.makeText(context, err.message ?: "生成求助码失败", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                )
                            }
                        }

                        BodyText(feedbackContentsText(state.pkg))
                        BodyText(
                            "点“去 GitHub 反馈”会复制诊断信息并打开填好版本、机型和错误码的问题表单：" +
                                "把复制的内容粘贴到“日志”一栏，再写一句出问题前你在做什么就行；日常小问题也可以点“QQ 群反馈”进群交流。"
                        )
                        ReportPreview(state.pkg.reportText)
                    }
                }
            }
        },
        // 四个操作按钮分成辅助组（关闭、保存诊断包）和反馈组（QQ 群、GitHub）：
        // 宽屏四钮同排，360dp 竖屏整组流式排成对称两行，按钮用正文级字号避免撑高底部
        confirmButton = {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FeedbackActionButton(
                        text = "关闭",
                        containerColor = CardBorder,
                        contentColor = TextPrimary,
                        onClick = onDismiss
                    )
                    FeedbackActionButton(
                        text = if (saving) "保存中..." else "保存诊断包",
                        enabled = pkg != null && !saving,
                        containerColor = CardBorder,
                        contentColor = TextPrimary,
                        onClick = { pkg?.let { saveLauncher.launch(it.fileName) } }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FeedbackActionButton(
                        text = "QQ 群反馈",
                        containerColor = BrandOrange.copy(alpha = 0.22f),
                        contentColor = TextPrimary,
                        onClick = { showQqGroup = true }
                    )
                    FeedbackActionButton(
                        text = "去 GitHub 反馈",
                        enabled = pkg != null,
                        containerColor = BrandOrange,
                        contentColor = OnBrandOrange,
                        onClick = { pkg?.let { openGitHubFeedback(context, it) } }
                    )
                }
            }
        },
        containerColor = CardSurface,
        titleContentColor = TextPrimary,
        textContentColor = TextMuted
    )

    if (showQqGroup) {
        QqFeedbackGroupDialog(onDismiss = { showQqGroup = false })
    }
}

@Composable
private fun FeedbackActionButton(
    text: String,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = containerColor, contentColor = contentColor)
    ) {
        Text(text = text, fontSize = TextSizes.Body, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** 错误码和失败说明一行：等宽字体的码在前，失败弹窗、反馈弹窗共用。 */
@Composable
internal fun FailureCodeLine(failure: Failure, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "错误码 ${failure.code}",
            color = StatusWarning,
            fontFamily = JetBrainsMono,
            fontSize = TextSizes.Caption,
            fontWeight = FontWeight.Bold,
            lineHeight = 16.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(Corners.Tag))
                .background(StatusWarning.copy(alpha = 0.14f))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        )
        BodyText(failure.message)
    }
}

/** 诊断包里有什么：报告、几份终端日志、MaiBot 日志、闪退记录，以及打码的范围。 */
internal fun feedbackContentsText(pkg: FeedbackPackage): String {
    val parts = listOfNotNull(
        "诊断报告（版本、机型、权限、网络、容器和运行状态、最近的失败）",
        "终端输出 ${pkg.terminalLogCount} 份".takeIf { pkg.terminalLogCount > 0 },
        "MaiBot 日志".takeIf { pkg.includesMaiBotLog },
        "闪退记录 ${pkg.crashCount} 份".takeIf { pkg.crashCount > 0 }
    )
    return "诊断包包括：${parts.joinToString("、")}。令牌、密钥、QQ 号、手机号和公网 IP 已自动打码；" +
        "聊天内容没有处理，发出前可以看一眼。"
}

@Composable
private fun ReportPreview(text: String) {
    Text(
        text = text,
        color = TextPrimary,
        fontFamily = JetBrainsMono,
        fontSize = TextSizes.Caption,
        lineHeight = 16.sp,
        softWrap = false,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .clip(RoundedCornerShape(Corners.Control))
            .background(TerminalBackground)
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState())
            .padding(10.dp)
    )
}

@Composable
private fun BodyText(text: String, color: Color = TextMuted) {
    Text(text = text, color = color, fontSize = TextSizes.Body, lineHeight = 19.sp)
}

// 复制诊断信息 -> 打开预填好的缺陷报告。Android 13 起系统自己会提示“已复制”，这里的提示换成下一步该做什么。
private fun openGitHubFeedback(context: Context, pkg: FeedbackPackage) {
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText("MaiDroid 诊断信息", pkg.clipboardText))
    val opened = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, pkg.issueUrl.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
    val hint = when {
        !opened -> "没有找到浏览器。诊断信息已复制，可以在电脑上打开 GitHub 反馈"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> "把复制的诊断信息粘贴到“日志”一栏"
        else -> "诊断信息已复制，粘贴到“日志”一栏"
    }
    Toast.makeText(context, hint, Toast.LENGTH_LONG).show()
}
