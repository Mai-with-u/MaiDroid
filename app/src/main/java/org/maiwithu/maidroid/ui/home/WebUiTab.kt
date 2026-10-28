package org.maiwithu.maidroid.ui.home

import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import org.maiwithu.maidroid.ui.theme.TextSizes
import org.maiwithu.maidroid.webui.MaiBotWebUi
import org.maiwithu.maidroid.webui.WebUiWebViewClient
import org.maiwithu.maidroid.webui.createWebUiWebView

/** WebUI 页签的 WebView 及其加载状态；放在主页外壳里记住，切换标签时 WebView 不销毁。 */
internal class WebUiTabState internal constructor(
    val webView: WebView
) {
    var webViewError by mutableStateOf<String?>(null)
    var reloadToken by mutableIntStateOf(0)
    var webViewHasSize by mutableStateOf(false)
}

/** 创建 WebUI 页签的 WebView（debug 包允许 Chrome 远程调试），离开主页时销毁。 */
@Composable
internal fun rememberWebUiTabState(): WebUiTabState {
    val context = LocalContext.current
    val state = remember(context) {
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

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
            // 网页 console 输出转到 logcat，排查 WebUI 显示问题时用。
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    Log.d(
                        MaiBotWebUi.LOG_TAG,
                        "console ${consoleMessage.messageLevel()}: ${consoleMessage.message()} " +
                            "(${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})"
                    )
                    return super.onConsoleMessage(consoleMessage)
                }
            }
        }

        WebUiTabState(webView).also { tabState ->
            webView.webViewClient = WebUiWebViewClient(
                logTag = MaiBotWebUi.LOG_TAG,
                // APP 底栏在 WebUI 页签默认收成右下角的小按钮，网页底部不用再给它留白；
                // 展开时底栏连同遮罩一起盖在网页上，不需要网页让位。
                onPageFinishedCallback = { _, _ ->
                    tabState.webViewError = null
                },
                onMainFrameError = { _, error ->
                    tabState.webViewError = "${error.errorCode}: ${error.description}"
                }
            )
        }
    }

    DisposableEffect(state) {
        onDispose {
            state.webView.destroy()
        }
    }

    return state
}

/**
 * WebUI 页签：嵌入式加载 MaiBot WebUI（带访问令牌自动登录），WebView 拿到非零尺寸后才开始加载。
 * 顶部按情况显示“加载失败，重试”或“WebUI 离线，唤醒”，右上角是刷新按钮。
 */
@Composable
internal fun WebUiTabPage(
    webUiOnline: Boolean,
    onWakeMai: () -> Unit,
    state: WebUiTabState,
    modifier: Modifier = Modifier,
    terminalLogs: List<String> = emptyList(),
    url: String = MaiBotWebUi.URL
) {
    val statusBarTop = statusBarTopInset()

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
                    Log.d(
                        MaiBotWebUi.LOG_TAG,
                        "load ${MaiBotWebUi.redactUrlForLogs(launchUrl)} at " +
                            "${webView.width}x${webView.height}"
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
                onAction = {
                    state.webViewError = null
                    state.reloadToken += 1
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = statusBarTop + 8.dp, start = 16.dp, end = 56.dp)
            )
        } else if (!webUiOnline) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = statusBarTop + 8.dp, start = 16.dp, end = 56.dp)
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
                    color = Color.White,
                    fontSize = TextSizes.Body,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                Text(
                    text = "唤醒",
                    color = BrandOrange,
                    fontSize = TextSizes.Body,
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
                state.webViewError = null
                state.reloadToken += 1
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = statusBarTop + 8.dp, end = 16.dp)
        )
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
            color = Color.White,
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
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onAction
            )
        )
    }
}
