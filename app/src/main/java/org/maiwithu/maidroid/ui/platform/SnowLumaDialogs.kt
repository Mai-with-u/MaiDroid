package org.maiwithu.maidroid.ui.platform

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maiwithu.maidroid.platform.qq.QqClient
import org.maiwithu.maidroid.platform.qq.QqOneBot
import org.maiwithu.maidroid.platform.snowluma.QqScreenFrame
import org.maiwithu.maidroid.platform.snowluma.SnowLumaHookPhase
import org.maiwithu.maidroid.platform.snowluma.SnowLumaPlatformState
import org.maiwithu.maidroid.platform.snowluma.SnowLumaScripts
import org.maiwithu.maidroid.platform.snowluma.snowLumaLoginHint
import org.maiwithu.maidroid.ui.diagnostics.FailureNotice
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardBorder
import org.maiwithu.maidroid.ui.theme.CardSurface
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.StatusDanger
import org.maiwithu.maidroid.ui.theme.StatusOnline
import org.maiwithu.maidroid.ui.theme.TextFaint
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes

// QQ（SnowLuma）这一路的弹窗：安装前的协议说明、QQ 窗口、配置详情，以及切换协议端、重启 QQ 的二次确认。

private const val SNOWLUMA_EULA_URL = "https://github.com/SnowLuma/SnowLuma/blob/v${SnowLumaScripts.VERSION}/EULA.md"
private const val SNOWLUMA_PRIVACY_URL = "https://github.com/SnowLuma/SnowLuma/blob/v${SnowLumaScripts.VERSION}/PRIVACY.md"

/**
 * 装 SnowLuma 之前的说明：它是什么、要下载什么、闭源组件只能非商业使用，以及它的用户协议和隐私政策。
 * SnowLuma 启动时 MaiDroid 会替用户确认这两份协议（免得它的管理页拦着），所以必须先让用户看过、点了同意。
 * 已经装着 NapCat 时说清楚：装好后 NapCat 停用、不卸载。
 */
