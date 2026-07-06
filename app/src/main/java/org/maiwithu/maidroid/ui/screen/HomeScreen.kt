package org.maiwithu.maidroid.ui.screen

import android.annotation.SuppressLint
import android.os.Build
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.maiwithu.maidroid.BuildConfig
import org.maiwithu.maidroid.R
import org.maiwithu.maidroid.container.MaiBotContainerConfig
import org.maiwithu.maidroid.ui.theme.MaiDroidTheme
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView

private val HomeBackground = Color(0xFF07090D)
private val HomeOrange = Color(0xFFE8921E)
private val HomeTextBlue = Color(0xFFA0B4C8)
private val HomeMutedBlue = Color(0xFF788CA0)
private val HomeOnline = Color(0xFF64C88C)
private val HomeOffline = Color(0xFFE8A064)
private const val HomeDesignWidth = 412f
private const val HomeDesignHeight = 917f
private val MainSurface = Color(0xFF0F0F0F)
private val PlatformCardSurface = Color(0xFF1C1C1E)
private val PlatformBorder = Color(0xFF2C2C2E)
private val PlatformTextSecondary = Color(0xFF8E8E93)
private val PlatformTextDim = Color(0xFF717171)
private val PlatformOnline = Color(0xFF30D158)
private val PlatformOrange = Color(0xFFE97F0F)
private val GlassSurface = Color(0x802B2B2B)
private val GlassStroke = Color(0x55FFFFFF)
private val StartupTopInset = 48.dp
private const val WebUiLogTag = "MaiDroidWebUi"

private enum class MainTab {
    WebUi,
    Platforms,
    Settings
}

private data class InstalledPlatform(
    val name: String,
    @param:DrawableRes val iconRes: Int,
    val account: String,
    val iconColor: Color,
    val statusColor: Color = PlatformOnline,
    val running: Boolean = true
)

private data class AvailablePlatform(
    val name: String,
    @param:DrawableRes val iconRes: Int,
    val iconColor: Color,
    val provider: String,
    val description: String,
    val tags: List<String>,
    val badge: String? = null,
    val adapters: List<String> = emptyList()
)

private val InstalledPlatforms = listOf(
    InstalledPlatform(
        name = "微信",
        iconRes = R.drawable.ic_platform_wechat,
        account = "野兽先辈 在线",
        iconColor = Color(0xFF07C160)
    ),
    InstalledPlatform(
        name = "Telegram",
        iconRes = R.drawable.ic_platform_telegram,
        account = "Harry Poorter 在线",
        iconColor = Color(0xFF229ED9)
    ),
    InstalledPlatform(
        name = "iMessage",
        iconRes = R.drawable.ic_platform_imessage,
        account = "325799 在线",
        iconColor = Color(0xFF06C755),
        statusColor = PlatformTextSecondary
    )
)

private val AvailablePlatforms = listOf(
    AvailablePlatform(
        name = "QQ",
        iconRes = R.drawable.ic_platform_qq,
        iconColor = Color(0xCC0F3BE9),
        provider = "将由 NapCat 提供服务",
        description = "日常闲聊，水群必选平台",
        tags = listOf("国内", "常用"),
        badge = "最佳适配",
        adapters = listOf("NapCat", "SnowLuma")
    ),
    AvailablePlatform(
        name = "Discord",
        iconRes = R.drawable.ic_platform_discord,
        iconColor = Color(0xCC5865F2),
        provider = "将由 Discord 官方 API 提供服务",
        description = "游戏玩家必选平台",
        tags = listOf("海外", "游戏平台", "常用")
    ),
    AvailablePlatform(
        name = "WhatsApp",
        iconRes = R.drawable.ic_platform_whatsapp,
        iconColor = Color(0xCC25D366),
        provider = "将由 ??? 提供服务",
        description = "实则根本没有做适配",
        tags = listOf("海外", "常用")
    ),
    AvailablePlatform(
        name = "Signal",
        iconRes = R.drawable.ic_platform_signal,
        iconColor = Color(0xCC3A76F0),
        provider = "将由 ??? 提供服务",
        description = "实则根本没有做适配",
        tags = listOf("海外", "常用")
    )
)

