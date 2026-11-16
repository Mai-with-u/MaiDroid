package org.maiwithu.maidroid.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.SwitchThumbUnchecked
import org.maiwithu.maidroid.ui.theme.SwitchTrackUnchecked
import org.maiwithu.maidroid.ui.theme.TextPrimary

private val PureWhite = Color.White
private val SoftWhite = Color.White.copy(alpha = 0.56f)

/**
 * 扁平无边框开关：移除 Material3 默认暗色模式自带的粗描边，用柔灰轨道和品牌橙区分状态，消除廉价塑料感。
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

/**
 * 清新扁平圆形功能图标徽章：明快实色圆底搭配双层柔白+纯白圆润几何图标，替代暗沉半透明方框和机械线框。
 */
@Composable
internal fun FlatFeatureIconBadge(
    icon: ImageVector,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    iconSize: Dp = 25.dp,
    shape: Shape = CircleShape,
    dimmed: Boolean = false
) {
    val bg = if (dimmed) Color.White.copy(alpha = 0.12f) else backgroundColor
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier.size(iconSize)
        )
    }
}

/**
 * 全应用统一的青春感双层扁平矢量图标集（Soft Duotone Flat）。
 * 每枚图标均由半透明柔白底层与纯白前景圆润几何构成，保证与实色圆底搭配时干净通透。
 */
internal object FlatFeatureIcons {

    val WebUi: ImageVector by lazy {
        flatVector("FlatWebUi") {
            fillLayer(SoftWhite) { roundedRectPath(2.8f, 4.0f, 21.2f, 20.0f, 4.2f) }
            fillLayer(PureWhite) {
                roundedRectPath(5.6f, 6.5f, 14.0f, 8.5f, 1.0f)
                circlePath(17.6f, 7.5f, 1.05f)
                sparklePath(12.0f, 14.3f, 4.7f, 1.6f)
            }
        }
    }

    val MaiBotUpdate: ImageVector by lazy {
        flatVector("FlatMaiBotUpdate") {
            fillLayer(SoftWhite) {
                circlePath(7.3f, 12.8f, 3.9f)
                circlePath(12.2f, 9.0f, 4.8f)
                circlePath(16.8f, 12.6f, 3.7f)
                roundedRectPath(3.4f, 10.2f, 20.6f, 16.8f, 3.2f)
            }
            strokeLayer(PureWhite, width = 2.5f) {
                moveTo(12.0f, 20.0f)
                lineTo(12.0f, 10.8f)
                moveTo(8.8f, 13.9f)
                lineTo(12.0f, 10.7f)
                lineTo(15.2f, 13.9f)
            }
        }
    }

    val ContainerSsh: ImageVector by lazy {
        flatVector("FlatContainerSsh") {
            fillLayer(SoftWhite) { roundedRectPath(2.6f, 4.2f, 21.4f, 19.8f, 4.2f) }
            strokeLayer(PureWhite, width = 2.4f) {
                moveTo(6.8f, 9.0f)
                lineTo(10.8f, 12.0f)
                lineTo(6.8f, 15.0f)
                moveTo(12.6f, 15.2f)
                lineTo(17.2f, 15.2f)
            }
        }
    }

    val MaiBotFiles: ImageVector by lazy {
        flatVector("FlatMaiBotFiles") {
            fillLayer(SoftWhite) {
                moveTo(2.8f, 7.0f)
                curveTo(2.8f, 5.5f, 4.0f, 4.4f, 5.4f, 4.4f)
                lineTo(9.4f, 4.4f)
                lineTo(11.4f, 6.8f)
                lineTo(18.6f, 6.8f)
                curveTo(20.0f, 6.8f, 21.2f, 8.0f, 21.2f, 9.4f)
                lineTo(21.2f, 17.0f)
                lineTo(2.8f, 17.0f)
                close()
            }
            fillLayer(PureWhite) { roundedRectPath(2.8f, 8.6f, 21.2f, 19.4f, 2.8f) }
        }
    }

