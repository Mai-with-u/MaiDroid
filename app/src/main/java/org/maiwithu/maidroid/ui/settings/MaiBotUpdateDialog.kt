package org.maiwithu.maidroid.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import org.maiwithu.maidroid.appDependencies
import org.maiwithu.maidroid.maibot.update.MaiBotChannel
import org.maiwithu.maidroid.maibot.update.MaiBotRevision
import org.maiwithu.maidroid.maibot.update.MaiBotUpdateAction
import org.maiwithu.maidroid.maibot.update.MaiBotUpdatePhase
import org.maiwithu.maidroid.maibot.update.MaiBotUpdateState
import org.maiwithu.maidroid.maibot.update.maiBotBranchLabel
import org.maiwithu.maidroid.ui.diagnostics.FailureNotice
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardBorder
import org.maiwithu.maidroid.ui.theme.CardSurface
import org.maiwithu.maidroid.ui.theme.CardSurfaceSelected
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.StatusOnline
import org.maiwithu.maidroid.ui.theme.StatusWarning
import org.maiwithu.maidroid.ui.theme.TextFaint
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 更新弹窗主按钮按下后要做的事。 */
internal enum class MaiBotUpdateButtonAction {
    Check,
    Apply,
    Switch,
    None
}

internal data class MaiBotUpdateButton(
    val label: String,
    val action: MaiBotUpdateButtonAction
) {
    val enabled: Boolean get() = action != MaiBotUpdateButtonAction.None
}

/** 弹窗主按钮：有新版本时更新，更新或切换通道失败时重试那一步，其余情况检查更新；进行中禁用。 */
internal fun maiBotUpdatePrimaryButton(state: MaiBotUpdateState): MaiBotUpdateButton =
    when (state.phase) {
        MaiBotUpdatePhase.Checking -> MaiBotUpdateButton("检查中...", MaiBotUpdateButtonAction.None)
        MaiBotUpdatePhase.Updating -> MaiBotUpdateButton(
            if (state.switchTarget != null) "切换中..." else "更新中...",
            MaiBotUpdateButtonAction.None
        )
        MaiBotUpdatePhase.Available -> MaiBotUpdateButton("更新并重启", MaiBotUpdateButtonAction.Apply)
        MaiBotUpdatePhase.Failed -> when {
            state.failedAction == MaiBotUpdateAction.Apply ->
                MaiBotUpdateButton("重试更新", MaiBotUpdateButtonAction.Apply)
            // 切换失败后重试切到同一个通道；拉取失败时源码没动，切过去后装依赖失败的会接着装
            state.failedAction == MaiBotUpdateAction.Switch && state.switchTarget != null ->
                MaiBotUpdateButton("重试切换", MaiBotUpdateButtonAction.Switch)
            else -> MaiBotUpdateButton("重新检查", MaiBotUpdateButtonAction.Check)
        }
        MaiBotUpdatePhase.Idle,
        MaiBotUpdatePhase.UpToDate,
        MaiBotUpdatePhase.Updated -> MaiBotUpdateButton("检查更新", MaiBotUpdateButtonAction.Check)
    }

