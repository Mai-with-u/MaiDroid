package org.maiwithu.maidroid.diagnostics

/**
 * 错误码：失败时界面、终端日志、诊断包和预填的 Issue 里都会出现的一串短码，形如 UV-SYNC/NET-DNS。
 *
 * 前半段 [FailureStep] 说明哪一步失败了，每个失败出口都有；后半段 [FailureCause] 是从命令输出里认出来的原因，
 * 认不出时没有后半段。两段都在这个文件里定义完：拿到用户报的码，搜一下就能找到出错的地方和识别规则。
 * 码一经发布就不改名、不复用，新增失败点时加新码。
 */
internal data class Failure(
    val step: FailureStep,
    /** 给用户看的一句话，原来界面上显示的失败说明。 */
    val message: String,
    val cause: FailureCause? = null
) {
    val code: String get() = cause?.let { "${step.code}/${it.code}" } ?: step.code

    /** 写进终端日志的一行：诊断包靠 FAILURE_LOG_PREFIX 从日志文件里找出最近的失败。 */
    fun logLine(): String = "$FAILURE_LOG_PREFIX$code $message"
}

/** 终端日志里失败记录的行首前缀，后面紧跟错误码、一个空格和失败说明。 */
internal const val FAILURE_LOG_PREFIX = "[MaiDroid] 错误码 "

/** 一步失败了：按这一步的输出认原因，拼成带错误码的失败。output 默认用失败说明本身（没有命令输出的失败）。 */
internal fun failureOf(step: FailureStep, message: String, output: String = message): Failure =
    Failure(step, message, diagnoseFailureCause(output))

/** 从日志里一行失败记录读出错误码和说明；不是失败记录返回 null。行首可以带时间等前缀。 */
internal fun parseFailureLogLine(line: String): Pair<String, String>? {
    val rest = line.substringAfter(FAILURE_LOG_PREFIX, missingDelimiterValue = "").takeIf { it.isNotEmpty() }
        ?: return null
    val code = rest.substringBefore(' ')
    if (code.isEmpty() || !code.all { it.isUpperCase() || it.isDigit() || it == '-' || it == '/' }) return null
    return code to rest.substringAfter(' ', missingDelimiterValue = "").trim()
}

/** 错误码前半段：哪一步失败了。label 是诊断包里给人看的名字。 */
internal enum class FailureStep(val code: String, val label: String) {
    // ── 首次引导：配置容器 ─────────────────────────────────────────
    ContainerUnpack("CT-UNPACK", "解包容器资源"),
    ContainerProot("CT-PROOT", "启动 proot"),
    ContainerStorage("CT-STORAGE", "配置共享存储入口"),
    ContainerDns("CT-DNS", "配置容器 DNS"),
    AptMirror("APT-MIRROR", "配置 Debian 软件源"),
    AptInstall("APT-INSTALL", "安装 Git、curl 和证书"),
    ContainerCheck("CT-CHECK", "核对容器文件"),
    OobeUnexpected("MD-OOBE", "首次引导意外出错"),

    // ── 首次引导：安装 MaiBot；依赖三步在线更新也用 ───────────────────
    MaiBotClone("MB-CLONE", "下载 MaiBot 源码"),
    UvInstall("UV-INSTALL", "安装 uv"),
    PythonInstall("PY-INSTALL", "准备 Python 解释器"),
    UvSync("UV-SYNC", "安装 Python 依赖包"),
    VenvCheck("UV-VENV", "核对 Python 依赖"),

    // ── MaiBot 启动（等 WebUI 时的结论，见 MaiBotStartupResult） ───────────
    MaiBotBlocked("MB-BLOCKED", "MaiBot 启动前检查"),
    MaiBotCrash("MB-CRASH", "MaiBot 启动后反复退出"),
    MaiBotNotStarted("MB-NOSTART", "MaiBot 进程没有启动"),
    MaiBotTimeout("MB-TIMEOUT", "等待 MaiBot WebUI 超时"),

