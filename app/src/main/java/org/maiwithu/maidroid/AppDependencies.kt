package org.maiwithu.maidroid

import android.content.Context
import android.os.Build
import android.os.Process
import org.maiwithu.maidroid.appupdate.AppUpdateManager
import org.maiwithu.maidroid.cleanup.StorageCleanupManager
import org.maiwithu.maidroid.diagnostics.CrashRecorder
import org.maiwithu.maidroid.diagnostics.DiagnosticsFiles
import org.maiwithu.maidroid.diagnostics.DiagnosticsManager
import org.maiwithu.maidroid.diagnostics.TerminalLogFiles
import org.maiwithu.maidroid.diagnostics.recentSystemCrashes
import org.maiwithu.maidroid.diagnostics.TerminalLogRecorder
import org.maiwithu.maidroid.maibot.MaiBotProcessSupervisor
import org.maiwithu.maidroid.maibot.update.MaiBotUpdateManager
import org.maiwithu.maidroid.oobe.OobeSetupManager
import org.maiwithu.maidroid.platform.napcat.NapCatPlatformManager
import org.maiwithu.maidroid.platform.napcat.NapCatProcessSupervisor
import org.maiwithu.maidroid.platform.qq.QqAdapterFiles
import org.maiwithu.maidroid.platform.qq.QqAdapterUpkeep
import org.maiwithu.maidroid.platform.qq.SnowLumaAdapterInstaller
import org.maiwithu.maidroid.platform.qqofficial.QqOfficialPlatformManager
import org.maiwithu.maidroid.runtime.ContainerCommandRunner
import org.maiwithu.maidroid.runtime.NetworkMonitor
import org.maiwithu.maidroid.runtime.RuntimePaths
import org.maiwithu.maidroid.runtime.RuntimeReadiness
import org.maiwithu.maidroid.service.SafeExitManager
import org.maiwithu.maidroid.settings.AppSettings
import org.maiwithu.maidroid.ssh.ContainerSshManager
import org.maiwithu.maidroid.terminal.AnsiText
import org.maiwithu.maidroid.terminal.TerminalLog

/**
 * 进程内所有长生命周期组件的唯一创建处（手动依赖注入）。
 *
 * Activity、Service、广播和 Compose 页面都通过 context.appDependencies 拿同一份实例，
 * 谁依赖谁在这里一眼就能看清。平台管理器、更新管理器创建时会启动后台轮询，
 * 所以都用 lazy 推迟到第一次使用（打开对应页面或服务第一次需要时）。
 */
internal class AppDependencies(context: Context) {
    private val appContext = context.applicationContext

    val settings = AppSettings()

    // ── 出错诊断：终端输出从进程一启动就落盘，最先创建，免得漏掉早期的日志 ─────
    val diagnosticsFiles = DiagnosticsFiles(appContext.filesDir)
    val terminalLogRecorder = TerminalLogRecorder(
        TerminalLogFiles(diagnosticsFiles.logsDir),
        startBanner = "===== MaiDroid ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) 启动，进程 ${Process.myPid()} ====="
    )
    // MaiDroid 自己闪退时记下堆栈和闪退前的终端输出，MaiDroidApplication 创建完依赖就接管未捕获异常
    val crashRecorder = CrashRecorder(
        diagnosticsFiles.crashesDir,
        header = {
            "MaiDroid ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}，${BuildConfig.BUILD_TYPE}）· " +
                "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）"
        },
        recentLines = { TerminalLog.logs.value.map(AnsiText::plain) },
        systemCrashes = { recentSystemCrashes(appContext) }
    )

    // ── 容器运行时：路径、就绪检查、命令执行 ─────────────────────────────
    val runtimePaths = RuntimePaths.from(appContext)
    val runtimeReadiness = RuntimeReadiness(runtimePaths)
    val commandRunner = ContainerCommandRunner(runtimePaths)

    // ── 容器里的长驻进程与功能模块 ───────────────────────────────────────
    val maiBotProcess: MaiBotProcessSupervisor by lazy {
        MaiBotProcessSupervisor(runtimePaths, runtimeReadiness, commandRunner)
    }
    val containerSsh: ContainerSshManager by lazy {
        ContainerSshManager(settings, runtimeReadiness, commandRunner)
    }
    // MaiBot 侧的 SnowLuma 适配器：消息平台页安装 NapCat、SnowLuma 和两边启动前的换装共用一个，迁移配置时靠它的锁串行。
    private val snowLumaAdapterInstaller: SnowLumaAdapterInstaller by lazy {
        SnowLumaAdapterInstaller(QqAdapterFiles(runtimePaths), commandRunner)
    }
    private val qqAdapterUpkeep: QqAdapterUpkeep by lazy {
        QqAdapterUpkeep(QqAdapterFiles(runtimePaths), snowLumaAdapterInstaller, maiBotProcess)
    }
    val napCatProcess: NapCatProcessSupervisor by lazy {
        NapCatProcessSupervisor(runtimePaths, commandRunner, qqAdapterUpkeep)
    }
    val napCat: NapCatPlatformManager by lazy {
        NapCatPlatformManager(
            appContext, runtimePaths, runtimeReadiness, commandRunner, napCatProcess, snowLumaAdapterInstaller,
            networkMonitor
        )
    }
    val qqOfficial: QqOfficialPlatformManager by lazy {
        QqOfficialPlatformManager(appContext, runtimePaths, runtimeReadiness, commandRunner, maiBotProcess)
    }
    val maiBotUpdater: MaiBotUpdateManager by lazy {
        MaiBotUpdateManager(appContext, runtimePaths, runtimeReadiness, commandRunner, maiBotProcess)
    }
    // “反馈问题”：收集版本、设备、容器和进程状态，打码后生成诊断包；只读文件，不创建平台管理器。
    val diagnostics: DiagnosticsManager by lazy {
        DiagnosticsManager(
            appContext, settings, runtimePaths, runtimeReadiness, diagnosticsFiles, terminalLogRecorder, maiBotProcess
        )
    }
    // 设置页和“空间不足”提示里的“清理空间”：量各类缓存的大小，删掉勾选的。
    val storageCleanup: StorageCleanupManager by lazy {
        StorageCleanupManager(runtimePaths, runtimeReadiness, commandRunner)
    }
    // 设置页的“安全退出”：让上面三个长驻进程正常退出后关闭应用，由前台服务执行。
    val safeExit: SafeExitManager by lazy {
        SafeExitManager(settings, maiBotProcess, napCatProcess, containerSsh)
    }

    // 手机有没有网：断网失败后等网络恢复自动重试用
    val networkMonitor: NetworkMonitor by lazy { NetworkMonitor(appContext) }

    // 首次引导的执行者放在进程级：界面被划掉、转屏都不打断正在装的，重新打开时接着显示进度
    val oobeSetup: OobeSetupManager by lazy {
        OobeSetupManager(appContext, settings, runtimePaths, runtimeReadiness, commandRunner, maiBotProcess, networkMonitor)
    }

    // ── MaiDroid 应用本体的在线更新（与容器无关） ─────────────────────────
    val appUpdater: AppUpdateManager by lazy {
        AppUpdateManager(appContext, BuildConfig.VERSION_NAME, BuildConfig.APP_UPDATE_API_URL)
    }
}

/** 取应用级的 AppDependencies；任何 Context（Activity、Service、Compose 的 LocalContext）都可以用。 */
internal val Context.appDependencies: AppDependencies
    get() = (applicationContext as MaiDroidApplication).dependencies
