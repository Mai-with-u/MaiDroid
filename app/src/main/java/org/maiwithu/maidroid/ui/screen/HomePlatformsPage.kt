package org.maiwithu.maidroid.ui.screen

import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.Toast
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
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maiwithu.maidroid.R
import org.maiwithu.maidroid.WebUiActivity
import org.maiwithu.maidroid.platform.NapCatInstallPhase
import org.maiwithu.maidroid.platform.NapCatPlatformManager
import org.maiwithu.maidroid.platform.NapCatPlatformState

private const val NapCatWebUiUrl = "http://127.0.0.1:6099/webui"

@Composable
internal fun MessagePlatformsPage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val napCatManager = remember(context) { NapCatPlatformManager.get(context) }
    val napCatState by napCatManager.state.collectAsState()
    LaunchedEffect(napCatManager) {
        napCatManager.refresh()
    }
    var selectedCategory by remember { mutableStateOf("全部") }
    var selectedAdapters by remember { mutableStateOf(mapOf("QQ" to "NapCat")) }
    var showNapCatQrCode by remember { mutableStateOf(false) }
    var showNapCatConfig by remember { mutableStateOf(false) }
    val categories = listOf("全部", "国内", "海外", "其他")
    val installedPlatforms = installedPlatformCards(napCatState)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MainSurface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MainSurface)
        ) {
            Spacer(modifier = Modifier.height(64.dp))
            MessagePlatformsHeader(runningCount = installedPlatforms.count { it.running })
            Spacer(modifier = Modifier.height(24.dp))
            InstalledPlatformsRow(
                platforms = installedPlatforms,
                onNapCatConfigSettings = {
                    napCatManager.refresh()
                    showNapCatConfig = true
                }
            )
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

                PlatformList(
                    platforms = visiblePlatforms,
                    selectedAdapters = selectedAdapters,
                    napCatState = napCatState,
                    onAdapterSelected = { platform, adapter ->
                        selectedAdapters = selectedAdapters + (platform.name to adapter)
                    },
                    onNapCatInstall = { napCatManager.installNapCat() },
                    onNapCatSettings = {
                        napCatManager.refresh()
                        if (napCatState.oneBotReachable) {
                            context.startActivity(
                                Intent(context, WebUiActivity::class.java).apply {
                                    putExtra(WebUiActivity.EXTRA_URL, NapCatWebUiUrl)
                                }
                            )
                        } else {
                            showNapCatQrCode = true
                        }
                    },
                    onUnsupportedInstall = {
                        Toast.makeText(context, "还没有做适配…… `（> _ <）.", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }

        if (showNapCatQrCode) {
            NapCatQrCodeDialog(
                state = napCatState,
                onRefresh = { napCatManager.refresh() },
                onDismiss = { showNapCatQrCode = false }
            )
        }

        if (showNapCatConfig) {
            NapCatConfigDialog(
                state = napCatState,
                onRefresh = { napCatManager.refresh() },
                onDismiss = { showNapCatConfig = false }
            )
        }
    }
}

@Composable
private fun PlatformList(
    platforms: List<AvailablePlatform>,
    selectedAdapters: Map<String, String>,
    napCatState: NapCatPlatformState,
    onAdapterSelected: (AvailablePlatform, String) -> Unit,
    onNapCatInstall: () -> Unit,
    onNapCatSettings: () -> Unit,
    onUnsupportedInstall: () -> Unit
) {
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
                PlatformInstallCard(
                    platform = platform,
                    selectedAdapter = selectedAdapters[platform.name]
                        ?: platform.adapters.firstOrNull()
                        ?: "",
                    napCatState = napCatState,
                    onAdapterSelected = { adapter -> onAdapterSelected(platform, adapter) },
                    onNapCatInstall = onNapCatInstall,
                    onNapCatSettings = onNapCatSettings,
                    onUnsupportedInstall = onUnsupportedInstall
                )
            }
        }
    }
}

