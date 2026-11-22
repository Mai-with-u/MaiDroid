package org.maiwithu.maidroid.ui.extension

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maiwithu.maidroid.permission.PermissionActions
import org.maiwithu.maidroid.permission.PermissionState
import org.maiwithu.maidroid.ui.component.CollapsingGlassHeaderContainer
import org.maiwithu.maidroid.ui.component.LocalBottomNavigationClearance
import org.maiwithu.maidroid.ui.component.computeLazyHeaderCollapseProgress
import org.maiwithu.maidroid.ui.component.overscrollBody
import org.maiwithu.maidroid.ui.component.rememberSplitHeaderOverscrollEffect
import org.maiwithu.maidroid.ui.component.statusBarTopInset
import org.maiwithu.maidroid.ui.theme.CardSurface
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.PageBackground
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes

/**
 * 扩展组件页（阶段一四栏导航骨架，将在后续阶段接入本机悬浮桌宠与扩展组件目录）。
 */
@Composable
internal fun ExtensionsPage(
    webUiOnline: Boolean,
    permissionState: PermissionState,
    permissionActions: PermissionActions,
    modifier: Modifier = Modifier
) {
    val statusBarTop = statusBarTopInset()
    val overscroll = rememberSplitHeaderOverscrollEffect()
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val collapseDistancePx = with(density) { 52.dp.toPx() }
    val collapseProgress = computeLazyHeaderCollapseProgress(
        firstVisibleItemIndex = listState.firstVisibleItemIndex,
        firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
        collapseDistancePx = collapseDistancePx
    )

    CollapsingGlassHeaderContainer(
        title = "扩展组件",
        collapseProgress = collapseProgress,
        statusBarTop = statusBarTop,
        horizontalPadding = 16.dp,
        expandedTopOffset = 40.dp,
        expandedFontSize = TextSizes.Display,
        collapsedFontSize = 19.sp,
        topBarHeight = 52.dp,
        headerOverscrollPx = overscroll.headerOverscrollPx,
        modifier = modifier
            .fillMaxSize()
            .background(PageBackground)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            overscrollEffect = overscroll,
            contentPadding = PaddingValues(
                top = statusBarTop + 40.dp,
                start = 16.dp,
                end = 16.dp,
                bottom = LocalBottomNavigationClearance.current
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(50.dp))
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .overscrollBody(overscroll)
                        .clip(RoundedCornerShape(Corners.Chip))
                        .background(CardSurface)
                        .padding(16.dp)
                ) {
                    Text(
                        text = "悬浮桌宠",
                        color = TextPrimary,
                        fontSize = TextSizes.Title,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (webUiOnline) {
                            "本机 MaiBot 在线，悬浮桌宠集成就绪后将在此开启。"
                        } else {
                            "正在接入本机悬浮桌宠与麦系扩展组件能力。"
                        },
                        color = TextMuted,
                        fontSize = TextSizes.Body
                    )
                }
            }
        }
    }
}
