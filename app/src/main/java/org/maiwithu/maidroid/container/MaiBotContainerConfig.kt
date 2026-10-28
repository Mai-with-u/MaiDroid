package org.maiwithu.maidroid.container

import android.content.Context
import android.system.Os
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.maiwithu.maidroid.runtime.ContainerPaths
import org.maiwithu.maidroid.runtime.killMatchingProcessesScript
import org.maiwithu.maidroid.runtime.shellQuote

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
class MaiBotContainerConfig private constructor(
    private val appFilesDir: File,
    private val nativeLibraryDir: File
) {
    companion object {
        private const val DEBIAN_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        const val REPOSITORY_URL = "https://github.com/Mai-with-u/MaiBot.git"
        const val GITHUB_RETRY_PER_MIRROR = 3
        private val GITHUB_MIRROR_SPECS = listOf(
            "gitproxy.mrhjx.cn|https://gitproxy.mrhjx.cn",
            "ghproxy.vip|https://ghproxy.vip",
            "raw.githubusercontent.com|",
            "gh-proxy.com|https://gh-proxy.com",
            "v6.gh-proxy.org|https://v6.gh-proxy.org",
            "cdn.gh-proxy.com|https://cdn.gh-proxy.com"
        )
        val GITHUB_MIRROR_DESCRIPTION: String =
            "GitHub 镜像自动重试（每源 ${GITHUB_RETRY_PER_MIRROR} 次）: " +
                GITHUB_MIRROR_SPECS.joinToString(" -> ") { it.substringBefore('|') }
        const val ROOTFS_ASSET = "runtime/debian-rootfs.tar.xz"
        const val BOOTSTRAP_ASSET = "runtime/bootstrap-aarch64.zip"
        const val PROOT_ASSET = "runtime/proot_5.1.107.82_aarch64.deb"
        const val LIBANDROID_SHMEM_ASSET = "runtime/libandroid-shmem_0.7_aarch64.deb"
        const val LIBTALLOC_ASSET = "runtime/libtalloc_2.4.3_aarch64.deb"
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
        const val QQ_OFFICIAL_ADAPTER_REPOSITORY_URL = "https://github.com/Mai-with-u/MaiBot-QQ-Adapter.git"
        const val QQ_OFFICIAL_ADAPTER_DIR_NAME = "MaiBot-QQ-Adapter"
        const val QQ_OFFICIAL_ADAPTER_PLUGIN_ID = "Mai-with-u.qq-adapter"

        // MaiBot 的 requires-python 只写了 >=3.12，没有上限也没有 .python-version，uv 会自动选最新的 Python。
        // 新版 Python 刚发布时，pydantic-core、pyarrow、zstandard 等包在 aarch64 上还没有预编译包，
        // uv 只能退回源码编译，而容器里没有 C 编译器和 Rust，uv sync 会直接失败。
        // 这里把范围压在 3.12~3.13：全新安装时 uv 选其中最新的 3.13（与上游 Dockerfile 一致），
        // 已经装好 3.12 的旧环境也不会被重建。
        const val UV_PYTHON_REQUEST = ">=3.12,<3.14"

        fun from(context: Context): MaiBotContainerConfig {
            val appContext = context.applicationContext
            return MaiBotContainerConfig(
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
    val permissionRepairMarker: File = File(appFilesDir, ".maidroid-runtime-permissions-ready")
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
    val maiBotQqOfficialAdapterDir: File = File(maiBotPluginsDir, QQ_OFFICIAL_ADAPTER_DIR_NAME)
    val sshdExecutable: File = File(rootfsDir, "usr/sbin/sshd")

    fun containerRequirements(): List<ContainerRequirement> = listOf(
        ContainerRequirement(
            name = "Debian rootfs",
            path = rootfsDir.absolutePath,
            ready = rootfsDir.isDirectory && rootfsMarker.isFile,
            hint = "OOBE 需要先解包 $ROOTFS_ASSET"
        ),
        ContainerRequirement(
            name = "Termux bootstrap",
            path = bootstrapMarker.absolutePath,
            ready = bootstrapMarker.isFile,
            hint = "OOBE 需要先解包 $BOOTSTRAP_ASSET"
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

    fun ensureExecutableBits() {
        listOf(
            prootExecutable,
            prootLoader,
            prootAndroidShmem,
            prootTalloc,
            termuxProotExecutable,
            termuxProotLoader,
            termuxProotLoader32,
            File(termuxDir, "bin/termux-chroot")
        ).forEach(::chmodExecutable)
    }

    fun ensureWritableRuntimePaths() {
        if (!rootfsDir.isDirectory) return
        makeWritableRecursively(rootfsDir)
        permissionRepairMarker.writeText("ok")
    }

    fun prootEnvironment(): Map<String, String> {
        termuxTmpDir.mkdirs()
        return buildMap {
            put("PATH", DEBIAN_PATH)
            put("HOME", "/root")
            put("USER", "root")
            put("LOGNAME", "root")
            put("SHELL", "/bin/sh")
            put("TERM", "xterm-256color")
            put("PROOT_LOADER", prootLoader.absolutePath)
            put("PROOT_TMP_DIR", termuxTmpDir.absolutePath)
            put("TMPDIR", termuxTmpDir.absolutePath)
            put("LD_LIBRARY_PATH", "${nativeLibraryDir.absolutePath}:${termuxLibDir.absolutePath}")
        }
    }

    // 克隆 -> 改名 -> 立刻读一次 HEAD 确认仓库可用。
    // proot 的 --link2symlink 会把 git 落盘 packfile 用的硬链接改成指向绝对路径的符号链接，
    // 先克隆到 MaiBot.tmp 再改名后，这些链接还指着旧路径，git 就读不到对象（fatal: bad object HEAD），
    // 在线更新和 MaiBot 自带的更新检查都会失败。core.createObject=rename 让 git 直接用重命名落盘，
    // 不产生链接；该配置会写进仓库的 .git/config，之后容器里任何 git fetch 也沿用。
    fun cloneCommand(): List<String> = shellCommand(
        script = listOf(
            githubMirrorScript(),
            """
            set -e
            mkdir -p /opt
            rm -rf ${shellQuote("$MAIBOT_CONTAINER_DIR.tmp")}
            maidroid_github_clone ${shellQuote(REPOSITORY_URL)} ${shellQuote("$MAIBOT_CONTAINER_DIR.tmp")} -c core.createObject=rename --depth=1 --single-branch
            rm -rf ${shellQuote(MAIBOT_CONTAINER_DIR)}
            mv ${shellQuote("$MAIBOT_CONTAINER_DIR.tmp")} ${shellQuote(MAIBOT_CONTAINER_DIR)}
            if ! git -C ${shellQuote(MAIBOT_CONTAINER_DIR)} cat-file -e 'HEAD^{commit}'; then
                printf "[GitHub] cloned repository is unreadable after move: %s\n" ${shellQuote(MAIBOT_CONTAINER_DIR)}
                exit 1
            fi
            """.trimIndent()
        ).joinToString("\n"),
        workingDirectory = "/"
    )

    fun installUvCommand(): List<String> = shellCommand(
        script = "curl -LsSf https://astral.sh/uv/install.sh | sh",
        workingDirectory = "/"
    )

    fun configureDnsCommand(): List<String> = shellCommand(
        script = configureDnsScript(),
        workingDirectory = "/"
    )

    fun configureAptMirrorCommand(): List<String> = shellCommand(
        script = configureAptMirrorScript(),
        workingDirectory = "/"
    )

    fun setupStorageBridgeCommand(): List<String> = shellCommand(
        script = setupStorageBridgeScript(),
        workingDirectory = "/"
    )

    fun installSystemDependenciesCommand(): List<String> = shellCommand(
        script = "export DEBIAN_FRONTEND=noninteractive; " +
            "apt-get -o Acquire::http::Pipeline-Depth=0 update && " +
            "apt-get install -y --no-install-recommends git ca-certificates curl",
        workingDirectory = "/"
    )

    fun syncCommand(): List<String> = shellCommand(
        script = "${uvPrelude()}; cd ${shellQuote(MAIBOT_CONTAINER_DIR)} && uv sync",
        workingDirectory = MAIBOT_CONTAINER_DIR
    )

    fun launchCommand(): List<String> = shellCommand(
        script = "${uvPrelude()}; cd ${shellQuote(MAIBOT_CONTAINER_DIR)} && " +
            "if command -v script >/dev/null 2>&1; then " +
            "script -q -c ${shellQuote("uv run python bot.py")} /dev/null; " +
            "else uv run python bot.py; fi",
        workingDirectory = MAIBOT_CONTAINER_DIR
    )

    fun cleanupMaiBotCommand(): List<String> = shellCommand(
        script = listOf(
            "set +e",
            killMatchingProcessesScript(
                pattern = "uv run python bot.py|python3 bot.py|src[.]plugin_runtime[.]runner[.]runner_main",
                processName = "stale MaiBot process",
                logPrefix = "[ProcessManager] "
            ),
            "exit 0"
        ).joinToString("\n"),
        workingDirectory = "/"
    )

    fun isNapCatInstalled(): Boolean =
        napCatReadyMarker.isFile && napCatOneBotConfig.isFile && maiBotNapCatAdapterConfig.isFile

    /**
     * 和 MaiBot 一样按清单里的插件 ID 找 QQ 官方机器人适配器：用户也可能在 MaiBot WebUI 的插件市场装过
     * （目录名是 Mai-with-u_qq-adapter），只认固定目录名会再装一份，同一插件 ID 出现两份会冲突。
     * 先看 MaiDroid 自己的目录，再扫其他目录；清单和入口都在才算装好，克隆了一半的临时目录以点开头会被跳过。
     */
    fun findQqOfficialAdapterDir(): File? =
        findPluginDirById(maiBotPluginsDir, QQ_OFFICIAL_ADAPTER_PLUGIN_ID, QQ_OFFICIAL_ADAPTER_DIR_NAME)

    fun isQqOfficialAdapterInstalled(): Boolean = findQqOfficialAdapterDir() != null

    // 已安装就用找到的那份目录里的配置，还没装时指向 MaiDroid 将要安装的位置。
    fun qqOfficialAdapterConfigFile(): File =
        File(findQqOfficialAdapterDir() ?: maiBotQqOfficialAdapterDir, "config.toml")

    /**
     * 安装 QQ 官方机器人适配器：已装好就直接退出 -> 经 GitHub 镜像克隆到 plugins 下的点开头临时目录 ->
     * 改名为正式目录。临时目录以点开头，MaiBot 扫插件和热重载时会跳过它，不会把克隆了一半的目录当插件加载；
     * core.createObject=rename 避免 proot 把 packfile 硬链接变成绝对路径符号链接，改名后仓库仍可读。
     * 凭据由宿主侧直接写 config.toml，不经过命令行，避免 AppSecret 出现在进程参数和日志里。
     */
    fun qqOfficialAdapterInstallCommand(): List<String> {
        val pluginsDir = "$MAIBOT_CONTAINER_DIR/plugins"
        val adapterDir = "$pluginsDir/$QQ_OFFICIAL_ADAPTER_DIR_NAME"
        val stagingDir = "$pluginsDir/.$QQ_OFFICIAL_ADAPTER_DIR_NAME.tmp"
        return shellCommand(
            script = listOf(
                githubMirrorScript(),
                """
                set -e
                if [ -f ${shellQuote("$adapterDir/_manifest.json")} ] && [ -f ${shellQuote("$adapterDir/plugin.py")} ]; then
                    printf "[QQ 官方机器人] 适配器已存在，跳过下载: %s\n" ${shellQuote(adapterDir)}
                    exit 0
                fi
                mkdir -p ${shellQuote(pluginsDir)}
                rm -rf ${shellQuote(stagingDir)}
                maidroid_github_clone ${shellQuote(QQ_OFFICIAL_ADAPTER_REPOSITORY_URL)} ${shellQuote(stagingDir)} -c core.createObject=rename --depth=1 --single-branch
                rm -rf ${shellQuote(adapterDir)}
                mv ${shellQuote(stagingDir)} ${shellQuote(adapterDir)}
                printf "[QQ 官方机器人] 适配器已下载到 %s\n" ${shellQuote(adapterDir)}
                """.trimIndent()
            ).joinToString("\n"),
            workingDirectory = "/"
        )
    }

    fun agreementEnvironment(): Map<String, String> {
        val eulaHash = md5(eulaFile) ?: return emptyMap()
        val privacyHash = md5(privacyFile) ?: return emptyMap()
        return mapOf(
            "EULA_AGREE" to eulaHash,
            "PRIVACY_AGREE" to privacyHash
        )
    }

    fun prootCommand(
        workingDirectory: String = MAIBOT_CONTAINER_DIR,
        vararg args: String
    ): List<String> {
        termuxTmpDir.mkdirs()
        ensureFakeProcFiles()
        return listOf(
            prootExecutable.absolutePath,
            "--link2symlink",
            "-0",
            "-r", rootfsDir.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "${fakeProcStat.absolutePath}:/proc/stat",
            "-b", "${fakeProcLoadAvg.absolutePath}:/proc/loadavg",
            "-b", "${fakeProcVmStat.absolutePath}:/proc/vmstat",
            "-b", "${fakeProcUptime.absolutePath}:/proc/uptime",
            "-b", "/sys",
            "-b", "/sdcard",
            "-b", "${termuxTmpDir.absolutePath}:/tmp",
            "-w", workingDirectory
        ) + args
    }

    private fun isProotRuntimeReady(): Boolean =
        prootExecutable.isFile && prootExecutable.canExecute() &&
            prootLoader.isFile && prootLoader.canExecute() &&
            prootAndroidShmem.isFile && prootAndroidShmem.canExecute() &&
            prootTalloc.isFile && prootTalloc.canExecute()

    fun areTermuxProotDependenciesReady(): Boolean =
        termuxAndroidShmem.isFile && termuxTalloc.isFile

    private fun chmodExecutable(file: File) {
        if (!file.isFile) return
        runCatching { Os.chmod(file.absolutePath, 0b111101101) }
        file.setReadable(true, false)
        file.setExecutable(true, false)
    }

    private fun makeWritableRecursively(file: File) {
        if (Files.isSymbolicLink(file.toPath())) return

        file.setReadable(true, true)
        file.setWritable(true, true)
        if (file.isDirectory || file.canExecute()) {
            file.setExecutable(true, true)
        }
        if (file.isDirectory) {
            file.listFiles()?.forEach(::makeWritableRecursively)
        }
    }

    /** 在容器里用 /bin/sh 执行一段脚本，脚本前会先重置 PATH、HOME 等 Debian 环境变量。 */
    internal fun shellCommand(
        script: String,
        workingDirectory: String
    ): List<String> =
        prootCommand(workingDirectory, "/bin/sh", "-c", "${shellPrelude()} $script")

    private fun shellPrelude(): String =
        "unset LD_LIBRARY_PATH; " +
            "export PATH=${shellQuote(DEBIAN_PATH)}; " +
            "export HOME=/root USER=root LOGNAME=root SHELL=/bin/sh TMPDIR=/tmp; "

    private fun configureDnsScript(): String =
        """
        set -e
        if [ -e /etc/resolv.conf ] || [ -L /etc/resolv.conf ]; then
            if [ ! -e /etc/resolv.conf.maidroid.bak ] && [ ! -L /etc/resolv.conf.maidroid.bak ]; then
                cp -P /etc/resolv.conf /etc/resolv.conf.maidroid.bak 2>/dev/null || true
            fi
        fi
        rm -f /etc/resolv.conf
        cat > /etc/resolv.conf <<'EOF'
        # Generated by MaiDroid OOBE.
        nameserver 223.5.5.5
        nameserver 114.114.114.114
        nameserver 8.8.8.8
        nameserver 1.1.1.1
        options timeout:2 attempts:3 rotate
        EOF
        chmod 0644 /etc/resolv.conf
        """.trimIndent()

    private fun configureAptMirrorScript(): String =
        """
        set -e
        codename=bookworm
        if [ -r /etc/os-release ]; then
            . /etc/os-release
            if [ -n "${'$'}{VERSION_CODENAME:-}" ]; then
                codename="${'$'}VERSION_CODENAME"
            fi
        fi
        mkdir -p /etc/apt/sources.list.d /etc/apt/apt.conf.d
        if [ -f /etc/apt/sources.list ] && [ ! -f /etc/apt/sources.list.maidroid.bak ]; then
            cp /etc/apt/sources.list /etc/apt/sources.list.maidroid.bak
        fi
        for source_file in /etc/apt/sources.list.d/*.list /etc/apt/sources.list.d/*.sources; do
            [ -e "${'$'}source_file" ] || continue
            case "${'$'}source_file" in
                *.maidroid.disabled) ;;
                *) mv "${'$'}source_file" "${'$'}source_file.maidroid.disabled" ;;
            esac
        done
        cat > /etc/apt/sources.list <<EOF
        # Generated by MaiDroid OOBE.
        deb http://mirrors.tuna.tsinghua.edu.cn/debian ${'$'}codename main contrib
        deb http://mirrors.tuna.tsinghua.edu.cn/debian ${'$'}codename-updates main contrib
        deb http://mirrors.tuna.tsinghua.edu.cn/debian-security ${'$'}codename-security main contrib
        EOF
        cat > /etc/apt/apt.conf.d/99maidroid-network <<'EOF'
        Acquire::Retries "3";
        Acquire::http::Pipeline-Depth "0";
        EOF
        apt-get clean
        rm -rf /var/lib/apt/lists/*
        mkdir -p /var/lib/apt/lists/partial
        """.trimIndent()

    private fun setupStorageBridgeScript(): String =
        """
        set -e
        mkdir -p /root/storage
        ln -sfn /sdcard /root/storage/shared
        ln -sfn /sdcard/Download /root/storage/downloads
        ln -sfn /sdcard/DCIM /root/storage/dcim
        ln -sfn /sdcard/Pictures /root/storage/pictures
        ln -sfn /sdcard/Movies /root/storage/movies
        ln -sfn /sdcard/Music /root/storage/music
        """.trimIndent()

    /**
     * 定义 maidroid_github_clone / maidroid_github_fetch / maidroid_github_curl 三个 shell 函数：
     * 依次尝试每个 GitHub 镜像，每个镜像重试 GITHUB_RETRY_PER_MIRROR 次，直到成功为止。
     */
    internal fun githubMirrorScript(): String {
        val mirrorSpecArguments = GITHUB_MIRROR_SPECS.joinToString(" \\\n                ") {
            shellQuote(it)
        }
        return """
        maidroid_github_mirror_specs() {
            printf '%s\n' \
                $mirrorSpecArguments
            if [ -n "${'$'}{target_proxy:-}" ]; then
                printf "%s\n" "custom|${'$'}target_proxy"
            fi
        }

        maidroid_github_candidate_url() {
            mirror_prefix="${'$'}1"
            original_url="${'$'}2"
            if [ -n "${'$'}mirror_prefix" ]; then
                printf "%s/%s\n" "${'$'}{mirror_prefix%/}" "${'$'}original_url"
            else
                printf "%s\n" "${'$'}original_url"
            fi
        }

        maidroid_github_clone() {
            repo_url="${'$'}1"
            clone_dest="${'$'}2"
            shift 2
            for mirror_spec in ${'$'}(maidroid_github_mirror_specs); do
                mirror_name="${'$'}{mirror_spec%%|*}"
                mirror_prefix="${'$'}{mirror_spec#*|}"
                candidate_url="${'$'}(maidroid_github_candidate_url "${'$'}mirror_prefix" "${'$'}repo_url")"
                attempt=1
                while [ "${'$'}attempt" -le $GITHUB_RETRY_PER_MIRROR ]; do
                    rm -rf "${'$'}clone_dest"
                    printf "[GitHub] git clone via %s (attempt %s/$GITHUB_RETRY_PER_MIRROR): %s\n" "${'$'}mirror_name" "${'$'}attempt" "${'$'}candidate_url"
                    if git clone "${'$'}@" "${'$'}candidate_url" "${'$'}clone_dest"; then
                        printf "[GitHub] git clone succeeded via %s\n" "${'$'}mirror_name"
                        return 0
                    fi
                    rm -rf "${'$'}clone_dest"
                    attempt=${'$'}((attempt + 1))
                    sleep 1
                done
                printf "[GitHub] git clone failed via %s, switching mirror\n" "${'$'}mirror_name"
            done
            printf "[GitHub] git clone failed after all mirrors: %s\n" "${'$'}repo_url"
            return 1
        }

        maidroid_github_fetch() {
            repo_url="${'$'}1"
            fetch_refspec="${'$'}2"
            shift 2
            for mirror_spec in ${'$'}(maidroid_github_mirror_specs); do
                mirror_name="${'$'}{mirror_spec%%|*}"
                mirror_prefix="${'$'}{mirror_spec#*|}"
                candidate_url="${'$'}(maidroid_github_candidate_url "${'$'}mirror_prefix" "${'$'}repo_url")"
                attempt=1
                while [ "${'$'}attempt" -le $GITHUB_RETRY_PER_MIRROR ]; do
                    printf "[GitHub] git fetch via %s (attempt %s/$GITHUB_RETRY_PER_MIRROR): %s\n" "${'$'}mirror_name" "${'$'}attempt" "${'$'}candidate_url"
                    if git fetch "${'$'}@" "${'$'}candidate_url" "${'$'}fetch_refspec"; then
                        printf "[GitHub] git fetch succeeded via %s\n" "${'$'}mirror_name"
                        return 0
                    fi
                    attempt=${'$'}((attempt + 1))
                    sleep 1
                done
                printf "[GitHub] git fetch failed via %s, switching mirror\n" "${'$'}mirror_name"
            done
            printf "[GitHub] git fetch failed after all mirrors: %s\n" "${'$'}repo_url"
            return 1
        }

        maidroid_github_curl() {
            output_path="${'$'}1"
            source_url="${'$'}2"
            label="${'$'}3"
            if [ -z "${'$'}label" ]; then
                label="${'$'}source_url"
            fi
            temp_path="${'$'}output_path.tmp"
            for mirror_spec in ${'$'}(maidroid_github_mirror_specs); do
                mirror_name="${'$'}{mirror_spec%%|*}"
                mirror_prefix="${'$'}{mirror_spec#*|}"
                candidate_url="${'$'}(maidroid_github_candidate_url "${'$'}mirror_prefix" "${'$'}source_url")"
                attempt=1
                while [ "${'$'}attempt" -le $GITHUB_RETRY_PER_MIRROR ]; do
                    rm -f "${'$'}temp_path"
                    printf "[GitHub] download %s via %s (attempt %s/$GITHUB_RETRY_PER_MIRROR): %s\n" "${'$'}label" "${'$'}mirror_name" "${'$'}attempt" "${'$'}candidate_url"
                    if curl -fL --connect-timeout 20 -o "${'$'}temp_path" "${'$'}candidate_url"; then
                        mv "${'$'}temp_path" "${'$'}output_path"
                        printf "[GitHub] download succeeded via %s\n" "${'$'}mirror_name"
                        return 0
                    fi
                    rm -f "${'$'}temp_path"
                    attempt=${'$'}((attempt + 1))
                    sleep 1
                done
                printf "[GitHub] download failed via %s, switching mirror\n" "${'$'}mirror_name"
            done
            printf "[GitHub] download failed after all mirrors: %s\n" "${'$'}source_url"
            return 1
        }
        """.trimIndent()
    }

    private fun ensureFakeProcFiles() {
        val bootTimeSeconds = (System.currentTimeMillis() / 1000L) - 60L
        writeIfChanged(
            fakeProcStat,
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
        writeIfChanged(fakeProcLoadAvg, "0.00 0.00 0.00 1/1 1\n")
        writeIfChanged(
            fakeProcVmStat,
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
        writeIfChanged(fakeProcUptime, "60.00 60.00\n")
    }

    private fun writeIfChanged(file: File, content: String) {
        file.parentFile?.mkdirs()
        if (!file.isFile || file.readText() != content) {
            file.writeText(content)
        }
        file.setReadable(true, false)
        file.setWritable(true, true)
    }

    // 同一份环境变量同时作用于 uv sync、uv run 和在线更新：先限定 Python 版本，再跳过 dev 依赖，
    // 保证“安装依赖”和“启动时 uv run 自动同步”用同一套参数，不会因为参数不一致反复重建 .venv。
    // dev 组里的 zstandard 等测试依赖在运行时用不到，却可能触发源码编译。
    private fun uvPrelude(): String =
        "export UV_PYTHON=${shellQuote(UV_PYTHON_REQUEST)}; " +
            "export UV_NO_DEV=1; " +
            "export UV_LINK_MODE=copy; " +
            "export PATH=\"\$HOME/.local/bin:\$PATH\"; " +
            "if [ -f \"\$HOME/.local/bin/env\" ]; then . \"\$HOME/.local/bin/env\"; fi"

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
