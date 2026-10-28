package org.maiwithu.maidroid.ui.component

import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.viewinterop.AndroidView
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView

// BlurView 先把背景缩小这么多倍再模糊，越大越省性能，画面越糊。
private const val BLUR_DOWNSCALE_FACTOR = 5f

/**
 * 毛玻璃效果的“被模糊的那一层”。
 *
 * BlurView 只能模糊同一个 BlurTarget 视图里的内容，所以把要被模糊的 Compose 内容包进 BlurTarget，
 * 再通过 onTargetChanged 把它交给上层，悬浮在它上面的 BackdropBlurView 就能实时模糊它。
 */
@Composable
internal fun BlurTargetHost(
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

/**
 * 毛玻璃背景：实时模糊 blurTarget 里的内容，再叠一层 overlayColor。
 * 底部导航栏、启动页信息卡和两侧留白、首次授权弹窗都用它。
 * blurTarget 还没准备好（第一帧）时关掉模糊，只留叠加色。
 */
@Composable
internal fun BackdropBlurView(
    blurTarget: BlurTarget?,
    blurRadius: Float,
    overlayColor: Color,
    modifier: Modifier = Modifier
) {
    val overlayArgb = overlayColor.toArgb()

    AndroidView(
        factory = { context -> BlurView(context) },
        update = { view ->
            when {
                blurTarget == null -> {
                    view.setBlurEnabled(false)
                    view.setOverlayColor(overlayArgb)
                }
                // 第一次拿到（或换了）模糊目标时重新绑定；之后只更新参数。
                view.tag !== blurTarget -> {
                    view.tag = blurTarget
                    view.setupWith(blurTarget, BLUR_DOWNSCALE_FACTOR, false)
                        .setBlurRadius(blurRadius)
                        .setOverlayColor(overlayArgb)
                        .setBlurAutoUpdate(true)
                }
                else -> {
                    view.setBlurEnabled(true)
                    view.setBlurAutoUpdate(true)
                    view.setBlurRadius(blurRadius)
                    view.setOverlayColor(overlayArgb)
                }
            }
        },
        modifier = modifier
    )
}
