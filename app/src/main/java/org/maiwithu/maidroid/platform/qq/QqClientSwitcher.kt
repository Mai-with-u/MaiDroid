package org.maiwithu.maidroid.platform.qq

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.maiwithu.maidroid.platform.napcat.NapCatProcessSupervisor
import org.maiwithu.maidroid.platform.snowluma.SnowLumaProcessSupervisor
import org.maiwithu.maidroid.terminal.TerminalLog

/**
 * 在 NapCat 和 SnowLuma 之间切换 QQ 协议端，两边的平台管理器共用一个（AppDependencies 里）。
 * 两者共用 /opt/QQ、QQ 的登录数据和 OneBot 端口，同时跑会抢端口、两个 QQ 互相挤下线，所以同一时刻只启用一个。
 */
internal class QqClientSwitcher(
    private val choice: QqClientChoice,
    private val napCatProcess: NapCatProcessSupervisor,
    private val snowLumaProcess: SnowLumaProcessSupervisor
) {
    /**
     * 两种协议端的安装共用这把锁：两边的安装脚本都会跑 apt、装 LinuxQQ，同时跑会抢 dpkg 的锁、互相覆盖 /opt/QQ。
     * 安装流程用 tryLock，拿不到就告诉用户等另一边装完。
     */
    val installLock = Mutex()

    /** 现在启用的协议端（没有记录时是 NapCat）。读一个小文件，界面每 2 秒刷新状态时调用。 */
    fun activeClient(): QqClient = choice.read()

    /**
     * 启用 client：先记下（另一个之后不会再被拉起）-> 停掉另一个，挂起到它的 QQ 正常下线。
     * 调用方随后经前台服务拉起运行时，被启用的这个才会启动；先停后起，两个 QQ 不会同时在跑。
     */
    suspend fun activate(client: QqClient) {
        val previous = withContext(Dispatchers.IO) {
            choice.read().also { choice.write(client) }
        }
        if (previous != client) {
            TerminalLog.append("${client.logPrefix} 改用 ${client.label} 登录 QQ，停用 ${previous.label}（不卸载，随时可以切回）")
        }
        when (client) {
            QqClient.NapCat -> snowLumaProcess.stopAndWait()
            QqClient.SnowLuma -> napCatProcess.stopAndWait()
        }
    }
}
