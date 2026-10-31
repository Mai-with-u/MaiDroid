package org.maiwithu.maidroid.ui.home

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eightbitlab.com.blurview.BlurTarget
import org.maiwithu.maidroid.R
import org.maiwithu.maidroid.ui.component.BackdropBlurView
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.GlassSelectedIndicator
import org.maiwithu.maidroid.ui.theme.GlassStroke
import org.maiwithu.maidroid.ui.theme.GlassSurface
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes

/** 主页导航的三个标签，顺序即底栏从左到右、横屏侧栏从上到下的顺序（也决定切换动画的方向）。 */
internal enum class HomeTab(val label: String) {
    WebUi("WebUI"),
    Platforms("消息平台"),
    Settings("设置")
}

/** 导航栏毛玻璃的模糊半径。 */
internal const val NavigationBlurRadius = 22f

/** 悬浮导航栏本体高度（标签栏与终端按钮同高）。 */
private val HomeBottomNavigationBarHeight = 64.dp

/** 悬浮导航栏离容器底边的留白。 */
private val HomeBottomNavigationBottomPadding = 16.dp

/** 单个标签按钮的高度（底栏、侧栏通用）。 */
internal val HomeTabButtonHeight = 56.dp

/**
 * WebUI 页签里收起后的小按钮离底边的距离：网页自己的底部导航贴着屏幕底边，
 * 不同主题下连同底部留白高 64~76dp，按钮放在它上方再空出十几 dp，不挡网页导航的按钮。
 */
internal val CollapsedHomeNavigationBottomPadding = 88.dp

/** 竖屏时悬浮在页面底部的毛玻璃导航：左边三个标签（选中块带弹性滑动），右边圆形终端按钮。 */
@Composable
internal fun HomeBottomNavigation(
    selectedTab: HomeTab,
    blurTarget: BlurTarget?,
    onTabSelected: (HomeTab) -> Unit,
    onTerminalClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = HomeBottomNavigationBottomPadding)
    ) {
        val horizontalPadding = 28.dp
        val availableWidth = maxWidth - horizontalPadding * 2
        val fabSize = HomeBottomNavigationBarHeight
        val tabBarWidth = minOf(249.dp, availableWidth - fabSize - 16.dp).coerceAtLeast(216.dp)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            AnimatedHomeTabBar(
                selectedTab = selectedTab,
                blurTarget = blurTarget,
                onTabSelected = onTabSelected,
                modifier = Modifier
                    .width(tabBarWidth)
                    .height(HomeBottomNavigationBarHeight)
            )

            HomeTerminalButton(
                blurTarget = blurTarget,
                onClick = onTerminalClick,
                modifier = Modifier.size(fabSize)
            )
        }
    }
}

/** 圆形毛玻璃终端按钮，点开终端输出；底栏右端和横屏侧栏底部共用，尺寸由调用方给。 */
@Composable
internal fun HomeTerminalButton(
    blurTarget: BlurTarget?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .shadow(
                elevation = 18.dp,
                shape = CircleShape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.35f),
                spotColor = Color.Black.copy(alpha = 0.35f)
            )
            .clip(CircleShape)
            .border(1.dp, GlassStroke, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        BackdropBlurView(
            blurTarget = blurTarget,
            blurRadius = NavigationBlurRadius,
            overlayColor = GlassSurface,
            modifier = Modifier.matchParentSize()
        )

        Icon(
            painter = painterResource(R.drawable.ic_terminal_code),
            contentDescription = "查看终端输出",
            tint = TextPrimary,
            modifier = Modifier.size(32.dp)
        )
    }
}

/**
 * WebUI 页签里底栏收起后留在右下角的按钮，点开弹出完整底栏。
 * 和终端按钮同样是毛玻璃底，描边换成品牌橙，叠在各种配色的网页上都能一眼找到。
 */
@Composable
internal fun CollapsedHomeNavigationButton(
    blurTarget: BlurTarget?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .shadow(
                elevation = 12.dp,
                shape = CircleShape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.35f),
                spotColor = Color.Black.copy(alpha = 0.35f)
            )
            .clip(CircleShape)
            .border(1.5.dp, BrandOrange.copy(alpha = 0.85f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        BackdropBlurView(
            blurTarget = blurTarget,
            blurRadius = NavigationBlurRadius,
            overlayColor = GlassSurface,
            modifier = Modifier.matchParentSize()
        )

        Icon(
            imageVector = Icons.Outlined.Menu,
            contentDescription = "展开底部导航",
            tint = TextPrimary,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun AnimatedHomeTabBar(
    selectedTab: HomeTab,
    blurTarget: BlurTarget?,
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
    ) {
        val tabCount = HomeTab.entries.size
        val tabWidth = (maxWidth - 8.dp) / tabCount
        val indicatorOffset by animateDpAsState(
            targetValue = 4.dp + tabWidth * selectedTab.ordinal.toFloat(),
            animationSpec = spring(
                dampingRatio = 0.78f,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "HomeTabIndicatorOffset"
        )

        BackdropBlurView(
            blurTarget = blurTarget,
            blurRadius = NavigationBlurRadius,
            overlayColor = GlassSurface,
            modifier = Modifier.matchParentSize()
        )

        Box(
            modifier = Modifier
                .offset { IntOffset(indicatorOffset.roundToPx(), 4.dp.roundToPx()) }
                .width(tabWidth)
                .height(HomeTabButtonHeight)
                .clip(RoundedCornerShape(Corners.Pill))
                .background(GlassSelectedIndicator)
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            HomeTab.entries.forEach { tab ->
                HomeTabButton(
                    tab = tab,
                    selected = selectedTab == tab,
                    onClick = { onTabSelected(tab) },
                    modifier = Modifier
                        .weight(1f)
                        .height(HomeTabButtonHeight)
                )
            }
        }
    }
}

/** 单个标签按钮：图标在上、文字在下，选中时不透明、未选中略淡；大小由调用方的 modifier 决定。 */
@Composable
internal fun HomeTabButton(
    tab: HomeTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val contentAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0.76f,
        animationSpec = spring(
            dampingRatio = 1f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "HomeTabContentAlpha"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Corners.Pill))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer { alpha = contentAlpha }
        ) {
            HomeTabIcon(tab)

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = tab.label,
                color = TextPrimary,
                fontSize = TextSizes.Caption,
                fontWeight = FontWeight.Bold,
                lineHeight = 12.sp,
                maxLines = 1
            )
        }
    }
}

/** 标签的图标：WebUI 用麦麦头像（彩色原图），其余两个按文字主色着色。 */
@Composable
private fun HomeTabIcon(tab: HomeTab) {
    when (tab) {
        HomeTab.WebUi -> Image(
            painter = painterResource(R.drawable.ic_maibot),
            contentDescription = null,
            modifier = Modifier.size(24.dp)
        )

        HomeTab.Platforms -> Icon(
            painter = painterResource(R.drawable.ic_platform_plug),
            contentDescription = null,
            tint = TextPrimary,
            modifier = Modifier.size(24.dp)
        )

        HomeTab.Settings -> Icon(
            imageVector = Icons.Outlined.Settings,
            contentDescription = null,
            tint = TextPrimary,
            modifier = Modifier.size(24.dp)
        )
    }
}
