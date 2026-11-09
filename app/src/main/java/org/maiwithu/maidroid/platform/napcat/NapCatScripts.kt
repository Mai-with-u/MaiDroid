package org.maiwithu.maidroid.platform.napcat

import org.maiwithu.maidroid.platform.qq.QqOneBot
import org.maiwithu.maidroid.runtime.AptMirrors
import org.maiwithu.maidroid.runtime.ContainerScript
import org.maiwithu.maidroid.runtime.ExitTarget
import org.maiwithu.maidroid.runtime.GitHubMirrors
import org.maiwithu.maidroid.runtime.killMatchingProcessesScript
import org.maiwithu.maidroid.runtime.requestExitScript
import org.maiwithu.maidroid.runtime.shellQuote

/**
 * NapCat（LinuxQQ + NapCat 注入）在容器里的安装、启动和清理脚本。
 *
 * 安装进度通过 $TMPDIR/progress 和 progress_des 两个文件汇报（宿主侧对应 termux/tmp），
 * NapCatPlatformManager 每 500 毫秒读一次显示到消息平台页的进度环上。
 */
internal object NapCatScripts {

    // 停止时等 QQ 自己退出的上限；实测 1~2 秒，留足余量，超时就照常强制清理。
    private const val QQ_EXIT_TIMEOUT_SECONDS = 20

    // NapCat 官方的 Linux 安装器。
    private const val INSTALLER_URL =
        "https://raw.githubusercontent.com/NapNeko/napcat-linux-installer/refs/heads/main/install.sh"

    /**
     * 首次安装 NapCat 本体：写入 OneBot token -> 跑官方 napcat 安装器 -> 生成 OneBot11 WebSocket 服务端配置 ->
     * 打 napcat_ready 标记 -> 生成 launcher.sh。
     * token 只在第一次安装 QQ 协议端时生成，之后重装、换成 SnowLuma 都沿用容器里已保存的那份（QqOneBot），
     * 避免 MaiBot 适配器配置失配。
     * MaiBot 侧的 SnowLuma 适配器不在这里装：脚本跑完后由 SnowLumaAdapterInstaller（platform/qq）另外安装，配置等 MaiBot 生成后再迁移，
     * 这样 NapCat 已装好、只缺适配器（老用户迁移）时不用再跑一遍 NapCat 安装器。
     */
    fun install(token: String): ContainerScript = ContainerScript(
        body = listOf(
            "set -e",
            AptMirrors.shellFunctions(),
            GitHubMirrors.shellFunctions(),
            // 读出已保存的 token，没有才存下新的；后面写 onebot11.json 时用 shell 变量 napcat_token
            QqOneBot.ensureTokenScript(token, variable = "napcat_token"),
            installBody(),
            prepareRuntimeBody(),
            "maidroid_progress 0.9 ${shellQuote("NapCat install completed")}"
        ).joinToString("\n"),
        workingDirectory = "/root"
    )

    /** 每次启动 NapCat 前执行：补齐缓存和 X11 目录，并重新生成 /root/launcher.sh。 */
    fun prepareRuntime(): ContainerScript = ContainerScript(prepareRuntimeBody(), workingDirectory = "/root")

    /**
     * 前台运行 launcher.sh（Xvfb + LinuxQQ）。有 script 命令时套一层伪终端，
     * 让 QQ 的输出按行刷新，终端日志里能实时看到登录过程。
     */
    fun launch(): ContainerScript = ContainerScript(
        body = "cd /root && " +
            "if command -v script >/dev/null 2>&1; then " +
            "script -q -c ${shellQuote("bash /root/launcher.sh")} /dev/null; " +
            "else bash /root/launcher.sh; fi",
        workingDirectory = "/root",
        pinToSmallCores = true
    )

    /**
     * 停止、重启和安全退出时，请 LinuxQQ 的主进程（launcher.sh 里那条 qq --no-sandbox --disable-gpu ...）自己下线退出，
     * 最多等 QQ_EXIT_TIMEOUT_SECONDS 秒。2026-11 真机实测 QQ 收到 SIGTERM 后 1~2 秒退出、退出码 0，
     * zygote、utility 等子进程（命令行带 --type=）由主进程自己收掉，所以只发给主进程。
     * QQ 退出后 launcher.sh 跟着退出，但它放到后台的 Xvfb 还在，proot 要等 Xvfb 也退出才结束，之后要再跑一次 cleanup。
     */
    fun requestQqExit(): ContainerScript = ContainerScript(
        body = listOf(
            "set +e",
            requestExitScript(QQ_EXIT_TARGET, timeoutSeconds = QQ_EXIT_TIMEOUT_SECONDS, logPrefix = "[NapCat] ")
        ).joinToString("\n"),
        workingDirectory = "/root"
    )

    /** requestQqExit 要请其退出的进程：QQ 主进程，排除命令行带 --type= 的 zygote、utility 子进程。 */
    val QQ_EXIT_TARGET = ExitTarget(pattern = "qq --no-sandbox --disable-gpu", exclude = "--type=", processName = "QQ")

