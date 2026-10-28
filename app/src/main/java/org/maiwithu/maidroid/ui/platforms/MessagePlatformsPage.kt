package org.maiwithu.maidroid.ui.platforms

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.maiwithu.maidroid.R
import org.maiwithu.maidroid.appContainer
import org.maiwithu.maidroid.WebUiActivity
import org.maiwithu.maidroid.platform.napcat.NapCatInstallPhase
import org.maiwithu.maidroid.platform.napcat.NapCatPlatformState
import org.maiwithu.maidroid.platform.qqofficial.QqOfficialInstallPhase
import org.maiwithu.maidroid.platform.qqofficial.QqOfficialPlatformState
import org.maiwithu.maidroid.platform.qqofficial.QqOfficialSaveResult
import org.maiwithu.maidroid.platform.qqofficial.qqOfficialStatusText
import kotlin.math.ceil
import kotlin.math.floor
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardBorder
import org.maiwithu.maidroid.ui.theme.CardSurface
import org.maiwithu.maidroid.ui.theme.PageBackground
import org.maiwithu.maidroid.ui.theme.StatusOnline
import org.maiwithu.maidroid.ui.theme.TextFaint
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.component.statusBarTopInset
import org.maiwithu.maidroid.ui.component.horizontalSlideTransition

private const val PlatformGridMinCardWidthDp = 272f
private const val PlatformGridMaxCardWidthDp = 420f
private const val PlatformGridSpacingDp = 12f
private val PlatformGridHorizontalPadding = 16.dp

/**
 * Chooses the largest practical card layout for the available content width.
 *
 * Cards stay between 272dp and 420dp whenever the viewport can satisfy both
 * constraints. For in-between widths where that is impossible, fewer columns
 * are preferred so controls inside a card are not squeezed.
 */
internal fun calculatePlatformGridColumnCount(availableWidthDp: Float): Int {
    val safeWidth = availableWidthDp.coerceAtLeast(0f)
    val minimumColumnsForMaxCardWidth = ceil(
        (safeWidth + PlatformGridSpacingDp) /
            (PlatformGridMaxCardWidthDp + PlatformGridSpacingDp)
    ).toInt().coerceAtLeast(1)
    val maximumColumnsForMinCardWidth = floor(
        (safeWidth + PlatformGridSpacingDp) /
            (PlatformGridMinCardWidthDp + PlatformGridSpacingDp)
    ).toInt().coerceAtLeast(1)

    return minimumColumnsForMaxCardWidth.coerceAtMost(maximumColumnsForMinCardWidth)
}

