package org.maiwithu.maidroid.maibot

import org.junit.Assert.assertTrue
import org.junit.Test

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
}
