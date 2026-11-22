package org.maiwithu.maidroid.platform.qqofficial

import org.maiwithu.maidroid.maibot.MaiBotInstance
import org.maiwithu.maidroid.maibot.MaiBotInstances
import org.maiwithu.maidroid.maibot.findPluginDirById
import org.maiwithu.maidroid.maibot.resolvePluginTargetInstance
import org.maiwithu.maidroid.runtime.InContainerPaths
import org.maiwithu.maidroid.runtime.RuntimePaths
import java.io.File

private const val BUILT_IN_ADAPTER_DIR_RELATIVE_PATH = "src/plugins/built_in/qq_official_adapter"
private const val BUILT_IN_MANIFEST_RELATIVE_PATH = "$BUILT_IN_ADAPTER_DIR_RELATIVE_PATH/_manifest.json"
private const val SETTINGS_FILE_NAME = "settings.py"

/** 检测当前 MaiBot 源码是否已内置 qq_official_adapter（不再走外部 plugins/ 克隆）。 */
internal fun isQqOfficialBuiltIn(maiBotDir: File): Boolean =
    File(maiBotDir, BUILT_IN_MANIFEST_RELATIVE_PATH).isFile

/** 返回内置 QQ 官方机器人适配器在目标实例下的 config.toml 路径（config/builtin_plugins/qq_official_adapter/config.toml）。 */
internal fun builtInQqOfficialConfigFile(instance: MaiBotInstance): File =
    File(instance.configDir, "builtin_plugins/qq_official_adapter/config.toml")

/** 从指定适配器目录下的 `settings.py` 动态读取 `config_version` 默认值；文件缺失或未匹配时回退 [fallback]。 */
internal fun readQqOfficialDefaultConfigVersion(adapterDir: File?, fallback: String): String {
    val settingsFile = adapterDir?.let { File(it, SETTINGS_FILE_NAME) }
    val settingsText = settingsFile?.takeIf { it.isFile }?.let { file ->
        runCatching { file.readText() }.getOrNull()
    }
    return QqOfficialAdapterConfig.parseDefaultConfigVersion(settingsText) ?: fallback
}

/**
 * 判断内置 QQ 官方机器人适配器是否已在目标实例下被启用或配置：
 * MaiBot 1.4.x 首次启动会生成 enabled = false 且凭据为空的默认配置，这不代表用户已接入该平台；
 * 仅当开关已打开或填写过 AppID / AppSecret 时才视为“已安装/已配置”，避免“已连接”里误显示空卡片。
 */
internal fun isBuiltInQqOfficialActivated(instance: MaiBotInstance): Boolean {
    val file = builtInQqOfficialConfigFile(instance)
    if (!file.isFile) return false
    val creds = runCatching { QqOfficialAdapterConfig.read(file.readText()) }.getOrNull() ?: return false
    return creds.enabled || creds.appId.isNotBlank() || creds.appSecret.isNotBlank()
}

/**
 * 内置模式下解析应读写配置的 MaiBot 实例：
 * 若某个实例已经开启或配置过内置 QQ 官方机器人（例如用户在多实例里单独配过非默认 bot），优先命中该实例；
 * 否则复用通用实例选择顺序（正在运行且已初始化的默认实例优先）。
 */
internal fun resolveBuiltInQqOfficialTarget(
    instances: List<MaiBotInstance>,
    fallback: MaiBotInstance
): MaiBotInstance =
    instances.firstOrNull(::isBuiltInQqOfficialActivated)
        ?: resolvePluginTargetInstance(
            instances,
            QqOfficialAdapterScripts.PLUGIN_ID,
            QqOfficialAdapterScripts.DIR_NAME,
            fallback
        )

/**
 * QQ 官方机器人适配器插件在宿主机侧的位置。
 * 与 1.4.x 多实例及内置适配兼容：按 MaiBot 版本自动切换内置配置路径或外置 plugins 目录，不在构造阶段写死路径。
 */
internal class QqOfficialAdapterFiles(private val paths: RuntimePaths) {
    /** 当前容器里的 MaiBot 是否已内置 QQ 官方机器人适配器。 */
    fun isBuiltIn(): Boolean = isQqOfficialBuiltIn(paths.maiBotDir)

    /** 解析当前应安装或读取配置的 MaiBot 实例（内置模式优先命中已配置内置插件的实例，外置模式优先命中已有插件的实例）。 */
    fun targetInstance(): MaiBotInstance {
        if (isBuiltIn()) {
            return resolveBuiltInQqOfficialTarget(
                MaiBotInstances.discover(paths),
                MaiBotInstance(paths.maiBotDir, InContainerPaths.MAIBOT_DIR)
            )
        }
        return MaiBotInstances.resolvePluginTarget(paths, QqOfficialAdapterScripts.PLUGIN_ID, QqOfficialAdapterScripts.DIR_NAME)
    }

    /** 读取当前适配器的默认 config_version：优先从插件 settings.py 动态提取，读不到时回退兜底值。 */
    fun defaultConfigVersion(instance: MaiBotInstance = targetInstance()): String =
        if (isBuiltIn()) {
            readQqOfficialDefaultConfigVersion(
                File(paths.maiBotDir, BUILT_IN_ADAPTER_DIR_RELATIVE_PATH),
                QqOfficialAdapterConfig.FallbackBuiltInConfigVersion
            )
        } else {
            readQqOfficialDefaultConfigVersion(
                findInstalledDir(instance),
                QqOfficialAdapterConfig.FallbackLegacyConfigVersion
            )
        }

    /**
     * 和 MaiBot 一样按清单里的插件 ID 找外置 QQ 官方机器人适配器：用户也可能在 MaiBot WebUI 的插件市场装过
     * （目录名是 Mai-with-u_qq-adapter），只认固定目录名会再装一份，同一插件 ID 出现两份会冲突。
     * 先看 MaiDroid 自己的目录，再扫其他目录；清单和入口都在才算装好，克隆了一半的临时目录以点开头会被跳过。
     */
    fun findInstalledDir(instance: MaiBotInstance = targetInstance()): File? =
        findPluginDirById(instance.pluginsDir, QqOfficialAdapterScripts.PLUGIN_ID, QqOfficialAdapterScripts.DIR_NAME)

    /** 内置版按“是否已启用或填写过凭据”判断是否已在平台页激活展示；外置版按插件目录是否就绪判断。 */
    fun isInstalled(instance: MaiBotInstance = targetInstance()): Boolean =
        if (isBuiltIn()) isBuiltInQqOfficialActivated(instance) else findInstalledDir(instance) != null

    /** 内置版固定读写 config/builtin_plugins/qq_official_adapter/config.toml；外置版读写插件目录里的 config.toml。 */
    fun configFile(instance: MaiBotInstance = targetInstance()): File =
        if (isBuiltIn()) {
            builtInQqOfficialConfigFile(instance)
        } else {
            File(findInstalledDir(instance) ?: File(instance.pluginsDir, QqOfficialAdapterScripts.DIR_NAME), "config.toml")
        }
}
