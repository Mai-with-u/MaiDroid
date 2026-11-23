package org.maiwithu.maidroid.platform.snowluma

import org.maiwithu.maidroid.platform.qq.LinuxQqRuntimeDeps
import org.maiwithu.maidroid.platform.qq.QqCustomSource
import org.maiwithu.maidroid.platform.qq.QqOneBot
import org.maiwithu.maidroid.runtime.AptMirror
import org.maiwithu.maidroid.runtime.AptMirrors
import org.maiwithu.maidroid.runtime.ContainerScript
import org.maiwithu.maidroid.runtime.ExitTarget
import org.maiwithu.maidroid.runtime.GitHubMirror
import org.maiwithu.maidroid.runtime.GitHubMirrors
import org.maiwithu.maidroid.runtime.InContainerPaths
import org.maiwithu.maidroid.runtime.killMatchingProcessesScript
import org.maiwithu.maidroid.runtime.requestExitScript
import org.maiwithu.maidroid.runtime.shellQuote

/**
 * SnowLuma 客户端在容器里的安装、启动和清理脚本。
 *
 * SnowLuma 不自己登录 QQ：容器里先在 Xvfb 上跑一个原版 LinuxQQ，SnowLuma 每 1.5 秒找一次 QQ 主进程，
 * 用 ptrace 把它的原生组件注入进去，再给 MaiBot 开 OneBot WebSocket（地址、端口、token 和 NapCat 一样，见 QqOneBot）。
 * 登录二维码只画在 QQ 自己的窗口里，所以 Xvfb 用 -fbdir 把屏幕写成文件，宿主侧读出来给用户看、替用户点。
 *
 * 原官方仓库不可用后，SnowLuma 发行包由用户填写自定义仓库或直链下载，优先匹配 v2.4.2 并在找不到时回退最新 Release
 * 的 linux-arm64 完整包；安装时会核对并记录实际 sha256 与官方 v2.4.2 的差异，LinuxQQ 仍按 sha256 严格校验。
 */
internal object SnowLumaScripts {
    /** 优先匹配的 SnowLuma 目标版本（GitHub Release 的 tag 去掉 v）。 */
    const val VERSION = "2.4.2"

    /** 原官方 v2.4.2 linux-arm64 完整版发行包的 sha256，用于日志提示是否被重新打包或修改。 */
    const val OFFICIAL_BUNDLE_SHA256 = "c4bcff22828fb465d6fbd8caa375e82432fd3af684b297c43f2c428a2de2200c"

    /** SnowLuma 绑定的 LinuxQQ 版本（安装包名里的写法：版本号_发布日期），和 NapCat 共用 /opt/QQ。 */
    const val QQ_VERSION = "3.2.32_260812"

    /**
     * 同一版 QQ 装好后 /opt/QQ/resources/app/package.json 里写的版本号：版本号-构建号，和包名里的发布日期对不上，
     * 只能单独记（2026-11 真机上读出来的）。安装前拿它判断容器里的 QQ 是不是这一版；升级 QQ 时和 QQ_VERSION 一起改。
     */
    const val QQ_PACKAGE_JSON_VERSION = "3.2.32-52194"
    private const val QQ_PACKAGE = "QQ_${QQ_VERSION}_arm64_01.deb"
    private const val QQ_SHA256 = "8796ccfd66acc025ef18db37185532d40bc8c58921e17da6d28c295acbcf8f92"

    // QQ 官方下载地址在前，GitHub 备份在后（经 GitHub 镜像），哪个下到都要对得上 sha256
    private const val QQ_OFFICIAL_URL = "https://qqdl.gtimg.cn/qqfile/QQNT/9.9.33/release/3f89efc5/$QQ_PACKAGE"
    private const val QQ_GITHUB_URL =
        "https://github.com/Rodert/qq-versions/releases/download/qq-packages-20260813-1d08f1d4/$QQ_PACKAGE"

    // ── 容器内路径（宿主侧对应位置见 SnowLumaFiles） ─────────────────────────
    const val APP_DIR = "/root/snowluma/app"
    const val DATA_DIR = "/root/snowluma/data"
    const val LAUNCHER = "/root/snowluma-launcher.sh"
    const val READY_MARKER = "/root/.maidroid/snowluma_ready"
    const val CONSENT_MARKER = "/root/.maidroid/snowluma_consent"
    const val WEBUI_PASSWORD_FILE = "/root/.maidroid/snowluma_webui_password"
    const val WEBUI_PORT_FILE = "/tmp/.maidroid-snowluma-webui-port"
    const val SCREEN_DIR = "/tmp/snowluma-screen"
    const val SCREEN_FILE = "$SCREEN_DIR/Xvfb_screen0"
    const val DISPLAY_FILE = "/tmp/.maidroid-snowluma-display"
    const val PROGRESS_FILE = "/tmp/snowluma_progress"
    const val PROGRESS_DESCRIPTION_FILE = "/tmp/snowluma_progress_des"

