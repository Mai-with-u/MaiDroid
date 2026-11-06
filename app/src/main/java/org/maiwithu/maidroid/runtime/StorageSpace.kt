package org.maiwithu.maidroid.runtime

import android.os.StatFs
import org.maiwithu.maidroid.appupdate.formatBytes
import org.maiwithu.maidroid.diagnostics.Failure
import org.maiwithu.maidroid.diagnostics.FailureCause
import org.maiwithu.maidroid.diagnostics.FailureStep
import java.io.File
import java.nio.file.Files

/**
 * 下载、解包这类一次要写下几百 MB 到几 GB 的步骤开工前，先看一眼手机还剩多少空间。
 * 明摆着装不下时直接说清楚“要多少、剩多少”（DISK-FULL，带清理空间按钮），不用装到一半才以
 * No space left on device 失败，还在 dpkg、git、uv 里留下一堆半成品。
 * 门槛按实测用量取得偏低：只拦一定装不下的，差一点的照常开工，真不够时再由失败输出认出 DISK-FULL。
 */
internal object StorageSpace {
    private const val MB = 1024L * 1024
    private const val GB = 1024 * MB

    /** 解包 APK 里的容器资源：rootfs 解开约 460 MB，bootstrap 约 70 MB（2026-11 的资源包）。 */
    const val CONTAINER_UNPACK_BYTES = 700 * MB

    /** apt 装 git、curl 和证书：下载加解开约 60 MB。 */
    const val APT_INSTALL_BYTES = 150 * MB

    /** 克隆 MaiBot 源码：约 170 MB。 */
    const val MAIBOT_CLONE_BYTES = 250 * MB

    /** uv、Python 和依赖包：调试机实测 Python 约 190 MB、.venv 约 800 MB、uv 下载缓存约 1.5 GB。 */
    const val MAIBOT_DEPENDENCIES_BYTES = 2 * GB

    /** NapCat 和 LinuxQQ：调试机实测 QQ 约 650 MB、NapCat 约 110 MB，再加安装包下载。 */
    const val NAPCAT_INSTALL_BYTES = 1 * GB

    /** SnowLuma 和 LinuxQQ：QQ 约 650 MB（和 NapCat 共用，已是这一版就不再装），SnowLuma 发行包解开后约 150 MB，再加两个安装包下载。 */
    const val SNOWLUMA_INSTALL_BYTES = 1 * GB

    /** 在线更新 MaiBot：新源码加上可能新增的依赖包。 */
    const val MAIBOT_UPDATE_BYTES = 500 * MB

    /** 一个目录（或文件）里所有文件的总大小，不跟随符号链接；不存在时是 0。文件多时很慢，放在 IO 线程。 */
    fun sizeOf(file: File): Long =
        runCatching {
            file.walkTopDown()
                .onEnter { !Files.isSymbolicLink(it.toPath()) }
                .filter { !Files.isSymbolicLink(it.toPath()) && it.isFile }
                .sumOf { it.length() }
        }.getOrDefault(0L)

    /** dir 所在分区还能用的字节数；读不到时按“空间充足”处理，不因为读不到就拦。 */
    fun availableBytes(dir: File): Long =
        runCatching { StatFs(dir.absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE)

    /**
     * 开工前看空间：dir 所在分区剩的不到 requiredBytes 时返回“这一步/DISK-FULL”的失败，够用返回 null。
     * what 是给用户看的“这一步要做什么”，例如“安装 Python 依赖”。
     */
    fun shortageFailure(step: FailureStep, dir: File, requiredBytes: Long, what: String): Failure? {
        val free = availableBytes(dir)
        if (free >= requiredBytes) return null
        return Failure(
            step,
            "${what}大约需要 ${formatBytes(requiredBytes)} 空间，手机现在只剩 ${formatBytes(free)}",
            FailureCause.DiskFull
        )
    }
}
