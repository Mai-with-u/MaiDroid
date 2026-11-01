package org.maiwithu.maidroid.runtime

/**
 * OOBE“配置容器”一步里对刚解包的 Debian rootfs 做的初始化。
 *
 * 执行顺序（见 OobeSetupManager.prepareContainer）：
 * 共享存储入口 -> DNS -> apt 换清华源 -> 安装 git、curl、CA 证书。
 * 每段脚本都可重复执行：已有的备份不会被覆盖，重跑只会把配置恢复成 MaiDroid 期望的样子。
 */
internal object ContainerSetupScripts {

    /** 在 /root/storage 下建到 /sdcard 各常用目录的链接，方便在容器里存取手机文件。 */
    fun setupStorageBridge(): ContainerScript = ContainerScript(
        """
        set -e
        mkdir -p /root/storage
        ln -sfn /sdcard /root/storage/shared
        ln -sfn /sdcard/Download /root/storage/downloads
        ln -sfn /sdcard/DCIM /root/storage/dcim
        ln -sfn /sdcard/Pictures /root/storage/pictures
        ln -sfn /sdcard/Movies /root/storage/movies
        ln -sfn /sdcard/Music /root/storage/music
        """.trimIndent()
    )

    /**
     * proot 里没有 Android 的 DNS 解析服务，rootfs 自带的 resolv.conf 往往不可用：
     * 先备份原文件（只备份一次）-> 写入 ContainerDns 生成的配置（手机当前网络的 DNS 在前，公共 DNS 兜底）。
     * systemServers 由调用方在宿主侧读出，见 ContainerDns.systemServers。
     */
    fun configureDns(systemServers: List<String>): ContainerScript = ContainerScript(
        listOf(
            """
            set -e
            if [ -e /etc/resolv.conf ] || [ -L /etc/resolv.conf ]; then
                if [ ! -e /etc/resolv.conf.maidroid.bak ] && [ ! -L /etc/resolv.conf.maidroid.bak ]; then
                    cp -P /etc/resolv.conf /etc/resolv.conf.maidroid.bak 2>/dev/null || true
                fi
            fi
            rm -f /etc/resolv.conf
            """.trimIndent(),
            "printf '%s' ${shellQuote(ContainerDns.resolvConf(systemServers))} > /etc/resolv.conf",
            """
            chmod 0644 /etc/resolv.conf
            printf "[DNS] nameservers: %s\n" "${'$'}(awk '/^nameserver/ { printf "%s ", ${'$'}2 }' /etc/resolv.conf)"
            """.trimIndent()
        ).joinToString("\n")
    )

    /**
     * 把 apt 源换成 AptMirrors.DEFAULT（清华镜像）：备份原 sources.list（只备份一次）->
     * 停用 sources.list.d 里的其他源 -> 写入新源和重试配置 -> 清空旧的索引缓存。
     * 清华镜像下载不下来时，下一步 installSystemDependencies 会自己换源重试。
     */
    fun configureAptMirror(): ContainerScript = ContainerScript(
        listOf(
            AptMirrors.shellFunctions(),
            """
            set -e
            mkdir -p /etc/apt/sources.list.d /etc/apt/apt.conf.d
            if [ -f /etc/apt/sources.list ] && [ ! -f /etc/apt/sources.list.maidroid.bak ]; then
                cp /etc/apt/sources.list /etc/apt/sources.list.maidroid.bak
            fi
            for source_file in /etc/apt/sources.list.d/*.list /etc/apt/sources.list.d/*.sources; do
                [ -e "${'$'}source_file" ] || continue
                case "${'$'}source_file" in
                    *.maidroid.disabled) ;;
                    *) mv "${'$'}source_file" "${'$'}source_file.maidroid.disabled" ;;
                esac
            done
            maidroid_apt_write_sources ${shellQuote(AptMirrors.DEFAULT.baseUrl)}
            cat > /etc/apt/apt.conf.d/99maidroid-network <<'EOF'
            Acquire::Retries "3";
            Acquire::http::Pipeline-Depth "0";
            EOF
            maidroid_apt_reset_lists
            """.trimIndent()
        ).joinToString("\n")
    )

    /**
     * 安装 MaiBot 安装流程依赖的系统工具：git 克隆源码，curl 和 CA 证书下载 uv。
     * 收拾上次被打断的 dpkg -> 更新软件源索引 -> 安装；下载失败时按 AptMirrors 的顺序自动换源重试，
     * 换成功的源留着给之后的 apt 用。每一步失败都以中文原因结尾，供页脚显示。
     */
    fun installSystemDependencies(): ContainerScript = ContainerScript(
        listOf(
            AptMirrors.shellFunctions(),
            """
            export DEBIAN_FRONTEND=noninteractive
            # OOBE 期间没有前台服务，装到一半时切到后台、划掉应用都会让系统结束进程，留下 dpkg 半装状态；
            # 不先收拾，之后每次 apt-get install 都报 dpkg was interrupted，点多少次“重新配置”都过不去。
            if ! dpkg --configure -a; then
                printf "[apt] dpkg --configure -a failed, apt-get -f install will try to repair\n"
            fi
            # apt-get update 有源下载失败时只打 W: 警告、照样返回 0，真正的网络错误会被吞掉，
            # 后面只剩一句 Unable to locate package；maidroid_apt_install 里用 --error-on=any 让它如实失败。
            if ! maidroid_apt_install git ca-certificates curl; then
                case "${'$'}maidroid_apt_failed_step" in
                    mirrors) printf "软件源下载失败（${AptMirrors.allNames}都试过了），请检查网络后点重新配置\n" ;;
                    update) printf "软件源索引更新失败，详情见终端输出\n" ;;
                    *) printf "安装 Git、curl 和证书失败，详情见终端输出\n" ;;
                esac
                exit 1
            fi
            """.trimIndent()
        ).joinToString("\n")
    )
}
