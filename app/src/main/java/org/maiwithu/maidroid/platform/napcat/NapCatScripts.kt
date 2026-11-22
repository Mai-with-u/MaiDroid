package org.maiwithu.maidroid.platform.napcat

import org.maiwithu.maidroid.platform.qq.LinuxQqRuntimeDeps
import org.maiwithu.maidroid.platform.qq.QqCustomSource
import org.maiwithu.maidroid.platform.qq.QqOneBot
import org.maiwithu.maidroid.runtime.AptMirrors
import org.maiwithu.maidroid.runtime.ContainerBindMount
import org.maiwithu.maidroid.runtime.ContainerScript
import org.maiwithu.maidroid.runtime.ExitTarget
import org.maiwithu.maidroid.runtime.GitHubMirrors
import org.maiwithu.maidroid.runtime.killMatchingProcessesScript
import org.maiwithu.maidroid.runtime.requestExitScript
import org.maiwithu.maidroid.runtime.shellQuote

/**
 * NapCat（LinuxQQ + NapCat 注入）在容器里的安装、启动和清理脚本。
 *
 * 原安装脚本仓库不可用后，改由 MaiDroid 直接安装官方 LinuxQQ，并从用户填写的自定义仓库或压缩包直链
 * 下载 NapCat.Shell.zip；启动时不编译 C++ 预加载库，改为生成 loadNapCat.cjs 与修改后的 qq-package.json，
 * 在启动命令里通过 proot -b 临时绑定到 /opt/QQ/resources/app/package.json，不改动磁盘上的原版 QQ 文件。
 *
 * 安装进度通过 $TMPDIR/progress 和 progress_des 两个文件汇报（宿主侧对应 termux/tmp），
 * NapCatPlatformManager 每 500 毫秒读一次显示到消息平台页的进度环上。
 */
internal object NapCatScripts {

    // 停止时等 QQ 自己退出的上限；实测 1~2 秒，留足余量，超时就照常强制清理。
    private const val QQ_EXIT_TIMEOUT_SECONDS = 20

    /** NapCat 运行要求的 LinuxQQ 最低构建号与内置官方安装包（主用 3.2.34 及备选更新构建 3.2.35）。 */
    const val QQ_MIN_BUILD_VERSION = 53644
    const val QQ_VERSION = "3.2.34-53644"
    const val QQ_FALLBACK_VERSION = "3.2.35-54395"
    private const val QQ_PACKAGE = "linuxqq_${QQ_VERSION}_arm64.deb"
    private const val QQ_OFFICIAL_URL = "https://qqdl.gtimg.cn/qqfile/QQNT/9.9.36/beta/9ee04bef/$QQ_PACKAGE"
    private const val QQ_FALLBACK_URL =
        "https://qqdl.gtimg.cn/qqfile/QQNT/9.9.37/beta/816b3c79/linuxqq_${QQ_FALLBACK_VERSION}_arm64.deb"
    private const val DOWNLOAD_DIR = "/root/.cache/maidroid-napcat"

    /** NapCat 启动入口脚本：由绑定覆盖后的 QQ package.json main 字段引导加载。 */
    const val LOAD_SCRIPT = "/root/loadNapCat.cjs"

    /** 宿主机侧相对 rootfs 的覆盖配置与容器内挂载目标，启动时临时覆盖而不改原版 QQ 文件。 */
    const val QQ_PACKAGE_JSON_OVERRIDE_REL = "root/napcat/qq-package.json"
    const val QQ_PACKAGE_JSON_OVERRIDE = "/$QQ_PACKAGE_JSON_OVERRIDE_REL"
    const val QQ_PACKAGE_JSON_TARGET = "/opt/QQ/resources/app/package.json"

