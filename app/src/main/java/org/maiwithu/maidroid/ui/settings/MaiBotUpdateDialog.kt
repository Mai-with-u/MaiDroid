package org.maiwithu.maidroid.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maiwithu.maidroid.maibot.update.MaiBotRevision
import org.maiwithu.maidroid.maibot.update.MaiBotUpdateAction
import org.maiwithu.maidroid.maibot.update.MaiBotUpdatePhase
import org.maiwithu.maidroid.maibot.update.MaiBotUpdateScripts
import org.maiwithu.maidroid.maibot.update.MaiBotUpdateState
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardBorder
import org.maiwithu.maidroid.ui.theme.CardSurface
import org.maiwithu.maidroid.ui.theme.StatusOnline
import org.maiwithu.maidroid.ui.theme.StatusWarning
import org.maiwithu.maidroid.ui.theme.TextMuted
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 更新弹窗主按钮按下后要做的事。 */
internal enum class MaiBotUpdateButtonAction {
    Check,
    Apply,
    None
}

internal data class MaiBotUpdateButton(
    val label: String,
    val action: MaiBotUpdateButtonAction
) {
    val enabled: Boolean get() = action != MaiBotUpdateButtonAction.None
}

/** 弹窗主按钮：有新版本时更新，更新失败时重试更新，其余情况检查更新；进行中禁用。 */
internal fun maiBotUpdatePrimaryButton(state: MaiBotUpdateState): MaiBotUpdateButton =
    when (state.phase) {
        MaiBotUpdatePhase.Checking -> MaiBotUpdateButton("检查中...", MaiBotUpdateButtonAction.None)
        MaiBotUpdatePhase.Updating -> MaiBotUpdateButton("更新中...", MaiBotUpdateButtonAction.None)
        MaiBotUpdatePhase.Available -> MaiBotUpdateButton("更新并重启", MaiBotUpdateButtonAction.Apply)
        MaiBotUpdatePhase.Failed -> if (state.failedAction == MaiBotUpdateAction.Apply) {
            MaiBotUpdateButton("重试更新", MaiBotUpdateButtonAction.Apply)
        } else {
            MaiBotUpdateButton("重新检查", MaiBotUpdateButtonAction.Check)
        }
        MaiBotUpdatePhase.Idle,
        MaiBotUpdatePhase.UpToDate,
        MaiBotUpdatePhase.Updated -> MaiBotUpdateButton("检查更新", MaiBotUpdateButtonAction.Check)
    }

/** 设置页“MaiBot 更新”一行的副标题。 */
internal fun maiBotUpdateSummaryText(
    state: MaiBotUpdateState,
    zoneId: ZoneId = ZoneId.systemDefault()
): String =
    when (state.phase) {
        MaiBotUpdatePhase.Checking -> "正在检查更新..."
        MaiBotUpdatePhase.Updating -> state.progressText ?: "正在更新..."
        MaiBotUpdatePhase.Available -> state.latest?.let { "发现新版本 ${it.shortCommit}" } ?: "发现新版本"
        MaiBotUpdatePhase.UpToDate -> state.installed?.let { "已是最新 · ${it.shortCommit}" } ?: "已是最新版本"
        MaiBotUpdatePhase.Updated -> state.installed?.let { "已更新到 ${it.shortCommit}" } ?: "更新完成"
        MaiBotUpdatePhase.Failed -> "更新失败：${state.errorMessage.orEmpty()}"
        MaiBotUpdatePhase.Idle -> state.installed
            ?.let { "当前 ${it.shortCommit} · ${formatRevisionTime(it.committedAtEpochSeconds, zoneId)}" }
            ?: "从上游仓库拉取最新版本"
    }

/** 弹窗里的状态说明，进行中的阶段直接显示当前步骤。 */
internal fun maiBotUpdateStatusText(state: MaiBotUpdateState): String =
    when (state.phase) {
        MaiBotUpdatePhase.Checking,
        MaiBotUpdatePhase.Updating -> state.progressText ?: "处理中..."
        MaiBotUpdatePhase.Available -> "发现新版本。更新会短暂停止麦麦，完成后自动重启。"
        MaiBotUpdatePhase.UpToDate -> "已是最新版本。"
        MaiBotUpdatePhase.Updated -> "更新完成，麦麦正在重启。"
        MaiBotUpdatePhase.Failed -> state.errorMessage.orEmpty().ifBlank { "更新失败，请查看终端日志。" }
        MaiBotUpdatePhase.Idle -> "检查更新会通过 GitHub 镜像获取上游最新提交，不会打断麦麦。"
    }

