package org.maiwithu.maidroid.platform.snowluma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maiwithu.maidroid.platform.qq.QqClient

class SnowLumaScriptsTest {
    private val install = SnowLumaScripts.install(token = "tok", webUiPassword = "pass-1234").body
    private val launcher = SnowLumaScripts.prepareRuntime().body

    @Test
    fun `release bundle is pinned and checked before unpacking`() {
        // 固定版本，经 GitHub 镜像下载，sha256 对不上就换下一个来源
        assertTrue(install.contains("releases/download/v${SnowLumaScripts.VERSION}/SnowLuma-v${SnowLumaScripts.VERSION}-linux-arm64.tar.gz"))
        assertTrue(install.contains("maidroid_github_urls 'https://github.com/SnowLuma/SnowLuma/releases/download/"))
        assertTrue(install.contains("75311338de0d6588295ae71d4a242584294a0b5b349b35af2145eddaad6b10b8"))
        assertTrue(install.contains("if [ \"${'$'}fetch_got\" = \"${'$'}fetch_sha\" ]; then"))
        // 先解到临时目录、确认入口和 node 都在才换上
        assertTrue(install.indexOf("tar -xzf") < install.indexOf("mv /root/snowluma/app.tmp /root/snowluma/app"))
        assertTrue(install.contains("[ ! -x /root/snowluma/app.tmp/node ]"))
    }

    @Test
    fun `linux qq is installed only when it is not the pinned version`() {
        val check = install.indexOf("qq_current=")
        val download = install.indexOf("maidroid_snowluma_fetch /root/.cache/maidroid-snowluma/QQ_3.2.32_260812_arm64_01.deb")
        assertTrue(check in 0 until download)
        // package.json 里写的是“版本号-构建号”（真机读出来的），不是包名里的发布日期
        assertTrue(install.contains("qq_wanted='3.2.32-52194'"))
        // 官方地址在前，GitHub 备份在后；NapCat 装过别的版本时允许降级
        assertTrue(install.indexOf("https://qqdl.gtimg.cn/") < install.indexOf("maidroid_github_urls 'https://github.com/Rodert/qq-versions/"))
        assertTrue(install.contains("maidroid_apt_install --allow-downgrades /root/.cache/maidroid-snowluma/QQ_3.2.32_260812_arm64_01.deb"))
        assertTrue(install.contains("0.0.0.0 qqpatch.gtimg.cn"))
        // 装之前先收拾 dpkg 半装状态
        assertTrue(install.indexOf("dpkg --configure -a") < install.indexOf("maidroid_apt_install xvfb xdotool"))
    }

    @Test
    fun `onebot config serves maibot on the shared port with the shared token`() {
        val config = install.substringAfter("cat > /root/snowluma/data/config/onebot.json <<EOF").substringBefore("\nEOF\n")

        assertTrue(config.contains("\"host\": \"127.0.0.1\""))
        assertTrue(config.contains("\"port\": 8095"))
        assertTrue(config.contains("\"accessToken\": \"${'$'}onebot_token\""))
        // 不开 SnowLuma 默认的 HTTP 服务端，关掉群里 #sl 自动回状态
        assertTrue(config.contains("\"httpServers\": []"))
        assertTrue(config.contains("\"statusCommand\": { \"enabled\": false }"))
        // token 和 NapCat 共用，写配置前已经读出来
        assertTrue(install.indexOf("onebot_token=\"${'$'}(cat /root/.maidroid/napcat_token)\"") < install.indexOf("onebot.json"))
    }

    @Test
    fun `heredoc terminators stay at column zero`() {
        assertEquals(2, Regex("(?m)^EOF$").findAll(install).count())
        assertEquals(1, Regex("(?m)^EOF$").findAll(launcher).count())
    }

