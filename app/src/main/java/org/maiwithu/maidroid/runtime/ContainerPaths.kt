package org.maiwithu.maidroid.runtime

/** 容器内（proot 根目录下）的固定路径，供各模块的容器脚本引用。宿主侧的对应位置见 RuntimePaths。 */
internal object ContainerPaths {
    /** MaiBot 源码目录，OOBE 把仓库克隆到这里。 */
    const val MAIBOT_DIR = "/opt/MaiBot"

    /** MaiBot 扫描插件的目录，NapCat 适配器和 QQ 官方机器人适配器都装在这里。 */
    const val MAIBOT_PLUGINS_DIR = "$MAIBOT_DIR/plugins"
}
