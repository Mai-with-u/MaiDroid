package org.maiwithu.maidroid.webui

/** MaiBot 自带的 WebUI。容器与宿主共用网络，宿主直接访问回环地址即可。 */
internal object MaiBotWebUi {
    const val URL = "http://127.0.0.1:8001/"

    /** WebView 加载、console 输出、视口修补结果都打到这个 logcat 标签。 */
    const val LOG_TAG = "MaiDroidWebUi"
}
