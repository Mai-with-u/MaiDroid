package org.maiwithu.maidroid.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * 手机当前的默认网络：有没有连上、走的是 Wi-Fi 还是移动数据（开着 VPN 也标出来）、系统有没有验证过能上网。
 * 容器里的下载前先看一眼，根本没网时直接告诉用户，不用等所有镜像都试一遍超时；诊断报告里也写一份。
 * 不要求 validated：国内不少网络连不上系统的连通性检测地址，验证不过但其实能上网。
 */
internal data class NetworkStatus(
    val connected: Boolean,
    val transports: List<String> = emptyList(),
    val validated: Boolean = false
) {
    fun describe(): String = when {
        !connected -> "没有连接网络"
        else -> transports.ifEmpty { listOf("未知类型") }.joinToString(" + ") +
            if (validated) "，系统已验证可上网" else "，系统未验证可上网"
    }

    companion object {
        /** 读当前默认网络；读不到时按“已连接、类型未知”处理，不因为读不到就拦下载。 */
        fun read(context: Context): NetworkStatus = runCatching {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val network = connectivity.activeNetwork ?: return@runCatching NetworkStatus(connected = false)
            val capabilities = connectivity.getNetworkCapabilities(network)
                ?: return@runCatching NetworkStatus(connected = true)
            NetworkStatus(
                connected = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                transports = listOfNotNull(
                    "Wi-Fi".takeIf { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) },
                    "移动数据".takeIf { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) },
                    "以太网".takeIf { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) },
                    "VPN".takeIf { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
                ),
                validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            )
        }.getOrDefault(NetworkStatus(connected = true))
    }
}
