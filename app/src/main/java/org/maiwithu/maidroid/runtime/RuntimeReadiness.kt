package org.maiwithu.maidroid.runtime

import java.io.File
import java.nio.file.Files

/** 运行 MaiBot 之前必须就绪的部件。OOBE 的检查卡片按它选图标和说明，不再按标题字符串匹配。 */
internal enum class RuntimeComponent {
    DebianRootfs,
    TermuxBootstrap,
    Proot,
    Git,
    CurlCa,
    MaiBotSource,
    PythonDependencies
}

/** 一个部件的就绪情况：name 是展示名，path 是判断依据的文件，hint 是没就绪时告诉用户该怎么办。 */
internal data class ContainerRequirement(
    val component: RuntimeComponent,
    val name: String,
    val path: String,
    val ready: Boolean,
    val hint: String
)

/**
 * 按文件是否存在、是否可执行判断容器和 MaiBot 装到了哪一步。
 * 只读文件系统，不跑任何命令；唯一的副作用是 containerProblem 会先补 proot 可执行位。
 */
internal class RuntimeReadiness(private val paths: RuntimePaths) {

    /** 进入容器所需的五个部件：rootfs、bootstrap、proot、git、curl 与 CA 证书。 */
    fun containerRequirements(): List<ContainerRequirement> = listOf(
        ContainerRequirement(
            component = RuntimeComponent.DebianRootfs,
            name = "Debian rootfs",
            path = paths.rootfsDir.absolutePath,
            ready = paths.rootfsDir.isDirectory && paths.rootfsMarker.isFile,
            hint = "OOBE 需要先解包 ${RuntimeAssets.ROOTFS}"
        ),
        ContainerRequirement(
            component = RuntimeComponent.TermuxBootstrap,
            name = "Termux bootstrap",
            path = paths.bootstrapMarker.absolutePath,
            ready = paths.bootstrapMarker.isFile,
            hint = "OOBE 需要先解包 ${RuntimeAssets.BOOTSTRAP}"
        ),
        ContainerRequirement(
            component = RuntimeComponent.Proot,
            name = "proot",
            path = paths.prootExecutable.absolutePath,
            ready = isProotRuntimeReady(),
            hint = "APK 内置 proot 启动器和 loader 后才能进入容器"
        ),
        ContainerRequirement(
            component = RuntimeComponent.Git,
            name = "Git",
            path = paths.gitExecutable.absolutePath,
            ready = paths.gitExecutable.isExecutableFile(),
            hint = "installation.md 要求容器内可用 Git"
        ),
        ContainerRequirement(
            component = RuntimeComponent.CurlCa,
            name = "curl / CA",
            path = paths.curlExecutable.absolutePath,
            ready = paths.curlExecutable.isExecutableFile() && paths.caCertificateBundle.isFile,
            hint = "需要 curl 安装 uv，并需要 CA 证书访问 HTTPS"
        )
    )

    /** 启动 MaiBot 所需的全部部件：容器五件套 + MaiBot 源码 + Python 依赖。 */
    fun maiBotLaunchRequirements(): List<ContainerRequirement> =
        containerRequirements() + listOf(
            ContainerRequirement(
                component = RuntimeComponent.MaiBotSource,
                name = "MaiBot",
                path = paths.maiBotEntry.absolutePath,
                ready = isMaiBotSourceReady(),
                hint = "需要按 installation.md 克隆 MaiBot 仓库"
            ),
            ContainerRequirement(
                component = RuntimeComponent.PythonDependencies,
                name = "uv dependencies",
                path = paths.venvPythonExecutable.absolutePath,
                ready = isMaiBotDependencyReady(),
                hint = "需要在 MaiBot 目录执行 uv sync"
            )
        )

    /**
     * 后台任务（装平台、在线更新、容器 SSH）开工前的统一检查：
     * 先补 proot 可执行位 -> 再看容器五件套，返回第一个没就绪的部件说明；全部就绪返回 null。
     */
    fun containerProblem(): String? {
        RuntimeFileModes.ensureExecutableBits(paths)
        return containerRequirements()
            .firstOrNull { !it.ready }
            ?.let { "${it.name} 未就绪：${it.hint}" }
    }

    /** APK 里的容器资源已经解包完整（rootfs、bootstrap、proot 依赖库都在），不含 apt 装的系统依赖。 */
    fun isBaseRuntimeReady(): Boolean =
        paths.rootfsMarker.isFile && paths.bootstrapMarker.isFile &&
            areTermuxProotDependenciesReady() && isProotRuntimeReady()

    fun isSystemDependencyReady(): Boolean =
        paths.gitExecutable.isExecutableFile() &&
            paths.curlExecutable.isExecutableFile() &&
            paths.caCertificateBundle.isFile

    fun isProotRuntimeReady(): Boolean =
        paths.prootExecutable.isExecutableFile() &&
            paths.prootLoader.isExecutableFile() &&
            paths.prootAndroidShmem.isExecutableFile() &&
            paths.prootTalloc.isExecutableFile()

    fun areTermuxProotDependenciesReady(): Boolean =
        paths.termuxAndroidShmem.isFile && paths.termuxTalloc.isFile

    fun isMaiBotSourceReady(): Boolean = paths.maiBotEntry.isFile && paths.maiBotPyproject.isFile

    // uv sync 一开始就会建好 .venv 的壳（pyvenv.cfg 和 python 链接），依赖装到一半失败时壳还留着。
    // 所以除了壳之外，还要确认 site-packages 里真有包，否则安装流程会把失败的 uv sync 当成已完成并跳过，
    // 接着启动 MaiBot 时又在后台重新编译依赖，界面就一直停在“等待 WebUI 启动”。
    fun isMaiBotDependencyReady(): Boolean =
        paths.venvConfig.isFile &&
            (Files.isSymbolicLink(paths.venvPythonExecutable.toPath()) ||
                paths.venvPythonExecutable.isExecutableFile()) &&
            hasInstalledPackages()

    private fun hasInstalledPackages(): Boolean =
        File(paths.venvDir, "lib")
            .listFiles { file -> file.isDirectory && file.name.startsWith("python") }
            ?.any { pythonDir -> File(pythonDir, "site-packages").list()?.isNotEmpty() == true }
            ?: false

    private fun File.isExecutableFile(): Boolean = isFile && canExecute()
}
