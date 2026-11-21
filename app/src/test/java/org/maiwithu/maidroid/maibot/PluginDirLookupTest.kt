package org.maiwithu.maidroid.maibot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PluginDirLookupTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val pluginId = "Mai-with-u.qq-adapter"

    private fun plugin(root: File, name: String, id: String, withEntry: Boolean = true): File =
        File(root, name).apply {
            mkdirs()
            File(this, "_manifest.json").writeText("""{"manifest_version": 2, "id": "$id"}""")
            if (withEntry) File(this, "plugin.py").writeText("")
        }

    @Test
    fun `finds a copy installed from the MaiBot plugin market by manifest id`() {
        val root = folder.newFolder("plugins")
        plugin(root, "MaiBot-Napcat-Adapter", "maibot-team.napcat-adapter")
        val market = plugin(root, "Mai-with-u_qq-adapter", pluginId)
        assertEquals(market, findPluginDirById(root, pluginId, "MaiBot-QQ-Adapter"))
    }

    @Test
    fun `prefers MaiDroid's own directory and ignores staging, reserved and incomplete ones`() {
        val root = folder.newFolder("plugins")
        plugin(root, ".MaiBot-QQ-Adapter.tmp", pluginId)
        plugin(root, "data", pluginId)
        plugin(root, "Half-Cloned", pluginId, withEntry = false)
        assertNull(findPluginDirById(root, pluginId, "MaiBot-QQ-Adapter"))

        plugin(root, "Mai-with-u_qq-adapter", pluginId)
        val own = plugin(root, "MaiBot-QQ-Adapter", "MAI-WITH-U.QQ-ADAPTER")
        assertEquals(own, findPluginDirById(root, pluginId, "MaiBot-QQ-Adapter"))
    }

    @Test
    fun `missing plugins directory or broken manifest means not installed`() {
        assertNull(findPluginDirById(File(folder.root, "absent"), pluginId, "MaiBot-QQ-Adapter"))
        val root = folder.newFolder("plugins")
        File(root, "Broken").apply {
            mkdirs()
            File(this, "_manifest.json").writeText("{not json")
            File(this, "plugin.py").writeText("")
        }
        assertNull(findPluginDirById(root, pluginId, "MaiBot-QQ-Adapter"))
    }

    @Test
    fun `findPluginDirsById returns every matching dir with preferred one first`() {
        val root = folder.newFolder("plugins")
        val market = plugin(root, "Mai-with-u_qq-adapter", pluginId)
        val own = plugin(root, "MaiBot-QQ-Adapter", pluginId)
        plugin(root, "Other", "other.plugin")

        assertEquals(listOf(own, market), findPluginDirsById(root, pluginId, "MaiBot-QQ-Adapter"))
    }

    @Test
    fun `parsePluginHostVersionRange and pluginHostVersionProblem validate dynamic manifest bounds`() {
        val manifest = """
            {
              "id": "maibot-team.snowluma-adapter",
              "host_application": {
                "min_version": "1.4.4",
                "max_version": "1.4.99"
              }
            }
        """.trimIndent()
        val range = parsePluginHostVersionRange(manifest)
        assertEquals(PluginHostVersionRange("1.4.4", "1.4.99"), range)

        val tooOld = pluginHostVersionProblem("QQ 适配器", "1.4.3", range)
        assertEquals(
            "QQ 适配器 需要 MaiBot 1.4.4 以上（当前 1.4.3），请先在设置页“MaiBot 更新”里更新 MaiBot",
            tooOld
        )
        assertNull(pluginHostVersionProblem("QQ 适配器", "1.4.4", range))
        assertNull(pluginHostVersionProblem("QQ 适配器", "1.4.99", range))
        val tooNew = pluginHostVersionProblem("QQ 适配器", "1.5.0", range)
        assertEquals(
            "QQ 适配器 最高支持 MaiBot 1.4.99（当前 1.5.0），请更新适配器或等待适配新版 MaiBot",
            tooNew
        )

        assertNull(pluginHostVersionProblem("QQ 适配器", null, range))
        assertNull(pluginHostVersionProblem("QQ 适配器", "dev", range))
        assertNull(parsePluginHostVersionRange("{broken"))
        assertNull(parsePluginHostVersionRange("""{"host_application": {"min_version": "  ", "max_version": ""}}"""))
    }
}
