package org.maiwithu.maidroid.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maiwithu.maidroid.diagnostics.FailureCause
import org.maiwithu.maidroid.diagnostics.FailureStep
import org.maiwithu.maidroid.diagnostics.LogRedactor

class AutoErrorExtractorTest {

    private fun testRedactor(): LogRedactor = LogRedactor(
        listOf(
            LogRedactor.Secret("API Key", "sk-test-secret-key-987654"),
            LogRedactor.Secret("QQ号", "123456789")
        )
    )

    @Test
    fun classifyMatrixFiltersNoiseAndSeparatesCategories() {
        assertEquals(AutoErrorCategory.Crash, AutoErrorExtractor.classifyForAutoReport(FailureStep.AppCrash, null))
        assertEquals(AutoErrorCategory.Install, AutoErrorExtractor.classifyForAutoReport(FailureStep.UvSync, null))
        assertEquals(
            AutoErrorCategory.Install,
            AutoErrorExtractor.classifyForAutoReport(FailureStep.UvSync, FailureCause.DependencyBuild)
        )
        assertNull(AutoErrorExtractor.classifyForAutoReport(FailureStep.UvSync, FailureCause.Dns))
        assertNull(AutoErrorExtractor.classifyForAutoReport(FailureStep.ContainerUnpack, FailureCause.DiskFull))
        assertNull(AutoErrorExtractor.classifyForAutoReport(FailureStep.QqOfficialConnect, null))

        assertEquals(
            AutoErrorCategory.MaiBotBoot,
            AutoErrorExtractor.classifyForAutoReport(FailureStep.MaiBotCrash, null)
        )
        assertEquals(
            AutoErrorCategory.MaiBotBoot,
            AutoErrorExtractor.classifyForAutoReport(FailureStep.MaiBotCrash, FailureCause.MissingModule)
        )
        assertNull(AutoErrorExtractor.classifyForAutoReport(FailureStep.MaiBotBlocked, FailureCause.PortInUse))
        assertNull(AutoErrorExtractor.classifyForAutoReport(FailureStep.MaiBotBlocked, FailureCause.LowBattery))
    }

    @Test
    fun parseFailureCodeRestoresStepAndOptionalCause() {
        val withCause = AutoErrorExtractor.parseFailureCode("UV-SYNC/NET-DNS")
        assertEquals(FailureStep.UvSync, withCause?.first)
        assertEquals(FailureCause.Dns, withCause?.second)

        val bareStep = AutoErrorExtractor.parseFailureCode("CT-PROOT")
        assertEquals(FailureStep.ContainerProot, bareStep?.first)
        assertNull(bareStep?.second)

        assertNull(AutoErrorExtractor.parseFailureCode("UNKNOWN-STEP"))
    }

    @Test
    fun crashDraftStripsTerminalTailRedactsSecretsAndStabilizesFingerprint() {
        val crashA = """
            MaiDroid 0.2.5（10，release）· HONOR OXF-AN10 · Android 12（API 31）
            时间：2026-11-20 08:10:00 +08:00
            线程：main

            java.lang.IllegalStateException: Token sk-test-secret-key-987654 for 123456789 broken
                at org.maiwithu.maidroid.ui.home.HomeScreenKt.HomeScreen(HomeScreen.kt:88)
                at androidx.compose.runtime.ComposerImpl.doCompose(ComposerImpl.kt:100)

            ----- 闪退前的终端输出 -----
            [MaiBot] 收到消息 用户私聊秘密内容: 今天晚上去吃饭
        """.trimIndent()

        val crashB = """
            MaiDroid 0.2.5（10，release）· Xiaomi 2311 · Android 14（API 34）
            时间：2026-11-20 09:20:00 +08:00
            线程：main

            java.lang.IllegalStateException: Token sk-test-secret-key-987654 for 123456789 broken
                at org.maiwithu.maidroid.ui.home.HomeScreenKt.HomeScreen(HomeScreen.kt:91)
                at androidx.compose.runtime.ComposerImpl.doCompose(ComposerImpl.kt:100)

            ----- 闪退前的终端输出 -----
            [MaiBot] 另一条群聊隐私日志
        """.trimIndent()

        val redactor = testRedactor()
        val draftA = AutoErrorExtractor.buildCrashDraft("java.lang.IllegalStateException: broken", crashA, redactor)
        val draftB = AutoErrorExtractor.buildCrashDraft("java.lang.IllegalStateException: broken", crashB, redactor)

        assertEquals(AutoErrorCategory.Crash, draftA.category)
        assertFalse(draftA.contextText.contains("闪退前的终端输出"))
        assertFalse(draftA.contextText.contains("今天晚上去吃饭"))
        assertFalse(draftA.contextText.contains("sk-test-secret-key-987654"))
        assertFalse(draftA.contextText.contains("123456789"))
        assertEquals(draftA.fingerprint, draftB.fingerprint)
    }

