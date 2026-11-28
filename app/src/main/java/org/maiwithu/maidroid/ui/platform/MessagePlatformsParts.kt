package org.maiwithu.maidroid.ui.platform

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maiwithu.maidroid.ui.component.LocalBottomNavigationClearance
import org.maiwithu.maidroid.ui.component.appPageBackground
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardBorder
import org.maiwithu.maidroid.ui.theme.CardSurface
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.GlassButtonSurface
import org.maiwithu.maidroid.ui.theme.GlassStroke
import org.maiwithu.maidroid.ui.theme.PageBackground
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes
import androidx.compose.foundation.lazy.grid.items as gridItems

/** 平板模式下的发现区多列网格：自动按可用宽度决定列数并居中排布。 */
@Composable
internal fun PlatformGrid(
    platforms: List<AvailablePlatform>,
    selectedAccessMethods: Map<String, AccessMethod>,
    actions: PlatformActions
) {
    val bottomClearance = LocalBottomNavigationClearance.current

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .appPageBackground(PageBackground),
        contentAlignment = Alignment.TopCenter
    ) {
        val availableWidth = (maxWidth - PlatformGridHorizontalPadding * 2f)
            .coerceAtLeast(0.dp)
        val widthBasedColumnCount = calculatePlatformGridColumnCount(availableWidth.value)
        // 当前分类结果数量不足以填满整行时，不保留空列占位，让实际渲染的窄网格由父容器居中
        val columnCount = minOf(widthBasedColumnCount, platforms.size.coerceAtLeast(1))
        val maximumGridWidth = PlatformGridMaxCardWidthDp.dp * columnCount.toFloat() +
            PlatformGridSpacingDp.dp * (columnCount - 1).toFloat()
        val gridWidth = minOf(availableWidth, maximumGridWidth)

        LazyVerticalGrid(
            columns = GridCells.Fixed(columnCount),
            modifier = Modifier
                .width(gridWidth)
                .fillMaxHeight(),
            contentPadding = PaddingValues(bottom = bottomClearance),
            horizontalArrangement = Arrangement.spacedBy(PlatformGridSpacingDp.dp),
            verticalArrangement = Arrangement.spacedBy(PlatformGridSpacingDp.dp)
        ) {
            if (platforms.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        EmptyPlatformCard(
                            modifier = Modifier.widthIn(max = PlatformGridMaxCardWidthDp.dp)
                        )
                    }
                }
            } else {
                gridItems(
                    items = platforms,
                    key = { platform -> platform.name }
                ) { platform ->
                    PlatformInstallCard(
                        platform = platform,
                        selectedAccessMethod = selectedAccessMethods[platform.name] ?: platform.accessMethods.firstOrNull(),
                        targets = actions.targets,
                        onAccessMethodSelected = { accessMethod -> actions.onAccessMethodSelected(platform, accessMethod) }
                    )
                }
            }
        }
    }
}

/**
 * 页头：“消息平台”标题、运行中的平台数，右边是搜索按钮。
 * 紧凑版给手机横屏左栏用，字号和设置页分栏左侧的“设置”标题一致，也不再占固定的 66dp 高。
 */
@Composable
internal fun MessagePlatformsHeader(
    runningCount: Int,
    searchOpen: Boolean,
    onSearchClick: () -> Unit,
    horizontalPadding: Dp = PlatformGridHorizontalPadding,
    compact: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (compact) Modifier else Modifier.height(66.dp))
            .padding(horizontal = horizontalPadding),
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "消息平台",
                color = TextPrimary,
                fontSize = if (compact) TextSizes.DisplayCompact else TextSizes.Display,
                fontWeight = FontWeight.Bold,
                lineHeight = if (compact) 30.sp else 34.sp
            )

            Spacer(modifier = Modifier.height(if (compact) 6.dp else 8.dp))

            Text(
                text = if (runningCount > 0) {
                    "$runningCount 个平台正在运行"
                } else {
                    "暂无平台正在运行"
                },
                color = TextMuted,
                fontSize = if (compact) TextSizes.Body else TextSizes.BodyLarge,
                fontWeight = FontWeight.Bold,
                lineHeight = if (compact) 17.sp else 18.sp
            )
        }

        SearchButton(searchOpen = searchOpen, onClick = onSearchClick)
    }
}

