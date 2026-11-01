package org.maiwithu.maidroid.runtime

import java.io.File

/**
 * 容器里 dpkg 的状态，只读文件，不跑命令。
 *
 * apt 装到一半被打断（闪退、强杀、没电关机）时，git、curl 的文件往往已经解包在位，只看文件会被当成装好了，
 * 可 CA 证书这些包的配置脚本还没跑完。2026-11 真机实测：在“Setting up ca-certificates”时强杀，
 * 重开后直接判定容器就绪进了下一步，半装状态一直拖到下次用 apt 才暴露。
 */
internal object DpkgState {
    // dpkg --audit 会报的几种没装完的状态：解包了没配置、配置到一半、解包到一半。
    private val UNFINISHED_STATES = setOf("unpacked", "half-configured", "half-installed")

    /**
     * 上次 dpkg 是不是没做完：updates 目录里还有没并进 status 的记录（dpkg 每做一步先记在这里），
     * 或 status 里还有没装完的包。是的话要先跑一遍 dpkg --configure -a。
     */
    fun isInterrupted(rootfsDir: File): Boolean {
        val dpkgDir = File(rootfsDir, "var/lib/dpkg")
        if (File(dpkgDir, "updates").list()?.isNotEmpty() == true) return true
        val status = File(dpkgDir, "status").takeIf { it.isFile } ?: return false
        return status.useLines { lines ->
            lines.any { line ->
                // 形如“Status: install ok half-configured”，最后一个词是包的状态
                line.startsWith("Status: ") && line.substringAfterLast(' ') in UNFINISHED_STATES
            }
        }
    }
}