    // ── MaiBot 在线更新、切换版本通道 ────────────────────────────────
    UpdatePrecheck("UP-PRECHECK", "在线更新前检查"),
    UpdateFetch("UP-FETCH", "获取上游 MaiBot 版本"),
    UpdateReset("UP-RESET", "切换 MaiBot 源码"),

    // ── QQ（NapCat）接入 ──────────────────────────────────────────
    NapCatPrecheck("QQ-PRECHECK", "安装 NapCat 前检查"),
    NapCatInstall("QQ-INSTALL", "安装 NapCat"),
    NapCatAdapter("QQ-ADAPTER", "安装 QQ 适配器"),
    NapCatAdapterConfig("QQ-CONFIG", "配置 QQ 适配器"),
    NapCatFilesCheck("QQ-FILES", "核对 NapCat 文件"),

    // ── QQ 官方机器人接入 ─────────────────────────────────────────
    QqOfficialPrecheck("QQO-PRECHECK", "安装 QQ 官方机器人适配器前检查"),
    QqOfficialInstall("QQO-INSTALL", "安装 QQ 官方机器人适配器"),
    QqOfficialConfig("QQO-CONFIG", "写入 QQ 官方机器人配置"),
    QqOfficialConnect("QQO-CONNECT", "连接 QQ 开放平台"),

    // ── 容器 SSH ────────────────────────────────────────────────
    SshConfig("SSH-CONFIG", "容器 SSH 配置"),
    SshSetup("SSH-SETUP", "准备容器 SSH"),
    SshStart("SSH-START", "启动容器 sshd"),

    // ── MaiDroid 自更新 ──────────────────────────────────────────
    AppUpdateCheck("APP-CHECK", "检查 MaiDroid 新版本"),
    AppUpdateDownload("APP-DOWNLOAD", "下载 MaiDroid 安装包"),
    AppUpdateVerify("APP-VERIFY", "校验 MaiDroid 安装包"),
    AppUpdateInstall("APP-INSTALL", "打开系统安装器"),

    // ── 清理空间、MaiDroid 自己闪退 ─────────────────────────────────
    StorageCleanup("MD-CLEANUP", "清理空间"),
    AppCrash("MD-CRASH", "MaiDroid 闪退")
}

/** 认出原因后界面上可以给的一个按钮。 */
internal enum class FailureAction(val label: String) {
    NetworkSettings("打开网络设置"),
    DateSettings("打开日期和时间设置"),
    FreeUpStorage("清理空间")
}

/**
 * 错误码后半段：从输出里认出来的原因，带给用户看的标题、建议和可选的按钮。
 * isNetwork 表示网络类原因，自动修复据此决定换源重试还是换别的办法。识别规则见 FailureRules.kt。
 */