/** 设置页“MaiBot 更新”一行的副标题；平时带上当前通道，让用户一眼看出装的是正式版还是测试版。 */
internal fun maiBotUpdateSummaryText(
    state: MaiBotUpdateState,
    zoneId: ZoneId = ZoneId.systemDefault()
): String {
    // 通道名放在提交号前面：main、dev 显示“正式版”“测试版”，别的分支直接显示分支名，读不到分支时不加
    val channelPrefix = state.branch?.let { branch -> MaiBotChannel.ofBranch(branch)?.label ?: branch }
    fun revisionText(revision: MaiBotRevision): String =
        listOfNotNull(channelPrefix, revision.shortCommit).joinToString(" ")

    return when (state.phase) {
        MaiBotUpdatePhase.Checking -> "正在检查更新..."
        MaiBotUpdatePhase.Updating -> state.progressText ?: "正在更新..."
        MaiBotUpdatePhase.Available -> state.latest?.let { "发现新版本 ${it.shortCommit}" } ?: "发现新版本"
        MaiBotUpdatePhase.UpToDate -> state.installed?.let { "已是最新 · ${revisionText(it)}" } ?: "已是最新版本"
        MaiBotUpdatePhase.Updated -> {
            val target = state.switchTarget
            when {
                target != null -> state.installed?.let { "已切换到${target.label} · ${it.shortCommit}" }
                    ?: "已切换到${target.label}"
                else -> state.installed?.let { "已更新到 ${it.shortCommit}" } ?: "更新完成"
            }
        }
        MaiBotUpdatePhase.Failed -> if (state.failedAction == MaiBotUpdateAction.Switch) {
            "切换失败：${state.errorMessage.orEmpty()}"
        } else {
            "更新失败：${state.errorMessage.orEmpty()}"
        }
        MaiBotUpdatePhase.Idle -> state.installed
            ?.let {
                "${channelPrefix ?: "当前"} ${it.shortCommit} · ${formatRevisionTime(it.committedAtEpochSeconds, zoneId)}"
            }
            ?: "从上游仓库拉取最新版本"
    }
}

