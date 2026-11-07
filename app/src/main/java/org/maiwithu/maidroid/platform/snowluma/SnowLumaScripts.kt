package org.maiwithu.maidroid.platform.snowluma

import org.maiwithu.maidroid.platform.qq.QqOneBot
import org.maiwithu.maidroid.runtime.AptMirrors
import org.maiwithu.maidroid.runtime.ContainerScript
import org.maiwithu.maidroid.runtime.ExitTarget
import org.maiwithu.maidroid.runtime.GitHubMirrors
import org.maiwithu.maidroid.runtime.InContainerPaths
import org.maiwithu.maidroid.runtime.killMatchingProcessesScript
import org.maiwithu.maidroid.runtime.requestExitScript
import org.maiwithu.maidroid.runtime.shellQuote

/**
 * SnowLuma 客户端（https://github.com/SnowLuma/SnowLuma）在容器里的安装、启动和清理脚本。
 *
 * SnowLuma 不自己登录 QQ：容器里先在 Xvfb 上跑一个原版 LinuxQQ，SnowLuma 每 1.5 秒找一次 QQ 主进程，
 * 用 ptrace 把它的原生组件注入进去，再给 MaiBot 开 OneBot WebSocket（地址、端口、token 和 NapCat 一样，见 QqOneBot）。
 * 登录二维码只画在 QQ 自己的窗口里，所以 Xvfb 用 -fbdir 把屏幕写成文件，宿主侧读出来给用户看、替用户点。
 *
 * 版本固定：发行包和 LinuxQQ 都按上游给的 sha256 校验后才装。SnowLuma 的原生组件绑着 QQ 的版本，
 * 上游自己的 Docker 镜像和安装脚本都钉在下面这版 QQ 上；升级时两组常量一起改，并重新在真机上验证注入。
 * SnowLuma 的原生组件是专有软件，按作者的书面授权由 MaiDroid 自动下载部署，用户在安装前看过并同意它的协议。
 */
internal object SnowLumaScripts {
    /** 装的 SnowLuma 版本（GitHub Release 的 tag 去掉 v）。 */
    const val VERSION = "2.4.2"

    // 完整版发行包（自带 Node 24），sha256 取自 GitHub Release 资产的 digest
    private const val BUNDLE_URL =
        "https://github.com/SnowLuma/SnowLuma/releases/download/v$VERSION/SnowLuma-v$VERSION-linux-arm64.tar.gz"
    private const val BUNDLE_SHA256 = "c4bcff22828fb465d6fbd8caa375e82432fd3af684b297c43f2c428a2de2200c"

    /** SnowLuma 上游钉的 LinuxQQ 版本（安装包名里的写法：版本号_发布日期），和 NapCat 共用 /opt/QQ。 */
    const val QQ_VERSION = "3.2.32_260812"

    /**
     * 同一版 QQ 装好后 /opt/QQ/resources/app/package.json 里写的版本号：版本号-构建号，和包名里的发布日期对不上，
     * 只能单独记（2026-11 真机上读出来的）。安装前拿它判断容器里的 QQ 是不是这一版；升级 QQ 时和 QQ_VERSION 一起改。
     */
    const val QQ_PACKAGE_JSON_VERSION = "3.2.32-52194"
    private const val QQ_PACKAGE = "QQ_${QQ_VERSION}_arm64_01.deb"
    private const val QQ_SHA256 = "8796ccfd66acc025ef18db37185532d40bc8c58921e17da6d28c295acbcf8f92"

    // QQ 官方下载地址在前，上游安装脚本用的 GitHub 备份在后（经 GitHub 镜像），哪个下到都要对得上 sha256
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

    // /proc/locks 的替身，启动脚本往里填 QQ 的文件锁，原因见启动脚本里的注释
    private const val PROC_LOCKS = InContainerPaths.PROC_LOCKS_STAND_IN
    private const val DOWNLOAD_DIR = "/root/.cache/maidroid-snowluma"

    /** Xvfb 屏幕大小：QQ 登录窗口和登录后的主窗口都放得下，屏幕文件约 4 MB。 */
    const val SCREEN_WIDTH = 1280
    const val SCREEN_HEIGHT = 800

