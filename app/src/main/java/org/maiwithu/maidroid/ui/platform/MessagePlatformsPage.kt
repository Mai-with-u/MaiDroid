package org.maiwithu.maidroid.ui.platform

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.maiwithu.maidroid.WebUiActivity
import org.maiwithu.maidroid.appDependencies
import org.maiwithu.maidroid.platform.napcat.NapCatPlatformState
import org.maiwithu.maidroid.platform.qqofficial.QqOfficialPlatformState
import org.maiwithu.maidroid.platform.qqofficial.QqOfficialSaveResult
import org.maiwithu.maidroid.ui.component.LocalBottomNavigationClearance
import org.maiwithu.maidroid.ui.component.horizontalSlideTransition
import org.maiwithu.maidroid.ui.component.statusBarTopInset
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardBorder
import org.maiwithu.maidroid.ui.theme.CardSurface
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.GlassButtonSurface
import org.maiwithu.maidroid.ui.theme.PageBackground
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes
import kotlin.math.ceil
import kotlin.math.floor
import androidx.compose.foundation.lazy.grid.items as gridItems

private const val PlatformGridMinCardWidthDp = 272f
private const val PlatformGridMaxCardWidthDp = 420f
private const val PlatformGridSpacingDp = 12f
private val PlatformGridHorizontalPadding = 16.dp

/** 消息平台页的三种排版，按窗口大小选，见 [messagePlatformsLayout]。 */
internal enum class PlatformsLayout {
    /** 手机竖屏等窄窗口：标题、已连接、发现从上到下排成分组列表，整页一起滚动。 */
    List,

    /** 手机横屏等矮窗口：左栏标题和已连接，右栏发现，两栏各自滚动。 */
    TwoPane,

    /** 平板：已安装横排小卡片，发现用大卡片网格，标题和分类固定在顶部。 */
    Grid
}

// 手机横屏约 360dp 高，放不下平板那套固定在顶部的标题、已安装和分类（合计约 320dp），改左右两栏；
// 两栏至少 520dp 宽，右栏才放得下“发现”标题加四个分类按钮。手机竖屏约 360~420dp 宽，用单列列表。
private const val PlatformTwoPaneMaxHeightDp = 560f
private const val PlatformTwoPaneMinWidthDp = 520f
private const val PlatformListMaxWidthDp = 600f

internal fun messagePlatformsLayout(widthDp: Float, heightDp: Float): PlatformsLayout = when {
    heightDp < PlatformTwoPaneMaxHeightDp && widthDp >= PlatformTwoPaneMinWidthDp -> PlatformsLayout.TwoPane
    widthDp < PlatformListMaxWidthDp -> PlatformsLayout.List
    else -> PlatformsLayout.Grid
}

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

/** 发现列表实际显示的平台：先按分类筛（“全部”不筛），再按关键字匹配名称和简介，不区分大小写。 */
internal fun filterPlatforms(
    platforms: List<AvailablePlatform>,
    category: String,
    query: String
): List<AvailablePlatform> {
    val keyword = query.trim()
    return platforms.filter { platform ->
        val inCategory = category == "全部" || category in platform.tags
        val matchesKeyword = keyword.isEmpty() ||
            platform.name.contains(keyword, ignoreCase = true) ||
            platform.description.contains(keyword, ignoreCase = true)
        inCategory && matchesKeyword
    }
}

/** 平台卡片和列表行上各个按钮的回调，三种排版共用，省得每一层都传一长串参数。 */
private class PlatformActions(
    val onInstalledSettingsClick: (InstalledPlatform) -> Unit,
    val onAccessMethodSelected: (AvailablePlatform, AccessMethod) -> Unit,
    val onNapCatInstall: () -> Unit,
    val onNapCatSettings: () -> Unit,
    val onQqOfficialSettings: () -> Unit,
    val onUnsupportedInstall: () -> Unit
)

