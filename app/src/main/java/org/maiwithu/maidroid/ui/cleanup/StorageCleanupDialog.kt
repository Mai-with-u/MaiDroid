package org.maiwithu.maidroid.ui.cleanup

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.maiwithu.maidroid.appDependencies
import org.maiwithu.maidroid.appupdate.formatBytes
import org.maiwithu.maidroid.cleanup.CleanupItem
import org.maiwithu.maidroid.cleanup.CleanupOutcome
import org.maiwithu.maidroid.cleanup.CleanupSizes
import org.maiwithu.maidroid.ui.component.FlatFeatureIcons
import org.maiwithu.maidroid.ui.component.MaiDialog
import org.maiwithu.maidroid.ui.component.MaiDialogButton
import org.maiwithu.maidroid.ui.component.MaiDialogButtonRow
import org.maiwithu.maidroid.ui.component.MaiDialogButtonStyle
import org.maiwithu.maidroid.ui.component.MaiDialogCallout
import org.maiwithu.maidroid.ui.component.MaiDialogDivider
import org.maiwithu.maidroid.ui.component.MaiDialogInfoChip
import org.maiwithu.maidroid.ui.component.MaiDialogSectionCard
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardBorder
import org.maiwithu.maidroid.ui.theme.IconTealMint
import org.maiwithu.maidroid.ui.theme.StatusOnline
import org.maiwithu.maidroid.ui.theme.StatusWarning
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes

// 清理空间弹窗的几个阶段：量大小 -> 容器还没装好（只能去系统设置）/ 量好了等勾选 -> 清理中 -> 清理完。
private sealed interface CleanupStage {
    data object Measuring : CleanupStage
    data object Unavailable : CleanupStage
    data class Ready(val sizes: CleanupSizes) : CleanupStage
    data object Cleaning : CleanupStage
    data class Done(val outcome: CleanupOutcome) : CleanupStage
}

/**
 * “清理空间”弹窗：一打开就量每类占多少 -> 默认勾上缓存几类（日志默认不勾）-> 点“清理”删除勾选的 ->
 * 报手机实际多出来的空间，以及因为正在装依赖、装软件而跳过的。设置页和“空间不足”的失败提示都从这里进。
 * 容器还没装好时没有可清的，改为提供系统的存储设置入口。
 */