    // QQ 里的 hook 和 SnowLuma 之间的 unix 套接字目录，两边必须一致：不设时 SnowLuma 按 QQ 进程的真实 uid 拼目录，
    // proot -0 下 QQ 看到的是 0、Android 上真实的是应用 uid，两边会对不上。路径要短，套接字路径有 108 字节上限。
    private const val HOOK_RUNTIME_DIR = "/tmp/snowluma-hook"

    // /proc/locks 的替身，SnowLuma 运行期间由宿主侧 QqProcLocksSync 定时采集原子更新
    private const val PROC_LOCKS = InContainerPaths.PROC_LOCKS_STAND_IN
    private const val DOWNLOAD_DIR = "/root/.cache/maidroid-snowluma"

    /** Xvfb 屏幕大小：QQ 登录窗口和登录后的主窗口都放得下，屏幕文件约 4 MB。 */
    const val SCREEN_WIDTH = 1280
    const val SCREEN_HEIGHT = 800

    /** WebUI 默认端口；被别的程序占着时监管器换一个空端口，经这个环境变量交给 SnowLuma。 */
    const val WEBUI_DEFAULT_PORT = 5099
    const val WEBUI_PORT_ENV = "SNOWLUMA_WEBUI_PORT"

    // 节点保育（launcher 内）：节点意外退出时原地重起新节点（QQ 和它的登录不动），连续失败上限与「稳定窗口」清零线。
    // 宿主侧请节点保存退出时写下标记文件，保育循环见到就不再重启，免得把「保存退出」当成意外死亡又拉起来。
    private const val NODE_STOP_MARKER = "/tmp/.maidroid-snowluma-stop"
    private const val NODE_RESTART_LIMIT = 3
    private const val NODE_STABLE_SECONDS = 600

    // 停止时等 SnowLuma、QQ 自己退出的上限，超时就照常强制清理。
    private const val SNOWLUMA_EXIT_TIMEOUT_SECONDS = 20
    private const val QQ_EXIT_TIMEOUT_SECONDS = 20

    /**
     * SnowLuma 的两个 node 进程（守护进程和它拉起的工作进程，命令行一样）。收到 SIGTERM 后守护进程转给工作进程，
     * 工作进程关掉 OneBot、WebUI，写完日志再退出，两个都发也没关系（重复的退出请求会被忽略）。
     */
    val SNOWLUMA_EXIT_TARGET = ExitTarget(pattern = "snowluma.app.index[.]mjs", exclude = "awk", processName = "SnowLuma")

    /** LinuxQQ 主进程，排除命令行带 --type= 的 zygote、utility 子进程；和 NapCat 起 QQ 用的参数一样。 */
    val QQ_EXIT_TARGET = ExitTarget(pattern = "qq --no-sandbox --disable-gpu", exclude = "--type=", processName = "QQ")

    /**
     * 首次安装（或从自定义地址重装）：
     * 写 OneBot token、WebUI 密码和协议同意记录 -> 装 Xvfb、xdotool、jq、中文字体 -> 没有这版 LinuxQQ 就下载校验后装上 ->
     * 补齐 LinuxQQ 运行必需的共享库（libnss3/libgbm1 等）并挡住 QQ 的热更新 ->
     * 从用户指定的自定义来源下载 SnowLuma 发行包、验证归档并对比官方 sha256、解到程序目录 ->
     * 写全局 OneBot 配置 -> 打完成标记 -> 生成启动脚本。
     */
    fun install(
        token: String,
        webUiPassword: String,
        source: QqCustomSource,
        aptMirrors: List<AptMirror> = AptMirrors.ALL,
        gitHubMirrors: List<GitHubMirror> = GitHubMirrors.ALL
    ): ContainerScript = ContainerScript(
        body = listOf(
            "set -e",
            AptMirrors.shellFunctions(aptMirrors),
            GitHubMirrors.shellFunctions(gitHubMirrors),
            // 和 NapCat 共用一份 OneBot token：装过 NapCat 的沿用它的，MaiBot 侧的适配器配置不用改
            QqOneBot.ensureTokenScript(token, variable = "onebot_token"),
            installBody(webUiPassword, source),
            prepareRuntimeBody(),
            "maidroid_progress 0.9 ${shellQuote("SnowLuma install completed")}"
        ).joinToString("\n"),
        workingDirectory = "/root"
    )

    /** 每次启动前执行：检测并自愈补装缺失的 LinuxQQ 运行依赖库，补齐目录并重新生成启动脚本。 */
    fun prepareRuntime(aptMirrors: List<AptMirror> = AptMirrors.ALL): ContainerScript = ContainerScript(
        body = listOf(
            "set -e",
            AptMirrors.shellFunctions(aptMirrors),
            LinuxQqRuntimeDeps.ensureScript(
                logPrefix = "[SnowLuma]",
                failOnError = false,
                requireQqInstalled = true
            ),
            prepareRuntimeBody()
        ).joinToString("\n"),
        workingDirectory = "/root"
    )

