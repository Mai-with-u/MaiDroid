package org.maiwithu.maidroid.platform.napcat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maiwithu.maidroid.platform.qq.QqClient
import org.maiwithu.maidroid.platform.qq.QqCustomSource
import org.maiwithu.maidroid.platform.qq.QqSourceParseResult
import org.maiwithu.maidroid.platform.qq.parseQqCustomSource
import org.maiwithu.maidroid.runtime.ContainerBindMount

class NapCatScriptsTest {
    private val repoSource = (parseQqCustomSource(QqClient.NapCat, "https://github.com/owner/NapCatQQ")
        as QqSourceParseResult.Valid).source

    @Test
    fun `installs official linuxqq and fetches napcat zip from custom repository`() {
        val install = NapCatScripts.install("token", repoSource).body

        assertTrue(install.contains("maidroid_download_urls"))
        // 内置 LinuxQQ 检查与下载，不再依赖原第三方安装脚本和 g++ 编译
        assertTrue(install.contains("https://qqdl.gtimg.cn/qqfile/QQNT/9.9.36/beta/9ee04bef/linuxqq_3.2.34-53644_arm64.deb"))
        assertTrue(install.contains("buildVersion | tonumber >= 53644"))
        assertFalse(install.contains("g++"))
        // 自定义仓库先尝试 latest，失败再走 GitHub Releases API；每次下载用 unzip -t 拦截伪造页面
        assertTrue(install.contains("https://github.com/owner/NapCatQQ/releases/latest/download/NapCat.Shell.zip"))
        assertTrue(install.contains("https://api.github.com/repos/owner/NapCatQQ/releases/latest"))
        assertTrue(install.contains("unzip -t /root/.cache/maidroid-napcat/NapCat.Shell.zip.part"))
        assertTrue(install.contains("custom package sha256"))
        assertFalse(install.lowercase().contains("napneko"))
        // OneBot token 和 SnowLuma 共用一份：先读出或存下，写 onebot11.json 时才有值
        assertTrue(install.indexOf("napcat_token=\"${'$'}(cat /root/.maidroid/napcat_token)\"") in 0 until install.indexOf("onebot11.json"))
        // 安装脚本里的 here-doc 结束标记必须顶格，否则 shell 找不到结尾
        assertEquals(3, Regex("(?m)^EOF$").findAll(install).count())
    }

    @Test
    fun `supports direct zip archive and pinned tag custom sources`() {
        val directInstall = NapCatScripts.install(
            "token",
            QqCustomSource.DirectArchive("https://example.com/custom/NapCat.Shell.zip")
        ).body
        assertTrue(directInstall.contains("maidroid_napcat_fetch_zip 'https://example.com/custom/NapCat.Shell.zip'"))

        val taggedSource = (parseQqCustomSource(QqClient.NapCat, "https://github.com/owner/NapCatQQ/releases/tag/v4.18.42")
            as QqSourceParseResult.Valid).source
        val taggedInstall = NapCatScripts.install("token", taggedSource).body
        assertTrue(taggedInstall.contains("https://github.com/owner/NapCatQQ/releases/download/v4.18.42/NapCat.Shell.zip"))
        assertTrue(taggedInstall.contains("https://api.github.com/repos/owner/NapCatQQ/releases/tags/v4.18.42"))
    }

    @Test
    fun `runtime generates package json override and mounts it via proot bind`() {
        val launcher = NapCatScripts.prepareRuntime().body
        val launch = NapCatScripts.launch()

        assertTrue(launcher.contains("cat > /root/loadNapCat.cjs <<'EOF'"))
        assertTrue(launcher.contains("import('file://' + path.join(currentPath, './napcat/napcat.mjs'))"))
        assertTrue(launcher.contains("/root/napcat/qq-package.json"))
        assertTrue(launcher.contains("../../../../root/loadNapCat.cjs"))
        assertFalse(launcher.contains("LD_PRELOAD=/root/libnapcat_launcher.so"))
        assertEquals(
            listOf(
                ContainerBindMount(
                    rootfsRelativeSource = NapCatScripts.QQ_PACKAGE_JSON_OVERRIDE_REL,
                    containerTarget = NapCatScripts.QQ_PACKAGE_JSON_TARGET
                )
            ),
            launch.extraBinds
        )
        assertEquals(2, Regex("(?m)^EOF$").findAll(launcher).count())
    }

    @Test
    fun `xvfb picks a free display instead of a fixed one`() {
        val launcher = NapCatScripts.prepareRuntime().body

        assertTrue(launcher.contains("Xvfb -displayfd 3 -screen 0 1x1x8 +extension GLX +render 3>\"${'$'}display_file\""))
        assertTrue(launcher.contains("export DISPLAY=\":${'$'}display\""))
        assertFalse(launcher.contains("DISPLAY=:1"))
        assertTrue(launcher.indexOf("rm -f /tmp/.X*-lock /tmp/.X11-unix/X*") < launcher.indexOf("Xvfb -displayfd 3"))
        assertTrue(NapCatScripts.cleanup().body.contains("rm -f /tmp/.X*-lock /tmp/.X11-unix/X* /tmp/.maidroid-display"))
    }

    @Test
    fun `interrupted installs are repaired before installing packages`() {
        val install = NapCatScripts.install("token", repoSource).body
        val aptStep = install.indexOf("maidroid_apt_install zip unzip jq curl xvfb procps ca-certificates")

        assertTrue(install.indexOf("dpkg --configure -a") in 0 until aptStep)
        assertTrue(install.indexOf("maidroid_apt_write_network_conf\n") in 0 until aptStep)
        assertTrue(install.indexOf("rm -rf \"${'$'}old_backup\"") < install.indexOf("mv \"${'$'}HOME/napcat\" \"${'$'}backup_dir\""))
    }
}
