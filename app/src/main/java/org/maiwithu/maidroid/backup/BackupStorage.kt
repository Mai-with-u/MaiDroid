package org.maiwithu.maidroid.backup

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

internal class SafAccessLostException(message: String) : Exception(message)

internal interface BackupStorageTarget {
    fun writeArchive(tempZip: File, fileName: String): BackupRecord
    fun listBackups(): List<BackupRecord>
    fun pruneOldBackups(keepCount: Int): Int
    fun deleteBackup(record: BackupRecord): Boolean
}

internal fun defaultExternalBackupDir(): File =
    File(Environment.getExternalStorageDirectory(), DEFAULT_EXTERNAL_RELATIVE_PATH)

internal fun isBackupArchiveFileName(name: String): Boolean =
    name.startsWith(BACKUP_FILE_PREFIX) && name.endsWith(BACKUP_FILE_SUFFIX)

/** 解析用户通过 SAF 选定目录的可读标签，供设置页展示当前备份位置。 */
internal fun resolveSafTreeLabel(context: Context, treeUri: Uri): String {
    val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
    if (!docId.isNullOrBlank()) {
        val colon = docId.indexOf(':')
        if (colon > 0) {
            val volume = docId.substring(0, colon)
            val rel = docId.substring(colon + 1).trimStart('/')
            val volumeName = if (volume == "primary") "内部存储" else volume
            return if (rel.isEmpty()) volumeName else "$volumeName/$rel"
        }
    }
    return treeUri.lastPathSegment?.substringAfterLast(':')?.ifBlank { null } ?: treeUri.toString()
}

/**
 * 写入本地文件系统的备份目标（默认对应 `/sdcard/MaiDroid/Backups`）：
 * 先写入隐藏 `.part` 临时文件再原子改名为正式 `.zip`，避免中途失败留下残缺包。
 */
internal class FileDirectoryBackupTarget(private val directory: File) : BackupStorageTarget {

    override fun writeArchive(tempZip: File, fileName: String): BackupRecord {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("无法创建备份目录：${directory.absolutePath}")
        }
        val partFile = File(directory, ".$fileName.part")
        val targetFile = File(directory, fileName)
        partFile.delete()
        try {
            FileInputStream(tempZip).use { input ->
                FileOutputStream(partFile).use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                    output.fd.sync()
                }
            }
            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (!partFile.renameTo(targetFile)) {
                throw IOException("重命名备份文件失败：${targetFile.absolutePath}")
            }
            return BackupRecord(
                fileName = targetFile.name,
                sizeBytes = targetFile.length(),
                lastModifiedMillis = targetFile.lastModified(),
                storageRef = targetFile.absolutePath
            )
        } catch (t: Throwable) {
            partFile.delete()
            throw t
        }
    }

    override fun listBackups(): List<BackupRecord> {
        if (!directory.isDirectory) return emptyList()
        return directory.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && isBackupArchiveFileName(it.name) }
            ?.map {
                BackupRecord(
                    fileName = it.name,
                    sizeBytes = it.length(),
                    lastModifiedMillis = it.lastModified(),
                    storageRef = it.absolutePath
                )
            }
            ?.sortedWith(compareByDescending<BackupRecord> { it.lastModifiedMillis }.thenByDescending { it.fileName })
            ?.toList()
            .orEmpty()
    }

    override fun pruneOldBackups(keepCount: Int): Int {
        val safeKeep = keepCount.coerceAtLeast(1)
        val records = listBackups()
        if (records.size <= safeKeep) return 0
        var removed = 0
        for (record in records.drop(safeKeep)) {
            if (deleteBackup(record)) removed++
        }
        return removed
    }

    override fun deleteBackup(record: BackupRecord): Boolean {
        val target = File(record.storageRef)
        val expectedParent = runCatching { directory.canonicalFile }.getOrElse { directory.absoluteFile }
        val actualParent = runCatching { target.canonicalFile.parentFile }.getOrNull()
        if (actualParent != expectedParent || !isBackupArchiveFileName(target.name)) {
            return false
        }
        return target.delete()
    }
}

/**
 * 写入用户通过 SAF `OPEN_DOCUMENT_TREE` 授权文件夹的备份目标（无需外部存储权限）：
 * 使用持久化 URI 权限列举、写入与裁剪 `maidroid-backup-*.zip`，绝不触碰目录下其他文件。
 */
internal class SafTreeBackupTarget(
    private val context: Context,
    private val treeUri: Uri
) : BackupStorageTarget {

    private val resolver = context.contentResolver

    private fun ensureWriteGrant(): Uri {
        val granted = resolver.persistedUriPermissions.any { permission ->
            permission.uri == treeUri && permission.isWritePermission
        }
        if (!granted) {
            throw SafAccessLostException("已选自定义备份文件夹的访问权限已失效，请重新选择文件夹")
        }
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
    }

    override fun writeArchive(tempZip: File, fileName: String): BackupRecord {
        val parentDocUri = ensureWriteGrant()
        val createdUri = DocumentsContract.createDocument(resolver, parentDocUri, "application/zip", fileName)
            ?: throw IOException("无法在所选文件夹中创建备份文件：$fileName")
        try {
            resolver.openOutputStream(createdUri, "w")?.use { output ->
                FileInputStream(tempZip).use { input ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            } ?: throw IOException("无法写入所选文件夹中的文件：$fileName")
            return BackupRecord(
                fileName = fileName,
                sizeBytes = tempZip.length(),
                lastModifiedMillis = System.currentTimeMillis(),
                storageRef = createdUri.toString()
            )
        } catch (t: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, createdUri) }
            throw t
        }
    }

    override fun listBackups(): List<BackupRecord> {
        ensureWriteGrant()
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val results = mutableListOf<BackupRecord>()
        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val mime = cursor.getString(mimeIdx)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                val name = cursor.getString(nameIdx) ?: continue
                if (!isBackupArchiveFileName(name)) continue
                val docId = cursor.getString(idIdx)
                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                results += BackupRecord(
                    fileName = name,
                    sizeBytes = if (cursor.isNull(sizeIdx)) 0L else cursor.getLong(sizeIdx),
                    lastModifiedMillis = if (cursor.isNull(modifiedIdx)) 0L else cursor.getLong(modifiedIdx),
                    storageRef = docUri.toString()
                )
            }
        }
        return results.sortedWith(
            compareByDescending<BackupRecord> { it.lastModifiedMillis }.thenByDescending { it.fileName }
        )
    }

    override fun pruneOldBackups(keepCount: Int): Int {
        val safeKeep = keepCount.coerceAtLeast(1)
        val records = listBackups()
        if (records.size <= safeKeep) return 0
        var removed = 0
        for (record in records.drop(safeKeep)) {
            if (deleteBackup(record)) removed++
        }
        return removed
    }

    override fun deleteBackup(record: BackupRecord): Boolean {
        ensureWriteGrant()
        if (!isBackupArchiveFileName(record.fileName)) return false
        return runCatching {
            DocumentsContract.deleteDocument(resolver, Uri.parse(record.storageRef))
        }.getOrDefault(false)
    }
}
