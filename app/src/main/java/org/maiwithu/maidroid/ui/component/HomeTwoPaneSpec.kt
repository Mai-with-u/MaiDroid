package org.maiwithu.maidroid.ui.component

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 首页原生页签（消息平台、扩展组件、设置）共享的横屏与宽屏左右分栏设计标准：
 * - 统一采用 38% : 62% 的固定比例分割左右两栏，切换页签时视觉中轴线保持稳定；
 * - 统一外边距（16dp）、栏间中缝（16dp，左右栏内侧各让出 8dp）、响应式断点与顶部标题基线，
 *   消除横屏下各页左右宽窄跳变与标题高低错位。
 */
internal object HomeTwoPaneSpec {
    /** 左栏固定占比（概览 / 导航 / 目录）。 */
    const val LeftPaneWeight = 0.38f

    /** 右栏固定占比（主列表 / 主控区 / 详情）。 */
    const val RightPaneWeight = 0.62f

    /** 手机横屏等宽矮窗口启用独立双栏滚动的最大高度阈值（dp）。 */
    const val CompactTwoPaneMaxHeightDp = 560f

    /** 横屏分栏能正常排下双栏内容与头栏按钮的最小可用宽度（dp）。 */
    const val CompactTwoPaneMinWidthDp = 520f

    /** 非宽矮窗口退回单列布局的最大宽度阈值（dp）。 */
    const val SingleColumnMaxWidthDp = 600f

    /** 手机横屏与单列列表统一的外侧水平边距。 */
    val OuterHorizontalPadding: Dp = 16.dp

    /** 手机横屏无竖线卡片型双栏之间的净中缝宽度。 */
    val PaneGap: Dp = 16.dp

    /** 无竖线卡片型双栏靠近中缝一侧各自的半缝内边距（合计 PaneGap）。 */
    val PaneHalfGap: Dp = PaneGap / 2

    /** 主从导航与详情页（如设置页）之间的细竖线分隔宽度，凸显主从层级。 */
    val MasterDetailDividerThickness: Dp = 0.5.dp

    /** 平板等宽大屏的外侧水平边距。 */
    val WideOuterHorizontalPadding: Dp = 24.dp

    /** 平板等宽大屏左右两栏之间的中缝间距。 */
    val WidePaneGap: Dp = 20.dp

    /** 按可用总宽度（扣除导航竖栏与挖孔安全区后）换算左栏理论宽度（dp）。 */
    fun leftPaneWidthDp(availableWidthDp: Float): Float =
        availableWidthDp.coerceAtLeast(0f) * LeftPaneWeight

    /**
     * 按当前容器高度计算顶部大标题展开时的顶边距：
     * 矮横屏（<= 480dp）稳定在 8dp 与左侧导航条顶端齐平；高度拉高至 720dp 以上时平滑展开到 40dp。
     */
    fun headerTopSpacing(maxHeightDp: Float): Dp {
        val expansion = ((maxHeightDp - 480f) / (720f - 480f)).coerceIn(0f, 1f)
        return 8.dp + 32.dp * expansion
    }
}
