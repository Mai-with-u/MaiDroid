package org.maiwithu.maidroid.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maiwithu.maidroid.maibot.MaiBotScripts
import org.maiwithu.maidroid.platform.napcat.NapCatScripts

class RequestExitScriptTest {

    // 2026-11 在调试机上 ps 抓到的容器进程命令行（MaiBot 1.3.19 + NapCat），proot 那行只保留开头和结尾。
    private val maiBotProcesses = listOf(
        "libproot_exec.so --link2symlink -0 -r /data/user/0/org.maiwithu.maidroid/files/debian -w /opt/MaiBot " +
            "/bin/sh -c unset LD_LIBRARY_PATH; cd '/opt/MaiBot' && if command -v script >/dev/null 2>&1; then " +
            "script -q -c 'uv run python bot.py' /dev/null; else uv run python bot.py; fi",
        "sh -c unset LD_LIBRARY_PATH; cd '/opt/MaiBot' && if command -v script >/dev/null 2>&1; then " +
            "script -q -c 'uv run python bot.py' /dev/null; else uv run python bot.py; fi",
        "script -q -c uv run python bot.py /dev/null",
        "sh -c uv run python bot.py",
        "uv run python bot.py",
        "python bot.py",
        "python -m src.runtime",
        "python -m src.plugin_runtime.runner.runner_main"
    )

    private val napCatProcesses = listOf(
        "script -q -c bash /root/launcher.sh /dev/null",
        "sh -c bash /root/launcher.sh",
        "bash /root/launcher.sh",
        "Xvfb :1 -screen 0 1x1x8 +extension GLX +render",
        "qq --no-sandbox --disable-gpu --disable-dev-shm-usage",
        "qq --type=zygote --no-zygote-sandbox --no-sandbox",
        "qq --type=zygote --no-sandbox",
        "exe --type=utility --utility-sub-type=network.mojom.NetworkService --no-sandbox --disable-dev-shm-usage"
    )

    // 和脚本里 awk 的条件一致：匹配 pattern、不匹配 exclude、不含 awk。这几个正则在 awk 和 Java 里含义相同。
    private fun ExitTarget.selects(args: String): Boolean =
        Regex(pattern).containsMatchIn(args) && !Regex(exclude).containsMatchIn(args) && !args.contains("awk")

    @Test
    fun `maibot exit goes only to the runner, not the sh script uv layers or the worker`() {
        assertEquals(listOf("python bot.py"), maiBotProcesses.filter { MaiBotScripts.EXIT_TARGET.selects(it) })
    }

    @Test
    fun `qq exit goes only to the main process, not its child processes or xvfb`() {
        assertEquals(
            listOf("qq --no-sandbox --disable-gpu --disable-dev-shm-usage"),
            napCatProcesses.filter { NapCatScripts.QQ_EXIT_TARGET.selects(it) }
        )
    }

    @Test
    fun `stale maibot cleanup catches every maibot layer and leaves napcat alone`() {
        val stale = Regex(MaiBotScripts.STALE_PROCESS_PATTERN)

        assertEquals(maiBotProcesses, maiBotProcesses.filter { stale.containsMatchIn(it) })
        assertEquals(emptyList<String>(), napCatProcesses.filter { stale.containsMatchIn(it) })
    }

    @Test
    fun `the exit script never selects its own command line`() {
        listOf(MaiBotScripts.requestExit(), NapCatScripts.requestQqExit()).forEach { script ->
            val ownCommandLine = "sh -c ${script.body}"
            assertFalse(MaiBotScripts.EXIT_TARGET.selects(ownCommandLine))
            assertFalse(NapCatScripts.QQ_EXIT_TARGET.selects(ownCommandLine))
        }
    }

    @Test
    fun `the script sends sigterm and waits up to the given timeout`() {
        val body = requestExitScript(ExitTarget("a[.]b", "c", "Demo"), timeoutSeconds = 42, logPrefix = "[Demo] ")

        assertTrue(body.contains("kill -TERM"))
        assertTrue(body.contains("-ge 42"))
        assertTrue(body.contains("awk '/a[.]b/ && !/c/ && !/awk/"))
    }

    @Test
    fun `exit codes map to outcomes and a failed launch counts as not confirmed`() {
        assertEquals(RequestedExit.Exited, requestedExitOf(ContainerCommandResult(0, "")))
        assertEquals(RequestedExit.NotFound, requestedExitOf(ContainerCommandResult(3, "")))
        assertEquals(RequestedExit.StillRunning, requestedExitOf(ContainerCommandResult(4, "")))
        assertEquals(RequestedExit.StillRunning, requestedExitOf(ContainerCommandResult(-1, "")))
    }
}
