package org.maiwithu.maidroid.runtime

import java.io.File
import java.util.TimeZone

/**
 * 把 ContainerScript 翻译成宿主机上可执行的 proot 命令行，并给出 proot 需要的环境变量。
 *
 * 容器 = APK 内置的 proot（jniLibs 里的 libproot_exec.so）+ 解包到应用私有目录的 Debian rootfs：
 * proot 以假 root（-0）把 rootfs 挂成根目录，绑定 /dev、/proc、/sys，并按外部存储授权状态按需绑定 /sdcard，
 * 再把 termux/tmp 绑成容器的 /tmp；--link2symlink 让不支持硬链接的应用私有目录也能装 Debian 软件包。
 */
internal class ProotCommand(
    private val paths: RuntimePaths,
    private val mountExternalStorage: () -> Boolean = { true }
) {
    private companion object {
        const val DEBIAN_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    }

    /** 在容器里用 /bin/sh 执行脚本：先重置 PATH、HOME 等 Debian 环境变量，按需挂载 extraBinds 与排除最高频 CPU 簇，再执行脚本正文。 */
    fun commandLine(script: ContainerScript): List<String> =
        prootArguments(script.workingDirectory, script.extraBinds) +
            listOf("/bin/sh", "-c", "${shellPrelude()} ${if (script.excludeTopCpuCluster) excludeTopCpuClusterPrelude() else ""} ${script.body}")

    /**
     * 构造可供伪终端（PTY）直接拉起的交互式容器 Shell 命令行：
     * 清空宿主 LD_LIBRARY_PATH 并注入 xterm-256color 与简洁橙蓝提示符，优先启动交互式 bash（缺失时回退 sh）。
     */
    fun interactiveShellCommandLine(workingDirectory: String = InContainerPaths.MAIBOT_DIR): List<String> =
        prootArguments(workingDirectory) + listOf("/bin/sh", "-c", interactiveShellScript())

    /**
     * 构造容器交互式 Shell 使用的宿主侧进程环境变量数组（KEY=VALUE 形式）：
     * 以宿主环境为底避免 execve 丢失系统基础变量，再覆写 proot 所需库路径与终端色彩/编码环境。
     */
    fun interactiveShellEnvironment(hostEnvironment: Map<String, String> = System.getenv()): Array<String> =
        buildMap {
            putAll(hostEnvironment)
            putAll(environment())
            put("TERM", "xterm-256color")
            put("COLORTERM", "truecolor")
            put("LANG", "C.UTF-8")
        }.entries.map { (key, value) -> "$key=$value" }.toTypedArray()

    /**
     * proot 进程本身的环境变量：PROOT_LOADER 和 LD_LIBRARY_PATH 指向 APK 内置的 loader 与依赖库，
     * PROOT_TMP_DIR 放 proot 的临时文件。进入容器后脚本前缀会 unset LD_LIBRARY_PATH，避免污染 Debian 程序。
     */
    fun environment(): Map<String, String> {
        paths.termuxTmpDir.mkdirs()
        return buildMap {
            put("PATH", DEBIAN_PATH)
            put("HOME", "/root")
            put("USER", "root")
            put("LOGNAME", "root")
            put("SHELL", "/bin/sh")
            put("TERM", "xterm-256color")
            put("PROOT_LOADER", paths.prootLoader.absolutePath)
            put("PROOT_TMP_DIR", paths.termuxTmpDir.absolutePath)
            put("TMPDIR", paths.termuxTmpDir.absolutePath)
            put("LD_LIBRARY_PATH", "${paths.nativeLibraryDir.absolutePath}:${paths.termuxLibDir.absolutePath}")
        }
    }

    /**
     * 把容器的系统时区同步成手机当前时区，返回容器现在用的时区名；rootfs 没装好或写盘失败时返回 null。
     * 时区同步失败不该挡住容器命令，失败时容器沿用原来的时区。
     */
    fun syncTimeZone(): String? =
        runCatching { ContainerTimeZone.sync(paths.rootfsDir, TimeZone.getDefault()) }.getOrNull()

    private fun prootArguments(
        workingDirectory: String,
        extraBinds: List<ContainerBindMount> = emptyList()
    ): List<String> {
        paths.termuxTmpDir.mkdirs()
        ensureFakeProcFiles()
        // 每条容器命令启动前都对一次时区：手机在应用没跑时改过时区，下次拉起的进程也能用上新时区。
        syncTimeZone()
        return buildList {
            addAll(
                listOf(
                    paths.prootExecutable.absolutePath,
                    "--link2symlink",
                    "-0",
                    "-r", paths.rootfsDir.absolutePath,
                    "-b", "/dev",
                    "-b", "/proc",
                    "-b", "${paths.fakeProcStat.absolutePath}:/proc/stat",
                    "-b", "${paths.fakeProcLoadAvg.absolutePath}:/proc/loadavg",
                    "-b", "${paths.fakeProcVmStat.absolutePath}:/proc/vmstat",
                    "-b", "${paths.fakeProcUptime.absolutePath}:/proc/uptime",
                    "-b", "${paths.procLocksStandIn.absolutePath}:/proc/locks",
                    "-b", "/sys",
                    "-b", "${paths.termuxTmpDir.absolutePath}:/tmp"
                )
            )
            // 仅在已授予外部存储权限时挂载手机 /sdcard，未授权时不挂载以避免绑定告警并遵守最小权限。
            if (mountExternalStorage()) {
                add("-b")
                add("/sdcard")
            }
            // 仅在宿主机已有对应覆盖文件时注入 proot 绑定（如 NapCat 覆盖 QQ 的 package.json），不修改磁盘原文件。
            for (bind in extraBinds) {
                val source = File(paths.rootfsDir, bind.rootfsRelativeSource)
                if (source.exists()) {
                    add("-b")
                    add("${source.absolutePath}:${bind.containerTarget}")
                }
            }
            add("-w")
            add(workingDirectory)
        }
    }

    // 注入基础环境变量并限制 glibc arenas，促使容器内 Python/Node/Electron 及时归还空闲内存。
    private fun shellPrelude(): String =
        "unset LD_LIBRARY_PATH; " +
            "export PATH=${shellQuote(DEBIAN_PATH)}; " +
            "export HOME=/root USER=root LOGNAME=root SHELL=/bin/sh TMPDIR=/tmp; " +
            "export MALLOC_ARENA_MAX=2 MALLOC_TRIM_THRESHOLD_=131072; "

    // 交互式本地终端进入容器前的环境整理：保留完整 Debian 工具链与颜色支持，并按 bash -> sh 顺序接管 TTY
    private fun interactiveShellScript(): String =
        shellPrelude() +
            "export TERM=xterm-256color COLORTERM=truecolor LANG=C.UTF-8; " +
            "export PS1='\\[\\e[1;33m\\]maidroid\\[\\e[0m\\]:\\[\\e[1;34m\\]\\w\\[\\e[0m\\]# '; " +
            "if [ -x /bin/bash ]; then export SHELL=/bin/bash; exec /bin/bash -i; " +
            "else export SHELL=/bin/sh; exec /bin/sh -i; fi"

    /**
     * 把容器整棵进程树与宿主外层 proot 父进程一并钉在「非最高频簇」的 CPU 上（通用能效规则，非机型特调）：
     * 直接枚举 /sys/devices/system/cpu/cpu[0-9]* 避免 512 次无效系统调用；每核优先读 cpuinfo_max_freq，
     * 不可读时回退 scaling_max_freq，若所有核频点均受限再回退 cpu_capacity；检测失败、单簇或无 taskset 时跳过。
     * 钉住当前 shell（$$）后，校验父进程 cmdline 含 proot 时同步钉住外层 proot，消除 ptrace 跨簇切换抖动。
     */
    private fun excludeTopCpuClusterPrelude(): String =
        """
        maidroid_exclude_top_cpu_cluster() {
            command -v taskset >/dev/null 2>&1 || return 0
            max_freq=0
            freq_cpus=''
            max_cap=0
            cap_cpus=''
            for dir in /sys/devices/system/cpu/cpu[0-9]*; do
                [ -d "${'$'}dir" ] || continue
                idx=${'$'}{dir##*/cpu}
                case "${'$'}idx" in ''|*[!0-9]*) continue ;; esac
                fv=''
                if [ -r "${'$'}dir/cpufreq/cpuinfo_max_freq" ]; then
                    fv=${'$'}(cat "${'$'}dir/cpufreq/cpuinfo_max_freq" 2>/dev/null)
                elif [ -r "${'$'}dir/cpufreq/scaling_max_freq" ]; then
                    fv=${'$'}(cat "${'$'}dir/cpufreq/scaling_max_freq" 2>/dev/null)
                fi
                case "${'$'}fv" in ''|*[!0-9]*) fv=0 ;; esac
                if [ "${'$'}fv" -gt 0 ]; then
                    freq_cpus="${'$'}freq_cpus ${'$'}idx:${'$'}fv"
                    [ "${'$'}fv" -gt "${'$'}max_freq" ] && max_freq=${'$'}fv
                fi
                if [ -r "${'$'}dir/cpu_capacity" ]; then
                    cv=${'$'}(cat "${'$'}dir/cpu_capacity" 2>/dev/null)
                    case "${'$'}cv" in ''|*[!0-9]*) cv=0 ;; esac
                    if [ "${'$'}cv" -gt 0 ]; then
                        cap_cpus="${'$'}cap_cpus ${'$'}idx:${'$'}cv"
                        [ "${'$'}cv" -gt "${'$'}max_cap" ] && max_cap=${'$'}cv
                    fi
                fi
            done
            src='none'
            top_val=0
            pairs=''
            mask=''
            if [ "${'$'}max_freq" -gt 0 ]; then
                src='cpufreq'
                top_val=${'$'}max_freq
                pairs=${'$'}freq_cpus
            elif [ "${'$'}max_cap" -gt 0 ]; then
                src='capacity'
                top_val=${'$'}max_cap
                pairs=${'$'}cap_cpus
            fi
            [ "${'$'}top_val" -gt 0 ] || return 0
            for pair in ${'$'}pairs; do
                idx=${'$'}{pair%%:*}
                val=${'$'}{pair##*:}
                [ "${'$'}val" -lt "${'$'}top_val" ] && mask="${'$'}mask${'$'}idx,"
            done
            mask=${'$'}{mask%,}
            if [ -z "${'$'}mask" ]; then
                return 0
            fi
            if taskset -pc "${'$'}mask" ${'$'}${'$'} >/dev/null 2>&1; then
                # 同时尝试将外层 proot 父进程钉入同一能效簇，防止父子跨核簇切换拉高大核功耗。
                proot_pinned='no'
                ppid="${'$'}{PPID:-}"
                if [ -z "${'$'}ppid" ] && [ -r "/proc/${'$'}${'$'}/status" ]; then
                    ppid=${'$'}(awk '/^PPid:/ {print ${'$'}2; exit}' "/proc/${'$'}${'$'}/status" 2>/dev/null || true)
                fi
                case "${'$'}ppid" in ''|*[!0-9]*) ppid='' ;; esac
                if [ -n "${'$'}ppid" ] && [ -r "/proc/${'$'}ppid/cmdline" ]; then
                    case "${'$'}(tr '\0' ' ' < "/proc/${'$'}ppid/cmdline" 2>/dev/null)" in
                        *proot*)
                            if taskset -pc "${'$'}mask" "${'$'}ppid" >/dev/null 2>&1; then
                                proot_pinned='yes'
                            fi
                            ;;
                    esac
                fi
                echo "[maidroid] CPU affinity pinned to cpus ${'$'}mask (source=${'$'}src, top=${'$'}top_val, proot=${'$'}proot_pinned)"
            fi
        }
        maidroid_exclude_top_cpu_cluster
        unset -f maidroid_exclude_top_cpu_cluster;
        """.trimIndent()

    /**
     * Android 不允许普通应用读 /proc/stat、/proc/loadavg、/proc/vmstat、/proc/uptime，
     * psutil、uv 等程序读不到会直接报错。这里写一份全零的假文件，由 proot 绑定到对应位置；
     * 开机时间按“一分钟前”填，内容不变时不重写，避免每条命令都触发一次磁盘写入。
     * /proc/locks 同样读不了，它的替身在 SnowLuma 运行期间由宿主侧 QqProcLocksSync 采集填内容（见 InContainerPaths.PROC_LOCKS_STAND_IN）。
     */
    private fun ensureFakeProcFiles() {
        val bootTimeSeconds = (System.currentTimeMillis() / 1000L) - 60L
        writeIfChanged(
            paths.fakeProcStat,
            """
            cpu  0 0 0 0 0 0 0 0 0 0
            cpu0 0 0 0 0 0 0 0 0 0 0
            intr 0
            ctxt 0
            btime $bootTimeSeconds
            processes 1
            procs_running 1
            procs_blocked 0
            softirq 0 0 0 0 0 0 0 0 0 0 0
            """.trimIndent() + "\n"
        )
        writeIfChanged(paths.fakeProcLoadAvg, "0.00 0.00 0.00 1/1 1\n")
        writeIfChanged(
            paths.fakeProcVmStat,
            """
            nr_free_pages 0
            nr_inactive_anon 0
            nr_active_anon 0
            nr_inactive_file 0
            nr_active_file 0
            nr_unevictable 0
            nr_mlock 0
            pgpgin 0
            pgpgout 0
            pswpin 0
            pswpout 0
            pgfault 0
            pgmajfault 0
            """.trimIndent() + "\n"
        )
        writeIfChanged(paths.fakeProcUptime, "60.00 60.00\n")
        // /proc/locks 的替身只在没有时建一个空的：SnowLuma 运行期间由宿主侧 QqProcLocksSync 定时刷新它，这里不能覆盖
        if (!paths.procLocksStandIn.isFile) {
            paths.procLocksStandIn.parentFile?.mkdirs()
            paths.procLocksStandIn.writeText("")
        }
    }

    private fun writeIfChanged(file: File, content: String) {
        file.parentFile?.mkdirs()
        if (!file.isFile || file.readText() != content) {
            file.writeText(content)
        }
        file.setReadable(true, false)
        file.setWritable(true, true)
    }
}
