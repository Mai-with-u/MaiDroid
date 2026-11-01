package org.maiwithu.maidroid.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 盯着手机现在有没有网，给“断网了，等网络恢复后自动重试”用（首次引导、装消息平台），
 * 网络一有变化（断开、恢复、Wi-Fi 和移动数据切换、DNS 变了）changes 就加一，前台服务据此刷新容器的 DNS。
 * 第一次读 connected 或 changes 时才向系统注册默认网络的回调，之后进程活着就一直盯着；注册不上时停在注册那一刻的状态。
 * 每次回调都重新读一遍当前默认网络（NetworkStatus.read），不按回调里的那个网络下结论：
 * Wi-Fi 和移动数据切换时，旧网络的 onLost 可能比新网络的 onAvailable 晚到，按它判断会误报断网。
 */
internal class NetworkMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val state = MutableStateFlow(true)
    private val changeCount = MutableStateFlow(0)
    private val registered by lazy {
        state.value = NetworkStatus.read(appContext).connected
        register()
    }

    /** 手机现在有没有连上能上网的网络（不要求系统验证过，理由见 NetworkStatus）。 */
    val connected: StateFlow<Boolean>
        get() {
            registered
            return state.asStateFlow()
        }

    /** 默认网络每变一次加一（值本身没有含义，只用来触发“按现在的网络刷新一遍”）。 */
    val changes: StateFlow<Int>
        get() {
            registered
            return changeCount.asStateFlow()
        }

    private fun register() {
        runCatching {
            appContext.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = refresh()

                    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = refresh()

                    override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = refresh()

                    override fun onLost(network: Network) = refresh()
                }
            )
        }
    }

    private fun refresh() {
        state.value = NetworkStatus.read(appContext).connected
        changeCount.value += 1
    }
}