    /** 前台直接以 exec bash 接管启动脚本，省掉额外 sh 与 script 伪终端进程。 */
    fun launch(): ContainerScript = ContainerScript(
        body = "cd /root && exec bash $LAUNCHER",
        workingDirectory = "/root",
        excludeTopCpuCluster = true
    )

    /** 停止时先请 SnowLuma 保存好数据退出（QQ 不会因此下线）。结果用 requestedExitOf 读。 */
    fun requestSnowLumaExit(): ContainerScript = ContainerScript(
        body = listOf(
            "set +e",
            // 写下停机标记，launcher 里的节点保育循环见到它就不再重启节点（见 prepareRuntimeBody 的保育注释）
            "printf stop > $NODE_STOP_MARKER 2>/dev/null || true",
            requestExitScript(SNOWLUMA_EXIT_TARGET, timeoutSeconds = SNOWLUMA_EXIT_TIMEOUT_SECONDS, logPrefix = "[SnowLuma] ")
        ).joinToString("\n"),
        workingDirectory = "/root"
    )

    /** 再请 QQ 主进程正常下线退出；QQ 退出后启动脚本跟着退出，放到后台的 Xvfb 由 cleanup 清掉。 */
    fun requestQqExit(): ContainerScript = ContainerScript(
        body = listOf(
            "set +e",
            requestExitScript(QQ_EXIT_TARGET, timeoutSeconds = QQ_EXIT_TIMEOUT_SECONDS, logPrefix = "[SnowLuma] ")
        ).joinToString("\n"),
        workingDirectory = "/root"
    )

    /** 结束残留的 Xvfb、qq、SnowLuma 和启动脚本，删掉 X11 锁、套接字、屏幕文件和记下的显示号。 */
    fun cleanup(): ContainerScript = ContainerScript(
        body = listOf(
            "set +e",
            killMatchingProcessesScript(
                pattern = "Xvfb |qq --no-sandbox|snowluma-launcher[.]sh|snowluma.app.index[.]mjs",
                processName = "stale process",
                logPrefix = "[SnowLuma] "
            ),
            "rm -f /tmp/.X*-lock /tmp/.X11-unix/X* $DISPLAY_FILE $SCREEN_FILE $PROC_LOCKS.new $NODE_STOP_MARKER",
            // 停止时清空替身内容而不删文件，proot 每条命令启动都会绑定该路径
            ": > $PROC_LOCKS",
            "exit 0"
        ).joinToString("\n"),
        workingDirectory = "/root"
    )

    /**
     * 替用户在 QQ 窗口的 (x, y)（屏幕像素）点一下：扫码页的“刷新二维码”、快速登录的“登录”按钮都靠它。
     * 没有窗口管理器，点击直接落到指针下面的窗口上。
     */
    fun click(display: Int, x: Int, y: Int): ContainerScript = ContainerScript(
        body = "DISPLAY=:$display xdotool mousemove $x $y click 1",
        workingDirectory = "/root"
    )

