package org.maiwithu.maidroid.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.maiwithu.maidroid.VersionNumber

/**
 * 远程清单的数据结构与本地匹配规则：包含定向公告（RemoteAnnouncement）和动态排障热规则（RemoteFailureHint）。
 * 清单本身必须装在带 ECDSA 签名的 SignedManifestEnvelope 信封里，验签通过后才进入这里的解析与筛选。
 */
@Serializable
internal data class SignedManifestEnvelope(
    val keyId: String,
    val payload: String,
    val signature: String
)

@Serializable
internal data class RemoteManifest(
    val version: Int = 0,
    val generatedAt: Long = 0L,
    val announcements: List<RemoteAnnouncement> = emptyList(),
    val failureHints: List<RemoteFailureHint> = emptyList()
)

@Serializable
internal enum class AnnouncementLevel {
    @SerialName("info")
    Info,

    @SerialName("warning")
    Warning,

    @SerialName("blocker")
    Blocker
}

@Serializable
internal data class AnnouncementFilter(
    val minAppVersion: String? = null,
    val maxAppVersion: String? = null,
    val manufacturers: List<String> = emptyList(),
    val minSdkInt: Int? = null,
    val activeQqClients: List<String> = emptyList(),
    val onlyDuringOobe: Boolean = false
)

@Serializable
internal data class RemoteAnnouncement(
    val id: String,
    val level: AnnouncementLevel = AnnouncementLevel.Info,
    val title: String,
    val body: String,
    val actionLabel: String? = null,
    val actionTarget: String? = null,
    val notifySystemBar: Boolean = false,
    val dismissible: Boolean = true,
    val expiresAt: Long? = null,
    val filter: AnnouncementFilter = AnnouncementFilter()
)

@Serializable
internal data class RemoteFailureHint(
    val id: String,
    val steps: List<String> = emptyList(),
    val containsAny: List<String> = emptyList(),
    val title: String,
    val advice: String,
    val action: String? = null
)

/** 客户端当前环境快照，抽成纯数据对象以便不依赖 Android 框架直接做单元测试。 */
internal data class ClientEnvironment(
    val appVersion: String,
    val manufacturer: String,
    val sdkInt: Int,
    val activeQqClient: String?,
    val setupComplete: Boolean,
    val nowEpochSeconds: Long
)

private val remoteJson = Json {
    ignoreUnknownKeys = true
    isLenient = false
}

internal fun parseSignedEnvelope(text: String): SignedManifestEnvelope? =
    runCatching { remoteJson.decodeFromString<SignedManifestEnvelope>(text) }
        .getOrNull()
        ?.takeIf { it.keyId.isNotBlank() && it.payload.isNotBlank() && it.signature.isNotBlank() }

internal fun parseRemoteManifest(payloadJson: String): RemoteManifest? =
    runCatching { remoteJson.decodeFromString<RemoteManifest>(payloadJson) }.getOrNull()

/** 判断一条远程公告是否命中当前设备环境且尚未过期。 */
internal fun RemoteAnnouncement.matches(env: ClientEnvironment): Boolean {
    if (id.isBlank() || title.isBlank() || body.isBlank()) return false
    if (expiresAt != null && env.nowEpochSeconds >= expiresAt) return false
    if (filter.onlyDuringOobe && env.setupComplete) return false
    if (filter.minSdkInt != null && env.sdkInt < filter.minSdkInt) return false

    if (filter.manufacturers.isNotEmpty()) {
        val brand = env.manufacturer.trim()
        if (filter.manufacturers.none { it.equals(brand, ignoreCase = true) }) return false
    }

    if (filter.activeQqClients.isNotEmpty()) {
        val active = env.activeQqClient?.trim().orEmpty()
        if (active.isEmpty() || filter.activeQqClients.none { it.equals(active, ignoreCase = true) }) {
            return false
        }
    }

    val currentVer = VersionNumber.parse(env.appVersion)
    val minVer = filter.minAppVersion?.takeIf { it.isNotBlank() }?.let(VersionNumber::parse)
    val maxVer = filter.maxAppVersion?.takeIf { it.isNotBlank() }?.let(VersionNumber::parse)
    if (minVer != null && (currentVer == null || currentVer < minVer)) return false
    if (maxVer != null && (currentVer == null || currentVer > maxVer)) return false

    return true
}

/**
 * 用远端排障热规则匹配命令输出尾部：仅使用大小写不敏感的字面子串列表（containsAny），
 * 不解析远端正则，从根源避免配置失误引发客户端正则回溯卡顿。
 */
internal fun matchRemoteFailureHint(
    hints: List<RemoteFailureHint>,
    stepCode: String,
    output: String,
    tailLines: Int = 400
): RemoteFailureHint? {
    if (hints.isEmpty() || output.isBlank()) return null
    val tail = output.lineSequence().toList().takeLast(tailLines).joinToString("\n")
    return hints.firstOrNull { hint ->
        val stepMatches = hint.steps.isEmpty() || hint.steps.any { it.equals(stepCode, ignoreCase = true) }
        val textMatches = hint.containsAny.any { needle -> needle.isNotBlank() && tail.contains(needle, ignoreCase = true) }
        stepMatches && textMatches && hint.title.isNotBlank() && hint.advice.isNotBlank()
    }
}
