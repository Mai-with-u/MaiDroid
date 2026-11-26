package org.maiwithu.maidroid.ui.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.shapes.RoundedRectangle
import org.maiwithu.maidroid.appupdate.formatBytes
import org.maiwithu.maidroid.backup.BackupComponent
import org.maiwithu.maidroid.backup.BackupRecord
import org.maiwithu.maidroid.backup.BackupRunOutcome
import org.maiwithu.maidroid.backup.BackupState
import org.maiwithu.maidroid.backup.DEFAULT_EXTERNAL_DISPLAY_PATH
import org.maiwithu.maidroid.backup.RETENTION_OPTIONS
import org.maiwithu.maidroid.backup.formatNextScheduleHint
import org.maiwithu.maidroid.backup.formatScheduleTime
import org.maiwithu.maidroid.ui.component.FlatFeatureIcons
import org.maiwithu.maidroid.ui.component.FlatSwitch
import org.maiwithu.maidroid.ui.component.LiquidButton
import org.maiwithu.maidroid.ui.component.LiquidButtonStyle
import org.maiwithu.maidroid.ui.component.LiquidSegmentItem
import org.maiwithu.maidroid.ui.component.LiquidSegmentedSelector
import org.maiwithu.maidroid.ui.component.MaiDialog
import org.maiwithu.maidroid.ui.component.MaiDialogButton
import org.maiwithu.maidroid.ui.component.MaiDialogButtonRow
import org.maiwithu.maidroid.ui.component.MaiDialogButtonStyle
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardBorder
import org.maiwithu.maidroid.ui.theme.CardDivider
import org.maiwithu.maidroid.ui.theme.CardSurface
import org.maiwithu.maidroid.ui.theme.CardSurfaceSelected
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.IconRoseAlert
import org.maiwithu.maidroid.ui.theme.StatusOnline
import org.maiwithu.maidroid.ui.theme.StatusWarning
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val BACKUP_TIME_DISPLAY_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.CHINA)
private val QUICK_SCHEDULE_PRESETS = listOf(2 to 0, 4 to 0, 6 to 0, 23 to 0)

/** 生成设置页“自动备份”一行的副标题摘要。 */
internal fun backupSettingsSummaryText(state: BackupState): String {
    if (state.running) return "正在备份..."
    val config = state.config
    val targetLabel = if (config.usesCustomTree) {
        config.customTreeLabel.ifBlank { "自定义文件夹" }
    } else {
        DEFAULT_EXTERNAL_DISPLAY_PATH
    }
    if (!config.autoEnabled) return "未开启 · $targetLabel"
    val base = "每天 ${formatScheduleTime(config.scheduleHour, config.scheduleMinute)} · $targetLabel"
    return if (state.lastError.isNotBlank()) "$base · 上次失败" else base
}

