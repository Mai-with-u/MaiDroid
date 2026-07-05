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
        script = "mkdir -p /opt && " +
            "rm -rf ${shellQuote("$MAIBOT_CONTAINER_DIR.tmp")} && " +
            "git clone --depth=1 --single-branch ${shellQuote(REPOSITORY_URL)} ${shellQuote("$MAIBOT_CONTAINER_DIR.tmp")} && " +
            "rm -rf ${shellQuote(MAIBOT_CONTAINER_DIR)} && " +
            "mv ${shellQuote("$MAIBOT_CONTAINER_DIR.tmp")} ${shellQuote(MAIBOT_CONTAINER_DIR)}",
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
        script = "${uvPrelude()}; cd ${shellQuote(MAIBOT_CONTAINER_DIR)} && uv run python bot.py",
        workingDirectory = MAIBOT_CONTAINER_DIR
    )

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
    ): List<String> = listOf(
        prootExecutable.absolutePath,
        "--link2symlink",
        "-0",
        "-r", rootfsDir.absolutePath,
        "-b", "/dev",
        "-b", "/proc",
        "-b", "/sys",
        "-b", "/sdcard",
        "-w", workingDirectory
    ) + args

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
