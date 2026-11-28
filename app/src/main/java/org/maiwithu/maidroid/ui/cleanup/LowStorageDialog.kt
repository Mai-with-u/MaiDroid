package org.maiwithu.maidroid.ui.cleanup

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import org.maiwithu.maidroid.runtime.formatBytes
import org.maiwithu.maidroid.ui.component.FlatFeatureIcons
import org.maiwithu.maidroid.ui.component.MaiDialog
import org.maiwithu.maidroid.ui.component.MaiDialogButton
import org.maiwithu.maidroid.ui.component.MaiDialogButtonRow
import org.maiwithu.maidroid.ui.component.MaiDialogButtonStyle
import org.maiwithu.maidroid.ui.component.MaiDialogInfoChip
import org.maiwithu.maidroid.ui.theme.IconWarmCoral
import org.maiwithu.maidroid.ui.theme.StatusWarning
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextSizes

/**
 * 麦麦跑着的时候手机存储快满了：提醒一次，给“清理空间”入口。存储写满后麦麦的数据库、记忆和日志都写不进去，
 * 会在聊天时莫名其妙地报错，等用户发现时往往已经丢了一段记录，所以快满时就先说。
 * “清理空间”接着打开清理弹窗（关掉清理弹窗即结束提醒）；“以后再说”这次打开应用期间不再提醒。
 */
@Composable
internal fun LowStorageDialog(freeBytes: Long, onDismiss: () -> Unit) {
    var cleaning by rememberSaveable { mutableStateOf(false) }
    if (cleaning) {
        StorageCleanupDialog(onDismiss = onDismiss)
        return
    }
    MaiDialog(
        title = "手机存储快满了",
        subtitle = "避免数据库与聊天记录写入失败",
        icon = FlatFeatureIcons.Storage,
        iconBackground = IconWarmCoral,
        headerBadge = {
            MaiDialogInfoChip(text = "仅剩 ${formatBytes(freeBytes)}", color = StatusWarning)
        },
        onDismissRequest = onDismiss,
        footer = {
            MaiDialogButtonRow {
                MaiDialogButton(
                    text = "以后再说",
                    style = MaiDialogButtonStyle.Secondary,
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss
                )
                MaiDialogButton(
                    text = "清理空间",
                    style = MaiDialogButtonStyle.Primary,
                    modifier = Modifier.weight(1f),
                    onClick = { cleaning = true }
                )
            }
        }
    ) {
        Text(
            text = "存储写满后麦麦的聊天记录、记忆和日志都写不进去，可能会引发异常。可以先清掉 MaiDroid 自己的下载缓存，不够的话再去系统设置里清理别的应用。",
            color = TextMuted,
            fontSize = TextSizes.Body,
            lineHeight = 19.sp
        )
    }
}
