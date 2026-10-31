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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
            webChromeClient = WebUiChromeClient(MaiBotWebUi.LOG_TAG, fileChooser)
        }

        // 回调里要改页签状态，状态里又要持有 client：先建 client，回调经 holder 找到稍后建好的状态。
        var tabState: WebUiTabState? = null
        val webViewClient = WebUiWebViewClient(
            logTag = MaiBotWebUi.LOG_TAG,
            // APP 底栏在 WebUI 页签默认收成右下角的小按钮（横屏时在左侧另占一列），网页底部不用再给它留白；
            // 展开时底栏连同遮罩一起盖在网页上，不需要网页让位。
            onPageFinishedCallback = { _, _ ->
                tabState?.webViewError = null
            },
            onMainFrameError = { _, error ->
                tabState?.webViewError = "${error.errorCode}: ${error.description}"
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
 * 每次加载前按 WebView 尺寸决定排版：横屏按桌面版（布局视口至少 1600×900），竖屏按手机版。
 * 顶部按情况显示“加载失败，重试”或“WebUI 离线，唤醒”；竖屏时右上角是刷新按钮，横屏时刷新按钮在左侧导航栏里。
 */
@Composable
internal fun WebUiTabPage(
    webUiOnline: Boolean,
    onWakeMai: () -> Unit,
    state: WebUiTabState,
    modifier: Modifier = Modifier,
    terminalLogs: List<String> = emptyList(),
    url: String = MaiBotWebUi.URL,
    showRefreshButton: Boolean = true
) {
    val statusBarTop = statusBarTopInset()
    // 横幅右侧给刷新按钮让出位置；刷新按钮挪进导航栏时左右对称留白。
    val bannerEndPadding = if (showRefreshButton) 56.dp else 16.dp

    Box(
        modifier = modifier
            .fillMaxSize()
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
