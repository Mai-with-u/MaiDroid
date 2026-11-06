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
        // OneBot token 和 SnowLuma 共用一份：先读出或存下，写 onebot11.json 时才有值
        assertTrue(install.indexOf("napcat_token=\"${'$'}(cat /root/.maidroid/napcat_token)\"") in 0 until install.indexOf("onebot11.json"))
        // 安装脚本里的 here-doc 结束标记必须顶格，否则 shell 找不到结尾
        assertTrue(install.contains("\nEOF\n"))
    }

    @Test
    fun `xvfb picks a free display instead of a fixed one`() {
        val launcher = NapCatScripts.prepareRuntime().body

        // 别的应用占着同一个显示号时不能撞上：让 Xvfb 自己挑，挑中的号再给 QQ 用
        assertTrue(launcher.contains("Xvfb -displayfd 3 -screen 0 1x1x8 +extension GLX +render 3>\"${'$'}display_file\""))
        assertTrue(launcher.contains("export DISPLAY=\":${'$'}display\""))
        assertFalse(launcher.contains("DISPLAY=:1"))
        // 被强杀、断电留下的锁和套接字在起 Xvfb 前删掉，cleanup 也把记下的号一起删
        assertTrue(launcher.indexOf("rm -f /tmp/.X*-lock /tmp/.X11-unix/X*") < launcher.indexOf("Xvfb -displayfd 3"))
        assertTrue(NapCatScripts.cleanup().body.contains("rm -f /tmp/.X*-lock /tmp/.X11-unix/X* /tmp/.maidroid-display"))
    }

    @Test
    fun `interrupted installs are repaired before running the installer`() {
        val install = NapCatScripts.install("token").body
        val installer = install.indexOf("bash napcat.sh")

        // 上次被打断留下的 dpkg 半装状态先收拾，apt 带超时并会自动换源
        assertTrue(install.indexOf("dpkg --configure -a") in 0 until installer)
        assertTrue(install.indexOf("maidroid_apt_write_network_conf\n") in 0 until installer)
        assertTrue(install.indexOf("maidroid_apt_install zip unzip jq curl xvfb screen xauth procps g++") in 0 until installer)
        // 没装完的目录只留最新一份备份，反复打断也不会占满空间
        assertTrue(install.indexOf("rm -rf \"${'$'}old_backup\"") < install.indexOf("mv \"${'$'}HOME/napcat\" \"${'$'}backup_dir\""))
    }
}
