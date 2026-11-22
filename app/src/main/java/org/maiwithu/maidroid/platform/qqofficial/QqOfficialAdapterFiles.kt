package org.maiwithu.maidroid.platform.qqofficial

import org.maiwithu.maidroid.maibot.MaiBotInstance
import org.maiwithu.maidroid.maibot.MaiBotInstances
import org.maiwithu.maidroid.maibot.findPluginDirById
import org.maiwithu.maidroid.runtime.RuntimePaths
import java.io.File

/**
 * QQ 官方机器人适配器插件在宿主机侧的位置。
 * 与 1.4.x 多实例兼容：每次读写动态解析目标实例的 plugins 目录，不在构造阶段把路径写死到根目录。
 */
internal class QqOfficialAdapterFiles(private val paths: RuntimePaths) {
    /** 解析当前应安装或读取配置的 MaiBot 实例（已有该插件的实例优先）。 */
    fun targetInstance(): MaiBotInstance =
        MaiBotInstances.resolvePluginTarget(paths, QqOfficialAdapterScripts.PLUGIN_ID, QqOfficialAdapterScripts.DIR_NAME)

    /**
     * 和 MaiBot 一样按清单里的插件 ID 找 QQ 官方机器人适配器：用户也可能在 MaiBot WebUI 的插件市场装过
     * （目录名是 Mai-with-u_qq-adapter），只认固定目录名会再装一份，同一插件 ID 出现两份会冲突。
     * 先看 MaiDroid 自己的目录，再扫其他目录；清单和入口都在才算装好，克隆了一半的临时目录以点开头会被跳过。
     */
    fun findInstalledDir(instance: MaiBotInstance = targetInstance()): File? =
        findPluginDirById(instance.pluginsDir, QqOfficialAdapterScripts.PLUGIN_ID, QqOfficialAdapterScripts.DIR_NAME)

    fun isInstalled(instance: MaiBotInstance = targetInstance()): Boolean = findInstalledDir(instance) != null

    /** 已安装就用找到的那份目录里的配置，还没装时指向 MaiDroid 将要安装的位置。 */
    fun configFile(instance: MaiBotInstance = targetInstance()): File =
        File(findInstalledDir(instance) ?: File(instance.pluginsDir, QqOfficialAdapterScripts.DIR_NAME), "config.toml")
}