    @Test
    fun `launcher mirrors the screen and shares the hook directory with qq`() {
        assertTrue(launcher.contains("Xvfb -displayfd 3 -screen 0 1280x800x24 -fbdir /tmp/snowluma-screen"))
        assertTrue(launcher.contains("export SNOWLUMA_HOOK_RUNTIME_DIR=/tmp/snowluma-hook"))
        assertTrue(launcher.contains("export SNOWLUMA_HOOK_AUTOLOAD=1"))
        assertTrue(launcher.contains("export SNOWLUMA_UPDATE_CHECK=0"))
        // WebUI 端口由监管器传进来，没传就用默认的，并记下来给宿主侧
        assertTrue(launcher.contains("export SNOWLUMA_WEBUI_PORT=\"${'$'}{SNOWLUMA_WEBUI_PORT:-5099}\""))
        assertTrue(launcher.contains("> /tmp/.maidroid-snowluma-webui-port"))
        // 只有用户同意过协议才替用户确认
        assertTrue(launcher.indexOf("if [ -f /root/.maidroid/snowluma_consent ]; then") < launcher.indexOf("export SNOWLUMA_ACCEPT_EULA=1"))
        // SnowLuma 的工作目录是数据目录，QQ 不带 WebUI 密码
        assertTrue(launcher.contains("(cd /root/snowluma/data && exec /root/snowluma/app/node /root/snowluma/app/index.mjs) 2>&1 &"))
        assertTrue(launcher.contains("env -u SNOWLUMA_WEBUI_BOOTSTRAP_PASSWORD qq --no-sandbox --disable-gpu --disable-dev-shm-usage"))
    }

    @Test
    fun `launcher rebuilds the lock table from qq fdinfo while qq runs`() {
        // QQ 起来之前就开始拼，先写临时文件再改名；QQ 退出后停掉并清空
        val loop = launcher.indexOf("for qq_pid in \$(pgrep -x qq); do")
        assertTrue(loop in 0 until launcher.indexOf("Launching LinuxQQ"))
        assertTrue(launcher.contains("grep -h '^lock:' /proc/\$qq_pid/fdinfo/* 2>/dev/null"))
        assertTrue(launcher.contains("done | sed 's/^lock:[[:space:]]*//' > /tmp/.maidroid-proc-locks.new"))
        assertTrue(launcher.contains("mv -f /tmp/.maidroid-proc-locks.new /tmp/.maidroid-proc-locks"))
        assertTrue(launcher.indexOf("kill \"\$locks_pid\"") > launcher.indexOf("qq exited with code"))
        // QQ 退出后最后结束 Xvfb，proot 才会跟着退出
        assertTrue(launcher.indexOf("kill \"\$xvfb_pid\"") > launcher.indexOf("kill -TERM \"\$snowluma_pid\""))
        // 清理时只清空替身，不删：proot 每条命令都要绑定它
        val cleanup = SnowLumaScripts.cleanup().body
        assertTrue(cleanup.contains(": > /tmp/.maidroid-proc-locks"))
        assertFalse(cleanup.contains("rm -f /tmp/.maidroid-proc-locks "))
    }

    @Test
    fun `exit targets match the real processes`() {
        val snowLuma = Regex(SnowLumaScripts.SNOWLUMA_EXIT_TARGET.pattern)
        // 真机上 ps 看到的是 node 改过的命令行；启动时写的原样也要认
        assertTrue(snowLuma.containsMatchIn("node /root/snowluma/app/index.mjs"))
        assertTrue(snowLuma.containsMatchIn("/root/snowluma/app/node /root/snowluma/app/index.mjs"))
        assertFalse(snowLuma.containsMatchIn("bash /root/snowluma-launcher.sh"))
        // 清理残留时同样认得出改过命令行的 node
        assertTrue(SnowLumaScripts.cleanup().body.contains("snowluma.app.index[.]mjs"))
        val qq = Regex(SnowLumaScripts.QQ_EXIT_TARGET.pattern)
        assertTrue(qq.containsMatchIn("qq --no-sandbox --disable-gpu --disable-dev-shm-usage"))
    }

    @Test
    fun `click is sent to the display xvfb picked`() {
        assertEquals("DISPLAY=:3 xdotool mousemove 120 340 click 1", SnowLumaScripts.click(display = 3, x = 120, y = 340).body)
    }

    @Test
    fun `qq client choice defaults to napcat for older installs`() {
        assertEquals(QqClient.NapCat, QqClient.fromWireValue(null))
        assertEquals(QqClient.NapCat, QqClient.fromWireValue("garbage"))
        assertEquals(QqClient.SnowLuma, QqClient.fromWireValue("snowluma\n"))
        assertEquals(QqClient.NapCat, QqClient.fromWireValue("napcat"))
    }
}
