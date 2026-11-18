package org.maiwithu.maidroid.ui.remote

/**
 * 远程公告与排障热规则支持的受控动作协议：
 * 只允许跳转到应用内固定的几个维护弹窗/页面，或以 `https://` 开头的安全网页链接，拒绝任意 scheme。
 */
internal sealed interface RemoteActionTarget {
    data object AppUpdate : RemoteActionTarget
    data object MaiBotUpdate : RemoteActionTarget
    data object Cleanup : RemoteActionTarget
    data object QqGroup : RemoteActionTarget
    data object AppPermissions : RemoteActionTarget
    data class WebUrl(val httpsUrl: String) : RemoteActionTarget
}

internal fun parseRemoteActionTarget(raw: String?): RemoteActionTarget? {
    val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return when {
        trimmed.equals("open:app_update", ignoreCase = true) ||
            trimmed.equals("OpenAppUpdate", ignoreCase = true) -> RemoteActionTarget.AppUpdate
        trimmed.equals("open:maibot_update", ignoreCase = true) ||
            trimmed.equals("OpenMaiBotUpdate", ignoreCase = true) -> RemoteActionTarget.MaiBotUpdate
        trimmed.equals("open:cleanup", ignoreCase = true) ||
            trimmed.equals("FreeUpStorage", ignoreCase = true) -> RemoteActionTarget.Cleanup
        trimmed.equals("open:qq_group", ignoreCase = true) ||
            trimmed.equals("OpenQqGroup", ignoreCase = true) -> RemoteActionTarget.QqGroup
        trimmed.equals("open:permissions", ignoreCase = true) ||
            trimmed.equals("OpenPermissions", ignoreCase = true) -> RemoteActionTarget.AppPermissions
        trimmed.startsWith("url:https://", ignoreCase = true) -> {
            val url = trimmed.substring(4).trim()
            if (url.length > "https://a.b".length) RemoteActionTarget.WebUrl(url) else null
        }
        trimmed.startsWith("https://", ignoreCase = true) -> {
            if (trimmed.length > "https://a.b".length) RemoteActionTarget.WebUrl(trimmed) else null
        }
        else -> null
    }
}

internal fun defaultRemoteActionLabel(target: RemoteActionTarget): String = when (target) {
    RemoteActionTarget.AppUpdate -> "检查应用更新"
    RemoteActionTarget.MaiBotUpdate -> "打开 MaiBot 更新"
    RemoteActionTarget.Cleanup -> "清理空间"
    RemoteActionTarget.QqGroup -> "QQ 群反馈"
    RemoteActionTarget.AppPermissions -> "应用权限设置"
    is RemoteActionTarget.WebUrl -> "查看详情"
}
