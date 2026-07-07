package org.maiwithu.maidroid.ui.screen

import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.viewinterop.AndroidView
import eightbitlab.com.blurview.BlurTarget

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
