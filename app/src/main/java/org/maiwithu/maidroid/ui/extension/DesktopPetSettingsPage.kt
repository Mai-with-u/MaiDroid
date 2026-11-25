package org.maiwithu.maidroid.ui.extension

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maiwithu.maidroid.pet.DesktopPetUiState
import org.maiwithu.maidroid.ui.component.CollapsingGlassHeaderContainer
import org.maiwithu.maidroid.ui.component.FlatFeatureIconBadge
import org.maiwithu.maidroid.ui.component.FlatFeatureIcons
import org.maiwithu.maidroid.ui.component.FlatSwitch
import org.maiwithu.maidroid.ui.component.GlassHeaderCircleButton
import org.maiwithu.maidroid.ui.component.HomeTwoPaneSpec
import org.maiwithu.maidroid.ui.component.LiquidButtonStyle
import org.maiwithu.maidroid.ui.component.LocalBottomNavigationClearance
import org.maiwithu.maidroid.ui.component.computeHeaderSubtitleAlpha
import org.maiwithu.maidroid.ui.component.computeLazyHeaderCollapseProgress
import org.maiwithu.maidroid.ui.component.overscrollBody
import org.maiwithu.maidroid.ui.component.overscrollHeader
import org.maiwithu.maidroid.ui.component.rememberSplitHeaderOverscrollEffect
import org.maiwithu.maidroid.ui.component.statusBarTopInset
import org.maiwithu.maidroid.ui.theme.CardDivider
import org.maiwithu.maidroid.ui.theme.CardSurface
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.IconPeachOrange
import org.maiwithu.maidroid.ui.theme.PageBackground
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes

/**
 * 悬浮桌宠独立配置子页面：
 * - 手机竖屏从「扩展组件」主卡点入（左上角带返回钮，支持系统返回手势退回）；
 * - 横屏与平板大屏作为右侧 62% 详情常驻展示；
 * - 按“运行状态 -> 外观与显示 -> 快捷操作 / 联动机器人”分组收纳，接入连续液态玻璃滑块实现无顿挫实时缩放。
 */