@Composable
fun HomeScreen(
    webUiOnline: Boolean,
    versionName: String,
    terminalLogs: List<String> = emptyList(),
    onWakeMai: () -> Unit,
    modifier: Modifier = Modifier
) {
    var dashboardVisible by rememberSaveable { mutableStateOf(false) }
    var wakeRequested by rememberSaveable { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableStateOf(MainTab.WebUi) }
    var showTerminalOutput by remember { mutableStateOf(false) }

    LaunchedEffect(webUiOnline, wakeRequested) {
        if (wakeRequested && webUiOnline) {
            dashboardVisible = true
            selectedTab = MainTab.WebUi
        }
    }

    AnimatedContent(
        targetState = dashboardVisible,
        modifier = modifier
            .fillMaxSize()
            .background(MainSurface),
        transitionSpec = { entryTransition() },
        label = "HomeEntryTransition"
    ) { showDashboard ->
        if (showDashboard) {
            DashboardShell(
                selectedTab = selectedTab,
                webUiOnline = webUiOnline,
                versionName = versionName,
                terminalLogs = terminalLogs,
                onTabSelected = { selectedTab = it },
                onWakeMai = onWakeMai,
                onTerminalDismiss = { showTerminalOutput = false },
                showTerminalOutput = showTerminalOutput,
                onTerminalClick = { showTerminalOutput = true },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            WakeMaiGatePage(
                webUiOnline = webUiOnline,
                versionName = versionName,
                onWakeClick = {
                    if (webUiOnline) {
                        dashboardVisible = true
                        selectedTab = MainTab.WebUi
                    } else {
                        wakeRequested = true
                        onWakeMai()
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun DashboardShell(
    selectedTab: MainTab,
    webUiOnline: Boolean,
    versionName: String,
    terminalLogs: List<String>,
    showTerminalOutput: Boolean,
    onTabSelected: (MainTab) -> Unit,
    onWakeMai: () -> Unit,
    onTerminalClick: () -> Unit,
    onTerminalDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var blurTarget by remember { mutableStateOf<BlurTarget?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MainSurface)
    ) {
        BlurTargetHost(
            modifier = Modifier
                .fillMaxSize()
                .background(MainSurface),
            onTargetChanged = { blurTarget = it }
        ) {
            AnimatedContent(
                targetState = selectedTab,
                modifier = Modifier
                    .fillMaxSize()
                    .background(MainSurface),
                transitionSpec = { tabTransition() },
                label = "HomeTabTransition"
            ) { tab ->
                when (tab) {
                    MainTab.WebUi -> WebUiTabPage(
                        webUiOnline = webUiOnline,
                        onWakeMai = onWakeMai,
                        modifier = Modifier.fillMaxSize()
                    )

                    MainTab.Platforms -> MessagePlatformsPage(modifier = Modifier.fillMaxSize())
                    MainTab.Settings -> SettingsPage(
                        webUiOnline = webUiOnline,
                        versionName = versionName,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        MainBottomNavigation(
            selectedTab = selectedTab,
            blurTarget = blurTarget,
            onTabSelected = onTabSelected,
            onTerminalClick = onTerminalClick,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        if (showTerminalOutput) {
            TerminalOutputDialog(
                logs = terminalLogs,
                onDismiss = onTerminalDismiss
            )
        }
    }
}

@Composable
private fun WakeMaiGatePage(
    webUiOnline: Boolean,
    versionName: String,
    onWakeClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(HomeBackground)
    ) {
        val sx = maxWidth.value / HomeDesignWidth
        val sy = maxHeight.value / HomeDesignHeight
        val textScale = minOf(sx, sy)
        fun x(value: Float): Dp = (value * sx).dp
        fun y(value: Float): Dp = (value * sy).dp
        fun sp(value: Float) = (value * textScale).sp

        Image(
            painter = painterResource(R.drawable.home_background),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .offset(x = x(0f), y = y(-4f))
                .width(x(600f))
                .height(y(789f))
                .graphicsLayer { alpha = 0.5f }
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(y(156.75f))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            HomeBackground.copy(alpha = 0.85f),
                            HomeBackground.copy(alpha = 0f)
                        )
                    )
                )
        )

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(y(384.04f))
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to HomeBackground.copy(alpha = 0f),
                            0.3f to HomeBackground.copy(alpha = 0.8f),
                            0.6f to HomeBackground.copy(alpha = 0.97f),
                            1f to HomeBackground
                        )
                    )
                )
        )

        HudOverlay(modifier = Modifier.fillMaxSize())

        HeaderLabels(
            webUiOnline = webUiOnline,
            versionName = versionName,
            sx = sx,
            sy = sy,
            textScale = textScale
        )

        BootTelemetry(
            sx = sx,
            sy = sy,
            textScale = textScale
        )

        Text(
            text = "麦麦Bot",
            color = Color.White,
            fontSize = sp(48f),
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = sp(4f),
            lineHeight = sp(52f),
            modifier = Modifier
                .offset(x = x(40f), y = y(588f))
                .width(x(220f))
        )

        Text(
            text = "MaiSaka × MaiDroid",
            color = HomeOrange.copy(alpha = 0.85f),
            fontSize = sp(16f),
            fontWeight = FontWeight.Bold,
            letterSpacing = sp(6f),
            lineHeight = sp(20f),
            modifier = Modifier
                .offset(x = x(39f), y = y(643f))
                .width(x(315f))
        )

        Box(
            modifier = Modifier
                .offset(x = x(39f), y = y(667f))
                .width(x(303f))
                .height(y(3f))
                .background(HomeOrange.copy(alpha = 0.25f))
        )

        Text(
            text = "一个正在努力成为人类的BOT。",
            color = Color(0xFFB4C3D7).copy(alpha = 0.7f),
            fontSize = sp(12f),
            fontWeight = FontWeight.Bold,
            letterSpacing = sp(1f),
            lineHeight = sp(16f),
            modifier = Modifier
                .offset(x = x(40f), y = y(679f))
                .width(x(240f))
        )

        WakeMaiButton(
            onClick = onWakeClick,
            modifier = Modifier
                .offset(x = x(38f), y = y(706f))
                .width(x(335.7f))
                .height(y(59.6f)),
            sx = sx,
            textScale = textScale
        )

        Text(
            text = if (webUiOnline) {
                "MAIBOT READY  ◇  TAP TO CONTINUE"
            } else {
                "WEBUI OFFLINE  ◇  TAP TO WAKE"
            },
            color = Color(0xFF96A5B9).copy(alpha = 0.4f),
            fontSize = sp(7.05f),
            letterSpacing = sp(2f),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = y(118f))
                .width(x(335.7f))
        )
    }
}

private fun AnimatedContentTransitionScope<Boolean>.entryTransition() =
    (fadeIn(animationSpec = tween(220)) +
        slideInHorizontally(animationSpec = tween(220)) { fullWidth ->
            if (targetState) fullWidth / 5 else -fullWidth / 5
        }) togetherWith
        (fadeOut(animationSpec = tween(160)) +
            slideOutHorizontally(animationSpec = tween(200)) { fullWidth ->
                if (targetState) -fullWidth / 5 else fullWidth / 5
            }) using SizeTransform(clip = false)

private fun AnimatedContentTransitionScope<MainTab>.tabTransition() =
    (fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing)) +
        slideInHorizontally(animationSpec = tween(260, easing = FastOutSlowInEasing)) { fullWidth ->
            val direction = targetState.ordinal - initialState.ordinal
            if (direction >= 0) fullWidth / 5 else -fullWidth / 5
        }) togetherWith
        (fadeOut(animationSpec = tween(180, easing = FastOutSlowInEasing)) +
            slideOutHorizontally(animationSpec = tween(240, easing = FastOutSlowInEasing)) { fullWidth ->
                val direction = targetState.ordinal - initialState.ordinal
                if (direction >= 0) -fullWidth / 5 else fullWidth / 5
            }) using SizeTransform(clip = false)