@Composable
internal fun BackupOverviewCard(
    state: BackupState,
    onAutoToggle: (Boolean) -> Unit,
    onManualBackup: () -> Unit,
    modifier: Modifier = Modifier
) {
    val config = state.config
    val nowMillis = remember(state) { System.currentTimeMillis() }
    val nextHint = state.nextScheduledAtMillis?.let {
        formatNextScheduleHint(nowMillis, it, ZoneId.systemDefault())
    }

    BackupSectionCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "每日定时自动备份",
                    color = TextPrimary,
                    fontSize = TextSizes.BodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = if (config.autoEnabled && nextHint != null) {
                        "下次计划：$nextHint"
                    } else {
                        "默认每天凌晨 04:00 自动备份配置与记忆库"
                    },
                    color = TextMuted,
                    fontSize = TextSizes.Caption,
                    lineHeight = 16.sp
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            FlatSwitch(
                checked = config.autoEnabled,
                onCheckedChange = onAutoToggle
            )
        }

        if (state.running) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = BrandOrange
                )
                Text(
                    text = state.progressText.ifBlank { "正在备份..." },
                    color = BrandOrange,
                    fontSize = TextSizes.Caption
                )
            }
        } else if (state.lastError.isNotBlank()) {
            Text(
                text = state.lastError,
                color = StatusWarning,
                fontSize = TextSizes.Caption,
                lineHeight = 16.sp
            )
        } else if (state.lastSuccessAtMillis > 0L) {
            val timeText = formatBackupTimestamp(state.lastSuccessAtMillis)
            Text(
                text = "上次成功：$timeText（${state.lastBackupSummary}）",
                color = StatusOnline,
                fontSize = TextSizes.Caption,
                lineHeight = 16.sp
            )
        }

        LiquidButton(
            onClick = onManualBackup,
            enabled = !state.running,
            style = LiquidButtonStyle.Primary,
            shape = { RoundedRectangle(Corners.Control) },
            minHeight = 42.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (state.running) "备份进行中..." else "立即备份",
                fontSize = TextSizes.Body,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
internal fun BackupLocationCard(
    state: BackupState,
    storageGranted: Boolean,
    onRequestStorage: () -> Unit,
    onPickFolder: () -> Unit,
    onUseDefault: () -> Unit,
    modifier: Modifier = Modifier
) {
    val config = state.config
    val needsStoragePermission = !config.usesCustomTree && !storageGranted

    BackupSectionCard(modifier = modifier) {
        Text(
            text = "备份位置",
            color = TextPrimary,
            fontSize = TextSizes.BodyLarge,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = if (config.usesCustomTree) {
                "自定义文件夹：${config.customTreeLabel.ifBlank { "已授权文件夹" }}"
            } else {
                "默认目录：$DEFAULT_EXTERNAL_DISPLAY_PATH"
            },
            color = TextMuted,
            fontSize = TextSizes.Caption,
            lineHeight = 16.sp
        )
        Text(
            text = when {
                config.usesCustomTree -> "已通过系统文件夹选择器持久化授权，无需外部存储权限。"
                storageGranted -> "外部存储权限已授权，备份包会写入公共目录，重装应用后仍保留。"
                else -> "默认目录需要外部存储权限才能读写；你也可以点击下方「选择文件夹」免权限保存。"
            },
            color = if (needsStoragePermission) StatusWarning else TextMuted,
            fontSize = TextSizes.Caption,
            lineHeight = 16.sp
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            LiquidButton(
                onClick = onPickFolder,
                style = LiquidButtonStyle.Secondary,
                shape = { RoundedRectangle(Corners.Control) },
                minHeight = 38.dp,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Outlined.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "选择文件夹", fontSize = TextSizes.Caption, fontWeight = FontWeight.Medium)
            }
            if (config.usesCustomTree) {
                LiquidButton(
                    onClick = onUseDefault,
                    style = LiquidButtonStyle.Secondary,
                    shape = { RoundedRectangle(Corners.Control) },
                    minHeight = 38.dp,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = "恢复默认", fontSize = TextSizes.Caption, fontWeight = FontWeight.Medium)
                }
            } else if (needsStoragePermission) {
                LiquidButton(
                    onClick = onRequestStorage,
                    style = LiquidButtonStyle.Primary,
                    shape = { RoundedRectangle(Corners.Control) },
                    minHeight = 38.dp,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = "去授权", fontSize = TextSizes.Caption, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
internal fun BackupScheduleCard(
    state: BackupState,
    onOpenTimePicker: () -> Unit,
    onSelectQuickTime: (hour: Int, minute: Int) -> Unit,
    onSelectRetention: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val config = state.config
    val retentionItems = remember { RETENTION_OPTIONS.map { LiquidSegmentItem("$it 份") } }
    val selectedRetentionIdx = RETENTION_OPTIONS.indexOf(config.retentionCount).takeIf { it >= 0 }

    BackupSectionCard(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Corners.Control))
                .clickable(onClick = onOpenTimePicker)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "每日执行时间",
                    color = TextPrimary,
                    fontSize = TextSizes.BodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "点按调整 24 小时制触发时刻（同一天最多自动备份一次）",
                    color = TextMuted,
                    fontSize = TextSizes.Caption
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Schedule,
                    contentDescription = null,
                    tint = BrandOrange,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = formatScheduleTime(config.scheduleHour, config.scheduleMinute),
                    color = BrandOrange,
                    fontSize = TextSizes.BodyLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QUICK_SCHEDULE_PRESETS.forEach { (hour, minute) ->
                val selected = config.scheduleHour == hour && config.scheduleMinute == minute
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(Corners.Control))
                        .background(if (selected) CardSurfaceSelected else CardDivider.copy(alpha = 0.45f))
                        .border(
                            width = 0.8.dp,
                            color = if (selected) BrandOrange else CardBorder,
                            shape = RoundedCornerShape(Corners.Control)
                        )
                        .clickable { onSelectQuickTime(hour, minute) }
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = formatScheduleTime(hour, minute),
                        color = if (selected) BrandOrange else TextMuted,
                        fontSize = TextSizes.Caption,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }

        Text(
            text = "保留最近备份份数（自动删除超出数量的旧包）",
            color = TextMuted,
            fontSize = TextSizes.Caption
        )
        LiquidSegmentedSelector(
            items = retentionItems,
            selectedIndex = selectedRetentionIdx,
            onSelect = { idx ->
                RETENTION_OPTIONS.getOrNull(idx)?.let(onSelectRetention)
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
internal fun BackupComponentsCard(
    selected: Set<BackupComponent>,
    onToggle: (BackupComponent, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    BackupSectionCard(modifier = modifier) {
        Text(
            text = "备份内容",
            color = TextPrimary,
            fontSize = TextSizes.BodyLarge,
            fontWeight = FontWeight.Medium
        )
        BackupComponent.entries.forEachIndexed { index, component ->
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(CardDivider)
                )
            }
            val checked = component in selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle(component, !checked) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = component.label,
                        color = TextPrimary,
                        fontSize = TextSizes.Body,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = component.description,
                        color = TextMuted,
                        fontSize = TextSizes.Caption,
                        lineHeight = 15.sp
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                FlatSwitch(
                    checked = checked,
                    onCheckedChange = { onToggle(component, it) }
                )
            }
        }
        Text(
            text = "注意：备份包内含模型 API 密钥、WebUI 凭据与协议端 Token 等敏感数据，请妥善保管并勿公开分享。",
            color = TextMuted,
            fontSize = TextSizes.Caption,
            lineHeight = 15.sp
        )
    }
}

@Composable
internal fun BackupHistoryCard(
    state: BackupState,
    onRefresh: () -> Unit,
    onDeleteRequest: (BackupRecord) -> Unit,
    modifier: Modifier = Modifier
) {
    BackupSectionCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "已有备份（${state.records.size}）",
                color = TextPrimary,
                fontSize = TextSizes.BodyLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.Outlined.Refresh,
                contentDescription = "刷新备份列表",
                tint = TextMuted,
                modifier = Modifier
                    .size(22.dp)
                    .clickable(enabled = !state.loadingRecords, onClick = onRefresh)
            )
        }

        if (state.loadingRecords) {
            Text(text = "正在读取备份列表...", color = TextMuted, fontSize = TextSizes.Caption)
        } else if (state.records.isEmpty()) {
            Text(text = "当前备份目录暂无备份压缩包", color = TextMuted, fontSize = TextSizes.Caption)
        } else {
            state.records.forEachIndexed { index, record ->
                if (index > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(CardDivider)
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = record.fileName,
                            color = TextPrimary,
                            fontSize = TextSizes.Caption,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val timeText = record.lastModifiedMillis.takeIf { it > 0L }?.let(::formatBackupTimestamp) ?: "时间未知"
                        Text(
                            text = "$timeText · ${formatBytes(record.sizeBytes)}",
                            color = TextMuted,
                            fontSize = TextSizes.Caption
                        )
                    }
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = "删除 ${record.fileName}",
                        tint = TextMuted,
                        modifier = Modifier
                            .size(22.dp)
                            .clickable { onDeleteRequest(record) }
                    )
                }
            }
        }
    }
}