@Composable
internal fun MessagePlatformsPage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val napCatManager = remember(context) { context.appContainer.napCat }
    val napCatState by napCatManager.state.collectAsState()
    val uiScope = rememberCoroutineScope()
    val qqOfficialManager = remember(context) { context.appContainer.qqOfficial }
    val qqOfficialState by qqOfficialManager.state.collectAsState()
    LaunchedEffect(napCatManager) {
        napCatManager.refresh()
    }
    LaunchedEffect(qqOfficialManager) {
        qqOfficialManager.refresh()
    }
    var selectedCategory by remember { mutableStateOf("全部") }
    // QQ 卡片默认选中最左边的官方机器人；用户切到 NapCat 等其他选项后，本次页面内记住所选。
    var selectedAdapters by remember { mutableStateOf(mapOf("QQ" to PlatformAdapter.QqOfficial)) }
    // 弹窗开关在 Activity 重建后保留：用户切到 QQ 扫码时系统可能因配置变化重建界面，回来弹窗还在。
    var showNapCatQrCode by rememberSaveable { mutableStateOf(false) }
    var showNapCatConfig by rememberSaveable { mutableStateOf(false) }
    var showQqLogoutConfirm by rememberSaveable { mutableStateOf(false) }
    var showQqOfficialConfig by rememberSaveable { mutableStateOf(false) }
    val categories = listOf("全部", "国内", "海外", "其他")
    val installedPlatforms = installedPlatformCards(napCatState, qqOfficialState)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(PageBackground)
    ) {
        val statusBarTop = statusBarTopInset()
        // Grow the generous portrait spacing back gradually instead of jumping
        // at a breakpoint when a landscape window is resized.
        val verticalExpansion = ((maxHeight.value - 480f) / (720f - 480f))
            .coerceIn(0f, 1f)
        // The previous 64dp top slot assumed a roughly 24dp status bar. Keep its
        // visual breathing room separate from the inset reported by the system.
        val topSpacing = 8.dp + 32.dp * verticalExpansion
        val sectionSpacing = 8.dp + 16.dp * verticalExpansion

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(PageBackground)
                .padding(top = statusBarTop)
        ) {
            Spacer(modifier = Modifier.height(topSpacing))
            MessagePlatformsHeader(runningCount = installedPlatforms.count { it.running })
            Spacer(modifier = Modifier.height(sectionSpacing))
            InstalledPlatformsRow(
                platforms = installedPlatforms,
                onSettingsClick = { platform ->
                    when (platform.kind) {
                        InstalledPlatformKind.NapCat -> {
                            napCatManager.refresh()
                            showNapCatConfig = true
                        }
                        InstalledPlatformKind.QqOfficial -> {
                            qqOfficialManager.refresh()
                            showQqOfficialConfig = true
                        }
                    }
                }
            )
            Spacer(modifier = Modifier.height(sectionSpacing))
            DiscoverHeader(
                categories = categories,
                selectedCategory = selectedCategory,
                onCategorySelected = { selectedCategory = it }
            )
            Spacer(modifier = Modifier.height(sectionSpacing))

            AnimatedContent(
                targetState = selectedCategory,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(PageBackground),
                transitionSpec = {
                    val forward = categories.indexOf(targetState) >= categories.indexOf(initialState)
                    horizontalSlideTransition(forward = forward, dampingRatio = 0.82f)
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
                    qqOfficialState = qqOfficialState,
                    onAdapterSelected = { platform, adapter ->
                        selectedAdapters = selectedAdapters + (platform.name to adapter)
                    },
                    onNapCatInstall = { napCatManager.installNapCat() },
                    onNapCatSettings = {
                        napCatManager.refresh()
                        // 点设置 -> 先问 NapCat QQ 是否已登录 -> 已登录进管理页，未登录弹二维码。
                        // 不能只看 OneBot 端口：登录后端口还没起来或 WsServer 被关掉时，
                        // 会弹出二维码又因“已登录”立刻关掉，永远进不了管理页去修。
                        uiScope.launch {
                            if (napCatState.oneBotReachable || napCatManager.isQqLoggedIn()) {
                                context.startActivity(
                                    Intent(context, WebUiActivity::class.java).apply {
                                        putExtra(WebUiActivity.EXTRA_URL, napCatManager.webUiPageUrl())
                                    }
                                )
                            } else {
                                showNapCatQrCode = true
                            }
                        }
                    },
                    // 官方机器人没装时点下载、装好后点齿轮都打开同一个凭据弹窗：先填 AppID 再安装。
                    onQqOfficialSettings = {
                        qqOfficialManager.refresh()
                        showQqOfficialConfig = true
                    },
                    onUnsupportedInstall = {
                        Toast.makeText(context, "还没有做适配…… `（> _ <）.", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }

        if (showNapCatQrCode) {
            // 弹窗开着才盯扫码进度：码过期或扫码作废时自动换新码，关掉弹窗协程取消即停止。
            LaunchedEffect(napCatManager) {
                napCatManager.keepQrCodeFresh()
            }
            // 手机上确认登录 -> NapCat 进入 ready / OneBot 端口开始监听 -> 自动关弹窗并提示，不用再手动点关闭。
            LaunchedEffect(napCatState.qrLoginCompleted) {
                if (napCatState.qrLoginCompleted) {
                    showNapCatQrCode = false
                    Toast.makeText(context, "QQ 登录成功", Toast.LENGTH_SHORT).show()
                }
            }
            NapCatQrCodeDialog(
                state = napCatState,
                onRefresh = { napCatManager.refreshQrCode() },
                onDismiss = { showNapCatQrCode = false }
            )
        }

        if (showNapCatConfig) {
            NapCatConfigDialog(
                state = napCatState,
                onRefresh = { napCatManager.refresh() },
                onLogoutQq = { showQqLogoutConfirm = true },
                onDismiss = { showNapCatConfig = false }
            )
        }

        // 未安装时确认 -> 后台克隆适配器、写凭据、重启 MaiBot，进度显示在卡片的进度环上；
        // 已安装时确认 -> 只改写凭据并重启 MaiBot，凭据没变就直接关掉。
        if (showQqOfficialConfig) {
            QqOfficialConfigDialog(
                state = qqOfficialState,
                onConfirm = { appId, appSecret ->
                    showQqOfficialConfig = false
                    if (!qqOfficialState.installed) {
                        qqOfficialManager.installAndConfigure(appId, appSecret)
                        Toast.makeText(context, "正在安装 QQ 官方机器人适配器", Toast.LENGTH_SHORT).show()
                    } else {
                        uiScope.launch {
                            val message = when (val result = qqOfficialManager.saveCredentials(appId, appSecret)) {
                                QqOfficialSaveResult.Unchanged -> null
                                QqOfficialSaveResult.Restarting -> "已保存，正在重启 MaiBot 让适配器重新连接"
                                is QqOfficialSaveResult.Failed -> "保存失败：${result.message}"
                            }
                            message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                        }
                    }
                },
                onDismiss = { showQqOfficialConfig = false }
            )
        }

        // 退出 QQ 会让 MaiBot 断开 QQ，必须二次确认；确认后关掉配置弹窗，后台清自动登录并重启 NapCat。
        if (showQqLogoutConfirm) {
            QqLogoutConfirmDialog(
                onConfirm = {
                    showQqLogoutConfirm = false
                    showNapCatConfig = false
                    napCatManager.logoutQq()
                    Toast.makeText(context, "正在退出 QQ 登录，稍后可重新扫码", Toast.LENGTH_SHORT).show()
                },
                onDismiss = { showQqLogoutConfirm = false }
            )
        }
    }
}

@Composable
private fun PlatformList(
    platforms: List<AvailablePlatform>,
    selectedAdapters: Map<String, PlatformAdapter>,
    napCatState: NapCatPlatformState,
    qqOfficialState: QqOfficialPlatformState,
    onAdapterSelected: (AvailablePlatform, PlatformAdapter) -> Unit,
    onNapCatInstall: () -> Unit,
    onNapCatSettings: () -> Unit,
    onQqOfficialSettings: () -> Unit,
    onUnsupportedInstall: () -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(PageBackground),
        contentAlignment = Alignment.TopCenter
    ) {
        val availableWidth = (maxWidth - PlatformGridHorizontalPadding * 2f)
            .coerceAtLeast(0.dp)
        val widthBasedColumnCount = calculatePlatformGridColumnCount(availableWidth.value)
        // Do not reserve empty columns when a category has fewer results; the
        // resulting narrower grid stays centered by the parent container.
        val columnCount = minOf(widthBasedColumnCount, platforms.size.coerceAtLeast(1))
        val maximumGridWidth = PlatformGridMaxCardWidthDp.dp * columnCount.toFloat() +
            PlatformGridSpacingDp.dp * (columnCount - 1).toFloat()
        val gridWidth = minOf(availableWidth, maximumGridWidth)

        LazyVerticalGrid(
            columns = GridCells.Fixed(columnCount),
            modifier = Modifier
                .width(gridWidth)
                .fillMaxHeight(),
            contentPadding = PaddingValues(bottom = 112.dp),
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
                        selectedAdapter = selectedAdapters[platform.name] ?: platform.adapters.firstOrNull(),
                        napCatState = napCatState,
                        qqOfficialState = qqOfficialState,
                        onAdapterSelected = { adapter -> onAdapterSelected(platform, adapter) },
                        onNapCatInstall = onNapCatInstall,
                        onNapCatSettings = onNapCatSettings,
                        onQqOfficialSettings = onQqOfficialSettings,
                        onUnsupportedInstall = onUnsupportedInstall
                    )
                }
            }
        }
    }
}

