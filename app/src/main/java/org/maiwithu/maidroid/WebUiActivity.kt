package org.maiwithu.maidroid

import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import org.maiwithu.maidroid.terminal.TerminalLog
import org.maiwithu.maidroid.ui.home.WEB_UI_HIBERNATE_DELAY_MS
import org.maiwithu.maidroid.ui.home.shouldHibernateWebUiOnTrimMemory
import org.maiwithu.maidroid.webui.MaiBotWebUi
import org.maiwithu.maidroid.webui.WebUiDownloads
import org.maiwithu.maidroid.webui.WebUiFileChooser
import org.maiwithu.maidroid.webui.WebUiRenderCrashGuard
import org.maiwithu.maidroid.webui.WebUiSessionHost

/**
 * 全屏打开一个 WebUI 页面：主页唤醒按钮打开 MaiBot WebUI，NapCat / SnowLuma 设置按钮打开对应管理页。
 * MaiBot WebUI 会换成带访问令牌的自动登录地址；返回键先在网页内后退，退到底再关闭页面；
 * 网页里的上传按钮经 WebUiFileChooser 打开系统选文件页，导出、下载按钮经 WebUiDownloads 弹“另存为”。
 * 网页内容进程没了（系统回收内存、网页崩溃）时整页重建一次重新打开，反复崩溃就关掉这一页回到主页。
 */
class WebUiActivity : ComponentActivity() {
    companion object {
        /** 要打开的地址；不传时打开 MaiBot WebUI。 */
        const val EXTRA_URL = "org.maiwithu.maidroid.extra.WEB_UI_URL"

        // 放在伴生对象里：整页重建后还是同一份计数，才认得出“反复崩溃”
        private val renderCrashGuard = WebUiRenderCrashGuard()
        private const val HIBERNATE_RETRY_DELAY_MS = 60_000L
    }

    private var sessionHost: WebUiSessionHost? = null
    private lateinit var fileChooser: WebUiFileChooser
    private lateinit var container: FrameLayout
    private var isMaiBotWebUi = true
    private var initialLaunchUrl = MaiBotWebUi.URL
    private var activityStarted = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hibernateRunnable = object : Runnable {
        override fun run() {
            if (activityStarted) return
            if (!hibernateSessionIfIdle()) {
                // 若文件选择或导出下载尚未结束，顺延重试，避免在系统选文件时拆掉会话。
                mainHandler.postDelayed(this, HIBERNATE_RETRY_DELAY_MS)
            }
        }
    }

    // ActivityResult 要在 Activity 启动前注册，所以放在属性里；选完文件把结果交回网页。
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        fileChooser.onResult(result.resultCode, result.data)
    }

    // 下载要用应用上下文，属性初始化时 Activity 还没有，所以在 onCreate 里建。
    private lateinit var downloads: WebUiDownloads

    // 网页点导出 -> downloads 用这个 launcher 弹“另存为” -> 选好位置回到这里，交给 downloads 写文件。
    private val downloadLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        downloads.onResult(result.resultCode, result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 全屏网页同样遵循设置页的“屏幕常亮”开关，默认允许自动息屏。
        applyKeepScreenOn(window, appDependencies.settings.keepScreenOn)
        // 横屏时和主界面一样隐藏状态栏和导航栏（从边缘滑动临时呼出），竖屏保持系统栏常显。
        applySystemBars(
            window = window,
            landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
            hideNavigationBar = false
        )

        fileChooser = WebUiFileChooser(this, MaiBotWebUi.LOG_TAG).apply { launcher = fileChooserLauncher }
        downloads = WebUiDownloads(this, MaiBotWebUi.LOG_TAG).apply { launcher = downloadLauncher }

        // 网页放进一层容器，容器按系统栏、挖孔和键盘留内边距，避免 Android 15 全面屏下被状态栏、挖孔或键盘遮挡。
        container = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(container)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val host = sessionHost
                    if (host != null && host.canGoBack) {
                        host.goBack()
                    } else {
                        finish()
                    }
                }
            }
        )

        val url = intent.getStringExtra(EXTRA_URL) ?: MaiBotWebUi.URL
        isMaiBotWebUi = MaiBotWebUi.isMaiBotWebUiUrl(url)
        initialLaunchUrl = if (isMaiBotWebUi) {
            MaiBotWebUi.launchUrl(appDependencies.runtimePaths, TerminalLog.logs.value, baseUrl = url)
        } else {
            url
        }
        attachAndLoadSessionHost()
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        mainHandler.removeCallbacks(hibernateRunnable)
        if (sessionHost == null) {
            attachAndLoadSessionHost()
        } else {
            sessionHost?.setActive(true)
        }
        appDependencies.powerTelemetry.onUiForegroundChanged(true)
    }

    override fun onResume() {
        super.onResume()
        applyKeepScreenOn(window, appDependencies.settings.keepScreenOn)
    }

    override fun onStop() {
        activityStarted = false
        sessionHost?.setActive(false)
        mainHandler.removeCallbacks(hibernateRunnable)
        mainHandler.postDelayed(hibernateRunnable, WEB_UI_HIBERNATE_DELAY_MS)
        appDependencies.powerTelemetry.onUiForegroundChanged(false)
        super.onStop()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // 退后台期间系统触发内存回收时主动关闭空闲网页渲染进程，减轻主进程及容器 LMK 压力。
        if (shouldHibernateWebUiOnTrimMemory(level, isStarted = activityStarted)) {
            mainHandler.removeCallbacks(hibernateRunnable)
            hibernateSessionIfIdle()
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        fileChooser.cancelPending()
        downloads.release()
        sessionHost?.release()
        sessionHost = null
        super.onDestroy()
    }

    // 创建或从休眠恢复全屏 GeckoView 会话并等布局就绪后加载地址。
    private fun attachAndLoadSessionHost() {
        if (sessionHost != null) return
        val host = WebUiSessionHost(
            context = this,
            logTag = MaiBotWebUi.LOG_TAG,
            fileChooser = fileChooser,
            downloads = downloads,
            onContentProcessGone = { dead ->
                (dead.view.parent as? ViewGroup)?.removeView(dead.view)
                dead.release()
                if (sessionHost === dead) sessionHost = null
                if (renderCrashGuard.shouldAutoReopen()) {
                    recreate()
                } else {
                    Toast.makeText(this, "网页反复崩溃，已关闭这个页面", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        )
        sessionHost = host
        container.removeAllViews()
        container.addView(host.view, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        host.view.doOnLayout {
            host.applyDesktopViewport(enabled = isMaiBotWebUi)
            host.loadUrl(initialLaunchUrl)
        }
    }

    private fun hibernateSessionIfIdle(): Boolean {
        val current = sessionHost ?: return true
        if (fileChooser.hasPending || downloads.hasPending) return false
        container.removeAllViews()
        current.release()
        sessionHost = null
        return true
    }
}