    @Test
    fun maiBotBootDraftExtractsOnlyTracebackAndRejectsNormalChatLines() {
        val logLines = listOf(
            "2026-11-20 09:00:01 | INFO | 收到消息 群聊 用户发了一段普通聊天内容",
            "[MaiBot] 2026-11-20 09:00:02 普通运行状态输出",
            "[MaiBot] Process started (PID: 12345)",
            "Traceback (most recent call last):",
            "  File \"/opt/MaiBot/src/runtime/app.py\", line 42, in main",
            "    import missing_pkg",
            "ModuleNotFoundError: No module named 'missing_pkg' with token sk-test-secret-key-987654",
            "2026-11-20 09:00:03 | INFO | 回复消息 绝对不能被带出去",
            "[MaiBot] Process exited with code: 1"
        )

        val draft = AutoErrorExtractor.buildMaiBotBootDraft(
            step = FailureStep.MaiBotCrash,
            cause = FailureCause.MissingModule,
            message = "MaiBot 启动后连续退出",
            recentRunLines = logLines,
            fallbackLogTail = null,
            redactor = testRedactor()
        )

        assertEquals(AutoErrorCategory.MaiBotBoot, draft.category)
        assertTrue(draft.contextText.contains("Traceback (most recent call last):"))
        assertTrue(draft.contextText.contains("ModuleNotFoundError"))
        assertFalse(draft.contextText.contains("普通聊天内容"))
        assertFalse(draft.contextText.contains("绝对不能被带出去"))
        assertFalse(draft.contextText.contains("普通运行状态输出"))
        assertFalse(draft.contextText.contains("sk-test-secret-key-987654"))

        // 没有 Traceback 也没有启动白名单行时，只输出脱敏占位摘要，绝不泄露普通日志
        val onlyNormalChat = listOf(
            "2026-11-20 09:00:01 | INFO | 聊天消息内容 hello world",
            "一些普通程序输出"
        )
        val noTbDraft = AutoErrorExtractor.buildMaiBotBootDraft(
            step = FailureStep.MaiBotTimeout,
            cause = null,
            message = "等待超时",
            recentRunLines = onlyNormalChat,
            fallbackLogTail = null,
            redactor = testRedactor()
        )
        assertFalse(noTbDraft.contextText.contains("hello world"))
        assertFalse(noTbDraft.contextText.contains("一些普通程序输出"))
        assertTrue(noTbDraft.contextText.contains("未提取到 Python Traceback"))
    }

    @Test
    fun installDraftStripsMaiBotRuntimeLinesAndNormalizesFingerprint() {
        val linesA = listOf(
            "[MaiBot] 用户正常聊天输出应当过滤",
            "[自动修复] 重试安装",
            "[NapCat] 2026-11-20 09:00:01 unzip /tmp/napcat.12345.zip error: Permission denied pid=4321",
            "[MaiDroid] 错误码 QQ-INSTALL 解压失败"
        )
        val linesB = listOf(
            "[NapCat] 2026-11-21 12:34:56 unzip /tmp/napcat.99999.zip error: Permission denied pid=8876",
            "[MaiDroid] 错误码 QQ-INSTALL 解压失败"
        )

        val draftA = AutoErrorExtractor.buildInstallDraft(
            step = FailureStep.NapCatInstall,
            cause = null,
            message = "解压失败",
            terminalPlainLines = linesA,
            setupComplete = true,
            redactor = testRedactor()
        )
        val draftB = AutoErrorExtractor.buildInstallDraft(
            step = FailureStep.NapCatInstall,
            cause = null,
            message = "解压失败",
            terminalPlainLines = linesB,
            setupComplete = true,
            redactor = testRedactor()
        )

        assertEquals(AutoErrorCategory.Install, draftA.category)
        assertFalse(draftA.contextText.contains("用户正常聊天输出"))
        assertFalse(draftA.contextText.contains("[MaiDroid] 错误码"))
        assertTrue(draftA.contextText.contains("Permission denied"))
        assertEquals(draftA.fingerprint, draftB.fingerprint)
    }

