package org.maiwithu.maidroid

import android.app.Application
import android.util.Log
import com.tencent.mmkv.MMKV
import org.maiwithu.maidroid.service.MaiBotRuntimeService

/** 进程入口：初始化设置存储（MMKV）-> 创建 AppDependencies -> 注册前台服务的通知渠道。 */
class MaiDroidApplication : Application() {

    /** 全进程共享的组件，用 context.appDependencies 访问。 */
    internal lateinit var dependencies: AppDependencies
        private set

    override fun onCreate() {
        super.onCreate()
        val mmkvRoot = MMKV.initialize(this)
        Log.d("MaiDroid", "MMKV root: $mmkvRoot")
        dependencies = AppDependencies(this)
        MaiBotRuntimeService.createNotificationChannel(this)
    }
}
