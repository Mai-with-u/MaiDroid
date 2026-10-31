package org.maiwithu.maidroid.ui.home

import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.maiwithu.maidroid.BuildConfig
import org.maiwithu.maidroid.appDependencies
import org.maiwithu.maidroid.ui.component.statusBarTopInset
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardBorder
import org.maiwithu.maidroid.ui.theme.CardSurface
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.GlassStroke
import org.maiwithu.maidroid.ui.theme.GlassSurface
import org.maiwithu.maidroid.ui.theme.PageBackground
import org.maiwithu.maidroid.ui.theme.StatusWarning
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes
import org.maiwithu.maidroid.webui.MaiBotWebUi
import org.maiwithu.maidroid.webui.WebUiChromeClient
import org.maiwithu.maidroid.webui.WebUiFileChooser
import org.maiwithu.maidroid.webui.WebUiPageBackground
import org.maiwithu.maidroid.webui.WebUiWebViewClient
import org.maiwithu.maidroid.webui.createWebUiWebView

/** WebUI 页签的 WebView 及其加载状态；放在主页外壳里记住，切换标签时 WebView 不销毁。 */
internal class WebUiTabState internal constructor(
    val webView: WebView,
    val fileChooser: WebUiFileChooser,
    val webViewClient: WebUiWebViewClient
) {
    var webViewError by mutableStateOf<String?>(null)
    var reloadToken by mutableIntStateOf(0)
    var webViewHasSize by mutableStateOf(false)

    /** 网页自己的底色（见 WebUiPageBackground）；还没报上来或加载失败时为 null。横屏时涂在侧栏底下让出的那一条。 */
    var pageBackground by mutableStateOf<Color?>(null)

    /** 清掉错误提示并重新加载：换一个加载令牌，页签的 update 看到令牌变了就按当前尺寸重新 loadUrl。 */
    fun reload() {
        webViewError = null
        reloadToken += 1
    }
}

/** 创建 WebUI 页签的 WebView（debug 包允许 Chrome 远程调试），离开主页时销毁。 */
@Composable
internal fun rememberWebUiTabState(): WebUiTabState {
    val context = LocalContext.current
    val state = remember(context) {
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        val fileChooser = WebUiFileChooser(MaiBotWebUi.LOG_TAG)

        // 回调里要改页签状态，状态里又要持有 WebView 和 client：先建它们，回调经 holder 找到稍后建好的状态。
        var tabState: WebUiTabState? = null

        val webView = createWebUiWebView(context).apply {
            // Compose 的 AndroidView 把没有布局参数的子视图加进来时，默认补 WRAP_CONTENT。
            // WebView 看到 WRAP_CONTENT 高度会进入"高度跟随内容"模式，把页面布局高度强制为 0，
            // 导致 vh/dvh 恒为 0、h-[100dvh] 之类的容器塌成空白。先显式声明铺满父视图，
            // 让 WebView 按固定高度排版，vh 才等于真实视口高度。
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            overScrollMode = WebView.OVER_SCROLL_NEVER
            isFocusable = true
            isFocusableInTouchMode = true
            webChromeClient = WebUiChromeClient(
                logTag = MaiBotWebUi.LOG_TAG,
                fileChooser = fileChooser,
                onPageBackgroundColor = { argb -> tabState?.pageBackground = Color(argb) }
            )
        }

        val webViewClient = WebUiWebViewClient(
            logTag = MaiBotWebUi.LOG_TAG,
            // APP 底栏在 WebUI 页签默认收成右下角的小按钮（横屏时是左侧悬浮的侧栏，网页已让出它的宽度），
            // 网页底部不用再给它留白；展开时底栏连同遮罩一起盖在网页上，不需要网页让位。
            // 加载成功 -> 清掉错误提示，注入底色脚本，网页随即报上底色（之后换主题再报）；加载失败 -> 底色作废。
            onPageFinishedCallback = { view, _ ->
                tabState?.webViewError = null
                view.evaluateJavascript(WebUiPageBackground.watchScript, null)
            },
            onMainFrameError = { _, error ->
                tabState?.webViewError = "${error.errorCode}: ${error.description}"
                tabState?.pageBackground = null
            }
        )
        webView.webViewClient = webViewClient
        WebUiTabState(webView, fileChooser, webViewClient).also { tabState = it }
    }

    // 网页点上传按钮 -> fileChooser 用这个 launcher 打开系统选文件页 -> 选完回到这里，把结果交回网页。
    val fileChooserLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        state.fileChooser.onResult(result.resultCode, result.data)
    }
    SideEffect {
        state.fileChooser.launcher = fileChooserLauncher
    }

    DisposableEffect(state) {
        onDispose {
            // 先把没回复的选文件请求按取消结束，再销毁 WebView。
            state.fileChooser.launcher = null
            state.fileChooser.cancelPending()
            state.webView.destroy()
        }
    }

    return state
}