internal fun installedPlatformCards(
    napCatState: NapCatPlatformState,
    qqOfficialState: QqOfficialPlatformState
): List<InstalledPlatform> = listOfNotNull(
    napCatInstalledCard(napCatState),
    qqOfficialInstalledCard(qqOfficialState)
)

private fun napCatInstalledCard(napCatState: NapCatPlatformState): InstalledPlatform? {
    if (!napCatState.installed && !napCatState.hasQrCode && napCatState.oneBotConfig == null) {
        return null
    }

    val account = when {
        napCatState.oneBotReachable -> "${napCatState.accountDisplayName ?: "QQ"} 在线"
        napCatState.installed -> "NapCat 等待连接"
        else -> "NapCat 待配置"
    }

    return InstalledPlatform(
        kind = InstalledPlatformKind.NapCat,
        name = "QQ（NapCat）",
        iconRes = R.drawable.ic_platform_qq,
        account = account,
        iconColor = Color(0xCC0F3BE9),
        statusColor = if (napCatState.oneBotReachable) StatusOnline else TextMuted,
        running = napCatState.oneBotReachable
    )
}

/**
 * 官方机器人装好后才出现在已安装行里。适配器连上 QQ 时不上报，
 * 所以“已启用”只表示凭据齐全、MaiBot 在跑且近一分钟没有连接报错。
 */
