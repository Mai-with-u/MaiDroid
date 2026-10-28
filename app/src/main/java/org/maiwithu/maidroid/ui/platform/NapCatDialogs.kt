package org.maiwithu.maidroid.ui.platform

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maiwithu.maidroid.platform.napcat.NapCatLoginPhase
import org.maiwithu.maidroid.platform.napcat.NapCatPlatformState
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardBorder
import org.maiwithu.maidroid.ui.theme.CardSurface
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.StatusDanger
import org.maiwithu.maidroid.ui.theme.StatusOnline
import org.maiwithu.maidroid.ui.theme.TextFaint
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextSizes

// QQ（NapCat）这一路的三个弹窗：登录二维码、NapCat 配置详情、退出 QQ 登录的二次确认。

/** 退出 QQ 会让 MaiBot 断开 QQ，先让用户确认。 */
@Composable
internal fun QqLogoutConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "退出 QQ 登录？",
                color = Color.White,
                fontSize = TextSizes.TitleLarge,
                fontWeight = FontWeight.Bold,
                lineHeight = 22.sp
            )
        },
        text = {
            Text(
                text = "将重启 NapCat，当前 QQ 账号下线，MaiBot 暂时无法收发 QQ 消息。之后需要重新扫码登录，也可以换别的账号。",
                color = TextMuted,
                fontSize = TextSizes.Body,
                lineHeight = 18.sp
            )
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = CardBorder,
                    contentColor = Color.White
                )
            ) {
                Text(text = "取消", fontWeight = FontWeight.Bold)
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = StatusDanger,
                    contentColor = Color.White
                )
            ) {
                Text(text = "退出登录", fontWeight = FontWeight.Bold)
            }
        },
        containerColor = CardSurface,
        titleContentColor = Color.White,
        textContentColor = TextMuted
    )
}

/**
 * QQ 登录二维码弹窗：显示 NapCat 写出的 qrcode.png（文件一更新就重新解码），下方提示扫码进度和错误；
 * “刷新”让 NapCat 重新向 QQ 要一张码，请求进行中按钮禁用。
 */
@Composable
internal fun NapCatQrCodeDialog(
    state: NapCatPlatformState,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    val qrBitmap by produceState<ImageBitmap?>(
        initialValue = null,
        state.qrCodePath,
        state.qrCodeLastModified
    ) {
        val decoded = withContext(Dispatchers.IO) {
            state.qrCodePath
                ?.let { BitmapFactory.decodeFile(it) }
                ?.asImageBitmap()
        }
        // NapCat 覆盖写 qrcode.png 的瞬间可能读到半截文件而解码失败，这时先留着旧图，
        // 写完后修改时间再变一次会重新解码；文件确实没了才清空。
        value = decoded ?: value.takeIf { state.qrCodePath != null }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "QQ 登录二维码",
                color = Color.White,
                fontSize = TextSizes.TitleLarge,
                fontWeight = FontWeight.Bold,
                lineHeight = 22.sp
            )
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val bitmap = qrBitmap
                if (bitmap != null) {
                    Box(
                        modifier = Modifier
                            .size(260.dp)
                            .clip(RoundedCornerShape(Corners.Control))
                            .background(Color.White)
                            .padding(10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = "NapCat 登录二维码",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    Text(
                        text = if (state.installed) {
                            "NapCat 尚未生成二维码，请稍等片刻后刷新。"
                        } else {
                            "NapCat 尚未安装。"
                        },
                        color = TextMuted,
                        fontSize = TextSizes.Body,
                        lineHeight = 17.sp
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "请使用QQ扫码登录……或者截图发给其他设备，然后用QQ扫码",
                    color = TextFaint,
                    fontSize = TextSizes.Caption,
                    lineHeight = 14.sp
                )

                // 扫码进度来自 NapCat：已扫码时提示去手机确认，换码中提示稍等，二维码过期会自动换新。
                napCatQrLoginHint(state)?.let { hint ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = hint,
                        color = TextMuted,
                        fontSize = TextSizes.Caption,
                        lineHeight = 15.sp
                    )
                }

                (state.qrRefreshError ?: state.errorMessage)?.let { error ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = error,
                        color = Color(0xFFFF6B6B),
                        fontSize = TextSizes.Caption,
                        lineHeight = 15.sp
                    )
                }
            }
        },
        dismissButton = {
            // 刷新请求进行中禁用按钮，避免连点把 QQ 登录服务打成频繁出码。
            Button(
                onClick = onRefresh,
                enabled = !state.qrRefreshing,
                colors = ButtonDefaults.buttonColors(
                    containerColor = CardBorder,
                    contentColor = Color.White,
                    disabledContainerColor = CardBorder,
                    disabledContentColor = TextFaint
                )
            ) {
                Text(
                    text = if (state.qrRefreshing) "刷新中…" else "刷新",
                    fontWeight = FontWeight.Bold
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrandOrange,
                    contentColor = Color.White
                )
            ) {
                Text(text = "关闭", fontWeight = FontWeight.Bold)
            }
        },
        containerColor = CardSurface,
        titleContentColor = Color.White,
        textContentColor = TextMuted
    )
}