    /**
     * 安装或重装 NapCat 本体：写入 OneBot token -> 装系统依赖与官方 LinuxQQ ->
     * 从用户指定的自定义来源下载 NapCat.Shell.zip（保留已有 config/plugins）-> 生成 OneBot11 WebSocket 配置 ->
     * 打 napcat_ready 标记 -> 生成 loadNapCat.cjs、qq-package.json 与 launcher.sh。
     */
    fun install(token: String, source: QqCustomSource): ContainerScript = ContainerScript(
        body = listOf(
            "set -e",
            AptMirrors.shellFunctions(),
            GitHubMirrors.shellFunctions(),
            // 读出已保存的 token，没有才存下新的；后面写 onebot11.json 时用 shell 变量 napcat_token
            QqOneBot.ensureTokenScript(token, variable = "napcat_token"),
            installBody(source),
            prepareRuntimeBody(),
            "maidroid_progress 0.9 ${shellQuote("NapCat install completed")}"
        ).joinToString("\n"),
        workingDirectory = "/root"
    )

    /** 每次启动 NapCat 前执行：检测并自愈补装缺失的 LinuxQQ 运行依赖库，补齐目录，更新 loadNapCat.cjs / qq-package.json，并重新生成 /root/launcher.sh。 */
    fun prepareRuntime(): ContainerScript = ContainerScript(
        body = listOf(
            "set -e",
            AptMirrors.shellFunctions(),
            LinuxQqRuntimeDeps.ensureScript(
                logPrefix = "[NapCat]",
                failOnError = false,
                requireQqInstalled = true
            ),
            prepareRuntimeBody()
        ).joinToString("\n"),
        workingDirectory = "/root"
    )

    /**
     * 前台直接以 exec bash 接管 /root/launcher.sh（Xvfb + LinuxQQ），省掉额外 sh 与 script 伪终端进程。
     * 通过 extraBinds 把覆盖后的 qq-package.json 挂载到 QQ 安装目录，既能导入 NapCat 又不破坏原始文件。
     */
    fun launch(): ContainerScript = ContainerScript(
        body = "cd /root && exec bash /root/launcher.sh",
        workingDirectory = "/root",
        excludeTopCpuCluster = true,
        extraBinds = listOf(
            ContainerBindMount(
                rootfsRelativeSource = QQ_PACKAGE_JSON_OVERRIDE_REL,
                containerTarget = QQ_PACKAGE_JSON_TARGET
            )
        )
    )