internal fun formatRevisionTime(
    epochSeconds: Long,
    zoneId: ZoneId = ZoneId.systemDefault()
): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(zoneId)
        .format(Instant.ofEpochSecond(epochSeconds))

@Composable
internal fun MaiBotUpdateDialog(
    state: MaiBotUpdateState,
    onCheck: () -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit
) {
    val primaryButton = maiBotUpdatePrimaryButton(state)
    val showLatest = state.latest != null && state.phase != MaiBotUpdatePhase.Idle
    // 有新版本或上次更新失败时提醒：被改过的受版本管理文件会先导出补丁，再还原为上游版本。
    val showLocalChangeNote = state.localChangeCount > 0 && (
        state.phase == MaiBotUpdatePhase.Available ||
            state.failedAction == MaiBotUpdateAction.Apply
        )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "MaiBot 更新",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 22.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RevisionBlock(
                    label = if (state.branch != null) "当前版本 · ${state.branch}" else "当前版本",
                    revision = state.installed,
                    highlight = false
                )

                if (showLatest) {
                    RevisionBlock(
                        label = "上游最新",
                        revision = state.latest,
                        highlight = state.phase == MaiBotUpdatePhase.Available
                    )
                }

                UpdateStatusLine(state)

                if (showLocalChangeNote) {
                    UpdateNote(
                        "检测到 ${state.localChangeCount} 个源码文件被修改。更新前会导出补丁到 " +
                            "${MaiBotUpdateScripts.BACKUP_DIR}，再还原为上游版本；配置、数据和插件不受影响。"
                    )
                }

                if (state.phase == MaiBotUpdatePhase.Updated && state.backupFile != null) {
                    UpdateNote("本地改动已备份到 ${state.backupFile}")
                }
            }
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = CardBorder,
                    contentColor = Color.White
                )
            ) {
                Text(
                    text = if (state.isBusy) "后台继续" else "关闭",
                    fontWeight = FontWeight.Bold
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when (primaryButton.action) {
                        MaiBotUpdateButtonAction.Check -> onCheck()
                        MaiBotUpdateButtonAction.Apply -> onApply()
                        MaiBotUpdateButtonAction.None -> Unit
                    }
                },
                enabled = primaryButton.enabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrandOrange,
                    contentColor = Color.White
                )
            ) {
                Text(text = primaryButton.label, fontWeight = FontWeight.Bold)
            }
        },
        containerColor = CardSurface,
        titleContentColor = Color.White,
        textContentColor = TextMuted
    )
}

@Composable
private fun RevisionBlock(
    label: String,
    revision: MaiBotRevision?,
    highlight: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = label,
            color = TextMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 14.sp
        )

        if (revision == null) {
            Text(
                text = "尚未读取到版本信息",
                color = Color.White,
                fontSize = 14.sp,
                lineHeight = 18.sp
            )
            return@Column
        }

        Text(
            text = "${revision.shortCommit} · ${formatRevisionTime(revision.committedAtEpochSeconds)}",
            color = if (highlight) BrandOrange else Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 18.sp
        )

        if (revision.subject.isNotBlank()) {
            Text(
                text = revision.subject,
                color = TextMuted,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun UpdateStatusLine(state: MaiBotUpdateState) {
    val color = when (state.phase) {
        MaiBotUpdatePhase.Failed -> StatusWarning
        MaiBotUpdatePhase.UpToDate,
        MaiBotUpdatePhase.Updated -> StatusOnline
        MaiBotUpdatePhase.Available -> BrandOrange
        else -> TextMuted
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (state.isBusy) {
            CircularProgressIndicator(
                color = BrandOrange,
                strokeWidth = 2.dp,
                modifier = Modifier.size(16.dp)
            )
        }

        Text(
            text = maiBotUpdateStatusText(state),
            color = color,
            fontSize = 13.sp,
            lineHeight = 17.sp
        )
    }
}

@Composable
private fun UpdateNote(text: String) {
    Text(
        text = text,
        color = TextMuted,
        fontSize = 12.sp,
        lineHeight = 16.sp
    )
}
