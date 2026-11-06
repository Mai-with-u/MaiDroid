package org.maiwithu.maidroid.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.maiwithu.maidroid.BuildConfig
import org.maiwithu.maidroid.appupdate.formatBytes
import org.maiwithu.maidroid.maibot.MaiBotProcessSupervisor
import org.maiwithu.maidroid.maibot.maiBotProjectVersion
import org.maiwithu.maidroid.maibot.update.maiBotBranchLabel
import org.maiwithu.maidroid.platform.napcat.NapCatFiles
import org.maiwithu.maidroid.platform.qqofficial.QqOfficialAdapterFiles
import org.maiwithu.maidroid.platform.qqofficial.QqOfficialAdapterConfig
import org.maiwithu.maidroid.runtime.ContainerDns
import org.maiwithu.maidroid.runtime.NetworkStatus
import org.maiwithu.maidroid.runtime.RuntimePaths
import org.maiwithu.maidroid.runtime.RuntimeReadiness
import org.maiwithu.maidroid.settings.AppSettings
import org.maiwithu.maidroid.terminal.TerminalLog
import org.maiwithu.maidroid.webui.MaiBotWebUi
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * “反馈问题”背后的收集者：读版本、机型、权限、网络、容器和进程状态，从日志文件里找最近的失败，
 * 打码后拼成诊断报告、剪贴板文字、预填的 Issue 地址和 zip 诊断包。
 *
 * 只读不写（除了用户选的诊断包位置），不创建会启动后台轮询的平台管理器：NapCat、QQ 官方机器人只看文件。
 * 由 AppDependencies 懒创建；prepare 在 IO 线程上跑，几 MB 日志打码要一两秒，界面显示“正在收集”。
 */
