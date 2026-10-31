package org.maiwithu.maidroid.appupdate

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maiwithu.maidroid.BuildConfig
import org.maiwithu.maidroid.VersionNumber
import org.maiwithu.maidroid.runtime.GitHubMirrors
import org.maiwithu.maidroid.terminal.TerminalLog
import java.io.File
import java.io.IOException

/** 更新弹窗所处的阶段：空闲 -> 检查中 -> 已是最新 / 有新版本 -> 下载中 -> 校验中 -> 待安装，任一步出错进入失败。 */
internal enum class AppUpdatePhase {
    Idle,
    Checking,
    UpToDate,
    Available,
    Downloading,
    Verifying,
    ReadyToInstall,
    Failed
}

/** 失败发生在哪一步，决定弹窗里“重试”该重新检查、接着下载还是重新打开安装器。 */
internal enum class AppUpdateAction {
    Check,
    Download,
    Install
}

/** 下载进度：已下载 / 总字节（总数未知时为 0）、最近几秒的速度、当前用的线路。 */
internal data class AppDownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long,
    val source: String?
) {
    val fraction: Float?
        get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null
}

/**
 * 应用自更新的界面状态。
 *
 * release 是最近一次检查拿到的最新正式版；canInstallInApp 为 false 表示当前装的包（debug 包、自己签的包）
 * 和发布版签名不同，系统不允许直接覆盖安装，界面只给“打开下载页”；
 * partialBytes 是上次下了一半留下的字节数，取消或失败后再点下载会接着下。
 */
internal data class AppUpdateState(
    val phase: AppUpdatePhase = AppUpdatePhase.Idle,
    val currentVersion: String = "",
    val release: AppRelease? = null,
    val canInstallInApp: Boolean = true,
    val partialBytes: Long = 0,
    val progress: AppDownloadProgress? = null,
    val checkSource: String? = null,
    val errorMessage: String? = null,
    val failedAction: AppUpdateAction? = null
) {
    val isBusy: Boolean
        get() = phase == AppUpdatePhase.Checking ||
            phase == AppUpdatePhase.Downloading ||
            phase == AppUpdatePhase.Verifying
}

/**
 * MaiDroid 应用本体的在线更新：查 GitHub 最新 Release -> 下载 APK -> 校验 -> 交给系统安装器。
 *
 * 进程级单例，关掉弹窗、离开设置页后下载照常进行，回来能看到最新进度。
 * 每次打开应用进入主页时主界面会调 [checkOnLaunch] 自动查一次，查到新版本就弹窗提醒。
 * 网络部分（同时试直连和所有镜像、用最快的那条）在 [AppUpdateClient]；
 * 每一步都写进终端日志，前缀 `[MaiDroid 更新]`。
 */