/** 弹窗里的状态说明，进行中的阶段直接显示当前步骤。 */
internal fun maiBotUpdateStatusText(state: MaiBotUpdateState): String =
    when (state.phase) {
        MaiBotUpdatePhase.Checking,
        MaiBotUpdatePhase.Updating -> state.progressText ?: "处理中..."
        MaiBotUpdatePhase.Available -> "发现新版本。更新会短暂停止麦麦，完成后自动重启。"
        MaiBotUpdatePhase.UpToDate -> "已是最新版本。"
        MaiBotUpdatePhase.Updated -> state.switchTarget
            ?.let { "已切换到${it.label}，麦麦正在重启。" }
            ?: "更新完成，麦麦正在重启。"
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

/** 切换版本通道前确认框的标题和说明。 */
internal data class MaiBotChannelSwitchPrompt(val title: String, val message: String)

// 切换的做法两边一样：先确认数据库不会降级 -> 停麦麦 -> 拉取目标分支 -> 源码硬重置过去 -> 重装依赖 -> 重启。
// 切到测试版提醒它不稳定、上游会回退提交；切到正式版说明数据库不能降级、切换前会先检查：
// MaiBot 启动时发现数据库版本比代码支持的新，会直接报错退出（src/common/database/migrations/bootstrap.py），
// 所以测试版先升级过数据库时，MaiBotUpdateManager 的预检会在停麦麦之前取消切换。
internal fun maiBotChannelSwitchPrompt(target: MaiBotChannel): MaiBotChannelSwitchPrompt {
    val procedure = "切换会停止麦麦，拉取上游 ${target.branch} 分支的最新提交，把 MaiBot 源码硬重置过去并重装依赖，" +
        "完成后自动重启；之后检查更新也跟随 ${target.branch}。配置、数据和插件不受影响，改动过的 MaiBot 源码文件会被还原。"
    return when (target) {
        MaiBotChannel.Testing -> MaiBotChannelSwitchPrompt(
            title = "切换到测试版？",
            message = "测试版是上游还在开发中的代码，可能不稳定，上游有时还会回退提交。\n\n$procedure"
        )
        MaiBotChannel.Stable -> MaiBotChannelSwitchPrompt(
            title = "切换到正式版？",
            message = "$procedure\n\nMaiBot 的数据库不能降级。如果测试版已经升级过数据库结构、正式版还读不了，" +
                "会在停止麦麦之前取消切换，麦麦照常运行。"
        )
    }
}

/**
 * 接上 MaiBot 更新管理器的更新弹窗：设置页点“MaiBot 更新”、启动时查到上游新版本的提醒共用这一个。
 * 状态都在进程级的更新管理器里，弹窗关掉再打开、Activity 重建后都能接上检查和更新进度。
 * showChannelSelector 为 true 时（只有设置页点开时）底部多一个“版本通道”选择器，启动提醒里不出现。
 */
@Composable
internal fun MaiBotUpdateDialogHost(onDismiss: () -> Unit, showChannelSelector: Boolean = false) {
    val context = LocalContext.current
    val updater = remember(context) { context.appDependencies.maiBotUpdater }
    val state by updater.state.collectAsState()
    MaiBotUpdateDialog(
        state = state,
        onCheck = updater::checkForUpdate,
        onApply = updater::applyUpdate,
        onSwitchChannel = updater::switchChannel,
        onDismiss = onDismiss,
        showChannelSelector = showChannelSelector
    )
}

@Composable
internal fun MaiBotUpdateDialog(
    state: MaiBotUpdateState,
    onCheck: () -> Unit,
    onApply: () -> Unit,
    onSwitchChannel: (MaiBotChannel) -> Unit,
    onDismiss: () -> Unit,
    showChannelSelector: Boolean = false
) {
    // 用户在选择器上点了另一个通道、还没确认：先弹确认框，确认后才真正切换
    var pendingChannel by remember { mutableStateOf<MaiBotChannel?>(null) }
    val primaryButton = maiBotUpdatePrimaryButton(state)
    val showLatest = state.latest != null && state.phase != MaiBotUpdatePhase.Idle
    // 有新版本或上次更新失败时提醒：用户改过的源码文件更新时会直接还原为上游版本，不做备份。
    val showLocalChangeNote = state.localChangeCount > 0 && (
        state.phase == MaiBotUpdatePhase.Available ||
            state.failedAction == MaiBotUpdateAction.Apply
        )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "MaiBot 更新",
                color = TextPrimary,
                fontSize = TextSizes.TitleLarge,
                fontWeight = FontWeight.Bold,
                lineHeight = 22.sp
            )
        },
        text = {
            // 手机横屏时弹窗很矮，内容区可以滚动，不会被截掉。
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                RevisionBlock(
                    label = state.branch?.let { "当前版本 · ${maiBotBranchLabel(it)}" } ?: "当前版本",
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

                // 失败时：错误码、认出的原因和建议、“反馈问题”
                val failure = state.failure
                if (state.phase == MaiBotUpdatePhase.Failed && failure != null) {
                    FailureNotice(failure = failure)
                }

                if (showLocalChangeNote) {
                    UpdateNote(
                        "检测到 ${state.localChangeCount} 个 MaiBot 源码文件被改动过，更新时会直接还原为上游版本；" +
                            "配置、数据和插件不受影响。"
                    )
                }

                // 还没读到当前分支时不让选：不知道现在在哪个通道，也就说不清要切到哪
                if (showChannelSelector) {
                    MaiBotChannelSelector(
                        selected = state.channel,
                        enabled = !state.isBusy && state.branch != null,
                        onSelect = { channel -> pendingChannel = channel }
                    )
                }
            }
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = CardBorder,
                    contentColor = TextPrimary
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
                        MaiBotUpdateButtonAction.Switch -> state.switchTarget?.let(onSwitchChannel)
                        MaiBotUpdateButtonAction.None -> Unit
                    }
                },
                enabled = primaryButton.enabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrandOrange,
                    contentColor = TextPrimary
                )
            ) {
                Text(text = primaryButton.label, fontWeight = FontWeight.Bold)
            }
        },
        containerColor = CardSurface,
        titleContentColor = TextPrimary,
        textContentColor = TextMuted
    )

    pendingChannel?.let { target ->
        MaiBotChannelSwitchDialog(
            target = target,
            onConfirm = {
                pendingChannel = null
                onSwitchChannel(target)
            },
            onDismiss = { pendingChannel = null }
        )
    }
}

