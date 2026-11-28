package org.maiwithu.maidroid.ui.component

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

/** 返回当前顶部状态栏安全区高度（dp）。 */
@Composable
internal fun statusBarTopInset(): Dp {
    val density = LocalDensity.current
    return with(density) {
        WindowInsets.statusBars.getTop(this).toDp()
    }
}
