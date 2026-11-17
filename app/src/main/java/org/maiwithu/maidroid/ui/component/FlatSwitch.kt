package org.maiwithu.maidroid.ui.component

import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.SwitchThumbUnchecked
import org.maiwithu.maidroid.ui.theme.SwitchTrackUnchecked
import org.maiwithu.maidroid.ui.theme.TextPrimary

/**
 * 扁平无边框开关：移除 Material3 默认暗色模式自带的粗描边，用柔灰轨道和品牌橙区分状态。
 */
@Composable
internal fun FlatSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = TextPrimary,
            checkedTrackColor = BrandOrange,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = SwitchThumbUnchecked,
            uncheckedTrackColor = SwitchTrackUnchecked,
            uncheckedBorderColor = Color.Transparent,
            disabledCheckedThumbColor = TextPrimary.copy(alpha = 0.6f),
            disabledCheckedTrackColor = BrandOrange.copy(alpha = 0.45f),
            disabledCheckedBorderColor = Color.Transparent,
            disabledUncheckedThumbColor = SwitchThumbUnchecked.copy(alpha = 0.5f),
            disabledUncheckedTrackColor = SwitchTrackUnchecked.copy(alpha = 0.5f),
            disabledUncheckedBorderColor = Color.Transparent
        )
    )
}
