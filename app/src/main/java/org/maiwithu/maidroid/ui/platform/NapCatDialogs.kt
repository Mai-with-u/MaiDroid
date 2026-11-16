package org.maiwithu.maidroid.ui.platform

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maiwithu.maidroid.platform.napcat.NapCatLoginPhase
import org.maiwithu.maidroid.platform.napcat.NapCatPlatformState
import org.maiwithu.maidroid.platform.napcat.NapCatScripts
import org.maiwithu.maidroid.platform.qq.QqClient
import org.maiwithu.maidroid.platform.qq.QqSourceParseResult
import org.maiwithu.maidroid.platform.qq.parseQqCustomSource
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

// QQ（NapCat）这一路的四个弹窗：安装自定义源、登录二维码、NapCat 配置详情、退出 QQ 登录的二次确认。

/**
 * 安装 NapCat 前的自定义仓库填写与安全风险确认弹窗：
 * 上游仓库不可用后默认留空，由用户自行输入 fork 仓库地址或 NapCat.Shell.zip 直链并确认风险后开始安装。
 */
@Composable
internal fun NapCatInstallDialog(
    state: NapCatPlatformState,
    snowLumaInstalled: Boolean,
    onConfirm: (repoUrl: String) -> Unit,
    onDismiss: () -> Unit
) {
    var repoUrlText by rememberSaveable { mutableStateOf(state.customRepoUrl) }
    val validSource = parseQqCustomSource(QqClient.NapCat, repoUrlText) as? QqSourceParseResult.Valid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "安装 NapCat",
                color = TextPrimary,
                fontSize = TextSizes.TitleLarge,
                fontWeight = FontWeight.Bold,
                lineHeight = 22.sp
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                state.failure?.let { failure ->
                    Text(
                        text = "上次安装失败：${failure.message}",
                        color = StatusDanger,
                        fontSize = TextSizes.Caption,
                        lineHeight = 15.sp
                    )
                    FailureNotice(failure = failure)
                }

                Text(
                    text = "原上游仓库已不可用，请填写 NapCat 的 fork 仓库地址（从 Release 下载 NapCat.Shell.zip）" +
                        "或安装包 .zip 直链。MaiDroid 会在容器里安装官方 LinuxQQ ${NapCatScripts.QQ_VERSION} 与 NapCat（约占 1 GB）。",
                    color = TextMuted,
                    fontSize = TextSizes.Body,
                    lineHeight = 18.sp
                )

                if (snowLumaInstalled) {
                    Text(
                        text = "装好后改用 NapCat 登录 QQ，SnowLuma 会停用（不卸载，随时可以切回）。",
                        color = TextMuted,
                        fontSize = TextSizes.Body,
                        lineHeight = 18.sp
                    )
                }

                CustomSourceTextField(
                    client = QqClient.NapCat,
                    value = repoUrlText,
                    onValueChange = { repoUrlText = it }
                )

                CustomSourceRiskNotice(clientLabel = "NapCat")
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
                Text(text = "取消", fontWeight = FontWeight.Bold)
            }
        },
        confirmButton = {
            Button(
                onClick = { validSource?.let { onConfirm(it.source.rawUrl) } },
                enabled = validSource != null && !state.isInstalling,
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrandOrange,
                    contentColor = TextPrimary,
                    disabledContainerColor = CardBorder,
                    disabledContentColor = TextFaint
                )
            ) {
                Text(text = "了解风险并安装", fontWeight = FontWeight.Bold)
            }
        },
        containerColor = CardSurface,
        titleContentColor = TextPrimary,
        textContentColor = TextMuted
    )
}

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
                color = TextPrimary,
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
                    containerColor = StatusDanger,
                    contentColor = TextPrimary
                )
            ) {
                Text(text = "退出登录", fontWeight = FontWeight.Bold)
            }
        },
        containerColor = CardSurface,
        titleContentColor = TextPrimary,
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
                color = TextPrimary,
                fontSize = TextSizes.TitleLarge,
                fontWeight = FontWeight.Bold,
                lineHeight = 22.sp
            )
        },
        text = {
            // 竖排（二维码在上、说明在下）放得下就竖排；手机横屏时弹窗只有一两百 dp 高，
            // 改成二维码在左、说明在右，二维码按可用高度缩小，说明文字太长时可以滚动。
            BoxWithConstraints {
                val availableHeight = maxHeight
                val sideBySide = availableHeight != Dp.Infinity && availableHeight < QrCodeSize + QrHintsMinHeight
                if (sideBySide) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        QrCodeImage(
                            bitmap = qrBitmap,
                            installed = state.installed,
                            size = minOf(QrCodeSize, availableHeight),
                            modifier = Modifier.weight(1f, fill = false)
                        )

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                        ) {
                            QrLoginHints(state)
                        }
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        QrCodeImage(bitmap = qrBitmap, installed = state.installed, size = QrCodeSize)

                        Spacer(modifier = Modifier.height(10.dp))

                        QrLoginHints(state)
                    }
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
                    contentColor = TextPrimary,
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
                    contentColor = TextPrimary
                )
            ) {
                Text(text = "关闭", fontWeight = FontWeight.Bold)
            }
        },
        containerColor = CardSurface,
        titleContentColor = TextPrimary,
        textContentColor = TextMuted
    )
}

