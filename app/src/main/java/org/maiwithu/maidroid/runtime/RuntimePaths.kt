package org.maiwithu.maidroid.runtime

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.maiwithu.maidroid.platform.qqofficial.QqOfficialAdapterScripts

// MaiBot 扫插件时跳过的目录：数据目录、字节码缓存，以及点开头的目录（含安装用的临时目录）。
private val ReservedPluginDirNames = setOf("data", "__pycache__")

/**
 * 在 MaiBot 的 plugins 目录里按清单 ID 找插件目录（大小写不敏感，与 MaiBot 的查找一致）：
 * 优先看 preferredDirName 这个目录，再扫其他目录；清单和 plugin.py 都在才算数。
 */
internal fun findPluginDirById(pluginsDir: File, pluginId: String, preferredDirName: String): File? {
    val candidates = pluginsDir.listFiles()
        ?.filter { dir ->
            dir.isDirectory && !dir.name.startsWith(".") &&
                dir.name.lowercase() !in ReservedPluginDirNames
        }
        ?.sortedWith(compareByDescending<File> { it.name == preferredDirName }.thenBy { it.name })
        ?: return null
    return candidates.firstOrNull { dir ->
        val manifest = File(dir, "_manifest.json")
        File(dir, "plugin.py").isFile && manifest.isFile &&
            runCatching {
                Json.parseToJsonElement(manifest.readText()).jsonObject["id"]?.jsonPrimitive?.contentOrNull
            }.getOrNull().equals(pluginId, ignoreCase = true)
    }
}

data class ContainerRequirement(
    val name: String,
    val path: String,
    val ready: Boolean,
    val hint: String
)

/**
 * Single source of truth for the Android-hosted MaiBot runtime layout.
 *
 * Commands mirror installation.md, but are executed inside the configured
 * proot container instead of asking the user to run them manually.
 */
