package org.maiwithu.maidroid.platform.qq

import org.maiwithu.maidroid.diagnostics.AUTO_FIX_LOG_PREFIX
import org.maiwithu.maidroid.runtime.shellQuote

/**
 * LinuxQQ 在 Debian 容器里运行必需的系统共享库检查与补装脚本，供 NapCat 与 SnowLuma 安装和启动阶段共用。
 *
 * 官方 LinuxQQ .deb 未完整声明运行时共享库依赖（如 libnss3、libgbm1、libgssapi-krb5-2 与 ALSA 库），
 * 在精简 Debian rootfs 上直接装 deb 后运行会以 exit code 127 找不到 libgbm.so.1 等库退出；
 * 同时老版本用户可能已经在未补齐依赖库的情况下装好了 /opt/QQ，因此这里把检查与安装拆出 deb 安装分支，
 * 并在每次启动前通过 ldconfig 缓存快速检查，全齐时直接跳过、缺失时自动补装恢复。
 */
internal object LinuxQqRuntimeDeps {

    /**
     * 生成校验并按需补齐 LinuxQQ 运行依赖库的 shell 片段（纯 POSIX sh 语法，不含 heredoc）。
     * 调用前需先拼入 AptMirrors.shellFunctions() 以提供 maidroid_apt_install 与失败步骤变量。
     *
     * @param logPrefix 日志前缀，例如 "[SnowLuma]" 或 "[NapCat]"
     * @param failOnError true 用于安装流程，缺库且补装失败时打印原因并 exit 1；
     * false 用于启动前自愈，只记日志而不阻断后续生成启动脚本（例如离线启动排查阶段）
     * @param requireQqInstalled true 表示仅在容器内已经装有 qq 命令时才检测补装，避免空容器提前 apt 安装
     */
    fun ensureScript(
        logPrefix: String,
        failOnError: Boolean,
        requireQqInstalled: Boolean = false
    ): String {
        val condition = if (requireQqInstalled) {
            "command -v qq >/dev/null 2>&1 && ! maidroid_linuxqq_deps_ready"
        } else {
            "! maidroid_linuxqq_deps_ready"
        }
        val quotedPrefix = shellQuote(logPrefix)
        val quotedAutoFixPrefix = shellQuote(AUTO_FIX_LOG_PREFIX)

        val checkBlock = if (failOnError) {
            """
            if $condition; then
                printf "%s 补齐 LinuxQQ 运行依赖库...\n" $quotedPrefix
                if ! maidroid_linuxqq_install_deps; then
                    printf "LinuxQQ 运行依赖安装失败（%s），请检查网络后重试\n" "${'$'}{maidroid_apt_failed_step:-unknown}"
                    exit 1
                fi
            fi
            """.trimIndent()
        } else {
            """
            if $condition; then
                printf "%s %s 检测到缺少 LinuxQQ 运行依赖库，正在自动补装\n" $quotedAutoFixPrefix $quotedPrefix
                dpkg --configure -a 2>/dev/null || true
                if ! maidroid_linuxqq_install_deps; then
                    printf "%s 自动补装 LinuxQQ 运行依赖失败（%s），请检查网络后重试\n" $quotedPrefix "${'$'}{maidroid_apt_failed_step:-unknown}"
                fi
            fi
            """.trimIndent()
        }

        // 先定义一次 ldconfig 检查和补装函数，再按当前模式（严格安装 / 轻量启动自愈）决定缺库后的动作。
        return listOf(
            """
            # 一次读取 ldconfig 缓存并用 shell 内置 case 检查四个关键共享库，全齐时零额外子进程开销直接跳过。
            maidroid_linuxqq_deps_ready() {
                qq_ld_cache="${'$'}(ldconfig -p 2>/dev/null || true)"
                [ -n "${'$'}qq_ld_cache" ] || return 1
                case "${'$'}qq_ld_cache" in
                    *libnss3.so*) ;;
                    *) return 1 ;;
                esac
                case "${'$'}qq_ld_cache" in
                    *libgbm.so.1*) ;;
                    *) return 1 ;;
                esac
                case "${'$'}qq_ld_cache" in
                    *libgssapi_krb5.so.2*) ;;
                    *) return 1 ;;
                esac
                case "${'$'}qq_ld_cache" in
                    *libasound.so.2*) ;;
                    *) return 1 ;;
                esac
                return 0
            }

            # 补装 LinuxQQ 缺失的共享库，并兼容 Debian 12 (libasound2) 与 Debian 13+ (libasound2t64)。
            # 若本地 apt 索引为空导致两者都查询不到，先装前三个库顺带更新索引，再判定 ALSA 包名。
            maidroid_linuxqq_install_deps() {
                export DEBIAN_FRONTEND=noninteractive
                maidroid_apt_write_network_conf
                if apt-cache show libasound2t64 >/dev/null 2>&1; then
                    maidroid_apt_install libnss3 libgbm1 libgssapi-krb5-2 libasound2t64 || return 1
                elif apt-cache show libasound2 >/dev/null 2>&1; then
                    maidroid_apt_install libnss3 libgbm1 libgssapi-krb5-2 libasound2 || return 1
                else
                    maidroid_apt_install libnss3 libgbm1 libgssapi-krb5-2 || return 1
                    if apt-cache show libasound2t64 >/dev/null 2>&1; then
                        maidroid_apt_install libasound2t64 || return 1
                    else
                        maidroid_apt_install libasound2 || return 1
                    fi
                fi
                if command -v ldconfig >/dev/null 2>&1; then
                    ldconfig 2>/dev/null || true
                fi
            }
            """.trimIndent(),
            checkBlock
        ).joinToString("\n")
    }
}
