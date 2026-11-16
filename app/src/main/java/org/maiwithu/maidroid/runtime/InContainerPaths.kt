package org.maiwithu.maidroid.runtime

/** 容器内（proot 根目录下）的固定路径，供各模块的容器脚本引用。宿主侧的对应位置见 RuntimePaths。 */
internal object InContainerPaths {
    /** MaiBot 源码目录，OOBE 把仓库克隆到这里。 */
    const val MAIBOT_DIR = "/opt/MaiBot"

    /** MaiBot 扫描插件的目录，NapCat 适配器和 QQ 官方机器人适配器都装在这里。 */
    const val MAIBOT_PLUGINS_DIR = "$MAIBOT_DIR/plugins"

    /** NapCat 与 SnowLuma 共用的 LinuxQQ 安装目录，两边升级或重装时复用或替换这里。 */
    const val LINUX_QQ_DIR = "/opt/QQ"

    /**
     * /proc/locks 的替身：Android 不让普通应用读真的 /proc/locks，proot 把这个普通文件绑过去（见 ProotCommand）。
     * 平时是空的；SnowLuma 靠这张表认出 QQ 登录了哪个号，它运行期间由宿主侧直接采集同 UID 下 qq 进程的 fdinfo 锁记录原子写入。
     */
    const val PROC_LOCKS_STAND_IN = "/tmp/.maidroid-proc-locks"
}