@Composable
internal fun DesktopPetSettingsPage(
    petState: DesktopPetUiState,
    webUiOnline: Boolean,
    overlayGranted: Boolean,
    onBack: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onRequestPermission: () -> Unit,
    onOpenLogin: () -> Unit,
    onEnableRemoteMode: () -> Unit,
    onSelectBot: (String) -> Unit,
    onToggleVisibility: () -> Unit,
    onOpenRecords: () -> Unit,
    onReconnect: () -> Unit,
    onPetScaleChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    showBackButton: Boolean = true,
    compact: Boolean = false,
    expandedTopOffset: Dp = 40.dp
) {
    val switchChecked = petState.desiredEnabled || petState.overlayActive
    val targetPhaseColor = resolvePetPhaseColor(petState)
    val animatedPhaseColor by animateColorAsState(
        targetValue = targetPhaseColor,
        animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow),
        label = "PetDetailPhaseColor"
    )
    val (statusBadgeText, statusBadgeColor) = resolvePetHeaderBadge(ExtensionCatalog.primaryEntry.badge, petState)

    val statusBarTop = statusBarTopInset()
    val overscroll = rememberSplitHeaderOverscrollEffect()
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val collapseDistancePx = with(density) { (if (compact) 40.dp else 52.dp).toPx() }
    val collapseProgress = computeLazyHeaderCollapseProgress(
        firstVisibleItemIndex = listState.firstVisibleItemIndex,
        firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
        collapseDistancePx = collapseDistancePx
    )
    val subtitleAlpha = computeHeaderSubtitleAlpha(collapseProgress)
    val horizontalPadding = if (compact) HomeTwoPaneSpec.OuterHorizontalPadding else 18.dp

    CollapsingGlassHeaderContainer(
        title = "悬浮桌宠",
        collapseProgress = collapseProgress,
        statusBarTop = statusBarTop,
        horizontalPadding = horizontalPadding,
        expandedTopOffset = expandedTopOffset,
        expandedFontSize = if (compact) TextSizes.DisplayCompact else TextSizes.Display,
        collapsedFontSize = if (compact) 18.sp else 19.sp,
        topBarHeight = if (compact) 46.dp else 52.dp,
        headerOverscrollPx = overscroll.headerOverscrollPx,
        leadingAction = if (showBackButton) {
            {
                GlassHeaderCircleButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回扩展组件",
                    onClick = onBack
                )
            }
        } else {
            null
        },
        modifier = modifier
            .fillMaxSize()
            .background(PageBackground)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            overscrollEffect = overscroll,
            contentPadding = PaddingValues(
                top = statusBarTop + expandedTopOffset,
                start = horizontalPadding,
                end = horizontalPadding,
                bottom = LocalBottomNavigationClearance.current
            ),
            verticalArrangement = Arrangement.spacedBy(if (compact) 14.dp else 18.dp)
        ) {
            item {
                Column(
                    modifier = Modifier
                        .overscrollHeader(overscroll)
                        .padding(horizontal = 2.dp)
                ) {
                    Spacer(modifier = Modifier.height(if (compact) 38.dp else 44.dp))
                    Text(
                        text = "管理悬浮显示、缩放大小与本机服务联动",
                        color = TextMuted,
                        fontSize = if (compact) TextSizes.Body else TextSizes.BodyLarge,
                        lineHeight = 18.sp,
                        modifier = Modifier.graphicsLayer { alpha = subtitleAlpha }
                    )
                }
            }

            item {
                PetSettingsSection(
                    title = "运行状态",
                    modifier = Modifier.overscrollBody(overscroll)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Corners.Chip))
                            .background(CardSurface)
                            .padding(if (compact) 14.dp else 16.dp),
                        verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FlatFeatureIconBadge(
                                icon = FlatFeatureIcons.DesktopPet,
                                backgroundColor = IconPeachOrange,
                                size = if (compact) 38.dp else 42.dp,
                                iconSize = if (compact) 24.dp else 26.dp
                            )
                            Spacer(modifier = Modifier.width(if (compact) 10.dp else 12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "启用悬浮桌宠",
                                        color = TextPrimary,
                                        fontSize = TextSizes.BodyLarge,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    ExtensionTagPill(text = statusBadgeText, color = statusBadgeColor)
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "允许麦麦在其他应用上层活动与互动",
                                    color = TextMuted,
                                    fontSize = TextSizes.Caption
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            FlatSwitch(
                                checked = switchChecked,
                                onCheckedChange = onToggle
                            )
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(0.5.dp)
                                .background(CardDivider)
                        )

                        PetRuntimeSummaryBox(
                            petState = petState,
                            phaseColor = animatedPhaseColor,
                            webUiOnline = webUiOnline,
                            overlayGranted = overlayGranted,
                            compact = compact,
                            onRequestPermission = onRequestPermission
                        )

                        PetActionPromptBanner(
                            petState = petState,
                            webUiOnline = webUiOnline,
                            onOpenLogin = onOpenLogin,
                            onEnableRemoteMode = onEnableRemoteMode,
                            onReconnect = onReconnect
                        )
                    }
                }
            }

            item {
                PetSettingsSection(
                    title = "外观与显示",
                    modifier = Modifier.overscrollBody(overscroll)
                ) {
                    PetScaleControlPanel(
                        scale = petState.petScale,
                        compact = compact,
                        onScaleChange = onPetScaleChange
                    )
                }
            }

            if (switchChecked) {
                item {
                    PetSettingsSection(
                        title = "快捷操作",
                        modifier = Modifier.overscrollBody(overscroll)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Corners.Chip))
                                .background(CardSurface)
                                .padding(if (compact) 14.dp else 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp)
                            ) {
                                CompactActionButton(
                                    icon = if (petState.overlayVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    label = if (petState.overlayVisible) "隐藏桌宠" else "显示桌宠",
                                    style = if (!petState.overlayVisible && petState.overlayActive) {
                                        LiquidButtonStyle.SoftAccent
                                    } else {
                                        LiquidButtonStyle.Secondary
                                    },
                                    enabled = petState.overlayActive,
                                    compact = compact,
                                    onClick = onToggleVisibility,
                                    modifier = Modifier.weight(1f)
                                )
                                CompactActionButton(
                                    icon = Icons.Outlined.Explore,
                                    label = "探索记录",
                                    style = LiquidButtonStyle.Secondary,
                                    enabled = petState.overlayActive,
                                    compact = compact,
                                    onClick = onOpenRecords,
                                    modifier = Modifier.weight(1f)
                                )
                                CompactActionButton(
                                    icon = Icons.Outlined.Refresh,
                                    label = "重连",
                                    style = LiquidButtonStyle.Secondary,
                                    enabled = !petState.updatingConfig,
                                    compact = compact,
                                    onClick = onReconnect,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }

            if (petState.bots.size > 1) {
                item {
                    PetSettingsSection(
                        title = "实例切换",
                        modifier = Modifier.overscrollBody(overscroll)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Corners.Chip))
                                .background(CardSurface)
                                .padding(if (compact) 14.dp else 16.dp)
                        ) {
                            PetBotSelectorRow(
                                bots = petState.bots,
                                selectedBotId = petState.selectedBotId,
                                onSelectBot = onSelectBot
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PetSettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            color = TextMuted,
            fontSize = TextSizes.Caption,
            fontWeight = FontWeight.Medium,
            lineHeight = 14.sp,
            modifier = Modifier.padding(start = 6.dp)
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            content = content
        )
    }
}