    /** 结束残留的 Xvfb、qq、launcher 进程，删掉 X11 锁、套接字和记下的显示号，新一轮启动重新挑号。 */
    fun cleanup(): ContainerScript = ContainerScript(
        body = listOf(
            "set +e",
            killMatchingProcessesScript(
                pattern = "Xvfb |qq --no-sandbox|launcher[.]sh|libnapcat_launcher[.]so",
                processName = "stale process",
                logPrefix = "[NapCat] "
            ),
            "rm -f /tmp/.X*-lock /tmp/.X11-unix/X* $DISPLAY_FILE",
            "exit 0"
        ).joinToString("\n"),
        workingDirectory = "/root"
    )

    private fun installBody(): String =
        """
        progress_file="${'$'}{TMPDIR:-/tmp}/progress"
        progress_des_file="${'$'}{TMPDIR:-/tmp}/progress_des"

        maidroid_progress() {
            printf "%s\n" "${'$'}1" > "${'$'}progress_file"
            printf "%s\n" "${'$'}2" > "${'$'}progress_des_file"
            printf "[NapCat] %s\n" "${'$'}2"
        }

        maidroid_progress 0.05 "Prepare NapCat install"

        if [ -d "${'$'}HOME/napcat" ] && [ ! -f "${'$'}HOME/.maidroid/napcat_ready" ]; then
            if find "${'$'}HOME/napcat" -mindepth 1 -type f | grep -q .; then
                # 只留最新一份备份：装一次被打断一次就多一份上百 MB 的备份，反复几次会把空间占满。
                # 备份的都是没装完的 NapCat 目录，QQ 的登录数据在 ~/.config/QQ，不在这里面。
                for old_backup in "${'$'}HOME"/napcat.maidroid-bak.*; do
                    [ -e "${'$'}old_backup" ] || continue
                    printf "[NapCat] Remove older unfinished NapCat backup: %s\n" "${'$'}old_backup"
                    rm -rf "${'$'}old_backup"
                done
                backup_dir="${'$'}HOME/napcat.maidroid-bak.${'$'}(date +%Y%m%d%H%M%S)"
                printf "[NapCat] Found unfinished NapCat directory, moving to backup: %s\n" "${'$'}backup_dir"
                mv "${'$'}HOME/napcat" "${'$'}backup_dir"
            else
                printf "[NapCat] Remove empty stale NapCat directory\n"
                rm -rf "${'$'}HOME/napcat"
            fi
        fi

        if ! command -v sudo >/dev/null 2>&1; then
            printf "[NapCat] sudo not found, creating root compatible wrapper\n"
            mkdir -p /usr/local/bin
            cat > /usr/local/bin/sudo <<'EOF'
        #!/bin/sh
        exec "$@"
        EOF
            chmod +x /usr/local/bin/sudo
        fi

        # NapCat 安装器自己用 apt-get 装 QQ 和依赖：上次装到一半被打断（闪退、断电）留下的 dpkg 半装状态先收拾掉，
        # 否则安装器一上来就报 dpkg was interrupted；再写好 apt 的超时设置，并用会自动换源的 maidroid_apt_install
        # 先把安装器要的常用依赖装上（换成功的源留在 sources.list 里，安装器随后的 apt-get 也走它）。
        # 预装失败不算失败，交给安装器自己装，真装不上时由它报错。
        maidroid_progress 0.10 "Prepare system packages"
        export DEBIAN_FRONTEND=noninteractive
        if ! dpkg --configure -a; then
            printf "[NapCat] dpkg --configure -a failed, apt-get -f install will try to repair\n"
        fi
        maidroid_apt_write_network_conf
        if ! maidroid_apt_install zip unzip jq curl xvfb screen xauth procps g++; then
            printf "[NapCat] pre-installing packages failed (%s), leaving it to the NapCat installer\n" "${'$'}{maidroid_apt_failed_step:-unknown}"
        fi

        maidroid_progress 0.15 "Download NapCat installer"
        # raw.githubusercontent.com 国内常连不上：按 GitHubMirrors 的线路依次下载，下到的必须是提到 napcat 的 shell 脚本，
        # 镜像限流、拉黑时可能返回 200 的网页或报错文字（2026-11 实测 ghfast.top 回“Forbidden by black list.”）。
        # 安装器自己下载 NapCat 和 LinuxQQ 时有它自己的镜像列表，这里只管拿到安装器本身。
        napcat_installer_ready=0
        for mirror_spec in ${'$'}(maidroid_github_mirror_specs); do
            mirror_name="${'$'}{mirror_spec%%|*}"
            mirror_prefix="${'$'}{mirror_spec#*|}"
            printf "[NapCat] downloading installer via %s\n" "${'$'}mirror_name"
            rm -f napcat.sh
            if curl -fL --connect-timeout 20 --speed-limit 1024 --speed-time 30 -o napcat.sh \
                "${'$'}(maidroid_github_candidate_url "${'$'}mirror_prefix" ${shellQuote(INSTALLER_URL)})" &&
                head -n 1 napcat.sh | grep -q '^#!' && grep -qi 'napcat' napcat.sh; then
                napcat_installer_ready=1
                break
            fi
        done
        if [ "${'$'}napcat_installer_ready" != 1 ]; then
            printf "下载不到 NapCat 安装脚本（直连和所有 GitHub 镜像都失败），请检查网络后重试\n"
            exit 1
        fi

        maidroid_progress 0.35 "Make NapCat installer executable"
        chmod +x napcat.sh

        maidroid_progress 0.45 "Run NapCat installer"
        bash napcat.sh

        maidroid_progress 0.78 "Write NapCat OneBot11 WebSocket config"
        mkdir -p "${'$'}HOME/napcat/config"
        cat > "${'$'}HOME/napcat/config/onebot11.json" <<EOF
        {
          "network": {
            "websocketServers": [
              {
                "name": "WsServer",
                "enable": true,
                "host": "${QqOneBot.HOST}",
                "port": ${QqOneBot.PORT},
                "reportSelfMessage": false,
                "enableForcePushEvent": true,
                "messagePostFormat": "array",
                "token": "${'$'}napcat_token",
                "debug": false,
                "heartInterval": 30000
              }
            ],
            "websocketClients": []
          },
          "musicSignUrl": "",
          "enableLocalFile2Url": false,
          "parseMultMsg": false
        }
        EOF

        maidroid_progress 0.86 "Record NapCat install state"
        printf "ready\n" > "${'$'}HOME/.maidroid/napcat_ready"
        """.trimIndent()