    /**
     * 停止、重启和安全退出时，请 LinuxQQ 的主进程（launcher.sh 里那条 qq --no-sandbox --disable-gpu ...）自己下线退出，
     * 最多等 QQ_EXIT_TIMEOUT_SECONDS 秒。zygote、utility 等子进程（命令行带 --type=）由主进程自己收掉，所以只发给主进程。
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

    private fun installBody(source: QqCustomSource): String =
        """
        progress_file="${'$'}{TMPDIR:-/tmp}/progress"
        progress_des_file="${'$'}{TMPDIR:-/tmp}/progress_des"

        maidroid_progress() {
            printf "%s\n" "${'$'}1" > "${'$'}progress_file"
            printf "%s\n" "${'$'}2" > "${'$'}progress_des_file"
            printf "[NapCat] %s\n" "${'$'}2"
        }

        # 经镜像（GitHub 域名）或直连下载 NapCat.Shell.zip，并用 unzip -t 当场验证确实是完整压缩包，
        # 挡住代理失效、限流返回的 200 HTML 提示页面。
        maidroid_napcat_fetch_zip() {
            zip_target="${'$'}1"
            for candidate_url in ${'$'}(maidroid_download_urls "${'$'}zip_target"); do
                rm -f $DOWNLOAD_DIR/NapCat.Shell.zip.part
                printf "[NapCat] downloading %s\n" "${'$'}candidate_url"
                if curl -fL --connect-timeout 20 --speed-limit 1024 --speed-time 30 \
                    -o $DOWNLOAD_DIR/NapCat.Shell.zip.part "${'$'}candidate_url" && \
                    unzip -t $DOWNLOAD_DIR/NapCat.Shell.zip.part >/dev/null 2>&1; then
                    mv $DOWNLOAD_DIR/NapCat.Shell.zip.part $DOWNLOAD_DIR/NapCat.Shell.zip
                    return 0
                fi
                printf "[NapCat] download or zip check failed, trying next source\n"
            done
            rm -f $DOWNLOAD_DIR/NapCat.Shell.zip.part
            return 1
        }

        # 向 GitHub Releases 接口查 NapCat.Shell.zip（兼容最新版是 pre-release 或略微改名的发行包）。
        maidroid_napcat_fetch_github_release() {
            api_url="${'$'}1"
            jq_filter="${'$'}2"
            for api_candidate in ${'$'}(maidroid_download_urls "${'$'}api_url"); do
                printf "[NapCat] querying release metadata: %s\n" "${'$'}api_candidate"
                api_json="${'$'}(curl -fsSL --connect-timeout 15 --max-time 30 "${'$'}api_candidate" 2>/dev/null || true)"
                [ -n "${'$'}api_json" ] || continue
                asset_url="${'$'}(printf '%s' "${'$'}api_json" | jq -r "${'$'}jq_filter" 2>/dev/null || true)"
                if [ -n "${'$'}asset_url" ] && [ "${'$'}asset_url" != "null" ]; then
                    if maidroid_napcat_fetch_zip "${'$'}asset_url"; then
                        return 0
                    fi
                fi
            done
            return 1
        }

        # 按候选列表依次下载 LinuxQQ 安装包：同一来源支持最多 3 次 curl -C - 断点续传重试（应对中途断连 56），
        # 初次请求即遇到 404 等 HTTP 错误（curl 22 且未落盘任何字节）则立刻切换下一个候选源，最后用 dpkg-deb -I 验包。
        maidroid_napcat_fetch_linuxqq() {
            qq_dest="${'$'}1"
            shift
            for qq_url in "${'$'}@"; do
                rm -f "${'$'}qq_dest.part"
                printf "[NapCat] downloading LinuxQQ %s\n" "${'$'}qq_url"
                qq_attempt=1
                while [ "${'$'}qq_attempt" -le 3 ]; do
                    curl_rc=0
                    curl -fL -C - --connect-timeout 20 --speed-limit 1024 --speed-time 30 \
                        -o "${'$'}qq_dest.part" "${'$'}qq_url" || curl_rc=${'$'}?
                    if [ "${'$'}curl_rc" -eq 0 ] && dpkg-deb -I "${'$'}qq_dest.part" >/dev/null 2>&1; then
                        mv "${'$'}qq_dest.part" "${'$'}qq_dest"
                        return 0
                    fi
                    if [ "${'$'}curl_rc" -eq 22 ] && [ ! -s "${'$'}qq_dest.part" ]; then
                        printf "[NapCat] LinuxQQ candidate unavailable (http error), trying next source\n"
                        rm -f "${'$'}qq_dest.part"
                        break
                    fi
                    if [ "${'$'}curl_rc" -eq 0 ] || [ "${'$'}curl_rc" -eq 22 ] || [ "${'$'}curl_rc" -eq 33 ]; then
                        rm -f "${'$'}qq_dest.part"
                    fi
                    printf "[NapCat] LinuxQQ download attempt %s/3 failed (rc=%s), retrying\n" "${'$'}qq_attempt" "${'$'}curl_rc"
                    qq_attempt=${'$'}((qq_attempt + 1))
                    sleep 2
                done
                rm -f "${'$'}qq_dest.part"
            done
            return 1
        }

        maidroid_progress 0.05 "Prepare NapCat install"

        # 只有上一次初装打断、连默认配置都没写过的半成品目录才挪到备份里；重装时保留已有 config 里的账号设置。
        if [ -d "${'$'}HOME/napcat" ] && [ ! -f "${'$'}HOME/.maidroid/napcat_ready" ] && [ ! -f "${'$'}HOME/napcat/config/onebot11.json" ]; then
            if find "${'$'}HOME/napcat" -mindepth 1 -type f | grep -q .; then
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

        maidroid_progress 0.10 "Prepare system packages"
        export DEBIAN_FRONTEND=noninteractive
        if ! dpkg --configure -a; then
            printf "[NapCat] dpkg --configure -a failed, apt-get -f install will try to repair\n"
        fi
        maidroid_apt_write_network_conf
        if ! maidroid_apt_install zip unzip jq curl xvfb procps ca-certificates; then
            printf "装不上 NapCat 需要的系统软件包（%s），请检查网络后重试\n" "${'$'}{maidroid_apt_failed_step:-unknown}"
            exit 1
        fi

        # 旧版容器在 /data/data 路径下装过 unzip 时，--link2symlink 留下的软链在 /data/user/0 下会失效，
        # dpkg 仍认为已安装而跳过；调不通 unzip 时清掉失效软链再强制重装一次。
        if ! unzip -v >/dev/null 2>&1; then
            printf "[NapCat] unzip is not runnable under current rootfs path, reinstalling unzip\n"
            rm -f /usr/bin/unzip /usr/bin/zipinfo
            if ! maidroid_apt_install --reinstall unzip || ! unzip -v >/dev/null 2>&1; then
                printf "修复 NapCat 需要的 unzip 失败（%s），请检查网络后重试\n" "${'$'}{maidroid_apt_failed_step:-unknown}"
                exit 1
            fi
        fi

        # 检查并安装官方 LinuxQQ：已装有符合最低构建号的版本时跳过，避免重复下载两百 MB 安装包。
        maidroid_progress 0.20 "Check LinuxQQ"
        mkdir -p $DOWNLOAD_DIR
        if [ -f $QQ_PACKAGE_JSON_TARGET ] && \
            jq -e '.buildVersion | tonumber >= $QQ_MIN_BUILD_VERSION' $QQ_PACKAGE_JSON_TARGET >/dev/null 2>&1 && \
            command -v qq >/dev/null 2>&1; then
            printf "[NapCat] LinuxQQ buildVersion >= $QQ_MIN_BUILD_VERSION already installed\n"
        else
            maidroid_progress 0.25 "Download LinuxQQ"
            rm -f $DOWNLOAD_DIR/$QQ_PACKAGE $DOWNLOAD_DIR/$QQ_PACKAGE.part
            if ! maidroid_napcat_fetch_linuxqq $DOWNLOAD_DIR/$QQ_PACKAGE \
                ${shellQuote(QQ_OFFICIAL_URL)} ${shellQuote(QQ_FALLBACK_URL)}; then
                printf "下载不到有效的 LinuxQQ 安装包，请检查网络后重试\n"
                exit 1
            fi

            maidroid_progress 0.38 "Install LinuxQQ"
            if ! maidroid_apt_install --allow-downgrades $DOWNLOAD_DIR/$QQ_PACKAGE; then
                rm -f $DOWNLOAD_DIR/$QQ_PACKAGE
                printf "LinuxQQ 安装失败（%s），请检查网络后重试\n" "${'$'}{maidroid_apt_failed_step:-unknown}"
                exit 1
            fi
            rm -f $DOWNLOAD_DIR/$QQ_PACKAGE
        fi
        """.trimIndent() + "\n" +
        // 运行依赖校验与补装放在 LinuxQQ if/else 外：避免此前由 SnowLuma 等方式已装过 /opt/QQ 时跳过补库。
        LinuxQqRuntimeDeps.ensureScript(logPrefix = "[NapCat]", failOnError = true) + "\n" +
        """

        maidroid_progress 0.55 "Download NapCat package"
        """.trimIndent() + "\n" +
        downloadCustomArchiveScript(source) + "\n" +
        """
        if [ "${'$'}napcat_zip_ready" != 1 ]; then
            printf "从自定义地址下载不到有效的 NapCat.Shell.zip，请检查填写的仓库或直链是否包含该发行包\n"
            exit 1
        fi
        napcat_sha="${'$'}(sha256sum $DOWNLOAD_DIR/NapCat.Shell.zip | awk '{print ${'$'}1}')"
        printf "[NapCat] custom package sha256: %s (not verified against official checksum)\n" "${'$'}napcat_sha"

        maidroid_progress 0.74 "Unpack NapCat"
        rm -rf $DOWNLOAD_DIR/unpack
        mkdir -p $DOWNLOAD_DIR/unpack
        unzip -q -o $DOWNLOAD_DIR/NapCat.Shell.zip -d $DOWNLOAD_DIR/unpack
        if [ -f $DOWNLOAD_DIR/unpack/napcat.mjs ]; then
            napcat_src="$DOWNLOAD_DIR/unpack"
        else
            napcat_entry="${'$'}(find $DOWNLOAD_DIR/unpack -maxdepth 2 -type f -name 'napcat.mjs' | head -n 1)"
            napcat_src="${'$'}(dirname "${'$'}{napcat_entry:-/missing}")"
        fi
        if [ ! -f "${'$'}{napcat_src}/napcat.mjs" ]; then
            rm -rf $DOWNLOAD_DIR/unpack $DOWNLOAD_DIR/NapCat.Shell.zip
            printf "压缩包内未找到 napcat.mjs，请确认下载的是 NapCat.Shell.zip\n"
            exit 1
        fi
        mkdir -p "${'$'}HOME/napcat/config" "${'$'}HOME/napcat/plugins"
        for entry in "${'$'}{napcat_src}"/*; do
            [ -e "${'$'}entry" ] || continue
            base="${'$'}(basename "${'$'}entry")"
            case "${'$'}base" in
                config|plugins)
                    if [ -d "${'$'}entry" ]; then
                        mkdir -p "${'$'}HOME/napcat/${'$'}base"
                        cp -rn "${'$'}entry"/. "${'$'}HOME/napcat/${'$'}base"/ 2>/dev/null || true
                    fi
                    ;;
                *)
                    rm -rf "${'$'}HOME/napcat/${'$'}base"
                    cp -a "${'$'}entry" "${'$'}HOME/napcat/${'$'}base"
                    ;;
            esac
        done
        rm -rf $DOWNLOAD_DIR/unpack $DOWNLOAD_DIR/NapCat.Shell.zip

        maidroid_progress 0.80 "Write NapCat OneBot11 WebSocket config"
        mkdir -p "${'$'}HOME/napcat/config"
        cat > "${'$'}HOME/napcat/config/onebot11.json.tmp" <<EOF
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
        mv -f "${'$'}HOME/napcat/config/onebot11.json.tmp" "${'$'}HOME/napcat/config/onebot11.json"

        maidroid_progress 0.86 "Record NapCat install state"
        mkdir -p "${'$'}HOME/.maidroid"
        printf "ready\n" > "${'$'}HOME/.maidroid/napcat_ready.tmp"
        mv -f "${'$'}HOME/.maidroid/napcat_ready.tmp" "${'$'}HOME/.maidroid/napcat_ready"
        """.trimIndent()

    /**
     * 按用户填写的地址类型生成 NapCat.Shell.zip 下载逻辑：
     * - 直链直接尝试经镜像（或直连）下载；
     * - 仓库链接带 tag 时按该 tag 下载，不带 tag 时先试 releases/latest，失败再用 GitHub Releases API 兜底查资产。
     */
    private fun downloadCustomArchiveScript(source: QqCustomSource): String = when (source) {
        is QqCustomSource.DirectArchive ->
            """
            napcat_zip_ready=0
            if maidroid_napcat_fetch_zip ${shellQuote(source.rawUrl)}; then
                napcat_zip_ready=1
            fi
            """.trimIndent()
        is QqCustomSource.Repository -> {
            val assetFilter =
                "[(.assets[]? | select(.name == \"NapCat.Shell.zip\") | .browser_download_url), " +
                    "(.assets[]? | select((.name | test(\"NapCat\\\\.Shell.*\\\\.zip\$\"; \"i\")) and ((.name | test(\"windows\"; \"i\")) | not)) | .browser_download_url)][0] // empty"
            val listAssetFilter =
                "[(.[]?.assets[]? | select(.name == \"NapCat.Shell.zip\") | .browser_download_url), " +
                    "(.[]?.assets[]? | select((.name | test(\"NapCat\\\\.Shell.*\\\\.zip\$\"; \"i\")) and ((.name | test(\"windows\"; \"i\")) | not)) | .browser_download_url)][0] // empty"
            if (source.pinnedTag != null) {
                val directTagUrl = "${source.repoBaseUrl}/releases/download/${source.pinnedTag}/NapCat.Shell.zip"
                val apiFallback = source.gitHubRepoSlug?.let { slug ->
                    val tagApiUrl = "https://api.github.com/repos/$slug/releases/tags/${source.pinnedTag}"
                    """
                    elif maidroid_napcat_fetch_github_release ${shellQuote(tagApiUrl)} ${shellQuote(assetFilter)}; then
                        napcat_zip_ready=1
                    """.trimIndent()
                }.orEmpty()
                """
                napcat_zip_ready=0
                if maidroid_napcat_fetch_zip ${shellQuote(directTagUrl)}; then
                    napcat_zip_ready=1
                $apiFallback
                fi
                """.trimIndent()
            } else {
                val latestUrl = "${source.repoBaseUrl}/releases/latest/download/NapCat.Shell.zip"
                val apiFallback = source.gitHubRepoSlug?.let { slug ->
                    val latestApiUrl = "https://api.github.com/repos/$slug/releases/latest"
                    val listApiUrl = "https://api.github.com/repos/$slug/releases?per_page=5"
                    """
                    elif maidroid_napcat_fetch_github_release ${shellQuote(latestApiUrl)} ${shellQuote(assetFilter)}; then
                        napcat_zip_ready=1
                    elif maidroid_napcat_fetch_github_release ${shellQuote(listApiUrl)} ${shellQuote(listAssetFilter)}; then
                        napcat_zip_ready=1
                    """.trimIndent()
                }.orEmpty()
                """
                napcat_zip_ready=0
                if maidroid_napcat_fetch_zip ${shellQuote(latestUrl)}; then
                    napcat_zip_ready=1
                $apiFallback
                fi
                """.trimIndent()
            }
        }
    }