internal class DiagnosticsManager(
    context: Context,
    private val settings: AppSettings,
    private val paths: RuntimePaths,
    private val readiness: RuntimeReadiness,
    private val files: DiagnosticsFiles,
    private val logRecorder: TerminalLogRecorder,
    private val maiBotProcess: MaiBotProcessSupervisor
) {
    private companion object {
        // 诊断包最多带最近几份终端日志（每份最多 2 MB），以及 MaiBot 最新一份日志的最后 1 MB
        const val MAX_TERMINAL_LOG_FILES = 4
        const val MAIBOT_LOG_TAIL_BYTES = 1024L * 1024
        const val MAX_CRASH_FILES = 5

        val generatedAtFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx")
        val fileNameFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        val exitTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
    }

    private val appContext = context.applicationContext

    /**
     * 收集一份反馈材料：读日志 -> 认出最近的失败 -> 拼报告 -> 整体打码 -> 拼剪贴板文字和 Issue 地址。
     * failure 是从哪个失败点打开的反馈，报告开头会写它的错误码；从设置页、终端页打开时传 null。
     */
    suspend fun prepare(failure: Failure?): FeedbackPackage = withContext(Dispatchers.IO) {
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val redactor = LogRedactor(knownSecrets())

        // 日志文件按时间先后排：最近的失败、剪贴板里的日志尾巴都要按发生顺序读
        val terminalLogs = logRecorder.recentFiles().take(MAX_TERMINAL_LOG_FILES).reversed()
        val terminalTexts = terminalLogs.map { it to redactor.redact(readTail(it, TerminalLogFiles.FILE_LIMIT_BYTES)) }
        val allLogLines = terminalTexts.flatMap { (_, text) -> text.lines() }

        val maiBotVersion = runCatching { maiBotProjectVersion(paths.maiBotPyproject.readText()) }.getOrNull()
        // 失败说明会进 Issue 标题和链接，也先打码（命令输出里偶尔带着地址和令牌）
        val shownFailure = failure?.let { it.copy(message = redactor.redact(it.message)) }
        val report = DiagnosticReport(
            generatedAt = generatedAtFormat.withZone(zone).format(now),
            failure = shownFailure,
            appVersion = BuildConfig.VERSION_NAME,
            maiBotVersion = maiBotVersion,
            device = "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}",
            sections = listOf(
                appSection(maiBotVersion),
                deviceSection(),
                permissionSection(),
                ReportSection("网络", listOf(
                    "当前网络" to NetworkStatus.read(appContext).describe(),
                    "系统 DNS" to ContainerDns.systemServers(appContext).joinToString(" ").ifBlank { "读不到" }
                )),
                containerSection(),
                processSection(),
                exitReasonSection(zone)
            ),
            recentFailures = recentFailuresFromLog(allLogLines),
            notes = listOf(
                "诊断包里的令牌、密钥、QQ 号、手机号和公网 IP 已自动打码；聊天内容没有处理，发出前可以打开看一眼。"
            )
        )
        val reportText = redactor.redact(formatReport(report))

        val entries = buildList {
            add(BundleEntry("report.txt", reportText.toByteArray()))
            terminalTexts.forEach { (file, text) -> add(BundleEntry("terminal/${file.name}", text.toByteArray())) }
            newestMaiBotLog()?.let { log ->
                add(BundleEntry("maibot/${log.name}", redactor.redact(readTail(log, MAIBOT_LOG_TAIL_BYTES)).toByteArray()))
            }
            crashFiles().forEach { crash -> add(BundleEntry("crashes/${crash.name}", redactor.redact(crash.readText()).toByteArray())) }
        }

        FeedbackPackage(
            failure = shownFailure,
            reportText = reportText,
            clipboardText = feedbackClipboardText(reportText, allLogLines.filter { it.isNotBlank() }),
            issueUrl = FeedbackIssue.newIssueUrl(report),
            entries = entries,
            fileName = "maidroid-diagnostics-${fileNameFormat.withZone(zone).format(now)}.zip",
            terminalLogCount = terminalTexts.size,
            crashCount = entries.count { it.name.startsWith("crashes/") },
            includesMaiBotLog = entries.any { it.name.startsWith("maibot/") }
        )
    }

    /** 把诊断包写进用户在“另存为”里选的位置；失败返回原因。 */
    suspend fun saveBundle(pkg: FeedbackPackage, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val out = appContext.contentResolver.openOutputStream(uri) ?: error("打不开保存位置")
            out.use { writeDiagnosticBundle(it, pkg.entries) }
        }.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
    }

    // ── 报告里的各节 ─────────────────────────────────────────────────

    private fun appSection(maiBotVersion: String?): ReportSection {
        val (branch, commit) = readGitHead(paths.maiBotGitDir)
        return ReportSection("应用", listOf(
            "MaiDroid" to "${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}，${BuildConfig.BUILD_TYPE}）",
            "安装来源" to if (BuildConfig.DEBUG) "自己构建的调试包" else "发布包",
            "首次引导" to if (settings.setupComplete) "已完成" else "未完成",
            "MaiBot" to listOfNotNull(
                maiBotVersion ?: "读不到版本",
                branch?.let(::maiBotBranchLabel),
                commit?.take(7)
            ).joinToString(" · ")
        ))
    }

    private fun deviceSection(): ReportSection {
        val storage = runCatching { StatFs(paths.rootfsDir.parentFile?.absolutePath ?: "/data") }.getOrNull()
        val memory = ActivityManager.MemoryInfo().also {
            appContext.getSystemService(ActivityManager::class.java).getMemoryInfo(it)
        }
        return ReportSection("设备", listOf(
            "型号" to "${Build.MANUFACTURER} ${Build.MODEL}（${Build.DEVICE}）",
            "系统" to "Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）· ${Build.DISPLAY}",
            "处理器架构" to Build.SUPPORTED_ABIS.joinToString(", "),
            "存储" to (storage?.let { "可用 ${formatBytes(it.availableBytes)} / 共 ${formatBytes(it.totalBytes)}" } ?: "读不到"),
            "内存" to "可用 ${formatBytes(memory.availMem)} / 共 ${formatBytes(memory.totalMem)}" +
                if (memory.lowMemory) "（系统认为内存不足）" else ""
        ))
    }

    private fun permissionSection(): ReportSection {
        val storageGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
        val batteryIgnored = appContext.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(appContext.packageName)
        val notifications = NotificationManagerCompat.from(appContext).areNotificationsEnabled()
        fun granted(value: Boolean) = if (value) "已开启" else "未开启"
        return ReportSection("权限", listOf(
            "所有文件访问" to granted(storageGranted),
            "通知" to granted(notifications),
            "忽略电池优化" to granted(batteryIgnored)
        ))
    }

    private fun containerSection(): ReportSection =
        ReportSection("容器", readiness.maiBotLaunchRequirements().map { requirement ->
            requirement.name to if (requirement.ready) "就绪" else "未就绪（${requirement.hint}）"
        })

    private fun processSection(): ReportSection {
        val status = maiBotProcess.status.value
        val maiBot = when {
            status.launchBlockedReason != null -> "没有启动：${status.launchBlockedReason}"
            status.running -> "运行中"
            else -> "没有运行"
        } + if (status.exitCount > 0) "，意外退出 ${status.exitCount} 次（最后退出码 ${status.lastExitCode ?: "未知"}）" else ""
        return ReportSection("运行状态", listOf(
            "MaiBot" to maiBot,
            "QQ（NapCat）" to if (NapCatFiles(paths).isNapCatReady()) "已安装" else "未安装",
            "QQ 官方机器人适配器" to if (QqOfficialAdapterFiles(paths).isInstalled()) "已安装" else "未安装",
            "容器 SSH" to if (settings.containerSshEnabled) "已开启" else "未开启"
        ))
    }

    // Android 11 起系统会记下应用进程每次是怎么结束的：被系统回收、闪退、无响应、用户强行停止……
    // 排查“麦麦老是自己掉线”时，这是唯一能看出是不是被系统杀了后台的地方。
    private fun exitReasonSection(zone: ZoneId): ReportSection {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ReportSection("最近几次进程结束", listOf("记录" to "Android 11 以下系统不提供"))
        }
        val reasons = runCatching {
            appContext.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(null, 0, MAX_EXIT_REASONS)
        }.getOrDefault(emptyList())
        if (reasons.isEmpty()) return ReportSection("最近几次进程结束", listOf("记录" to "没有记录"))
        return ReportSection("最近几次进程结束", reasons.map { info ->
            val time = exitTimeFormat.withZone(zone).format(Instant.ofEpochMilli(info.timestamp))
            time to listOfNotNull(exitReasonLabel(info.reason), info.description?.takeIf { it.isNotBlank() })
                .joinToString("：")
        })
    }

    // ── 打码要用的具体值 ─────────────────────────────────────────────

    // MaiDroid 能读到的密钥和账号：读不到的跳过，打码时短于 4 个字符的值不替换。
    private fun knownSecrets(): List<LogRedactor.Secret> = buildList {
        fun add(label: String, value: String?) {
            if (!value.isNullOrBlank()) add(LogRedactor.Secret(label, value))
        }
        add("WebUI令牌", MaiBotWebUi.readAccessTokenFromConfig(paths))
        add("WebUI令牌", MaiBotWebUi.extractAccessTokenFromLogs(TerminalLog.logs.value))
        val napCat = NapCatFiles(paths)
        add("NapCat令牌", readTextOrNull(napCat.adapter.oneBotTokenFile)?.trim())
        add("NapCat管理令牌", readTextOrNull(napCat.webUiConfig)?.let { runCatching { JSONObject(it).optString("token") }.getOrNull() })
        val qqOfficial = QqOfficialAdapterConfig.read(readTextOrNull(QqOfficialAdapterFiles(paths).configFile()))
        add("AppID", qqOfficial.appId)
        add("AppSecret", qqOfficial.appSecret)
        add("SSH密码", settings.containerSshRootPassword)
        val maiBotConfigDir = File(paths.maiBotDir, "config")
        readTextOrNull(File(maiBotConfigDir, "model_config.toml"))?.let { text ->
            API_KEY_LINE.findAll(text).forEach { add("API Key", it.groupValues[1]) }
        }
        readTextOrNull(File(maiBotConfigDir, "bot_config.toml"))?.let { text ->
            QQ_ACCOUNT_LINE.findAll(text).forEach { add("QQ号", it.groupValues[1]) }
        }
        // NapCat 登录后按 QQ 号另存的配置文件名，以及 QQ 本地登录数据的目录名，都是登录过的 QQ 号
        napCat.configDir.listFiles()?.forEach { file ->
            NAPCAT_ACCOUNT_FILE.matchEntire(file.name)?.let { add("QQ号", it.groupValues[1]) }
        }
        napCat.qqLoginDataDir.listFiles()?.forEach { dir ->
            if (dir.name.length >= 5 && dir.name.all(Char::isDigit)) add("QQ号", dir.name)
        }
    }

    // ── 文件 ────────────────────────────────────────────────────────

    private fun newestMaiBotLog(): File? =
        File(paths.maiBotDir, "logs").listFiles { file -> file.isFile && file.name.startsWith("app_") }
            ?.maxByOrNull { it.lastModified() }

    private fun crashFiles(): List<File> =
        files.crashesDir.listFiles { file -> file.isFile && file.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            ?.take(MAX_CRASH_FILES)
            .orEmpty()

    private fun readTextOrNull(file: File): String? =
        runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()
}

