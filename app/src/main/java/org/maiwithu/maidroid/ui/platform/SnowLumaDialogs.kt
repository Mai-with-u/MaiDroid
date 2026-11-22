package org.maiwithu.maidroid.ui.platform

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.Role
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
import org.maiwithu.maidroid.ui.component.MaiDialog
import org.maiwithu.maidroid.ui.component.MaiDialogButton
import org.maiwithu.maidroid.ui.component.MaiDialogButtonRow
import org.maiwithu.maidroid.ui.component.MaiDialogButtonStyle
import org.maiwithu.maidroid.ui.component.MaiDialogInfoChip
import org.maiwithu.maidroid.ui.component.MaiDialogSectionCard
import org.maiwithu.maidroid.ui.diagnostics.FailureNotice
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardBorder
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.IconPeachOrange
import org.maiwithu.maidroid.ui.theme.IconRoseAlert
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

    MaiDialog(
        title = "安装 SnowLuma",
        subtitle = "需填写 Fork 仓库或 .tar.gz 直链",
        icon = FlatFeatureIcons.Platforms,
        iconBackground = IconPeachOrange,
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
                    text = "同意并安装",
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
            MaiDialogInfoChip(text = "LinuxQQ ${SnowLumaScripts.QQ_VERSION}", color = TextPrimary, containerColor = CardBorder)
            MaiDialogInfoChip(text = "下载约 200 MB", color = TextMuted, containerColor = CardBorder)
            MaiDialogInfoChip(text = "约占 1 GB", color = TextMuted, containerColor = CardBorder)
        }

        MaiDialogSectionCard(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "原上游仓库已不可用，请填写 SnowLuma 的 Fork 仓库地址（优先尝试 v${SnowLumaScripts.VERSION}，回退最新 linux-arm64 Release）或 .tar.gz 直链。",
                color = TextMuted,
                fontSize = TextSizes.Caption,
                lineHeight = 16.sp
            )

            CustomSourceTextField(
                client = QqClient.SnowLuma,
                value = sourceUrlText,
                onValueChange = { sourceUrlText = it }
            )

            if (napCatInstalled) {
                Text(
                    text = "提示：装好后改用 SnowLuma 登录 QQ，NapCat 会停用（不卸载，可随时切回）。",
                    color = TextMuted,
                    fontSize = TextSizes.Caption,
                    lineHeight = 16.sp
                )
            }

            // 闭源声明与用户/隐私协议链接集中放进源填写卡片底部，点击链接不误触主按钮。
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "SnowLuma 含闭源原生组件，仅限非商业用途；点“同意并安装”即表示你已阅读并同意对应仓库内的协议条款：",
                    color = TextMuted,
                    fontSize = TextSizes.Caption,
                    lineHeight = 16.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SourceAgreementLink(text = "查看用户协议", onClick = { openSourceDoc("EULA.md") })
                    SourceAgreementLink(text = "查看隐私政策", onClick = { openSourceDoc("PRIVACY.md") })
                }
            }
        }

        CustomSourceRiskNotice(clientLabel = "SnowLuma")
    }
}

@Composable
private fun SourceAgreementLink(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        color = BrandOrange,
        fontSize = TextSizes.Caption,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(Corners.Tag))
            .background(BrandOrange.copy(alpha = 0.14f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
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
    MaiDialog(
        title = "改用 ${target.label} 登录 QQ？",
        subtitle = "同一时间仅运行一种 QQ 协议端",
        icon = FlatFeatureIcons.Platforms,
        iconBackground = IconSkyBlue,
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
                    text = if (targetInstalled) "切换" else "安装并切换",
                    style = MaiDialogButtonStyle.Primary,
                    modifier = Modifier.weight(1f),
                    onClick = onConfirm
                )
            }
        }
    ) {
        Text(
            text = "${other.label} 会先停下（不卸载，随时可切回），QQ 下线后由 ${target.label} 重新启动，期间麦麦暂无法收发 QQ 消息。$login$install",
            color = TextMuted,
            fontSize = TextSizes.Body,
            lineHeight = 19.sp
        )
    }
}

/**
 * QQ 窗口：显示容器里 QQ 的窗口画面（每秒刷新），点画面上的哪里就替用户在 QQ 窗口的那里点一下。
 * SnowLuma 不出二维码，扫码登录、二维码过期后刷新、快速登录都在这里做。
 */
@Composable
internal fun QqWindowDialog(
    state: SnowLumaPlatformState,
    frame: QqScreenFrame?,
    onTap: (QqScreenFrame, Float, Float) -> Unit,
    onDismiss: () -> Unit
) {
    MaiDialog(
        title = "QQ 窗口",
        subtitle = snowLumaLoginHint(state),
        icon = FlatFeatureIcons.DesktopWindow,
        iconBackground = IconPeachOrange,
        onDismissRequest = onDismiss,
        footer = {
            MaiDialogButton(
                text = "关闭",
                style = MaiDialogButtonStyle.Primary,
                modifier = Modifier.fillMaxWidth(),
                onClick = onDismiss
            )
        }
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            QqWindowImage(frame = frame, onTap = onTap)
        }
        Text(
            text = "点击画面位置即可操作 QQ 窗口；也可截图发给另一台设备，用手机 QQ 扫码登录。",
            color = TextFaint,
            fontSize = TextSizes.Caption,
            lineHeight = 15.sp
        )
        if (state.hookPhase == SnowLumaHookPhase.Failed && state.failure == null) {
            Text(
                text = snowLumaLoginHint(state),
                color = StatusDanger,
                fontSize = TextSizes.Caption,
                lineHeight = 15.sp
            )
        }
        state.failure?.let { failure ->
            FailureNotice(failure = failure, summary = failure.message)
        }
    }
}

/** QQ 窗口的画面，按窗口的宽高比铺满弹窗宽度；横屏时限高，免得把说明挤出去。还没有画面时换成一句原因。 */
@Composable
private fun QqWindowImage(
    frame: QqScreenFrame?,
    onTap: (QqScreenFrame, Float, Float) -> Unit
) {
    if (frame == null) {
        MaiDialogSectionCard {
            Text(
                text = "QQ 窗口还没画出来，请稍等；一直这样的话看终端里 [SnowLuma] 开头的输出。",
                color = TextMuted,
                fontSize = TextSizes.Body,
                lineHeight = 18.sp
            )
        }
        return
    }
    // 画面变了（signature 变了）才重新生成位图
    val bitmap = remember(frame.signature) {
        Bitmap.createBitmap(frame.pixels, frame.width, frame.height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Box(
        modifier = Modifier
            .heightIn(max = 400.dp)
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
            FailureNotice(failure = failure, summary = failure.message)
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
    MaiDialog(
        title = "重启 QQ？",
        subtitle = "如需切换账号，重启后在 QQ 窗口内操作",
        icon = FlatFeatureIcons.DesktopWindow,
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
                    text = "重启 QQ",
                    style = MaiDialogButtonStyle.Danger,
                    modifier = Modifier.weight(1f),
                    onClick = onConfirm
                )
            }
        }
    ) {
        Text(
            text = "QQ 会先下线再重新启动，期间 MaiBot 暂时无法收发 QQ 消息；启动后请在 QQ 窗口里重新登录或换号。",
            color = TextMuted,
            fontSize = TextSizes.Body,
            lineHeight = 19.sp
        )
    }
}