@Composable
internal fun StorageCleanupDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val cleanup = remember(context) { context.appDependencies.storageCleanup }
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf<CleanupStage>(CleanupStage.Measuring) }
    var selectedKeys by rememberSaveable {
        mutableStateOf(CleanupItem.entries.filter { it.selectedByDefault }.map { it.key }.toSet())
    }
    val selected = CleanupItem.entries.filter { it.key in selectedKeys }.toSet()

    // 打开时量一次；清理完不再自动重量，结果页直接用清理脚本最后量的大小
    LaunchedEffect(Unit) {
        stage = cleanup.measure()?.let { CleanupStage.Ready(it) } ?: CleanupStage.Unavailable
    }

    val freeSpaceText = (stage as? CleanupStage.Ready)?.let { "可用 ${formatBytes(it.sizes.freeBytes)}" }
    val canDismiss = stage != CleanupStage.Cleaning

    MaiDialog(
        title = "清理空间",
        subtitle = freeSpaceText ?: "删除下载缓存，不动配置与聊天记录",
        icon = FlatFeatureIcons.Cleanup,
        iconBackground = IconTealMint,
        showCloseButton = canDismiss,
        dismissOnBackPress = canDismiss,
        dismissOnClickOutside = canDismiss,
        onDismissRequest = { if (canDismiss) onDismiss() },
        footer = {
            when (val current = stage) {
                CleanupStage.Unavailable -> MaiDialogButtonRow {
                    MaiDialogButton(
                        text = "关闭",
                        style = MaiDialogButtonStyle.Secondary,
                        modifier = Modifier.weight(0.78f),
                        onClick = onDismiss
                    )
                    MaiDialogButton(
                        text = "打开存储设置",
                        style = MaiDialogButtonStyle.Primary,
                        modifier = Modifier.weight(1.22f),
                        onClick = {
                            openStorageSettings(context)
                            onDismiss()
                        }
                    )
                }
                is CleanupStage.Ready -> {
                    val selectedBytes = selected.sumOf { current.sizes.sizes[it] ?: 0L }
                    // 精简前缀并适当加宽主按钮占比，防止“清理（约 x.xx GB）”在半宽按钮里截断成省略号。
                    val cleanLabel = if (selected.isNotEmpty() && selectedBytes > 0L) {
                        "清理约 ${formatBytes(selectedBytes)}"
                    } else {
                        "清理"
                    }
                    MaiDialogButtonRow {
                        MaiDialogButton(
                            text = "取消",
                            style = MaiDialogButtonStyle.Secondary,
                            modifier = Modifier.weight(0.78f),
                            onClick = onDismiss
                        )
                        MaiDialogButton(
                            text = cleanLabel,
                            style = MaiDialogButtonStyle.Primary,
                            enabled = selected.isNotEmpty(),
                            modifier = Modifier.weight(1.22f),
                            onClick = {
                                val items = selected
                                stage = CleanupStage.Cleaning
                                scope.launch { stage = CleanupStage.Done(cleanup.clean(items)) }
                            }
                        )
                    }
                }
                CleanupStage.Measuring,
                CleanupStage.Cleaning -> MaiDialogButtonRow {
                    if (stage == CleanupStage.Measuring) {
                        MaiDialogButton(
                            text = "取消",
                            style = MaiDialogButtonStyle.Secondary,
                            modifier = Modifier.weight(1f),
                            onClick = onDismiss
                        )
                    }
                    MaiDialogButton(
                        text = if (stage == CleanupStage.Cleaning) "正在清理..." else "统计中...",
                        style = MaiDialogButtonStyle.Primary,
                        enabled = false,
                        modifier = Modifier.weight(1f),
                        onClick = {}
                    )
                }
                is CleanupStage.Done -> MaiDialogButton(
                    text = "完成",
                    style = MaiDialogButtonStyle.Primary,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onDismiss
                )
            }
        }
    ) {
        when (val current = stage) {
            CleanupStage.Measuring -> MaiDialogSectionCard {
                Progress("正在统计各类文件的大小...")
            }
            CleanupStage.Unavailable -> BodyText(
                "容器还没有准备好，暂时没有能清理的缓存。可以打开系统的存储设置，清理别的应用或文件。"
            )
            is CleanupStage.Ready -> {
                MaiDialogSectionCard(
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    CleanupItem.entries.forEachIndexed { index, item ->
                        if (index > 0) MaiDialogDivider()
                        CleanupRow(
                            item = item,
                            size = current.sizes.sizes[item],
                            checked = item in selected,
                            onCheckedChange = { checked ->
                                selectedKeys = if (checked) selectedKeys + item.key else selectedKeys - item.key
                            }
                        )
                    }
                }
                Text(
                    text = "配置、聊天数据、插件和已经装好的依赖都不会被删。",
                    color = TextMuted,
                    fontSize = TextSizes.Caption,
                    lineHeight = 16.sp
                )
            }
            CleanupStage.Cleaning -> MaiDialogSectionCard {
                Progress("正在清理，请不要关闭应用...")
            }
            is CleanupStage.Done -> DoneContent(current.outcome)
        }
    }
}

// 一类可清理的内容：勾选框、名称和说明、右侧体积胶囊；整行可点。
@Composable
private fun CleanupRow(item: CleanupItem, size: Long?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Checkbox) { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(checkedColor = BrandOrange, uncheckedColor = TextMuted),
            modifier = Modifier.padding(end = 10.dp)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = item.label,
                color = TextPrimary,
                fontSize = TextSizes.Body,
                fontWeight = FontWeight.Medium,
                lineHeight = 18.sp
            )
            Text(text = item.detail, color = TextMuted, fontSize = TextSizes.Caption, lineHeight = 15.sp)
        }
        MaiDialogInfoChip(
            text = size?.let(::formatBytes) ?: "未知",
            color = if (checked && (size ?: 0L) > 0L) BrandOrange else TextMuted,
            containerColor = CardBorder
        )
    }
}

// 清理结果：多出来的空间；有跳过的说明原因；脚本没跑成功时提示去终端看。
@Composable
private fun DoneContent(outcome: CleanupOutcome) {
    MaiDialogCallout(
        title = "清理完成",
        body = "手机多出约 ${formatBytes(outcome.freedBytes)}。",
        accentColor = StatusOnline
    )
    if (outcome.skipped.isNotEmpty()) {
        MaiDialogCallout(
            title = "部分内容已跳过",
            body = "${outcome.skipped.joinToString("、") { it.label }}正在使用（装依赖或装软件），这次先跳过了，装完再来清理。",
            accentColor = StatusWarning
        )
    }
    if (!outcome.success) {
        MaiDialogCallout(
            title = "未完全清理",
            body = "有一部分没能清理，详情可查看终端输出。",
            accentColor = StatusWarning
        )
    }
}

@Composable
private fun Progress(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(color = BrandOrange, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        BodyText(text)
    }
}

@Composable
private fun BodyText(text: String, color: Color = TextMuted) {
    Text(text = text, color = color, fontSize = TextSizes.Body, lineHeight = 19.sp)
}

/** 打开系统的存储设置；容器没装好、或者应用里清完还不够时用。 */
internal fun openStorageSettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        Toast.makeText(context, "打不开系统设置，请手动打开“设置” -> “存储”", Toast.LENGTH_SHORT).show()
    }
}
