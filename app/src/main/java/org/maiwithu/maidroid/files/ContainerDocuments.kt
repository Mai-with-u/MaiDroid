package org.maiwithu.maidroid.files

/*
 * ContainerDocumentsProvider 用到的纯逻辑：documentId 与容器内路径互转、按容器语义解析符号链接、
 * 判断上下级、推断 MIME、给新文件取不重名的名字。不依赖 Android，可以直接做 JVM 单测。
 *
 * documentId = "container:" + 相对容器根目录的路径，例如 "container:opt/MaiBot/logs"，容器根目录本身是 "container:"。
 * 路径一律用 "/" 分隔、不带首尾斜杠、不含 "."、".." 和空段；不合这个格式的 documentId
 * （包括别的应用拼出来想越界的 "container:../../shared_prefs"）一律当作不存在。
 */

internal const val CONTAINER_DOCUMENT_PREFIX = "container:"

/** 符号链接最多跟随的层数，与 Linux 的 MAXSYMLINKS 一致；超过就当成链接成环，按不存在处理。 */
internal const val MAX_SYMLINK_HOPS = 40

/** proot --link2symlink 把硬链接换成符号链接时，真正的文件内容和引用计数存在 ".l2s." 开头的同目录文件里。 */
private const val PROOT_LINK2SYMLINK_PREFIX = ".l2s."

private const val TEXT_PLAIN = "text/plain"
private const val OCTET_STREAM = "application/octet-stream"

/**
 * 报成 text/plain 的扩展名：MaiBot 的日志是 .log.jsonl、配置是 .toml、插件是 .py 和 _manifest.json，
 * 系统的扩展名表要么不认识，要么报成 application/json 这类几乎没有应用能打开的类型；
 * 报成纯文本后，系统“文件”应用点开就能交给任意文本查看器。
 */
private val TEXT_EXTENSIONS = setOf(
    "log", "txt", "jsonl", "ndjson", "json", "toml", "ini", "cfg", "conf", "env", "properties",
    "py", "pyi", "sh", "bash", "yaml", "yml", "md", "markdown", "rst", "lock", "js", "mjs", "ts", "css"
)

internal fun containerDocumentId(relativePath: String): String = CONTAINER_DOCUMENT_PREFIX + relativePath

/** documentId -> 相对容器根目录的路径（"" 是根目录）；格式不对返回 null。 */
internal fun containerRelativePath(documentId: String): String? {
    if (!documentId.startsWith(CONTAINER_DOCUMENT_PREFIX)) return null
    val path = documentId.substring(CONTAINER_DOCUMENT_PREFIX.length)
    if (path.isEmpty()) return path
    return path.takeIf { it.split('/').all(::isPlainPathSegment) }
}

/** 目录 parent（相对路径）下名为 name 的子项的相对路径。 */
internal fun childRelativePath(parent: String, name: String): String =
    if (parent.isEmpty()) name else "$parent/$name"

/** 子项的父目录（相对路径）；根目录没有父目录，返回 null。 */
internal fun parentRelativePath(path: String): String? =
    if (path.isEmpty()) null else path.substringBeforeLast('/', missingDelimiterValue = "")

/** child 是否在 parent 之下（子孙都算，自身不算）。只看路径字面，供 SAF 校验目录树授权用。 */
internal fun isDescendantPath(parent: String, child: String): Boolean =
    child != parent && (parent.isEmpty() || child.startsWith("$parent/"))

/** 新建、改名时用户给的文件名能不能直接用：不能为空，不能是 "."、".."，不能带路径分隔符。 */
internal fun isValidDisplayName(name: String): Boolean =
    isPlainPathSegment(name) && name.isNotBlank()

/** proot 自己维护的硬链接替身文件，列目录时不显示（它们的内容经由同名符号链接照常可见）。 */
internal fun isProotInternalEntry(name: String): Boolean = name.startsWith(PROOT_LINK2SYMLINK_PREFIX)

/**
 * 宿主绝对路径 hostPath 落在容器根目录（任一别名）之下时，返回相对容器根目录的路径，否则返回 null。
 *
 * 容器根目录在宿主上有多个写法：Context.filesDir 给的 /data/user/0/...、它的真实路径，以及
 * /data/data/... ——真机上 --link2symlink 写下的链接目标用的就是最后这种，所以三种都要认。
 */
