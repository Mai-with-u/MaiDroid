package org.maiwithu.maidroid.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maiwithu.maidroid.appupdate.AppUpdatePhase
import org.maiwithu.maidroid.appupdate.AppUpdateState
import org.maiwithu.maidroid.maibot.update.MaiBotUpdatePhase
import org.maiwithu.maidroid.maibot.update.MaiBotUpdateState
import org.maiwithu.maidroid.permission.PermissionState
import org.maiwithu.maidroid.settings.AppThemeMode
import org.maiwithu.maidroid.ui.component.FlatFeatureIconBadge
import org.maiwithu.maidroid.ui.component.FlatFeatureIcons
import org.maiwithu.maidroid.ui.component.LiquidSegmentItem
import org.maiwithu.maidroid.ui.component.LiquidSegmentedSelector
import org.maiwithu.maidroid.ui.component.LiquidToggle
import org.maiwithu.maidroid.ui.component.LocalLiquidBackdrop
import org.maiwithu.maidroid.ui.component.rememberSurfaceBackdrop
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardDivider
import org.maiwithu.maidroid.ui.theme.CardSurface
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.IconIrisBlue
import org.maiwithu.maidroid.ui.theme.MaiDroidColors
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes

/** 设置页分组容器：小标题 + 圆润卡片底板，承载同类别导航行与开关项。 */
@Composable
internal fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            color = TextMuted,
            fontSize = TextSizes.Caption,
            fontWeight = FontWeight.Medium,
            lineHeight = 14.sp,
            modifier = Modifier.padding(start = 6.dp)
        )

        // 取消外圈硬描边，用圆润扁平卡片底色与深色背景自然拉开层级
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Corners.Chip))
                .background(CardSurface),
            content = content
        )
    }
}

/** 设置页标准单行条目：圆形功能图标、标题/副标题、可选选中高亮与尾部操作区。 */
@Composable
internal fun SettingsNavigationRow(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    supportingText: String,
    modifier: Modifier = Modifier,
    supportingColor: Color = TextMuted,
    selected: Boolean = false,
    exposesSelection: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val clickModifier = when {
        onClick == null -> Modifier
        exposesSelection -> Modifier.selectable(
            selected = selected,
            onClick = onClick,
            role = Role.Tab
        )
        else -> Modifier.clickable(onClick = onClick)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (selected) BrandOrange.copy(alpha = 0.08f) else Color.Transparent)
            .then(clickModifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FlatFeatureIconBadge(
            icon = icon,
            backgroundColor = iconColor,
            size = 38.dp,
            iconSize = 25.dp
        )

        Spacer(modifier = Modifier.width(13.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = TextPrimary,
                fontSize = TextSizes.BodyLarge,
                fontWeight = FontWeight.Medium,
                lineHeight = 19.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(3.dp))

            Text(
                text = supportingText,
                color = supportingColor,
                fontSize = TextSizes.Caption,
                lineHeight = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (trailing != null) {
            Spacer(modifier = Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
internal fun SettingsRowArrow(
    contentDescription: String,
    tint: Color = TextMuted.copy(alpha = 0.6f)
) {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = contentDescription,
        tint = tint,
        modifier = Modifier.size(20.dp)
    )
}

@Composable
internal fun SettingsDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 67.dp, end = 16.dp)
            .height(0.5.dp)
            .background(CardDivider)
    )
}

@Composable
internal fun SettingsStatusDot(color: Color) {
    Box(
        modifier = Modifier
            .size(9.dp)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
internal fun CompactSshSwitchRow(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    CompactSettingSwitchRow(
        label = "启用 SSH",
        checked = checked,
        enabled = enabled,
        onCheckedChange = onCheckedChange
    )
}

@Composable
internal fun CompactSettingSwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    // 左右交互彻底分离：左侧占满剩余宽度防止窄屏挤压，右侧开关独立承接点击与液态拖拽，避免整行按压蒙层盖在开关上方。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .defaultMinSize(minHeight = 40.dp)
                .clickable(
                    interactionSource = null,
                    indication = null,
                    enabled = enabled,
                    role = Role.Switch,
                    onClick = { onCheckedChange(!checked) }
                ),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = label,
                color = TextMuted,
                fontSize = TextSizes.Caption,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        LiquidToggle(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

// 失败标醒目暖橙提醒，平时保持柔和次要灰字，避免喧宾夺主。
internal fun maiBotUpdateSummaryColor(state: MaiBotUpdateState, colors: MaiDroidColors): Color =
    when (state.phase) {
        MaiBotUpdatePhase.Failed -> colors.statusWarning
        MaiBotUpdatePhase.Available -> BrandOrange
        else -> colors.textMuted
    }

// 失败标醒目暖橙提醒，发现新版本标品牌橙提醒，其余保持次要灰字。
internal fun appUpdateSummaryColor(state: AppUpdateState, colors: MaiDroidColors): Color =
    when (state.phase) {
        AppUpdatePhase.Failed -> colors.statusWarning
        AppUpdatePhase.Available -> BrandOrange
        else -> colors.textMuted
    }

/** 外观主题三段式切换卡片：跟随系统 / 浅色 / 深色。 */
@Composable
internal fun ThemeModeSettingBlock(
    selectedMode: AppThemeMode,
    isDarkTheme: Boolean,
    onModeSelected: (AppThemeMode) -> Unit
) {
    val cardBackdrop = rememberSurfaceBackdrop(CardSurface)
    val items = remember {
        AppThemeMode.entries.map { mode -> LiquidSegmentItem(label = mode.label) }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        SettingsNavigationRow(
            icon = FlatFeatureIcons.Appearance,
            iconColor = IconIrisBlue,
            title = "外观主题",
            supportingText = themeModeSummaryText(selectedMode, isDark = isDarkTheme)
        )
        CompositionLocalProvider(
            LocalLiquidBackdrop provides cardBackdrop
        ) {
            LiquidSegmentedSelector(
                items = items,
                selectedIndex = selectedMode.ordinal,
                onSelect = { index ->
                    AppThemeMode.entries.getOrNull(index)?.let(onModeSelected)
                },
                height = 38.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 14.dp)
            )
        }
    }
}

internal fun themeModeSummaryText(mode: AppThemeMode, isDark: Boolean): String = when (mode) {
    AppThemeMode.System -> if (isDark) "跟随系统（当前：深色）" else "跟随系统（当前：浅色）"
    AppThemeMode.Light -> "已固定为浅色外观"
    AppThemeMode.Dark -> "已固定为深色外观（OLED 纯黑）"
}

internal fun permissionSummary(state: PermissionState): String {
    val notificationStatus = if (state.notificationGranted) "通知已开启" else "通知未开启"
    val optionalGranted = listOf(
        state.batteryOptimizationIgnored,
        state.storageGranted
    ).count { it }
    return "$notificationStatus · 可选项 $optionalGranted/2"
}
