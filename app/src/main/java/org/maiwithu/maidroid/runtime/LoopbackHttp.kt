package org.maiwithu.maidroid.runtime

import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

/**
 * 容器内长驻服务均监听在宿主机回环网卡上，这里集中判断回环地址并强制走直连（[Proxy.NO_PROXY]），
 * 避免用户开启系统 Wi-Fi / VPN HTTP 代理时，WebUI 页面与各协议端管理接口请求被代理劫持。
 */

/** 判断目标主机是否指向本机回环地址，供本机服务直连绕过系统代理使用。 */
internal fun isLoopbackHost(host: String?): Boolean {
    val normalized = host?.trim()?.lowercase() ?: return false
    return normalized == "127.0.0.1" ||
        normalized == "localhost" ||
        normalized == "::1" ||
        normalized == "[::1]"
}

/**
 * 打开 HTTP 连接：命中回环地址时跳过系统代理直连本机，避免代理劫持容器内的 WebUI 或协议端接口；
 * 其余外网地址沿用系统默认连接策略。
 */
internal fun openDirectOrDefault(url: URL): HttpURLConnection {
    val connection = if (isLoopbackHost(url.host)) {
        url.openConnection(Proxy.NO_PROXY)
    } else {
        url.openConnection()
    }
    return connection as HttpURLConnection
}