    // Xvfb 挑中的显示号记在这里，launcher.sh 写、cleanup 删
    private const val DISPLAY_FILE = "/tmp/.maidroid-display"

    private fun prepareRuntimeBody(): String =
        """
        set -e
        mkdir -p /root/napcat/cache /root/napcat/config /tmp /tmp/.X11-unix /tmp/runtime-root
        chmod 700 /tmp/runtime-root 2>/dev/null || true
        cat > $LOAD_SCRIPT <<'EOF'
        const path = require('path');
        const currentPath = process.env.NAPCAT_BOOTMAIN || path.dirname(__filename);
        (async () => {
            await import('file://' + path.join(currentPath, './napcat/napcat.mjs'));
        })();
        EOF
        if [ -f $QQ_PACKAGE_JSON_TARGET ]; then
            # 改写后的 package.json 单独保存在 /root/napcat 下，由 proot 在启动 NapCat 时挂载覆盖原文件。
            if command -v jq >/dev/null 2>&1; then
                jq --arg main "../../../../root/loadNapCat.cjs" '.main = ${'$'}main' $QQ_PACKAGE_JSON_TARGET > $QQ_PACKAGE_JSON_OVERRIDE.tmp
            else
                sed 's|"main"[[:space:]]*:[[:space:]]*"[^"]*"|"main": "../../../../root/loadNapCat.cjs"|' $QQ_PACKAGE_JSON_TARGET > $QQ_PACKAGE_JSON_OVERRIDE.tmp
            fi
            mv $QQ_PACKAGE_JSON_OVERRIDE.tmp $QQ_PACKAGE_JSON_OVERRIDE
        fi
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

        echo "[NapCat] Launching LinuxQQ with NapCat package binding"
        qq --no-sandbox --disable-gpu --disable-dev-shm-usage 2>&1
        exit_code=$?
        echo "[NapCat] qq exited with code ${'$'}exit_code"
        exit ${'$'}exit_code
        EOF
        chmod +x /root/launcher.sh
        """.trimIndent()
}