@Composable
private fun BlurTargetHost(
    onTargetChanged: (BlurTarget?) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val parentComposition = rememberCompositionContext()
    val currentContent by rememberUpdatedState(content)

    AndroidView(
        factory = { context ->
            BlurTarget(context).apply {
                clipChildren = false
                clipToPadding = false

                addView(
                    ComposeView(context).apply {
                        setParentCompositionContext(parentComposition)
                        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                        setContent {
                            currentContent()
                        }
                    },
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }
        },
        update = { target ->
            onTargetChanged(target)
        },
        modifier = modifier
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebUiTabPage(
    webUiOnline: Boolean,
    onWakeMai: () -> Unit,
    modifier: Modifier = Modifier,
    url: String = MaiBotContainerConfig.WEB_UI_URL
) {
    var webViewError by remember { mutableStateOf<String?>(null) }
    var reloadToken by remember { mutableStateOf(0) }
    var webViewHasSize by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MainSurface)
    ) {
        AndroidView(
            factory = { context ->
                if (BuildConfig.DEBUG) {
                    WebView.setWebContentsDebuggingEnabled(true)
                }
                WebView(context).apply {
                    setBackgroundColor(android.graphics.Color.BLACK)
                    overScrollMode = WebView.OVER_SCROLL_NEVER
                    isFocusable = true
                    isFocusableInTouchMode = true
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        settings.mixedContentMode =
                            android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                            Log.d(
                                WebUiLogTag,
                                "console ${consoleMessage.messageLevel()}: ${consoleMessage.message()} " +
                                    "(${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})"
                            )
                            return super.onConsoleMessage(consoleMessage)
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, pageUrl: String) {
                            webViewError = null
                            patchBrokenViewportUnitsIfNeeded(view)
                            super.onPageFinished(view, pageUrl)
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError
                        ) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && request.isForMainFrame) {
                                webViewError = "${error.errorCode}: ${error.description}"
                            }
                            super.onReceivedError(view, request, error)
                        }
                    }
                }
            },
            update = { webView ->
                val loadRequest = "$url#$reloadToken"
                if (webViewHasSize && webView.tag != loadRequest) {
                    webView.tag = loadRequest
                    webViewError = null
                    Log.d(WebUiLogTag, "load $url at ${webView.width}x${webView.height}")
                    webView.loadUrl(url)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    webViewHasSize = size.width > 0 && size.height > 0
                }
        )

        if (webViewError != null) {
            WebUiStatusBanner(
                message = "WebView 加载失败：$webViewError",
                action = "重试",
                onAction = {
                    webViewError = null
                    reloadToken += 1
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = StartupTopInset + 8.dp, start = 16.dp, end = 56.dp)
            )
        } else if (!webUiOnline) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = StartupTopInset + 8.dp, start = 16.dp, end = 56.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .border(1.dp, PlatformBorder, RoundedCornerShape(22.dp))
                    .background(PlatformCardSurface.copy(alpha = 0.92f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(HomeOffline)
                )

                Spacer(modifier = Modifier.width(10.dp))

                Text(
                    text = "WebUI 离线",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                Text(
                    text = "唤醒",
                    color = PlatformOrange,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onWakeMai
                    )
                )
            }
        }

        WebUiRefreshButton(
            onClick = {
                webViewError = null
                reloadToken += 1
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = StartupTopInset + 8.dp, end = 16.dp)
        )
    }
}

