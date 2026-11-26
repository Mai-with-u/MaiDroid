package org.maiwithu.maidroid.backup

import android.database.sqlite.SQLiteDatabase
import android.os.Build
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.time.Instant
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Serializable
internal data class BackupManifest(
    val format: String = "maidroid-backup",
    val formatVersion: Int = 1,
    val createdAtIso: String,
    val appVersion: String,
    val maiBotVersion: String?,
    val trigger: String,
    val components: List<String>,
    val fileCount: Int,
    val skippedFiles: Int,
    val sqliteSnapshots: Int
)

internal data class ArchiveBuildResult(
    val zipFile: File,
    val fileCount: Int,
    val skippedFiles: Int,
    val sqliteSnapshots: Int
)

/**
 * 将规划好的组件文件写入暂存 zip 压缩包：
 * 对 SQLite 数据库优先在运行中生成一致性快照（VACUUM INTO），不可用时退回主库连带 WAL/Journal 打包。
 */
internal class BackupArchiver(
    private val sqliteSnapshotter: (source: File, target: File) -> Boolean = ::snapshotSqliteWithVacuumInto
) {
    private companion object {
        val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        val PRE_COMPRESSED_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "webp", "gif", "zip", "gz", "xz", "bz2", "7z", "mp3", "ogg", "mp4", "m4a"
        )
        const val COPY_BUFFER_SIZE = 64 * 1024
        val manifestJson = Json { prettyPrint = true }
    }

    fun buildArchive(
        entries: List<PlannedBackupEntry>,
        outputZip: File,
        snapshotDir: File,
        appVersion: String,
        maiBotVersion: String?,
        trigger: BackupTrigger,
        components: Set<BackupComponent>,
        createdAtMillis: Long = System.currentTimeMillis(),
        onProgress: (processed: Int, total: Int) -> Unit = { _, _ -> }
    ): ArchiveBuildResult {
        outputZip.parentFile?.mkdirs()
        snapshotDir.mkdirs()

        var fileCount = 0
        var skippedFiles = 0
        var sqliteSnapshots = 0
        val writtenArchivePaths = HashSet<String>()
        val total = entries.size

        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(outputZip))).use { zipOut ->
                entries.forEachIndexed { index, entry ->
                    onProgress(index + 1, total)
                    val source = entry.sourceFile
                    if (!source.isFile) {
                        skippedFiles++
                        return@forEachIndexed
                    }

                    if (isSqliteDatabaseFile(source)) {
                        val snapshotFile = File(snapshotDir, "sqlite-$index.db")
                        val snapshotOk = runCatching { sqliteSnapshotter(source, snapshotFile) }.getOrDefault(false)
                        if (snapshotOk && snapshotFile.isFile && snapshotFile.length() > 0L) {
                            try {
                                if (writeZipEntry(zipOut, snapshotFile, entry.archivePath, writtenArchivePaths)) {
                                    fileCount++
                                    sqliteSnapshots++
                                }
                            } finally {
                                snapshotFile.delete()
                            }
                            return@forEachIndexed
                        } else {
                            snapshotFile.delete()
                        }

                        // 在线快照不可用时回退打包主库，并补齐同目录 WAL 与 Journal 旁路日志，防止近期写入丢失。
                        if (writeFileOrCountSkipped(zipOut, source, entry.archivePath, writtenArchivePaths)) {
                            fileCount++
                        } else {
                            skippedFiles++
                        }
                        for (suffix in listOf("-wal", "-journal")) {
                            val sidecar = File(source.parentFile, "${source.name}$suffix")
                            if (sidecar.isFile && !isSymlink(sidecar)) {
                                val sidecarPath = "${entry.archivePath}$suffix"
                                if (writeFileOrCountSkipped(zipOut, sidecar, sidecarPath, writtenArchivePaths)) {
                                    fileCount++
                                } else {
                                    skippedFiles++
                                }
                            }
                        }
                        return@forEachIndexed
                    }

                    if (writeFileOrCountSkipped(zipOut, source, entry.archivePath, writtenArchivePaths)) {
                        fileCount++
                    } else {
                        skippedFiles++
                    }
                }

                val manifest = BackupManifest(
                    createdAtIso = Instant.ofEpochMilli(createdAtMillis).toString(),
                    appVersion = appVersion,
                    maiBotVersion = maiBotVersion,
                    trigger = trigger.name.lowercase(Locale.US),
                    components = components.map { it.name }.sorted(),
                    fileCount = fileCount,
                    skippedFiles = skippedFiles,
                    sqliteSnapshots = sqliteSnapshots
                )
                val manifestBytes = manifestJson.encodeToString(BackupManifest.serializer(), manifest)
                    .toByteArray(Charsets.UTF_8)
                val manifestEntry = ZipEntry("manifest.json").apply {
                    time = createdAtMillis
                    method = ZipEntry.DEFLATED
                }
                zipOut.putNextEntry(manifestEntry)
                zipOut.write(manifestBytes)
                zipOut.closeEntry()
            }
        } catch (t: Throwable) {
            outputZip.delete()
            throw t
        }

        return ArchiveBuildResult(
            zipFile = outputZip,
            fileCount = fileCount,
            skippedFiles = skippedFiles,
            sqliteSnapshots = sqliteSnapshots
        )
    }

    private fun writeFileOrCountSkipped(
        zipOut: ZipOutputStream,
        source: File,
        archivePath: String,
        writtenPaths: MutableSet<String>
    ): Boolean = runCatching {
        writeZipEntry(zipOut, source, archivePath, writtenPaths)
    }.getOrDefault(false)

    private fun writeZipEntry(
        zipOut: ZipOutputStream,
        source: File,
        archivePath: String,
        writtenPaths: MutableSet<String>
    ): Boolean {
        if (!source.isFile || !writtenPaths.add(archivePath)) return false
        val ext = source.extension.lowercase(Locale.US)
        val entry = ZipEntry(archivePath).apply {
            time = source.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
        }
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        if (ext in PRE_COMPRESSED_EXTENSIONS) {
            val (size, crcValue) = measureFileSizeAndCrc(source, buffer)
            entry.method = ZipEntry.STORED
            entry.size = size
            entry.compressedSize = size
            entry.crc = crcValue
        } else {
            entry.method = ZipEntry.DEFLATED
        }
        zipOut.putNextEntry(entry)
        FileInputStream(source).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                zipOut.write(buffer, 0, read)
            }
        }
        zipOut.closeEntry()
        return true
    }

    private fun measureFileSizeAndCrc(file: File, buffer: ByteArray): Pair<Long, Long> {
        val crc = CRC32()
        var total = 0L
        FileInputStream(file).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                crc.update(buffer, 0, read)
                total += read
            }
        }
        return total to crc.value
    }

    private fun isSqliteDatabaseFile(file: File): Boolean {
        if (!file.isFile || file.length() < SQLITE_MAGIC.size) return false
        val header = ByteArray(SQLITE_MAGIC.size)
        val read = runCatching {
            FileInputStream(file).use { it.read(header) }
        }.getOrDefault(-1)
        return read == SQLITE_MAGIC.size && header.contentEquals(SQLITE_MAGIC)
    }

    private fun isSymlink(file: File): Boolean =
        runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)
}

/**
 * Android 11+ 使用 SQLite 原生 VACUUM INTO 生成只读一致性快照，不打断正在运行的 MaiBot 进程；
 * 必须携带 NO_LOCALIZED_COLLATORS，避免 Android SQLite 自动往原库写入 android_metadata 表。
 */
internal fun snapshotSqliteWithVacuumInto(source: File, target: File): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
    if (!source.isFile) return false
    target.parentFile?.mkdirs()
    target.delete()
    val escapedPath = target.absolutePath.replace("'", "''")
    return try {
        SQLiteDatabase.openDatabase(
            source.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        ).use { db ->
            db.rawQuery("PRAGMA busy_timeout = 5000", null)?.close()
            db.execSQL("VACUUM INTO '$escapedPath'")
        }
        target.isFile && target.length() > 0L
    } catch (_: Throwable) {
        target.delete()
        false
    }
}
