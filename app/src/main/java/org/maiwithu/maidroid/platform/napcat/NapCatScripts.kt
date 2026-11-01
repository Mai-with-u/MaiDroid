package org.maiwithu.maidroid.platform.napcat

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
     * token 只在第一次安装时生成，之后重装沿用容器里已保存的那份，避免 MaiBot 适配器配置失配。
     * MaiBot 侧的 SnowLuma 适配器不在这里装：脚本跑完后由 SnowLumaAdapterInstaller 另外安装，配置等 MaiBot 生成后再迁移，
     * 这样 NapCat 已装好、只缺适配器（老用户迁移）时不用再跑一遍 NapCat 安装器。
     */
    fun install(token: String): ContainerScript = ContainerScript(
        body = listOf(
            "set -e",
            GitHubMirrors.shellFunctions(),
            installBody(token),
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
        workingDirectory = "/root"
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

    /** 结束残留的 Xvfb、qq、launcher 进程并删除 X11 锁，避免新一轮启动时显示号 :1 被占。 */
    fun cleanup(): ContainerScript = ContainerScript(
        body = listOf(
            "set +e",
            killMatchingProcessesScript(
                pattern = "Xvfb :1|qq --no-sandbox|launcher[.]sh|libnapcat_launcher[.]so",
                processName = "stale process",
                logPrefix = "[NapCat] "
            ),
            "rm -f /tmp/.X1-lock",
            "rm -f /tmp/.X11-unix/X1",
            "exit 0"
        ).joinToString("\n"),
        workingDirectory = "/root"
    )

    private fun installBody(token: String): String =
        """
        progress_file="${'$'}{TMPDIR:-/tmp}/progress"
        progress_des_file="${'$'}{TMPDIR:-/tmp}/progress_des"

        maidroid_progress() {
            printf "%s\n" "${'$'}1" > "${'$'}progress_file"
            printf "%s\n" "${'$'}2" > "${'$'}progress_des_file"
            printf "[NapCat] %s\n" "${'$'}2"
        }

        maidroid_progress 0.05 "Prepare NapCat install"
        mkdir -p "${'$'}HOME/.maidroid"
        if [ -s "${'$'}HOME/.maidroid/napcat_token" ]; then
            napcat_token="${'$'}(cat "${'$'}HOME/.maidroid/napcat_token")"
        else
            napcat_token="$token"
            printf "%s" "${'$'}napcat_token" > "${'$'}HOME/.maidroid/napcat_token"
            chmod 600 "${'$'}HOME/.maidroid/napcat_token" 2>/dev/null || true
        fi

        if [ -d "${'$'}HOME/napcat" ] && [ ! -f "${'$'}HOME/.maidroid/napcat_ready" ]; then
            if find "${'$'}HOME/napcat" -mindepth 1 -type f | grep -q .; then
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
                "host": "${SnowLumaAdapterConfig.ONE_BOT_HOST}",
                "port": ${SnowLumaAdapterConfig.ONE_BOT_PORT},
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
        export DISPLAY=:1
        export XDG_RUNTIME_DIR=/tmp/runtime-root
        export HOME=/root
        export QQNT_PROFILE_DIR=/root/.config/QQ
        trap "" SIGPIPE

        if ! command -v qq >/dev/null 2>&1; then
            echo "[NapCat] qq executable not found in PATH"
            exit 127
        fi

        if ! ps -eo args= | grep -F "Xvfb :1" | grep -v grep >/dev/null 2>&1; then
            rm -f /tmp/.X1-lock /tmp/.X11-unix/X1
            Xvfb :1 -screen 0 1x1x8 +extension GLX +render 2>&1 &
            xvfb_pid=$!
            echo "[NapCat] Xvfb started, pid=${'$'}xvfb_pid"
            sleep 1
        else
            echo "[NapCat] Reusing existing Xvfb :1"
        fi

        echo "[NapCat] Launching LinuxQQ with NapCat preload"
        LD_PRELOAD=/root/libnapcat_launcher.so qq --no-sandbox --disable-gpu --disable-dev-shm-usage 2>&1
        exit_code=$?
        echo "[NapCat] qq exited with code ${'$'}exit_code"
        exit ${'$'}exit_code
        EOF
        chmod +x /root/launcher.sh
        """.trimIndent()
}