    private fun installBody(webUiPassword: String, source: QqCustomSource): String =
        """
        maidroid_progress() {
            printf "%s\n" "${'$'}1" > $PROGRESS_FILE
            printf "%s\n" "${'$'}2" > $PROGRESS_DESCRIPTION_FILE
            printf "[SnowLuma] %s\n" "${'$'}2"
        }

        # 依次试每个地址下载到 $1，同一来源支持最多 3 次 curl -C - 断点续传重试，下到的 sha256 必须是 $2（用于严格校验固定版本的 LinuxQQ deb）。
        maidroid_snowluma_fetch() {
            fetch_out="${'$'}1"
            fetch_sha="${'$'}2"
            shift 2
            for fetch_url in "${'$'}@"; do
                rm -f "${'$'}fetch_out.part"
                printf "[SnowLuma] downloading %s\n" "${'$'}fetch_url"
                fetch_attempt=1
                while [ "${'$'}fetch_attempt" -le 3 ]; do
                    curl_rc=0
                    curl -fsSL -C - --connect-timeout 20 --speed-limit 1024 --speed-time 30 \
                        -o "${'$'}fetch_out.part" "${'$'}fetch_url" || curl_rc=${'$'}?
                    if [ "${'$'}curl_rc" -eq 0 ]; then
                        fetch_got="${'$'}(sha256sum "${'$'}fetch_out.part" | awk '{print ${'$'}1}')"
                        if [ "${'$'}fetch_got" = "${'$'}fetch_sha" ]; then
                            mv "${'$'}fetch_out.part" "${'$'}fetch_out"
                            return 0
                        fi
                        printf "[SnowLuma] checksum mismatch (got %s), trying next source\n" "${'$'}fetch_got"
                        rm -f "${'$'}fetch_out.part"
                        break
                    fi
                    if [ "${'$'}curl_rc" -eq 22 ] && [ ! -s "${'$'}fetch_out.part" ]; then
                        printf "[SnowLuma] source unavailable (http error), trying next source\n"
                        rm -f "${'$'}fetch_out.part"
                        break
                    fi
                    if [ "${'$'}curl_rc" -eq 22 ] || [ "${'$'}curl_rc" -eq 33 ]; then
                        rm -f "${'$'}fetch_out.part"
                    fi
                    printf "[SnowLuma] download attempt %s/3 failed (rc=%s), retrying\n" "${'$'}fetch_attempt" "${'$'}curl_rc"
                    fetch_attempt=${'$'}((fetch_attempt + 1))
                    sleep 2
                done
                rm -f "${'$'}fetch_out.part"
            done
            rm -f "${'$'}fetch_out.part"
            return 1
        }

        # 从自定义源下载 SnowLuma 发行包：跳过官方固定 sha256 限制，但立即用 tar -tzf 检查压缩包完整性，
        # 避免把镜像站限流返回的 200 HTML 页面当成归档解包。
        maidroid_snowluma_fetch_archive() {
            archive_out="${'$'}1"
            archive_url="${'$'}2"
            for fetch_url in ${'$'}(maidroid_download_urls "${'$'}archive_url"); do
                rm -f "${'$'}archive_out.part"
                printf "[SnowLuma] downloading %s\n" "${'$'}fetch_url"
                if curl -fsSL --connect-timeout 20 --speed-limit 1024 --speed-time 30 \
                    -o "${'$'}archive_out.part" "${'$'}fetch_url" && \
                    tar -tzf "${'$'}archive_out.part" >/dev/null 2>&1; then
                    mv "${'$'}archive_out.part" "${'$'}archive_out"
                    return 0
                fi
                printf "[SnowLuma] download or archive check failed, trying next source\n"
            done
            rm -f "${'$'}archive_out.part"
            return 1
        }

        # 查 GitHub Releases 接口挑出 linux-arm64 完整版 .tar.gz（排除 -lite），找到后经镜像下载并检验归档。
        maidroid_snowluma_fetch_github_release() {
            api_url="${'$'}1"
            jq_filter="${'$'}2"
            for api_candidate in ${'$'}(maidroid_download_urls "${'$'}api_url"); do
                printf "[SnowLuma] querying release metadata: %s\n" "${'$'}api_candidate"
                api_json="${'$'}(curl -fsSL --connect-timeout 15 --max-time 30 "${'$'}api_candidate" 2>/dev/null || true)"
                [ -n "${'$'}api_json" ] || continue
                asset_url="${'$'}(printf '%s' "${'$'}api_json" | jq -r "${'$'}jq_filter" 2>/dev/null || true)"
                if [ -n "${'$'}asset_url" ] && [ "${'$'}asset_url" != "null" ]; then
                    if maidroid_snowluma_fetch_archive $DOWNLOAD_DIR/snowluma.tar.gz "${'$'}asset_url"; then
                        return 0
                    fi
                fi
            done
            return 1
        }

        maidroid_progress 0.05 "Prepare SnowLuma install"
        mkdir -p /root/.maidroid $DATA_DIR/config $DOWNLOAD_DIR
        if [ ! -s $WEBUI_PASSWORD_FILE ]; then
            printf "%s" ${shellQuote(webUiPassword)} > $WEBUI_PASSWORD_FILE
            chmod 600 $WEBUI_PASSWORD_FILE 2>/dev/null || true
        fi
        # 用户是在消息平台页看过风险与协议说明、点了确认才走到这里的，记下来，启动时据此替用户确认协议
        printf "SnowLuma %s agreed %s\n" $VERSION "${'$'}(date -u +%Y-%m-%dT%H:%M:%SZ)" > $CONSENT_MARKER

        # 上次装到一半被打断留下的 dpkg 半装状态先收拾掉；apt 带超时，下载失败会自动换源。
        # xdotool 往 QQ 窗口里点击，jq 用来从自定义仓库接口挑选 arm64 资产，中文字体让 QQ 窗口文字不显示成方块。
        maidroid_progress 0.10 "Install system packages"
        export DEBIAN_FRONTEND=noninteractive
        if ! dpkg --configure -a; then
            printf "[SnowLuma] dpkg --configure -a failed, apt-get -f install will try to repair\n"
        fi
        maidroid_apt_write_network_conf
        if ! maidroid_apt_install xvfb xdotool procps curl ca-certificates tar jq fonts-wqy-zenhei; then
            printf "装不上 SnowLuma 需要的系统软件包（%s），请检查网络后重试\n" "${'$'}{maidroid_apt_failed_step:-unknown}"
            exit 1
        fi

        # LinuxQQ 和 NapCat 共用 /opt/QQ：已经是这一版就不动；不是（没装、或者 NapCat 装的是别的版本）就换成这一版，
        # SnowLuma 的原生组件绑着 QQ 的版本。package.json 里的版本号是“版本号-构建号”，按 QQ_PACKAGE_JSON_VERSION 比。
        maidroid_progress 0.20 "Check LinuxQQ"
        qq_wanted=${shellQuote(QQ_PACKAGE_JSON_VERSION)}
        qq_current="${'$'}(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' /opt/QQ/resources/app/package.json 2>/dev/null | head -n 1)"
        if [ "${'$'}qq_current" = "${'$'}qq_wanted" ] && command -v qq >/dev/null 2>&1; then
            printf "[SnowLuma] LinuxQQ %s already installed\n" "${'$'}qq_current"
        else
            printf "[SnowLuma] LinuxQQ is %s, installing %s\n" "${'$'}{qq_current:-missing}" "${'$'}qq_wanted"
            maidroid_progress 0.25 "Download LinuxQQ"
            if ! maidroid_snowluma_fetch $DOWNLOAD_DIR/$QQ_PACKAGE $QQ_SHA256 \
                ${shellQuote(QQ_OFFICIAL_URL)} ${'$'}(maidroid_download_urls ${shellQuote(QQ_GITHUB_URL)}); then
                printf "下载不到 LinuxQQ 安装包（官方地址和所有 GitHub 镜像都失败或校验不通过），请检查网络后重试\n"
                exit 1
            fi
            maidroid_progress 0.40 "Install LinuxQQ"
            # NapCat 装过别的版本时要允许降级；本地 deb 的依赖照常从软件源装
            if ! maidroid_apt_install --allow-downgrades $DOWNLOAD_DIR/$QQ_PACKAGE; then
                rm -f $DOWNLOAD_DIR/$QQ_PACKAGE
                printf "LinuxQQ 安装失败（%s），请检查网络后重试\n" "${'$'}{maidroid_apt_failed_step:-unknown}"
                exit 1
            fi
            rm -f $DOWNLOAD_DIR/$QQ_PACKAGE
        fi
        """.trimIndent() + "\n" +
        // 官方 deb 未完整声明 libnss3/libgbm1 等运行库；放在 if/else 外，即使容器里先前已装过 /opt/QQ 也要确保补齐。
        LinuxQqRuntimeDeps.ensureScript(logPrefix = "[SnowLuma]", failOnError = true) + "\n" +
        """
        # 挡住 QQ 的热更新：热更新会悄悄换掉 QQ 的组件，SnowLuma 的注入就对不上了
        if ! grep -q 'qqpatch[.]gtimg[.]cn' /etc/hosts 2>/dev/null; then
            printf "0.0.0.0 qqpatch.gtimg.cn\n" >> /etc/hosts
        fi

        maidroid_progress 0.60 "Download SnowLuma"
        """.trimIndent() + "\n" +
        downloadCustomArchiveScript(source) + "\n" +
        """
        if [ "${'$'}snowluma_archive_ready" != 1 ]; then
            printf "从自定义地址下载不到有效的 SnowLuma linux-arm64 发行包，请检查仓库 Release 或直链后重试\n"
            exit 1
        fi
        bundle_sha="${'$'}(sha256sum $DOWNLOAD_DIR/snowluma.tar.gz | awk '{print ${'$'}1}')"
        if [ "${'$'}bundle_sha" = ${shellQuote(OFFICIAL_BUNDLE_SHA256)} ]; then
            printf "[SnowLuma] bundle sha256 matches official v$VERSION release\n"
        else
            printf "[SnowLuma] WARNING: bundle sha256 (%s) differs from official v$VERSION (%s); continuing per user risk confirmation\n" \
                "${'$'}bundle_sha" ${shellQuote(OFFICIAL_BUNDLE_SHA256)}
        fi

        # 先解到临时目录，确认入口和自带的 node 都在再换上；数据在另一个目录，换程序目录不丢登录和配置
        maidroid_progress 0.75 "Unpack SnowLuma"
        rm -rf $APP_DIR.tmp
        mkdir -p $APP_DIR.tmp
        tar -xzf $DOWNLOAD_DIR/snowluma.tar.gz -C $APP_DIR.tmp
        if [ ! -f $APP_DIR.tmp/index.mjs ]; then
            nested_entry="${'$'}(find $APP_DIR.tmp -maxdepth 2 -type f -name 'index.mjs' | head -n 1)"
            if [ -n "${'$'}nested_entry" ]; then
                nested_dir="${'$'}(dirname "${'$'}nested_entry")"
                mv "${'$'}nested_dir"/* "${'$'}nested_dir"/.[!.]* $APP_DIR.tmp/ 2>/dev/null || true
            fi
        fi
        [ -f $APP_DIR.tmp/node ] && chmod +x $APP_DIR.tmp/node 2>/dev/null || true
        if [ ! -f $APP_DIR.tmp/index.mjs ] || [ ! -x $APP_DIR.tmp/node ]; then
            printf "SnowLuma 发行包内容不完整，请重试\n"
            exit 1
        fi
        rm -rf $APP_DIR
        mv $APP_DIR.tmp $APP_DIR
        rm -f $DOWNLOAD_DIR/snowluma.tar.gz

        # 全局 OneBot 配置：只开一个连本机的 WebSocket 服务端给 MaiBot，带上和 NapCat 一样的 token；
        # 不开 SnowLuma 默认的 HTTP 3000、WS 3001。#sl 状态命令关掉，免得群里有人发 #sl 时机器人自动回一条状态。
        # QQ 登录后 SnowLuma 以这份为底另存 onebot_<QQ号>.json。
        maidroid_progress 0.85 "Write SnowLuma OneBot config"
        cat > $DATA_DIR/config/onebot.json.tmp <<EOF
        {
          "networks": {
            "httpServers": [],
            "httpClients": [],
            "wsServers": [
              {
                "name": "maidroid",
                "enabled": true,
                "host": "${QqOneBot.HOST}",
                "port": ${QqOneBot.PORT},
                "path": "/",
                "role": "Universal",
                "accessToken": "${'$'}onebot_token",
                "messageFormat": "array",
                "reportSelfMessage": false
              }
            ],
            "wsClients": []
          },
          "statusCommand": { "enabled": false }
        }
        EOF
        mv -f $DATA_DIR/config/onebot.json.tmp $DATA_DIR/config/onebot.json

        maidroid_progress 0.88 "Record SnowLuma install state"
        # 优先从实际解压出来的 package.json 提取真实版本号写入标记，读不到再回退默认目标版本
        installed_ver="${'$'}(jq -r '.version // empty' $APP_DIR/package.json 2>/dev/null | head -n 1)"
        [ -n "${'$'}installed_ver" ] || installed_ver=${shellQuote(VERSION)}
        printf "%s\n" "${'$'}installed_ver" > $READY_MARKER.tmp
        mv -f $READY_MARKER.tmp $READY_MARKER
        """.trimIndent()