    val Permissions: ImageVector by lazy {
        flatVector("FlatPermissions") {
            fillLayer(SoftWhite) { shieldPath() }
            strokeLayer(PureWhite, width = 2.6f) {
                moveTo(8.0f, 12.2f)
                lineTo(10.8f, 15.0f)
                lineTo(16.2f, 9.2f)
            }
        }
    }

    val Platforms: ImageVector by lazy {
        flatVector("FlatPlatforms") {
            fillLayer(SoftWhite) {
                roundedRectPath(8.8f, 3.8f, 21.4f, 14.0f, 4.5f)
                circlePath(19.2f, 13.8f, 1.7f)
            }
            fillLayer(PureWhite) {
                roundedRectPath(2.6f, 8.2f, 16.8f, 18.2f, 4.5f)
                moveTo(5.0f, 16.2f)
                lineTo(3.6f, 20.2f)
                lineTo(8.2f, 17.8f)
                close()
            }
        }
    }

    val AppVersion: ImageVector by lazy {
        flatVector("FlatAppVersion") {
            fillLayer(SoftWhite) {
                sparklePath(18.0f, 5.8f, 3.3f, 1.15f)
                circlePath(4.8f, 6.4f, 1.35f)
            }
            fillLayer(PureWhite) { sparklePath(10.2f, 13.4f, 7.2f, 2.45f) }
        }
    }

    val Feedback: ImageVector by lazy {
        flatVector("FlatFeedback") {
            fillLayer(SoftWhite) {
                moveTo(10.2f, 13.5f)
                lineTo(11.0f, 19.2f)
                lineTo(14.3f, 15.8f)
                close()
            }
            fillLayer(PureWhite) {
                moveTo(3.0f, 11.2f)
                lineTo(20.6f, 3.8f)
                lineTo(10.0f, 13.3f)
                close()
                moveTo(20.6f, 3.8f)
                lineTo(15.2f, 20.2f)
                lineTo(11.0f, 14.0f)
                close()
            }
        }
    }

    val Cleanup: ImageVector by lazy {
        flatVector("FlatCleanup") {
            // 斜置小扫帚手柄与闪烁星点用柔白层，前景圆头束腰和扇形扫头用纯白层
            strokeLayer(SoftWhite, width = 2.6f) {
                moveTo(14.0f, 11.0f)
                lineTo(19.6f, 5.4f)
            }
            fillLayer(SoftWhite) {
                sparklePath(6.0f, 6.4f, 2.5f, 0.85f)
                sparklePath(17.6f, 17.4f, 2.3f, 0.8f)
            }
            strokeLayer(PureWhite, width = 2.4f) {
                moveTo(11.0f, 9.2f)
                lineTo(14.8f, 13.0f)
            }
            fillLayer(PureWhite) {
                moveTo(9.3f, 10.9f)
                lineTo(13.1f, 14.7f)
                curveTo(11.7f, 17.4f, 10.3f, 19.3f, 8.8f, 20.2f)
                curveTo(6.8f, 19.1f, 5.0f, 17.3f, 3.8f, 15.2f)
                curveTo(4.7f, 13.6f, 6.6f, 12.2f, 9.3f, 10.9f)
                close()
            }
        }
    }

    val Storage: ImageVector by lazy {
        flatVector("FlatStorage") {
            fillLayer(SoftWhite) {
                moveTo(6.2f, 3.0f)
                lineTo(14.8f, 3.0f)
                lineTo(19.6f, 7.8f)
                lineTo(19.6f, 18.8f)
                curveTo(19.6f, 20.1f, 18.5f, 21.2f, 17.2f, 21.2f)
                lineTo(6.2f, 21.2f)
                curveTo(4.9f, 21.2f, 3.8f, 20.1f, 3.8f, 18.8f)
                lineTo(3.8f, 5.4f)
                curveTo(3.8f, 4.1f, 4.9f, 3.0f, 6.2f, 3.0f)
                close()
            }
            fillLayer(PureWhite) {
                roundedRectPath(7.8f, 6.0f, 9.4f, 9.8f, 0.8f)
                roundedRectPath(11.0f, 6.0f, 12.6f, 9.8f, 0.8f)
                roundedRectPath(14.2f, 7.0f, 15.8f, 9.8f, 0.8f)
                roundedRectPath(7.0f, 13.8f, 16.6f, 17.4f, 1.6f)
            }
        }
    }

