package org.maiwithu.maidroid.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 扁平开关统一入口：对外保持兼容签名，内部全面委托至 AndroidLiquidGlass 液态水滴开关（LiquidToggle）。
 */
@Composable
internal fun FlatSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    LiquidToggle(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled
    )
}