@Composable
internal fun SnowLumaAgreementDialog(
    napCatInstalled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { DialogTitle("安装 SnowLuma") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                DialogBody(
                    "SnowLuma 是另一种登录个人 QQ 的方式：MaiDroid 会在容器里装 LinuxQQ ${SnowLumaScripts.QQ_VERSION} " +
                        "和 SnowLuma ${SnowLumaScripts.VERSION}（要下载约 200 MB，装好后约占 1 GB），" +
                        "装好后在 QQ 窗口里扫码登录。"
                )
                if (napCatInstalled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    DialogBody("装好后改用 SnowLuma 登录 QQ，NapCat 会停用（不卸载，随时可以切回）。")
                }
                Spacer(modifier = Modifier.height(8.dp))
                DialogBody(
                    "SnowLuma 含有闭源的原生组件，只能用于非商业用途。" +
                        "点“同意并安装”即表示你已阅读并同意 SnowLuma 的用户协议和隐私政策。"
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { uriHandler.openUri(SNOWLUMA_EULA_URL) }) {
                        Text(text = "用户协议", color = BrandOrange, fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = { uriHandler.openUri(SNOWLUMA_PRIVACY_URL) }) {
                        Text(text = "隐私政策", color = BrandOrange, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        dismissButton = { SecondaryButton(text = "取消", onClick = onDismiss) },
        confirmButton = { PrimaryButton(text = "同意并安装", onClick = onConfirm) },
        containerColor = CardSurface,
        titleContentColor = TextPrimary,
        textContentColor = TextMuted
    )
}

/**
 * 现在启用的是另一种协议端时，确认要不要切过去：两者共用一个 QQ，只能启用一个。
 * 目标还没装（只会是 NapCat）时说明确认后开始安装。
 */
@Composable
internal fun QqClientSwitchDialog(
    target: QqClient,
    targetInstalled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val other = if (target == QqClient.NapCat) QqClient.SnowLuma else QqClient.NapCat
    val login = when (target) {
        QqClient.NapCat -> "之后可能需要重新扫码。"
        QqClient.SnowLuma -> "之后在 QQ 窗口里登录，通常点一下“登录”就能用上次的账号。"
    }
    val install = if (targetInstalled) "" else "${target.label} 还没装，确认后开始下载安装。"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { DialogTitle("改用 ${target.label} 登录 QQ？") },
        text = {
            DialogBody(
                "${other.label} 会停下（不卸载，随时可以切回），QQ 先下线，再由 ${target.label} 重新启动，" +
                    "期间麦麦收发不了 QQ 消息。$login$install"
            )
        },
        dismissButton = { SecondaryButton(text = "取消", onClick = onDismiss) },
        confirmButton = { PrimaryButton(text = if (targetInstalled) "切换" else "安装并切换", onClick = onConfirm) },
        containerColor = CardSurface,
        titleContentColor = TextPrimary,
        textContentColor = TextMuted
    )
}

/**
 * QQ 窗口：显示容器里 QQ 的窗口画面（每秒刷新），点画面上的哪里就替用户在 QQ 窗口的那里点一下。
 * SnowLuma 不出二维码，扫码登录、二维码过期后刷新、快速登录都在这里做。
 * 下面是 SnowLuma 汇报的登录进度和安装失败的原因。
 */
@Composable
internal fun QqWindowDialog(
    state: SnowLumaPlatformState,
    frame: QqScreenFrame?,
    onTap: (QqScreenFrame, Float, Float) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { DialogTitle("QQ 窗口") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                QqWindowImage(frame = frame, onTap = onTap)
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "点画面就是点 QQ 窗口。可以截图发给其他设备，再用手机 QQ 扫码",
                    color = TextFaint,
                    fontSize = TextSizes.Caption,
                    lineHeight = 14.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = snowLumaLoginHint(state),
                    color = if (state.hookPhase == SnowLumaHookPhase.Failed) StatusDanger else TextMuted,
                    fontSize = TextSizes.Caption,
                    lineHeight = 15.sp
                )
                state.failure?.let { failure ->
                    Spacer(modifier = Modifier.height(8.dp))
                    FailureNotice(failure = failure)
                }
            }
        },
        confirmButton = { PrimaryButton(text = "关闭", onClick = onDismiss) },
        containerColor = CardSurface,
        titleContentColor = TextPrimary,
        textContentColor = TextMuted
    )
}

