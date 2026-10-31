package org.maiwithu.maidroid.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import org.maiwithu.maidroid.maibot.MaiBotProcessStatus
import org.maiwithu.maidroid.ui.theme.BootAccent
import org.maiwithu.maidroid.ui.theme.BootText
import org.maiwithu.maidroid.ui.theme.BootTextDim
import org.maiwithu.maidroid.ui.theme.JetBrainsMono
import org.maiwithu.maidroid.ui.theme.StatusWarning
import org.maiwithu.maidroid.webui.MaiBotWebUi
import kotlin.math.exp

// 唤醒页开机画面里的自检行：三个启动阶段怎么从运行状态判定、进度条该走到哪、每一行怎么排版。

private val BootLineStyle = TextStyle(
    fontFamily = JetBrainsMono,
    fontSize = 12.sp,
    lineHeight = 22.8.sp,
    // 每行固定占满行高：隐藏的行、报错的多行说明和正常行高度一致，逐行出现时整块不上下跳。
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None
    )
)

// 点线引导把状态词对齐到这一列（按字符数）。
private const val BootStatusColumn = 30

/** 自检阶段数：容器、MaiBot 进程、WebUI。打出的行数等于它加一时表示 LINK 行也出来了。 */
internal const val BootStageCount = 3

/** 一行自检的结论：Pending 还在等，Done 通过，Warning 出了状况但还在重试，Failed 卡住、后面的行不再出现。 */
internal enum class BootCheck { Pending, Done, Warning, Failed }

internal data class BootLine(val label: String, val status: String, val check: BootCheck)

/**
 * 三个启动阶段，从 MaiBot 进程状态和 WebUI 探测结果拼出来；exits 只算唤醒页出现之后的意外退出。
 * 容器：启动前检查没通过就是 blocked；MaiBot：进程在跑或 WebUI 已通就是 running，退出过就显示重启次数；WebUI：探测通了就是 online。
 */
internal fun bootLines(webUiOnline: Boolean, status: MaiBotProcessStatus, exits: Int): List<BootLine> {
    val processUp = status.running || webUiOnline
    return listOf(
        if (status.launchBlockedReason != null) {
            BootLine("container runtime", "blocked", BootCheck.Failed)
        } else {
            BootLine("container runtime", "ok", BootCheck.Done)
        },
        when {
            processUp -> BootLine("maibot process", "running", BootCheck.Done)
            exits > 0 -> BootLine("maibot process", "restart ×$exits", BootCheck.Warning)
            else -> BootLine("maibot process", "starting", BootCheck.Pending)
        },
        if (webUiOnline) {
            BootLine("webui endpoint", "online", BootCheck.Done)
        } else {
            BootLine("webui endpoint", "waiting", BootCheck.Pending)
        }
    )
}

/**
 * 进度条按真实阶段推进：每打出一行、每一行有了结论都往前推一截。
 * 等 MaiBot 进程、等 WebUI 这两段耗时不定，进度朝本段上限慢慢逼近（越往后越慢），不会停住，也不会在 WebUI 上线前走满。
 * revealed 是已经打出的自检行数，waitingSeconds 是打出最后一行之后过了多久；有一行卡住（blocked）时返回 null，进度条停在原处。
 */
internal fun bootProgressTarget(revealed: Int, lines: List<BootLine>, waitingSeconds: Float): Float? {
    if (lines.any { it.check == BootCheck.Failed }) return null
    fun creep(from: Float, to: Float, seconds: Float) = from + (to - from) * (1f - exp(-waitingSeconds / seconds))
    return when (revealed) {
        0 -> 0.04f
        1 -> 0.14f
        2 -> if (lines[1].check == BootCheck.Done) 0.36f else creep(0.14f, 0.32f, seconds = 4f)
        3 -> if (lines[2].check == BootCheck.Done) 0.97f else creep(0.36f, 0.95f, seconds = 25f)
        else -> 1f
    }
}

/**
 * 抬头 + 三行自检 + LINK 行。没轮到的行先透明占位，整块高度从一开始就固定；
 * 容器检查没通过时，报错说明占掉后面三行的位置。
 */
@Composable
internal fun BootLog(
    header: String,
    lines: List<BootLine>,
    revealed: Int,
    cursorVisible: Boolean,
    blockedReason: String?,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        BootLogRow(
            text = AnnotatedString(header),
            color = BootText,
            bold = true,
            visible = true
        )
        BootLogRow(
            text = bootLineText(lines[0], cursorVisible),
            color = BootTextDim,
            visible = revealed >= 1
        )
        if (blockedReason != null) {
            Text(
                text = "! $blockedReason",
                style = BootLineStyle,
                color = StatusWarning,
                minLines = 3,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (revealed >= 1) 1f else 0f)
            )
        } else {
            lines.drop(1).forEachIndexed { index, line ->
                BootLogRow(
                    text = bootLineText(line, cursorVisible),
                    color = BootTextDim,
                    visible = revealed >= index + 2
                )
            }
            BootLogRow(
                text = AnnotatedString("LINK ${MaiBotWebUi.URL.removePrefix("http://").trimEnd('/')}"),
                color = BootAccent,
                bold = true,
                visible = revealed > BootStageCount
            )
        }
    }
}

@Composable
private fun BootLogRow(
    text: AnnotatedString,
    color: Color,
    visible: Boolean,
    bold: Boolean = false
) {
    Text(
        text = text,
        style = BootLineStyle,
        color = color,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (visible) 1f else 0f)
    )
}

// “label ........ status”：点线补齐到 BootStatusColumn 列，状态词竖着对齐。
// 还在等的阶段在状态词后面闪一个下划线光标；熄灭时换成透明而不是删掉，行宽不跟着跳。
private fun bootLineText(line: BootLine, cursorVisible: Boolean): AnnotatedString = buildAnnotatedString {
    append(line.label)
    append(' ')
    append(".".repeat((BootStatusColumn - line.label.length - 2).coerceAtLeast(3)))
    append(' ')
    val statusColor = when (line.check) {
        BootCheck.Warning, BootCheck.Failed -> StatusWarning
        else -> BootTextDim
    }
    withStyle(SpanStyle(color = statusColor)) { append(line.status) }
    if (line.check == BootCheck.Pending || line.check == BootCheck.Warning) {
        withStyle(SpanStyle(color = if (cursorVisible) statusColor else Color.Transparent)) { append('_') }
    }
}
