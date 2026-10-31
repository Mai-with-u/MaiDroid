package org.maiwithu.maidroid.maibot

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

// MaiBot 扫插件时跳过的目录：数据目录、字节码缓存，以及点开头的目录（含安装用的临时目录）。
private val ReservedPluginDirNames = setOf("data", "__pycache__")

/**
 * 在 MaiBot 的 plugins 目录里按清单 ID 找插件目录（大小写不敏感，与 MaiBot 的查找一致）：
 * 优先看 preferredDirName 这个目录，再扫其他目录；清单和 plugin.py 都在才算数。
 * 同一插件可能由 MaiDroid 安装，也可能由用户从 MaiBot WebUI 的插件市场安装，目录名不同但清单 ID 相同。
 */
internal fun findPluginDirById(pluginsDir: File, pluginId: String, preferredDirName: String): File? {
    val candidates = pluginsDir.listFiles()
        ?.filter { dir ->
            dir.isDirectory && !dir.name.startsWith(".") &&
                dir.name.lowercase() !in ReservedPluginDirNames
        }
        ?.sortedWith(compareByDescending<File> { it.name == preferredDirName }.thenBy { it.name })
        ?: return null
    return candidates.firstOrNull { dir ->
        val manifest = File(dir, "_manifest.json")
        File(dir, "plugin.py").isFile && manifest.isFile &&
            runCatching {
                Json.parseToJsonElement(manifest.readText()).jsonObject["id"]?.jsonPrimitive?.contentOrNull
            }.getOrNull().equals(pluginId, ignoreCase = true)
    }
}