private fun patchBrokenViewportUnitsIfNeeded(webView: WebView) {
    webView.evaluateJavascript(
        """
        (() => {
            const probe = document.createElement('div');
            probe.style.cssText = 'position:absolute;left:-9999px;top:-9999px;width:1px;height:100vh;pointer-events:none';
            document.body.appendChild(probe);
            const vh = probe.getBoundingClientRect().height;
            probe.remove();

            const root = document.getElementById('root');
            const rootHeight = root ? root.getBoundingClientRect().height : -1;
            const viewportHeight = window.innerHeight || document.documentElement.clientHeight || 0;
            const shouldPatch = viewportHeight > 0 && (vh < 1 || rootHeight < 1);
            if (!shouldPatch) {
                return JSON.stringify({ patched: false, vh, viewportHeight, rootHeight });
            }

            let style = document.getElementById('maidroid-webview-viewport-fix');
            if (!style) {
                style = document.createElement('style');
                style.id = 'maidroid-webview-viewport-fix';
                document.head.appendChild(style);
            }
            style.textContent =
                'html,body,#root{width:100%!important;height:' + viewportHeight + 'px!important;' +
                'min-height:' + viewportHeight + 'px!important;overflow:auto!important}' +
                '.min-h-screen{min-height:' + viewportHeight + 'px!important}' +
                '.h-screen{height:' + viewportHeight + 'px!important}';
            window.dispatchEvent(new Event('resize'));
            return JSON.stringify({ patched: true, vh, viewportHeight, rootHeight });
        })()
        """.trimIndent()
    ) { result ->
        Log.d(WebUiLogTag, "viewport probe $result")
    }
}

