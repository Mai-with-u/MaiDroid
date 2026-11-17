package org.maiwithu.maidroid.ui.platform

import android.graphics.Bitmap
import android.widget.Toast
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maiwithu.maidroid.platform.qq.QqClient
import org.maiwithu.maidroid.platform.qq.QqOneBot
import org.maiwithu.maidroid.platform.qq.QqSourceParseResult
import org.maiwithu.maidroid.platform.qq.parseQqCustomSource
import org.maiwithu.maidroid.platform.qq.snowLumaDocUrl
import org.maiwithu.maidroid.platform.snowluma.QqScreenFrame
import org.maiwithu.maidroid.platform.snowluma.SnowLumaHookPhase
import org.maiwithu.maidroid.platform.snowluma.SnowLumaPlatformState
import org.maiwithu.maidroid.platform.snowluma.SnowLumaScripts
import org.maiwithu.maidroid.platform.snowluma.snowLumaLoginHint
import org.maiwithu.maidroid.ui.component.FlatFeatureIcons
import org.maiwithu.maidroid.ui.diagnostics.FailureNotice
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardSurface
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.IconPeachOrange
import org.maiwithu.maidroid.ui.theme.IconSkyBlue
import org.maiwithu.maidroid.ui.theme.QrCanvasBackground
import org.maiwithu.maidroid.ui.theme.StatusDanger
import org.maiwithu.maidroid.ui.theme.StatusOnline
import org.maiwithu.maidroid.ui.theme.TextFaint
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes

// QQ（SnowLuma）这一路的弹窗：安装前的协议与自定义源说明、QQ 窗口、配置详情，以及切换协议端、重启 QQ 的二次确认。

/**
 * 装 SnowLuma 之前的说明：要求填写自定义 fork 仓库或发行包直链，警示 SHA-256 不一致的风险，
 * 说明闭源组件只能非商业使用以及用户协议和隐私政策（按钮链接动态指向当前填入的仓库）。
 * 已经装着 NapCat 时说清楚：装好后 NapCat 停用、不卸载。
 */
