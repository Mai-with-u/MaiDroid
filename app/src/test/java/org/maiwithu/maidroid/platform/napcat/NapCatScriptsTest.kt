package org.maiwithu.maidroid.platform.napcat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NapCatScriptsTest {
    @Test
    fun `installer is downloaded through github mirrors and checked`() {
        val install = NapCatScripts.install("token").body

        assertTrue(install.contains("maidroid_github_mirror_specs()"))
        assertTrue(install.contains("napcat-linux-installer/refs/heads/main/install.sh"))
        // 镜像返回网页或报错文字时不能当成安装器去执行
        assertTrue(install.contains("grep -qi 'napcat' napcat.sh"))
        assertFalse(install.contains("curl -fL --connect-timeout 20 -o napcat.sh https://"))
    }
}