@Composable
internal fun BackupDeleteConfirmDialog(
    record: BackupRecord,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    MaiDialog(
        title = "删除备份文件？",
        subtitle = record.fileName,
        icon = FlatFeatureIcons.Backup,
        iconBackground = IconRoseAlert,
        onDismissRequest = onDismiss,
        footer = {
            MaiDialogButtonRow {
                MaiDialogButton(
                    text = "取消",
                    style = MaiDialogButtonStyle.Secondary,
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss
                )
                MaiDialogButton(
                    text = "删除",
                    style = MaiDialogButtonStyle.Danger,
                    modifier = Modifier.weight(1f),
                    onClick = onConfirm
                )
            }
        }
    ) {
        Text(
            text = "将永久删除该备份压缩包（${formatBytes(record.sizeBytes)}），不会影响容器内当前配置。",
            color = TextMuted,
            fontSize = TextSizes.Body,
            lineHeight = 19.sp
        )
    }
}

@Composable
private fun BackupSectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corners.Chip))
            .background(CardSurface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

internal fun showBackupOutcomeToast(context: Context, outcome: BackupRunOutcome) {
    val msg = when (outcome) {
        is BackupRunOutcome.Success -> "备份完成：${outcome.record.fileName}"
        BackupRunOutcome.PermissionRequired -> "请先授予外部存储权限"
        is BackupRunOutcome.SafAccessLost -> outcome.detail
        BackupRunOutcome.EmptySelection -> "请至少勾选一项备份内容"
        BackupRunOutcome.NoFilesFound -> "所选组件当前没有可备份的文件"
        is BackupRunOutcome.Failed -> "备份失败：${outcome.reason}"
    }
    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}

private fun formatBackupTimestamp(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(BACKUP_TIME_DISPLAY_FORMATTER)
