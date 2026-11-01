package org.maiwithu.maidroid.ui.cleanup

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardBorder
import org.maiwithu.maidroid.ui.theme.CardSurface
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

    AlertDialog(
        onDismissRequest = { if (stage != CleanupStage.Cleaning) onDismiss() },
        title = {
            Text(
                text = "清理空间",
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
                when (val current = stage) {
                    CleanupStage.Measuring -> Progress("正在统计各类文件的大小...")
                    CleanupStage.Unavailable -> BodyText(
                        "容器还没有准备好，暂时没有能清理的缓存。可以打开系统的存储设置，清理别的应用或文件。"
                    )
                    is CleanupStage.Ready -> {
                        BodyText("手机剩余空间 ${formatBytes(current.sizes.freeBytes)}。勾选要清理的内容：")
                        CleanupItem.entries.forEach { item ->
                            CleanupRow(
                                item = item,
                                size = current.sizes.sizes[item],
                                checked = item in selected,
                                onCheckedChange = { checked ->
                                    selectedKeys = if (checked) selectedKeys + item.key else selectedKeys - item.key
                                }
                            )
                        }
                        BodyText("配置、聊天数据、插件和已经装好的依赖都不会被删。")
                    }
                    CleanupStage.Cleaning -> Progress("正在清理，请不要关闭应用...")
                    is CleanupStage.Done -> DoneText(current.outcome)
                }
            }
        },
        dismissButton = {
            when (stage) {
                CleanupStage.Unavailable -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("关闭", onClick = onDismiss)
                }
                is CleanupStage.Ready, CleanupStage.Measuring -> SecondaryButton("取消", onClick = onDismiss)
                else -> Unit
            }
        },
        confirmButton = {
            when (val current = stage) {
                CleanupStage.Unavailable -> PrimaryButton("打开存储设置") {
                    openStorageSettings(context)
                    onDismiss()
                }
                is CleanupStage.Ready -> {
                    val total = selected.sumOf { current.sizes.sizes[it] ?: 0L }
                    PrimaryButton(
                        text = if (selected.isEmpty()) "清理" else "清理（约 ${formatBytes(total)}）",
                        enabled = selected.isNotEmpty()
                    ) {
                        val items = selected
                        stage = CleanupStage.Cleaning
                        scope.launch { stage = CleanupStage.Done(cleanup.clean(items)) }
                    }
                }
                CleanupStage.Measuring, CleanupStage.Cleaning -> PrimaryButton("清理", enabled = false) {}
                is CleanupStage.Done -> PrimaryButton("完成", onClick = onDismiss)
            }
        },
        containerColor = CardSurface,
        titleContentColor = TextPrimary,
        textContentColor = TextMuted
    )
}

// 一类可清理的内容：勾选框、名称和大小、一句说明；整行可点。
@Composable
private fun CleanupRow(item: CleanupItem, size: Long?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Checkbox) { onCheckedChange(!checked) }
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(checkedColor = BrandOrange, uncheckedColor = TextMuted),
            modifier = Modifier.padding(top = 2.dp, end = 10.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "${item.label}  ${size?.let(::formatBytes) ?: "大小未知"}",
                color = TextPrimary,
                fontSize = TextSizes.Body,
                fontWeight = FontWeight.Bold,
                lineHeight = 18.sp
            )
            Text(text = item.detail, color = TextMuted, fontSize = TextSizes.Caption, lineHeight = 16.sp)
        }
    }
}

// 清理结果：多出来的空间；有跳过的说明原因；脚本没跑成功时提示去终端看。
@Composable
private fun DoneText(outcome: CleanupOutcome) {
    BodyText("清理完成，手机多出约 ${formatBytes(outcome.freedBytes)}。", color = StatusOnline)
    if (outcome.skipped.isNotEmpty()) {
        BodyText(
            "${outcome.skipped.joinToString("、") { it.label }}正在使用（装依赖或装软件），这次先跳过了，装完再来清理。",
            color = StatusWarning
        )
    }
    if (!outcome.success) {
        BodyText("有一部分没能清理，详情见终端输出。", color = StatusWarning)
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

@Composable
private fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = BrandOrange, contentColor = TextPrimary)
    ) {
        Text(text = text, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = CardBorder, contentColor = TextPrimary)
    ) {
        Text(text = text, fontWeight = FontWeight.Bold)
    }
}

/** 打开系统的存储设置；容器没装好、或者应用里清完还不够时用。 */
internal fun openStorageSettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        Toast.makeText(context, "打不开系统设置，请手动打开“设置” -> “存储”", Toast.LENGTH_SHORT).show()
    }
}
