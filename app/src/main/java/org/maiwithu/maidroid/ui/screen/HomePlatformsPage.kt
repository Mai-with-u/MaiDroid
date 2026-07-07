package org.maiwithu.maidroid.ui.screen

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maiwithu.maidroid.R

@Composable
internal fun MessagePlatformsPage(modifier: Modifier = Modifier) {
    var selectedCategory by remember { mutableStateOf("全部") }
    val categories = listOf("全部", "国内", "海外", "其他")

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MainSurface)
    ) {
        Spacer(modifier = Modifier.height(64.dp))
        MessagePlatformsHeader()
        Spacer(modifier = Modifier.height(24.dp))
        InstalledPlatformsRow()
        Spacer(modifier = Modifier.height(24.dp))
        DiscoverHeader(
            categories = categories,
            selectedCategory = selectedCategory,
            onCategorySelected = { selectedCategory = it }
        )
        Spacer(modifier = Modifier.height(24.dp))

        AnimatedContent(
            targetState = selectedCategory,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(MainSurface),
            transitionSpec = {
                (fadeIn(animationSpec = tween(180, easing = FastOutSlowInEasing)) +
                    slideInHorizontally(animationSpec = tween(220, easing = FastOutSlowInEasing)) { 24 }) togetherWith
                    (fadeOut(animationSpec = tween(120)) +
                        slideOutHorizontally(animationSpec = tween(180, easing = FastOutSlowInEasing)) { -24 }) using
                    SizeTransform(clip = false)
            },
            label = "PlatformCategoryTransition"
        ) { category ->
            val visiblePlatforms = remember(category) {
                if (category == "全部") {
                    AvailablePlatforms
                } else {
                    AvailablePlatforms.filter { category in it.tags }
                }
            }

            PlatformList(platforms = visiblePlatforms)
        }
    }
}

@Composable
private fun PlatformList(platforms: List<AvailablePlatform>) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MainSurface),
        contentPadding = PaddingValues(start = 16.dp, end = 11.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (platforms.isEmpty()) {
            item {
                EmptyPlatformCard()
            }
        } else {
            items(platforms) { platform ->
                PlatformInstallCard(platform = platform)
            }
        }
    }
}

@Composable
private fun MessagePlatformsHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(66.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "消息平台",
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 34.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "3 个平台正在运行",
                color = PlatformTextSecondary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 18.sp
            )
        }

        SearchButton()
    }
}

@Composable
private fun SearchButton() {
    Box(
        modifier = Modifier
            .padding(top = 0.dp)
            .size(42.dp)
            .clip(CircleShape)
            .border(1.dp, Color(0x552B2B2B), CircleShape)
            .background(Color(0x552B2B2B))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = "搜索",
            tint = PlatformTextSecondary,
            modifier = Modifier.size(25.dp)
        )
    }
}

@Composable
private fun InstalledPlatformsRow() {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(InstalledPlatforms) { platform ->
            InstalledPlatformCard(platform = platform)
        }
    }
}

