package org.maiwithu.maidroid.container

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
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

data class RuntimeInstallProgress(
    val title: String,
    val statusText: String,
    val progress: Float
)

class RuntimeAssetInstaller(
    context: Context,
    private val config: MaiBotContainerConfig = MaiBotContainerConfig.from(context)
) {
    private val appContext = context.applicationContext

    suspend fun installFromAssets(
        onProgress: suspend (RuntimeInstallProgress) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            requireAsset(MaiBotContainerConfig.ROOTFS_ASSET)
            requireAsset(MaiBotContainerConfig.BOOTSTRAP_ASSET)
            requireAsset(MaiBotContainerConfig.LIBANDROID_SHMEM_ASSET)
            requireAsset(MaiBotContainerConfig.LIBTALLOC_ASSET)
            requireAsset(MaiBotContainerConfig.PROOT_ASSET)

            if (
                config.rootfsMarker.isFile &&
                config.bootstrapMarker.isFile &&
                config.areTermuxProotDependenciesReady()
            ) {
                onProgress(
                    RuntimeInstallProgress(
                        title = "容器资源",
                        statusText = "本地容器资源已解包",
                        progress = 1f
                    )
                )
                return@runCatching
            }

            if (config.rootfsMarker.isFile && config.bootstrapMarker.isFile) {
                onProgress(
                    RuntimeInstallProgress(
                        title = "Termux bootstrap",
                        statusText = "正在补齐 proot 运行库依赖",
                        progress = 0.7f
                    )
                )
                extractProotDependencyPackages(config.termuxDir)
                config.ensureExecutableBits()
                onProgress(
                    RuntimeInstallProgress(
                        title = "容器资源",
                        statusText = "proot 运行库依赖已补齐",
                        progress = 1f
                    )
                )
                return@runCatching
            }

            deleteRecursively(config.stagingRootfsDir)
            deleteRecursively(config.stagingTermuxDir)
            config.stagingRootfsDir.mkdirs()
            config.stagingTermuxDir.mkdirs()

            onProgress(
                RuntimeInstallProgress(
                    title = "Debian rootfs",
                    statusText = "正在解包 debian-rootfs.tar.xz",
                    progress = 0.15f
                )
            )
            extractRootfs(config.stagingRootfsDir)
            File(config.stagingRootfsDir, config.rootfsMarker.name).writeText("ok")

            onProgress(
                RuntimeInstallProgress(
                    title = "Termux bootstrap",
                    statusText = "正在合并 bootstrap-aarch64.zip 和 proot 包",
                    progress = 0.65f
                )
            )
            extractBootstrap(config.stagingTermuxDir)
            extractProotDependencyPackages(config.stagingTermuxDir)
            extractTermuxDebPackage(config.stagingTermuxDir, MaiBotContainerConfig.PROOT_ASSET)
            File(config.stagingTermuxDir, config.bootstrapMarker.name).writeText("ok")

            onProgress(
                RuntimeInstallProgress(
                    title = "容器资源",
                    statusText = "正在切换到正式目录",
                    progress = 0.95f
                )
            )
            deleteRecursively(config.rootfsDir)
            deleteRecursively(config.termuxDir)
            check(config.stagingRootfsDir.renameTo(config.rootfsDir)) {
                "无法将 ${config.stagingRootfsDir.absolutePath} 切换为 ${config.rootfsDir.absolutePath}"
            }
            check(config.stagingTermuxDir.renameTo(config.termuxDir)) {
                "无法将 ${config.stagingTermuxDir.absolutePath} 切换为 ${config.termuxDir.absolutePath}"
            }
            config.installedMarker.writeText("ok")
            config.ensureWritableRuntimePaths()
            config.ensureExecutableBits()

            onProgress(
                RuntimeInstallProgress(
                    title = "容器资源",
                    statusText = "解包完成，proot runtime 已就绪",
                    progress = 1f
                )
            )
        }
    }

    private fun requireAsset(assetPath: String) {
        appContext.assets.open(assetPath).use { }
    }

    @Suppress("DEPRECATION")
    private fun extractRootfs(targetRoot: File) {
        appContext.assets.open(MaiBotContainerConfig.ROOTFS_ASSET).buffered().use { raw ->
            XZCompressorInputStream(raw).use { xz ->
                TarArchiveInputStream(xz).use { tar ->
                    while (true) {
                        val entry = tar.nextTarEntry ?: break
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
                runCatching {
                    if (target.exists()) target.delete()
                    Os.link(linkTarget.absolutePath, target.absolutePath)
                }.onFailure {
                    if (linkTarget.isFile) {
                        linkTarget.copyTo(target, overwrite = true)
                    }
                }
            }
            entry.isFile -> {
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { output ->
                    tar.copyTo(output)
                }
                chmod(target, entry.mode)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun extractProotDependencyPackages(targetRoot: File) {
        extractTermuxDebPackage(targetRoot, MaiBotContainerConfig.LIBANDROID_SHMEM_ASSET)
        extractTermuxDebPackage(targetRoot, MaiBotContainerConfig.LIBTALLOC_ASSET)
    }

    @Suppress("DEPRECATION")
    private fun extractTermuxDebPackage(targetRoot: File, assetPath: String) {
        appContext.assets.open(assetPath).buffered().use { raw ->
            ArArchiveInputStream(raw).use { ar ->
                while (true) {
                    val arEntry = ar.nextArEntry ?: break
                    if (arEntry.name != "data.tar.xz") continue

                    XZCompressorInputStream(ar).use { xz ->
                        TarArchiveInputStream(xz).use { tar ->
                            while (true) {
                                val entry = tar.nextTarEntry ?: break
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
        appContext.assets.open(MaiBotContainerConfig.BOOTSTRAP_ASSET).buffered().use { raw ->
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

    private fun replaceWithSymlink(link: File, target: String) {
        runCatching {
            if (link.exists()) {
                if (link.isDirectory) deleteRecursively(link) else link.delete()
            }
            Os.symlink(target, link.absolutePath)
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

    private fun deleteRecursively(file: File) {
        if (!file.exists()) return
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursively(it) }
        }
        file.delete()
    }
}