    @Test
    fun qqConfigDraftIgnoresUnrelatedMaiBotExceptionsAndAggregatesSameFailure() {
        val logA = listOf(
            "[NapCat] [NativeCrashHandler.cpp][uploadCrashEvent][331]get null crashDetailBean, return!",
            "[MaiBot] 11-21 23:17:25 [模型] Traceback (most recent call last):",
            "[MaiBot]   raise ValueError(\"图片嵌入必须配置 Provider 对应的 image_embedding_input 或 image_embedding_body\")",
            "[MaiBot] ValueError: 图片嵌入必须配置 Provider 对应的 image_embedding_input 或 image_embedding_body",
            "[NapCat] 安装失败: 等了 5 分钟麦麦还没生成 QQ 适配器的配置，请确认麦麦在运行后重试",
            "[NapCat] 等麦麦生成 QQ 适配器的配置，生成后自动迁移连接设置"
        )
        val logB = listOf(
            "[MaiBot] 11-21 23:40:01 [模型] RuntimeError: 另一个无关的大模型超时",
            "[NapCat] 安装失败: 等了 5 分钟麦麦还没生成 QQ 适配器的配置，请确认麦麦在运行后重试",
            "[NapCat] 等麦麦生成 QQ 适配器的配置，生成后自动迁移连接设置"
        )

        val draftA = AutoErrorExtractor.buildInstallDraft(
            step = FailureStep.NapCatAdapterConfig,
            cause = null,
            message = "等了 5 分钟麦麦还没生成 QQ 适配器的配置，请确认麦麦在运行后重试",
            terminalPlainLines = logA,
            setupComplete = true,
            redactor = testRedactor()
        )
        val draftB = AutoErrorExtractor.buildInstallDraft(
            step = FailureStep.NapCatAdapterConfig,
            cause = null,
            message = "等了 5 分钟麦麦还没生成 QQ 适配器的配置，请确认麦麦在运行后重试",
            terminalPlainLines = logB,
            setupComplete = true,
            redactor = testRedactor()
        )

        assertFalse(draftA.title.contains("image_embedding_input"))
        assertFalse(draftA.contextText.contains("image_embedding_input"))
        assertTrue(draftA.title.contains("等了 5 分钟"))
        assertEquals(draftA.fingerprint, draftB.fingerprint)

        // QQ-CONFIG 下真正与适配器相关的 MaiBot 加载报错应当保留，方便定位原因
        val withAdapterError = AutoErrorExtractor.buildInstallDraft(
            step = FailureStep.NapCatAdapterConfig,
            cause = FailureCause.MaiBotTooOld,
            message = "MaiBot 拒绝加载 QQ 适配器（当前版本低于最小要求）",
            terminalPlainLines = listOf(
                "[MaiBot] 插件 maibot-team.snowluma-adapter manifest 校验失败: 版本 1.4.3 低于最小要求 1.4.4",
                "[MaiBot] ValueError: 图片嵌入必须配置 Provider 对应的 image_embedding_input 或 image_embedding_body"
            ),
            setupComplete = true,
            redactor = testRedactor()
        )
        assertTrue(withAdapterError.contextText.contains("maibot-team.snowluma-adapter"))
        assertFalse(withAdapterError.contextText.contains("image_embedding_input"))
    }

    @Test
    fun maiBotBootDraftKeepsSigkillUptimeDiagnosticLineWithoutSplittingFingerprint() {
        val draftA = AutoErrorExtractor.buildMaiBotBootDraft(
            step = FailureStep.MaiBotCrash,
            cause = null,
            message = "MaiBot 启动后连续退出了 2 次（最后退出码 137），请查看终端输出里的报错",
            recentRunLines = listOf(
                "[MaiBot] Process started (PID: 11498)",
                "[MaiBot] Process exited with code: 137",
                "[MaiDroid] 容器进程收到 SIGKILL(137)，本轮已运行 305s，当前本应用子进程数：19"
            ),
            fallbackLogTail = null,
            redactor = testRedactor()
        )
        val draftB = AutoErrorExtractor.buildMaiBotBootDraft(
            step = FailureStep.MaiBotCrash,
            cause = null,
            message = "MaiBot 启动后连续退出了 2 次（最后退出码 137），请查看终端输出里的报错",
            recentRunLines = listOf(
                "[MaiBot] Process started (PID: 22100)",
                "[MaiBot] Process exited with code: 137",
                "[MaiDroid] 容器进程收到 SIGKILL(137)，本轮已运行 1842s，当前本应用子进程数：27"
            ),
            fallbackLogTail = null,
            redactor = testRedactor()
        )

        assertTrue(draftA.contextText.contains("本轮已运行 305s"))
        assertTrue(draftA.contextText.contains("子进程数：19"))
        assertEquals(draftA.fingerprint, draftB.fingerprint)
    }

