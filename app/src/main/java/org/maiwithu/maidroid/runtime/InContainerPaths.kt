package org.maiwithu.maidroid.runtime

/** 容器内（proot 根目录下）的固定路径，供各模块的容器脚本引用。宿主侧的对应位置见 RuntimePaths。 */
internal object InContainerPaths {
    /** MaiBot 源码目录，OOBE 把仓库克隆到这里。 */
    const val MAIBOT_DIR = "/opt/MaiBot"

    /** MaiBot 扫描插件的目录，NapCat 适配器和 QQ 官方机器人适配器都装在这里。 */
    const val MAIBOT_PLUGINS_DIR = "$MAIBOT_DIR/plugins"

    /**
     * /proc/locks 的替身：Android 不让普通应用读真的 /proc/locks，proot 把这个普通文件绑过去（见 ProotCommand）。
     * 平时是空的；SnowLuma 靠这张表认出 QQ 登录了哪个号，它运行期间由启动脚本按 QQ 进程 fdinfo 里的锁信息不断重写。
     */
    const val PROC_LOCKS_STAND_IN = "/tmp/.maidroid-proc-locks"
}
