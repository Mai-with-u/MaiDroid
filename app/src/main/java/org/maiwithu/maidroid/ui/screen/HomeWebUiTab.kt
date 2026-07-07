package org.maiwithu.maidroid.ui.screen

import android.annotation.SuppressLint
import android.os.Build
import android.util.Log
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.maiwithu.maidroid.BuildConfig
import org.maiwithu.maidroid.container.MaiBotContainerConfig
import org.maiwithu.maidroid.webui.MaiBotWebUiSupport
import org.maiwithu.maidroid.webui.MaiBotWebViewClient

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun WebUiTabPage(
    webUiOnline: Boolean,
    onWakeMai: () -> Unit,
    modifier: Modifier = Modifier,
    terminalLogs: List<String> = emptyList(),
    url: String = MaiBotContainerConfig.WEB_UI_URL
) {
    var webViewError by remember { mutableStateOf<String?>(null) }
    var reloadToken by remember { mutableStateOf(0) }
    var webViewHasSize by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val statusBarTop = with(density) {
        WindowInsets.statusBars.getTop(this).toDp()
    }

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
                    MaiBotWebUiSupport.enableCookies(this)
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
                    webViewClient = MaiBotWebViewClient(
                        logTag = WebUiLogTag,
                        onPageFinishedCallback = { view, _ ->
                            webViewError = null
                            patchBrokenViewportUnitsIfNeeded(view)
                        },
                        onMainFrameError = { _, error ->
                            webViewError = "${error.errorCode}: ${error.description}"
                        }
                    )
                }
            },
            update = { webView ->
                val launchUrl = MaiBotWebUiSupport.resolveLaunchUrl(
                    context = webView.context,
                    baseUrl = url,
                    terminalLogs = terminalLogs
                )
                val loadRequest = "$launchUrl#$reloadToken"
                if (webViewHasSize && webView.tag != loadRequest) {
                    webView.tag = loadRequest
                    webViewError = null
                    Log.d(
                        WebUiLogTag,
                        "load ${MaiBotWebUiSupport.redactUrlForLogs(launchUrl)} at " +
                            "${webView.width}x${webView.height}"
                    )
                    webView.loadUrl(launchUrl)
                }
            },
            modifier = Modifier
                .padding(top = statusBarTop)
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
                    .padding(top = statusBarTop + 8.dp, start = 16.dp, end = 56.dp)
            )
        } else if (!webUiOnline) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = statusBarTop + 8.dp, start = 16.dp, end = 56.dp)
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
                .padding(top = statusBarTop + 8.dp, end = 16.dp)
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
