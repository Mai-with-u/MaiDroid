package org.maiwithu.maidroid.platform.napcat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NapCatAdapterConfigTest {
    // MaiBot 按新适配器默认值生成的配置（节选）：默认关着、连 3001、没有 token
    private val generated = """
        [plugin]
        enabled = false
        qq_face_parse_mode = "description"
        config_version = "2.7.1"

        [chat_abilities]
        enable_private_chat_tool = false
        enable_poke_tool = false

        [client]
        client_type = "auto"
        server = "127.0.0.1"
        port = 3001
        token = ""
        heartbeat_interval = 30.0

        [notice]
        enabled = true

        [filters]
        ban_user_id = []
        ban_qq_bot = false
        regex_filter_enabled = false
        regex_filter_mode = "blacklist"
        regex_filter_patterns = []

        [debug]
        ignore_self_message = true
    """.trimIndent() + "\n"

    // 旧适配器 MaiBot-Napcat-Adapter 的配置：用户改过几项
    private val legacy = """
        [plugin]
        enabled = true
        enable_private_chat_tool = true
        config_version = "0.1.0"

        [napcat_server]
        host = "127.0.0.1"
        port = 8095
        token = "tok"

        [chat]
        group_list_type = "whitelist"
        group_list = ["123"]
        ban_user_id = [10001, 10002] # 广告号
        ban_qq_bot = true

        [filters]
        ignore_self_message = false
        regex_filter_enabled = true
        regex_filter_patterns = [
            "^#",
        ]

        [notice]
        enabled = false
    """.trimIndent() + "\n"

    @Test
    fun `only configs written by MaiBot count as generated`() {
        assertTrue(NapCatAdapterConfig.isGenerated(generated))
        assertFalse(NapCatAdapterConfig.isGenerated("[plugin]\nenabled = true\n\n[client]\nport = 8095\n"))
        assertFalse(NapCatAdapterConfig.isGenerated(""))
    }

    @Test
    fun `migration enables the adapter and points it at NapCat`() {
        val migrated = NapCatAdapterConfig.migrate(generated, "tok", legacy = null)

        assertEquals(
            generated
                .replace("enabled = false\nqq_face", "enabled = true\nqq_face")
                .replace("port = 3001", "port = 8095")
                .replace("token = \"\"", "token = \"tok\""),
            migrated
        )
        assertTrue(NapCatAdapterConfig.isMigrated(migrated, "tok"))
        assertFalse(NapCatAdapterConfig.isMigrated(generated, "tok"))
        assertFalse(NapCatAdapterConfig.isMigrated(migrated, "other"))
    }

    @Test
    fun `legacy settings with a counterpart are carried over`() {
        val migrated = NapCatAdapterConfig.migrate(generated, "tok", legacy)

        assertTrue(migrated.contains("[chat_abilities]\nenable_private_chat_tool = true\n"))
        assertTrue(migrated.contains("ban_user_id = [10001, 10002] # 广告号\nban_qq_bot = true\n"))
        assertTrue(migrated.contains("regex_filter_enabled = true\n"))
        assertTrue(migrated.contains("[notice]\nenabled = false\n"))
        assertTrue(migrated.contains("[debug]\nignore_self_message = false\n"))
        // 旧的群聊名单新适配器没有对应项，连接项以 MaiDroid 写的为准，版本号保留 MaiBot 生成的
        assertFalse(migrated.contains("group_list"))
        assertFalse(migrated.contains("napcat_server"))
        assertTrue(migrated.contains("config_version = \"2.7.1\""))
    }

    @Test
    fun `multi-line legacy values are skipped instead of breaking the file`() {
        val migrated = NapCatAdapterConfig.migrate(generated, "tok", legacy)

        assertTrue(migrated.contains("regex_filter_patterns = []\n"))
        assertFalse(migrated.contains("\"^#\""))
    }

    @Test
    fun `migrating an already migrated config changes nothing`() {
        val migrated = NapCatAdapterConfig.migrate(generated, "tok", legacy)

        assertEquals(migrated, NapCatAdapterConfig.migrate(migrated, "tok", legacy = null))
    }

    @Test
    fun `MaiBot older than the adapter requires is asked to update first`() {
        val problem = adapterHostVersionProblem("1.3.14")

        assertTrue(problem!!.contains("1.3.18"))
        assertTrue(problem.contains("当前 1.3.14"))
        assertNull(adapterHostVersionProblem("1.3.18"))
        assertNull(adapterHostVersionProblem("1.3.19"))
        assertNull(adapterHostVersionProblem("1.4.0"))
    }

    @Test
    fun `unknown MaiBot version does not block the install`() {
        assertNull(adapterHostVersionProblem(null))
        assertNull(adapterHostVersionProblem("dev"))
    }

    @Test
    fun `install script clones the new adapter and removes the legacy one by manifest id`() {
        val fresh = NapCatAdapterScripts.install(cloneAdapter = true).body
        val existing = NapCatAdapterScripts.install(cloneAdapter = false).body

        assertTrue(fresh.contains("maidroid_github_clone 'https://github.com/Mai-with-u/MaiBot-SnowLuma-Adapter.git'"))
        assertTrue(fresh.contains("'/opt/MaiBot/plugins/.MaiBot-SnowLuma-Adapter.tmp'"))
        // 旧适配器按清单 id 删：插件市场装的副本目录名不同
        assertTrue(fresh.contains("maibot-team\\.napcat-adapter"))
        assertFalse(existing.contains("maidroid_github_clone '"))
        assertTrue(existing.contains("maibot-team\\.napcat-adapter"))
    }
}