/** 一份准备好的反馈材料：打码后的报告、剪贴板文字、Issue 地址和诊断包内容。 */
internal class FeedbackPackage(
    val failure: Failure?,
    val reportText: String,
    val clipboardText: String,
    val issueUrl: String,
    val entries: List<BundleEntry>,
    val fileName: String,
    val terminalLogCount: Int,
    val crashCount: Int,
    val includesMaiBotLog: Boolean
)

private val API_KEY_LINE = Regex("""(?m)^\s*api_key\s*=\s*["']([^"'\n]+)["']""")
private val QQ_ACCOUNT_LINE = Regex("""(?m)^\s*qq_account\s*=\s*["']?(\d{5,12})""")
private val NAPCAT_ACCOUNT_FILE = Regex("""(?:onebot11|napcat)_(\d{5,12})\.json""")

/** 读 MaiBot 仓库当前所在的分支和提交，不跑 git：.git/HEAD 指向的引用，再从散放的引用文件或 packed-refs 里找提交。 */
private fun readGitHead(gitDir: File): Pair<String?, String?> {
    val head = runCatching { File(gitDir, "HEAD").readText().trim() }.getOrNull() ?: return null to null
    if (!head.startsWith("ref: ")) return null to head
    val ref = head.removePrefix("ref: ").trim()
    val branch = ref.removePrefix("refs/heads/")
    val commit = runCatching { File(gitDir, ref).readText().trim() }.getOrNull()
        ?: runCatching {
            File(gitDir, "packed-refs").readLines().firstOrNull { it.endsWith(" $ref") }?.substringBefore(' ')
        }.getOrNull()
    return branch to commit
}