@Composable
private fun InstalledPlatformCard(platform: InstalledPlatform) {
    Box(
        modifier = Modifier
            .width(170.dp)
            .height(135.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(PlatformCardSurface)
    ) {
        PlatformLogo(
            iconRes = platform.iconRes,
            contentDescription = platform.name,
            color = platform.iconColor,
            modifier = Modifier
                .offset(x = 12.dp, y = 12.dp)
                .size(52.dp),
            cornerRadius = 12.dp,
            iconSize = 32.dp
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 12.dp, end = 14.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(platform.statusColor)
        )

        Text(
            text = platform.name,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 18.sp,
            modifier = Modifier
                .offset(x = 12.dp, y = 72.dp)
                .width(130.dp)
        )

        Row(
            modifier = Modifier
                .offset(x = 12.dp, y = 96.dp)
                .width(142.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(platform.statusColor)
            )

            Spacer(modifier = Modifier.width(5.dp))

            Text(
                text = platform.account,
                color = platform.statusColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 14.sp,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun DiscoverHeader(
    categories: List<String>,
    selectedCategory: String,
    onCategorySelected: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "发现",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 30.sp,
                modifier = Modifier.weight(1f)
            )

            Text(
                text = "共 325 个",
                color = PlatformOrange,
                fontSize = 14.sp,
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

@Composable
private fun CategoryChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (selected) PlatformOrange else PlatformCardSurface,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "CategoryChipBackground"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) PlatformOrange else PlatformBorder,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "CategoryChipBorder"
    )
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.96f,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "CategoryChipScale"
    )

    Box(
        modifier = Modifier
            .width(72.dp)
            .height(42.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(21.dp))
            .border(
                width = 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(21.dp)
            )
            .background(backgroundColor)
            .animateContentSize(animationSpec = tween(180, easing = FastOutSlowInEasing))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 20.sp
        )
    }
}

@Composable
private fun PlatformInstallCard(platform: AvailablePlatform) {
    val hasAdapters = platform.adapters.isNotEmpty()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (hasAdapters) 172.dp else 120.dp)
            .clip(RoundedCornerShape(24.dp))
            .border(1.dp, PlatformBorder, RoundedCornerShape(24.dp))
            .background(PlatformCardSurface)
            .padding(16.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            PlatformLogo(
                iconRes = platform.iconRes,
                contentDescription = platform.name,
                color = platform.iconColor,
                modifier = Modifier.size(64.dp),
                cornerRadius = 16.dp,
                iconSize = 32.dp
            )

            Spacer(modifier = Modifier.width(10.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 48.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = platform.name,
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 26.sp
                    )

                    platform.badge?.let { badge ->
                        Spacer(modifier = Modifier.width(8.dp))
                        Badge(text = badge)
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = platform.provider,
                    color = PlatformTextDim,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 15.sp
                )

                Text(
                    text = platform.description,
                    color = PlatformTextDim,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 15.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    platform.tags.forEach { tag ->
                        SmallTag(text = tag)
                    }
                }
            }
        }

        DownloadIconButton(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 3.dp, end = 8.dp)
        )

        if (hasAdapters) {
            AdapterSelector(
                adapters = platform.adapters,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .width(172.dp)
                    .height(42.dp)
            )
        }
    }
}

@Composable
private fun EmptyPlatformCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(24.dp))
            .border(1.dp, PlatformBorder, RoundedCornerShape(24.dp))
            .background(PlatformCardSurface)
            .padding(18.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "暂无其他平台",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 22.sp
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "后续适配项会出现在这里。",
            color = PlatformTextDim,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 16.sp
        )
    }
}

@Composable
private fun PlatformLogo(
    @DrawableRes iconRes: Int,
    contentDescription: String?,
    color: Color,
    modifier: Modifier,
    cornerRadius: Dp,
    iconSize: Dp
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(color),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(iconSize)
        )
    }
}

@Composable
private fun Badge(text: String) {
    Box(
        modifier = Modifier
            .height(16.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(PlatformOrange.copy(alpha = 0.5f))
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 10.sp
        )
    }
}

@Composable
private fun SmallTag(text: String) {
    Box(
        modifier = Modifier
            .height(20.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(PlatformBorder)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 11.sp
        )
    }
}

@Composable
private fun DownloadIconButton(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_platform_download),
            contentDescription = "下载",
            tint = Color.White,
            modifier = Modifier.size(32.dp)
        )
    }
}

@Composable
private fun AdapterSelector(
    adapters: List<String>,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(21.dp))
            .background(PlatformBorder)
            .border(1.dp, PlatformBorder, RoundedCornerShape(21.dp))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        adapters.forEachIndexed { index, adapter ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(19.dp))
                    .background(if (index == 0) Color(0xFF3C3C3E) else Color.Transparent),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = adapter,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 14.sp
                )
            }
        }
    }
}