internal fun relativeToHostRoot(hostPath: String, hostRootAliases: List<String>): String? {
    for (alias in hostRootAliases) {
        val root = alias.trimEnd('/')
        if (root.isEmpty()) continue
        if (hostPath == root) return ""
        if (hostPath.startsWith("$root/")) return hostPath.substring(root.length + 1)
    }
    return null
}

/**
 * 按容器语义逐段解析 relativePath 里的符号链接，返回解析后相对容器根目录的路径；链接成环（超过 40 层）返回 null。
 *
 * 遇到符号链接时：
 * - 目标落在容器根目录的宿主路径下（--link2symlink 写的硬链接替身）-> 换算成容器内路径；
 * - 目标是其它绝对路径（/usr/share/zoneinfo/...、/sdcard）-> 当作容器内路径，从容器根目录重新解析；
 * - 目标是相对路径 -> 相对链接所在目录解析。
 * ".." 到容器根目录为止，和 chroot 一样，所以结果永远在容器里，不会解析到应用私有目录以外。
 * 中间不存在的路径段照常保留，结果就是一个不存在的文件，由调用方按“找不到”处理。
 *
 * readLink 按相对容器根目录的路径读链接目标，不是符号链接（或读不到）时返回 null。
 */
internal fun resolveContainerSymlinks(
    relativePath: String,
    hostRootAliases: List<String>,
    readLink: (String) -> String?
): String? {
    val pending = ArrayDeque(relativePath.split('/'))
    val resolved = ArrayList<String>()
    var hops = 0
    while (pending.isNotEmpty()) {
        val segment = pending.removeFirst()
        when (segment) {
            "", "." -> continue
            ".." -> {
                if (resolved.isNotEmpty()) resolved.removeAt(resolved.lastIndex)
                continue
            }
        }
        resolved += segment
        val target = readLink(resolved.joinToString("/")) ?: continue
        if (++hops > MAX_SYMLINK_HOPS) return null
        // 链接本身不进结果，换成它的目标继续解析；绝对路径目标从容器根目录重新开始。
        resolved.removeAt(resolved.lastIndex)
        val absolute = target.startsWith("/")
        if (absolute) resolved.clear()
        val next = if (absolute) relativeToHostRoot(target, hostRootAliases) ?: target else target
        next.split('/').asReversed().forEach(pending::addFirst)
    }
    return resolved.joinToString("/")
}

/**
 * 按文件名推断 MIME：源码、配置、日志一律 text/plain（原因见 TEXT_EXTENSIONS），
 * ".bashrc"、".env" 这类只有一个前导点的配置文件也算文本；其余查系统扩展名表，查不到是 application/octet-stream。
 */
internal fun containerFileMimeType(name: String, lookupByExtension: (String) -> String?): String {
    val dot = name.lastIndexOf('.')
    if (dot == 0) return TEXT_PLAIN
    if (dot < 0 || dot == name.lastIndex) return OCTET_STREAM
    val extension = name.substring(dot + 1).lowercase()
    if (extension in TEXT_EXTENSIONS) return TEXT_PLAIN
    return lookupByExtension(extension) ?: OCTET_STREAM
}

/**
 * 新建文件或文件夹时取一个不重名的名字：没有重名就用原名，否则在扩展名前加 " (1)"、" (2)"……
 * 文件夹和没有扩展名的文件直接加在末尾。exists 按名字判断同一目录下是否已有同名项。
 */
internal fun uniqueChildName(name: String, isDirectory: Boolean, exists: (String) -> Boolean): String {
    if (!exists(name)) return name
    val dot = if (isDirectory) -1 else name.lastIndexOf('.')
    val (base, extension) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
    var index = 1
    while (true) {
        val candidate = "$base ($index)$extension"
        if (!exists(candidate)) return candidate
        index++
    }
}

private fun isPlainPathSegment(segment: String): Boolean =
    segment.isNotEmpty() && segment != "." && segment != ".." && '/' !in segment && '\u0000' !in segment
