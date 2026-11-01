package org.maiwithu.maidroid.ui.cleanup

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.maiwithu.maidroid.appupdate.formatBytes
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardBorder
import org.maiwithu.maidroid.ui.theme.CardSurface
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextPrimary
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "手机存储快满了",
                color = TextPrimary,
                fontSize = TextSizes.TitleLarge,
                fontWeight = FontWeight.Bold,
                lineHeight = 22.sp
            )
        },
        text = {
            Text(
                text = "只剩 ${formatBytes(freeBytes)}。存储写满后麦麦的聊天记录、记忆和日志都写不进去，可能会出错。" +
                    "可以先清掉 MaiDroid 自己的下载缓存，不够的话再去系统设置里清理别的应用。",
                color = TextMuted,
                fontSize = TextSizes.Body,
                lineHeight = 19.sp
            )
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = CardBorder, contentColor = TextPrimary)
            ) {
                Text(text = "以后再说", fontWeight = FontWeight.Bold)
            }
        },
        confirmButton = {
            Button(
                onClick = { cleaning = true },
                colors = ButtonDefaults.buttonColors(containerColor = BrandOrange, contentColor = TextPrimary)
            ) {
                Text(text = "清理空间", fontWeight = FontWeight.Bold)
            }
        },
        containerColor = CardSurface,
        titleContentColor = TextPrimary,
        textContentColor = TextMuted
    )
}
