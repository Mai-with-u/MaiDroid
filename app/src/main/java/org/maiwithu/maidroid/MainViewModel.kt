package org.maiwithu.maidroid

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maiwithu.maidroid.diagnostics.CrashRecord
import org.maiwithu.maidroid.maibot.MaiBotProcessStatus
import org.maiwithu.maidroid.maibot.update.shouldAutoUpdateMaiBot
import org.maiwithu.maidroid.oobe.OobeSetupManager
import org.maiwithu.maidroid.oobe.OobeStep
import org.maiwithu.maidroid.runtime.StorageSpace
import org.maiwithu.maidroid.service.MaiBotRuntimeService
import org.maiwithu.maidroid.webui.MaiBotWebUi

/**
 * 主界面的流程状态：启动页 ->（首次）授权弹窗 + OOBE 三步 -> 主页。
 *
 * OOBE 的安装进程（OobeSetupManager）是进程级的，转屏、界面被划掉都不打断；OOBE 当前步骤存进设置（AppSettings.oobeStep），
 * 闪退、强制重启、断电后重新打开也回到这一步接着装。“启动页是否已经播过”存进 SavedStateHandle，进程被系统回收后回来不再重播。
 */
internal class MainViewModel(
    application: Application,
    private val savedState: SavedStateHandle
) : AndroidViewModel(application) {

    private companion object {
        const val KEY_SHOW_SPLASH = "show_startup_splash"

        // WebUI 在线时 5 秒探测一次，离线时 1.5 秒一次，好让主页尽快切到 WebUI。
        const val WEB_UI_ONLINE_POLL_MS = 5_000L
        const val WEB_UI_OFFLINE_POLL_MS = 1_500L

        // 剩余空间低于这个数就提醒一次；每分钟看一次
        const val LOW_STORAGE_WARNING_BYTES = 300L * 1024 * 1024
        const val STORAGE_CHECK_INTERVAL_MS = 60_000L
    }

    private val dependencies = application.appDependencies
    private val settings = dependencies.settings

    /** 首次引导的执行者是进程级的（见 AppDependencies.oobeSetup）：界面被划掉重开后接着显示同一份进度。 */
    val setupManager: OobeSetupManager = dependencies.oobeSetup

    private val _setupComplete = MutableStateFlow(settings.setupComplete)

    /** OOBE 是否已完成；完成后显示主页，并开始拉起 MaiBot、探测 WebUI。 */
    val setupComplete: StateFlow<Boolean> = _setupComplete.asStateFlow()

    private val _authorizationAccepted = MutableStateFlow(settings.authorizationAccepted)
    val authorizationAccepted: StateFlow<Boolean> = _authorizationAccepted.asStateFlow()

    /** 启动页只在一次全新启动时播放（屏幕旋转、进程回收后恢复都不再播）。 */
    val showStartupSplash: StateFlow<Boolean> = savedState.getStateFlow(KEY_SHOW_SPLASH, true)

    private val _oobeStep = MutableStateFlow(
        OobeStep.entries.firstOrNull { it.name == settings.oobeStep } ?: OobeStep.Permissions
    )
    val oobeStep: StateFlow<OobeStep> = _oobeStep.asStateFlow()

    private val _webUiOnline = MutableStateFlow(false)

    /** MaiBot WebUI 现在能不能访问（不只是进程在跑）。 */
    val webUiOnline: StateFlow<Boolean> = _webUiOnline.asStateFlow()

    private val _lowStorageBytes = MutableStateFlow<Long?>(null)

    /** 麦麦跑着时手机只剩这么多空间、该提醒用户清理了；不用提醒（空间够、这次已经提醒过）时为 null。 */
    val lowStorageBytes: StateFlow<Long?> = _lowStorageBytes.asStateFlow()

    // 这次打开应用已经提醒过空间不足（用户点了以后再说或去清理了），之后不再弹
    private var lowStorageWarned = false

    private val _pendingCrash = MutableStateFlow(dependencies.crashRecorder.pendingCrash())

    /** 上次 MaiDroid 闪退过、用户还没看过提示：主界面据此问要不要反馈。 */
    val pendingCrash: StateFlow<CrashRecord?> = _pendingCrash.asStateFlow()

    /** MaiBot 进程是否在跑、意外退出过几次、为什么没拉起来；唤醒页的自检行据此显示启动进度。 */
    val maiBotStatus: StateFlow<MaiBotProcessStatus> = dependencies.maiBotProcess.status

    /**
     * 这次是不是 MaiDroid 更新后的第一次启动、要在唤醒页上先自动升级 MaiBot（设置页可关闭）；唤醒页据此多显示一行升级进度。
     * 判定完立刻记下当前版本号：同一版本之后的启动（包括这次没升级完就被关掉）都不再自动升级，
     * 转屏时 ViewModel 还在，也不会重复判定。
     */
    val autoUpdatingMaiBot: Boolean = shouldAutoUpdateMaiBot(
        setupComplete = settings.setupComplete,
        lastLaunchedVersionCode = settings.lastLaunchedVersionCode,
        currentVersionCode = BuildConfig.VERSION_CODE,
        autoUpdateDisabled = settings.maiBotAutoUpdateDisabled
    ).also { settings.lastLaunchedVersionCode = BuildConfig.VERSION_CODE } ||
        // 上次更新 MaiBot 做到一半被打断（闪退、被系统结束、断电）：同样先在唤醒页上把它做完，不受“关闭自动更新”影响
        (settings.setupComplete && dependencies.maiBotUpdater.hasInterruptedUpdate())

    init {
        // OOBE 完成后：检查一次 MaiDroid 新版本 ->（MaiDroid 刚更新过时）先自动升级 MaiBot ->
        // 拉起前台服务（启动 MaiBot）->（没自动升级时）检查一次 MaiBot 新版本 -> 持续探测 WebUI 是否在线。
        // 每次打开应用（新的 ViewModel）都查一次更新，转屏重建 Activity 时 ViewModel 还在，不会重复查；
        // MaiBot 的检查只拉取上游提交、不动源码，和麦麦启动同时进行互不影响；自动升级期间麦麦被拦着不启动，升级完由它拉起。
        // 查到新版本由主界面在主页上弹窗提醒，见 MaiDroidApp。
        viewModelScope.launch {
            setupComplete.collectLatest { complete ->
                if (!complete) return@collectLatest
                dependencies.appUpdater.checkOnLaunch()
                if (autoUpdatingMaiBot) {
                    dependencies.maiBotUpdater.updateAfterAppUpgrade()
                    wakeMaiBot()
                } else {
                    wakeMaiBot()
                    dependencies.maiBotUpdater.checkOnLaunch()
                }
                var lastStorageCheck = 0L
                while (true) {
                    _webUiOnline.value = MaiBotWebUi.isReachable()
                    // 顺带每分钟看一次空间：快满了提醒一次（见 LowStorageDialog）
                    val now = SystemClock.elapsedRealtime()
                    if (!lowStorageWarned && now - lastStorageCheck >= STORAGE_CHECK_INTERVAL_MS) {
                        lastStorageCheck = now
                        val free = withContext(Dispatchers.IO) {
                            StorageSpace.availableBytes(dependencies.runtimePaths.appFilesDir)
                        }
                        _lowStorageBytes.value = free.takeIf { it < LOW_STORAGE_WARNING_BYTES }
                    }
                    delay(if (_webUiOnline.value) WEB_UI_ONLINE_POLL_MS else WEB_UI_OFFLINE_POLL_MS)
                }
            }
        }
    }

    /** 空间不足的提醒关掉了（以后再说，或清理完关掉了清理弹窗）：这次打开应用期间不再提醒。 */
    fun dismissLowStorage() {
        lowStorageWarned = true
        _lowStorageBytes.value = null
    }

    /** 用户看过闪退提示（点了反馈或知道了），这次闪退之后不再问。 */
    fun acknowledgeCrash() {
        _pendingCrash.value?.let(dependencies.crashRecorder::acknowledge)
        _pendingCrash.value = null
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

    // 换到 OOBE 的某一步，同时记进设置，重新打开应用时回到这一步
    private fun moveToOobeStep(step: OobeStep) {
        _oobeStep.value = step
        settings.oobeStep = step.name
    }

    // 进入或重试“安装 MaiBot”：容器没配好（例如重新打开时回到这一步、容器文件被清掉一部分）
    // 就退回“配置容器”一步，由它补齐后再进来，而不是在安装页上报一个只能返回才能解决的错误。
    private fun startInstallOrReturnToContainer() {
        if (dependencies.runtimeReadiness.containerProblem() == null) {
            setupManager.startInstall()
        } else {
            moveToOobeStep(OobeStep.Container)
        }
    }

    /** 系统返回键：退回 OOBE 的上一步（第一步时界面不拦截返回键，不会调到这里）。 */
    fun goBackOobeStep() {
        OobeStep.entries.getOrNull(oobeStep.value.ordinal - 1)?.let(::moveToOobeStep)
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
                moveToOobeStep(OobeStep.Container)
            }
            OobeStep.Container -> {
                if (state.canInstall) {
                    moveToOobeStep(OobeStep.Install)
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
            .onFailure { dependencies.maiBotProcess.start() }
    }

}
