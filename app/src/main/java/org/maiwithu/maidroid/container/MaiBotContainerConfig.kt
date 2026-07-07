package org.maiwithu.maidroid.container

import android.content.Context
import android.system.Os
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

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
        const val MAIBOT_CONTAINER_DIR = "/opt/MaiBot"
        const val MAIBOT_HOST_DIR = "opt/MaiBot"
        const val PROOT_BIN = "bin/proot"
        const val PROOT_NATIVE_LIB = "libproot_exec.so"
        const val PROOT_LOADER_NATIVE_LIB = "libproot_loader.so"
        const val PROOT_ANDROID_SHMEM_LIB = "libandroid-shmem.so"
        const val PROOT_TALLOC_LIB = "libtalloc.so"
        const val TERMUX_PROOT_LOADER = "libexec/proot/loader"
        const val TERMUX_PROOT_LOADER_32 = "libexec/proot/loader32"
        const val SOCKET_NAME = "maidroid_ipc_socket"
        const val WEB_UI_URL = "http://127.0.0.1:8001/"

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
    val napCatQrCodeFile: File = File(napCatDir, "cache/qrcode.png")
    val napCatTokenFile: File = File(rootfsDir, "root/.maidroid/napcat_token")
    val napCatReadyMarker: File = File(rootfsDir, "root/.maidroid/napcat_ready")
    val napCatLauncher: File = File(rootfsDir, "root/launcher.sh")
    val maiBotNapCatAdapterDir: File = File(maiBotDir, "plugins/MaiBot-Napcat-Adapter")
    val maiBotNapCatAdapterConfig: File = File(maiBotNapCatAdapterDir, "config.toml")

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
    fun isDependencyReady(): Boolean =
        venvConfig.isFile &&
            (Files.isSymbolicLink(venvPythonExecutable.toPath()) ||
                (venvPythonExecutable.isFile && venvPythonExecutable.canExecute()))
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

    fun cloneCommand(): List<String> = shellCommand(
        script = """
        ${githubMirrorScript()}
        set -e
        mkdir -p /opt
        rm -rf ${shellQuote("$MAIBOT_CONTAINER_DIR.tmp")}
        maidroid_github_clone ${shellQuote(REPOSITORY_URL)} ${shellQuote("$MAIBOT_CONTAINER_DIR.tmp")} --depth=1 --single-branch
        rm -rf ${shellQuote(MAIBOT_CONTAINER_DIR)}
        mv ${shellQuote("$MAIBOT_CONTAINER_DIR.tmp")} ${shellQuote(MAIBOT_CONTAINER_DIR)}
        """.trimIndent(),
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
        script = """
        set +e
        ps -eo pid=,args= | awk '
            /uv run python bot.py|python3 bot.py|src[.]plugin_runtime[.]runner[.]runner_main/ &&
            !/awk/ { print ${'$'}1 }
        ' | while read pid; do
            [ -n "${'$'}pid" ] || continue
            printf "[ProcessManager] stopping stale MaiBot process %s\n" "${'$'}pid"
            kill "${'$'}pid" 2>/dev/null || true
        done
        sleep 1
        ps -eo pid=,args= | awk '
            /uv run python bot.py|python3 bot.py|src[.]plugin_runtime[.]runner[.]runner_main/ &&
            !/awk/ { print ${'$'}1 }
        ' | while read pid; do
            [ -n "${'$'}pid" ] || continue
            printf "[ProcessManager] force stopping stale MaiBot process %s\n" "${'$'}pid"
            kill -9 "${'$'}pid" 2>/dev/null || true
        done
        exit 0
        """.trimIndent(),
        workingDirectory = "/"
    )

    fun napCatInstallCommand(token: String, targetProxy: String? = null): List<String> =
        shellCommand(
            script = napCatInstallScript(token = token, targetProxy = targetProxy),
            workingDirectory = "/root"
        )

    fun napCatPrepareRuntimeCommand(): List<String> = shellCommand(
        script = napCatPrepareRuntimeScript(),
        workingDirectory = "/root"
    )

    fun cleanupNapCatCommand(): List<String> = shellCommand(
        script = """
        set +e
        ps -eo pid=,args= | awk '
            /Xvfb :1|qq --no-sandbox|launcher[.]sh|libnapcat_launcher[.]so/ &&
            !/awk/ { print ${'$'}1 }
        ' | while read pid; do
            [ -n "${'$'}pid" ] || continue
            printf "[NapCat] stopping stale process %s\n" "${'$'}pid"
            kill "${'$'}pid" 2>/dev/null || true
        done
        sleep 1
        ps -eo pid=,args= | awk '
            /Xvfb :1|qq --no-sandbox|launcher[.]sh|libnapcat_launcher[.]so/ &&
            !/awk/ { print ${'$'}1 }
        ' | while read pid; do
            [ -n "${'$'}pid" ] || continue
            printf "[NapCat] force stopping stale process %s\n" "${'$'}pid"
            kill -9 "${'$'}pid" 2>/dev/null || true
        done
        rm -f /tmp/.X1-lock
        rm -f /tmp/.X11-unix/X1
        exit 0
        """.trimIndent(),
        workingDirectory = "/root"
    )

    fun napCatLaunchCommand(): List<String> = shellCommand(
        script = "cd /root && " +
            "if command -v script >/dev/null 2>&1; then " +
            "script -q -c ${shellQuote("bash /root/launcher.sh")} /dev/null; " +
            "else bash /root/launcher.sh; fi",
        workingDirectory = "/root"
    )

    fun isNapCatInstalled(): Boolean =
        napCatReadyMarker.isFile && napCatOneBotConfig.isFile && maiBotNapCatAdapterConfig.isFile

    fun agreementEnvironment(): Map<String, String> {
        val eulaHash = md5(eulaFile) ?: return emptyMap()
        val privacyHash = md5(privacyFile) ?: return emptyMap()
        return mapOf(
            "EULA_AGREE" to eulaHash,
            "PRIVACY_AGREE" to privacyHash,
            "MAIDROID_IPC_SOCKET" to SOCKET_NAME
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

    private fun shellCommand(
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

    private fun napCatInstallScript(token: String, targetProxy: String?): String =
        """
        set -e
        export target_proxy=${shellQuote(targetProxy.orEmpty())}
        ${githubMirrorScript()}
        progress_file="${'$'}{TMPDIR:-/tmp}/progress"
        progress_des_file="${'$'}{TMPDIR:-/tmp}/progress_des"

        maidroid_progress() {
            printf "%s\n" "${'$'}1" > "${'$'}progress_file"
            printf "%s\n" "${'$'}2" > "${'$'}progress_des_file"
            printf "[NapCat] %s\n" "${'$'}2"
        }

        maidroid_progress 0.05 "准备 NapCat 安装环境"
        mkdir -p "${'$'}HOME/.maidroid"
        if [ -s "${'$'}HOME/.maidroid/napcat_token" ]; then
            napcat_token="${'$'}(cat "${'$'}HOME/.maidroid/napcat_token")"
        else
            napcat_token="$token"
            printf "%s" "${'$'}napcat_token" > "${'$'}HOME/.maidroid/napcat_token"
            chmod 600 "${'$'}HOME/.maidroid/napcat_token" 2>/dev/null || true
        fi
        if [ -d "${'$'}HOME/napcat" ] && [ ! -f "${'$'}HOME/.maidroid/napcat_ready" ]; then
            if find "${'$'}HOME/napcat" -mindepth 1 -type f | grep -q .; then
                backup_dir="${'$'}HOME/napcat.maidroid-bak.${'$'}(date +%Y%m%d%H%M%S)"
                printf "[NapCat] 检测到未完成的 NapCat 目录，移动到备份: %s\n" "${'$'}backup_dir"
                mv "${'$'}HOME/napcat" "${'$'}backup_dir"
            else
                printf "[NapCat] 清理旧版本留下的空 NapCat 目录骨架\n"
                rm -rf "${'$'}HOME/napcat"
            fi
        fi
        if ! command -v sudo >/dev/null 2>&1; then
            printf "[NapCat] 容器内未找到 sudo，创建 root 兼容包装\n"
            mkdir -p /usr/local/bin
            cat > /usr/local/bin/sudo <<'EOF'
        #!/bin/sh
        exec "$@"
        EOF
            chmod +x /usr/local/bin/sudo
        fi

        maidroid_progress 0.15 "下载 NapCat 官方安装脚本"
        maidroid_github_curl napcat.sh https://raw.githubusercontent.com/NapNeko/napcat-linux-installer/refs/heads/main/install.sh "NapCat installer"

        maidroid_progress 0.35 "赋予 NapCat 安装脚本执行权限"
        chmod +x napcat.sh

        maidroid_progress 0.45 "执行 NapCat 官方安装脚本"
        bash napcat.sh

        maidroid_progress 0.78 "写入 NapCat OneBot11 WebSocket 配置"
        mkdir -p "${'$'}HOME/napcat/config"
        cat > "${'$'}HOME/napcat/config/onebot11.json" <<EOF
        {
          "network": {
            "websocketServers": [
              {
                "name": "WsServer",
                "enable": true,
                "host": "127.0.0.1",
                "port": 8095,
                "reportSelfMessage": false,
                "enableForcePushEvent": true,
                "messagePostFormat": "array",
                "token": "${'$'}napcat_token",
                "debug": false,
                "heartInterval": 30000
              }
            ],
            "websocketClients": []
          },
          "musicSignUrl": "",
          "enableLocalFile2Url": false,
          "parseMultMsg": false
        }
        EOF

        maidroid_progress 0.88 "安装并配置 MaiBot NapCat 适配器"
        INSTALL_DIR=${shellQuote(MAIBOT_CONTAINER_DIR)}
        ADAPTER_DIR="${'$'}INSTALL_DIR/plugins/MaiBot-Napcat-Adapter"
        if [ -d "${'$'}INSTALL_DIR" ]; then
            mkdir -p "${'$'}INSTALL_DIR/plugins"
            if [ ! -d "${'$'}ADAPTER_DIR" ]; then
                maidroid_github_clone https://github.com/MaiM-with-u/MaiBot-Napcat-Adapter.git "${'$'}ADAPTER_DIR" --depth=1 --branch main
            fi
            cat > "${'$'}ADAPTER_DIR/config.toml" <<EOF
        [plugin]
        enabled = true
        config_version = "0.1.0"

        [napcat_server]
        host = "127.0.0.1"
        port = 8095
        token = "${'$'}napcat_token"
        heartbeat_interval = 30.0
        reconnect_delay_sec = 5.0
        action_timeout_sec = 15.0
        EOF
        else
            printf "[NapCat] MaiBot 目录不存在，跳过适配器配置: %s\n" "${'$'}INSTALL_DIR"
        fi

        maidroid_progress 0.96 "记录 NapCat 安装状态"
        printf "ready\n" > "${'$'}HOME/.maidroid/napcat_ready"
        ${napCatPrepareRuntimeScript()}

        maidroid_progress 1.0 "NapCat 安装和自动配置完成"
        """.trimIndent()

    private fun napCatPrepareRuntimeScript(): String =
        """
        set -e
        mkdir -p /root/napcat/cache /root/napcat/config /tmp /tmp/.X11-unix /tmp/runtime-root
        chmod 700 /tmp/runtime-root 2>/dev/null || true
        cat > /root/launcher.sh <<'EOF'
        #!/bin/bash
        set -u
        cd /root
        mkdir -p /root/napcat/cache /tmp /tmp/.X11-unix /tmp/runtime-root
        chmod 700 /tmp/runtime-root 2>/dev/null || true
        export DISPLAY=:1
        export XDG_RUNTIME_DIR=/tmp/runtime-root
        export HOME=/root
        export QQNT_PROFILE_DIR=/root/.config/QQ
        trap "" SIGPIPE

        if ! command -v qq >/dev/null 2>&1; then
            echo "[NapCat] qq executable not found in PATH"
            exit 127
        fi

        if ! ps -eo args= | grep -F "Xvfb :1" | grep -v grep >/dev/null 2>&1; then
            rm -f /tmp/.X1-lock /tmp/.X11-unix/X1
            Xvfb :1 -screen 0 1x1x8 +extension GLX +render 2>&1 &
            xvfb_pid=$!
            echo "[NapCat] Xvfb started, pid=${'$'}xvfb_pid"
            sleep 1
        else
            echo "[NapCat] Reusing existing Xvfb :1"
        fi

        echo "[NapCat] Launching LinuxQQ with NapCat preload"
        LD_PRELOAD=/root/libnapcat_launcher.so qq --no-sandbox --disable-gpu --disable-dev-shm-usage 2>&1
        exit_code=$?
        echo "[NapCat] qq exited with code ${'$'}exit_code"
        exit ${'$'}exit_code
        EOF
        chmod +x /root/launcher.sh
        """.trimIndent()

    private fun githubMirrorScript(): String {
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

    private fun uvPrelude(): String =
        "export UV_LINK_MODE=copy; " +
            "export PATH=\"\$HOME/.local/bin:\$PATH\"; " +
            "if [ -f \"\$HOME/.local/bin/env\" ]; then . \"\$HOME/.local/bin/env\"; fi"

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

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