/** 二维码弹窗里的扫码进度提示；NapCat 未汇报进度（未运行或弹窗刚打开）时不显示。 */
internal fun napCatQrLoginHint(state: NapCatPlatformState): String? = when {
    state.qrRefreshing -> "正在获取新的二维码…"
    state.loginPhase == NapCatLoginPhase.QrCodeScanned -> "已扫码，请在手机 QQ 上确认登录"
    state.loginPhase == NapCatLoginPhase.Initializing -> "登录成功，NapCat 正在初始化…"
    state.loginPhase == NapCatLoginPhase.Reconnecting -> "QQ 登录服务正在重连，稍后会自动生成新二维码"
    state.loginPhase == NapCatLoginPhase.WaitingQrCode -> "二维码约 2 分钟有效，过期后会自动刷新"
    else -> null
}

/** NapCat 配置详情：连接状态、当前账号、OneBot WebSocket 服务端的各项配置，以及“退出 QQ 登录”按钮。 */
@Composable
internal fun NapCatConfigDialog(
    state: NapCatPlatformState,
    onRefresh: () -> Unit,
    onLogoutQq: () -> Unit,
    onDismiss: () -> Unit
) {
    val config = state.oneBotConfig

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "NapCat 配置",
                color = Color.White,
                fontSize = TextSizes.TitleLarge,
                fontWeight = FontWeight.Bold,
                lineHeight = 22.sp
            )
        },
        text = {
            // 配置项加上退出按钮后小屏放不下，内容区允许滚动。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                ConfigValueRow(
                    label = "连接状态",
                    value = when {
                        state.oneBotReachable -> "OneBot 已监听，MaiBot 可连接"
                        config?.enabled == true -> "已启用，等待连接"
                        config != null -> "配置存在但未启用"
                        else -> "未找到 onebot11.json"
                    },
                    valueColor = if (state.oneBotReachable) StatusOnline else TextMuted
                )
                ConfigValueRow("账号", state.accountDisplayName ?: "-")
                ConfigValueRow("服务类型", config?.serviceType ?: "-")
                ConfigValueRow("服务名称", config?.name ?: "-")
                ConfigValueRow("监听地址", config?.host ?: "-")
                ConfigValueRow("监听端口", config?.port?.toString() ?: "-")
                ConfigValueRow("Token", config?.token?.ifBlank { "未设置" } ?: "-")
                ConfigValueRow("消息格式", config?.messagePostFormat?.ifBlank { "-" } ?: "-")
                ConfigValueRow("上报自身消息", config?.reportSelfMessage.toEnabledText())
                ConfigValueRow("强制推送事件", config?.enableForcePushEvent.toEnabledText())
                ConfigValueRow("心跳间隔", config?.heartInterval?.let { "${it}ms" } ?: "-")
                ConfigValueRow("调试模式", config?.debug.toEnabledText())
                ConfigValueRow("配置文件", config?.configPath ?: "/root/napcat/config/onebot11.json")

                // 退出 QQ：先弹二次确认，确认后清自动登录并重启 NapCat；进行中禁用，避免重复重启。
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onLogoutQq,
                    enabled = state.installed && !state.qqLoggingOut,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StatusDanger,
                        contentColor = Color.White,
                        disabledContainerColor = CardBorder,
                        disabledContentColor = TextFaint
                    )
                ) {
                    Text(
                        text = if (state.qqLoggingOut) "正在退出…" else "退出 QQ 登录",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        dismissButton = {
            Button(
                onClick = onRefresh,
                colors = ButtonDefaults.buttonColors(
                    containerColor = CardBorder,
                    contentColor = Color.White
                )
            ) {
                Text(text = "刷新", fontWeight = FontWeight.Bold)
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrandOrange,
                    contentColor = Color.White
                )
            ) {
                Text(text = "关闭", fontWeight = FontWeight.Bold)
            }
        },
        containerColor = CardSurface,
        titleContentColor = Color.White,
        textContentColor = TextMuted
    )
}

private fun Boolean?.toEnabledText(): String = when (this) {
    true -> "开启"
    false -> "关闭"
    null -> "-"
}
