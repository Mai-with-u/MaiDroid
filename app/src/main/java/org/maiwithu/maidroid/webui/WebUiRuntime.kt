package org.maiwithu.maidroid.webui

import android.content.Context
import android.util.Log
import org.maiwithu.maidroid.BuildConfig
import org.mozilla.geckoview.BuildConfig as GeckoBuildConfig
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.WebExtension

/**
 * 进程级 Mozilla GeckoView 运行时与内置 WebExtension 安装管理器。
 *
 * 延迟到第一次真正打开 WebUI 时再创建 GeckoRuntime，避免仅挂在后台服务或 OOBE 页面时提前拉起渲染子进程；
 * 运行时启用深色配色、调试版远程调试（Firefox `about:debugging`）以及回环服务所需的明文/宽松连接配置。
 */
internal object WebUiRuntime {
    private const val TAG = "MaiDroidGecko"

    @Volatile
    private var runtime: GeckoRuntime? = null

    @Volatile
    private var extensionPromise: GeckoResult<WebExtension>? = null

    /** 获取或初始化全进程共享的 GeckoRuntime（需在主线程首次创建）。 */
    fun getOrCreate(context: Context): GeckoRuntime {
        runtime?.let { return it }
        val appContext = context.applicationContext
        val settings = GeckoRuntimeSettings.Builder()
            .javaScriptEnabled(true)
            .consoleOutput(true)
            .remoteDebuggingEnabled(BuildConfig.DEBUG)
            .preferredColorScheme(GeckoRuntimeSettings.COLOR_SCHEME_DARK)
            .allowInsecureConnections(GeckoRuntimeSettings.ALLOW_ALL)
            .build()
        return GeckoRuntime.create(appContext, settings).also { created ->
            runtime = created
        }
    }

    /** 安装（或复用已持久化的）内置桥接 WebExtension。 */
    fun ensureBridgeExtension(context: Context): GeckoResult<WebExtension> {
        extensionPromise?.let { return it }
        val geckoRuntime = getOrCreate(context)
        val result = geckoRuntime.webExtensionController
            .ensureBuiltIn(WebUiBridgeProtocol.EXTENSION_LOCATION, WebUiBridgeProtocol.EXTENSION_ID)
            .also { promise ->
                promise.accept(
                    { ext ->
                        Log.i(TAG, "Built-in WebExtension ready: ${ext?.id}")
                    },
                    { error ->
                        Log.e(TAG, "Failed to install built-in WebExtension", error)
                        // 安装失败时允许后续重试，不永久卡死失败 Promise
                        if (extensionPromise === promise) extensionPromise = null
                    }
                )
            }
        extensionPromise = result
        return result
    }

    /** 返回打包内嵌的 GeckoView 引擎版本号，供诊断报告展示。 */
    fun engineVersion(): String = runCatching { GeckoBuildConfig.MOZ_APP_VERSION }.getOrElse { "未知" }
}
