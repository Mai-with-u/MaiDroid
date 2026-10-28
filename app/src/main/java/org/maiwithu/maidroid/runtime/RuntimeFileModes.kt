package org.maiwithu.maidroid.runtime

import android.system.Os
import java.io.File
import java.nio.file.Files

/**
 * 修复运行时文件的权限位。
 *
 * APK 安装、覆盖升级或解包时，proot 及其 loader 可能丢失可执行位；rootfs 里有些目录解包后不可写，
 * apt 和 git 会因此失败。启动任何容器命令前先调用 ensureExecutableBits，OOBE 安装系统依赖前调用 makeRootfsWritable。
 */
internal object RuntimeFileModes {
    private const val MODE_RWXR_XR_X = 0b111101101

    /** 给 APK 内置的 proot 原生库和 Termux bootstrap 里的 proot 工具补上可执行位，文件不存在时跳过。 */
    fun ensureExecutableBits(paths: RuntimePaths) {
        listOf(
            paths.prootExecutable,
            paths.prootLoader,
            paths.prootAndroidShmem,
            paths.prootTalloc,
            paths.termuxProotExecutable,
            paths.termuxProotLoader,
            paths.termuxProotLoader32,
            paths.termuxChroot
        ).forEach(::chmodExecutable)
    }

    /** 递归把 rootfs 里的文件改成属主可读写（目录和原本可执行的文件保留可执行位），符号链接不跟随。 */
    fun makeRootfsWritable(paths: RuntimePaths) {
        if (!paths.rootfsDir.isDirectory) return
        makeWritableRecursively(paths.rootfsDir)
    }

    private fun chmodExecutable(file: File) {
        if (!file.isFile) return
        runCatching { Os.chmod(file.absolutePath, MODE_RWXR_XR_X) }
        file.setReadable(true, false)
        file.setExecutable(true, false)
    }

    private fun makeWritableRecursively(file: File) {
        if (Files.isSymbolicLink(file.toPath())) return

        file.setReadable(true, true)
        file.setWritable(true, true)
        if (file.isDirectory || file.canExecute()) {
            file.setExecutable(true, true)
        }
        if (file.isDirectory) {
            file.listFiles()?.forEach(::makeWritableRecursively)
        }
    }
}
