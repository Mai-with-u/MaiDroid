package org.maiwithu.maidroid.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maiwithu.maidroid.appDependencies
import org.maiwithu.maidroid.runtime.MirrorManager
import org.maiwithu.maidroid.runtime.MirrorSettingsState
import org.maiwithu.maidroid.ui.component.CollapsingGlassHeaderContainer
import org.maiwithu.maidroid.ui.component.FlatFeatureIcons
import org.maiwithu.maidroid.ui.component.GlassHeaderCircleButton
import org.maiwithu.maidroid.ui.component.HomeTwoPaneSpec
import org.maiwithu.maidroid.ui.component.LocalBottomNavigationClearance
import org.maiwithu.maidroid.ui.component.MaiDialog
import org.maiwithu.maidroid.ui.component.MaiDialogButton
import org.maiwithu.maidroid.ui.component.MaiDialogButtonRow
import org.maiwithu.maidroid.ui.component.MaiDialogButtonStyle
import org.maiwithu.maidroid.ui.component.MaiDialogInfoChip
import org.maiwithu.maidroid.ui.component.MaiDialogSize
import org.maiwithu.maidroid.ui.component.appPageBackground
import org.maiwithu.maidroid.ui.component.computeLazyHeaderCollapseProgress
import org.maiwithu.maidroid.ui.component.overscrollBody
import org.maiwithu.maidroid.ui.component.rememberSplitHeaderOverscrollEffect
import org.maiwithu.maidroid.ui.component.statusBarTopInset
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.IconCyanBlue
import org.maiwithu.maidroid.ui.theme.PageBackground
import org.maiwithu.maidroid.ui.theme.TextSizes

/**
 * 全应用统一的镜像源设置页面：支持在设置详情栏 / 竖屏推入页查看并测速三类镜像源，
 * 既可由程序自动测速择优，也可手动锁定某条预设或输入并验证自定义镜像地址。
 */
@Composable
internal fun MirrorSettingsPage(
    manager: MirrorManager,
    state: MirrorSettingsState,
    onBack: () -> Unit,
    showBackButton: Boolean = true,
    compact: Boolean = false,
    expandedTopOffset: Dp = 0.dp,
    modifier: Modifier = Modifier
) {
    val editor = rememberMirrorEditorState(manager = manager, state = state)
    val statusBarTop = statusBarTopInset()
    val effectiveExpandedTopOffset = if (expandedTopOffset > 0.dp) expandedTopOffset else 14.dp
    val horizontalPadding = if (compact) HomeTwoPaneSpec.OuterHorizontalPadding else 20.dp
    val topBarHeight = 52.dp
    val headerTopPadding = statusBarTop + effectiveExpandedTopOffset + 56.dp
    val bottomClearance = LocalBottomNavigationClearance.current

    val listState = rememberLazyListState()
    val overscroll = rememberSplitHeaderOverscrollEffect()
    val density = LocalDensity.current
    val collapseDistancePx = with(density) { (effectiveExpandedTopOffset + 22.dp).toPx() }
    val collapseProgress by remember(listState, collapseDistancePx) {
        derivedStateOf {
            computeLazyHeaderCollapseProgress(
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                collapseDistancePx = collapseDistancePx
            )
        }
    }

    CollapsingGlassHeaderContainer(
        title = "镜像源设置",
        collapseProgress = collapseProgress,
        statusBarTop = statusBarTop,
        horizontalPadding = horizontalPadding,
        expandedTopOffset = effectiveExpandedTopOffset,
        expandedFontSize = if (compact) TextSizes.Headline else TextSizes.Display,
        collapsedFontSize = if (compact) 18.sp else 19.sp,
        topBarHeight = topBarHeight,
        headerOverscrollPx = overscroll.headerOverscrollPx,
        leadingAction = if (showBackButton) {
            {
                GlassHeaderCircleButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回设置",
                    onClick = onBack
                )
            }
        } else {
            null
        },
        modifier = modifier
            .fillMaxSize()
            .appPageBackground(PageBackground)
    ) {
        LazyColumn(
            state = listState,
            overscrollEffect = overscroll,
            contentPadding = PaddingValues(
                start = horizontalPadding,
                top = headerTopPadding,
                end = horizontalPadding,
                bottom = bottomClearance + 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                MirrorOverviewCard(
                    editor = editor,
                    modifier = Modifier.overscrollBody(overscroll)
                )
            }

            item {
                MirrorCategorySegmentBar(
                    selectedCategory = editor.selectedCategory,
                    onSelectCategory = editor::selectCategory,
                    modifier = Modifier.overscrollBody(overscroll)
                )
            }

            item {
                MirrorModeAndPresetsCard(
                    editor = editor,
                    modifier = Modifier.overscrollBody(overscroll)
                )
            }

            item {
                CustomMirrorInputCard(
                    editor = editor,
                    modifier = Modifier.overscrollBody(overscroll)
                )
            }
        }
    }
}

/** OOBE 首次引导阶段弹出的镜像源配置弹窗：与设置页共享同一组测速状态与自定义校验逻辑。 */
@Composable
internal fun MirrorSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val manager = remember(context) { context.appDependencies.mirrorManager }
    val state by manager.state.collectAsState()
    val editor = rememberMirrorEditorState(manager = manager, state = state)

    MaiDialog(
        title = "镜像源设置",
        subtitle = "配置 GitHub、Debian 与 PyPI 加速来源",
        icon = FlatFeatureIcons.MirrorSources,
        iconBackground = IconCyanBlue,
        size = MaiDialogSize.Expanded,
        headerBadge = if (editor.anyTesting) {
            { MaiDialogInfoChip(text = "测速中", color = BrandOrange) }
        } else {
            null
        },
        secondaryContent = {
            CustomMirrorDialogSection(editor = editor)
        },
        onDismissRequest = onDismiss,
        footer = {
            MaiDialogButtonRow {
                MaiDialogButton(
                    text = if (editor.anyTesting) "测速中..." else "全部测速",
                    style = MaiDialogButtonStyle.Secondary,
                    enabled = !editor.anyTesting,
                    modifier = Modifier.weight(1f),
                    onClick = editor::testAll
                )
                MaiDialogButton(
                    text = "完成",
                    style = MaiDialogButtonStyle.Primary,
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss
                )
            }
        }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MirrorCategorySegmentBar(
                selectedCategory = editor.selectedCategory,
                onSelectCategory = editor::selectCategory
            )
            MirrorModeAndPresetsCard(editor = editor)
        }
    }
}
