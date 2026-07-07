package org.maiwithu.maidroid.ui.screen

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.maiwithu.maidroid.R
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView

@Composable
internal fun MainBottomNavigation(
    selectedTab: MainTab,
    blurTarget: BlurTarget?,
    onTabSelected: (MainTab) -> Unit,
    onTerminalClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
    ) {
        val horizontalPadding = 28.dp
        val availableWidth = maxWidth - horizontalPadding * 2
        val fabSize = 64.dp
        val tabBarWidth = minOf(249.dp, availableWidth - fabSize - 16.dp).coerceAtLeast(216.dp)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            AnimatedMainTabBar(
                selectedTab = selectedTab,
                blurTarget = blurTarget,
                onTabSelected = onTabSelected,
                modifier = Modifier
                    .width(tabBarWidth)
                    .height(64.dp)
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
                BackdropBlurLayer(
                    blurTarget = blurTarget,
                    cornerRadius = 32.dp,
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
private fun BackdropBlurLayer(
    cornerRadius: Dp,
    blurTarget: BlurTarget?,
    modifier: Modifier = Modifier
) {
    val overlayColor = GlassSurface.toArgb()

    AndroidView(
        factory = { context ->
            BlurView(context)
        },
        update = { view ->
            if (blurTarget != null && view.tag !== blurTarget) {
                view.tag = blurTarget
                view.setupWith(blurTarget, 5f, false)
                    .setBlurRadius(22f)
                    .setOverlayColor(overlayColor)
                    .setBlurAutoUpdate(true)
            } else if (blurTarget != null) {
                view.setBlurEnabled(true)
                view.setBlurAutoUpdate(true)
                view.setBlurRadius(22f)
                view.setOverlayColor(overlayColor)
            } else {
                view.setBlurEnabled(false)
            }
        },
        modifier = modifier
    )
}

@Composable
private fun AnimatedMainTabBar(
    selectedTab: MainTab,
    blurTarget: BlurTarget?,
    onTabSelected: (MainTab) -> Unit,
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
        val tabCount = MainTab.values().size
        val tabWidth = (maxWidth - 8.dp) / tabCount
        val indicatorOffset by animateDpAsState(
            targetValue = 4.dp + tabWidth * selectedTab.ordinal.toFloat(),
            animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
                label = "MainTabIndicatorOffset"
        )

        BackdropBlurLayer(
            blurTarget = blurTarget,
            cornerRadius = 32.dp,
            modifier = Modifier.matchParentSize()
        )

        Box(
            modifier = Modifier
                .offset(x = indicatorOffset, y = 4.dp)
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
            MainTabButton(
                label = "WebUI",
                selected = selectedTab == MainTab.WebUi,
                onClick = { onTabSelected(MainTab.WebUi) }
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_maibot),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
            }

            MainTabButton(
                label = "消息平台",
                selected = selectedTab == MainTab.Platforms,
                onClick = { onTabSelected(MainTab.Platforms) }
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_platform_plug),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            MainTabButton(
                label = "设置",
                selected = selectedTab == MainTab.Settings,
                onClick = { onTabSelected(MainTab.Settings) }
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
private fun RowScope.MainTabButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit
) {
    val contentAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0.76f,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "MainTabContentAlpha"
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
