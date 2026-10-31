package org.maiwithu.maidroid.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.TimeZone
import kotlin.math.abs

/**
 * 让容器的系统时区跟着手机走：rootfs 的 /etc/localtime 指向手机时区对应的 zoneinfo 文件，/etc/timezone 写同一个名字。
 *
 * Debian rootfs 出厂是 Etc/UTC，麦麦按容器时间报时，国内手机上会差 8 个小时。
 * ProotCommand 在每条容器命令启动前同步一次；手机改时区时前台服务收到广播再同步，并重启正在跑的麦麦——
 * 进程启动时就读定了时区，不重启会一直按旧时区报时。SSH 里新开的命令不经过 ProotCommand，靠的也是广播那一次同步。
 */
internal object ContainerTimeZone {
    private const val ZONEINFO_DIR = "usr/share/zoneinfo"
    private const val FALLBACK_ZONE = "Etc/UTC"
    private const val HOUR_MILLIS = 3_600_000

    // 时区名会拼进 rootfs 里的路径，只放行 IANA 名字里会出现的字符，挡掉 ".." 和开头的 "/"。
    private val ZONE_ID_PATTERN = Regex("[A-Za-z0-9_+-]+(/[A-Za-z0-9_+-]+)*")

    /**
     * 把容器时区改成手机的 zone，返回容器现在用的时区名；内容没变时不写盘。
     * rootfs 还没解好（没有 zoneinfo 目录）时什么都不改，返回 null。多个容器命令可能同时启动，所以加锁。
     */
    @Synchronized
    fun sync(rootfsDir: File, zone: TimeZone, nowMillis: Long = System.currentTimeMillis()): String? {
        val zoneinfoDir = File(rootfsDir, ZONEINFO_DIR)
        val etcDir = File(rootfsDir, "etc")
        if (!zoneinfoDir.isDirectory || !etcDir.isDirectory) return null

        // zoneinfo 里有些名字是软链接（链到容器内的绝对路径，宿主侧解析不了），所以只看这一项本身在不在。
        val zoneId = containerZoneId(zone, nowMillis) {
            Files.exists(File(zoneinfoDir, it).toPath(), LinkOption.NOFOLLOW_LINKS)
        }
        pointLocaltimeAt(etcDir, "/$ZONEINFO_DIR/$zoneId")
        val timezoneFile = File(etcDir, "timezone")
        if (!timezoneFile.isFile || timezoneFile.readText().trim() != zoneId) {
            timezoneFile.writeText("$zoneId\n")
        }
        return zoneId
    }

    /**
     * 手机时区在容器里的名字：容器的 tzdata 里有同名文件就用原名；
     * 没有（手机上的 tzdata 比容器新，或是 GMT+08:00 这种自定义时区）就按此刻的偏移换成 Etc/GMT∓N，
     * 偏移不是整点就退回 UTC。Etc/GMT 的正负号和常说的 UTC+8 相反，Etc/GMT-8 才是东八区。
     */
    internal fun containerZoneId(zone: TimeZone, nowMillis: Long, hasZoneInfo: (String) -> Boolean): String {
        val id = zone.id
        if (id.matches(ZONE_ID_PATTERN) && hasZoneInfo(id)) return id

        val offset = zone.getOffset(nowMillis)
        if (offset == 0 || offset % HOUR_MILLIS != 0) return FALLBACK_ZONE
        val hours = offset / HOUR_MILLIS
        val fixedOffsetId = "Etc/GMT" + (if (hours > 0) "-" else "+") + abs(hours)
        return if (hasZoneInfo(fixedOffsetId)) fixedOffsetId else FALLBACK_ZONE
    }

    // 已经指向目标就不动；否则先建一个临时软链接，再整个换掉 /etc/localtime，容器里的程序不会读到半截状态。
    private fun pointLocaltimeAt(etcDir: File, target: String) {
        val localtime = File(etcDir, "localtime").toPath()
        val targetPath = Paths.get(target)
        if (runCatching { Files.readSymbolicLink(localtime) }.getOrNull() == targetPath) return

        val staging = File(etcDir, ".localtime.maidroid").toPath()
        Files.deleteIfExists(staging)
        Files.createSymbolicLink(staging, targetPath)
        Files.move(staging, localtime, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