/** 二维码边长：竖屏时的固定大小，横屏时按弹窗可用高度缩小，但不超过它。 */
private val QrCodeSize = 260.dp

/** 二维码下方说明文字大约要占的高度，用来判断竖排放不放得下。 */
private val QrHintsMinHeight = 96.dp

/** 白底圆角里的登录二维码；还没有二维码时换成一句原因。 */
@Composable
private fun QrCodeImage(
    bitmap: ImageBitmap?,
    installed: Boolean,
    size: Dp,
    modifier: Modifier = Modifier
) {
    if (bitmap != null) {
        Box(
            modifier = modifier
                .size(size)
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
            text = if (installed) {
                "NapCat 尚未生成二维码，请稍等片刻后刷新。"
            } else {
                "NapCat 尚未安装。"
            },
            color = TextMuted,
            fontSize = TextSizes.Body,
            lineHeight = 17.sp,
            modifier = modifier
        )
    }
}

/** 二维码旁的说明：怎么扫、扫码进度、出错原因。 */
@Composable
private fun QrLoginHints(state: NapCatPlatformState) {
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
            color = StatusDanger,
            fontSize = TextSizes.Caption,
            lineHeight = 15.sp
        )
    }

    // 安装失败时再给错误码、建议和“反馈问题”
    if (state.qrRefreshError == null) {
        state.failure?.let { failure ->
            Spacer(modifier = Modifier.height(8.dp))
            FailureNotice(failure = failure)
        }
    }
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

/** NapCat 配置详情：连接状态、当前账号、OneBot WebSocket 服务端配置、自定义仓库设置与“退出 QQ 登录”按钮。 */
@Composable
internal fun NapCatConfigDialog(
    state: NapCatPlatformState,
    onRefresh: () -> Unit,
    onSaveRepoUrl: (String) -> Unit,
    onReinstallFromRepoUrl: (String) -> Unit,
    onLogoutQq: () -> Unit,
    onDismiss: () -> Unit
) {
    val config = state.oneBotConfig

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "NapCat 配置",
                color = TextPrimary,
                fontSize = TextSizes.TitleLarge,
                fontWeight = FontWeight.Bold,
                lineHeight = 22.sp
            )
        },
        text = {
            // 配置项加上自定义来源和退出按钮后小屏放不下，内容区允许滚动。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                state.failure?.let { failure ->
                    Text(
                        text = "上次操作失败：${failure.message}",
                        color = StatusDanger,
                        fontSize = TextSizes.Caption,
                        lineHeight = 15.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    FailureNotice(failure = failure)
                    Spacer(modifier = Modifier.height(10.dp))
                }
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

                CustomSourceEditorSection(
                    client = QqClient.NapCat,
                    savedUrl = state.customRepoUrl,
                    installing = state.isInstalling,
                    onSaveUrl = onSaveRepoUrl,
                    onRequestReinstall = onReinstallFromRepoUrl
                )

                // 退出 QQ：先弹二次确认，确认后清自动登录并重启 NapCat；进行中禁用，避免重复重启。
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onLogoutQq,
                    enabled = state.installed && !state.qqLoggingOut,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StatusDanger,
                        contentColor = TextPrimary,
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
                    contentColor = TextPrimary
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
                    contentColor = TextPrimary
                )
            ) {
                Text(text = "关闭", fontWeight = FontWeight.Bold)
            }
        },
        containerColor = CardSurface,
        titleContentColor = TextPrimary,
        textContentColor = TextMuted
    )
}

private fun Boolean?.toEnabledText(): String = when (this) {
    true -> "开启"
    false -> "关闭"
    null -> "-"
}
