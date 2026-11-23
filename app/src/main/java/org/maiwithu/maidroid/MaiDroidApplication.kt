package org.maiwithu.maidroid

import android.app.Application
import android.os.Build
import android.util.Log
import com.tencent.mmkv.MMKV
import org.maiwithu.maidroid.remote.RemoteOpsManager
import org.maiwithu.maidroid.service.MaiBotRuntimeService
import org.maiwithu.maidroid.settings.AppSettingsRecovery
import java.io.File

/**
 * 进程入口：初始化设置存储（MMKV，文件损坏时尽量恢复）-> 创建 AppDependencies -> 接管闪退记录 -> 注册前台服务的通知渠道。
 */
class MaiDroidApplication : Application() {

    /** 全进程共享的组件，用 context.appDependencies 访问。 */
    internal lateinit var dependencies: AppDependencies
        private set

    override fun onCreate() {
        super.onCreate()
        // GeckoView 会自行拉起隔离渲染等子进程：子进程不承载宿主业务，提前退出避免重复初始化 MMKV、日志与后台监管。
        if (!isMainProcess()) return
        val mmkvRoot = MMKV.initialize(this)
        MMKV.registerHandler(AppSettingsRecovery)
        Log.d("MaiDroid", "MMKV root: $mmkvRoot")
        dependencies = AppDependencies(this)
        dependencies.crashRecorder.install()
        MaiBotRuntimeService.createNotificationChannel(this)
        RemoteOpsManager.createAnnouncementChannel(this)
        // 进程启动后立即挂载终端失败日志监听器，不触发容器监管组件的提前初始化
        dependencies.remoteOps.ensureStarted()
    }

    // 优先用系统 getProcessName（API 28+）；旧系统直接读 /proc/self/cmdline，避免在隔离渲染进程调 ActivityManager 触发 SecurityException。
    private fun isMainProcess(): Boolean {
        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            runCatching {
                File("/proc/self/cmdline").readText(Charsets.UTF_8).substringBefore('\u0000').trim()
            }.getOrNull()
        }
        return processName.isNullOrEmpty() || processName == packageName
    }
}