@Composable
private fun WebUiRefreshButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(32.dp)
            .shadow(
                elevation = 10.dp,
                shape = CircleShape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.28f),
                spotColor = Color.Black.copy(alpha = 0.28f)
            )
            .clip(CircleShape)
            .border(1.dp, GlassStroke, CircleShape)
            .background(GlassSurface)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.Refresh,
            contentDescription = "刷新 WebUI",
            tint = Color.White,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun WebUiStatusBanner(
    message: String,
    action: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .border(1.dp, PlatformBorder, RoundedCornerShape(22.dp))
            .background(PlatformCardSurface.copy(alpha = 0.94f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(HomeOffline)
        )

        Spacer(modifier = Modifier.width(10.dp))

        Text(
            text = message,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 16.sp,
            modifier = Modifier.weight(1f)
        )

        Text(
            text = action,
            color = PlatformOrange,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onAction
            )
        )
    }
}

@Composable
private fun MessagePlatformsPage(modifier: Modifier = Modifier) {
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

@Composable
private fun SettingsPage(
    webUiOnline: Boolean,
    versionName: String,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MainSurface),
        contentPadding = PaddingValues(top = 72.dp, start = 16.dp, end = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "设置",
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 34.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }

        item {
            SettingsInfoCard(
                title = "WebUI 状态",
                value = if (webUiOnline) "在线" else "离线",
                accent = if (webUiOnline) PlatformOnline else HomeOffline
            )
        }

        item {
            SettingsInfoCard(
                title = "应用版本",
                value = "v$versionName",
                accent = PlatformOrange
            )
        }

        item {
            SettingsInfoCard(
                title = "消息平台",
                value = "${InstalledPlatforms.size} 个平台正在运行",
                accent = PlatformOnline
            )
        }
    }
}

@Composable
private fun SettingsInfoCard(
    title: String,
    value: String,
    accent: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(84.dp)
            .clip(RoundedCornerShape(24.dp))
            .border(1.dp, PlatformBorder, RoundedCornerShape(24.dp))
            .background(PlatformCardSurface)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(accent)
        )

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = PlatformTextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 15.sp
            )

            Spacer(modifier = Modifier.height(5.dp))

            Text(
                text = value,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 22.sp
            )
        }
    }
}

