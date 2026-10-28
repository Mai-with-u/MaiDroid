package org.maiwithu.maidroid.oobe

import org.maiwithu.maidroid.runtime.RuntimeComponent

/** OOBE 的三步，顺序就是界面上步骤指示器的顺序。 */
internal enum class OobeStep {
    /** 授予存储（必需）和后台保活（可选）权限。 */
    Permissions,

    /** 解包容器资源并装好 git、curl 等系统依赖。 */
    Container,

    /** 克隆 MaiBot、安装 Python 依赖、确认协议、等 WebUI 起来。 */
    Install
}

/** 安装页上的一张任务卡片对应的检查项。前五项属于“配置容器”一步，后四项属于“安装 MaiBot”一步。 */
internal enum class OobeTaskKind {
    DebianRootfs,
    TermuxBootstrap,
    Proot,
    Git,
    CurlCa,
    MaiBotSource,
    PythonDependencies,
    Agreement,
    WebUi
}

internal enum class OobeTaskStatus {
    Waiting,
    Running,
    Done,
    /** 前置条件没满足（文件缺失），还没开始做。 */
    Blocked,
    Failed
}

/** 一张任务卡片：标题、状态，以及运行中或失败时的说明（失败说明会同时显示在页脚）。 */
internal data class OobeTask(
    val kind: OobeTaskKind,
    val title: String,
    val status: OobeTaskStatus,
    val statusText: String = ""
)

/**
 * OOBE“配置容器”和“安装 MaiBot”两步的界面状态，由 OobeSetupManager 维护。
 *
 * canInstall：容器五件套都就绪，可以进入安装步骤（安装失败后也为 true，表示允许重试）；
 * isComplete：MaiBot 装好且 WebUI 已经能访问，可以进入主页。
 */
internal data class OobeSetupState(
    val containerTasks: List<OobeTask>,
    val installTasks: List<OobeTask>,
    val footerText: String,
    val canInstall: Boolean,
    val isComplete: Boolean,
    val commandLogs: List<String> = emptyList()
) {
    companion object {
        /** 第一次检查容器之前的占位状态：三张容器卡片都在等待，安装卡片都在等容器。 */
        fun beforeFirstCheck(): OobeSetupState = OobeSetupState(
            containerTasks = listOf(
                OobeTask(OobeTaskKind.DebianRootfs, "Debian rootfs", OobeTaskStatus.Waiting),
                OobeTask(OobeTaskKind.TermuxBootstrap, "Termux bootstrap", OobeTaskStatus.Waiting),
                OobeTask(OobeTaskKind.Proot, "proot", OobeTaskStatus.Waiting)
            ),
            installTasks = listOf(
                OobeTask(OobeTaskKind.MaiBotSource, "MaiBot 源码", OobeTaskStatus.Waiting),
                OobeTask(OobeTaskKind.PythonDependencies, "Python 依赖", OobeTaskStatus.Waiting),
                OobeTask(OobeTaskKind.Agreement, "协议确认", OobeTaskStatus.Waiting),
                OobeTask(OobeTaskKind.WebUi, "MaiBot WebUI", OobeTaskStatus.Waiting)
            ),
            footerText = "正在检查容器配置...",
            canInstall = false,
            isComplete = false
        )
    }
}

/** 容器检查项与安装页卡片一一对应。 */
internal fun RuntimeComponent.toOobeTaskKind(): OobeTaskKind = when (this) {
    RuntimeComponent.DebianRootfs -> OobeTaskKind.DebianRootfs
    RuntimeComponent.TermuxBootstrap -> OobeTaskKind.TermuxBootstrap
    RuntimeComponent.Proot -> OobeTaskKind.Proot
    RuntimeComponent.Git -> OobeTaskKind.Git
    RuntimeComponent.CurlCa -> OobeTaskKind.CurlCa
    RuntimeComponent.MaiBotSource -> OobeTaskKind.MaiBotSource
    RuntimeComponent.PythonDependencies -> OobeTaskKind.PythonDependencies
}