    // Xvfb 挑中的显示号记在这里，launcher.sh 写、cleanup 删
    private const val DISPLAY_FILE = "/tmp/.maidroid-display"

    private fun prepareRuntimeBody(): String =
        """
        set -e
        mkdir -p /root/napcat/cache /root/napcat/config /tmp /tmp/.X11-unix /tmp/runtime-root
        chmod 700 /tmp/runtime-root 2>/dev/null || true
        cat > /root/launcher.sh <<'EOF'
        #!/bin/bash
        set -u
        cd /root
        mkdir -p /root/napcat/cache /tmp /tmp/.X11-unix /tmp/runtime-root
        chmod 700 /tmp/runtime-root 2>/dev/null || true
        export XDG_RUNTIME_DIR=/tmp/runtime-root
        export HOME=/root
        export QQNT_PROFILE_DIR=/root/.config/QQ
        trap "" SIGPIPE

        if ! command -v qq >/dev/null 2>&1; then
            echo "[NapCat] qq executable not found in PATH"
            exit 127
        fi

        # 显示号不写死：手机上各个应用共用一套抽象 Unix 套接字，别的应用（另一个 MaiDroid、Termux 里开的 VNC）
        # 占着同一个号时，这边的 Xvfb 起不来，QQ 还会连到别人的显示上、一直出不了二维码（2026-11 真机实测）。
        # 让 Xvfb 用 -displayfd 自己挑第一个空着的号，挑中的号记下来，同一个 Xvfb 还在就接着用。
        display_file=$DISPLAY_FILE
        if [ -s "${'$'}display_file" ] && ps -eo args= | grep -F "Xvfb -displayfd" | grep -v grep >/dev/null 2>&1; then
            display="${'$'}(cat "${'$'}display_file")"
            echo "[NapCat] Reusing existing Xvfb :${'$'}display"
        else
            # 没有自己的 Xvfb 在跑：上次被强杀、断电留下的锁和套接字都是死的，先删掉
            rm -f /tmp/.X*-lock /tmp/.X11-unix/X* "${'$'}display_file"
            Xvfb -displayfd 3 -screen 0 1x1x8 +extension GLX +render 3>"${'$'}display_file" 2>&1 &
            xvfb_pid=$!
            # Xvfb 挑好号、能接受连接后才把号写到 3 号描述符，最多等 10 秒
            for _ in ${'$'}(seq 1 50); do
                [ -s "${'$'}display_file" ] && break
                kill -0 "${'$'}xvfb_pid" 2>/dev/null || break
                sleep 0.2
            done
            display="${'$'}(tr -cd '0-9' < "${'$'}display_file" 2>/dev/null)"
            if [ -z "${'$'}display" ]; then
                echo "[NapCat] Xvfb failed to start"
                exit 1
            fi
            echo "[NapCat] Xvfb started on :${'$'}display, pid=${'$'}xvfb_pid"
        fi
        export DISPLAY=":${'$'}display"

        echo "[NapCat] Launching LinuxQQ with NapCat preload"
        LD_PRELOAD=/root/libnapcat_launcher.so qq --no-sandbox --disable-gpu --disable-dev-shm-usage 2>&1
        exit_code=$?
        echo "[NapCat] qq exited with code ${'$'}exit_code"
        exit ${'$'}exit_code
        EOF
        chmod +x /root/launcher.sh
        """.trimIndent()
}
