package org.maiwithu.maidroid.ui.platform

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import org.maiwithu.maidroid.ui.component.FlatFeatureIcons
import org.maiwithu.maidroid.ui.component.LocalMaiDialogPhoneLandscape
import org.maiwithu.maidroid.ui.component.MaiDialog
import org.maiwithu.maidroid.ui.component.MaiDialogButton
import org.maiwithu.maidroid.ui.component.MaiDialogButtonRow
import org.maiwithu.maidroid.ui.component.MaiDialogButtonStyle
import org.maiwithu.maidroid.ui.component.MaiDialogCallout
import org.maiwithu.maidroid.ui.component.MaiDialogInfoChip
import org.maiwithu.maidroid.ui.component.MaiDialogSectionCard
import org.maiwithu.maidroid.ui.component.MaiDialogSize
import org.maiwithu.maidroid.ui.diagnostics.FailureNotice
import org.maiwithu.maidroid.ui.theme.CardBorder
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.IconRoseAlert
import org.maiwithu.maidroid.ui.theme.IconSkyBlue
import org.maiwithu.maidroid.ui.theme.QrCanvasBackground
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
    onConfirm: (sourceUrl: String) -> Unit,
    onDismiss: () -> Unit
) {
    var sourceUrlText by rememberSaveable { mutableStateOf(state.customSourceUrl) }
    val validSource = parseQqCustomSource(QqClient.NapCat, sourceUrlText) as? QqSourceParseResult.Valid

    MaiDialog(
        title = "安装 NapCat",
        subtitle = "需填写 Fork 仓库或 NapCat.Shell.zip 直链",
        icon = FlatFeatureIcons.Platforms,
        iconBackground = IconSkyBlue,
        size = MaiDialogSize.Expanded,
        onDismissRequest = onDismiss,
        secondaryContent = {
            CustomSourceRiskNotice(clientLabel = "NapCat")
        },
        footer = {
            MaiDialogButtonRow {
                MaiDialogButton(
                    text = "取消",
                    style = MaiDialogButtonStyle.Secondary,
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss
                )
                MaiDialogButton(
                    text = "了解风险并安装",
                    style = MaiDialogButtonStyle.Primary,
                    enabled = validSource != null && !state.isInstalling,
                    modifier = Modifier.weight(1f),
                    onClick = { validSource?.let { onConfirm(it.source.rawUrl) } }
                )
            }
        }
    ) {
        state.failure?.let { failure ->
            FailureNotice(failure = failure, summary = failure.message)
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MaiDialogInfoChip(text = "LinuxQQ ${NapCatScripts.QQ_VERSION}", color = TextPrimary, containerColor = CardBorder)
            MaiDialogInfoChip(text = "约占 1 GB 空间", color = TextMuted, containerColor = CardBorder)
        }

        MaiDialogSectionCard(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "原上游仓库已不可用，请填写 NapCat 的 Fork 仓库地址（从 Release 下载 NapCat.Shell.zip）或安装包直链。",
                color = TextMuted,
                fontSize = TextSizes.Caption,
                lineHeight = 16.sp
            )

            CustomSourceTextField(
                client = QqClient.NapCat,
                value = sourceUrlText,
                onValueChange = { sourceUrlText = it }
            )

            if (snowLumaInstalled) {
                Text(
                    text = "提示：装好后将改用 NapCat 登录 QQ，SnowLuma 会自动停用（不卸载，可随时切回）。",
                    color = TextMuted,
                    fontSize = TextSizes.Caption,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

/** 退出 QQ 会让 MaiBot 断开 QQ，先让用户确认。 */
@Composable
internal fun QqLogoutConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    MaiDialog(
        title = "退出 QQ 登录？",
        subtitle = "当前账号下线，后续需重新扫码登录",
        icon = FlatFeatureIcons.Platforms,
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
                    text = "退出登录",
                    style = MaiDialogButtonStyle.Danger,
                    modifier = Modifier.weight(1f),
                    onClick = onConfirm
                )
            }
        }
    ) {
        Text(
            text = "将重启 NapCat 并清除自动登录凭据：MaiBot 暂时无法收发 QQ 消息，之后可以重新扫码或更换其他 QQ 账号。",
            color = TextMuted,
            fontSize = TextSizes.Body,
            lineHeight = 19.sp
        )
    }
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

    MaiDialog(
        title = "QQ 登录二维码",
        subtitle = napCatQrLoginHint(state) ?: "等待获取登录状态",
        icon = FlatFeatureIcons.Platforms,
        iconBackground = IconSkyBlue,
        size = MaiDialogSize.Medium,
        onDismissRequest = onDismiss,
        // 交由内部 BoxWithConstraints 读取弹窗剩余有效高度，横屏时自动切入二维码 + 说明左右并排。
        scrollable = false,
        footer = {
            MaiDialogButtonRow {
                // 刷新请求进行中禁用按钮，避免连点把 QQ 登录服务打成频繁出码。
                MaiDialogButton(
                    text = if (state.qrRefreshing) "刷新中…" else "刷新二维码",
                    style = MaiDialogButtonStyle.Secondary,
                    enabled = !state.qrRefreshing,
                    modifier = Modifier.weight(1f),
                    onClick = onRefresh
                )
                MaiDialogButton(
                    text = "关闭",
                    style = MaiDialogButtonStyle.Primary,
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss
                )
            }
        }
    ) {
        // 竖排（二维码在上、说明在下）放得下就竖排；手机横屏时改成左右并排，避免截断二维码。
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
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
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        QrLoginHints(state)
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    QrCodeImage(bitmap = qrBitmap, installed = state.installed, size = QrCodeSize)
                    QrLoginHints(state)
                }
            }
        }
    }
}

