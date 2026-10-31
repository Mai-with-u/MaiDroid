package org.maiwithu.maidroid.ui.theme

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 全应用的字号和圆角阶梯。页面里只从这里取值，不再随手写 sp、dp：
// 同一种角色的文字、同一类容器在各页面看起来一样，要整体调大调小也只改这一处。
// 启动页的插画按设计稿等比缩放，唤醒页按等宽终端排版，都有自己的尺寸，不走这里。

/** 字号阶梯，按文字在界面里的角色取用。 */
object TextSizes {
    /** 角标、标签（“必须”“最佳适配”“国内”）。 */
    val Tag = 10.sp

    /** 辅助说明、次要信息、卡片里的描述。 */
    val Caption = 12.sp

    /** 正文、弹窗说明、按钮旁的小字。 */
    val Body = 14.sp

    /** 强调正文、设置项标题、列表行标题。 */
    val BodyLarge = 16.sp

    /** 卡片标题。 */
    val Title = 18.sp

    /** 弹窗标题、主要按钮文字。 */
    val TitleLarge = 20.sp

    /** 页面里的区块标题（如“发现”）。 */
    val Headline = 24.sp

    /** 页面大标题（如“消息平台”“权限管理”）。 */
    val Display = 32.sp
}

/** 圆角阶梯，按容器的类型取用。 */
object Corners {
    /** 角标、小标签。 */
    val Tag = 6.dp

    /** 按钮、输入框、图标底块。 */
    val Control = 12.dp

    /** 卡片里嵌套的面板（终端输出区、平台图标）。 */
    val Panel = 16.dp

    /** 分类标签、状态横幅、接入方式选择器。 */
    val Chip = 20.dp

    /** 卡片、弹窗。 */
    val Card = 24.dp

    /** 底部导航这类胶囊形的条。 */
    val Pill = 32.dp
}