    /** WebUI 默认端口；被别的程序占着时监管器换一个空端口，经这个环境变量交给 SnowLuma。 */
    const val WEBUI_DEFAULT_PORT = 5099
    const val WEBUI_PORT_ENV = "SNOWLUMA_WEBUI_PORT"

    // 停止时等 SnowLuma、QQ 自己退出的上限，超时就照常强制清理。
    private const val SNOWLUMA_EXIT_TIMEOUT_SECONDS = 20
    private const val QQ_EXIT_TIMEOUT_SECONDS = 20

    /**
     * SnowLuma 的两个 node 进程（守护进程和它拉起的工作进程，命令行一样）。收到 SIGTERM 后守护进程转给工作进程，
     * 工作进程关掉 OneBot、WebUI，写完日志再退出，两个都发也没关系（重复的退出请求会被忽略）。
     * 只认入口文件：node 起来后把命令行改成了 `node /root/snowluma/app/index.mjs`（2026-11 真机上看到的），
     * 不是启动时写的 `/root/snowluma/app/node ...`。正则里的点写成 [.]，免得匹配到脚本自己的命令行里那份正则原文。
     */
    val SNOWLUMA_EXIT_TARGET = ExitTarget(pattern = "snowluma.app.index[.]mjs", exclude = "awk", processName = "SnowLuma")

    /** LinuxQQ 主进程，排除命令行带 --type= 的 zygote、utility 子进程；和 NapCat 起 QQ 用的参数一样。 */
    val QQ_EXIT_TARGET = ExitTarget(pattern = "qq --no-sandbox --disable-gpu", exclude = "--type=", processName = "QQ")

    /**
     * 首次安装（或重装）：
     * 写 OneBot token、WebUI 密码和协议同意记录 -> 装 Xvfb、xdotool、中文字体 -> 没有这版 LinuxQQ 就下载校验后装上，
     * 并挡住 QQ 的热更新 -> 下载校验 SnowLuma 发行包、解到程序目录 -> 写全局 OneBot 配置 -> 打完成标记 -> 生成启动脚本。
     * 每一步都假设上次装到一半被打断：dpkg 先收拾半装状态，下载和解包都先写临时文件、校验通过才换上。
     */
    fun install(token: String, webUiPassword: String): ContainerScript = ContainerScript(
        body = listOf(
            "set -e",
            AptMirrors.shellFunctions(),
            GitHubMirrors.shellFunctions(),
            // 和 NapCat 共用一份 OneBot token：装过 NapCat 的沿用它的，MaiBot 侧的适配器配置不用改
            QqOneBot.ensureTokenScript(token, variable = "onebot_token"),
            installBody(webUiPassword),
            prepareRuntimeBody(),
            "maidroid_progress 0.9 ${shellQuote("SnowLuma install completed")}"
        ).joinToString("\n"),
        workingDirectory = "/root"
    )

    /** 每次启动前执行：补齐目录，并重新生成启动脚本。 */
    fun prepareRuntime(): ContainerScript = ContainerScript(prepareRuntimeBody(), workingDirectory = "/root")

    /** 前台运行启动脚本；有 script 命令时套一层伪终端，让 QQ 和 SnowLuma 的输出按行刷新。 */
    fun launch(): ContainerScript = ContainerScript(
        body = "cd /root && " +
            "if command -v script >/dev/null 2>&1; then " +
            "script -q -c ${shellQuote("bash $LAUNCHER")} /dev/null; " +
            "else bash $LAUNCHER; fi",
        workingDirectory = "/root"
    )

