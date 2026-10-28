package org.maiwithu.maidroid

import android.content.Context
import org.maiwithu.maidroid.maibot.MaiBotSupervisor
import org.maiwithu.maidroid.platform.napcat.NapCatPlatformManager
import org.maiwithu.maidroid.platform.napcat.NapCatProcessSupervisor
import org.maiwithu.maidroid.platform.qqofficial.QqOfficialPlatformManager
import org.maiwithu.maidroid.runtime.ContainerCommandRunner
import org.maiwithu.maidroid.runtime.RuntimePaths
import org.maiwithu.maidroid.runtime.RuntimeReadiness
import org.maiwithu.maidroid.settings.AppSettings
import org.maiwithu.maidroid.ssh.ContainerSshManager
import org.maiwithu.maidroid.update.MaiBotUpdateManager

/**
 * 进程内所有长生命周期组件的唯一创建处（手动依赖注入）。
 *
 * Activity、Service、广播和 Compose 页面都通过 context.appContainer 拿同一份实例，
 * 谁依赖谁在这里一眼就能看清。平台管理器、更新管理器创建时会启动后台轮询，
 * 所以都用 lazy 推迟到第一次使用（打开对应页面或服务第一次需要时）。
 */
internal class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val settings = AppSettings()

    // ── 容器运行时：路径、就绪检查、命令执行 ─────────────────────────────
    val runtimePaths = RuntimePaths.from(appContext)
    val runtimeReadiness = RuntimeReadiness(runtimePaths)
    val commandRunner = ContainerCommandRunner(runtimePaths)

    // ── 容器里的长驻进程与功能模块 ───────────────────────────────────────
    val maiBot: MaiBotSupervisor by lazy {
        MaiBotSupervisor(runtimePaths, runtimeReadiness, commandRunner)
    }
    val containerSsh: ContainerSshManager by lazy {
        ContainerSshManager(settings, runtimeReadiness, commandRunner)
    }
    val napCatProcess: NapCatProcessSupervisor by lazy {
        NapCatProcessSupervisor(runtimePaths, commandRunner)
    }
    val napCat: NapCatPlatformManager by lazy {
        NapCatPlatformManager(appContext, runtimePaths, runtimeReadiness, commandRunner, napCatProcess)
    }
    val qqOfficial: QqOfficialPlatformManager by lazy {
        QqOfficialPlatformManager(appContext, runtimePaths, runtimeReadiness, commandRunner, maiBot)
    }
    val maiBotUpdater: MaiBotUpdateManager by lazy {
        MaiBotUpdateManager(appContext, runtimePaths, runtimeReadiness, commandRunner, maiBot)
    }
}

/** 取应用级的 AppContainer；任何 Context（Activity、Service、Compose 的 LocalContext）都可以用。 */
internal val Context.appContainer: AppContainer
    get() = (applicationContext as MaiDroidApplication).container