internal fun installedPlatformCards(napCatState: NapCatPlatformState): List<InstalledPlatform> {
    if (!napCatState.installed && !napCatState.hasQrCode && napCatState.oneBotConfig == null) {
        return emptyList()
    }

    val account = when {
        napCatState.oneBotReachable -> "${napCatState.accountDisplayName ?: "QQ"} 在线"
        napCatState.installed -> "NapCat 等待连接"
        else -> "NapCat 待配置"
    }

    return listOf(
        InstalledPlatform(
            name = "QQ（NapCat）",
            iconRes = R.drawable.ic_platform_qq,
            account = account,
            iconColor = Color(0xCC0F3BE9),
            statusColor = if (napCatState.oneBotReachable) PlatformOnline else PlatformTextSecondary,
            running = napCatState.oneBotReachable
        )
    )
}

@Composable
private fun MessagePlatformsHeader(runningCount: Int) {
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
                text = if (runningCount > 0) {
                    "$runningCount 个平台正在运行"
                } else {
                    "暂无平台正在运行"
                },
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
private fun InstalledPlatformsRow(
    platforms: List<InstalledPlatform>,
    onNapCatConfigSettings: () -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
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
                    onSettingsClick = onNapCatConfigSettings
                )
            }
        }
    }
}

@Composable
private fun InstalledPlatformCard(
    platform: InstalledPlatform,
    onSettingsClick: () -> Unit
) {
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
                .padding(top = 10.dp, end = 10.dp)
                .size(34.dp)
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onSettingsClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = "设置",
                tint = PlatformTextSecondary,
                modifier = Modifier.size(22.dp)
            )
        }

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
private fun EmptyInstalledPlatformCard() {
    Column(
        modifier = Modifier
            .width(170.dp)
            .height(135.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(PlatformCardSurface)
            .padding(16.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "暂无消息平台",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 18.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "安装并登录消息平台后会在这里显示状态",
            color = PlatformTextDim,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 15.sp
        )
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
private fun PlatformInstallCard(
    platform: AvailablePlatform,
    selectedAdapter: String,
    napCatState: NapCatPlatformState,
    onAdapterSelected: (String) -> Unit,
    onNapCatInstall: () -> Unit,
    onNapCatSettings: () -> Unit,
    onUnsupportedInstall: () -> Unit
) {
    val hasAdapters = platform.adapters.isNotEmpty()
    val isNapCatTarget = platform.name == "QQ" && selectedAdapter == "NapCat"

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
            napCatState = if (isNapCatTarget) napCatState else null,
            onClick = {
                when {
                    !isNapCatTarget -> onUnsupportedInstall()
                    napCatState.isInstalling -> Unit
                    napCatState.canOpenSettings -> onNapCatSettings()
                    else -> onNapCatInstall()
                }
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 3.dp, end = 8.dp)
        )

        if (hasAdapters) {
            AdapterSelector(
                adapters = platform.adapters,
                selectedAdapter = selectedAdapter,
                onAdapterSelected = onAdapterSelected,
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
private fun DownloadIconButton(
    napCatState: NapCatPlatformState?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val installing = napCatState?.isInstalling == true
    val installed = napCatState?.canOpenSettings == true
    val failed = napCatState?.phase == NapCatInstallPhase.Failed

    Box(
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        when {
            installing -> ProgressRing(progress = napCatState.progress)
            installed -> Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = "设置",
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
            else -> Icon(
                painter = painterResource(R.drawable.ic_platform_download),
                contentDescription = "下载",
                tint = if (failed) Color(0xFFFF6B6B) else Color.White,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

@Composable
private fun AdapterSelector(
    adapters: List<String>,
    selectedAdapter: String,
    onAdapterSelected: (String) -> Unit,
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
        adapters.forEach { adapter ->
            val selected = adapter == selectedAdapter
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(19.dp))
                    .background(if (selected) Color(0xFF3C3C3E) else Color.Transparent)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onAdapterSelected(adapter) }
                    ),
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

@Composable
private fun ProgressRing(progress: Float) {
    val clampedProgress = progress.coerceIn(0f, 1f)
    Canvas(modifier = Modifier.size(32.dp)) {
        val strokeWidth = 3.dp.toPx()
        drawCircle(
            color = PlatformBorder,
            style = Stroke(width = strokeWidth)
        )
        drawArc(
            color = PlatformOrange,
            startAngle = -90f,
            sweepAngle = 360f * clampedProgress,
            useCenter = false,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
    }
}

@Composable
private fun NapCatQrCodeDialog(
    state: NapCatPlatformState,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    val qrBitmap by produceState<ImageBitmap?>(
        initialValue = null,
        state.qrCodePath,
        state.qrCodeLastModified
    ) {
        value = withContext(Dispatchers.IO) {
            state.qrCodePath
                ?.let { BitmapFactory.decodeFile(it) }
                ?.asImageBitmap()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "QQ 登录二维码",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 22.sp
            )
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val bitmap = qrBitmap
                if (bitmap != null) {
                    Box(
                        modifier = Modifier
                            .size(260.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .padding(10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = "NapCat 登录二维码",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    Text(
                        text = if (state.installed) {
                            "NapCat 尚未生成二维码，请稍等片刻后刷新。"
                        } else {
                            "NapCat 尚未安装。"
                        },
                        color = PlatformTextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 17.sp
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "请使用QQ扫码登录……或者截图发给其他设备，然后用QQ扫码",
                    color = PlatformTextDim,
                    fontSize = 11.sp,
                    lineHeight = 14.sp
                )

                state.errorMessage?.let { error ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = error,
                        color = Color(0xFFFF6B6B),
                        fontSize = 12.sp,
                        lineHeight = 15.sp
                    )
                }
            }
        },
        dismissButton = {
            Button(
                onClick = onRefresh,
                colors = ButtonDefaults.buttonColors(
                    containerColor = PlatformBorder,
                    contentColor = Color.White
                )
            ) {
                Text(text = "刷新", fontWeight = FontWeight.Bold)
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = PlatformOrange,
                    contentColor = Color.White
                )
            ) {
                Text(text = "关闭", fontWeight = FontWeight.Bold)
            }
        },
        containerColor = PlatformCardSurface,
        titleContentColor = Color.White,
        textContentColor = PlatformTextSecondary
    )
}

@Composable
private fun NapCatConfigDialog(
    state: NapCatPlatformState,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    val config = state.oneBotConfig

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "NapCat 配置",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 22.sp
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                ConfigValueRow(
                    label = "连接状态",
                    value = when {
                        state.oneBotReachable -> "OneBot 已监听，MaiBot 可连接"
                        config?.enabled == true -> "已启用，等待连接"
                        config != null -> "配置存在但未启用"
                        else -> "未找到 onebot11.json"
                    },
                    valueColor = if (state.oneBotReachable) PlatformOnline else PlatformTextSecondary
                )
                ConfigValueRow("账号", state.accountDisplayName ?: "-")
                ConfigValueRow("服务类型", config?.serviceType ?: "-")
                ConfigValueRow("服务名称", config?.name ?: "-")
                ConfigValueRow("监听地址", config?.host ?: "-")
                ConfigValueRow("监听端口", config?.port?.toString() ?: "-")
                ConfigValueRow("Token", config?.token?.ifBlank { "未设置" } ?: "-")
                ConfigValueRow("消息格式", config?.messagePostFormat?.ifBlank { "-" } ?: "-")
                ConfigValueRow("上报自身消息", config?.reportSelfMessage.toEnabledText())
                ConfigValueRow("强制推送事件", config?.enableForcePushEvent.toEnabledText())
                ConfigValueRow("心跳间隔", config?.heartInterval?.let { "${it}ms" } ?: "-")
                ConfigValueRow("调试模式", config?.debug.toEnabledText())
                ConfigValueRow("配置文件", config?.configPath ?: "/root/napcat/config/onebot11.json")
            }
        },
        dismissButton = {
            Button(
                onClick = onRefresh,
                colors = ButtonDefaults.buttonColors(
                    containerColor = PlatformBorder,
                    contentColor = Color.White
                )
            ) {
                Text(text = "刷新", fontWeight = FontWeight.Bold)
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = PlatformOrange,
                    contentColor = Color.White
                )
            ) {
                Text(text = "关闭", fontWeight = FontWeight.Bold)
            }
        },
        containerColor = PlatformCardSurface,
        titleContentColor = Color.White,
        textContentColor = PlatformTextSecondary
    )
}

@Composable
private fun ConfigValueRow(
    label: String,
    value: String,
    valueColor: Color = PlatformTextSecondary
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
    ) {
        Text(
            text = label,
            color = PlatformTextDim,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 13.sp
        )
        Text(
            text = value,
            color = valueColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 17.sp
        )
    }
}

private fun Boolean?.toEnabledText(): String = when (this) {
    true -> "开启"
    false -> "关闭"
    null -> "-"
}
