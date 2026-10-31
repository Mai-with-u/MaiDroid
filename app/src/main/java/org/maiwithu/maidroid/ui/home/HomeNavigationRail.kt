package org.maiwithu.maidroid.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.GlassSelectedIndicator
import org.maiwithu.maidroid.ui.theme.GlassStroke
import org.maiwithu.maidroid.ui.theme.GlassSurface
import org.maiwithu.maidroid.ui.theme.TextPrimary

/** 侧栏里标签竖条的宽度（容得下“消息平台”四个字）。 */
private val HomeNavigationRailBarWidth = 72.dp

/** 侧栏左右、上下的留白。 */
private val HomeNavigationRailPadding = 12.dp

/**
 * 主页导航放在哪：窗口宽大于高（手机横屏、平板横屏、横向分屏）时竖着放在左侧，否则悬浮在底部。
 * 手机是细长的，横过来高度只剩 360dp 左右，底栏和页面抢高度；放到左侧只占一窄列，页面能用满整块高度。
 */
internal fun homeUsesNavigationRail(widthDp: Float, heightDp: Float): Boolean = widthDp > heightDp

/**
 * 横屏时主页左侧的竖向导航：上面是三个标签的毛玻璃竖条（选中块上下弹性滑动），
 * 下面是刷新 WebUI（只在 WebUI 页签显示）和终端按钮。
 * 侧栏独占一列、不盖在页面上，所以不做实时模糊，只铺毛玻璃的底色。
 */
@Composable
internal fun HomeNavigationRail(
    selectedTab: HomeTab,
    onTabSelected: (HomeTab) -> Unit,
    onTerminalClick: () -> Unit,
    onRefreshWebUi: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .padding(HomeNavigationRailPadding)
            .width(HomeNavigationRailBarWidth),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AnimatedHomeTabRail(
            selectedTab = selectedTab,
            onTabSelected = onTabSelected,
            modifier = Modifier
                .fillMaxWidth()
                .height(HomeTabButtonHeight * HomeTab.entries.size + 8.dp)
        )

        Spacer(modifier = Modifier.weight(1f))

        // 刷新按钮跟着 WebUI 页签出现；竖屏时它悬浮在网页右上角，横屏网页是桌面版，右上角有网页自己的按钮，挪到这里不挡。
        AnimatedVisibility(
            visible = onRefreshWebUi != null,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut()
        ) {
            RailRefreshButton(
                onClick = { onRefreshWebUi?.invoke() },
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }

        HomeTerminalButton(
            blurTarget = null,
            onClick = onTerminalClick,
            modifier = Modifier.size(HomeTabButtonHeight)
        )
    }
}

@Composable
private fun AnimatedHomeTabRail(
    selectedTab: HomeTab,
    onTabSelected: (HomeTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(Corners.Pill)

    BoxWithConstraints(
        modifier = modifier
            .shadow(
                elevation = 18.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.32f),
                spotColor = Color.Black.copy(alpha = 0.32f)
            )
            .clip(shape)
            .border(1.dp, GlassStroke, shape)
            .background(GlassSurface)
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
                .clip(RoundedCornerShape(Corners.Pill))
                .background(GlassSelectedIndicator)
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
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .border(1.dp, GlassStroke, CircleShape)
            .background(GlassSurface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.Refresh,
            contentDescription = "刷新 WebUI",
            tint = TextPrimary,
            modifier = Modifier.size(22.dp)
        )
    }
}
