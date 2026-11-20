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
}
