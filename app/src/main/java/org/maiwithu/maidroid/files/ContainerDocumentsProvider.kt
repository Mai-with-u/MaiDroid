package org.maiwithu.maidroid.files

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import android.webkit.MimeTypeMap
import org.maiwithu.maidroid.BuildConfig
import org.maiwithu.maidroid.R
import org.maiwithu.maidroid.runtime.RuntimePaths
import java.io.File
import java.io.FileNotFoundException

/**
 * 把容器（Debian rootfs）里的文件经 SAF 共享给系统“文件”应用、MT 管理器等文件管理器：
 * 容器在应用私有目录里，不 root 看不到；有了它，用户能直接浏览、打开、编辑、新建、删除容器里的文件。
 *
 * 提供两个根，共用一套 documentId（格式见 ContainerDocuments.kt），同一个文件只有一个 id：
 * - “MaiBot”：容器内 /opt/MaiBot。看日志、查插件报错、改配置都在这里，所以单独列成显眼入口；
 * - “MaiDroid 容器”：整个容器根目录，NapCat（/root/napcat）等其它文件从这里找。
 *
 * 安全边界是容器根目录：documentId 只认规范的相对路径，符号链接按容器语义解析（见 resolveContainerSymlinks），
 * 结果不会落到容器以外。/root/storage/shared -> /sdcard 这类链接在这里解析成容器里不存在的文件，
 * 不会经由本应用把手机共享存储或应用的其它私有文件暴露出去；删除时也不跟随链接。
 */
class ContainerDocumentsProvider : DocumentsProvider() {

    companion object {
        val AUTHORITY = "${BuildConfig.APPLICATION_ID}.documents"
        const val ROOT_MAIBOT = "maibot"
        const val ROOT_CONTAINER = "container"

        // 搜索最多返回的条数和最多检查的目录项数：容器里光 Python 依赖就有上万个文件，不设上限会让文件应用一直转圈。
        private const val SEARCH_RESULT_LIMIT = 50
        private const val SEARCH_VISIT_LIMIT = 20_000

        private const val CONTAINER_ROOT_TITLE = "MaiDroid 容器"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_AVAILABLE_BYTES
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS
        )

        /** MaiBot 目录相对容器根目录的路径，从 RuntimePaths 推出来，不另写一遍。 */
        internal fun maiBotRelativePath(paths: RuntimePaths): String =
            paths.maiBotDir.relativeTo(paths.rootfsDir).invariantSeparatorsPath

