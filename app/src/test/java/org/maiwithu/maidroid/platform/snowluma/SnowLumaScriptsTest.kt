package org.maiwithu.maidroid.platform.snowluma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maiwithu.maidroid.platform.qq.QqClient
import org.maiwithu.maidroid.platform.qq.QqCustomSource
import org.maiwithu.maidroid.platform.qq.QqSourceParseResult
import org.maiwithu.maidroid.platform.qq.parseQqCustomSource

class SnowLumaScriptsTest {
    private val customRepo = (parseQqCustomSource(QqClient.SnowLuma, "https://github.com/owner/SnowLuma")
        as QqSourceParseResult.Valid).source
    private val install = SnowLumaScripts.install(token = "tok", webUiPassword = "pass-1234", source = customRepo).body
    private val launcher = SnowLumaScripts.prepareRuntime().body

    @Test
    fun `custom repository prefers v2_4_2 then falls back to latest non-lite arm64 release`() {
        // 优先尝试 v2.4.2 发行包，失败时经 GitHub Releases API 兜底查询最新非 lite 的 linux-arm64 压缩包
        val preferred = "https://github.com/owner/SnowLuma/releases/download/v${SnowLumaScripts.VERSION}/SnowLuma-v${SnowLumaScripts.VERSION}-linux-arm64.tar.gz"
        val latestApi = "https://api.github.com/repos/owner/SnowLuma/releases/latest"
        assertTrue(install.indexOf(preferred) in 0 until install.indexOf(latestApi))
        assertTrue(install.contains("-lite"))
        assertTrue(install.contains("tar -tzf \"${'$'}archive_out.part\""))
        assertTrue(install.contains("WARNING: bundle sha256"))
        assertTrue(install.contains(SnowLumaScripts.OFFICIAL_BUNDLE_SHA256))
        assertFalse(install.contains("github.com/SnowLuma/SnowLuma"))
        // 先解到临时目录、确认入口和 node 都在才换上
        assertTrue(install.indexOf("tar -xzf") < install.indexOf("mv /root/snowluma/app.tmp /root/snowluma/app"))
        assertTrue(install.contains("[ ! -x /root/snowluma/app.tmp/node ]"))
    }

    @Test
    fun `supports direct archive and pinned tag custom sources`() {
        val directInstall = SnowLumaScripts.install(
            token = "tok",
            webUiPassword = "pass-1234",
            source = QqCustomSource.DirectArchive("https://example.com/SnowLuma-custom.tar.gz")
        ).body
        assertTrue(directInstall.contains("maidroid_snowluma_fetch_archive /root/.cache/maidroid-snowluma/snowluma.tar.gz 'https://example.com/SnowLuma-custom.tar.gz'"))

        val taggedRepo = (parseQqCustomSource(QqClient.SnowLuma, "https://github.com/owner/SnowLuma/releases/tag/v2.5.0")
            as QqSourceParseResult.Valid).source
        val taggedInstall = SnowLumaScripts.install(token = "tok", webUiPassword = "pass-1234", source = taggedRepo).body
        assertTrue(taggedInstall.contains("https://github.com/owner/SnowLuma/releases/download/v2.5.0/SnowLuma-v2.5.0-linux-arm64.tar.gz"))
        assertTrue(taggedInstall.contains("https://api.github.com/repos/owner/SnowLuma/releases/tags/v2.5.0"))

        val slashTaggedRepo = (parseQqCustomSource(QqClient.SnowLuma, "https://github.com/owner/SnowLuma/releases/tag/release/v2.5.1")
            as QqSourceParseResult.Valid).source
        val slashTaggedInstall = SnowLumaScripts.install(token = "tok", webUiPassword = "pass-1234", source = slashTaggedRepo).body
        assertTrue(
            slashTaggedInstall.contains(
                "https://github.com/owner/SnowLuma/releases/download/release/v2.5.1/SnowLuma-v2.5.1-linux-arm64.tar.gz"
            )
        )
    }

    @Test
    fun `linux qq is installed only when it is not the pinned version`() {
        val check = install.indexOf("qq_current=")
        val download = install.indexOf("maidroid_snowluma_fetch /root/.cache/maidroid-snowluma/QQ_3.2.32_260812_arm64_01.deb")
        assertTrue(check in 0 until download)
        // package.json 里写的是“版本号-构建号”（真机读出来的），不是包名里的发布日期
        assertTrue(install.contains("qq_wanted='3.2.32-52194'"))
        // 官方地址在前，GitHub 备份在后；NapCat 装过别的版本时允许降级
        assertTrue(install.indexOf("https://qqdl.gtimg.cn/") < install.indexOf("maidroid_download_urls 'https://github.com/Rodert/qq-versions/"))
        assertTrue(install.contains("maidroid_apt_install --allow-downgrades /root/.cache/maidroid-snowluma/QQ_3.2.32_260812_arm64_01.deb"))
        assertTrue(install.contains("0.0.0.0 qqpatch.gtimg.cn"))
        // 装之前先收拾 dpkg 半装状态
        assertTrue(install.indexOf("dpkg --configure -a") < install.indexOf("maidroid_apt_install xvfb xdotool"))
    }