    /**
     * 按用户填写的地址类型生成 SnowLuma 发行包下载逻辑：
     * - 直链直接尝试经镜像或直连下载；
     * - 未指定 tag 的仓库优先尝试 v2.4.2，失败时再查最新 Release（含 pre-release）的 linux-arm64 完整包；
     * - 指定 tag 的仓库先按该 tag 拼包名下载，失败再查对应 tag 的 Release 资产列表。
     */
    private fun downloadCustomArchiveScript(source: QqCustomSource): String = when (source) {
        is QqCustomSource.DirectArchive ->
            """
            snowluma_archive_ready=0
            if maidroid_snowluma_fetch_archive $DOWNLOAD_DIR/snowluma.tar.gz ${shellQuote(source.rawUrl)}; then
                snowluma_archive_ready=1
            fi
            """.trimIndent()
        is QqCustomSource.Repository -> {
            val singleReleaseFilter =
                "[.assets[]? | select((.name | test(\"linux-arm64.*\\\\.tar\\\\.gz\$\"; \"i\")) and ((.name | test(\"-lite\"; \"i\")) | not)) | .browser_download_url][0] // empty"
            val listReleaseFilter =
                "[.[]?.assets[]? | select((.name | test(\"linux-arm64.*\\\\.tar\\\\.gz\$\"; \"i\")) and ((.name | test(\"-lite\"; \"i\")) | not)) | .browser_download_url][0] // empty"
            if (source.pinnedTag != null) {
                val pinnedVer = source.pinnedTag.substringAfterLast('/').removePrefix("v")
                val pinnedUrl = "${source.repoBaseUrl}/releases/download/${source.pinnedTag}/SnowLuma-v$pinnedVer-linux-arm64.tar.gz"
                val apiFallback = source.gitHubRepoSlug?.let { slug ->
                    val tagApiUrl = "https://api.github.com/repos/$slug/releases/tags/${source.pinnedTag}"
                    """
                    elif maidroid_snowluma_fetch_github_release ${shellQuote(tagApiUrl)} ${shellQuote(singleReleaseFilter)}; then
                        snowluma_archive_ready=1
                    """.trimIndent()
                }.orEmpty()
                """
                snowluma_archive_ready=0
                if maidroid_snowluma_fetch_archive $DOWNLOAD_DIR/snowluma.tar.gz ${shellQuote(pinnedUrl)}; then
                    snowluma_archive_ready=1
                $apiFallback
                fi
                """.trimIndent()
            } else {
                val preferredUrl = "${source.repoBaseUrl}/releases/download/v$VERSION/SnowLuma-v$VERSION-linux-arm64.tar.gz"
                val fallbackBlock = if (source.gitHubRepoSlug != null) {
                    val latestApiUrl = "https://api.github.com/repos/${source.gitHubRepoSlug}/releases/latest"
                    val listApiUrl = "https://api.github.com/repos/${source.gitHubRepoSlug}/releases?per_page=5"
                    """
                    elif maidroid_snowluma_fetch_github_release ${shellQuote(latestApiUrl)} ${shellQuote(singleReleaseFilter)}; then
                        snowluma_archive_ready=1
                    elif maidroid_snowluma_fetch_github_release ${shellQuote(listApiUrl)} ${shellQuote(listReleaseFilter)}; then
                        snowluma_archive_ready=1
                    """.trimIndent()
                } else {
                    val latestDownloadUrl = "${source.repoBaseUrl}/releases/latest/download/SnowLuma-v$VERSION-linux-arm64.tar.gz"
                    """
                    elif maidroid_snowluma_fetch_archive $DOWNLOAD_DIR/snowluma.tar.gz ${shellQuote(latestDownloadUrl)}; then
                        snowluma_archive_ready=1
                    """.trimIndent()
                }
                """
                snowluma_archive_ready=0
                if maidroid_snowluma_fetch_archive $DOWNLOAD_DIR/snowluma.tar.gz ${shellQuote(preferredUrl)}; then
                    snowluma_archive_ready=1
                $fallbackBlock
                fi
                """.trimIndent()
            }
        }
    }