@Composable
private fun SearchButton(searchOpen: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(GlassButtonSurface)
            .border(0.8.dp, GlassStroke, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (searchOpen) Icons.Outlined.Close else Icons.Outlined.Search,
            contentDescription = if (searchOpen) "关闭搜索" else "搜索平台",
            tint = TextMuted,
            modifier = Modifier.size(25.dp)
        )
    }
}

/** 平台页关键字筛选输入框，展开时自动获取焦点并弹出软键盘。 */
@Composable
internal fun PlatformSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    horizontalPadding: Dp = PlatformGridHorizontalPadding
) {
    val focusRequester = remember { FocusRequester() }
    // 点开搜索后直接把焦点放进输入框、弹出键盘，不用再点一次。
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding)
            .focusRequester(focusRequester),
        singleLine = true,
        placeholder = { Text(text = "搜索平台名称或简介", color = TextMuted) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(19.dp)
            )
        },
        shape = RoundedCornerShape(Corners.Control),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            cursorColor = BrandOrange,
            focusedBorderColor = BrandOrange.copy(alpha = 0.8f),
            unfocusedBorderColor = CardBorder,
            focusedContainerColor = CardSurface,
            unfocusedContainerColor = CardSurface
        )
    )
}

/** 平板顶部的已安装平台横向小卡片列表。 */
@Composable
internal fun InstalledPlatformsRow(
    platforms: List<InstalledPlatform>,
    onSettingsClick: (InstalledPlatform) -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = PlatformGridHorizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (platforms.isEmpty()) {
            item {
                EmptyInstalledPlatformCard()
            }
        } else {
            items(platforms) { platform ->
                InstalledPlatformCard(
                    platform = platform,
                    onSettingsClick = { onSettingsClick(platform) }
                )
            }
        }
    }
}

/** 平板“发现”区固定标题与分类标签行。 */
@Composable
internal fun DiscoverHeader(
    platformCount: Int,
    categories: List<String>,
    selectedCategory: String,
    onCategorySelected: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PlatformGridHorizontalPadding)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "发现",
                color = TextPrimary,
                fontSize = TextSizes.Headline,
                fontWeight = FontWeight.Bold,
                lineHeight = 30.sp,
                modifier = Modifier.weight(1f)
            )

            Text(
                text = "共 $platformCount 个",
                color = BrandOrange,
                fontSize = TextSizes.Body,
                fontWeight = FontWeight.Bold,
                lineHeight = 16.sp
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(categories) { category ->
                CategoryChip(
                    text = category,
                    selected = category == selectedCategory,
                    onClick = { onCategorySelected(category) }
                )
            }
        }
    }
}

/**
 * 分类按钮。平板上是 72×42 的大按钮；手机列表里用紧凑版：32dp 高、宽度随文字，
 * 字号降到正文大小，不再比平台名还抢眼。
 */
@Composable
internal fun CategoryChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    compact: Boolean = false
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (selected) BrandOrange else CardSurface,
        animationSpec = spring(
            dampingRatio = 1f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "CategoryChipBackground"
    )
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.96f,
        animationSpec = spring(
            dampingRatio = 0.72f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "CategoryChipScale"
    )

    Box(
        modifier = Modifier
            .then(
                if (compact) {
                    Modifier
                        .height(32.dp)
                        .widthIn(min = 56.dp)
                } else {
                    Modifier
                        .width(72.dp)
                        .height(42.dp)
                }
            )
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(Corners.Chip))
            .background(backgroundColor)
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = 0.86f,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
            .clickable(onClick = onClick)
            .then(if (compact) Modifier.padding(horizontal = 12.dp) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = TextPrimary,
            fontSize = if (compact) TextSizes.Body else TextSizes.Title,
            fontWeight = FontWeight.Medium,
            lineHeight = if (compact) 18.sp else 20.sp
        )
    }
}
