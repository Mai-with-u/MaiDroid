package org.maiwithu.maidroid.cleanup

import org.maiwithu.maidroid.runtime.ContainerScript
import org.maiwithu.maidroid.runtime.InContainerPaths
import org.maiwithu.maidroid.runtime.shellQuote

/**
 * “清理空间”里能清的几类东西。都是删了能自己再下回来、或者确定用不到的，不碰配置、数据、插件和已经装好的依赖。
 * 2026-11 调试机实测：uv 下载缓存约 1.5 GB，apt 缓存和索引约 320 MB，没用上的 Python 约 100 MB，MaiBot 日志约 76 MB。
 */
internal enum class CleanupItem(
    /** 脚本输出里的键，见 StorageCleanupScripts。 */
    val key: String,
    val label: String,
    val detail: String,
    val selectedByDefault: Boolean
) {
    DependencyCache(
        "dependency_cache", "依赖包下载缓存",
        "uv 和 pip 下载过的安装包。已经装好的依赖不受影响，下次装依赖时重新下载。", true
    ),
    AptCache("apt_cache", "软件源缓存", "apt 下载过的软件包和索引，下次装软件时重新下载。", true),
    UnusedPython(
        "unused_python", "用不到的 Python 版本",
        "uv 装过、但 MaiBot 现在没在用的 Python；正在用的那个会留着。", true
    ),
    NapCatBackups("napcat_backups", "没装完的 NapCat 备份", "重装 NapCat 时挪到一边的半成品。", true),
    OldMaiBotLogs(
        "maibot_old_logs", "MaiBot 三天前的日志",
        "最近三天的日志会留着，反馈问题时用得上。", false
    )
}

/**
 * 清理空间的两段容器脚本：measure 量出每类占多少（输出 `size.<键>=<KB>`），
 * clean 按勾选的几类删除，正在装依赖或装软件时跳过对应的那类（输出 `skipped=<键>`），删完再量一遍。
 */
internal object StorageCleanupScripts {
    private const val MAIBOT_DIR = InContainerPaths.MAIBOT_DIR
    private const val UV_PYTHON_DIR = "/root/.local/share/uv/python"
    private const val LOG_PREFIX = "[清理]"

    // 三天前的日志才删
    private const val LOG_KEEP_DAYS = 3

    fun measure(): ContainerScript = ContainerScript(listOf("set +e", functions(), measureBody()).joinToString("\n"))

    fun clean(items: Set<CleanupItem>): ContainerScript = ContainerScript(
        listOf(
            "set +e",
            functions(),
            CleanupItem.entries.filter { it in items }.joinToString("\n") { cleanBody(it) },
            measureBody()
        ).joinToString("\n")
    )

    private fun measureBody(): String =
        CleanupItem.entries.joinToString("\n") { item ->
            val size = when (item) {
                CleanupItem.DependencyCache -> "maidroid_size_kb /root/.cache/uv /root/.cache/pip"
                CleanupItem.AptCache -> "maidroid_size_kb /var/cache/apt /var/lib/apt/lists"
                CleanupItem.UnusedPython -> "maidroid_size_kb ${'$'}(maidroid_unused_pythons)"
                CleanupItem.NapCatBackups -> "maidroid_size_kb /root/napcat.maidroid-bak.*"
                CleanupItem.OldMaiBotLogs -> "maidroid_old_logs | maidroid_sum_kb"
            }
            "printf 'size.${item.key}=%s\\n' \"${'$'}($size)\""
        }

    // 每一类怎么删：缓存和 Python 在有 uv、apt 正在跑时跳过，免得删掉它们正在用的文件。
    private fun cleanBody(item: CleanupItem): String = when (item) {
        CleanupItem.DependencyCache ->
            """
            if maidroid_busy uv 'sync|pip|python|lock|venv|add|remove'; then
                maidroid_skip ${item.key} ${shellQuote(item.label)} "正在装依赖"
            else
                rm -rf /root/.cache/uv /root/.cache/pip
                maidroid_done ${shellQuote(item.label)}
            fi
            """.trimIndent()
        CleanupItem.AptCache ->
            """
            if maidroid_busy 'apt|apt-get|dpkg' ''; then
                maidroid_skip ${item.key} ${shellQuote(item.label)} "正在装软件"
            else
                apt-get clean >/dev/null 2>&1
                rm -rf /var/lib/apt/lists/*
                mkdir -p /var/lib/apt/lists/partial
                maidroid_done ${shellQuote(item.label)}
            fi
            """.trimIndent()
        CleanupItem.UnusedPython ->
            """
            if maidroid_busy uv 'sync|python|venv'; then
                maidroid_skip ${item.key} ${shellQuote(item.label)} "正在装依赖"
            else
                for python_dir in ${'$'}(maidroid_unused_pythons); do
                    printf "$LOG_PREFIX 删除 %s\n" "${'$'}{python_dir##*/}"
                    rm -rf "${'$'}python_dir"
                done
                # 删掉 Python 后，指向它的版本链接和 ~/.local/bin 里的 python3.x 链接成了空链接，一起清掉
                for python_link in $UV_PYTHON_DIR/* /root/.local/bin/python*; do
                    if [ -L "${'$'}python_link" ] && [ ! -e "${'$'}python_link" ]; then
                        rm -f "${'$'}python_link"
                    fi
                done
                maidroid_done ${shellQuote(item.label)}
            fi
            """.trimIndent()
        CleanupItem.NapCatBackups ->
            """
            rm -rf /root/napcat.maidroid-bak.*
            maidroid_done ${shellQuote(item.label)}
            """.trimIndent()
        CleanupItem.OldMaiBotLogs ->
            """
            maidroid_old_logs | while IFS= read -r old_log; do rm -f "${'$'}old_log"; done
            maidroid_done ${shellQuote(item.label)}
            """.trimIndent()
    }

