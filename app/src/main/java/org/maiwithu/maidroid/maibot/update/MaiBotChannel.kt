package org.maiwithu.maidroid.maibot.update

/**
 * MaiBot 的版本通道：正式版跟随上游 main，测试版跟随上游 dev（MaiBot 自带的自更新也只认这两个分支）。
 * 当前在哪个通道不另存设置，以容器里 MaiBot 仓库当前所在的分支为准：
 * 重新安装、切换到一半失败之后，界面显示的都是仓库真实所在的分支。
 */
internal enum class MaiBotChannel(val branch: String, val label: String) {
    Stable("main", "正式版"),
    Testing("dev", "测试版");

    companion object {
        /** 分支对应的通道；手动切到其它分支（例如经容器 SSH）时返回 null。 */
        fun ofBranch(branch: String?): MaiBotChannel? = entries.firstOrNull { it.branch == branch }
    }
}

/** 界面上显示的分支名：main、dev 带上通道名，其它分支原样显示。 */
internal fun maiBotBranchLabel(branch: String): String =
    MaiBotChannel.ofBranch(branch)?.let { "${it.label}（$branch）" } ?: branch
