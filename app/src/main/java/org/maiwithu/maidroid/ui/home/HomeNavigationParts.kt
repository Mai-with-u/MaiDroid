package org.maiwithu.maidroid.ui.home

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import org.maiwithu.maidroid.R
import org.maiwithu.maidroid.ui.component.liquidGlassSurface
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes

/** 主页导航的四个标签，顺序即底栏从左到右、横屏侧栏从上到下的顺序（也决定切换动画的方向）。 */
internal enum class HomeTab(val label: String) {
    WebUi("WebUI"),
    Platforms("消息平台"),
    Extensions("扩展组件"),
    Settings("设置")
}

/** 竖屏底部导航栏与独立圆形终端按钮的统一高度。 */
internal val PortraitBottomBarHeight = 64.dp

/** 竖屏底部导航栏离容器底边的留白。 */
internal val PortraitBottomBarBottomPadding = 16.dp

/** 竖屏底部导航栏与容器左右边缘的留白（收紧边距，为四栏标签让出横向空间）。 */
internal val PortraitBottomBarHorizontalPadding = 16.dp

/** 竖屏底部导航胶囊与右下角圆形终端按钮之间的安全间距。 */
internal val PortraitBottomBarGap = 12.dp

/** 单个标签按钮高度（dp 数值常量，同时供竖屏底栏与正常高度横屏侧栏计算复用）。 */
internal const val HomeTabButtonHeightDp = 56f

/** 单个标签按钮的高度（竖屏底栏与正常高度横屏侧栏通用）。 */
internal val HomeTabButtonHeight = HomeTabButtonHeightDp.dp

/** 计算竖屏底部四栏标签胶囊的目标宽度（dp），为右下角圆形终端按钮与左右留白让出间距。 */
internal fun computeBottomTabBarWidthDp(maxWidthDp: Float): Float {
    val availableWidthDp = maxWidthDp - PortraitBottomBarHorizontalPadding.value * 2f
    val terminalButtonSizeDp = PortraitBottomBarHeight.value
    return minOf(308f, availableWidthDp - terminalButtonSizeDp - PortraitBottomBarGap.value)
        .coerceAtLeast(244f)
}

/** 圆形液态玻璃终端按钮，点开终端输出；竖屏底栏右端与横屏侧栏底部共用，尺寸由调用方给。 */
@Composable
internal fun HomeTerminalButton(
    backdrop: Backdrop,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    iconSizeDp: Float = 32f
) {
    Box(
        modifier = modifier
            .liquidGlassSurface(
                backdrop = backdrop,
                shape = { CircleShape }
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_terminal_code),
            contentDescription = "查看终端输出",
            tint = TextPrimary,
            modifier = Modifier.size(iconSizeDp.dp)
        )
    }
}

/** 单个标签按钮：图标在上、文字在下，选中时不透明、未选中略淡；大小由调用方的 modifier 决定。 */
@Composable
internal fun HomeTabButton(
    tab: HomeTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false
) {
    val contentAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0.76f,
        animationSpec = spring(
            dampingRatio = 1f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "HomeTabContentAlpha"
    )
    val iconSize = if (compact) 21.dp else 24.dp
    val labelGap = if (compact) 2.dp else 4.dp
    val labelSize = if (compact) 11.sp else TextSizes.Caption

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Corners.Pill))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer { alpha = contentAlpha }
        ) {
            HomeTabIcon(tab = tab, iconSize = iconSize)

            Spacer(modifier = Modifier.height(labelGap))

            Text(
                text = tab.label,
                color = TextPrimary,
                fontSize = labelSize,
                fontWeight = FontWeight.Bold,
                lineHeight = 12.sp,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

/** 标签的图标：WebUI 用麦麦头像（彩色原图），其余三个按文字主色着色。 */
@Composable
private fun HomeTabIcon(
    tab: HomeTab,
    iconSize: Dp
) {
    when (tab) {
        HomeTab.WebUi -> Image(
            painter = painterResource(R.drawable.ic_maibot),
            contentDescription = null,
            modifier = Modifier.size(iconSize)
        )

        HomeTab.Platforms -> Icon(
            painter = painterResource(R.drawable.ic_platform_plug),
            contentDescription = null,
            tint = TextPrimary,
            modifier = Modifier.size(iconSize)
        )

        HomeTab.Extensions -> Icon(
            imageVector = Icons.Outlined.Extension,
            contentDescription = null,
            tint = TextPrimary,
            modifier = Modifier.size(iconSize)
        )

        HomeTab.Settings -> Icon(
            imageVector = Icons.Outlined.Settings,
            contentDescription = null,
            tint = TextPrimary,
            modifier = Modifier.size(iconSize)
        )
    }
}