class RuntimePaths private constructor(
    private val appFilesDir: File,
    val nativeLibraryDir: File
) {
    companion object {
        const val ROOTFS_DIR = "debian"
        const val STAGING_ROOTFS_DIR = "debian.tmp"
        const val TERMUX_DIR = "termux"
        const val STAGING_TERMUX_DIR = "termux.tmp"
        const val MAIBOT_CONTAINER_DIR = ContainerPaths.MAIBOT_DIR
        const val MAIBOT_HOST_DIR = "opt/MaiBot"
        const val PROOT_BIN = "bin/proot"
        const val PROOT_NATIVE_LIB = "libproot_exec.so"
        const val PROOT_LOADER_NATIVE_LIB = "libproot_loader.so"
        const val PROOT_ANDROID_SHMEM_LIB = "libandroid-shmem.so"
        const val PROOT_TALLOC_LIB = "libtalloc.so"
        const val TERMUX_PROOT_LOADER = "libexec/proot/loader"
        const val TERMUX_PROOT_LOADER_32 = "libexec/proot/loader32"
        const val WEB_UI_URL = "http://127.0.0.1:8001/"

        fun from(context: Context): RuntimePaths {
            val appContext = context.applicationContext
            return RuntimePaths(
                appFilesDir = appContext.filesDir,
                nativeLibraryDir = File(appContext.applicationInfo.nativeLibraryDir)
            )
        }
    }

    val rootfsDir: File = File(appFilesDir, ROOTFS_DIR)
    val stagingRootfsDir: File = File(appFilesDir, STAGING_ROOTFS_DIR)
    val termuxDir: File = File(appFilesDir, TERMUX_DIR)
    val stagingTermuxDir: File = File(appFilesDir, STAGING_TERMUX_DIR)
    val prootExecutable: File = File(nativeLibraryDir, PROOT_NATIVE_LIB)
    val prootLoader: File = File(nativeLibraryDir, PROOT_LOADER_NATIVE_LIB)
    val prootAndroidShmem: File = File(nativeLibraryDir, PROOT_ANDROID_SHMEM_LIB)
    val prootTalloc: File = File(nativeLibraryDir, PROOT_TALLOC_LIB)
    val termuxProotExecutable: File = File(termuxDir, PROOT_BIN)
    val termuxProotLoader: File = File(termuxDir, TERMUX_PROOT_LOADER)
    val termuxProotLoader32: File = File(termuxDir, TERMUX_PROOT_LOADER_32)
    val termuxChroot: File = File(termuxDir, "bin/termux-chroot")
    val termuxLibDir: File = File(termuxDir, "lib")
    val termuxAndroidShmem: File = File(termuxLibDir, PROOT_ANDROID_SHMEM_LIB)
    val termuxTalloc: File = File(termuxLibDir, "libtalloc.so.2")
    val termuxTmpDir: File = File(termuxDir, "tmp")
    val maiBotDir: File = File(rootfsDir, MAIBOT_HOST_DIR)
    val maiBotGitDir: File = File(maiBotDir, ".git")
    val botEntry: File = File(maiBotDir, "bot.py")
    val pyproject: File = File(maiBotDir, "pyproject.toml")
    val uvLock: File = File(maiBotDir, "uv.lock")
    val venvDir: File = File(maiBotDir, ".venv")
    val venvConfig: File = File(venvDir, "pyvenv.cfg")
    val venvPythonExecutable: File = File(venvDir, "bin/python")
    val eulaFile: File = File(maiBotDir, "EULA.md")
    val privacyFile: File = File(maiBotDir, "PRIVACY.md")
    val userUvExecutable: File = File(rootfsDir, "root/.local/bin/uv")
    val rootfsMarker: File = File(rootfsDir, ".maidroid-rootfs-ready")
    val bootstrapMarker: File = File(termuxDir, ".maidroid-bootstrap-ready")
    val installedMarker: File = File(appFilesDir, ".maidroid-runtime-ready")
    val gitExecutable: File = File(rootfsDir, "usr/bin/git")
    val curlExecutable: File = File(rootfsDir, "usr/bin/curl")
    val caCertificateBundle: File = File(rootfsDir, "etc/ssl/certs/ca-certificates.crt")
    val fakeProcStat: File = File(rootfsDir, "proc/.stat")
    val fakeProcLoadAvg: File = File(rootfsDir, "proc/.loadavg")
    val fakeProcVmStat: File = File(rootfsDir, "proc/.vmstat")
    val fakeProcUptime: File = File(rootfsDir, "proc/.uptime")
    val napCatDir: File = File(rootfsDir, "root/napcat")
    val napCatConfigDir: File = File(napCatDir, "config")
    val napCatOneBotConfig: File = File(napCatConfigDir, "onebot11.json")
    val napCatWebUiConfig: File = File(napCatConfigDir, "webui.json")
    val napCatQrCodeFile: File = File(napCatDir, "cache/qrcode.png")
    val napCatTokenFile: File = File(rootfsDir, "root/.maidroid/napcat_token")
    val napCatReadyMarker: File = File(rootfsDir, "root/.maidroid/napcat_ready")
    val napCatLauncher: File = File(rootfsDir, "root/launcher.sh")
    val maiBotNapCatAdapterDir: File = File(maiBotDir, "plugins/MaiBot-Napcat-Adapter")
    val maiBotNapCatAdapterConfig: File = File(maiBotNapCatAdapterDir, "config.toml")
    val maiBotPluginsDir: File = File(maiBotDir, "plugins")
    // MaiDroid 自己安装时用的目录；判断是否已安装、读写配置都以 findQqOfficialAdapterDir 找到的目录为准。
    val maiBotQqOfficialAdapterDir: File = File(maiBotPluginsDir, QqOfficialAdapterScripts.DIR_NAME)
    val sshdExecutable: File = File(rootfsDir, "usr/sbin/sshd")

    fun containerRequirements(): List<ContainerRequirement> = listOf(
        ContainerRequirement(
            name = "Debian rootfs",
            path = rootfsDir.absolutePath,
            ready = rootfsDir.isDirectory && rootfsMarker.isFile,
            hint = "OOBE 需要先解包 ${RuntimeAssets.ROOTFS}"
        ),
        ContainerRequirement(
            name = "Termux bootstrap",
            path = bootstrapMarker.absolutePath,
            ready = bootstrapMarker.isFile,
            hint = "OOBE 需要先解包 ${RuntimeAssets.BOOTSTRAP}"
        ),
        ContainerRequirement(
            name = "proot",
            path = prootExecutable.absolutePath,
            ready = isProotRuntimeReady(),
            hint = "APK 内置 proot 启动器和 loader 后才能进入容器"
        ),
        ContainerRequirement(
            name = "Git",
            path = gitExecutable.absolutePath,
            ready = gitExecutable.isFile && gitExecutable.canExecute(),
            hint = "installation.md 要求容器内可用 Git"
        ),
        ContainerRequirement(
            name = "curl / CA",
            path = curlExecutable.absolutePath,
            ready = curlExecutable.isFile && curlExecutable.canExecute() && caCertificateBundle.isFile,
            hint = "需要 curl 安装 uv，并需要 CA 证书访问 HTTPS"
        )
    )

    fun launchRequirements(): List<ContainerRequirement> =
        containerRequirements() + listOf(
            ContainerRequirement(
                name = "MaiBot",
                path = botEntry.absolutePath,
                ready = botEntry.isFile && pyproject.isFile,
                hint = "需要按 installation.md 克隆 MaiBot 仓库"
            ),
            ContainerRequirement(
                name = "uv dependencies",
                path = venvPythonExecutable.absolutePath,
                ready = isDependencyReady(),
                hint = "需要在 MaiBot 目录执行 uv sync"
            )
        )

    fun isContainerReady(): Boolean = containerRequirements().all { it.ready }
    fun isBaseRuntimeReady(): Boolean =
        rootfsMarker.isFile && bootstrapMarker.isFile &&
            areTermuxProotDependenciesReady() && isProotRuntimeReady()

    fun isSystemDependencyReady(): Boolean =
        gitExecutable.isFile && gitExecutable.canExecute() &&
            curlExecutable.isFile && curlExecutable.canExecute() &&
            caCertificateBundle.isFile

    fun isSourceReady(): Boolean = botEntry.isFile && pyproject.isFile
    // uv sync 一开始就会建好 .venv 的壳（pyvenv.cfg 和 python 链接），依赖装到一半失败时壳还留着。
    // 所以除了壳之外，还要确认 site-packages 里真有包，否则安装流程会把失败的 uv sync 当成已完成并跳过，
    // 接着启动 MaiBot 时又在后台重新编译依赖，界面就一直停在“等待 WebUI 启动”。
    fun isDependencyReady(): Boolean =
        venvConfig.isFile &&
            (Files.isSymbolicLink(venvPythonExecutable.toPath()) ||
                (venvPythonExecutable.isFile && venvPythonExecutable.canExecute())) &&
            hasInstalledPackages()

    private fun hasInstalledPackages(): Boolean =
        File(venvDir, "lib")
            .listFiles { file -> file.isDirectory && file.name.startsWith("python") }
            ?.any { pythonDir -> File(pythonDir, "site-packages").list()?.isNotEmpty() == true }
            ?: false
    fun hasAgreementFiles(): Boolean = eulaFile.isFile && privacyFile.isFile

    fun isNapCatInstalled(): Boolean =
        napCatReadyMarker.isFile && napCatOneBotConfig.isFile && maiBotNapCatAdapterConfig.isFile

    /**
     * 和 MaiBot 一样按清单里的插件 ID 找 QQ 官方机器人适配器：用户也可能在 MaiBot WebUI 的插件市场装过
     * （目录名是 Mai-with-u_qq-adapter），只认固定目录名会再装一份，同一插件 ID 出现两份会冲突。
     * 先看 MaiDroid 自己的目录，再扫其他目录；清单和入口都在才算装好，克隆了一半的临时目录以点开头会被跳过。
     */
    fun findQqOfficialAdapterDir(): File? =
        findPluginDirById(maiBotPluginsDir, QqOfficialAdapterScripts.PLUGIN_ID, QqOfficialAdapterScripts.DIR_NAME)

    fun isQqOfficialAdapterInstalled(): Boolean = findQqOfficialAdapterDir() != null

    // 已安装就用找到的那份目录里的配置，还没装时指向 MaiDroid 将要安装的位置。
    fun qqOfficialAdapterConfigFile(): File =
        File(findQqOfficialAdapterDir() ?: maiBotQqOfficialAdapterDir, "config.toml")

    fun agreementEnvironment(): Map<String, String> {
        val eulaHash = md5(eulaFile) ?: return emptyMap()
        val privacyHash = md5(privacyFile) ?: return emptyMap()
        return mapOf(
            "EULA_AGREE" to eulaHash,
            "PRIVACY_AGREE" to privacyHash
        )
    }

    fun isProotRuntimeReady(): Boolean =
        prootExecutable.isFile && prootExecutable.canExecute() &&
            prootLoader.isFile && prootLoader.canExecute() &&
            prootAndroidShmem.isFile && prootAndroidShmem.canExecute() &&
            prootTalloc.isFile && prootTalloc.canExecute()

    fun areTermuxProotDependenciesReady(): Boolean =
        termuxAndroidShmem.isFile && termuxTalloc.isFile

    private fun md5(file: File): String? {
        if (!file.isFile) return null
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