    @Test
    fun qqConfigDraftDropsQrNoiseAndDoesNotUseLoginErrorAsTitle() {
        // 超过 50 行的日志，中间夹杂两整块字符画二维码与解码 URL，验证去除噪音后前置 MaiBot 报错不会被挤出窗口
        val qrBlock = List(22) { idx ->
            if (idx % 2 == 0) "[NapCat] █ ▄▄▄▄▄ █▀ █▀▀▀ ▀▀██▄█▄▄▄▄█ ▄▄▄▄▄ █"
            else "[NapCat] █▄▀█ █ ▄▀█ ▀ ▄██▄▀█▄█▄  ▀▀▀▀▄ ▄▀▄██"
        }
        val fillerLines = List(25) { "[NapCat] 11-22 00:39:${10 + it} [info] ordinary status line $it" }
        val fullLogs = buildList {
            add("[MaiBot] 插件 maibot-team.snowluma-adapter manifest 校验失败: 版本 1.4.3 低于最小要求 1.4.4")
            addAll(fillerLines)
            addAll(qrBlock)
            add("[NapCat] 二维码解码URL: https://txz.qq.com/p?k=oddpZqC*wMEqVM8-0v0prZlBXi9k6-oh&f=1600001615")
            add("[NapCat] 11-22 00:39:59 [warn] 二维码已保存到 /root/napcat/cache/qrcode.png")
            add("[NapCat] 11-22 00:40:08 [info] [Core] [Login] 二维码已被扫描，等待确认...")
            add("[NapCat] 11-22 00:40:09 [error] [Core] [Login] Login Error,ErrType:  1  ErrCode: 5")
            add("[NapCat] 11-22 00:40:38 [warn] [Core] [Login] 请求刷新二维码 {\"requested\":true,\"msfStatus\":1}")
            add("[NapCat] 11-22 00:40:38 [warn] 请扫描下面的二维码，然后在手Q上授权登录：")
            addAll(qrBlock)
            add("[NapCat] 11-22 00:40:50 [error] [Core] [Login] Login Error,ErrType:  1  ErrCode: 5")
            add("[NapCat] 等麦麦生成 QQ 适配器的配置，生成后自动迁移连接设置")
        }

        val draftWithMaiBotErr = AutoErrorExtractor.buildInstallDraft(
            step = FailureStep.NapCatAdapterConfig,
            cause = FailureCause.MaiBotTooOld,
            message = "等了 5 分钟麦麦还没生成 QQ 适配器的配置，请确认麦麦在运行后重试",
            terminalPlainLines = fullLogs,
            setupComplete = true,
            redactor = testRedactor()
        )

        assertFalse(draftWithMaiBotErr.contextText.contains("txz.qq.com"))
        assertFalse(draftWithMaiBotErr.contextText.contains("█"))
        assertFalse(draftWithMaiBotErr.contextText.contains("▄"))
        assertFalse(draftWithMaiBotErr.contextText.contains("▀"))
        assertTrue(draftWithMaiBotErr.contextText.contains("maibot-team.snowluma-adapter"))
        assertFalse(draftWithMaiBotErr.title.contains("Login Error"))

        // 即使没有安装失败显式尾行或前置 MaiBot 报错，Login Error 也不得抢占 QQ-CONFIG 的标题
        val loginErrOnlyLogs = listOf(
            "[NapCat] 二维码解码URL: https://txz.qq.com/p?k=secret",
            "[NapCat] 11-22 00:40:50 [error] [Core] [Login] Login Error,ErrType:  1  ErrCode: 5",
            "[NapCat] 等麦麦生成 QQ 适配器的配置，生成后自动迁移连接设置"
        )
        val draftLoginOnly = AutoErrorExtractor.buildInstallDraft(
            step = FailureStep.NapCatAdapterConfig,
            cause = null,
            message = "等了 5 分钟麦麦还没生成 QQ 适配器的配置，请确认麦麦在运行后重试",
            terminalPlainLines = loginErrOnlyLogs,
            setupComplete = true,
            redactor = testRedactor()
        )
        assertFalse(draftLoginOnly.title.contains("Login Error"))
        assertTrue(draftLoginOnly.title.contains("等了 5 分钟"))
        assertTrue(draftLoginOnly.contextText.contains("Login Error"))
        assertFalse(draftLoginOnly.contextText.contains("txz.qq.com"))
    }

