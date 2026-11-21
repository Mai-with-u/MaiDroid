package org.maiwithu.maidroid.maibot

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.maiwithu.maidroid.VersionNumber
import java.io.File

// MaiBot 扫插件时跳过的目录：数据目录、字节码缓存，以及点开头的目录（含安装用的临时目录）。
private val ReservedPluginDirNames = setOf("data", "__pycache__")

/** 插件清单里声明的宿主机（MaiBot）版本兼容区间。 */
internal data class PluginHostVersionRange(
    val minVersion: String? = null,
    val maxVersion: String? = null
)

/**
 * 在 MaiBot 的 plugins 目录里按清单 ID 找出所有匹配的插件目录（大小写不敏感，与 MaiBot 的查找一致）：
 * preferredDirName 非空时优先排在前面，再按目录名排序；清单和 plugin.py 都在才算数。
 * 同一插件可能由 MaiDroid 安装，也可能由用户从 MaiBot WebUI 的插件市场安装，目录名不同但清单 ID 相同。
 */
internal fun findPluginDirsById(
    pluginsDir: File,
    pluginId: String,
    preferredDirName: String = ""
): List<File> {
    val candidates = pluginsDir.listFiles()
        ?.filter { dir ->
            dir.isDirectory && !dir.name.startsWith(".") &&
                dir.name.lowercase() !in ReservedPluginDirNames
        }
        ?.sortedWith(
            compareByDescending<File> { preferredDirName.isNotEmpty() && it.name == preferredDirName }
                .thenBy { it.name }
        )
        ?: return emptyList()
    return candidates.filter { dir ->
        val manifest = File(dir, "_manifest.json")
        File(dir, "plugin.py").isFile && manifest.isFile &&
            runCatching {
                val root = Json.parseToJsonElement(manifest.readText()) as? JsonObject
                (root?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            }.getOrNull().equals(pluginId, ignoreCase = true)
    }
}

/**
 * 在 MaiBot 的 plugins 目录里按清单 ID 找首选插件目录；详见 [findPluginDirsById]。
 */
internal fun findPluginDirById(pluginsDir: File, pluginId: String, preferredDirName: String = ""): File? =
    findPluginDirsById(pluginsDir, pluginId, preferredDirName).firstOrNull()

/**
 * 从插件清单 JSON 解析宿主机版本范围（`host_application.min_version` / `max_version`）。
 * 字段缺失、为空白或 JSON 格式无效时返回 null。
 */
internal fun parsePluginHostVersionRange(manifestJson: String): PluginHostVersionRange? {
    val root = runCatching { Json.parseToJsonElement(manifestJson) as? JsonObject }.getOrNull() ?: return null
    val hostApp = root["host_application"] as? JsonObject ?: return null
    val minVersion = readNonBlankString(hostApp, "min_version")
    val maxVersion = readNonBlankString(hostApp, "max_version")
    if (minVersion == null && maxVersion == null) return null
    return PluginHostVersionRange(minVersion = minVersion, maxVersion = maxVersion)
}

/** 读取指定插件目录下 `_manifest.json` 的宿主机兼容区间，读不到或无效时返回 null。 */
internal fun readPluginHostVersionRange(pluginDir: File): PluginHostVersionRange? {
    val manifest = File(pluginDir, "_manifest.json")
    if (!manifest.isFile) return null
    val content = runCatching { manifest.readText() }.getOrNull() ?: return null
    return parsePluginHostVersionRange(content)
}

/**
 * 核对当前 MaiBot 版本是否满足插件清单声明的兼容区间：
 * 先判是否低于下限，再判是否高于上限；版本或要求任一方缺失、无法解析时返回 null（交由 MaiBot 自行决定）。
 */
internal fun pluginHostVersionProblem(
    pluginName: String,
    maiBotVersion: String?,
    required: PluginHostVersionRange?
): String? {
    if (required == null) return null
    val current = maiBotVersion?.let(VersionNumber::parse) ?: return null
    val minText = required.minVersion?.trim()?.takeIf { it.isNotEmpty() }
    val min = minText?.let(VersionNumber::parse)
    if (min != null && current < min) {
        return "$pluginName 需要 MaiBot $minText 以上（当前 $maiBotVersion），请先在设置页“MaiBot 更新”里更新 MaiBot"
    }
    val maxText = required.maxVersion?.trim()?.takeIf { it.isNotEmpty() }
    val max = maxText?.let(VersionNumber::parse)
    if (max != null && current > max) {
        return "$pluginName 最高支持 MaiBot $maxText（当前 $maiBotVersion），请更新适配器或等待适配新版 MaiBot"
    }
    return null
}

private fun readNonBlankString(obj: JsonObject, key: String): String? =
    (obj[key] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