    val NotificationKeepAlive: ImageVector by lazy {
        flatVector("FlatNotificationKeepAlive") {
            fillLayer(SoftWhite) {
                circlePath(12.0f, 4.2f, 1.55f)
                roundedRectPath(9.4f, 17.8f, 14.6f, 20.2f, 1.2f)
            }
            fillLayer(PureWhite) {
                moveTo(12.0f, 5.0f)
                curveTo(8.1f, 5.0f, 5.8f, 7.7f, 5.8f, 11.2f)
                lineTo(5.8f, 14.3f)
                lineTo(4.2f, 16.0f)
                curveTo(3.6f, 16.8f, 4.2f, 17.8f, 5.2f, 17.8f)
                lineTo(18.8f, 17.8f)
                curveTo(19.8f, 17.8f, 20.4f, 16.8f, 19.8f, 16.0f)
                lineTo(18.2f, 14.3f)
                lineTo(18.2f, 11.2f)
                curveTo(18.2f, 7.7f, 15.9f, 5.0f, 12.0f, 5.0f)
                close()
            }
        }
    }

    val BatteryOptimization: ImageVector by lazy {
        flatVector("FlatBatteryOptimization") {
            fillLayer(SoftWhite) {
                roundedRectPath(9.4f, 2.8f, 14.6f, 4.6f, 0.9f)
                roundedRectPath(6.0f, 5.0f, 18.0f, 20.8f, 3.3f)
            }
            fillLayer(PureWhite) {
                moveTo(13.3f, 8.2f)
                lineTo(8.8f, 13.6f)
                lineTo(12.0f, 13.6f)
                lineTo(10.7f, 18.0f)
                lineTo(15.2f, 12.6f)
                lineTo(12.0f, 12.6f)
                close()
            }
        }
    }

    val AutoStart: ImageVector by lazy {
        flatVector("FlatAutoStart") {
            fillLayer(SoftWhite) { circlePath(12.0f, 12.0f, 9.2f) }
            fillLayer(PureWhite) {
                moveTo(9.3f, 7.8f)
                curveTo(9.3f, 6.8f, 10.4f, 6.2f, 11.2f, 6.8f)
                lineTo(17.2f, 11.0f)
                curveTo(18.0f, 11.5f, 18.0f, 12.5f, 17.2f, 13.0f)
                lineTo(11.2f, 17.2f)
                curveTo(10.4f, 17.8f, 9.3f, 17.2f, 9.3f, 16.2f)
                close()
            }
        }
    }

    val TaskLock: ImageVector by lazy {
        flatVector("FlatTaskLock") {
            strokeLayer(SoftWhite, width = 2.6f) {
                moveTo(7.8f, 10.2f)
                lineTo(7.8f, 7.5f)
                arcTo(4.2f, 4.2f, 0f, false, true, 16.2f, 7.5f)
                lineTo(16.2f, 10.2f)
            }
            path(
                fill = SolidColor(PureWhite),
                pathFillType = PathFillType.EvenOdd
            ) {
                roundedRectPath(5.0f, 10.0f, 19.0f, 20.4f, 3.6f)
                circlePath(12.0f, 15.2f, 1.75f)
            }
        }
    }

    val Accessibility: ImageVector by lazy {
        flatVector("FlatAccessibility") {
            fillLayer(SoftWhite) { circlePath(12.0f, 12.0f, 8.8f) }
            fillLayer(PureWhite) { circlePath(12.0f, 12.0f, 4.3f) }
        }
    }

    val DeviceAdmin: ImageVector by lazy {
        flatVector("FlatDeviceAdmin") {
            fillLayer(SoftWhite) { shieldPath() }
            fillLayer(PureWhite) { sparklePath(12.0f, 12.0f, 4.7f, 1.65f) }
        }
    }

