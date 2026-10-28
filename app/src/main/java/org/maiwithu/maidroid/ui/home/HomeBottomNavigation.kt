package org.maiwithu.maidroid.ui.home

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eightbitlab.com.blurview.BlurTarget
import org.maiwithu.maidroid.R
import org.maiwithu.maidroid.ui.component.BackdropBlurView
import org.maiwithu.maidroid.ui.theme.GlassStroke
import org.maiwithu.maidroid.ui.theme.GlassSurface

/** 主页底部导航栏的三个标签，顺序即从左到右的顺序（也决定切换动画的方向）。 */
internal enum class HomeTab {
    WebUi,
    Platforms,
    Settings
}

/** 导航栏毛玻璃的模糊半径。 */
private const val NavigationBlurRadius = 22f

/** 悬浮导航栏本体高度（标签栏与终端按钮同高）。 */
private val HomeBottomNavigationBarHeight = 64.dp

/** 悬浮导航栏离容器底边的留白。 */
private val HomeBottomNavigationBottomPadding = 16.dp

/**
 * 悬浮导航栏从容器底边往上盖住的总高度。
 * WebUI 页签里导航栏浮在 WebView 上方，WebView 据此把这段高度当作网页的底部安全区，
 * 让网页自己的贴底元素整体上移到导航栏之上。
 */
internal val HomeBottomNavigationOverlayHeight =
    HomeBottomNavigationBarHeight + HomeBottomNavigationBottomPadding

/** 悬浮在页面底部的毛玻璃导航：左边三个标签（选中块带弹性滑动），右边圆形终端按钮。 */
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

            Box(
                modifier = Modifier
                    .size(fabSize)
                    .shadow(
                        elevation = 18.dp,
                        shape = CircleShape,
                        clip = false,
                        ambientColor = Color.Black.copy(alpha = 0.35f),
                        spotColor = Color.Black.copy(alpha = 0.35f)
                    )
                    .clip(CircleShape)
                    .border(1.dp, GlassStroke, CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onTerminalClick
                    ),
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
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

@Composable
private fun AnimatedHomeTabBar(
    selectedTab: HomeTab,
    blurTarget: BlurTarget?,
    onTabSelected: (HomeTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(32.dp)

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
        val tabCount = HomeTab.values().size
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
                .height(56.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(Color(0x54717171))
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            HomeTabButton(
                label = "WebUI",
                selected = selectedTab == HomeTab.WebUi,
                onClick = { onTabSelected(HomeTab.WebUi) }
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_maibot),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
            }

            HomeTabButton(
                label = "消息平台",
                selected = selectedTab == HomeTab.Platforms,
                onClick = { onTabSelected(HomeTab.Platforms) }
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_platform_plug),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            HomeTabButton(
                label = "设置",
                selected = selectedTab == HomeTab.Settings,
                onClick = { onTabSelected(HomeTab.Settings) }
            ) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun RowScope.HomeTabButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit
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
        modifier = Modifier
            .weight(1f)
            .height(56.dp)
            .clip(RoundedCornerShape(32.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer { alpha = contentAlpha }
        ) {
            icon()

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = label,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 12.sp,
                maxLines = 1
            )
        }
    }
}
