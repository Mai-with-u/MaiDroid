package org.maiwithu.maidroid.pet

import java.net.URI
import java.util.Locale
import kotlin.math.roundToInt

/** 本机 MaiBot 多实例列表中的单个机器人摘要。 */
internal data class DesktopPetBot(
    val botId: String,
    val name: String,
    val status: String
)

/** 从 `/api/webui/desktop-pet/status` 读回的当前机器人桌宠配置与运行状态。 */
internal data class DesktopPetRemoteConfigState(
    val enabled: Boolean,
    val mode: String,
    val ready: Boolean = false,
    val error: String = ""
) {
    /** 只有同时启用且运行模式为 remote 时，Android 悬浮桌宠端才可连入。 */
    val needsRemoteActivation: Boolean
        get() = !enabled || mode != "remote"
}

/** 悬浮桌宠当前所处的业务阶段。 */
internal enum class DesktopPetPhase {
    Idle,
    Connecting,
    AuthRequired,
    ConfigRequired,
    Running,
    Error
}

/** 供「扩展组件」页与控制入口观察的不可变桌宠状态快照。 */
internal data class DesktopPetUiState(
    val phase: DesktopPetPhase = DesktopPetPhase.Idle,
    val desiredEnabled: Boolean = false,
    val overlayActive: Boolean = false,
    val overlayVisible: Boolean = true,
    val connected: Boolean = false,
    val statusText: String = "未开启悬浮桌宠",
    val bots: List<DesktopPetBot> = emptyList(),
    val selectedBotId: String = "",
    val remoteConfig: DesktopPetRemoteConfigState? = null,
    val assetSource: PetAssetSource = PetAssetSource.BundledFallback,
    val containerHasDesktopPet: Boolean = false,
    val updatingConfig: Boolean = false,
    val lastError: String? = null
)

/** 拖动桌宠开始时上报给服务端的有限数值载荷（对齐 desktop_pet 远端协议校验）。 */
internal data class DesktopPetDragStartPayload(
    val gestureId: String,
    val offsetX: Int,
    val offsetY: Int,
    val scale: Double
)

/** 悬浮桌宠 URL、Origin 与身份标识的纯 Kotlin 协议边界校验。 */
internal object DesktopPetProtocol {
    const val LOCAL_ORIGIN = "https://appassets.androidplatform.net"
    const val PET_PAGE_URL = "$LOCAL_ORIGIN/pet/index.html"
    const val RECORDS_PAGE_URL = "$LOCAL_ORIGIN/pet/exploration.html"
    const val AUTH_SESSION_PATH = "/api/host/desktop-pet/session"
    const val MAX_BRIDGE_MESSAGE_CHARS = 65 * 1024 * 1024
    const val MAX_DRAG_SCALE = 20.0

    private val botIdRegex = Regex("^[a-f0-9]{32}$")
    private val clientIdRegex = Regex("^[A-Za-z0-9_-]{1,64}$")

    fun isValidBotId(botId: String): Boolean = botIdRegex.matches(botId)

    fun isValidClientId(clientId: String): Boolean = clientIdRegex.matches(clientId)

    /** 提取规范化 origin（`scheme://host[:port]`），去除默认端口与用户信息；非法 URI 返回空串。 */
    fun normalizeOrigin(urlString: String?): String {
        if (urlString.isNullOrBlank()) return ""
        val uri = runCatching { URI(urlString.trim()) }.getOrNull() ?: return ""
        if (uri.userInfo != null) return ""
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return ""
        val host = uri.host?.lowercase(Locale.ROOT) ?: return ""
        val port = uri.port
        val defaultPort = (scheme == "http" && port == 80) || (scheme == "https" && port == 443)
        val portPart = if (port == -1 || defaultPort) "" else ":$port"
        return "$scheme://$host$portPart"
    }

    /** 校验目标页面是否与受信任的宿主地址同源且处于同一路径前缀下。 */
    fun isTrustedServerUrl(baseServerUrl: String, targetUrl: String?): Boolean {
        val baseUri = runCatching { URI(baseServerUrl.trim()) }.getOrNull() ?: return false
        val targetUri = runCatching { URI(targetUrl?.trim().orEmpty()) }.getOrNull() ?: return false
        val baseScheme = baseUri.scheme?.lowercase(Locale.ROOT) ?: return false
        if (baseScheme != "http" && baseScheme != "https") return false
        if (baseUri.userInfo != null) return false

        val baseOrigin = normalizeOrigin(baseServerUrl)
        val targetOrigin = normalizeOrigin(targetUrl)
        if (baseOrigin.isEmpty() || baseOrigin != targetOrigin) return false

        val prefix = baseUri.rawPath.orEmpty().trimEnd('/')
        if (prefix.isEmpty()) return true
        val targetPath = targetUri.rawPath.orEmpty()
        return targetPath == prefix || targetPath.startsWith("$prefix/")
    }

    /** 判断当前 URL 是否已停留在轻量的桌面宠同源会话页，避免持续驻留完整 React WebUI。 */
    fun isSessionDocumentUrl(baseServerUrl: String, targetUrl: String?): Boolean {
        if (!isTrustedServerUrl(baseServerUrl, targetUrl)) return false
        val baseUri = runCatching { URI(baseServerUrl.trim()) }.getOrNull() ?: return false
        val targetUri = runCatching { URI(targetUrl?.trim().orEmpty()) }.getOrNull() ?: return false
        val prefix = baseUri.rawPath.orEmpty().trimEnd('/')
        val expectedPath = "$prefix$AUTH_SESSION_PATH"
        return targetUri.rawPath.orEmpty() == expectedPath
    }

    /** 仅允许由外链打开的标准 HTTP/HTTPS 网页地址。 */
    fun isAllowedExternalHttpUrl(urlString: String?): Boolean {
        if (urlString.isNullOrBlank()) return false
        val uri = runCatching { URI(urlString.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        return (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
    }

    /**
     * 计算拖动开始事件载荷（以桌宠窗口水平中心与底边站立锚点减去按下点屏幕坐标，并夹紧屏幕密度比例），
     * 确保 `offset_x`/`offset_y`/`scale` 均为有限数值且 `0 < scale <= 20`，防止服务端校验拒收并断连。
     */
    fun computeDragStartPayload(
        gestureId: String,
        windowX: Int,
        windowWidth: Int,
        windowBottom: Int,
        pointerDownX: Float,
        pointerDownY: Float,
        density: Float
    ): DesktopPetDragStartPayload {
        val anchorX = windowX + windowWidth / 2
        val offsetX = if (pointerDownX.isFinite()) (anchorX - pointerDownX).roundToInt() else 0
        val offsetY = if (pointerDownY.isFinite()) (windowBottom - pointerDownY).roundToInt() else 0
        val rawScale = density.toDouble()
        val safeScale = if (rawScale.isFinite() && rawScale > 0.0) rawScale.coerceAtMost(MAX_DRAG_SCALE) else 1.0
        return DesktopPetDragStartPayload(
            gestureId = gestureId,
            offsetX = offsetX,
            offsetY = offsetY,
            scale = safeScale
        )
    }
}