// 更新弹窗底部的“版本通道”：两格胶囊选择器，高亮的是仓库当前所在的通道。
// 点另一格不会马上切换，只是交给外面弹确认框；点当前这格什么也不做。
@Composable
private fun MaiBotChannelSelector(
    selected: MaiBotChannel?,
    enabled: Boolean,
    onSelect: (MaiBotChannel) -> Unit
) {
    Column(
        modifier = Modifier.padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "版本通道",
            color = TextMuted,
            fontSize = TextSizes.Caption,
            fontWeight = FontWeight.Bold,
            lineHeight = 14.sp
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Corners.Chip))
                .background(CardBorder)
                .padding(2.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            MaiBotChannel.entries.forEach { channel ->
                val isSelected = channel == selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(Corners.Chip))
                        .background(if (isSelected) CardSurfaceSelected else Color.Transparent)
                        .selectable(
                            selected = isSelected,
                            enabled = enabled,
                            role = Role.RadioButton,
                            onClick = { if (!isSelected) onSelect(channel) }
                        )
                        .padding(vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = channel.label,
                        color = when {
                            !enabled -> TextFaint
                            isSelected -> TextPrimary
                            else -> TextMuted
                        },
                        fontSize = TextSizes.Body,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 17.sp,
                        maxLines = 1
                    )
                    Text(
                        text = channel.branch,
                        color = if (enabled) TextMuted else TextFaint,
                        fontSize = TextSizes.Caption,
                        lineHeight = 14.sp,
                        maxLines = 1
                    )
                }
            }
        }

        Text(
            text = "正式版跟随上游 main 分支；测试版跟随 dev 分支，能先用上新功能，但可能不稳定。",
            color = TextMuted,
            fontSize = TextSizes.Caption,
            lineHeight = 16.sp
        )
    }
}

// 切换通道前的确认框：说明会停麦麦、硬重置源码、重装依赖，以及该通道的风险；确认后才开始切换。
@Composable
private fun MaiBotChannelSwitchDialog(
    target: MaiBotChannel,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val prompt = maiBotChannelSwitchPrompt(target)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = prompt.title,
                color = TextPrimary,
                fontSize = TextSizes.TitleLarge,
                fontWeight = FontWeight.Bold,
                lineHeight = 22.sp
            )
        },
        text = {
            Text(
                text = prompt.message,
                color = TextMuted,
                fontSize = TextSizes.Body,
                lineHeight = 19.sp,
                modifier = Modifier.verticalScroll(rememberScrollState())
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
                    containerColor = BrandOrange,
                    contentColor = TextPrimary
                )
            ) {
                Text(text = "切换并重启", fontWeight = FontWeight.Bold)
            }
        },
        containerColor = CardSurface,
        titleContentColor = TextPrimary,
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
            fontSize = TextSizes.Caption,
            fontWeight = FontWeight.Bold,
            lineHeight = 14.sp
        )

        if (revision == null) {
            Text(
                text = "尚未读取到版本信息",
                color = TextPrimary,
                fontSize = TextSizes.Body,
                lineHeight = 18.sp
            )
            return@Column
        }

        Text(
            text = "${revision.shortCommit} · ${formatRevisionTime(revision.committedAtEpochSeconds)}",
            color = if (highlight) BrandOrange else TextPrimary,
            fontSize = TextSizes.Body,
            fontWeight = FontWeight.Bold,
            lineHeight = 18.sp
        )

        if (revision.subject.isNotBlank()) {
            Text(
                text = revision.subject,
                color = TextMuted,
                fontSize = TextSizes.Caption,
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
            fontSize = TextSizes.Body,
            lineHeight = 17.sp
        )
    }
}

@Composable
private fun UpdateNote(text: String) {
    Text(
        text = text,
        color = TextMuted,
        fontSize = TextSizes.Caption,
        lineHeight = 16.sp
    )
}