// 诊断报告列出、以及找原生闪退时看的进程结束记录条数
private const val MAX_EXIT_REASONS = 5

/**
 * 系统记下的最近几次原生代码闪退和应用无响应（Android 11 起才有），给 CrashRecorder 补上它自己记不到的闪退。
 * 普通的 Java 闪退不在这里：CrashRecorder 自己记了，还带着调用栈和闪退前的终端输出。
 */
internal fun recentSystemCrashes(context: Context): List<SystemCrash> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
    return runCatching {
        context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(null, 0, MAX_EXIT_REASONS)
            .filter { it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || it.reason == ApplicationExitInfo.REASON_ANR }
            .map { info ->
                // 例如“原生代码闪退（crash）”，提示里是“MaiDroid 上次闪退：原生代码闪退（crash）”
                val detail = info.description?.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
                SystemCrash(info.timestamp, exitReasonLabel(info.reason) + detail)
            }
    }.getOrDefault(emptyList())
}

/** 系统记录的进程结束原因（ApplicationExitInfo.REASON_*）换成中文。 */
private fun exitReasonLabel(reason: Int): String = when (reason) {
    ApplicationExitInfo.REASON_EXIT_SELF -> "自己退出"
    ApplicationExitInfo.REASON_SIGNALED -> "被信号结束"
    ApplicationExitInfo.REASON_LOW_MEMORY -> "内存不足被系统回收"
    ApplicationExitInfo.REASON_CRASH -> "闪退"
    ApplicationExitInfo.REASON_CRASH_NATIVE -> "原生代码闪退"
    ApplicationExitInfo.REASON_ANR -> "应用无响应"
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "启动失败"
    ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "权限变更"
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "占用资源过多被系统结束"
    ApplicationExitInfo.REASON_USER_REQUESTED -> "用户强行停止"
    ApplicationExitInfo.REASON_USER_STOPPED -> "用户停止"
    ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "依赖的进程结束"
    ApplicationExitInfo.REASON_OTHER -> "其他（厂商系统的后台清理多半记在这里）"
    else -> "未知（$reason）"
}
