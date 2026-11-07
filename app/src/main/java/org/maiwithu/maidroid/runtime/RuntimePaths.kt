package org.maiwithu.maidroid.runtime

import android.content.Context
import java.io.File

/**
 * 容器运行时在宿主机（应用私有目录）上的文件位置。只描述“东西放在哪”，不做检查、不跑命令。
 *
 * 目录布局（filesDir 下）：
 * - debian/      解包后的 Debian rootfs，也就是容器的根目录；MaiBot 装在 debian/opt/MaiBot
 * - termux/      Termux bootstrap 和 proot 依赖库，termux/tmp 绑定为容器的 /tmp
 * - *.tmp        解包过程中的临时目录，全部解完才改名为正式目录
 * proot 本体及其 loader 不在 filesDir，而是随 APK 安装在 nativeLibraryDir（jniLibs）。
 *
 * NapCat、QQ 官方机器人等平台各自的文件位置见各自包里的 NapCatFiles、QqOfficialAdapterFiles。
 */
internal class RuntimePaths private constructor(
    /** 应用私有的 files 目录；容器和 MaiDroid 自己的文件都在这个分区上，看剩余空间就看它。 */
    val appFilesDir: File,
    val nativeLibraryDir: File
) {
    companion object {
        fun from(context: Context): RuntimePaths {
            val appContext = context.applicationContext
            return RuntimePaths(
                appFilesDir = appContext.filesDir,
                nativeLibraryDir = File(appContext.applicationInfo.nativeLibraryDir)
            )
        }
    }

    // ── 容器根目录与 Termux bootstrap ─────────────────────────────────
    val rootfsDir: File = File(appFilesDir, "debian")
    val stagingRootfsDir: File = File(appFilesDir, "debian.tmp")
    val termuxDir: File = File(appFilesDir, "termux")
    val stagingTermuxDir: File = File(appFilesDir, "termux.tmp")
    val termuxLibDir: File = File(termuxDir, "lib")
    val termuxTmpDir: File = File(termuxDir, "tmp")

    /** 解包完成标记：rootfs 和 bootstrap 各一份，标记在才算解包完整（中途被杀不会留下半成品被当成可用）。 */
    val rootfsMarker: File = File(rootfsDir, ".maidroid-rootfs-ready")
    val bootstrapMarker: File = File(termuxDir, ".maidroid-bootstrap-ready")

    // ── proot：APK 内置的原生库，以及 bootstrap 里的同名工具 ─────────────
    val prootExecutable: File = File(nativeLibraryDir, "libproot_exec.so")
    val prootLoader: File = File(nativeLibraryDir, "libproot_loader.so")
    val prootAndroidShmem: File = File(nativeLibraryDir, "libandroid-shmem.so")
    val prootTalloc: File = File(nativeLibraryDir, "libtalloc.so")
    val termuxProotExecutable: File = File(termuxDir, "bin/proot")
    val termuxProotLoader: File = File(termuxDir, "libexec/proot/loader")
    val termuxProotLoader32: File = File(termuxDir, "libexec/proot/loader32")
    val termuxChroot: File = File(termuxDir, "bin/termux-chroot")
    val termuxAndroidShmem: File = File(termuxLibDir, "libandroid-shmem.so")
    val termuxTalloc: File = File(termuxLibDir, "libtalloc.so.2")

    /** 绑定到容器 /proc 下的假文件，原因见 ProotCommand.ensureFakeProcFiles。 */
    val fakeProcStat: File = File(rootfsDir, "proc/.stat")
    val fakeProcLoadAvg: File = File(rootfsDir, "proc/.loadavg")
    val fakeProcVmStat: File = File(rootfsDir, "proc/.vmstat")
    val fakeProcUptime: File = File(rootfsDir, "proc/.uptime")

    /** /proc/locks 的替身放在容器的 /tmp 下（容器里要能改写它），原因见 InContainerPaths.PROC_LOCKS_STAND_IN。 */
    val fakeProcLocks: File = File(termuxTmpDir, InContainerPaths.PROC_LOCKS_STAND_IN.removePrefix("/tmp/"))

    // ── 容器里的系统依赖 ─────────────────────────────────────────────
    val gitExecutable: File = File(rootfsDir, "usr/bin/git")
    val curlExecutable: File = File(rootfsDir, "usr/bin/curl")
    val caCertificateBundle: File = File(rootfsDir, "etc/ssl/certs/ca-certificates.crt")

    // ── MaiBot（容器内 /opt/MaiBot）─────────────────────────────────────
    val maiBotDir: File = File(rootfsDir, InContainerPaths.MAIBOT_DIR.removePrefix("/"))
    val maiBotGitDir: File = File(maiBotDir, ".git")
    val maiBotEntry: File = File(maiBotDir, "bot.py")
    val maiBotPyproject: File = File(maiBotDir, "pyproject.toml")
    val maiBotPluginsDir: File = File(maiBotDir, "plugins")
    val maiBotEula: File = File(maiBotDir, "EULA.md")
    val maiBotPrivacy: File = File(maiBotDir, "PRIVACY.md")

    /**
     * MaiBot WebUI 的登录凭据（access_token）。2026-11 起 MaiBot 改成多实例宿主，凭据挪到 host/webui.json，
     * 旧位置 data/webui.json 在升级时迁过去并删掉；还没升级的 MaiBot 只有旧位置。按顺序找，先新后旧。
     */
    val maiBotWebUiCredentials: List<File> = listOf(
        File(maiBotDir, "host/webui.json"),
        File(maiBotDir, "data/webui.json")
    )
    val venvDir: File = File(maiBotDir, ".venv")

    /** uv 的下载缓存和 uv 管理的 Python 解释器（容器 /root 下）；装依赖到一半时它们已经占了不少空间。 */
    val uvCacheDir: File = File(rootfsDir, "root/.cache/uv")
    val uvPythonDir: File = File(rootfsDir, "root/.local/share/uv/python")
    val venvConfig: File = File(venvDir, "pyvenv.cfg")
    val venvPythonExecutable: File = File(venvDir, "bin/python")
}
