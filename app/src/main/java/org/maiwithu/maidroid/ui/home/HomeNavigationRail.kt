package org.maiwithu.maidroid.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import org.maiwithu.maidroid.ui.component.liquidGlassIndicator
import org.maiwithu.maidroid.ui.component.liquidGlassSurface
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.TextPrimary

/** 侧栏里标签竖条的宽度（容得下“消息平台”“扩展组件”四个字）。 */
private val HomeNavigationRailBarWidth = 72.dp

/** 正常高度下的侧栏留白，用于计算页面正文左偏移与窄矮屏统一基准。 */
private val HomeNavigationRailPadding = 12.dp

/** 侧栏连同两侧留白的总宽度：页面内容左边让出这么多，静止时不被侧栏盖住。 */
internal val HomeNavigationRailWidth = HomeNavigationRailBarWidth + HomeNavigationRailPadding * 2

/** 矮屏（例如手机横屏约 360dp 高）下切换紧凑尺寸的高度分界线。 */
private const val CompactRailMaxHeightDp = 420f

/**
 * 横屏侧栏在不同可用高度下的几何尺寸（dp）：
 * 4 个页签 + 刷新按钮 + 终端按钮总高度若按常规 56dp 堆叠会超出手机横屏 360dp 高度，
 * 故矮屏自动切为紧凑尺寸确保零裁剪、零重叠。
 */
internal data class HomeRailMetrics(
    val compact: Boolean,
    val railPaddingDp: Float,
    val tabHeightDp: Float,
    val refreshButtonSizeDp: Float,
    val refreshBottomGapDp: Float,
    val terminalButtonSizeDp: Float
) {
    val tabRailHeightDp: Float
        get() = tabHeightDp * HomeTab.entries.size + 8f

    val minimumTotalHeightDp: Float
        get() = railPaddingDp * 2f + tabRailHeightDp + refreshButtonSizeDp + refreshBottomGapDp + terminalButtonSizeDp
}

/** 按当前容器高度计算横屏侧栏各元素尺寸。 */
internal fun computeHomeRailMetrics(maxHeightDp: Float): HomeRailMetrics =
    if (maxHeightDp < CompactRailMaxHeightDp) {
        HomeRailMetrics(
            compact = true,
            railPaddingDp = 8f,
            tabHeightDp = 46f,
            refreshButtonSizeDp = 38f,
            refreshBottomGapDp = 8f,
            terminalButtonSizeDp = 46f
        )
    } else {
        HomeRailMetrics(
            compact = false,
            railPaddingDp = HomeNavigationRailPadding.value,
            tabHeightDp = HomeTabButtonHeightDp,
            refreshButtonSizeDp = 44f,
            refreshBottomGapDp = 12f,
            terminalButtonSizeDp = HomeTabButtonHeightDp
        )
    }

/**
 * 主页导航放在哪：窗口宽大于高（手机横屏、平板横屏、横向分屏）时竖着放在左侧，否则悬浮在底部。
 * 手机是细长的，横过来高度只剩 360dp 左右，底栏和页面抢高度；放到左侧只占一窄列，页面能用满整块高度。
 */
internal fun homeUsesNavigationRail(widthDp: Float, heightDp: Float): Boolean = widthDp > heightDp

/**
 * 横屏时主页左侧的竖向液态玻璃导航：上面是四个标签的液态玻璃竖条（选中块上下弹性滑动），
 * 下面是刷新 WebUI（只在 WebUI 页签显示）和终端按钮。
 */
@Composable
internal fun HomeNavigationRail(
    selectedTab: HomeTab,
    backdrop: Backdrop,
    onTabSelected: (HomeTab) -> Unit,
    onTerminalClick: () -> Unit,
    onRefreshWebUi: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxHeight()) {
        val metrics = computeHomeRailMetrics(maxHeight.value)

        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = HomeNavigationRailPadding, vertical = metrics.railPaddingDp.dp)
                .width(HomeNavigationRailBarWidth),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AnimatedHomeTabRail(
                selectedTab = selectedTab,
                backdrop = backdrop,
                compact = metrics.compact,
                onTabSelected = onTabSelected,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(metrics.tabRailHeightDp.dp)
            )

            Spacer(modifier = Modifier.weight(1f))

            // 刷新按钮仅在 WebUI 页签显示：横屏网页为桌面版，左侧栏留这一枚圆形快捷刷新钮不挡网页右上角控件。
            AnimatedVisibility(
                visible = onRefreshWebUi != null,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut()
            ) {
                RailRefreshButton(
                    backdrop = backdrop,
                    sizeDp = metrics.refreshButtonSizeDp,
                    onClick = { onRefreshWebUi?.invoke() },
                    modifier = Modifier.padding(bottom = metrics.refreshBottomGapDp.dp)
                )
            }

            HomeTerminalButton(
                backdrop = backdrop,
                onClick = onTerminalClick,
                iconSizeDp = if (metrics.compact) 26f else 32f,
                modifier = Modifier.size(metrics.terminalButtonSizeDp.dp)
            )
        }
    }
}

@Composable
private fun AnimatedHomeTabRail(
    selectedTab: HomeTab,
    backdrop: Backdrop,
    compact: Boolean,
    onTabSelected: (HomeTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(Corners.Pill)

    BoxWithConstraints(
        modifier = modifier.liquidGlassSurface(
            backdrop = backdrop,
            shape = { shape }
        )
    ) {
        val tabHeight = (maxHeight - 8.dp) / HomeTab.entries.size
        val indicatorOffset by animateDpAsState(
            targetValue = 4.dp + tabHeight * selectedTab.ordinal.toFloat(),
            animationSpec = spring(
                dampingRatio = 0.78f,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "HomeRailIndicatorOffset"
        )

        Box(
            modifier = Modifier
                .offset { IntOffset(4.dp.roundToPx(), indicatorOffset.roundToPx()) }
                .width(maxWidth - 8.dp)
                .height(tabHeight)
                .liquidGlassIndicator(
                    backdrop = backdrop,
                    shape = { shape }
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp)
        ) {
            HomeTab.entries.forEach { tab ->
                HomeTabButton(
                    tab = tab,
                    selected = selectedTab == tab,
                    compact = compact,
                    onClick = { onTabSelected(tab) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }
        }
    }
}

@Composable
private fun RailRefreshButton(
    backdrop: Backdrop,
    sizeDp: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(sizeDp.dp)
            .liquidGlassSurface(
                backdrop = backdrop,
                shape = { CircleShape }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.Refresh,
            contentDescription = "刷新 WebUI",
            tint = TextPrimary,
            modifier = Modifier.size(if (sizeDp < 42f) 20.dp else 22.dp)
        )
    }
}