    private fun prepareRuntimeBody(): String =
        """
        set -e
        mkdir -p $DATA_DIR /tmp /tmp/.X11-unix /tmp/runtime-root $SCREEN_DIR $HOOK_RUNTIME_DIR
        chmod 700 /tmp/runtime-root $HOOK_RUNTIME_DIR 2>/dev/null || true
        cat > $LAUNCHER <<'EOF'
        #!/bin/bash
        set -u
        cd /root
        mkdir -p /tmp /tmp/.X11-unix /tmp/runtime-root $SCREEN_DIR $HOOK_RUNTIME_DIR
        chmod 700 /tmp/runtime-root $HOOK_RUNTIME_DIR 2>/dev/null || true
        # 上次正常保存退出留下的停机标记清掉，保育循环才能正常盯着新节点
        rm -f $NODE_STOP_MARKER
        export XDG_RUNTIME_DIR=/tmp/runtime-root
        export HOME=/root
        export QQNT_PROFILE_DIR=/root/.config/QQ
        export SNOWLUMA_HOOK_RUNTIME_DIR=$HOOK_RUNTIME_DIR
        trap "" SIGPIPE

        if ! command -v qq >/dev/null 2>&1; then
            echo "[SnowLuma] qq executable not found in PATH"
            exit 127
        fi
        if [ ! -x $APP_DIR/node ] || [ ! -f $APP_DIR/index.mjs ]; then
            echo "[SnowLuma] SnowLuma is not installed in $APP_DIR"
            exit 127
        fi

        # 显示号不写死（理由同 NapCat）：让 Xvfb 用 -displayfd 自己挑第一个空着的号。
        # -fbdir 让 Xvfb 把屏幕内容一直写在 $SCREEN_FILE（XWD 格式），宿主侧读它显示 QQ 窗口。
        rm -f /tmp/.X*-lock /tmp/.X11-unix/X* $DISPLAY_FILE $SCREEN_FILE
        Xvfb -displayfd 3 -screen 0 ${SCREEN_WIDTH}x${SCREEN_HEIGHT}x24 -fbdir $SCREEN_DIR +extension GLX +render 3>$DISPLAY_FILE 2>&1 &
        xvfb_pid=${'$'}!
        for _ in ${'$'}(seq 1 50); do
            [ -s $DISPLAY_FILE ] && break
            kill -0 "${'$'}xvfb_pid" 2>/dev/null || break
            sleep 0.2
        done
        display="${'$'}(tr -cd '0-9' < $DISPLAY_FILE 2>/dev/null)"
        if [ -z "${'$'}display" ]; then
            echo "[SnowLuma] Xvfb failed to start"
            exit 1
        fi
        echo "[SnowLuma] Xvfb started on :${'$'}display, pid=${'$'}xvfb_pid"
        export DISPLAY=":${'$'}display"

        # Android 不让应用读 /proc/locks，SnowLuma 却要靠它认出 QQ 登录了哪个号（看 QQ 锁着哪个账号的 nt_msg.db）。
        # 现在由宿主协程 QqProcLocksSync 直接读取同 UID 下 QQ 的 /proc/<pid>/fdinfo 原子更新 $PROC_LOCKS，
        # 不再在容器内每 2 秒起一轮 bash/pgrep/grep/sed 子进程（省去幻影进程名额和 SELinux 审计刷屏）。

        # SnowLuma 放在后台跑，工作目录是数据目录（config/、data/、logs/ 都放在工作目录下）：
        # 自动注入默认关着，要打开；不查更新（程序目录由 MaiDroid 管）；WebUI 只听本机，端口由监管器挑好传进来；
        # 首次启动用 MaiDroid 生成的密码建 WebUI 账号；用户在安装前同意过协议，才替用户确认协议。
        export SNOWLUMA_HOOK_AUTOLOAD=1
        export SNOWLUMA_UPDATE_CHECK=0
        export NO_COLOR=1
        # 给 SnowLuma node 的 V8 堆设上限：封在 384MB 防失控。
        # 只对容器里的 node 生效（QQ 启动行会 -u 掉这条变量）。
        export NODE_OPTIONS="--max-old-space-size=384"
        export SNOWLUMA_WEBUI_HOST=127.0.0.1
        export $WEBUI_PORT_ENV="${'$'}{$WEBUI_PORT_ENV:-$WEBUI_DEFAULT_PORT}"
        printf "%s" "${'$'}$WEBUI_PORT_ENV" > $WEBUI_PORT_FILE
        export SNOWLUMA_LOG_MAX_TOTAL_MB=64
        export SNOWLUMA_LOG_RETAIN_DAYS=3
        if [ -s $WEBUI_PASSWORD_FILE ]; then
            export SNOWLUMA_WEBUI_BOOTSTRAP_PASSWORD="${'$'}(cat $WEBUI_PASSWORD_FILE)"
        fi
        if [ -f $CONSENT_MARKER ]; then
            export SNOWLUMA_ACCEPT_EULA=1
            export SNOWLUMA_ACCEPT_PRIVACY=1
        fi
        echo "[SnowLuma] Starting SnowLuma, WebUI port ${'$'}$WEBUI_PORT_ENV"
        (cd $DATA_DIR && exec $APP_DIR/node $APP_DIR/index.mjs) 2>&1 &
        snowluma_pid=${'$'}!
        snowluma_spawn_ts=${'$'}(date +%s)
        snowluma_restarts=0

        # QQ 在后台拉起，主脚本留在同一条 bash 进程内用 wait -n 阻塞监管它和 SnowLuma 节点；
        # QQ 本身不需要 SnowLuma 的这些变量，但要和它用同一个 hook 目录；实测 Electron 40 在 proot 下禁用 zygote 会阻止渲染子进程启动，故保留默认 zygote。
        echo "[SnowLuma] Launching LinuxQQ"
        env -u SNOWLUMA_WEBUI_BOOTSTRAP_PASSWORD -u NODE_OPTIONS qq --no-sandbox --disable-gpu --disable-dev-shm-usage 2>&1 &
        qq_pid=${'$'}!

        # 节点保育（不额外派生监视子 shell，零轮询唤醒）：
        # 正常/意外结束 QQ 或宿主写入停机标记后单等 QQ 收尾；只有 node 意外死掉才在连续失败上限内原地重启节点，保住 QQ 登录。
        exit_code=0
        while :; do
            exited_pid=''
            wait_rc=0
            wait -n -p exited_pid "${'$'}qq_pid" "${'$'}snowluma_pid" 2>/dev/null || wait_rc=${'$'}?
            if [ "${'$'}exited_pid" = "${'$'}qq_pid" ]; then
                exit_code=${'$'}wait_rc
                break
            fi
            if ! kill -0 "${'$'}qq_pid" 2>/dev/null; then
                wait "${'$'}qq_pid" 2>/dev/null
                exit_code=${'$'}?
                break
            fi
            if [ -s $NODE_STOP_MARKER ]; then
                wait "${'$'}qq_pid" 2>/dev/null
                exit_code=${'$'}?
                break
            fi
            if [ "${'$'}exited_pid" = "${'$'}snowluma_pid" ] || ! kill -0 "${'$'}snowluma_pid" 2>/dev/null; then
                [ "${'$'}exited_pid" = "${'$'}snowluma_pid" ] || wait "${'$'}snowluma_pid" 2>/dev/null || true
                echo "[SnowLuma] SnowLuma node exited unexpectedly"
                if [ "${'$'}snowluma_spawn_ts" -gt 0 ] && [ "$(( ${'$'}(date +%s) - snowluma_spawn_ts ))" -ge $NODE_STABLE_SECONDS ]; then
                    snowluma_restarts=0
                fi
                if [ "${'$'}snowluma_restarts" -ge $NODE_RESTART_LIMIT ]; then
                    echo "[SnowLuma] node died $NODE_RESTART_LIMIT times in a row, giving up; asking QQ to exit for a full restart"
                    kill -TERM "${'$'}qq_pid" 2>/dev/null || true
                    wait "${'$'}qq_pid" 2>/dev/null
                    exit_code=${'$'}?
                    break
                fi
                snowluma_restarts=$(( ${'$'}snowluma_restarts + 1 ))
                sleep 5
                if ! kill -0 "${'$'}qq_pid" 2>/dev/null; then
                    wait "${'$'}qq_pid" 2>/dev/null
                    exit_code=${'$'}?
                    break
                fi
                if [ -s $NODE_STOP_MARKER ]; then
                    wait "${'$'}qq_pid" 2>/dev/null
                    exit_code=${'$'}?
                    break
                fi
                echo "[SnowLuma] restarting SnowLuma node (attempt ${'$'}snowluma_restarts/$NODE_RESTART_LIMIT)"
                (cd $DATA_DIR && exec $APP_DIR/node $APP_DIR/index.mjs) 2>&1 &
                snowluma_pid=${'$'}!
                snowluma_spawn_ts=${'$'}(date +%s)
            fi
        done
        echo "[SnowLuma] qq exited with code ${'$'}exit_code"

        : > $PROC_LOCKS

        pkill -TERM -f "snowluma.app.index[.]mjs" 2>/dev/null || true
        for _ in ${'$'}(seq 1 15); do
            pgrep -f "snowluma.app.index[.]mjs" >/dev/null 2>&1 || break
            sleep 1
        done

        rm -f $NODE_STOP_MARKER

        kill "${'$'}xvfb_pid" 2>/dev/null || true
        exit ${'$'}exit_code
        EOF
        chmod +x $LAUNCHER
        """.trimIndent()
}