internal class AppUpdateManager(
    private val appContext: Context,
    private val currentVersionName: String,
    private val apiUrl: String
) {
    companion object {
        private const val LOG_TAG = "[MaiDroid 更新]"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        // 除了安装包本身，再留出系统解包安装时要用的余量
        private const val FREE_SPACE_MARGIN_BYTES = 64L * 1024 * 1024

        /** 只共享 cacheDir/app-update/ 的 FileProvider，见 res/xml/app_update_paths.xml。 */
        val FILE_PROVIDER_AUTHORITY = "${BuildConfig.APPLICATION_ID}.appupdate"
    }

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var job: Job? = null
    private val currentVersion = VersionNumber.parse(currentVersionName)
    private val files = AppUpdateFiles(appContext.cacheDir)
    private val client = AppUpdateClient(userAgent = "MaiDroid/$currentVersionName")

    private val _state = MutableStateFlow(AppUpdateState(currentVersion = currentVersionName))
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    private val _launchPrompt = MutableStateFlow(false)

    /** 启动时的自动检查查到了新版本、用户还没关掉提醒：主界面据此在主页上弹出更新弹窗。 */
    val launchPrompt: StateFlow<Boolean> = _launchPrompt.asStateFlow()

    // 正在进行的这次检查是不是启动时的自动检查：是的话查到新版本要弹窗提醒，查失败了不打扰用户。
    // 用户在设置页打开更新弹窗后改回 false，结果直接显示在那个弹窗里。
    private var checkingOnLaunch = false

    init {
        // 上次的更新已经装上了：缓存里不比当前新的安装包没用了，启动检查或打开设置页时顺手清掉
        scope.launch(Dispatchers.IO) { files.deleteInstalledOrUnknown(currentVersion) }
    }

    /** 检查更新：同时向直连和所有镜像请求最新 Release，用最先返回的那个和当前版本比较。 */
    fun checkForUpdate() = startCheck(onLaunch = false)

    /**
     * 打开应用进入主页时自动检查一次：和手动检查走同一套流程，
     * 查到新版本（或这一版安装包已下好待安装）时置位 [launchPrompt]，主页弹窗提醒用户更新；
     * 已是最新、连不上都不提示。正在下载或校验时跳过，用户已经在更新了，不打断。
     */
    fun checkOnLaunch() = startCheck(onLaunch = true)

    /** 不再弹启动提醒：用户关掉了提醒弹窗，或者自己在设置页打开了更新弹窗（结果改在那里显示）。 */
    fun dismissLaunchPrompt() {
        checkingOnLaunch = false
        _launchPrompt.value = false
    }

    private fun startCheck(onLaunch: Boolean) {
        if (job?.isActive == true) return
        checkingOnLaunch = onLaunch
        job = scope.launch {
            _state.update {
                it.copy(phase = AppUpdatePhase.Checking, errorMessage = null, failedAction = null)
            }
            val sourceCount = GitHubMirrors.candidatesFor(apiUrl).size
            TerminalLog.append("")
            TerminalLog.append("$LOG_TAG 检查新版本，同时尝试 $sourceCount 条线路，当前 v$currentVersionName")

            val win = client.fetchLatestRelease(apiUrl)
            if (win == null) {
                failCheck("连不上 GitHub 和所有镜像，请检查网络后重试")
                return@launch
            }
            val release = win.value
            TerminalLog.append("$LOG_TAG 经 ${win.mirror.name} 获取到最新版本 ${release.tag}")

            if (currentVersion != null && release.version <= currentVersion) {
                TerminalLog.append("$LOG_TAG 已是最新版本")
                _state.update {
                    it.copy(phase = AppUpdatePhase.UpToDate, release = release, checkSource = win.mirror.name)
                }
                return@launch
            }
            if (release.apk == null) {
                _state.update { it.copy(release = release, checkSource = win.mirror.name) }
                failCheck("最新版本 ${release.tag} 里没有找到安装包")
                return@launch
            }

            val ownSigners = withContext(Dispatchers.IO) {
                installedPackageIdentity(appContext)?.signerSha256.orEmpty()
            }
            val installable = canInstallOverCurrent(release.certSha256, ownSigners)
            if (!installable) {
                TerminalLog.append("$LOG_TAG 当前安装包的签名和发布版不同（调试版或自行构建），不能直接覆盖安装")
            }
            // 换了更新的版本时，别的版本下了一半的包已经没用；这一版下完校验过的包还在就直接待安装
            val (ready, partial) = withContext(Dispatchers.IO) {
                files.deleteOtherVersions(release.version)
                files.apkFile(release.version).exists() to files.partFile(release.version).length()
            }
            _state.update {
                it.copy(
                    phase = if (ready && installable) AppUpdatePhase.ReadyToInstall else AppUpdatePhase.Available,
                    release = release,
                    canInstallInApp = installable,
                    partialBytes = partial,
                    progress = null,
                    checkSource = win.mirror.name
                )
            }
            // 启动检查查到新版本：主页弹窗提醒（待安装时弹窗里直接是“安装”按钮）
            if (checkingOnLaunch) {
                TerminalLog.append("$LOG_TAG 发现新版本 ${release.tag}，弹窗提醒更新")
                _launchPrompt.value = true
            }
        }
    }

    // 检查这一步失败：用户自己点的检查照常报错；启动时的自动检查只记日志、退回空闲，不在设置页挂一条没人问过的失败。
    private fun failCheck(message: String) {
        if (!checkingOnLaunch) {
            fail(AppUpdateAction.Check, message)
            return
        }
        TerminalLog.append("$LOG_TAG 启动时检查更新没有成功：$message")
        _state.update { it.copy(phase = AppUpdatePhase.Idle, progress = null) }
    }

    /**
     * 下载安装包：检查剩余空间 -> 从断点接着下（AppUpdateClient 自动选线路、断了换线路）
     * -> 核对大小、SHA-256、包名、版本号和签名 -> 改名为 .apk 进入待安装。
     */
    fun download() {
        val current = _state.value
        val release = current.release ?: return
        val asset = release.apk ?: return
        if (job?.isActive == true || !current.canInstallInApp) return
        job = scope.launch {
            val part = files.partFile(release.version)
            val (existing, usable) = withContext(Dispatchers.IO) {
                files.dir.mkdirs()
                part.length() to files.dir.usableSpace
            }
            val needed = (asset.size - existing).coerceAtLeast(0L) + FREE_SPACE_MARGIN_BYTES
            if (asset.size > 0 && usable < needed) {
                fail(AppUpdateAction.Download, "手机存储空间不足，还需要约 ${formatBytes(needed - usable)}")
                return@launch
            }

            _state.update {
                it.copy(
                    phase = AppUpdatePhase.Downloading,
                    progress = AppDownloadProgress(existing, asset.size, 0, null),
                    errorMessage = null,
                    failedAction = null
                )
            }
            TerminalLog.append("")
            TerminalLog.append(
                "$LOG_TAG 下载 ${asset.name}（${formatBytes(asset.size)}）" +
                    if (existing > 0) "，从已下载的 ${formatBytes(existing)} 处接着下" else ""
            )

            try {
                client.downloadApk(asset, part, DownloadListener())
            } catch (e: CancellationException) {
                // 用户取消：已下载的部分留着，下次点下载接着下
                TerminalLog.append("$LOG_TAG 已取消下载，已下载的部分保留")
                _state.update {
                    it.copy(phase = AppUpdatePhase.Available, progress = null, partialBytes = part.length())
                }
                throw e
            } catch (e: IOException) {
                _state.update { it.copy(partialBytes = part.length()) }
                fail(AppUpdateAction.Download, "下载失败：${e.message ?: e.javaClass.simpleName}")
                return@launch
            }

            verifyDownloaded(release, part)
        }
    }

    /** 取消下载：停掉协程，并立刻掐断正在读的连接（否则卡住的连接要等读超时才退出）。 */
    fun cancelDownload() {
        if (_state.value.phase != AppUpdatePhase.Downloading) return
        job?.cancel()
        client.abortDownload()
    }

    /** 当前是否已经允许 MaiDroid 安装应用（“安装未知应用”）；没允许时系统安装器会直接拦下。 */
    fun canRequestPackageInstalls(): Boolean =
        runCatching { appContext.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    /** 跳到系统里 MaiDroid 的“安装未知应用”开关；个别系统没有这个页面时退到应用详情页。 */
    fun openInstallPermissionSettings() {
        val packageUri = "package:${appContext.packageName}".toUri()
        val opened = startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri)) ||
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
        if (!opened) TerminalLog.append("$LOG_TAG 打不开“安装未知应用”设置页")
    }

    /**
     * 交给系统安装器。打开前再核对一次安装包（缓存可能被系统清掉，或上次校验到一半应用被关了）。
     * 安装开始后系统会结束 MaiDroid 进程，麦麦和 NapCat 随之停止，装好后重新打开 MaiDroid 即可。
     */
    fun install() {
        val release = _state.value.release ?: return
        if (job?.isActive == true) return
        val apk = files.apkFile(release.version)
        job = scope.launch {
            if (!withContext(Dispatchers.IO) { apk.exists() }) {
                TerminalLog.append("$LOG_TAG 安装包已被系统清理，需要重新下载")
                _state.update {
                    it.copy(
                        phase = AppUpdatePhase.Available,
                        partialBytes = 0,
                        errorMessage = "安装包已被系统清理，请重新下载"
                    )
                }
                return@launch
            }
            val problem = withContext(Dispatchers.IO) {
                installedPackageIdentity(appContext)?.let { installed ->
                    downloadedPackageProblem(archivePackageIdentity(appContext, apk), installed)
                }
            }
            if (problem != null) {
                withContext(Dispatchers.IO) { apk.delete() }
                fail(AppUpdateAction.Download, "$problem，已删除安装包")
                return@launch
            }

            val uri = FileProvider.getUriForFile(appContext, FILE_PROVIDER_AUTHORITY, apk)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_MIME_TYPE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (startActivity(intent)) {
                TerminalLog.append("$LOG_TAG 已打开系统安装器：${release.tag}")
            } else {
                fail(AppUpdateAction.Install, "没有找到系统安装器")
            }
        }
    }

    private suspend fun verifyDownloaded(release: AppRelease, part: File) {
        _state.update { it.copy(phase = AppUpdatePhase.Verifying) }
        TerminalLog.append("$LOG_TAG 下载完成，校验安装包")
        val apk = files.apkFile(release.version)
        val problem = withContext(Dispatchers.IO) {
            // 先在 .part 上核对大小和 SHA-256；系统解析安装包要求 .apk 后缀，所以改名后再核对包名、版本号和签名
            sizeOrChecksumProblem(release, part) ?: run {
                apk.delete()
                if (!part.renameTo(apk)) return@run "安装包改名失败"
                installedPackageIdentity(appContext)?.let { installed ->
                    downloadedPackageProblem(archivePackageIdentity(appContext, apk), installed)
                }
            }
        }
        if (problem != null) {
            withContext(Dispatchers.IO) {
                part.delete()
                apk.delete()
            }
            _state.update { it.copy(partialBytes = 0) }
            fail(AppUpdateAction.Download, "$problem，已删除，请重新下载")
            return
        }
        TerminalLog.append("$LOG_TAG 安装包校验通过：${apk.name}")
        _state.update {
            it.copy(phase = AppUpdatePhase.ReadyToInstall, progress = null, partialBytes = 0)
        }
    }

    private fun sizeOrChecksumProblem(release: AppRelease, part: File): String? {
        val expectedSize = release.apk?.size ?: 0L
        if (expectedSize > 0 && part.length() != expectedSize) {
            return "安装包大小不对（${part.length()} / $expectedSize 字节）"
        }
        val expectedSha256 = release.apkSha256 ?: return null
        return if (fileSha256(part) != expectedSha256) "安装包 SHA-256 与 Release 说明不一致" else null
    }

    private fun startActivity(intent: Intent): Boolean =
        try {
            appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }

    private fun fail(action: AppUpdateAction, message: String) {
        TerminalLog.append("$LOG_TAG 失败：$message")
        _state.update {
            it.copy(
                phase = AppUpdatePhase.Failed,
                progress = null,
                errorMessage = message,
                failedAction = action
            )
        }
    }

    // 进度回调在下载所在的 IO 线程上，StateFlow 的 update 可以跨线程用。
    private inner class DownloadListener : ApkDownloadListener {
        private val speed = DownloadSpeedMeter()

        override fun onSourceChosen(source: String, startByte: Long) {
            speed.reset()
            TerminalLog.append(
                "$LOG_TAG 经 $source 下载" + if (startByte > 0) "，从 ${formatBytes(startByte)} 处接着下" else ""
            )
        }

        override fun onProgress(downloadedBytes: Long, totalBytes: Long, source: String) {
            val bytesPerSecond = speed.sample(System.nanoTime(), downloadedBytes)
            _state.update {
                if (it.phase != AppUpdatePhase.Downloading) {
                    it
                } else {
                    it.copy(progress = AppDownloadProgress(downloadedBytes, totalBytes, bytesPerSecond, source))
                }
            }
        }

        override fun onSourceFailed(source: String, reason: String) {
            TerminalLog.append("$LOG_TAG $source 中断（$reason），换线路接着下")
        }
    }
}