@Composable
private fun TerminalOutputDialog(
    logs: List<String>,
    onDismiss: () -> Unit
) {
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.lastIndex)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "终端输出",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 22.sp
            )
        },
        text = {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black.copy(alpha = 0.38f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (logs.isEmpty()) {
                    item {
                        Text(
                            text = "暂无命令输出。平台安装或 MaiBot 启动后会显示在这里。",
                            color = PlatformTextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                } else {
                    items(logs) { line ->
                        Text(
                            text = line.ifBlank { " " },
                            color = PlatformTextSecondary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                }
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
private fun MainBottomNavigation(
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

@Composable
private fun HeaderLabels(
    webUiOnline: Boolean,
    versionName: String,
    sx: Float,
    sy: Float,
    textScale: Float
) {
    fun x(value: Float): Dp = (value * sx).dp
    fun y(value: Float): Dp = (value * sy).dp
    fun sp(value: Float) = (value * textScale).sp

    Text(
        text = "MAISAKA",
        color = HomeOrange.copy(alpha = 0.9f),
        fontSize = sp(8.62f),
        letterSpacing = sp(4f),
        modifier = Modifier
            .offset(x = x(31f), y = y(44f))
            .width(x(90f))
    )

    Text(
        text = "牢麦待机中",
        color = HomeTextBlue.copy(alpha = 0.7f),
        fontSize = sp(7.05f),
        letterSpacing = sp(1.5f),
        modifier = Modifier
            .offset(x = x(31f), y = y(58f))
            .width(x(96f))
    )

    Text(
        text = "v$versionName",
        color = HomeMutedBlue.copy(alpha = 0.6f),
        fontSize = sp(7.84f),
        textAlign = TextAlign.End,
        modifier = Modifier
            .offset(x = x(320f), y = y(45f))
            .width(x(58f))
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
        modifier = Modifier
            .offset(x = x(300f), y = y(56f))
            .width(x(78f))
    ) {
        Box(
            modifier = Modifier
                .size(x(3.9f))
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(if (webUiOnline) HomeOnline else HomeOffline)
        )
        Spacer(modifier = Modifier.width(x(4f)))
        Text(
            text = if (webUiOnline) "ONLINE" else "OFFLINE",
            color = if (webUiOnline) HomeOnline.copy(alpha = 0.8f) else HomeOffline.copy(alpha = 0.82f),
            fontSize = sp(7.05f),
            letterSpacing = sp(2f)
        )
    }
}

@Composable
private fun BootTelemetry(
    sx: Float,
    sy: Float,
    textScale: Float
) {
    fun x(value: Float): Dp = (value * sx).dp
    fun y(value: Float): Dp = (value * sy).dp
    fun sp(value: Float) = (value * textScale).sp

    Text(
        text = "114°51'N  41°91'E",
        color = Color(0xFF788CAA).copy(alpha = 0.4f),
        fontSize = sp(6.27f),
        letterSpacing = sp(1f),
        modifier = Modifier
            .offset(x = x(16.8f), y = y(352.7f))
            .width(x(124f))
    )

    Text(
        text = "MAI.BOOT  //  AWAITING INPUT",
        color = Color(0xFFC8A064).copy(alpha = 0.35f),
        fontSize = sp(6.27f),
        letterSpacing = sp(1.5f),
        modifier = Modifier
            .offset(x = x(16.8f), y = y(362.1f))
            .width(x(180f))
    )
}

@Composable
private fun WakeMaiButton(
    onClick: () -> Unit,
    modifier: Modifier,
    sx: Float,
    textScale: Float
) {
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .shadow(
                elevation = (7f * textScale).dp,
                shape = WakeButtonShape,
                ambientColor = HomeOrange.copy(alpha = 0.28f),
                spotColor = HomeOrange.copy(alpha = 0.5f)
            )
            .clip(WakeButtonShape)
            .background(HomeOrange)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .drawWithContent {
                drawContent()
                drawLine(
                    color = Color.White.copy(alpha = 0.18f),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx()
                )
                drawLine(
                    color = Color.Black.copy(alpha = 0.22f),
                    start = Offset(0f, size.height),
                    end = Offset(size.width - 18.dp.toPx(), size.height),
                    strokeWidth = 1.dp.toPx()
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = (16f * sx).dp)
                .size((30f * textScale).dp)
        )

        Text(
            text = "唤醒麦麦",
            color = Color.White,
            fontSize = (24f * textScale).sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (4f * textScale).sp,
            lineHeight = (24f * textScale).sp
        )
    }
}

private val WakeButtonShape = GenericShape { size, _ ->
    val radius = size.height * 0.1f
    val cut = size.height * 0.3f
    moveTo(radius, 0f)
    lineTo(size.width, 0f)
    lineTo(size.width, size.height - cut)
    lineTo(size.width - cut, size.height)
    lineTo(radius, size.height)
    quadraticTo(0f, size.height, 0f, size.height - radius)
    lineTo(0f, radius)
    quadraticTo(0f, 0f, radius, 0f)
    close()
}

@Composable
private fun HudOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val sx = size.width / HomeDesignWidth
        val sy = size.height / HomeDesignHeight
        val scale = minOf(sx, sy)
        fun x(value: Float) = value * sx
        fun y(value: Float) = value * sy
        fun stroke(value: Float) = (value * scale).coerceAtLeast(1f)

        fun line(
            startX: Float,
            startY: Float,
            endX: Float,
            endY: Float,
            alpha: Float,
            color: Color = HomeOrange,
            width: Float = 0.78f
        ) {
            drawLine(
                color = color.copy(alpha = alpha),
                start = Offset(x(startX), y(startY)),
                end = Offset(x(endX), y(endY)),
                strokeWidth = stroke(width)
            )
        }

        fun diamond(
            left: Float,
            top: Float,
            side: Float,
            alpha: Float,
            fillAlpha: Float = 0f,
            width: Float = 1f
        ) {
            val cx = x(left + side / 2f)
            val cy = y(top + side / 2f)
            val half = side * 0.36f * scale
            val path = Path().apply {
                moveTo(cx, cy - half)
                lineTo(cx + half, cy)
                lineTo(cx, cy + half)
                lineTo(cx - half, cy)
                close()
            }
            if (fillAlpha > 0f) {
                drawPath(path = path, color = HomeOrange.copy(alpha = fillAlpha))
            }
            drawPath(
                path = path,
                color = HomeOrange.copy(alpha = alpha),
                style = Stroke(width = stroke(width))
            )
        }

        line(16.8f, 17.24f, 35.1f, 17.24f, 0.6f)
        line(16.8f, 17.24f, 16.8f, 36.05f, 0.6f)
        line(376.9f, 17.24f, 395.2f, 17.24f, 0.6f)
        line(394.45f, 17.24f, 394.45f, 36.05f, 0.6f)
        line(31f, 72f, 381.96f, 72f, 0.3f)

        line(16.8f, 109.7f, 16.8f, 344.9f, 0.2f)
        line(394.45f, 125.4f, 394.45f, 344.9f, 0.15f, Color(0xFFC8C8DC))
        listOf(313.5f, 344.85f, 407.56f, 438.91f).forEach { y ->
            line(0f, y, 412f, y, 0.04f, Color(0xFFB4C8DC))
        }

        line(17f, 899f, 38.36f, 899f, 0.45f)
        line(17f, 877f, 17f, 899f, 0.45f)
        line(374f, 899f, 395.36f, 899f, 0.45f)
        line(394.6f, 877f, 394.6f, 899f, 0.45f)

        diamond(152.6f, 89.7f, 8.75f, 0.5f, width = 1.5f)
        diamond(42f, 243.25f, 15.31f, 0.55f, fillAlpha = 0.1f, width = 1.5f)
        diamond(350.96f, 152.98f, 7.66f, 0.35f)
        diamond(22.89f, 372.97f, 6.56f, 0.3f)
        diamond(381.48f, 347.3f, 10.94f, 0.4f)
        diamond(206f, 83.52f, 5.47f, 0.25f)
        diamond(61.04f, 465.94f, 8.75f, 0.3f)
        diamond(19.07f, 574.76f, 19.69f, 0.9f, fillAlpha = 0.25f, width = 2f)
        diamond(383.01f, 26.74f, 6.56f, 0.5f, fillAlpha = 0.5f, width = 0f)
    }
}

@Preview(
    name = "Home / Online",
    showBackground = true,
    backgroundColor = 0xFF07090D,
    widthDp = 412,
    heightDp = 917
)
@Composable
private fun HomeScreenOnlinePreview() {
    MaiDroidTheme {
        HomeScreen(
            webUiOnline = true,
            versionName = "1.0",
            onWakeMai = {}
        )
    }
}

@Preview(
    name = "Home / Offline",
    showBackground = true,
    backgroundColor = 0xFF07090D,
    widthDp = 412,
    heightDp = 917
)
@Composable
private fun HomeScreenOfflinePreview() {
    MaiDroidTheme {
        HomeScreen(
            webUiOnline = false,
            versionName = "1.0",
            onWakeMai = {}
        )
    }
}

@Preview(
    name = "Home / Tall Ratio",
    showBackground = true,
    backgroundColor = 0xFF07090D,
    widthDp = 360,
    heightDp = 900
)
@Composable
private fun HomeScreenTallRatioPreview() {
    MaiDroidTheme {
        HomeScreen(
            webUiOnline = true,
            versionName = "1.0",
            onWakeMai = {}
        )
    }
}

@Preview(
    name = "Home / Short Ratio",
    showBackground = true,
    backgroundColor = 0xFF07090D,
    widthDp = 412,
    heightDp = 760
)
@Composable
private fun HomeScreenShortRatioPreview() {
    MaiDroidTheme {
        HomeScreen(
            webUiOnline = false,
            versionName = "1.0",
            onWakeMai = {}
        )
    }
}