@Composable
internal fun SnowLumaAgreementDialog(
    state: SnowLumaPlatformState,
    napCatInstalled: Boolean,
    onConfirm: (sourceUrl: String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var sourceUrlText by rememberSaveable { mutableStateOf(state.customSourceUrl) }
    val validSource = parseQqCustomSource(QqClient.SnowLuma, sourceUrlText) as? QqSourceParseResult.Valid

    fun openSourceDoc(fileName: String) {
        val docUrl = snowLumaDocUrl(sourceUrlText, fileName)
        if (docUrl != null) {
            uriHandler.openUri(docUrl)
        } else {
            Toast.makeText(
                context,
                "原上游仓库已无法访问，请先在上方填写 fork 仓库地址后再查看对应协议文件",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { PlatformDialogTitle("安装 SnowLuma") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
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

                PlatformDialogBody(
                    "SnowLuma 是另一种登录个人 QQ 的方式：原上游仓库已不可用，" +
                        "请填写 SnowLuma 的 fork 仓库地址（优先尝试 v${SnowLumaScripts.VERSION}，找不到则回退最新 Release 的 linux-arm64 发行包）" +
                        "或 .tar.gz 直链。MaiDroid 会在容器里安装 LinuxQQ ${SnowLumaScripts.QQ_VERSION} 和 SnowLuma" +
                        "（要下载约 200 MB，装好后约占 1 GB）。"
                )
                if (napCatInstalled) {
                    PlatformDialogBody("装好后改用 SnowLuma 登录 QQ，NapCat 会停用（不卸载，随时可以切回）。")
                }

                CustomSourceTextField(
                    client = QqClient.SnowLuma,
                    value = sourceUrlText,
                    onValueChange = { sourceUrlText = it }
                )

                PlatformDialogBody(
                    "SnowLuma 含有闭源的原生组件，只能用于非商业用途。" +
                        "点“了解风险，同意并安装”即表示你已阅读并同意对应仓库内的用户协议和隐私政策。"
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { openSourceDoc("EULA.md") }) {
                        Text(text = "用户协议", color = BrandOrange, fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = { openSourceDoc("PRIVACY.md") }) {
                        Text(text = "隐私政策", color = BrandOrange, fontWeight = FontWeight.Bold)
                    }
                }

                CustomSourceRiskNotice(clientLabel = "SnowLuma")
            }
        },
        dismissButton = { PlatformSecondaryButton(text = "取消", onClick = onDismiss) },
        confirmButton = {
            PlatformPrimaryButton(
                text = "了解风险，同意并安装",
                enabled = validSource != null && !state.isInstalling,
                onClick = { validSource?.let { onConfirm(it.source.rawUrl) } }
            )
        },
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
        title = { PlatformDialogTitle("改用 ${target.label} 登录 QQ？") },
        text = {
            PlatformDialogBody(
                "${other.label} 会停下（不卸载，随时可以切回），QQ 先下线，再由 ${target.label} 重新启动，" +
                    "期间麦麦收发不了 QQ 消息。$login$install"
            )
        },
        dismissButton = { PlatformSecondaryButton(text = "取消", onClick = onDismiss) },
        confirmButton = { PlatformPrimaryButton(text = if (targetInstalled) "切换" else "安装并切换", onClick = onConfirm) },
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
        title = { PlatformDialogTitle("QQ 窗口") },
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
        confirmButton = { PlatformPrimaryButton(text = "关闭", onClick = onDismiss) },
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
            .background(QrCanvasBackground)
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

/** SnowLuma 配置详情：固定顶栏展示账号与状态，置顶 QQ 窗口和管理页入口，技术细节与自定义源默认折叠。 */
@Composable
internal fun SnowLumaConfigDialog(
    state: SnowLumaPlatformState,
    onRefresh: () -> Unit,
    onShowQqWindow: () -> Unit,
    onOpenWebUi: () -> Unit,
    onSaveSourceUrl: (String) -> Unit,
    onReinstallFromSourceUrl: (String) -> Unit,
    onRestartQq: () -> Unit,
    onDismiss: () -> Unit
) {
    val statusText = when {
        !state.active -> "已停用（当前使用 NapCat）"
        state.oneBotReachable -> "${state.accountDisplayName ?: "QQ"} 在线"
        state.hookPhase == SnowLumaHookPhase.WaitingLogin && state.accountDetected -> "QQ 已登录，等待接管"
        state.hookPhase == SnowLumaHookPhase.WaitingLogin -> "等待在 QQ 窗口登录"
        state.hookPhase == SnowLumaHookPhase.Failed -> "SnowLuma 接入失败"
        state.running -> "SnowLuma 启动中"
        else -> "未运行"
    }
    val connectionSummary = when {
        !state.active -> "已停用（使用 NapCat）"
        state.oneBotReachable -> "OneBot 已监听，可连接"
        state.hookPhase == SnowLumaHookPhase.WaitingLogin -> "请在 QQ 窗口扫码或登录"
        state.hookPhase == SnowLumaHookPhase.Failed -> "接入失败，请查看终端"
        state.running -> "启动中…"
        else -> "未运行"
    }

    PlatformConfigDialogShell(
        title = "SnowLuma 配置",
        statusText = statusText,
        statusOnline = state.active && state.oneBotReachable,
        onRefresh = onRefresh,
        onDismiss = onDismiss
    ) {
        state.failure?.let { failure ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "上次操作失败：${failure.message}",
                    color = StatusDanger,
                    fontSize = TextSizes.Caption,
                    lineHeight = 15.sp
                )
                FailureNotice(failure = failure)
            }
        }

        ConfigCardGroup {
            ConfigQuickActionRow(
                icon = FlatFeatureIcons.DesktopWindow,
                iconColor = IconPeachOrange,
                title = "查看 QQ 窗口",
                subtitle = "点击查看虚拟桌面画面，完成扫码或快捷登录",
                enabled = state.active,
                onClick = onShowQqWindow
            )
            ConfigGroupDivider(startPadding = 60.dp)
            ConfigQuickActionRow(
                icon = FlatFeatureIcons.BrowserConsole,
                iconColor = IconSkyBlue,
                title = "打开 SnowLuma 管理页",
                subtitle = "在内置浏览器中查看协议端面板与日志",
                enabled = state.active && state.running,
                onClick = onOpenWebUi
            )
        }

        ConfigCardGroup {
            ConfigKeyValueRow(
                label = "连接状态",
                value = connectionSummary,
                valueColor = if (state.active && state.oneBotReachable) StatusOnline else TextMuted
            )
            ConfigGroupDivider()
            ConfigKeyValueRow("监听地址", "${QqOneBot.HOST}:${QqOneBot.PORT}")
            ConfigGroupDivider()
            ConfigKeyValueRow(
                label = "组件版本",
                value = "SnowLuma ${state.installedVersion ?: SnowLumaScripts.VERSION} · QQ ${SnowLumaScripts.QQ_VERSION}",
                valueColor = TextMuted
            )
        }

        ConfigFoldableSection(
            title = "连接与配置详情",
            summary = "3 项参数"
        ) {
            ConfigKeyValueRow("服务类型", "WebSocket Server")
            ConfigGroupDivider()
            ConfigKeyValueRow("Token", "和 NapCat 共用一个", valueColor = TextMuted)
            ConfigGroupDivider()
            ConfigKeyValueRow(
                label = "配置文件",
                value = "${SnowLumaScripts.DATA_DIR}/config/onebot.json",
                valueColor = TextMuted
            )
        }

        CustomSourceEditorSection(
            client = QqClient.SnowLuma,
            savedUrl = state.customSourceUrl,
            installing = state.isInstalling,
            onSaveUrl = onSaveSourceUrl,
            onRequestReinstall = onReinstallFromSourceUrl
        )

        // 重启 QQ：先弹二次确认；进行中禁用，避免重复重启
        ConfigSoftDangerButton(
            text = if (state.qqRestarting) "正在重启…" else "重启 QQ",
            enabled = state.active && !state.qqRestarting,
            onClick = onRestartQq
        )
    }
}

/** 重启 QQ 会让 MaiBot 暂时断开 QQ，先让用户确认。SnowLuma 没有退出登录的接口，换号也是重启后在 QQ 窗口里换。 */
@Composable
internal fun SnowLumaRestartQqDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { PlatformDialogTitle("重启 QQ？") },
        text = {
            PlatformDialogBody(
                "QQ 会先下线再重新启动，MaiBot 暂时收发不了 QQ 消息。之后在 QQ 窗口里重新登录，也可以换别的账号。"
            )
        },
        dismissButton = { PlatformSecondaryButton(text = "取消", onClick = onDismiss) },
        confirmButton = { PlatformDangerButton(text = "重启 QQ", onClick = onConfirm) },
        containerColor = CardSurface,
        titleContentColor = TextPrimary,
        textContentColor = TextMuted
    )
}