    /** 停止时先请 SnowLuma 保存好数据退出（QQ 不会因此下线）。结果用 requestedExitOf 读。 */
    fun requestSnowLumaExit(): ContainerScript = ContainerScript(
        body = listOf(
            "set +e",
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
            "rm -f /tmp/.X*-lock /tmp/.X11-unix/X* $DISPLAY_FILE $SCREEN_FILE $PROC_LOCKS.new",
            // 拼锁表的循环随启动脚本一起被清掉；替身文件留着（proot 绑定要用），只清空内容
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

    private fun installBody(webUiPassword: String): String =
        """
        maidroid_progress() {
            printf "%s\n" "${'$'}1" > $PROGRESS_FILE
            printf "%s\n" "${'$'}2" > $PROGRESS_DESCRIPTION_FILE
            printf "[SnowLuma] %s\n" "${'$'}2"
        }

        # 依次试每个地址下载到 $1，下到的 sha256 必须是 $2：镜像限流、拉黑时会回 200 的网页，下到了也不能用
        maidroid_snowluma_fetch() {
            fetch_out="${'$'}1"
            fetch_sha="${'$'}2"
            shift 2
            for fetch_url in "${'$'}@"; do
                rm -f "${'$'}fetch_out.part"
                printf "[SnowLuma] downloading %s\n" "${'$'}fetch_url"
                if curl -fsSL --connect-timeout 20 --speed-limit 1024 --speed-time 30 -o "${'$'}fetch_out.part" "${'$'}fetch_url"; then
                    fetch_got="${'$'}(sha256sum "${'$'}fetch_out.part" | awk '{print ${'$'}1}')"
                    if [ "${'$'}fetch_got" = "${'$'}fetch_sha" ]; then
                        mv "${'$'}fetch_out.part" "${'$'}fetch_out"
                        return 0
                    fi
                    printf "[SnowLuma] checksum mismatch (got %s), trying next source\n" "${'$'}fetch_got"
                fi
            done
            rm -f "${'$'}fetch_out.part"
            return 1
        }

        # 一个 GitHub 地址经每条镜像线路（含直连）的实际下载地址
        maidroid_github_urls() {
            for mirror_spec in ${'$'}(maidroid_github_mirror_specs); do
                maidroid_github_candidate_url "${'$'}{mirror_spec#*|}" "${'$'}1"
            done
        }

        maidroid_progress 0.05 "Prepare SnowLuma install"
        mkdir -p /root/.maidroid $DATA_DIR/config $DOWNLOAD_DIR
        if [ ! -s $WEBUI_PASSWORD_FILE ]; then
            printf "%s" ${shellQuote(webUiPassword)} > $WEBUI_PASSWORD_FILE
            chmod 600 $WEBUI_PASSWORD_FILE 2>/dev/null || true
        fi
        # 用户是在消息平台页看过 SnowLuma 的协议、点了“同意并安装”才走到这里的，记下来，启动时据此替用户确认协议
        printf "SnowLuma %s agreed %s\n" $VERSION "${'$'}(date -u +%Y-%m-%dT%H:%M:%SZ)" > $CONSENT_MARKER

        # 上次装到一半被打断留下的 dpkg 半装状态先收拾掉；apt 带超时，下载失败会自动换源。
        # xdotool 往 QQ 窗口里点击，中文字体让 QQ 窗口里的字（“扫码登录”“二维码已失效”）不显示成方块。
        maidroid_progress 0.10 "Install system packages"
        export DEBIAN_FRONTEND=noninteractive
        if ! dpkg --configure -a; then
            printf "[SnowLuma] dpkg --configure -a failed, apt-get -f install will try to repair\n"
        fi
        maidroid_apt_write_network_conf
        if ! maidroid_apt_install xvfb xdotool procps curl ca-certificates tar fonts-wqy-zenhei; then
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
                ${shellQuote(QQ_OFFICIAL_URL)} ${'$'}(maidroid_github_urls ${shellQuote(QQ_GITHUB_URL)}); then
                printf "下载不到 LinuxQQ 安装包（官方地址和所有 GitHub 镜像都失败或校验不通过），请检查网络后重试\n"
                exit 1
            fi
            maidroid_progress 0.40 "Install LinuxQQ"
            # NapCat 装过别的版本时要允许降级；本地 deb 的依赖照常从软件源装
            if ! maidroid_apt_install --allow-downgrades $DOWNLOAD_DIR/$QQ_PACKAGE; then
                printf "LinuxQQ 安装失败（%s），请检查网络后重试\n" "${'$'}{maidroid_apt_failed_step:-unknown}"
                exit 1
            fi
            rm -f $DOWNLOAD_DIR/$QQ_PACKAGE
        fi
        # 挡住 QQ 的热更新：热更新会悄悄换掉 QQ 的组件，SnowLuma 的注入就对不上了（上游安装脚本也这么做）
        if ! grep -q 'qqpatch[.]gtimg[.]cn' /etc/hosts 2>/dev/null; then
            printf "0.0.0.0 qqpatch.gtimg.cn\n" >> /etc/hosts
        fi

        maidroid_progress 0.60 "Download SnowLuma"
        if ! maidroid_snowluma_fetch $DOWNLOAD_DIR/snowluma.tar.gz $BUNDLE_SHA256 ${'$'}(maidroid_github_urls ${shellQuote(BUNDLE_URL)}); then
            printf "下载不到 SnowLuma 发行包（直连和所有 GitHub 镜像都失败或校验不通过），请检查网络后重试\n"
            exit 1
        fi

        # 先解到临时目录，确认入口和自带的 node 都在再换上；数据在另一个目录，换程序目录不丢登录和配置
        maidroid_progress 0.75 "Unpack SnowLuma"
        rm -rf $APP_DIR.tmp
        mkdir -p $APP_DIR.tmp
        tar -xzf $DOWNLOAD_DIR/snowluma.tar.gz -C $APP_DIR.tmp
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
        cat > $DATA_DIR/config/onebot.json <<EOF
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

        maidroid_progress 0.88 "Record SnowLuma install state"
        printf "%s\n" $VERSION > $READY_MARKER
        """.trimIndent()

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

        # Android 不让应用读 /proc/locks，SnowLuma 却要靠它认出 QQ 登录了哪个号（看 QQ 锁着哪个账号的 nt_msg.db），
        # 读不到就一直停在“等待登录”。proot 把 /proc/locks 绑到了 $PROC_LOCKS（见 ProotCommand）：
        # 这里每 2 秒用 QQ 进程 fdinfo 里的 lock: 行拼一张同样格式的表，先写临时文件再改名，SnowLuma 读不到写了一半的表。
        (
            while :; do
                for qq_pid in ${'$'}(pgrep -x qq); do
                    grep -h '^lock:' /proc/${'$'}qq_pid/fdinfo/* 2>/dev/null
                done | sed 's/^lock:[[:space:]]*//' > $PROC_LOCKS.new
                mv -f $PROC_LOCKS.new $PROC_LOCKS
                sleep 2
            done
        ) &
        locks_pid=${'$'}!

        # SnowLuma 放在后台跑，工作目录是数据目录（config/、data/、logs/ 都放在工作目录下）：
        # 自动注入默认关着，要打开；不查更新（程序目录由 MaiDroid 管）；WebUI 只听本机，端口由监管器挑好传进来；
        # 首次启动用 MaiDroid 生成的密码建 WebUI 账号；用户在安装前同意过协议，才替用户确认协议。
        export SNOWLUMA_HOOK_AUTOLOAD=1
        export SNOWLUMA_UPDATE_CHECK=0
        export NO_COLOR=1
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

        # QQ 在前台跑，SnowLuma 找到它就注入；QQ 本身不需要 SnowLuma 的这些变量，但要和它用同一个 hook 目录
        echo "[SnowLuma] Launching LinuxQQ"
        env -u SNOWLUMA_WEBUI_BOOTSTRAP_PASSWORD qq --no-sandbox --disable-gpu --disable-dev-shm-usage 2>&1
        exit_code=${'$'}?
        echo "[SnowLuma] qq exited with code ${'$'}exit_code"

        # QQ 退出了，锁表也不用再拼：停掉循环、清空替身，免得留着旧进程的锁
        kill "${'$'}locks_pid" 2>/dev/null || true
        : > $PROC_LOCKS

        # QQ 退出了，SnowLuma 留着也收发不了消息：请它保存好数据退出，最多等 15 秒
        if kill -0 "${'$'}snowluma_pid" 2>/dev/null; then
            kill -TERM "${'$'}snowluma_pid" 2>/dev/null || true
            for _ in ${'$'}(seq 1 15); do
                kill -0 "${'$'}snowluma_pid" 2>/dev/null || break
                sleep 1
            done
        fi

        # 最后结束后台的 Xvfb：它不退，proot 就一直不退，应用会以为 SnowLuma 还在跑（QQ 自己崩了时尤其如此）
        kill "${'$'}xvfb_pid" 2>/dev/null || true
        exit ${'$'}exit_code
        EOF
        chmod +x $LAUNCHER
        """.trimIndent()
}