    @Test
    fun extractCrashHeaderVersionParsesVersionFromCrashFileHeader() {
        assertEquals(
            "0.2.3",
            AutoErrorExtractor.extractCrashHeaderVersion(
                "MaiDroid 0.2.3（8，release）· Xiaomi 22081212C · Android 15（API 35）\n时间：2026-11-17 15:43:06 +08:00"
            )
        )
        assertEquals(
            "0.3.1",
            AutoErrorExtractor.extractCrashHeaderVersion(
                "\nMaiDroid 0.3.1 (11, release) · HONOR\n线程：main"
            )
        )
        assertNull(AutoErrorExtractor.extractCrashHeaderVersion(null))
        assertNull(AutoErrorExtractor.extractCrashHeaderVersion(""))
        assertNull(AutoErrorExtractor.extractCrashHeaderVersion("MaiDroid\n时间：2026-11-22"))
    }

    @Test
    fun installDraftFiltersElectronDbusHexNoiseAndIgnoresDpkgLiberrorPackage() {
        val noiseLogsA = listOf(
            "[SnowLuma] [22918:1122/010411.225459:ERROR:dbus/object_proxy.cc:573] Failed to call method: org.freedesktop.DBus",
            "[SnowLuma] [22986:1122/010411.265192:ERROR:base/files/file_path_watcher_inotify.cc:923] Failed to read",
            "[SnowLuma] 01:18:13.161 › [1a1ff1b36b0][QQ hotUpdate] hotUpdateApi checkIsOnErrorVersion result: false",
            "[SnowLuma] [BuglyManager.cpp][UploadBugly][107]!!!in UploadBugly.",
            "[SnowLuma] [NativeCrashHandler.cpp][getCrashDetailBeanFromRecord][52]!!!! in NativeCrashHandler getCrashDetailBeanFromRecord, open file error!!!",
            "[SnowLuma] [31035][93113559165]DroppedFrame(1): host_id=2, time=93112658678, latest_seq=118",
            "[SnowLuma] a00d221c85c861831e8eca43b139782e82f98c5a264070cfb484e0ee999f920e",
            "[NapCat] 8318109752347ecb8d115913df8fd865254e93b7516936bb0b43f13c52f4061a"
        )
        val noiseDraft = AutoErrorExtractor.buildInstallDraft(
            step = FailureStep.SnowLumaAdapterConfig,
            cause = null,
            message = "等了 5 分钟麦麦还没生成 QQ 适配器的配置，请确认麦麦在运行后重试",
            terminalPlainLines = noiseLogsA,
            setupComplete = true,
            redactor = testRedactor()
        )
        assertFalse(noiseDraft.contextText.contains("ERROR:dbus/"))
        assertFalse(noiseDraft.contextText.contains("file_path_watcher_inotify"))
        assertFalse(noiseDraft.contextText.contains("QQ hotUpdate"))
        assertFalse(noiseDraft.contextText.contains("NativeCrashHandler.cpp"))
        assertFalse(noiseDraft.contextText.contains("BuglyManager.cpp"))
        assertFalse(noiseDraft.contextText.contains("DroppedFrame"))
        assertFalse(noiseDraft.contextText.contains("a00d221c85c861831e8eca43b139782e82f98c5a264070cfb484e0ee999f920e"))
        assertFalse(noiseDraft.contextText.contains("8318109752347ecb8d115913df8fd865254e93b7516936bb0b43f13c52f4061a"))
        assertTrue(noiseDraft.title.contains("等了 5 分钟"))

        val dpkgLogs = listOf(
            "Preparing to unpack .../09-liberror-perl_0.17029-2_all.deb ...",
            "Unpacking liberror-perl (0.17029-2) ...",
            "Setting up liberror-perl (0.17029-2) ...",
            "[MaiBot 更新] 失败：Debian rootfs 未就绪：OOBE 需要先解包 runtime/debian-rootfs.tar.xz"
        )
        val dpkgDraft = AutoErrorExtractor.buildInstallDraft(
            step = FailureStep.UpdatePrecheck,
            cause = null,
            message = "Debian rootfs 未就绪：OOBE 需要先解包 runtime/debian-rootfs.tar.xz",
            terminalPlainLines = dpkgLogs,
            setupComplete = false,
            redactor = testRedactor()
        )
        assertFalse(dpkgDraft.title.contains("liberror-perl"))
        assertTrue(dpkgDraft.title.contains("Debian rootfs 未就绪"))

        // 成功退出码（exit code: 0 / 退出码: 0）不得因匹配 "exit code"/"退出码" 而抢走错误标题
        val zeroExitLogs = listOf(
            "done.",
            "exit code: 0",
            "进程退出码: 0"
        )
        val zeroExitDraft = AutoErrorExtractor.buildInstallDraft(
            step = FailureStep.UpdatePrecheck,
            cause = null,
            message = "Debian rootfs 未就绪：OOBE 需要先解包 runtime/debian-rootfs.tar.xz",
            terminalPlainLines = zeroExitLogs,
            setupComplete = false,
            redactor = testRedactor()
        )
        assertFalse(zeroExitDraft.title.contains("exit code: 0"))
        assertFalse(zeroExitDraft.title.contains("退出码: 0"))
        assertTrue(zeroExitDraft.title.contains("Debian rootfs 未就绪"))
    }

