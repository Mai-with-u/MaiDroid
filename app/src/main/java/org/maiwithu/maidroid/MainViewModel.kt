package org.maiwithu.maidroid

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.maiwithu.maidroid.maibot.MaiBotProcessStatus
import org.maiwithu.maidroid.oobe.OobeSetupManager
import org.maiwithu.maidroid.oobe.OobeStep
import org.maiwithu.maidroid.service.MaiBotRuntimeService
import org.maiwithu.maidroid.webui.MaiBotWebUi

/**
 * 主界面的流程状态：启动页 ->（首次）授权弹窗 + OOBE 三步 -> 主页。
 *
 * 跨屏幕旋转保留 OOBE 的安装进程（OobeSetupManager）；OOBE 当前步骤和“启动页是否已经播过”
 * 存进 SavedStateHandle，进程被系统回收后回来也能接着上次的位置。
 */
internal class MainViewModel(
    application: Application,
    private val savedState: SavedStateHandle
) : AndroidViewModel(application) {

    private companion object {
        const val KEY_OOBE_STEP = "oobe_step"
        const val KEY_SHOW_SPLASH = "show_startup_splash"

        // WebUI 在线时 5 秒探测一次，离线时 1.5 秒一次，好让主页尽快切到 WebUI。
        const val WEB_UI_ONLINE_POLL_MS = 5_000L
        const val WEB_UI_OFFLINE_POLL_MS = 1_500L
    }

    private val container = application.appDependencies
    private val settings = container.settings

    val setupManager = OobeSetupManager(
        application,
        settings,
        container.runtimePaths,
        container.runtimeReadiness,
        container.commandRunner,
        container.maiBotProcess
    )

    private val _setupComplete = MutableStateFlow(settings.setupComplete)

    /** OOBE 是否已完成；完成后显示主页，并开始拉起 MaiBot、探测 WebUI。 */
    val setupComplete: StateFlow<Boolean> = _setupComplete.asStateFlow()

    private val _authorizationAccepted = MutableStateFlow(settings.authorizationAccepted)
    val authorizationAccepted: StateFlow<Boolean> = _authorizationAccepted.asStateFlow()

    /** 启动页只在一次全新启动时播放（屏幕旋转、进程回收后恢复都不再播）。 */
    val showStartupSplash: StateFlow<Boolean> = savedState.getStateFlow(KEY_SHOW_SPLASH, true)

    val oobeStep: StateFlow<OobeStep> = savedState.getStateFlow(KEY_OOBE_STEP, OobeStep.Permissions)

    private val _webUiOnline = MutableStateFlow(false)

    /** MaiBot WebUI 现在能不能访问（不只是进程在跑）。 */
    val webUiOnline: StateFlow<Boolean> = _webUiOnline.asStateFlow()

    /** MaiBot 进程是否在跑、意外退出过几次、为什么没拉起来；唤醒页的自检行据此显示启动进度。 */
    val maiBotStatus: StateFlow<MaiBotProcessStatus> = container.maiBotProcess.status

    init {
        // OOBE 完成后：拉起前台服务（启动 MaiBot）-> 检查一次 MaiDroid 和 MaiBot 的新版本 -> 持续探测 WebUI 是否在线。
        // 每次打开应用（新的 ViewModel）都查一次更新，转屏重建 Activity 时 ViewModel 还在，不会重复查；
        // MaiBot 的检查只拉取上游提交、不动源码，和麦麦启动同时进行互不影响。
        // 查到新版本由主界面在主页上弹窗提醒，见 MaiDroidApp。
        viewModelScope.launch {
            setupComplete.collectLatest { complete ->
                if (!complete) return@collectLatest
                wakeMaiBot()
                container.appUpdater.checkOnLaunch()
                container.maiBotUpdater.checkOnLaunch()
                while (true) {
                    _webUiOnline.value = MaiBotWebUi.isReachable()
                    delay(if (_webUiOnline.value) WEB_UI_ONLINE_POLL_MS else WEB_UI_OFFLINE_POLL_MS)
                }
            }
        }
    }

    fun finishStartupSplash() {
        savedState[KEY_SHOW_SPLASH] = false
    }

    fun acceptAuthorization() {
        settings.authorizationAccepted = true
        _authorizationAccepted.value = true
    }

    /** 进入某一步时自动开工：配置容器一步开始解包和装系统依赖，安装一步开始装 MaiBot。 */
    fun onOobeStepShown(step: OobeStep) {
        when (step) {
            OobeStep.Permissions -> Unit
            OobeStep.Container -> setupManager.prepareContainer()
            OobeStep.Install -> startInstallOrReturnToContainer()
        }
    }

    // 进入或重试“安装 MaiBot”：容器没配好（例如进程被回收后恢复到这一步、容器文件被清掉一部分）
    // 就退回“配置容器”一步，由它补齐后再进来，而不是在安装页上报一个只能返回才能解决的错误。
    private fun startInstallOrReturnToContainer() {
        if (container.runtimeReadiness.containerProblem() == null) {
            setupManager.startInstall()
        } else {
            savedState[KEY_OOBE_STEP] = OobeStep.Container
        }
    }

    /** 系统返回键：退回 OOBE 的上一步（第一步时界面不拦截返回键，不会调到这里）。 */
    fun goBackOobeStep() {
        OobeStep.entries.getOrNull(oobeStep.value.ordinal - 1)?.let { savedState[KEY_OOBE_STEP] = it }
    }

    /**
     * 页脚主按钮：
     * 权限步骤 -> 存储已授权才进入配置容器（未授权返回 false，由界面提示并再次申请）；
     * 配置容器 -> 容器就绪就进入安装，否则重新配置；
     * 安装 MaiBot -> 已完成就进入主页，否则重新开始安装。
     */
    fun onOobeNext(storageGranted: Boolean): Boolean {
        val state = setupManager.state.value
        when (oobeStep.value) {
            OobeStep.Permissions -> {
                if (!storageGranted) return false
                savedState[KEY_OOBE_STEP] = OobeStep.Container
            }
            OobeStep.Container -> {
                if (state.canInstall) {
                    savedState[KEY_OOBE_STEP] = OobeStep.Install
                } else {
                    setupManager.prepareContainer()
                }
            }
            OobeStep.Install -> {
                if (state.isComplete) {
                    _setupComplete.value = true
                } else {
                    startInstallOrReturnToContainer()
                }
            }
        }
        return true
    }

    /** 页脚的“重新配置 / 重试安装”。 */
    fun retryOobeStep() {
        if (oobeStep.value == OobeStep.Container) {
            setupManager.prepareContainer()
        } else {
            startInstallOrReturnToContainer()
        }
    }

    /**
     * 唤醒麦麦：拉起前台服务，服务内部对已在运行的 MaiBot 不会重复启动。
     * 系统不让开前台服务时（应用已退到后台）直接让进程监管器启动，不让异常把应用带崩。
     */
    fun wakeMaiBot() {
        runCatching { MaiBotRuntimeService.start(getApplication()) }
            .onFailure { container.maiBotProcess.start() }
    }

    override fun onCleared() {
        setupManager.close()
        super.onCleared()
    }
}
