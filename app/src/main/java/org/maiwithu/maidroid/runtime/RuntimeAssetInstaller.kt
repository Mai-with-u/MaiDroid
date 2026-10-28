package org.maiwithu.maidroid.runtime

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/** APK 里 assets/runtime 下随包分发的容器资源，由 RuntimeAssetInstaller 在 OOBE 时解包。 */
internal object RuntimeAssets {
    const val ROOTFS = "runtime/debian-rootfs.tar.xz"
    const val BOOTSTRAP = "runtime/bootstrap-aarch64.zip"
    const val PROOT_DEB = "runtime/proot_5.1.107.82_aarch64.deb"
    const val LIBANDROID_SHMEM_DEB = "runtime/libandroid-shmem_0.7_aarch64.deb"
    const val LIBTALLOC_DEB = "runtime/libtalloc_2.4.3_aarch64.deb"
}

/** 解包进行到哪一部分：OOBE 据此决定哪几张容器卡片显示为进行中。 */
internal enum class RuntimeInstallStage {
    DebianRootfs,
    TermuxBootstrap,

    /** 整体状态（开始、切换目录、完成），三张卡片一起显示。 */
    WholeRuntime
}

/** 解包过程中的一次进度汇报：进行到哪一部分，以及显示在 OOBE 页脚的说明。 */
internal data class RuntimeInstallProgress(
    val stage: RuntimeInstallStage,
    val statusText: String
)

/**
 * 把 APK 里 assets/runtime 下的容器资源解包到应用私有目录：
 * Debian rootfs（tar.xz）-> Termux bootstrap（zip，含 SYMLINKS.txt 描述的符号链接）-> proot 及其依赖（deb）。
 *
 * 先解到 *.tmp 临时目录，全部成功才删旧目录、改名为正式目录，中途被杀不会留下半成品；
 * rootfs 和 bootstrap 都已解好、只缺 proot 依赖库时（旧版本升级上来）只补依赖库。
 */
