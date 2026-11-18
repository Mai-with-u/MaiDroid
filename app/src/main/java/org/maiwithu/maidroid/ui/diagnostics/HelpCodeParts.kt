package org.maiwithu.maidroid.ui.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardBorder
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.JetBrainsMono
import org.maiwithu.maidroid.ui.theme.TerminalBackground
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes

/**
 * 6 位故障求助码展示条：报错提示（FailureNotice）和反馈问题弹窗（FeedbackDialog）生成求助码后共用。
 * 用醒目大号橙色等宽字展示 `#XXX-XXX`，即使不会复制粘贴、直接截屏发进群里也能一眼认出。
 */
@Composable
internal fun HelpCodeCard(
    code: String,
    errorCode: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corners.Control))
            .background(TerminalBackground)
            .clickable(role = Role.Button, onClickLabel = "复制求助码") {
                copyHelpCodeToClipboard(context, code, errorCode)
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = "故障求助码（直接发到反馈群即可排查）",
                color = TextMuted,
                fontSize = TextSizes.Caption,
                lineHeight = 15.sp
            )
            Text(
                text = "#$code",
                color = BrandOrange,
                fontFamily = JetBrainsMono,
                fontSize = TextSizes.Title,
                fontWeight = FontWeight.Bold,
                lineHeight = 22.sp
            )
        }
        Text(
            text = "复制求助码",
            color = TextPrimary,
            fontSize = TextSizes.Caption,
            fontWeight = FontWeight.Bold,
            lineHeight = 15.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(Corners.Tag))
                .background(CardBorder)
                .padding(horizontal = 8.dp, vertical = 5.dp)
        )
    }
}

internal fun copyHelpCodeToClipboard(context: Context, code: String, errorCode: String?) {
    val summary = if (!errorCode.isNullOrBlank()) {
        "求助码 #$code（错误码 $errorCode）"
    } else {
        "求助码 #$code"
    }
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText("MaiDroid 求助码", summary))
    val msg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        "可直接粘贴到 QQ 反馈群求助"
    } else {
        "已复制 $summary，可直接发到 QQ 反馈群"
    }
    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
}