    @Test
    fun maiBotBootDraftCapturesHostBootstrapFailureOnExitCodeTwo() {
        val logLinesA = listOf(
            "[MaiBot] Process started (PID: 2106)",
            "宿主启动失败：WebUI 凭据文件无法解析：/opt/MaiBot/host/webui.json",
            "[MaiBot] Process exited with code: 2",
            "[MaiBot] 2026-11-22 16:14:22 普通聊天消息绝不可泄漏",
            "[MaiBot] Process started (PID: 2341)"
        )
        val logLinesB = listOf(
            "[MaiBot] Process started (PID: 8801)",
            "宿主启动失败：WebUI 凭据文件无法解析：/opt/MaiBot/host/webui.json",
            "[MaiBot] Process exited with code: 2"
        )

        val draftA = AutoErrorExtractor.buildMaiBotBootDraft(
            step = FailureStep.MaiBotCrash,
            cause = null,
            message = "MaiBot 启动后连续退出了 2 次（最后退出码 2），请查看终端输出里的报错",
            recentRunLines = logLinesA,
            fallbackLogTail = null,
            redactor = testRedactor()
        )
        val draftB = AutoErrorExtractor.buildMaiBotBootDraft(
            step = FailureStep.MaiBotCrash,
            cause = null,
            message = "MaiBot 启动后连续退出了 2 次（最后退出码 2），请查看终端输出里的报错",
            recentRunLines = logLinesB,
            fallbackLogTail = null,
            redactor = testRedactor()
        )

        assertTrue(draftA.contextText.contains("宿主启动失败：WebUI 凭据文件无法解析"))
        assertFalse(draftA.contextText.contains("普通聊天消息绝不可泄漏"))
        assertTrue(draftA.title.startsWith("宿主启动失败：WebUI 凭据文件无法解析"))
        assertEquals(draftA.fingerprint, draftB.fingerprint)
    }

    @Test
    fun classifyFiltersUserGuardrailPrechecksWhileKeepingRealPrecheckFailures() {
        assertNull(
            AutoErrorExtractor.classifyForAutoReport(
                FailureStep.SnowLumaPrecheck,
                null,
                "NapCat 正在安装，等它装完再装 SnowLuma"
            )
        )
        assertNull(
            AutoErrorExtractor.classifyForAutoReport(
                FailureStep.NapCatPrecheck,
                null,
                "请填写 NapCat 的自定义仓库地址或安装包直链"
            )
        )
        assertNull(
            AutoErrorExtractor.classifyForAutoReport(
                FailureStep.SnowLumaPrecheck,
                null,
                "直链需指向 linux-arm64 的 .tar.gz 安装包，或改填仓库主页地址"
            )
        )

        assertEquals(
            AutoErrorCategory.Install,
            AutoErrorExtractor.classifyForAutoReport(
                FailureStep.SnowLumaPrecheck,
                null,
                "MaiBot 源码尚未安装，无法配置 QQ 适配器"
            )
        )
        assertEquals(
            AutoErrorCategory.Install,
            AutoErrorExtractor.classifyForAutoReport(
                FailureStep.NapCatPrecheck,
                FailureCause.MaiBotTooOld,
                "当前 MaiBot 版本 1.4.3 低于适配器要求"
            )
        )
    }
}