    val GridMenu: ImageVector by lazy {
        flatVector("FlatGridMenu") {
            fillLayer(SoftWhite) {
                roundedRectPath(13.4f, 3.8f, 20.2f, 10.6f, 2.6f)
                circlePath(7.2f, 16.8f, 3.4f)
            }
            fillLayer(PureWhite) {
                roundedRectPath(3.8f, 3.8f, 10.6f, 10.6f, 2.6f)
                roundedRectPath(13.4f, 13.4f, 20.2f, 20.2f, 2.6f)
            }
        }
    }

    val SettingsGear: ImageVector by lazy {
        flatVector("FlatSettingsGear") {
            // 柔白圆齿外环，前景纯白同心圆环通过 EvenOdd 挖空圆心透出徽章底色
            fillLayer(SoftWhite) {
                val toothRadius = 7.6f
                val toothSize = 1.9f
                val diag = toothRadius * 0.7071f
                circlePath(12.0f, 12.0f, 6.8f)
                circlePath(12.0f, 12.0f - toothRadius, toothSize)
                circlePath(12.0f, 12.0f + toothRadius, toothSize)
                circlePath(12.0f - toothRadius, 12.0f, toothSize)
                circlePath(12.0f + toothRadius, 12.0f, toothSize)
                circlePath(12.0f - diag, 12.0f - diag, toothSize)
                circlePath(12.0f + diag, 12.0f - diag, toothSize)
                circlePath(12.0f - diag, 12.0f + diag, toothSize)
                circlePath(12.0f + diag, 12.0f + diag, toothSize)
            }
            path(
                fill = SolidColor(PureWhite),
                pathFillType = PathFillType.EvenOdd
            ) {
                circlePath(12.0f, 12.0f, 5.5f)
                circlePath(12.0f, 12.0f, 2.4f)
            }
        }
    }
}

private inline fun flatVector(
    name: String,
    block: ImageVector.Builder.() -> Unit
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).apply(block).build()

private inline fun ImageVector.Builder.fillLayer(
    color: Color,
    block: PathBuilder.() -> Unit
) {
    path(fill = SolidColor(color), pathBuilder = block)
}

private inline fun ImageVector.Builder.strokeLayer(
    color: Color,
    width: Float,
    block: PathBuilder.() -> Unit
) {
    path(
        stroke = SolidColor(color),
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = block
    )
}

private fun PathBuilder.roundedRectPath(l: Float, t: Float, r: Float, b: Float, radius: Float) {
    val cr = radius.coerceAtMost(minOf((r - l) / 2f, (b - t) / 2f))
    moveTo(l + cr, t)
    lineTo(r - cr, t)
    arcTo(cr, cr, 0f, false, true, r, t + cr)
    lineTo(r, b - cr)
    arcTo(cr, cr, 0f, false, true, r - cr, b)
    lineTo(l + cr, b)
    arcTo(cr, cr, 0f, false, true, l, b - cr)
    lineTo(l, t + cr)
    arcTo(cr, cr, 0f, false, true, l + cr, t)
    close()
}

private fun PathBuilder.circlePath(cx: Float, cy: Float, r: Float) {
    moveTo(cx - r, cy)
    arcTo(r, r, 0f, true, false, cx + r, cy)
    arcTo(r, r, 0f, true, false, cx - r, cy)
    close()
}

private fun PathBuilder.sparklePath(cx: Float, cy: Float, radius: Float, waist: Float) {
    moveTo(cx, cy - radius)
    quadTo(cx + waist, cy - waist, cx + radius, cy)
    quadTo(cx + waist, cy + waist, cx, cy + radius)
    quadTo(cx - waist, cy + waist, cx - radius, cy)
    quadTo(cx - waist, cy - waist, cx, cy - radius)
    close()
}

private fun PathBuilder.shieldPath() {
    moveTo(12.0f, 2.8f)
    curveTo(15.8f, 4.1f, 19.2f, 4.7f, 20.4f, 5.0f)
    lineTo(20.4f, 11.6f)
    curveTo(20.4f, 16.6f, 16.3f, 19.8f, 12.0f, 21.2f)
    curveTo(7.7f, 19.8f, 3.6f, 16.6f, 3.6f, 11.6f)
    lineTo(3.6f, 5.0f)
    curveTo(4.8f, 4.7f, 8.2f, 4.1f, 12.0f, 2.8f)
    close()
}