private fun qqOfficialInstalledCard(state: QqOfficialPlatformState): InstalledPlatform? {
    if (!state.installed) return null
    return InstalledPlatform(
        kind = InstalledPlatformKind.QqOfficial,
        // 卡片标题只有 130dp 宽，“QQ（官方机器人）”会折行压住状态行，所以不加括号。
        name = "QQ 官方机器人",
        iconRes = R.drawable.ic_platform_qq,
        account = qqOfficialStatusText(state),
        iconColor = Color(0xCC0F3BE9),
        statusColor = qqOfficialStatusColor(state),
        running = state.presumedOnline
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
                color = TextMuted,
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
            tint = TextMuted,
            modifier = Modifier.size(25.dp)
        )
    }
}

@Composable
private fun InstalledPlatformsRow(
    platforms: List<InstalledPlatform>,
    onSettingsClick: (InstalledPlatform) -> Unit
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
                    onSettingsClick = { onSettingsClick(platform) }
                )
            }
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
                color = BrandOrange,
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
        targetValue = if (selected) BrandOrange else CardSurface,
        animationSpec = spring(
            dampingRatio = 1f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "CategoryChipBackground"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) BrandOrange else CardBorder,
        animationSpec = spring(
            dampingRatio = 1f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "CategoryChipBorder"
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
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = 0.86f,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
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
    selectedAdapter: PlatformAdapter?,
    napCatState: NapCatPlatformState,
    qqOfficialState: QqOfficialPlatformState,
    onAdapterSelected: (PlatformAdapter) -> Unit,
    onNapCatInstall: () -> Unit,
    onNapCatSettings: () -> Unit,
    onQqOfficialSettings: () -> Unit,
    onUnsupportedInstall: () -> Unit
) {
    val hasAdapters = platform.adapters.isNotEmpty()
    val isNapCatTarget = selectedAdapter == PlatformAdapter.NapCat
    val isQqOfficialTarget = selectedAdapter == PlatformAdapter.QqOfficial
    // 右上角按钮跟着选中的适配器显示各自的安装进度；没适配的选项一律显示下载图标。
    val buttonState = when {
        isNapCatTarget -> InstallButtonState(
            installing = napCatState.isInstalling,
            progress = napCatState.progress,
            installed = napCatState.canOpenSettings,
            failed = napCatState.phase == NapCatInstallPhase.Failed
        )
        isQqOfficialTarget -> InstallButtonState(
            installing = qqOfficialState.isInstalling,
            progress = qqOfficialState.progress,
            installed = qqOfficialState.installed,
            failed = qqOfficialState.phase == QqOfficialInstallPhase.Failed
        )
        else -> InstallButtonState()
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (hasAdapters) 172.dp else 120.dp)
            .clip(RoundedCornerShape(24.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(24.dp))
            .background(CardSurface)
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
                        lineHeight = 25.sp
                    )

                    platform.badge?.let { badge ->
                        Spacer(modifier = Modifier.width(8.dp))
                        Badge(text = badge)
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // 服务方说明跟着选择器走：选官方机器人时不再写“将由 NapCat 提供服务”。
                val provider = platform.adapterProviders[selectedAdapter] ?: platform.provider
                Text(
                    text = "$provider\n${platform.description}",
                    color = TextFaint,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 15.sp,
                    maxLines = 2,
                    modifier = Modifier.height(30.dp)
                )

                Spacer(modifier = Modifier.height(9.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    platform.tags.forEach { tag ->
                        SmallTag(text = tag)
                    }
                }
            }
        }

        DownloadIconButton(
            state = buttonState,
            onClick = {
                when {
                    isQqOfficialTarget -> if (!qqOfficialState.isInstalling) onQqOfficialSettings()
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
                    .width(adapterSelectorWidth(platform.adapters.size))
                    .height(42.dp)
            )
        }
    }
}