/** QQ 窗口的画面，按窗口的宽高比铺满弹窗宽度；横屏时限高，免得把说明挤出去。还没有画面时换成一句原因。 */
@Composable
private fun QqWindowImage(
    frame: QqScreenFrame?,
    onTap: (QqScreenFrame, Float, Float) -> Unit
) {
    if (frame == null) {
        Text(
            text = "QQ 窗口还没画出来，请稍等；一直这样的话看终端里 [SnowLuma] 开头的输出。",
            color = TextMuted,
            fontSize = TextSizes.Body,
            lineHeight = 17.sp
        )
        return
    }
    // 画面变了（signature 变了）才重新生成位图
    val bitmap = remember(frame.signature) {
        Bitmap.createBitmap(frame.pixels, frame.width, frame.height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Box(
        modifier = Modifier
            .heightIn(max = 420.dp)
            .aspectRatio(frame.width.toFloat() / frame.height.toFloat())
            .clip(RoundedCornerShape(Corners.Control))
            .background(Color.White)
            // 点下去的位置按这块区域的比例换算，画面和区域同宽高比，比例就是 QQ 窗口里的位置
            .pointerInput(frame.signature) {
                detectTapGestures { offset ->
                    onTap(frame, offset.x / size.width, offset.y / size.height)
                }
            }
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = "QQ 窗口",
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/** SnowLuma 配置详情：连接状态、当前账号、MaiBot 连的地址、版本和配置文件位置，以及查看 QQ 窗口、管理页、重启 QQ。 */
@Composable
internal fun SnowLumaConfigDialog(
    state: SnowLumaPlatformState,
    onRefresh: () -> Unit,
    onShowQqWindow: () -> Unit,
    onOpenWebUi: () -> Unit,
    onRestartQq: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { DialogTitle("SnowLuma 配置") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                ConfigValueRow(
                    label = "连接状态",
                    value = when {
                        !state.active -> "已停用，现在用 NapCat"
                        state.oneBotReachable -> "OneBot 已监听，MaiBot 可连接"
                        else -> snowLumaLoginHint(state)
                    },
                    valueColor = if (state.active && state.oneBotReachable) StatusOnline else TextMuted
                )
                ConfigValueRow("账号", state.accountDisplayName ?: "-")
                ConfigValueRow("服务类型", "WebSocket Server")
                ConfigValueRow("监听地址", "${QqOneBot.HOST}:${QqOneBot.PORT}")
                ConfigValueRow("Token", "和 NapCat 共用一个")
                ConfigValueRow("SnowLuma 版本", SnowLumaScripts.VERSION)
                ConfigValueRow("LinuxQQ 版本", SnowLumaScripts.QQ_VERSION)
                ConfigValueRow("配置文件", "${SnowLumaScripts.DATA_DIR}/config/onebot.json")

                Spacer(modifier = Modifier.height(12.dp))
                WideButton(text = "查看 QQ 窗口", enabled = state.active, onClick = onShowQqWindow)
                Spacer(modifier = Modifier.height(8.dp))
                WideButton(text = "打开 SnowLuma 管理页", enabled = state.active && state.running, onClick = onOpenWebUi)
                Spacer(modifier = Modifier.height(8.dp))
                // 重启 QQ：先弹二次确认；进行中禁用，避免重复重启
                WideButton(
                    text = if (state.qqRestarting) "正在重启…" else "重启 QQ",
                    enabled = state.active && !state.qqRestarting,
                    danger = true,
                    onClick = onRestartQq
                )
            }
        },
        dismissButton = { SecondaryButton(text = "刷新", onClick = onRefresh) },
        confirmButton = { PrimaryButton(text = "关闭", onClick = onDismiss) },
        containerColor = CardSurface,
        titleContentColor = TextPrimary,
        textContentColor = TextMuted
    )
}

/** 重启 QQ 会让 MaiBot 暂时断开 QQ，先让用户确认。SnowLuma 没有退出登录的接口，换号也是重启后在 QQ 窗口里换。 */
@Composable
internal fun SnowLumaRestartQqDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { DialogTitle("重启 QQ？") },
        text = {
            DialogBody(
                "QQ 会先下线再重新启动，MaiBot 暂时收发不了 QQ 消息。之后在 QQ 窗口里重新登录，也可以换别的账号。"
            )
        },
        dismissButton = { SecondaryButton(text = "取消", onClick = onDismiss) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = StatusDanger, contentColor = TextPrimary)
            ) {
                Text(text = "重启 QQ", fontWeight = FontWeight.Bold)
            }
        },
        containerColor = CardSurface,
        titleContentColor = TextPrimary,
        textContentColor = TextMuted
    )
}

@Composable
private fun DialogTitle(text: String) {
    Text(
        text = text,
        color = TextPrimary,
        fontSize = TextSizes.TitleLarge,
        fontWeight = FontWeight.Bold,
        lineHeight = 22.sp
    )
}

@Composable
private fun DialogBody(text: String) {
    Text(text = text, color = TextMuted, fontSize = TextSizes.Body, lineHeight = 18.sp)
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
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

@Composable
private fun WideButton(text: String, enabled: Boolean, onClick: () -> Unit, danger: Boolean = false) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (danger) StatusDanger else CardBorder,
            contentColor = TextPrimary,
            disabledContainerColor = CardBorder,
            disabledContentColor = TextFaint
        )
    ) {
        Text(text = text, fontWeight = FontWeight.Bold)
    }
}
