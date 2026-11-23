package org.maiwithu.maidroid.maibot

import org.junit.Assert.assertTrue
import org.junit.Test
import org.maiwithu.maidroid.runtime.GitHubMirror
import org.maiwithu.maidroid.runtime.PyPiMirror

class MaiBotScriptsTest {
    @Test
    fun `sync switches pypi index on download failure and remembers the working one`() {
        val sync = MaiBotScripts.syncDependencies().body

        assertTrue(sync.contains("'清华 PyPI 镜像|https://pypi.tuna.tsinghua.edu.cn/simple'"))
        // 换源前删掉记着旧源地址的锁文件，换成功后把源记下来
        assertTrue(sync.contains("rm -f uv.lock"))
        assertTrue(sync.contains("> '${MaiBotScripts.PYPI_INDEX_FILE}'"))
        // 不是下载问题时重建一次 .venv
        assertTrue(sync.contains("rm -rf .venv"))
    }

    @Test
    fun `every uv command reads the remembered pypi index`() {
        val readIndex = "export UV_INDEX=\"\$(cat '${MaiBotScripts.PYPI_INDEX_FILE}')\""

        assertTrue(MaiBotScripts.syncDependencies().body.contains(readIndex))
        assertTrue(MaiBotScripts.installPython().body.contains(readIndex))
        // 启动时 uv run 也会同步依赖，必须和装依赖用同一个源
        assertTrue(MaiBotScripts.launch().body.contains(readIndex))
    }

    @Test
    fun `proxy preferred github order puts proxy before astral official fallback and custom pypi overrides first`() {
        val proxyFirst = listOf(
            GitHubMirror("自定义镜像", "https://fast.gh.example"),
            GitHubMirror("直连", "")
        )
        val uv = MaiBotScripts.installUv(proxyFirst).body
        assertTrue(uv.indexOf("maidroid_github_mirror_specs") < uv.indexOf("releases.astral.sh / github.com"))

        val python = MaiBotScripts.installPython(proxyFirst).body
        assertTrue(python.indexOf("UV_PYTHON_INSTALL_MIRROR") < python.lastIndexOf("via official source"))

        val syncCustom = MaiBotScripts.syncDependencies(
            listOf(PyPiMirror("自定义 PyPI 源", "https://custom.pypi.test/simple"))
        ).body
        assertTrue(syncCustom.contains("preferred_pypi_url='https://custom.pypi.test/simple'"))
    }
}
