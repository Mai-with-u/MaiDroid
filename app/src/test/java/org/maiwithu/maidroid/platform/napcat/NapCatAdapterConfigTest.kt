package org.maiwithu.maidroid.platform.napcat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class NapCatAdapterConfigTest {
    @Test
    fun `first write creates connection config with open chat lists`() {
        val text = NapCatAdapterConfig.write(null, "tok")

        assertEquals(
            """
            [plugin]
            enabled = true
            config_version = "0.1.0"

            [napcat_server]
            host = "127.0.0.1"
            port = 8095
            token = "tok"
            heartbeat_interval = 30.0
            reconnect_delay_sec = 5.0
            action_timeout_sec = 15.0

            [chat]
            group_list_type = "blacklist"
            private_list_type = "blacklist"

            """.trimIndent(),
            text
        )
    }

    @Test
    fun `rewrite keeps user chat lists and other sections`() {
        // 用户在 WebUI 里选了白名单并填了群号：重装只改连接项，名单原样保留
        val existing = """
            [plugin]
            enabled = false
            config_version = "0.1.0"

            [napcat_server]
            host = "0.0.0.0"
            port = 3001
            token = "old"
            heartbeat_interval = 60.0

            [chat]
            group_list_type = "whitelist"
            group_list = ["123"]
            private_list_type = "whitelist"

            [notice]
            enabled = false
        """.trimIndent() + "\n"

        val text = NapCatAdapterConfig.write(existing, "new")

        assertEquals(
            existing
                .replace("enabled = false\nconfig_version", "enabled = true\nconfig_version")
                .replace("host = \"0.0.0.0\"", "host = \"127.0.0.1\"")
                .replace("port = 3001", "port = 8095")
                .replace("token = \"old\"", "token = \"new\""),
            text
        )
    }

    @Test
    fun `old MaiDroid config gets open chat lists appended`() {
        // 旧版本 MaiDroid 只写了 [plugin] 和 [napcat_server]，适配器会按空白名单丢掉所有消息
        val existing = """
            [plugin]
            enabled = true
            config_version = "0.1.0"

            [napcat_server]
            host = "127.0.0.1"
            port = 8095
            token = "tok"
        """.trimIndent() + "\n"

        assertEquals(
            existing + "\n[chat]\ngroup_list_type = \"blacklist\"\nprivate_list_type = \"blacklist\"\n",
            NapCatAdapterConfig.withOpenChatLists(existing)
        )
    }

    @Test
    fun `chat section without list types only gets the missing keys`() {
        val existing = """
            [chat]
            enable_chat_list_filter = true
            private_list_type = "whitelist"

            [notice]
            enabled = true
        """.trimIndent() + "\n"

        assertEquals(
            """
            [chat]
            enable_chat_list_filter = true
            private_list_type = "whitelist"
            group_list_type = "blacklist"

            [notice]
            enabled = true

            """.trimIndent(),
            NapCatAdapterConfig.withOpenChatLists(existing)
        )
    }

    @Test
    fun `config with both list types is returned unchanged`() {
        // 什么都不缺时原样返回同一个字符串，调用方据此跳过写文件
        val existing = "[chat]\ngroup_list_type = \"whitelist\"\nprivate_list_type = \"blacklist\"\n"

        assertSame(existing, NapCatAdapterConfig.withOpenChatLists(existing))
    }
}
