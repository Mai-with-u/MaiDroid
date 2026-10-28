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
}
