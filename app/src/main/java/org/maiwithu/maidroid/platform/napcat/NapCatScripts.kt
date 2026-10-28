package org.maiwithu.maidroid.platform.napcat

import org.maiwithu.maidroid.runtime.ContainerPaths
import org.maiwithu.maidroid.runtime.ContainerScript
import org.maiwithu.maidroid.runtime.killMatchingProcessesScript
import org.maiwithu.maidroid.runtime.shellQuote

/**
 * NapCat（LinuxQQ + NapCat 注入）在容器里的安装、启动和清理脚本。
 *
 * 安装进度通过 $TMPDIR/progress 和 progress_des 两个文件汇报（宿主侧对应 termux/tmp），
 * NapCatPlatformManager 每 500 毫秒读一次显示到消息平台页的进度环上。
 */
internal object NapCatScripts {

    /**
     * 首次安装：写入 OneBot token -> 跑官方 napcat 安装器 -> 生成 OneBot11 WebSocket 服务端配置 ->
     * 克隆 MaiBot 的 NapCat 适配器插件并写好连接配置 -> 打 napcat_ready 标记 -> 生成 launcher.sh。
     * token 只在第一次安装时生成，之后重装沿用容器里已保存的那份，避免 MaiBot 适配器配置失配。
     */
    fun install(token: String): ContainerScript = ContainerScript(
        body = listOf(
            "set -e",
            installBody(token),
            prepareRuntimeBody(),
            "maidroid_progress 1.0 ${shellQuote("NapCat install and auto configuration completed")}"
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
        curl -fL --connect-timeout 20 -o napcat.sh https://raw.githubusercontent.com/NapNeko/napcat-linux-installer/refs/heads/main/install.sh

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
                "host": "127.0.0.1",
                "port": 8095,
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

        maidroid_progress 0.88 "Install and configure MaiBot NapCat adapter"
        INSTALL_DIR=${shellQuote(ContainerPaths.MAIBOT_DIR)}
        ADAPTER_DIR="${'$'}INSTALL_DIR/plugins/MaiBot-Napcat-Adapter"
        if [ -d "${'$'}INSTALL_DIR" ]; then
            mkdir -p "${'$'}INSTALL_DIR/plugins"
            if [ ! -d "${'$'}ADAPTER_DIR" ]; then
                # 适配器仓库现在在 Mai-with-u 组织下，直接用现名克隆，不再依赖旧组织名的自动跳转。
                git clone --depth=1 --branch main https://github.com/Mai-with-u/MaiBot-Napcat-Adapter.git "${'$'}ADAPTER_DIR"
            fi
            cat > "${'$'}ADAPTER_DIR/config.toml" <<EOF
        [plugin]
        enabled = true
        config_version = "0.1.0"

        [napcat_server]
        host = "127.0.0.1"
        port = 8095
        token = "${'$'}napcat_token"
        heartbeat_interval = 30.0
        reconnect_delay_sec = 5.0
        action_timeout_sec = 15.0
        EOF
        else
            printf "[NapCat] MaiBot directory does not exist, skip adapter config: %s\n" "${'$'}INSTALL_DIR"
        fi

        maidroid_progress 0.96 "Record NapCat install state"
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