        internal fun maiBotDocumentId(paths: RuntimePaths): String =
            containerDocumentId(maiBotRelativePath(paths))
    }

    // 不从 appDependencies 取：系统先创建 ContentProvider、后调 Application.onCreate，provider 一发布
    // 就可能在 binder 线程上收到查询，那时 AppDependencies 可能还没建好。RuntimePaths 只描述文件位置，
    // 自己建一份没有副作用；用 lazy 推迟到第一次查询，应用启动时不碰磁盘。
    private val paths by lazy { RuntimePaths.from(checkNotNull(context)) }
    private val rootDir by lazy { paths.rootfsDir }
    private val maiBotPath by lazy { maiBotRelativePath(paths) }

    /**
     * 容器根目录在宿主上的几种写法，解析 --link2symlink 留下的绝对路径链接时用：filesDir 给的路径、真实路径，
     * 以及 /data/data/<包名>/... ——真机上这些链接的目标就是这种写法。旧写法只在确实指向同一目录时才认，
     * 多用户（工作资料）下 /data/data 指向的是主用户的目录，不能混用。
     */
    private val hostRootAliases: List<String> by lazy {
        val context = checkNotNull(context)
        val canonicalRoot = rootDir.canonicalPath
        val legacyRoot = runCatching {
            val dataDir = File(context.applicationInfo.dataDir)
            File(File(Environment.getDataDirectory(), "data/${context.packageName}"), rootDir.relativeTo(dataDir).path)
        }.getOrNull()?.takeIf { it.canonicalPath == canonicalRoot }
        listOfNotNull(rootDir.absolutePath, canonicalRoot, legacyRoot?.absolutePath).distinct()
    }

    override fun onCreate(): Boolean = true

    // ── 根 ─────────────────────────────────────────────────────────────

    // 容器没解包完时不列任何根；MaiBot 还没克隆下来时只列容器根。
    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        if (!paths.rootfsMarker.isFile) return cursor
        if (paths.maiBotDir.isDirectory) {
            addRoot(cursor, ROOT_MAIBOT, maiBotPath, title = "MaiBot", summary = "MaiDroid · 日志、插件、配置")
        }
        addRoot(cursor, ROOT_CONTAINER, "", title = CONTAINER_ROOT_TITLE, summary = "MaiDroid · 容器根目录")
        return cursor
    }

    private fun addRoot(cursor: MatrixCursor, rootId: String, path: String, title: String, summary: String) {
        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, rootId)
            add(Root.COLUMN_DOCUMENT_ID, containerDocumentId(path))
            add(Root.COLUMN_TITLE, title)
            add(Root.COLUMN_SUMMARY, summary)
            // FLAG_SUPPORTS_IS_CHILD 让 MT 管理器等应用能用“选择目录”（ACTION_OPEN_DOCUMENT_TREE）长期授权访问
            add(
                Root.COLUMN_FLAGS,
                Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_SEARCH or
                    Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_LOCAL_ONLY
            )
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(Root.COLUMN_MIME_TYPES, "*/*")
            add(Root.COLUMN_AVAILABLE_BYTES, rootDir.freeSpace)
        }
    }

    // ── 查询 ───────────────────────────────────────────────────────────

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val path = requirePath(documentId)
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        if (path.isEmpty()) {
            includeRootDocument(cursor)
        } else {
            val parentPath = checkNotNull(parentRelativePath(path))
            val resolvedParent = requireResolved(parentPath)
            if (!includeEntry(cursor, path, resolvedParent, hostFile(resolvedParent).canWrite())) {
                throw FileNotFoundException("找不到 $documentId")
            }
        }
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val parentPath = requirePath(parentDocumentId)
        val resolvedParent = requireResolved(parentPath)
        val dir = hostFile(resolvedParent)
        val names = dir.list() ?: throw FileNotFoundException("不是目录或无法读取：$parentDocumentId")
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val parentWritable = dir.canWrite()
        for (name in names) {
            if (isProotInternalEntry(name)) continue
            // 列目录和读属性之间被删掉的项直接跳过
            includeEntry(cursor, childRelativePath(parentPath, name), resolvedParent, parentWritable)
        }
        // 新建、删除、改名后通知这个地址，文件应用会自动刷新当前目录
        cursor.setNotificationUri(
            checkNotNull(context).contentResolver,
            DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentDocumentId)
        )
        return cursor
    }

    /**
     * 按文件名搜索：从根对应的目录开始广度优先，浅层的日志、插件先出来。不进符号链接指向的目录
     * （避免链接成环，也避免从 MaiBot 目录经 .venv 里的链接搜遍整个容器），也不搜 proot 的替身文件。
     */
    override fun querySearchDocuments(rootId: String, query: String, projection: Array<out String>?): Cursor {
        val startPath = when (rootId) {
            ROOT_MAIBOT -> maiBotPath
            ROOT_CONTAINER -> ""
            else -> throw FileNotFoundException("没有这个根：$rootId")
        }
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return cursor

        // 队列里是（字面路径，解析后的路径）
        val queue = ArrayDeque(listOf(startPath to requireResolved(startPath)))
        var visited = 0
        while (queue.isNotEmpty() && visited < SEARCH_VISIT_LIMIT && cursor.count < SEARCH_RESULT_LIMIT) {
            val (dirPath, resolvedDir) = queue.removeFirst()
            val dir = hostFile(resolvedDir)
            val names = dir.list() ?: continue
            val dirWritable = dir.canWrite()
            for (name in names) {
                if (isProotInternalEntry(name)) continue
                if (++visited >= SEARCH_VISIT_LIMIT || cursor.count >= SEARCH_RESULT_LIMIT) break
                val childPath = childRelativePath(dirPath, name)
                if (name.lowercase().contains(needle)) {
                    includeEntry(cursor, childPath, resolvedDir, dirWritable)
                }
                val childResolved = childRelativePath(resolvedDir, name)
                val stat = lstatOrNull(hostFile(childResolved)) ?: continue
                if (OsConstants.S_ISDIR(stat.st_mode)) {
                    queue.addLast(childPath to childResolved)
                }
            }
        }
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = containerRelativePath(parentDocumentId) ?: return false
        val child = containerRelativePath(documentId) ?: return false
        return isDescendantPath(parent, child)
    }

    // ── 读写 ───────────────────────────────────────────────────────────

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val file = hostFile(requireResolved(requirePath(documentId)))
        if (file.isDirectory) throw FileNotFoundException("目录不能按文件打开：$documentId")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    // 只给图片标了缩略图，直接把原图交给文件应用自己缩放
    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point?,
        signal: CancellationSignal?
    ): AssetFileDescriptor {
        val file = hostFile(requireResolved(requirePath(documentId)))
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(descriptor, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        require(isValidDisplayName(displayName)) { "文件名不能为空，也不能包含 /：$displayName" }
        val parentPath = requirePath(parentDocumentId)
        val dir = hostFile(requireResolved(parentPath))
        if (!dir.isDirectory) throw FileNotFoundException("不是目录：$parentDocumentId")
        val isDirectory = mimeType == Document.MIME_TYPE_DIR
        // 同名时像系统文件应用一样改名为 “名字 (1)”，不覆盖已有文件
        val name = uniqueChildName(displayName, isDirectory) { entryExists(File(dir, it)) }
        val target = File(dir, name)
        val created = if (isDirectory) target.mkdir() else target.createNewFile()
        if (!created) throw FileNotFoundException("无法新建 $name")
        notifyChildrenChanged(parentPath)
        return containerDocumentId(childRelativePath(parentPath, name))
    }

    // 删除、改名只作用于目录项本身：解析父目录，最后一段不跟随，删符号链接只删链接
    override fun deleteDocument(documentId: String) {
        val path = requirePath(documentId)
        requireRemovable(path)
        val parentPath = checkNotNull(parentRelativePath(path))
        val entry = File(hostFile(requireResolved(parentPath)), path.substringAfterLast('/'))
        if (!entryExists(entry)) throw FileNotFoundException("找不到 $documentId")
        deleteWithoutFollowingLinks(entry)
        notifyChildrenChanged(parentPath)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        require(isValidDisplayName(displayName)) { "文件名不能为空，也不能包含 /：$displayName" }
        val path = requirePath(documentId)
        requireRemovable(path)
        val parentPath = checkNotNull(parentRelativePath(path))
        val dir = hostFile(requireResolved(parentPath))
        val source = File(dir, path.substringAfterLast('/'))
        val target = File(dir, displayName)
        if (!entryExists(source)) throw FileNotFoundException("找不到 $documentId")
        // File.renameTo 会直接覆盖同名文件，先拦下
        check(!entryExists(target)) { "已有同名文件：$displayName" }
        if (!source.renameTo(target)) throw FileNotFoundException("无法改名为 $displayName")
        notifyChildrenChanged(parentPath)
        return containerDocumentId(childRelativePath(parentPath, displayName))
    }

    // ── 内部 ───────────────────────────────────────────────────────────

    private fun requirePath(documentId: String): String =
        containerRelativePath(documentId) ?: throw FileNotFoundException("无效的 documentId：$documentId")

    private fun requireResolved(path: String): String =
        resolveContainerSymlinks(path, hostRootAliases, ::readLinkInContainer)
            ?: throw FileNotFoundException("符号链接层数过多：$path")

    // 容器根目录、MaiBot 目录及其上级是两个根的入口，删掉或改名会让入口失效
    private fun requireRemovable(path: String) {
        if (path.isEmpty() || path == maiBotPath || isDescendantPath(path, maiBotPath)) {
            throw SecurityException("不能删除或改名：/$path")
        }
    }

    private fun hostFile(resolvedPath: String): File =
        if (resolvedPath.isEmpty()) rootDir else File(rootDir, resolvedPath)

    private fun readLinkInContainer(path: String): String? =
        try {
            Os.readlink(hostFile(path).path)
        } catch (_: ErrnoException) {
            null
        }

    private fun lstatOrNull(file: File): StructStat? =
        try {
            Os.lstat(file.path)
        } catch (_: ErrnoException) {
            null
        }

    private fun statOrNull(file: File): StructStat? =
        try {
            Os.stat(file.path)
        } catch (_: ErrnoException) {
            null
        }

    // 断开的符号链接 exists() 看不到，按 lstat 判断目录项在不在
    private fun entryExists(file: File): Boolean = lstatOrNull(file) != null

    private fun includeRootDocument(cursor: MatrixCursor) {
        val stat = statOrNull(rootDir)
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, containerDocumentId(""))
            add(Document.COLUMN_DISPLAY_NAME, CONTAINER_ROOT_TITLE)
            add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
            add(Document.COLUMN_LAST_MODIFIED, stat?.let { it.st_mtime * 1000 })
            add(Document.COLUMN_FLAGS, if (rootDir.canWrite()) Document.FLAG_DIR_SUPPORTS_CREATE else 0)
        }
    }

    /**
     * 往 cursor 里加一行：path 是字面路径（documentId 用它），resolvedParent 是它父目录解析后的路径。
     * 符号链接显示为它指向的东西（目录就能点进去），断开或成环的链接显示成一个打不开、但能删除改名的文件。
     * 目录项已经不存在时返回 false。
     */
    private fun includeEntry(
        cursor: MatrixCursor,
        path: String,
        resolvedParent: String,
        parentWritable: Boolean
    ): Boolean {
        val name = path.substringAfterLast('/')
        val entryPath = childRelativePath(resolvedParent, name)
        val entryStat = lstatOrNull(hostFile(entryPath)) ?: return false
        val targetFile: File?
        val targetStat: StructStat?
        if (OsConstants.S_ISLNK(entryStat.st_mode)) {
            targetFile = resolveContainerSymlinks(entryPath, hostRootAliases, ::readLinkInContainer)?.let(::hostFile)
            targetStat = targetFile?.let(::statOrNull)
        } else {
            targetFile = hostFile(entryPath)
            targetStat = entryStat
        }

        val isDirectory = targetStat != null && OsConstants.S_ISDIR(targetStat.st_mode)
        val mimeType = if (isDirectory) {
            Document.MIME_TYPE_DIR
        } else {
            containerFileMimeType(name) { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
        }
        val writable = targetStat != null && targetFile?.canWrite() == true
        var flags = 0
        if (isDirectory && writable) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        if (!isDirectory && writable) flags = flags or Document.FLAG_SUPPORTS_WRITE
        if (!isDirectory && targetStat != null && mimeType.startsWith("image/")) {
            flags = flags or Document.FLAG_SUPPORTS_THUMBNAIL
        }
        val removable = path != maiBotPath && !isDescendantPath(path, maiBotPath)
        if (parentWritable && removable) {
            flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        }

        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, containerDocumentId(path))
            add(Document.COLUMN_DISPLAY_NAME, name)
            add(Document.COLUMN_MIME_TYPE, mimeType)
            add(Document.COLUMN_SIZE, if (isDirectory) null else targetStat?.st_size)
            add(Document.COLUMN_LAST_MODIFIED, (targetStat ?: entryStat).st_mtime * 1000)
            add(Document.COLUMN_FLAGS, flags)
        }
        return true
    }

    // 不跟随符号链接：rootfs 里的 /root/storage/shared -> /sdcard 在宿主上指向手机共享存储，
    // 跟进去删会把用户的照片、下载一起删掉。遇到链接只删链接本身。
    private fun deleteWithoutFollowingLinks(file: File) {
        val stat = lstatOrNull(file) ?: return
        if (OsConstants.S_ISDIR(stat.st_mode)) {
            file.listFiles()?.forEach(::deleteWithoutFollowingLinks)
        }
        if (!file.delete()) throw FileNotFoundException("无法删除 ${file.name}")
    }

    private fun notifyChildrenChanged(parentPath: String) {
        checkNotNull(context).contentResolver.notifyChange(
            DocumentsContract.buildChildDocumentsUri(AUTHORITY, containerDocumentId(parentPath)),
            null
        )
    }
}