internal enum class FailureCause(
    val code: String,
    val title: String,
    val advice: String,
    val action: FailureAction? = null,
    val isNetwork: Boolean = false
) {
    NoNetwork(
        "NET-OFFLINE", "手机没有连上网络",
        "打开 Wi-Fi 或移动数据后重试。",
        FailureAction.NetworkSettings, isNetwork = true
    ),
    Dns(
        "NET-DNS", "域名解析失败",
        "当前网络解析不了下载地址。切换一下 Wi-Fi 和移动数据，或关掉 VPN、代理和“私人 DNS”后重试。",
        FailureAction.NetworkSettings, isNetwork = true
    ),
    Timeout(
        "NET-TIMEOUT", "网络连接超时",
        "下载服务器连不上或太慢。换个网络或过一会儿重试；开着 VPN、代理的话先关掉。",
        isNetwork = true
    ),
    ConnectionLost(
        "NET-CONN", "网络连接被中断",
        "连接被拒绝、重置或中途断开，多半是网络不稳定。换个网络或过一会儿重试。",
        isNetwork = true
    ),
    HttpError(
        "NET-HTTP", "下载服务器返回错误",
        "镜像暂时不可用（返回了 403、404 或 5xx），过一会儿重试。",
        isNetwork = true
    ),
    ClockSkew(
        "TIME-SKEW", "手机时间不对",
        "证书或软件源签名显示还没生效或已经过期，说明手机时间偏差太大。打开“自动设置时间”后重试。",
        FailureAction.DateSettings
    ),
    Tls(
        "NET-TLS", "安全连接失败",
        "证书校验没通过：可能是手机时间不对，或者连的是需要网页登录的公共 Wi-Fi。确认时间自动设置、换个网络后重试。",
        FailureAction.DateSettings, isNetwork = true
    ),
    DiskFull(
        "DISK-FULL", "存储空间不足",
        "手机存储满了。清理出一些空间后重试，设置页的“清理空间”可以删掉 MaiDroid 自己的下载缓存。",
        FailureAction.FreeUpStorage
    ),
    OutOfMemory(
        "MEM-OOM", "内存不足",
        "进程因为内存不足被结束了。关掉其他占内存的应用后重试，或者重启一下手机。"
    ),
    UnsupportedCpu(
        "ABI-ARM64", "这台手机的处理器不受支持",
        "MaiDroid 只能在 64 位 ARM（arm64）手机上运行，请确认装的是 arm64 版安装包。"
    ),
    AptMirrorSyncing(
        "APT-HASH", "软件源文件校验没通过",
        "Debian 镜像正在同步，文件对不上。MaiDroid 已经换别的镜像试过，过几分钟再重试。"
    ),
    AptLocked(
        "APT-LOCK", "软件包管理器正被占用",
        "上一次安装还没结束。等一会儿再重试；还不行就在设置页安全退出后重新打开 MaiDroid。"
    ),
    GitLocked(
        "GIT-LOCK", "MaiBot 源码仓库被锁住",
        "上次操作源码时被中途打断，留下了锁文件。重试一次，MaiDroid 会清掉没人用的锁。"
    ),
    GitBroken(
        "GIT-BROKEN", "MaiBot 源码仓库损坏",
        "源码仓库里有文件读不出来。请反馈问题，附上诊断包。"
    ),
    DatabaseDowngrade(
        "DB-DOWNGRADE", "MaiBot 数据库不能降级",
        "要换过去的版本读不了现在的数据库，已取消，麦麦照常运行。等那个版本跟上后再试。"
    ),
    DependencyConflict(
        "UV-RESOLVE", "依赖版本解不出来",
        "MaiBot 要求的依赖版本互相冲突，通常是上游刚改了依赖。过一阵再更新，或者反馈问题。"
    ),
    DependencyBuild(
        "UV-BUILD", "有依赖包需要现场编译",
        "某个依赖包没有手机能直接用的安装包，容器里也没有编译器。请反馈问题，附上诊断包。"
    ),
    MissingModule(
        "PY-MODULE", "Python 依赖包不完整",
        "MaiBot 启动时缺了某个依赖包。重新装一遍依赖：首次引导里点“重试安装”，装好后可以在设置页“MaiBot 更新”里重试更新。"
    ),
    ConfigSyntax(
        "CFG-TOML", "MaiBot 配置文件格式有误",
        "最近改过 MaiBot 的配置文件的话，检查改动处的引号、括号和等号，改好后重启麦麦。"
    ),
    PortInUse(
        "PORT-BUSY", "端口被占用",
        "要用的端口被别的程序占着。在设置页安全退出后重新打开 MaiDroid；手机上还开着别的 MaiBot 或 Termux 服务的话先关掉。"
    ),
    DatabaseLocked(
        "DB-LOCKED", "MaiBot 数据库被占用",
        "可能同时跑着两个麦麦。在设置页安全退出后重新打开 MaiDroid。"
    )
}
