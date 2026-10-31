package org.maiwithu.maidroid.appupdate

import java.io.File
import org.maiwithu.maidroid.VersionNumber

/**
 * 应用自更新的安装包放在哪：cacheDir/app-update/ 下，下载中是 `MaiDroid-<版本>.apk.part`，
 * 校验通过后改名为 `MaiDroid-<版本>.apk` 交给系统安装器（FileProvider 只共享这个目录）。
 *
 * 文件名用解析后的版本号拼，不用 Release 里的资源名，所以不会带出奇怪的路径字符，清理时也能按版本判断。
 */
internal class AppUpdateFiles(cacheDir: File) {
    val dir = File(cacheDir, DIR_NAME)

    fun partFile(version: VersionNumber): File = File(dir, "$PREFIX$version$APK_SUFFIX$PART_SUFFIX")

    fun apkFile(version: VersionNumber): File = File(dir, "$PREFIX$version$APK_SUFFIX")

    /** 删掉不比当前版本新的安装包（已经装上了）和不认识的文件；更新的版本留着，下次可以接着下或直接装。 */
    fun deleteInstalledOrUnknown(current: VersionNumber?) {
        dir.listFiles()?.forEach { file ->
            val version = versionOf(file.name)
            if (version == null || (current != null && version <= current)) file.delete()
        }
    }

    /** 发现了更新的版本时，别的版本下了一半的包已经没用了，只留 keep 这一版的文件。 */
    fun deleteOtherVersions(keep: VersionNumber) {
        dir.listFiles()?.forEach { file ->
            if (versionOf(file.name) != keep) file.delete()
        }
    }

    companion object {
        const val DIR_NAME = "app-update"
        private const val PREFIX = "MaiDroid-"
        private const val APK_SUFFIX = ".apk"
        private const val PART_SUFFIX = ".part"

        /** 从本类起的文件名里取回版本号，不是这个命名规则的返回 null。 */
        fun versionOf(fileName: String): VersionNumber? {
            if (!fileName.startsWith(PREFIX)) return null
            val rest = fileName.removePrefix(PREFIX).removeSuffix(PART_SUFFIX)
            if (!rest.endsWith(APK_SUFFIX)) return null
            return VersionNumber.parse(rest.removeSuffix(APK_SUFFIX))
        }
    }
}