    private fun functions(): String =
        """
        maidroid_size_kb() {
            [ "${'$'}#" -gt 0 ] || { echo 0; return; }
            du -skc "${'$'}@" 2>/dev/null | tail -n 1 | cut -f1
        }
        maidroid_sum_kb() {
            while IFS= read -r sized_file; do du -sk "${'$'}sized_file" 2>/dev/null; done | awk '{ s += ${'$'}1 } END { print s + 0 }'
        }
        maidroid_old_logs() {
            find ${shellQuote("$MAIBOT_DIR/logs")} -type f -mtime +$LOG_KEEP_DAYS 2>/dev/null
        }

        # MaiBot 的 .venv 用的是哪个 Python（pyvenv.cfg 的 home 指向 uv 的版本链接，解析到真实目录），
        # 列出 uv 装过的其它版本；没有 .venv 时不知道在用哪个，一个也不列。
        maidroid_unused_pythons() {
            venv_home="${'$'}(sed -n 's/^home *= *//p' ${shellQuote("$MAIBOT_DIR/.venv/pyvenv.cfg")} 2>/dev/null | head -n 1)"
            [ -n "${'$'}venv_home" ] || return 0
            used_python="${'$'}(readlink -f "${'$'}venv_home/.." 2>/dev/null)"
            [ -n "${'$'}used_python" ] || return 0
            for python_dir in $UV_PYTHON_DIR/cpython-*; do
                [ -d "${'$'}python_dir" ] && [ ! -L "${'$'}python_dir" ] || continue
                [ "${'$'}(readlink -f "${'$'}python_dir")" = "${'$'}used_python" ] && continue
                printf '%s\n' "${'$'}python_dir"
            done
        }

        # 有没有正在跑的 names 进程（只认命令行第一个词去掉路径后的程序名）；subcommands 不为空时还要第二个词匹配，
        # 例如 uv 只看 sync、pip 这些会读写缓存的子命令，不算一直跑着的 uv run。
        maidroid_busy() {
            ps -eo args= | awk -v names="${'$'}1" -v subcommands="${'$'}2" '
                { name = ${'$'}1; sub(/.*\//, "", name)
                  if (name ~ ("^(" names ")${'$'}") && (subcommands == "" || ${'$'}2 ~ ("^(" subcommands ")${'$'}"))) found = 1 }
                END { exit !found }'
        }
        maidroid_skip() {
            printf "skipped=%s\n" "${'$'}1"
            printf "$LOG_PREFIX %s：%s，这次先跳过\n" "${'$'}2" "${'$'}3"
        }
        maidroid_done() {
            printf "$LOG_PREFIX 已清理：%s\n" "${'$'}1"
        }
        """.trimIndent()
}

/** 从脚本输出里读出每类的大小（字节）；没量到的类不在结果里。 */
internal fun parseCleanupSizes(output: String): Map<CleanupItem, Long> =
    output.lineSequence().mapNotNull { line ->
        val key = line.substringAfter("size.", "").substringBefore('=')
        val item = CleanupItem.entries.firstOrNull { it.key == key } ?: return@mapNotNull null
        val kb = line.substringAfter('=').trim().toLongOrNull() ?: return@mapNotNull null
        item to kb * 1024
    }.toMap()

/** 从清理脚本的输出里读出因为正在装依赖、装软件而跳过的几类。 */
internal fun parseSkippedCleanup(output: String): Set<CleanupItem> =
    output.lineSequence().mapNotNull { line ->
        val key = line.substringAfter("skipped=", "").trim()
        CleanupItem.entries.firstOrNull { it.key == key }
    }.toSet()
