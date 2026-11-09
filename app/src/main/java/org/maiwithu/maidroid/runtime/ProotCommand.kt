package org.maiwithu.maidroid.runtime

import java.io.File
import java.util.TimeZone

/**
 * 把 ContainerScript 翻译成宿主机上可执行的 proot 命令行，并给出 proot 需要的环境变量。
 *
 * 容器 = APK 内置的 proot（jniLibs 里的 libproot_exec.so）+ 解包到应用私有目录的 Debian rootfs：
 * proot 以假 root（-0）把 rootfs 挂成根目录，绑定 /dev、/proc、/sys、/sdcard，
 * 再把 termux/tmp 绑成容器的 /tmp；--link2symlink 让不支持硬链接的应用私有目录也能装 Debian 软件包。
 */
internal class ProotCommand(private val paths: RuntimePaths) {
    private companion object {
        const val DEBIAN_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    }

    /** 在容器里用 /bin/sh 执行脚本：先重置 PATH、HOME 等 Debian 环境变量，再执行脚本正文。 */
    fun commandLine(script: ContainerScript): List<String> =
        prootArguments(script.workingDirectory) +
            listOf("/bin/sh", "-c", "${shellPrelude()} ${if (script.pinToSmallCores) pinSmallCoresPrelude() else ""} ${script.body}")

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

    private fun prootArguments(workingDirectory: String): List<String> {
        paths.termuxTmpDir.mkdirs()
        ensureFakeProcFiles()
        // 每条容器命令启动前都对一次时区：手机在应用没跑时改过时区，下次拉起的进程也能用上新时区。
        syncTimeZone()
        return listOf(
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
            "-b", "${paths.fakeProcLocks.absolutePath}:/proc/locks",
            "-b", "/sys",
            "-b", "/sdcard",
            "-b", "${paths.termuxTmpDir.absolutePath}:/tmp",
            "-w", workingDirectory
        )
    }

    private fun shellPrelude(): String =
        "unset LD_LIBRARY_PATH; " +
            "export PATH=${shellQuote(DEBIAN_PATH)}; " +
            "export HOME=/root USER=root LOGNAME=root SHELL=/bin/sh TMPDIR=/tmp; "

    /**
     * 把整棵进程树钉在「非最高频簇」的 CPU 上（能效优化，通用规则不是机型特调）：
     * 从 /sys 分簇，频率最高的簇（大小核机型的大核；三簇机型的 prime 簇）不进亲和掩码，
     * 其余全部保留；检测失败、 Cliff 全同频（单簇）或没有 taskset 时不钉。子进程继承亲和性，
     * 所以钉住这一条 sh，proot 里的 MaiBot/QQ/node/worker 全都会跟着留在中小核上。
     */
    private fun pinSmallCoresPrelude(): String =
        """
        maidroid_pin_small_cores() {
            command -v taskset >/dev/null 2>&1 || return 0
            max_freq=0
            cpus=''
            i=0
            while [ ${'$'}i -lt 512 ]; do
                f=/sys/devices/system/cpu/cpu${'$'}i/cpufreq/cpuinfo_max_freq
                if [ -r "${'$'}f" ]; then
                    v=${'$'}(cat "${'$'}f" 2>/dev/null)
                    case "${'$'}v" in ''|*[!0-9]*) v=0 ;; esac
                    if [ "${'$'}v" -gt 0 ]; then
                        cpus="${'$'}cpus ${'$'}i:${'$'}v"
                        [ "${'$'}v" -gt "${'$'}max_freq" ] && max_freq=${'$'}v
                    fi
                fi
                i=${'$'}((i + 1))
            done
            [ "${'$'}max_freq" -gt 0 ] || return 0
            mask=''
            for pair in ${'$'}cpus; do
                idx=${'$'}{pair%%:*}
                freq=${'$'}{pair##*:}
                [ "${'$'}freq" -lt "${'$'}max_freq" ] && mask="${'$'}mask${'$'}idx,"
            done
            mask=${'$'}{mask%,}
            if [ -z "${'$'}mask" ]; then
                return 0
            fi
            // 紧连的 -pc 在 util-linux 2.38 的 Debian rootfs 实测可用；
            // 分开写的「-c 列表 -p pid」会把 -p 当成待执行程序（实测报 failed to execute -p）。
            if taskset -pc "${'$'}mask" ${'$'}${'$'} >/dev/null 2>&1; then
                echo "[maidroid] CPU affinity pinned to cpus ${'$'}mask (excluding top cluster ${'$'}max_freq kHz)"
            fi
        }
        maidroid_pin_small_cores
        unset -f maidroid_pin_small_cores
        """.trimIndent()

    /**
     * Android 不允许普通应用读 /proc/stat、/proc/loadavg、/proc/vmstat、/proc/uptime，
     * psutil、uv 等程序读不到会直接报错。这里写一份全零的假文件，由 proot 绑定到对应位置；
     * 开机时间按“一分钟前”填，内容不变时不重写，避免每条命令都触发一次磁盘写入。
     * /proc/locks 同样读不了，它的替身由用到它的 SnowLuma 启动脚本填内容（见 InContainerPaths.PROC_LOCKS_STAND_IN）。
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
        // /proc/locks 的替身只在没有时建一个空的：SnowLuma 运行期间容器里会不断重写它，这里不能覆盖
        if (!paths.fakeProcLocks.isFile) {
            paths.fakeProcLocks.parentFile?.mkdirs()
            paths.fakeProcLocks.writeText("")
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