internal class RuntimeAssetInstaller(
    context: Context,
    private val paths: RuntimePaths
) {
    private val appContext = context.applicationContext

    suspend fun installFromAssets(
        onProgress: suspend (RuntimeInstallProgress) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            requireAsset(RuntimeAssets.ROOTFS)
            requireAsset(RuntimeAssets.BOOTSTRAP)
            requireAsset(RuntimeAssets.LIBANDROID_SHMEM_DEB)
            requireAsset(RuntimeAssets.LIBTALLOC_DEB)
            requireAsset(RuntimeAssets.PROOT_DEB)

            if (
                paths.rootfsMarker.isFile &&
                paths.bootstrapMarker.isFile &&
                RuntimeReadiness(paths).areTermuxProotDependenciesReady()
            ) {
                onProgress(
                    RuntimeInstallProgress(
                        stage = RuntimeInstallStage.WholeRuntime,
                        statusText = "本地容器资源已解包"
                    )
                )
                return@runCatching
            }

            if (paths.rootfsMarker.isFile && paths.bootstrapMarker.isFile) {
                onProgress(
                    RuntimeInstallProgress(
                        stage = RuntimeInstallStage.TermuxBootstrap,
                        statusText = "正在补齐 proot 运行库依赖"
                    )
                )
                extractProotDependencyPackages(paths.termuxDir)
                RuntimeFileModes.ensureExecutableBits(paths)
                onProgress(
                    RuntimeInstallProgress(
                        stage = RuntimeInstallStage.WholeRuntime,
                        statusText = "proot 运行库依赖已补齐"
                    )
                )
                return@runCatching
            }

            deleteRecursively(paths.stagingRootfsDir)
            deleteRecursively(paths.stagingTermuxDir)
            paths.stagingRootfsDir.mkdirs()
            paths.stagingTermuxDir.mkdirs()

            onProgress(
                RuntimeInstallProgress(
                    stage = RuntimeInstallStage.DebianRootfs,
                    statusText = "正在解包 debian-rootfs.tar.xz"
                )
            )
            extractRootfs(paths.stagingRootfsDir)
            File(paths.stagingRootfsDir, paths.rootfsMarker.name).writeText("ok")

            onProgress(
                RuntimeInstallProgress(
                    stage = RuntimeInstallStage.TermuxBootstrap,
                    statusText = "正在合并 bootstrap-aarch64.zip 和 proot 包"
                )
            )
            extractBootstrap(paths.stagingTermuxDir)
            extractProotDependencyPackages(paths.stagingTermuxDir)
            extractTermuxDebPackage(paths.stagingTermuxDir, RuntimeAssets.PROOT_DEB)
            File(paths.stagingTermuxDir, paths.bootstrapMarker.name).writeText("ok")

            onProgress(
                RuntimeInstallProgress(
                    stage = RuntimeInstallStage.WholeRuntime,
                    statusText = "正在切换到正式目录"
                )
            )
            deleteRecursively(paths.rootfsDir)
            deleteRecursively(paths.termuxDir)
            check(paths.stagingRootfsDir.renameTo(paths.rootfsDir)) {
                "无法将 ${paths.stagingRootfsDir.absolutePath} 切换为 ${paths.rootfsDir.absolutePath}"
            }
            check(paths.stagingTermuxDir.renameTo(paths.termuxDir)) {
                "无法将 ${paths.stagingTermuxDir.absolutePath} 切换为 ${paths.termuxDir.absolutePath}"
            }
            RuntimeFileModes.makeRootfsWritable(paths)
            RuntimeFileModes.ensureExecutableBits(paths)

            onProgress(
                RuntimeInstallProgress(
                    stage = RuntimeInstallStage.WholeRuntime,
                    statusText = "解包完成，proot runtime 已就绪"
                )
            )
        }
    }

    private fun requireAsset(assetPath: String) {
        appContext.assets.open(assetPath).use { }
    }

    private fun extractRootfs(targetRoot: File) {
        appContext.assets.open(RuntimeAssets.ROOTFS).buffered().use { raw ->
            XZCompressorInputStream(raw).use { xz ->
                TarArchiveInputStream(xz).use { tar ->
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        extractTarEntry(targetRoot, entry, tar)
                    }
                }
            }
        }
    }

    private fun extractTarEntry(
        targetRoot: File,
        entry: TarArchiveEntry,
        tar: TarArchiveInputStream,
        entryName: String = entry.name
    ) {
        val target = safeTarget(targetRoot, entryName)
        when {
            entry.isDirectory -> target.mkdirs()
            entry.isSymbolicLink -> {
                target.parentFile?.mkdirs()
                replaceWithSymlink(target, entry.linkName)
            }
            entry.isLink -> {
                val linkTarget = safeTarget(
                    targetRoot,
                    stripTermuxPrefix(entry.linkName) ?: entry.linkName
                )
                target.parentFile?.mkdirs()
                deleteExisting(target)
                // 多数机型不允许在应用私有目录建硬链接，建不了就复制一份。tar 里的硬链接总是指向前面已解出的普通文件，
                // 那个文件也不在时说明包已损坏，直接报错，不再悄悄少一个文件。
                val linked = runCatching { Os.link(linkTarget.absolutePath, target.absolutePath) }.isSuccess
                if (!linked) {
                    check(linkTarget.isFile) { "解包失败：硬链接 $entryName 指向的 ${entry.linkName} 不存在" }
                    linkTarget.copyTo(target, overwrite = true)
                }
            }
            entry.isFile -> {
                target.parentFile?.mkdirs()
                // 先删掉同名的旧链接：补装 proot 依赖库时是解到已有目录里，直接写会顺着旧链接改到别的文件。
                deleteExisting(target)
                FileOutputStream(target).use { output ->
                    tar.copyTo(output)
                }
                chmod(target, entry.mode)
            }
        }
    }

    private fun extractProotDependencyPackages(targetRoot: File) {
        extractTermuxDebPackage(targetRoot, RuntimeAssets.LIBANDROID_SHMEM_DEB)
        extractTermuxDebPackage(targetRoot, RuntimeAssets.LIBTALLOC_DEB)
    }

    private fun extractTermuxDebPackage(targetRoot: File, assetPath: String) {
        appContext.assets.open(assetPath).buffered().use { raw ->
            ArArchiveInputStream(raw).use { ar ->
                while (true) {
                    val arEntry = ar.nextEntry ?: break
                    if (arEntry.name != "data.tar.xz") continue

                    XZCompressorInputStream(ar).use { xz ->
                        TarArchiveInputStream(xz).use { tar ->
                            while (true) {
                                val entry = tar.nextEntry ?: break
                                val entryName = stripTermuxPrefix(entry.name) ?: continue
                                extractTarEntry(targetRoot, entry, tar, entryName)
                            }
                        }
                    }
                    break
                }
            }
        }
    }

    private fun stripTermuxPrefix(name: String): String? {
        val cleanName = name
            .replace('\\', '/')
            .removePrefix("./")
            .trimStart('/')
        val prefix = "data/data/com.termux/files/usr/"
        if (!cleanName.startsWith(prefix)) return null
        return cleanName.removePrefix(prefix).takeIf { it.isNotBlank() }
    }

    private fun extractBootstrap(targetRoot: File) {
        appContext.assets.open(RuntimeAssets.BOOTSTRAP).buffered().use { raw ->
            ZipInputStream(raw).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == "SYMLINKS.txt") {
                        applyBootstrapSymlinks(targetRoot, zip.readBytes().decodeToString())
                    } else {
                        extractZipEntry(targetRoot, entry, zip)
                    }
                    zip.closeEntry()
                }
            }
        }
    }

    private fun extractZipEntry(
        targetRoot: File,
        entry: ZipEntry,
        zip: ZipInputStream
    ) {
        val target = safeTarget(targetRoot, entry.name)
        if (entry.isDirectory) {
            target.mkdirs()
            return
        }

        target.parentFile?.mkdirs()
        FileOutputStream(target).use { output ->
            zip.copyTo(output)
        }

        if (entry.name.startsWith("bin/") || entry.name.startsWith("libexec/")) {
            chmod(target, 0b111101101)
        }
    }

    private fun applyBootstrapSymlinks(targetRoot: File, symlinkText: String) {
        symlinkText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                val parts = line.split("←", limit = 2)
                if (parts.size != 2) return@forEach

                val targetPath = parts[0]
                val linkPath = parts[1]
                if (targetPath.startsWith("/")) return@forEach

                val link = safeTarget(targetRoot, linkPath)
                link.parentFile?.mkdirs()
                replaceWithSymlink(link, targetPath)
            }
    }

    private fun safeTarget(root: File, rawName: String): File {
        val cleanName = rawName
            .replace('\\', '/')
            .removePrefix("./")
            .trimStart('/')
        val rootPath = root.toPath().toAbsolutePath().normalize()
        val targetPath = rootPath.resolve(cleanName).normalize()
        require(targetPath.startsWith(rootPath)) {
            "压缩包路径越界: $rawName"
        }
        return targetPath.toFile()
    }

    // 建不了符号链接直接报错。以前静默忽略，少了链接要到 proot 或容器命令出错时才以别的样子暴露出来。
    private fun replaceWithSymlink(link: File, target: String) {
        deleteExisting(link)
        try {
            Os.symlink(target, link.absolutePath)
        } catch (e: ErrnoException) {
            throw IllegalStateException("解包失败：无法创建符号链接 ${link.absolutePath} -> $target（${e.message}）", e)
        }
    }

    // 删掉某个路径上已有的东西：符号链接只删链接本身（包括 exists() 看不到的断链），目录整个删掉。
    private fun deleteExisting(file: File) {
        when {
            Files.isSymbolicLink(file.toPath()) -> file.delete()
            file.isDirectory -> deleteRecursively(file)
            file.exists() -> file.delete()
        }
    }

    private fun chmod(file: File, mode: Int) {
        try {
            Os.chmod(file.absolutePath, mode)
        } catch (_: ErrnoException) {
            file.setReadable(mode and 0b100000000 != 0, true)
            file.setWritable(mode and 0b010000000 != 0, true)
            file.setExecutable(mode and 0b001000000 != 0, true)
        }
    }

    // 不跟随符号链接。rootfs 里有 /root/storage/shared -> /sdcard 这类绝对路径链接，在宿主上会解析到
    // 手机的共享存储；跟进去删会把用户的照片、下载等文件一起删掉。遇到链接只删链接本身。
    private fun deleteRecursively(file: File) {
        if (Files.isSymbolicLink(file.toPath())) {
            file.delete()
            return
        }
        if (!file.exists()) return
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursively(it) }
        }
        file.delete()
    }
}