// 卡片右上角按钮的样子：安装中显示进度环，已安装显示齿轮，否则显示下载图标（上次失败时标红）。
private data class InstallButtonState(
    val installing: Boolean = false,
    val progress: Float = 0f,
    val installed: Boolean = false,
    val failed: Boolean = false
)

@Composable
private fun DownloadIconButton(
    state: InstallButtonState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val installing = state.installing
    val installed = state.installed
    val failed = state.failed

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
            installing -> ProgressRing(progress = state.progress)
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

private val AdapterSelectorItemWidth = 72.dp
private val AdapterSelectorItemSpacing = 2.dp
private val AdapterSelectorPadding = 2.dp

/**
 * 选择器按选项数定宽：每格 72dp 正好放下 12sp 的“官方机器人”五个字不折行。
 * 三个选项时约 224dp，卡片最窄 272dp 减去内边距还剩 240dp，放得下。
 */
internal fun adapterSelectorWidth(adapterCount: Int): Dp {
    val count = adapterCount.coerceAtLeast(1)
    return AdapterSelectorItemWidth * count.toFloat() +
        AdapterSelectorItemSpacing * (count - 1).toFloat() +
        AdapterSelectorPadding * 2f
}

@Composable
private fun AdapterSelector(
    adapters: List<PlatformAdapter>,
    selectedAdapter: PlatformAdapter?,
    onAdapterSelected: (PlatformAdapter) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedIndex = adapters.indexOf(selectedAdapter).coerceAtLeast(0)
    val itemSpacing = AdapterSelectorItemSpacing
    val horizontalPadding = AdapterSelectorPadding

    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(21.dp))
            .background(CardBorder)
            .border(1.dp, CardBorder, RoundedCornerShape(21.dp))
    ) {
        val itemWidth = if (adapters.isNotEmpty()) {
            (maxWidth - horizontalPadding * 2f - itemSpacing * (adapters.size - 1).toFloat()) /
                adapters.size.toFloat()
        } else {
            0.dp
        }
        val selectedOffset by animateDpAsState(
            targetValue = horizontalPadding + (itemWidth + itemSpacing) * selectedIndex.toFloat(),
            animationSpec = spring(
                dampingRatio = 0.76f,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "AdapterSelectorOffset"
        )

        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = selectedOffset)
                .width(itemWidth)
                .height(38.dp)
                .clip(RoundedCornerShape(19.dp))
                .background(Color(0xFF3C3C3E))
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding, vertical = horizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(itemSpacing)
        ) {
            adapters.forEach { adapter ->
                val selected = adapter == selectedAdapter
                val textColor by animateColorAsState(
                    targetValue = if (selected) Color.White else TextMuted,
                    animationSpec = spring(
                        dampingRatio = 1f,
                        stiffness = Spring.StiffnessMediumLow
                    ),
                    label = "AdapterSelectorTextColor"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(19.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onAdapterSelected(adapter) }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    // 字号被系统放大时宁可截断也不折成两行，避免撑破 38dp 高的胶囊。
                    Text(
                        text = adapter.label,
                        color = textColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 14.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                }
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
            color = CardBorder,
            style = Stroke(width = strokeWidth)
        )
        drawArc(
            color = BrandOrange,
            startAngle = -90f,
            sweepAngle = 360f * clampedProgress,
            useCenter = false,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
    }
}