    @Test
    fun `linux qq runtime libraries are ensured even when qq deb install is skipped`() {
        // 即使容器内早已装过 /opt/QQ 而跳过 deb 安装，也要在 if/else 外检查并补齐运行所需的四个共享库。
        val qqBranchEnd = install.indexOf("rm -f /root/.cache/maidroid-snowluma/QQ_3.2.32_260812_arm64_01.deb\nfi")
        assertTrue(qqBranchEnd >= 0)
        val afterQqBranch = install.substring(qqBranchEnd)

        val readyCall = afterQqBranch.indexOf("if ! maidroid_linuxqq_deps_ready; then")
        assertTrue(readyCall > 0)
        assertTrue(afterQqBranch.indexOf("maidroid_linuxqq_install_deps", readyCall) > readyCall)
        assertTrue(install.contains("libnss3 libgbm1 libgssapi-krb5-2"))
        assertTrue(install.contains("libasound2t64"))
        assertTrue(install.contains("libasound2"))
        assertTrue(install.contains("LinuxQQ 运行依赖安装失败"))
    }

    @Test
    fun `prepare runtime checks and self-heals missing linux qq shared libraries without blocking startup`() {
        // 每次启动前注入 AptMirrors 并仅在已装 qq 时检查四库，缺库自动补装且失败时不 exit 1 阻断生成启动脚本。
        val selfHealSection = launcher.substringBefore("mkdir -p /root/snowluma/data")
        assertTrue(selfHealSection.contains("maidroid_apt_install()"))
        assertTrue(selfHealSection.contains("if command -v qq >/dev/null 2>&1 && ! maidroid_linuxqq_deps_ready; then"))
        assertTrue(selfHealSection.contains("[自动修复]"))
        assertTrue(selfHealSection.contains("maidroid_linuxqq_install_deps"))
        assertFalse(selfHealSection.contains("exit 1"))
    }

    @Test
    fun `onebot config serves maibot on the shared port with the shared token`() {
        val config = install.substringAfter("cat > /root/snowluma/data/config/onebot.json.tmp <<EOF").substringBefore("\nEOF\n")

        assertTrue(config.contains("\"host\": \"127.0.0.1\""))
        assertTrue(config.contains("\"port\": 8095"))
        assertTrue(config.contains("\"accessToken\": \"${'$'}onebot_token\""))
        // 不开 SnowLuma 默认的 HTTP 服务端，关掉群里 #sl 自动回状态
        assertTrue(config.contains("\"httpServers\": []"))
        assertTrue(config.contains("\"statusCommand\": { \"enabled\": false }"))
        // token 和 NapCat 共用，写配置前已经读出来
        assertTrue(install.indexOf("onebot_token=\"${'$'}(cat /root/.maidroid/napcat_token)\"") < install.indexOf("onebot.json"))
        // 优先从解压后的 package.json 读取实际版本原子写完成标记
        assertTrue(install.contains("jq -r '.version // empty' /root/snowluma/app/package.json"))
        assertTrue(install.contains("mv -f /root/.maidroid/snowluma_ready.tmp /root/.maidroid/snowluma_ready"))
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
        // SnowLuma 的工作目录是数据目录，QQ 不带 WebUI 密码且不禁用 zygote
        assertTrue(launcher.contains("(cd /root/snowluma/data && exec /root/snowluma/app/node /root/snowluma/app/index.mjs) 2>&1 &"))
        assertTrue(launcher.contains("env -u SNOWLUMA_WEBUI_BOOTSTRAP_PASSWORD -u NODE_OPTIONS qq --no-sandbox --disable-gpu --disable-dev-shm-usage 2>&1 &"))
        assertFalse(launcher.contains("--no-zygote"))
    }

    @Test
    fun `launcher uses exec bash and wait -n supervision without polling child processes`() {
        val launch = SnowLumaScripts.launch().body
        assertEquals("cd /root && exec bash /root/snowluma-launcher.sh", launch)
        assertFalse(launch.contains("script -q"))

        // 宿主机 QqProcLocksSync 接管 /proc/locks，节点保育留在主脚本内由 wait -n 阻塞等待
        assertFalse(launcher.contains("pgrep -x qq"))
        assertFalse(launcher.contains("oom_score_adj"))
        assertTrue(launcher.contains("wait -n -p exited_pid \"\$qq_pid\" \"\$snowluma_pid\""))
        assertTrue(launcher.indexOf(": > /tmp/.maidroid-proc-locks") > launcher.indexOf("qq exited with code"))
        // QQ 退出后最后结束 Xvfb，proot 才会跟着退出
        assertTrue(launcher.indexOf("kill \"\$xvfb_pid\"") > launcher.indexOf("pkill -TERM -f \"snowluma.app.index[.]mjs\""))
        // 清理时只清空替身，不删：proot 每条命令都要绑定它
        val cleanup = SnowLumaScripts.cleanup().body
        assertTrue(cleanup.contains(": > /tmp/.maidroid-proc-locks"))
        assertFalse(cleanup.contains("rm -f /tmp/.maidroid-proc-locks "))
    }

    @Test
    fun `exit targets match the real processes`() {
        val snowLuma = Regex(SnowLumaScripts.SNOWLUMA_EXIT_TARGET.pattern)
        assertTrue(snowLuma.containsMatchIn("node /root/snowluma/app/index.mjs"))
        assertTrue(snowLuma.containsMatchIn("/root/snowluma/app/node /root/snowluma/app/index.mjs"))
        assertFalse(snowLuma.containsMatchIn("bash /root/snowluma-launcher.sh"))
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