/**
 * WebUI 页签：嵌入式加载 MaiBot WebUI（带访问令牌自动登录），WebView 拿到非零尺寸后才开始加载。
 * 每次加载前按 WebView 尺寸决定排版：横屏按桌面版（布局宽度至少 1040），竖屏按手机版。
 * 顶部按情况显示“加载失败，重试”或“WebUI 离线，唤醒”；竖屏时右上角是刷新按钮，横屏时刷新按钮在左侧导航栏里。
 * startClearance：横屏时左侧悬浮侧栏的宽度，网页和横幅都让开它，让出的那一条涂网页自己的底色。
 */
@Composable
internal fun WebUiTabPage(
    webUiOnline: Boolean,
    onWakeMai: () -> Unit,
    state: WebUiTabState,
    modifier: Modifier = Modifier,
    terminalLogs: List<String> = emptyList(),
    url: String = MaiBotWebUi.URL,
    showRefreshButton: Boolean = true,
    startClearance: Dp = 0.dp
) {
    val statusBarTop = statusBarTopInset()
    // 横幅右侧给刷新按钮让出位置；刷新按钮挪进导航栏时左右对称留白。
    val bannerEndPadding = if (showRefreshButton) 56.dp else 16.dp

    // 弹出键盘时 WebView 底部让出键盘的高度。主界面是全面屏（edge-to-edge），清单里的 adjustResize 不再缩小窗口，
    // 不让的话 WebView 一直是全高，键盘直接盖住网页下半截（聊天页的输入栏首当其冲）。
    // 点输入框 -> 键盘开始弹出 -> WebView 一次缩到键盘上沿 -> 网页可视高度变矮，内核把正在输入的框滚到可见处，
    // 贴底的输入栏落在键盘正上方。按键盘的最终高度（imeAnimationTarget）只缩一次，不跟着键盘动画逐帧缩放：
    // WebUI 页面重，每帧重新排版会卡；收起键盘时 WebView 先恢复全高，露出的部分还被正在收起的键盘盖着，看不出跳动。
    // 横屏时有些输入法会切成悬浮键盘，悬浮键盘不占底部、报上来的高度是 0，这时 WebView 保持原样不缩。
    @OptIn(ExperimentalLayoutApi::class)
    val imeClearance = WindowInsets.imeAnimationTarget.only(WindowInsetsSides.Bottom)

    // 麦麦重启（改时区、换装 QQ 适配器等）的那十几秒里页签刚好重新加载（比如切回应用时界面被重建），
    // 会停在“网页无法打开”：WebUI 重新上线时，上次加载失败就自动重载一次，不用再手动点刷新或“唤醒”。
    // 在别的标签时上线，切回 WebUI 页签的第一帧也会检查一次。只在上线的那一刻重载，WebUI 自身出错不会反复刷新。
    LaunchedEffect(webUiOnline) {
        if (webUiOnline && state.webViewError != null) {
            state.reload()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // 横屏：整页先涂网页底色 -> 左边让出侧栏宽度 -> 余下部分照旧铺页面底色、放网页和横幅。
            // 侧栏的毛玻璃底下透出的就是网页的颜色，切换标签时这一条跟着网页一起滑走；网页还没报上底色时用页面底色。
            .background(state.pageBackground ?: PageBackground)
            .padding(start = startClearance)
            .background(PageBackground)
    ) {
        AndroidView(
            factory = {
                (state.webView.parent as? ViewGroup)?.removeView(state.webView)
                state.webView
            },
            update = { webView ->
                val launchUrl = MaiBotWebUi.launchUrl(
                    paths = webView.context.appDependencies.runtimePaths,
                    terminalLogs = terminalLogs,
                    baseUrl = url
                )
                val loadRequest = "$launchUrl#${state.reloadToken}"
                if (state.webViewHasSize && webView.tag != loadRequest) {
                    webView.tag = loadRequest
                    state.webViewError = null
                    // 首次加载、点刷新或重试时都按此刻的尺寸重新决定桌面版还是手机版；
                    // 转屏会重建 Activity 和 WebView，新页面自然按新方向加载。
                    state.webViewClient.applyDesktopViewport(webView)
                    Log.d(
                        MaiBotWebUi.LOG_TAG,
                        "load ${MaiBotWebUi.redactUrlForLogs(launchUrl)} at " +
                            "${webView.width}x${webView.height}, " +
                            "desktop width=${state.webViewClient.desktopLayoutWidthCss}"
                    )
                    webView.loadUrl(launchUrl)
                }
            },
            modifier = Modifier
                .padding(top = statusBarTop)
                .windowInsetsPadding(imeClearance)
                .fillMaxSize()
                .onSizeChanged { size ->
                    state.webViewHasSize = size.width > 0 && size.height > 0
                }
        )

        if (state.webViewError != null) {
            WebUiStatusBanner(
                message = "WebView 加载失败：${state.webViewError}",
                action = "重试",
                onAction = state::reload,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = statusBarTop + 8.dp, start = 16.dp, end = bannerEndPadding)
            )
        } else if (!webUiOnline) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = statusBarTop + 8.dp, start = 16.dp, end = bannerEndPadding)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Corners.Chip))
                    .border(1.dp, CardBorder, RoundedCornerShape(Corners.Chip))
                    .background(CardSurface.copy(alpha = 0.92f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(StatusWarning)
                )

                Spacer(modifier = Modifier.width(10.dp))

                Text(
                    text = "WebUI 离线",
                    color = TextPrimary,
                    fontSize = TextSizes.Body,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                Text(
                    text = "唤醒",
                    color = BrandOrange,
                    fontSize = TextSizes.Body,
                    fontWeight = FontWeight.Bold,
                    // 文字按钮：裁成圆角再加内边距，按下的水波纹有个按钮的样子，也更好点中。
                    modifier = Modifier
                        .clip(RoundedCornerShape(Corners.Control))
                        .clickable(onClick = onWakeMai)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        if (showRefreshButton) {
            WebUiRefreshButton(
                onClick = state::reload,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = statusBarTop + 8.dp, end = 16.dp)
            )
        }
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
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.Refresh,
            contentDescription = "刷新 WebUI",
            tint = TextPrimary,
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
            .clip(RoundedCornerShape(Corners.Chip))
            .border(1.dp, CardBorder, RoundedCornerShape(Corners.Chip))
            .background(CardSurface.copy(alpha = 0.94f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(StatusWarning)
        )

        Spacer(modifier = Modifier.width(10.dp))

        Text(
            text = message,
            color = TextPrimary,
            fontSize = TextSizes.Body,
            fontWeight = FontWeight.Bold,
            lineHeight = 16.sp,
            modifier = Modifier.weight(1f)
        )

        Text(
            text = action,
            color = BrandOrange,
            fontSize = TextSizes.Body,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(Corners.Control))
                .clickable(onClick = onAction)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
