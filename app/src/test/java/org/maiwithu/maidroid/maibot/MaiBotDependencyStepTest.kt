package org.maiwithu.maidroid.maibot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.maiwithu.maidroid.runtime.ContainerCommandResult

class MaiBotDependencyStepTest {

    // 真机容器里 uv 0.13 断网时的原样输出。
    @Test
    fun `network failure keeps what failed and the root cause`() {
        val summary = uvErrorSummary(
            """
            Using CPython 3.13.17 interpreter at: /root/.local/share/uv/python/cpython-3.13-linux-aarch64-gnu/bin/python3.13
            error: Request failed after 3 retries in 8.9s
              cause: Failed to fetch: https://pypi.org/simple/requests/
              cause: error sending request for url (https://pypi.org/simple/requests/)
              cause: client error (Connect)
              cause: tunnel error: failed to create underlying connection
              cause: tcp connect error
              cause: Connection refused (os error 111)
            """.trimIndent()
        )

        assertEquals("Request failed after 3 retries in 8.9s（Connection refused (os error 111)）", summary)
    }

    @Test
    fun `older caused by lines are read as causes too`() {
        val summary = uvErrorSummary(
            """
            error: Failed to fetch: `https://mirrors.aliyun.com/pypi/simple/aiohttp/`
              Caused by: Request failed after 3 retries
              Caused by: dns error: failed to lookup address information: Name or service not known
            """.trimIndent()
        )

        assertEquals(
            "Failed to fetch: `https://mirrors.aliyun.com/pypi/simple/aiohttp/`" +
                "（dns error: failed to lookup address information: Name or service not known）",
            summary
        )
    }

    @Test
    fun `tree style build failure ignores the trailing hint`() {
        val summary = uvErrorSummary(
            """
              × Failed to build `zstandard==0.23.0`
              ├─▶ The build backend returned an error
              ╰─▶ Call to `setuptools.build_meta.build_wheel` failed (exit status: 1)

                  [stderr]
                  error: command 'gcc' failed: No such file or directory

                  hint: This usually indicates a problem with the package or the build environment.
            """.trimIndent()
        )

        assertEquals(
            "Failed to build `zstandard==0.23.0`" +
                "（Call to `setuptools.build_meta.build_wheel` failed (exit status: 1)）",
            summary
        )
    }

    @Test
    fun `headline without cause drops the trailing colon`() {
        assertEquals(
            "No solution found when resolving dependencies",
            uvErrorSummary("  × No solution found when resolving dependencies:")
        )
    }

    @Test
    fun `output without uv error lines has no summary`() {
        assertNull(uvErrorSummary("找不到可用的 uv，请重试\n"))
    }

    @Test
    fun `sync failure message prefers the uv error over the last line`() {
        val result = ContainerCommandResult(
            exitCode = 2,
            output = "error: Failed to fetch: `https://example/simple/x/`\nhint: retry later\n"
        )

        assertEquals(
            "安装 Python 依赖包失败：Failed to fetch: `https://example/simple/x/`",
            MaiBotDependencyStep.Packages.failureMessage(result)
        )
    }

    @Test
    fun `installer failure message uses the reason printed last by the script`() {
        val result = ContainerCommandResult(
            exitCode = 1,
            output = "[uv] downloading installer: https://astral.sh/uv/install.sh\n" +
                "curl: (28) Connection timed out after 20001 milliseconds\n" +
                "下载不到 uv 安装脚本（astral.sh 和所有 GitHub 镜像都失败），请检查网络后重试\n"
        )

        assertEquals(
            "安装 uv 失败：下载不到 uv 安装脚本（astral.sh 和所有 GitHub 镜像都失败），请检查网络后重试",
            MaiBotDependencyStep.Uv.failureMessage(result)
        )
    }
}
