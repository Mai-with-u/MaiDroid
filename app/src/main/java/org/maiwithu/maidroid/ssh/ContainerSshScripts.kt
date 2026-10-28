package org.maiwithu.maidroid.ssh

import org.maiwithu.maidroid.runtime.ContainerScript
import org.maiwithu.maidroid.runtime.killMatchingProcessesScript
import org.maiwithu.maidroid.runtime.shellQuote

/** 容器 SSH 允许使用的端口范围：避开需要 root 才能监听的 1024 以下端口。 */
internal val ContainerSshPortRange = 1024..65535

/**
 * 容器里 sshd 的三段脚本：配置、前台启动、清理残留进程。
 * 由 ContainerSshManager 按“配置 -> 清理 -> 启动”的顺序执行。
 */
internal object ContainerSshScripts {

    /**
     * 首次启用或改了端口、密码时执行：
     * 修复 dpkg 半装状态 -> 没有 sshd 就 apt 安装 openssh-server -> 补齐 sshd 运行所需的用户和目录 ->
     * 改写 sshd_config 的端口和登录方式 -> 设置 root 密码 -> 生成主机密钥并自检配置。
     * proot 里 dpkg 经常停在半配置状态，安装失败时先尝试修复再重装一次。
     */
    fun configure(port: Int, rootPassword: String): ContainerScript {
        val safePort = port.coerceIn(ContainerSshPortRange)
        return ContainerScript(
            """
            set -e
            export DEBIAN_FRONTEND=noninteractive
            root_password=${shellQuote(rootPassword)}

            maidroid_dpkg_preflight() {
                mkdir -p /var/lib/dpkg /var/lib/dpkg/updates
                if [ -f /var/lib/dpkg/status ] && [ ! -f /var/lib/dpkg/status-old ]; then
                    cp /var/lib/dpkg/status /var/lib/dpkg/status-old 2>/dev/null || true
                fi
                if [ -f /var/lib/dpkg/status-new ]; then
                    mv /var/lib/dpkg/status-new /var/lib/dpkg/status
                fi
            }

            maidroid_dpkg_repair() {
                rm -f /var/lib/dpkg/status-old
                if [ -f /var/lib/dpkg/status ]; then
                    cp /var/lib/dpkg/status /var/lib/dpkg/status-old 2>/dev/null || true
                fi
                if [ -f /var/lib/dpkg/status-new ]; then
                    mv /var/lib/dpkg/status-new /var/lib/dpkg/status
                fi
                if [ -f /var/lib/dpkg/status ]; then
                    sed -i '/^Package: openssh-server$/,/^$/s/^Status:.*/Status: install ok installed/' /var/lib/dpkg/status 2>/dev/null || true
                fi
                dpkg --configure -a 2>/dev/null || true
                apt-get -f install -y 2>/dev/null || true
            }

            maidroid_dpkg_preflight
            if [ ! -x /usr/sbin/sshd ]; then
                apt-get -o Acquire::http::Pipeline-Depth=0 update
                apt-get install -y --no-install-recommends openssh-server || {
                    echo "apt install openssh-server failed, repairing dpkg state"
                    maidroid_dpkg_repair
                    if [ ! -x /usr/sbin/sshd ]; then
                        apt-get install -y --fix-broken || apt-get install -y --no-install-recommends openssh-server --fix-missing
                    fi
                }
            fi

            if [ ! -x /usr/sbin/sshd ]; then
                echo "openssh-server is not installed"
                exit 127
            fi

            if [ -f /etc/ssh/moduli.dpkg-new ] && [ ! -f /etc/ssh/moduli ]; then
                mv /etc/ssh/moduli.dpkg-new /etc/ssh/moduli
            fi

            if ! grep -q '^sshd:' /etc/passwd 2>/dev/null; then
                echo 'sshd:x:1000:1000:sshd:/var/empty:/usr/sbin/nologin' >> /etc/passwd
            fi
            if ! grep -q '^sshd:' /etc/shadow 2>/dev/null; then
                echo 'sshd:*:19688:0:99999:7:::' >> /etc/shadow
            fi
            if ! grep -q '^sshd:' /etc/group 2>/dev/null; then
                echo 'sshd:x:1000:' >> /etc/group
            fi

            mkdir -p /etc/ssh /run/sshd /var/empty
            chmod 0755 /run /run/sshd /var/empty 2>/dev/null || true
            touch /etc/ssh/sshd_config
            sed -i '/^[#[:space:]]*UsePrivilegeSeparation[[:space:]]/d' /etc/ssh/sshd_config 2>/dev/null || true
            sed -i '/^[#[:space:]]*Port[[:space:]]/d' /etc/ssh/sshd_config 2>/dev/null || true
            sed -i '/^[#[:space:]]*PermitRootLogin[[:space:]]/d' /etc/ssh/sshd_config 2>/dev/null || true
            sed -i '/^[#[:space:]]*PasswordAuthentication[[:space:]]/d' /etc/ssh/sshd_config 2>/dev/null || true
            sed -i '/^[#[:space:]]*KbdInteractiveAuthentication[[:space:]]/d' /etc/ssh/sshd_config 2>/dev/null || true
            sed -i '/^[#[:space:]]*ChallengeResponseAuthentication[[:space:]]/d' /etc/ssh/sshd_config 2>/dev/null || true
            sed -i '/^[#[:space:]]*UsePAM[[:space:]]/d' /etc/ssh/sshd_config 2>/dev/null || true
            cat >> /etc/ssh/sshd_config <<EOF
            Port $safePort
            PermitRootLogin yes
            PasswordAuthentication yes
            KbdInteractiveAuthentication no
            ChallengeResponseAuthentication no
            UsePAM no
            EOF

            printf 'root:%s\n' "${'$'}root_password" | chpasswd
            ssh-keygen -A 2>/dev/null || true
            /usr/sbin/sshd -t
            echo "sshd configured on port $safePort"
            """.trimIndent()
        )
    }

    /** 以前台模式（-D）运行 sshd，进程生命周期由 ContainerSshManager 持有，日志经 -e 打到标准错误。 */
    fun launch(port: Int): ContainerScript {
        val safePort = port.coerceIn(ContainerSshPortRange)
        return ContainerScript(
            """
            set -e
            mkdir -p /run/sshd /var/empty
            chmod 0755 /run /run/sshd /var/empty 2>/dev/null || true
            if [ ! -x /usr/sbin/sshd ]; then
                echo "openssh-server is not installed"
                exit 127
            fi
            /usr/sbin/sshd -t
            echo "sshd listening on port $safePort"
            exec /usr/sbin/sshd -D -e -p $safePort
            """.trimIndent()
        )
    }

    /** 结束容器里所有 sshd 并删掉 pid 文件；容器里没有 ps 时什么也不做。 */
    fun cleanup(): ContainerScript = ContainerScript(
        listOf(
            "set +e",
            "if command -v ps >/dev/null 2>&1; then",
            killMatchingProcessesScript(
                pattern = "\\/usr\\/sbin\\/sshd|sshd: ",
                processName = "sshd process"
            ),
            "fi",
            "rm -f /run/sshd.pid /run/sshd/sshd.pid 2>/dev/null || true",
            "exit 0"
        ).joinToString("\n")
    )
}