/** 二维码边长：竖屏时的固定大小，横屏时按弹窗可用高度缩小，但不超过它。 */
private val QrCodeSize = 240.dp

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
                .clip(RoundedCornerShape(Corners.Panel))
                .background(QrCanvasBackground)
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
        MaiDialogSectionCard(modifier = modifier) {
            Text(
                text = if (installed) {
                    "NapCat 尚未生成二维码，请稍等片刻后点下方刷新。"
                } else {
                    "NapCat 尚未安装。"
                },
                color = TextMuted,
                fontSize = TextSizes.Body,
                lineHeight = 18.sp
            )
        }
    }
}

/** 二维码旁的说明：怎么扫、扫码进度、出错原因。 */
@Composable
private fun QrLoginHints(state: NapCatPlatformState) {
    Text(
        text = "请使用 QQ 扫码登录；也可以截图发给其他设备后再用 QQ 扫描。",
        color = TextFaint,
        fontSize = TextSizes.Caption,
        lineHeight = 15.sp
    )

    val error = state.qrRefreshError ?: state.errorMessage
    if (error != null && (state.qrRefreshError != null || state.failure == null)) {
        MaiDialogCallout(
            title = "扫码提示",
            body = error,
            accentColor = StatusDanger
        )
    }

    if (state.qrRefreshError == null) {
        state.failure?.let { failure ->
            FailureNotice(failure = failure, summary = failure.message)
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

/** NapCat 配置详情：固定顶栏显示账号与在线状态，置顶管理页/扫码入口，技术参数和自定义源默认折叠收起。 */
@Composable
internal fun NapCatConfigDialog(
    state: NapCatPlatformState,
    onRefresh: () -> Unit,
    onSaveSourceUrl: (String) -> Unit,
    onReinstallFromSourceUrl: (String) -> Unit,
    onLogoutQq: () -> Unit,
    onDismiss: () -> Unit,
    onOpenManagement: () -> Unit
) {
    val config = state.oneBotConfig
    val statusText = when {
        !state.active -> "已停用（当前使用 SnowLuma）"
        state.oneBotReachable -> "${state.accountDisplayName ?: "QQ"} 在线"
        config?.enabled == true -> "等待连接"
        else -> "未配置"
    }
    val endpoint = if (config != null && config.host.isNotBlank()) {
        "${config.host}:${config.port}"
    } else {
        "-"
    }

    PlatformConfigDialogShell(
        title = "NapCat 配置",
        statusText = statusText,
        statusOnline = state.active && state.oneBotReachable,
        onRefresh = onRefresh,
        onDismiss = onDismiss,
        secondaryContent = {
            ConfigFoldableSection(
                title = "连接与配置详情",
                summary = "9 项参数"
            ) {
                ConfigKeyValueRow(
                    label = "Token",
                    value = config?.token?.ifBlank { "未设置" } ?: "-",
                    maskableSecret = true
                )
                ConfigGroupDivider()
                ConfigKeyValueRow("服务类型", config?.serviceType ?: "-")
                ConfigGroupDivider()
                ConfigKeyValueRow("服务名称", config?.name ?: "-")
                ConfigGroupDivider()
                ConfigKeyValueRow("消息格式", config?.messagePostFormat?.ifBlank { "-" } ?: "-")
                ConfigGroupDivider()
                ConfigKeyValueRow("上报自身消息", config?.reportSelfMessage.toEnabledText())
                ConfigGroupDivider()
                ConfigKeyValueRow("强制推送事件", config?.enableForcePushEvent.toEnabledText())
                ConfigGroupDivider()
                ConfigKeyValueRow("心跳间隔", config?.heartInterval?.let { "${it}ms" } ?: "-")
                ConfigGroupDivider()
                ConfigKeyValueRow("调试模式", config?.debug.toEnabledText())
                ConfigGroupDivider()
                ConfigKeyValueRow(
                    label = "配置文件",
                    value = config?.configPath ?: "/root/napcat/config/onebot11.json",
                    valueColor = TextMuted
                )
            }

            CustomSourceEditorSection(
                client = QqClient.NapCat,
                savedUrl = state.customSourceUrl,
                installing = state.isInstalling,
                onSaveUrl = onSaveSourceUrl,
                onRequestReinstall = onReinstallFromSourceUrl
            )
        },
        footerStart = {
            // 退出 QQ 放在左下固定底栏，避免塞在右列滚动区末端导致横屏被腰斩。
            ConfigFooterDangerButton(
                text = if (state.qqLoggingOut) "正在退出…" else "退出 QQ 登录",
                enabled = state.installed && !state.qqLoggingOut,
                onClick = onLogoutQq
            )
        },
        footer = {
            MaiDialogButton(
                text = "关闭",
                style = MaiDialogButtonStyle.Secondary,
                modifier = if (LocalMaiDialogPhoneLandscape.current) Modifier else Modifier.fillMaxWidth(),
                onClick = onDismiss
            )
        }
    ) {
        state.failure?.let { failure ->
            FailureNotice(failure = failure, summary = failure.message)
        }

        ConfigCardGroup {
            ConfigQuickActionRow(
                icon = FlatFeatureIcons.BrowserConsole,
                iconColor = IconSkyBlue,
                title = "打开管理页 / 登录二维码",
                subtitle = "已登录直接进入控制台，未登录展示扫码二维码",
                enabled = state.active && state.canOpenSettings,
                onClick = onOpenManagement
            )
        }

        ConfigCardGroup {
            ConfigKeyValueRow(
                label = "连接状态",
                value = when {
                    state.oneBotReachable -> "OneBot 已监听，MaiBot 可连接"
                    config?.enabled == true -> "已启用，等待连接"
                    config != null -> "配置存在但未启用"
                    else -> "未找到 onebot11.json"
                },
                valueColor = if (state.oneBotReachable) StatusOnline else TextMuted
            )
            ConfigGroupDivider()
            ConfigKeyValueRow(
                label = "监听地址",
                value = endpoint
            )
        }
    }
}

private fun Boolean?.toEnabledText(): String = when (this) {
    true -> "开启"
    false -> "关闭"
    null -> "-"
}
