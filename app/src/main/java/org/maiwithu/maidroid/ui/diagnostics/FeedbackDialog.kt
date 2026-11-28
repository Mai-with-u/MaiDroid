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
import org.maiwithu.maidroid.ui.component.FlatFeatureIcons
import org.maiwithu.maidroid.ui.component.LocalMaiDialogPhoneLandscape
import org.maiwithu.maidroid.ui.component.MaiDialog
import org.maiwithu.maidroid.ui.component.MaiDialogButton
import org.maiwithu.maidroid.ui.component.MaiDialogButtonRow
import org.maiwithu.maidroid.ui.component.MaiDialogButtonStyle
import org.maiwithu.maidroid.ui.component.MaiDialogCallout
import org.maiwithu.maidroid.ui.component.MaiDialogCollapsible
import org.maiwithu.maidroid.ui.component.MaiDialogInfoChip
import org.maiwithu.maidroid.ui.component.MaiDialogSectionCard
import org.maiwithu.maidroid.ui.component.MaiDialogSize
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardBorder
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.IconCoralPink
import org.maiwithu.maidroid.ui.theme.JetBrainsMono
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
 * “反馈问题”弹窗：一打开就在后台收集并打码诊断信息 -> 胶囊摘要呈现诊断包构成并折叠长报告预览 ->
 * 支持导出 zip 诊断包、跳转 QQ 群反馈或一键复制诊断信息到 GitHub Issue。
 */
@Composable
internal fun FeedbackDialog(failure: Failure?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val diagnostics = remember(context) { context.appDependencies.diagnostics }
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
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

    MaiDialog(
        title = "反馈问题",
        subtitle = "密钥、账号与公网 IP 已自动打码",
        icon = FlatFeatureIcons.Feedback,
        iconBackground = IconCoralPink,
        size = MaiDialogSize.Expanded,
        secondaryContent = pkg?.let { readyPkg ->
            {
                // 手机横屏直接展开诊断报告供右列浏览，竖屏仍保持默认折叠避免首屏过长。
                val expandByDefault = LocalMaiDialogPhoneLandscape.current
                MaiDialogCollapsible(
                    title = "预览诊断报告",
                    summary = "点击展开",
                    initiallyExpanded = expandByDefault
                ) {
                    ReportPreview(readyPkg.reportText)
                }
            }
        },
        onDismissRequest = onDismiss,
        landscapeFooter = {
            MaiDialogButton(
                text = if (saving) "保存中..." else "保存诊断包",
                style = MaiDialogButtonStyle.Secondary,
                enabled = pkg != null && !saving,
                onClick = { pkg?.let { saveLauncher.launch(it.fileName) } }
            )
            MaiDialogButton(
                text = "QQ 群反馈",
                style = MaiDialogButtonStyle.SoftAccent,
                onClick = { showQqGroup = true }
            )
            MaiDialogButton(
                text = "复制并去 GitHub 反馈",
                style = MaiDialogButtonStyle.Primary,
                enabled = pkg != null,
                onClick = { pkg?.let { openGitHubFeedback(context, it) } }
            )
        },
        footer = {
            MaiDialogButton(
                text = "复制并去 GitHub 反馈",
                style = MaiDialogButtonStyle.Primary,
                enabled = pkg != null,
                modifier = Modifier.fillMaxWidth(),
                onClick = { pkg?.let { openGitHubFeedback(context, it) } }
            )
            MaiDialogButtonRow {
                MaiDialogButton(
                    text = if (saving) "保存中..." else "保存诊断包",
                    style = MaiDialogButtonStyle.Secondary,
                    enabled = pkg != null && !saving,
                    modifier = Modifier.weight(1f),
                    onClick = { pkg?.let { saveLauncher.launch(it.fileName) } }
                )
                MaiDialogButton(
                    text = "QQ 群反馈",
                    style = MaiDialogButtonStyle.SoftAccent,
                    modifier = Modifier.weight(1f),
                    onClick = { showQqGroup = true }
                )
            }
        }
    ) {
        failure?.let {
            MaiDialogSectionCard {
                FailureCodeLine(it)
            }
        }
        when (val state = preparation) {
            FeedbackPreparation.Loading -> MaiDialogSectionCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(color = BrandOrange, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    BodyText("正在收集诊断信息并打码...")
                }
            }
            is FeedbackPreparation.Error -> MaiDialogCallout(
                title = "收集诊断信息失败",
                body = state.message,
                accentColor = StatusWarning
            )
            is FeedbackPreparation.Ready -> {
                // 胶囊概括诊断包已包含内容，把长报告拆到次列或默认收进折叠面板保持首屏清爽。
                MaiDialogSectionCard {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        MaiDialogInfoChip(text = "诊断报告", color = TextPrimary, containerColor = CardBorder)
                        if (state.pkg.terminalLogCount > 0) {
                            MaiDialogInfoChip(
                                text = "终端输出 ${state.pkg.terminalLogCount} 份",
                                color = TextPrimary,
                                containerColor = CardBorder
                            )
                        }
                        if (state.pkg.includesMaiBotLog) {
                            MaiDialogInfoChip(text = "MaiBot 日志", color = TextPrimary, containerColor = CardBorder)
                        }
                        if (state.pkg.crashCount > 0) {
                            MaiDialogInfoChip(
                                text = "闪退记录 ${state.pkg.crashCount} 份",
                                color = StatusWarning
                            )
                        }
                    }
                    Text(
                        text = "点击「保存诊断包」导出 zip 文件即可发到 QQ 群或拖进 GitHub Issue；聊天记录未作脱敏，发出前可展开核对。",
                        color = TextMuted,
                        fontSize = TextSizes.Caption,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }

    if (showQqGroup) {
        QqFeedbackGroupDialog(onDismiss = { showQqGroup = false })
    }
}

/** 错误码和失败说明一行：等宽字体的码在前，失败弹窗、反馈弹窗共用。 */
@Composable
internal fun FailureCodeLine(failure: Failure, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MaiDialogInfoChip(
            text = "错误码 ${failure.code}",
            color = StatusWarning,
            monospace = true
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