@Composable
internal fun MessagePlatformsPage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val napCatManager = remember(context) { context.appDependencies.napCat }
    val napCatState by napCatManager.state.collectAsState()
    val uiScope = rememberCoroutineScope()
    val qqOfficialManager = remember(context) { context.appDependencies.qqOfficial }
    val qqOfficialState by qqOfficialManager.state.collectAsState()
    LaunchedEffect(napCatManager) {
        napCatManager.refresh()
    }
    LaunchedEffect(qqOfficialManager) {
        qqOfficialManager.refresh()
    }
    var selectedCategory by remember { mutableStateOf("全部") }
    // 右上角搜索：点开后标题下出现搜索框，按名称和简介筛选当前分类；再点一次或按返回键收起并清空关键字。
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val closeSearch = {
        searchOpen = false
        searchQuery = ""
    }
    BackHandler(enabled = searchOpen, onBack = closeSearch)
    // QQ 卡片默认选中最左边的官方机器人；用户切到 NapCat 等其他选项后，本次页面内记住所选。
    var selectedAccessMethods by remember { mutableStateOf(mapOf("QQ" to AccessMethod.QqOfficial)) }
    // 弹窗开关在 Activity 重建后保留：用户切到 QQ 扫码时系统可能因配置变化重建界面，回来弹窗还在。
    var showNapCatQrCode by rememberSaveable { mutableStateOf(false) }
    var showNapCatConfig by rememberSaveable { mutableStateOf(false) }
    var showQqLogoutConfirm by rememberSaveable { mutableStateOf(false) }
    var showQqOfficialConfig by rememberSaveable { mutableStateOf(false) }
    val categories = listOf("全部", "国内", "海外", "其他")
    val installedPlatforms = installedPlatformCards(napCatState, qqOfficialState)
    val runningCount = installedPlatforms.count { it.running }
    val onSearchClick: () -> Unit = { if (searchOpen) closeSearch() else searchOpen = true }

    val actions = PlatformActions(
        onInstalledSettingsClick = { platform ->
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
        },
        onAccessMethodSelected = { platform, accessMethod ->
            selectedAccessMethods = selectedAccessMethods + (platform.name to accessMethod)
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
            Toast.makeText(context, "这个平台暂未适配，敬请期待", Toast.LENGTH_SHORT).show()
        }
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(PageBackground)
    ) {
        val statusBarTop = statusBarTopInset()
        val bottomClearance = LocalBottomNavigationClearance.current
        val pageWidth = maxWidth
        // Grow the generous portrait spacing back gradually instead of jumping
        // at a breakpoint when a landscape window is resized.
        val verticalExpansion = ((maxHeight.value - 480f) / (720f - 480f))
            .coerceIn(0f, 1f)
        // The previous 64dp top slot assumed a roughly 24dp status bar. Keep its
        // visual breathing room separate from the inset reported by the system.
        val topSpacing = 8.dp + 32.dp * verticalExpansion
        val sectionSpacing = 8.dp + 16.dp * verticalExpansion

        // 手机两种排版共用的三块：页头（带搜索框）、已连接、发现。
        val listHeader: @Composable (Boolean) -> Unit = { compact ->
            Column {
                MessagePlatformsHeader(
                    runningCount = runningCount,
                    searchOpen = searchOpen,
                    onSearchClick = onSearchClick,
                    horizontalPadding = 0.dp,
                    compact = compact
                )
                if (searchOpen) {
                    Spacer(modifier = Modifier.height(12.dp))
                    PlatformSearchField(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        horizontalPadding = 0.dp
                    )
                }
            }
        }
        val installedSection: @Composable () -> Unit = {
            PlatformSection(title = "已连接") {
                if (installedPlatforms.isEmpty()) {
                    EmptyInstalledPlatformRow()
                }
                installedPlatforms.forEachIndexed { index, platform ->
                    if (index > 0) PlatformRowDivider()
                    InstalledPlatformRow(
                        platform = platform,
                        onSettingsClick = { actions.onInstalledSettingsClick(platform) }
                    )
                }
            }
        }
        // 竖屏分类按钮放在“发现”标题下面；横屏右栏够宽，放在标题右边省一行。
        val discoverSection: @Composable (Boolean) -> Unit = { chipsBesideTitle ->
            val chips: @Composable () -> Unit = {
                CompactCategoryChips(
                    categories = categories,
                    selectedCategory = selectedCategory,
                    onCategorySelected = { selectedCategory = it }
                )
            }
            val visiblePlatforms = filterPlatforms(AvailablePlatforms, selectedCategory, searchQuery)
            PlatformSection(
                title = "发现",
                titleTrailing = if (chipsBesideTitle) chips else null,
                belowTitle = if (chipsBesideTitle) null else chips
            ) {
                if (visiblePlatforms.isEmpty()) {
                    EmptyPlatformRow()
                }
                visiblePlatforms.forEachIndexed { index, platform ->
                    if (index > 0) PlatformRowDivider()
                    key(platform.name) {
                        PlatformRow(
                            platform = platform,
                            selectedAccessMethod = selectedAccessMethods[platform.name] ?: platform.accessMethods.firstOrNull(),
                            napCatState = napCatState,
                            qqOfficialState = qqOfficialState,
                            onAccessMethodSelected = { accessMethod -> actions.onAccessMethodSelected(platform, accessMethod) },
                            onNapCatInstall = actions.onNapCatInstall,
                            onNapCatSettings = actions.onNapCatSettings,
                            onQqOfficialSettings = actions.onQqOfficialSettings,
                            onUnsupportedInstall = actions.onUnsupportedInstall
                        )
                    }
                }
            }
        }

        when (messagePlatformsLayout(maxWidth.value, maxHeight.value)) {
            PlatformsLayout.List -> {
                // 手机竖屏：页头 -> 已连接 -> 发现，整页一起滚动，底部给浮动导航栏留出空间。
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = PlatformGridHorizontalPadding)
                        .padding(top = statusBarTop + topSpacing, bottom = bottomClearance),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    listHeader(false)
                    installedSection()
                    discoverSection(false)
                }
            }

            PlatformsLayout.TwoPane -> {
                // 手机横屏：左栏页头和已连接，右栏发现；两栏各自滚动，一屏能看到全部平台。
                val leftPaneWidth = (pageWidth * 0.4f).coerceIn(220.dp, 320.dp)
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = statusBarTop)
                ) {
                    Column(
                        modifier = Modifier
                            .width(leftPaneWidth)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(start = PlatformGridHorizontalPadding, end = 8.dp)
                            .padding(top = topSpacing, bottom = bottomClearance),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        listHeader(true)
                        installedSection()
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(start = 8.dp, end = PlatformGridHorizontalPadding)
                            .padding(top = topSpacing, bottom = bottomClearance)
                    ) {
                        discoverSection(true)
                    }
                }
            }

            PlatformsLayout.Grid -> {
                // 平板：标题、已安装、分类固定在顶部，下面的平台网格单独滚动，切分类时整块滑动。
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PageBackground)
                        .padding(top = statusBarTop)
                ) {
                    Spacer(modifier = Modifier.height(topSpacing))
                    MessagePlatformsHeader(
                        runningCount = runningCount,
                        searchOpen = searchOpen,
                        onSearchClick = onSearchClick
                    )
                    if (searchOpen) {
                        Spacer(modifier = Modifier.height(12.dp))
                        PlatformSearchField(query = searchQuery, onQueryChange = { searchQuery = it })
                    }
                    Spacer(modifier = Modifier.height(sectionSpacing))
                    InstalledPlatformsRow(
                        platforms = installedPlatforms,
                        onSettingsClick = actions.onInstalledSettingsClick
                    )
                    Spacer(modifier = Modifier.height(sectionSpacing))
                    DiscoverHeader(
                        platformCount = filterPlatforms(AvailablePlatforms, selectedCategory, searchQuery).size,
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
                        val visiblePlatforms = remember(category, searchQuery) {
                            filterPlatforms(AvailablePlatforms, category, searchQuery)
                        }

                        PlatformGrid(
                            platforms = visiblePlatforms,
                            selectedAccessMethods = selectedAccessMethods,
                            napCatState = napCatState,
                            qqOfficialState = qqOfficialState,
                            actions = actions
                        )
                    }
                }
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
private fun PlatformGrid(
    platforms: List<AvailablePlatform>,
    selectedAccessMethods: Map<String, AccessMethod>,
    napCatState: NapCatPlatformState,
    qqOfficialState: QqOfficialPlatformState,
    actions: PlatformActions
) {
    val bottomClearance = LocalBottomNavigationClearance.current

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
                        napCatState = napCatState,
                        qqOfficialState = qqOfficialState,
                        onAccessMethodSelected = { accessMethod -> actions.onAccessMethodSelected(platform, accessMethod) },
                        onNapCatInstall = actions.onNapCatInstall,
                        onNapCatSettings = actions.onNapCatSettings,
                        onQqOfficialSettings = actions.onQqOfficialSettings,
                        onUnsupportedInstall = actions.onUnsupportedInstall
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
private fun MessagePlatformsHeader(
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
                fontSize = if (compact) 27.sp else TextSizes.Display,
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
            .border(1.dp, GlassButtonSurface, CircleShape)
            .background(GlassButtonSurface)
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

@Composable
private fun PlatformSearchField(
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

@Composable
private fun InstalledPlatformsRow(
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

@Composable
private fun DiscoverHeader(
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
            .border(
                width = 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(Corners.Chip)
            )
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
            fontWeight = FontWeight.Bold,
            lineHeight = if (compact) 18.sp else 20.sp
        )
    }
}
