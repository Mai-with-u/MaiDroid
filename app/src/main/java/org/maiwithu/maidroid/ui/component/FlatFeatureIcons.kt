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
private val SoftWhite = Color.White.copy(alpha = 0.52f)

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
    iconSize: Dp = 20.dp,
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
            fillLayer(SoftWhite) { roundedRectPath(3.5f, 4.5f, 20.5f, 19.5f, 4.0f) }
            fillLayer(PureWhite) {
                roundedRectPath(6.0f, 6.8f, 13.6f, 8.6f, 0.9f)
                circlePath(17.1f, 7.7f, 0.95f)
                sparklePath(12.0f, 14.2f, 4.2f, 1.45f)
            }
        }
    }

    val MaiBotUpdate: ImageVector by lazy {
        flatVector("FlatMaiBotUpdate") {
            fillLayer(SoftWhite) {
                circlePath(8.2f, 13.0f, 3.4f)
                circlePath(12.2f, 9.6f, 4.2f)
                circlePath(16.0f, 12.8f, 3.2f)
                roundedRectPath(4.8f, 10.6f, 19.2f, 16.4f, 2.9f)
            }
            strokeLayer(PureWhite, width = 2.3f) {
                moveTo(12.0f, 19.2f)
                lineTo(12.0f, 11.4f)
                moveTo(9.3f, 14.0f)
                lineTo(12.0f, 11.3f)
                lineTo(14.7f, 14.0f)
            }
        }
    }

    val ContainerSsh: ImageVector by lazy {
        flatVector("FlatContainerSsh") {
            fillLayer(SoftWhite) { roundedRectPath(3.2f, 4.8f, 20.8f, 19.2f, 4.0f) }
            strokeLayer(PureWhite, width = 2.2f) {
                moveTo(7.4f, 9.4f)
                lineTo(10.8f, 12.1f)
                lineTo(7.4f, 14.8f)
                moveTo(12.8f, 15.0f)
                lineTo(16.6f, 15.0f)
            }
        }
    }

    val MaiBotFiles: ImageVector by lazy {
        flatVector("FlatMaiBotFiles") {
            fillLayer(SoftWhite) {
                moveTo(4.0f, 7.6f)
                curveTo(4.0f, 6.3f, 5.0f, 5.2f, 6.3f, 5.2f)
                lineTo(9.5f, 5.2f)
                lineTo(11.3f, 7.2f)
                lineTo(17.7f, 7.2f)
                curveTo(19.0f, 7.2f, 20.0f, 8.2f, 20.0f, 9.5f)
                lineTo(20.0f, 16.5f)
                lineTo(4.0f, 16.5f)
                close()
            }
            fillLayer(PureWhite) { roundedRectPath(4.0f, 9.0f, 20.0f, 18.6f, 2.6f) }
        }
    }

    val Permissions: ImageVector by lazy {
        flatVector("FlatPermissions") {
            fillLayer(SoftWhite) { shieldPath() }
            strokeLayer(PureWhite, width = 2.4f) {
                moveTo(8.5f, 12.1f)
                lineTo(10.9f, 14.4f)
                lineTo(15.6f, 9.6f)
            }
        }
    }

    val Platforms: ImageVector by lazy {
        flatVector("FlatPlatforms") {
            fillLayer(SoftWhite) {
                roundedRectPath(9.4f, 4.6f, 20.6f, 13.8f, 4.2f)
                circlePath(18.6f, 13.6f, 1.5f)
            }
            fillLayer(PureWhite) {
                roundedRectPath(3.4f, 8.6f, 16.2f, 17.6f, 4.2f)
                moveTo(5.6f, 15.8f)
                lineTo(4.4f, 19.3f)
                lineTo(8.4f, 17.2f)
                close()
            }
        }
    }

    val AppVersion: ImageVector by lazy {
        flatVector("FlatAppVersion") {
            fillLayer(SoftWhite) {
                sparklePath(17.6f, 6.4f, 2.8f, 0.95f)
                circlePath(5.4f, 6.8f, 1.15f)
            }
            fillLayer(PureWhite) { sparklePath(10.5f, 13.2f, 6.2f, 2.1f) }
        }
    }

    val Feedback: ImageVector by lazy {
        flatVector("FlatFeedback") {
            fillLayer(SoftWhite) {
                moveTo(10.4f, 13.4f)
                lineTo(11.1f, 18.4f)
                lineTo(14.0f, 15.4f)
                close()
            }
            fillLayer(PureWhite) {
                moveTo(4.3f, 11.2f)
                lineTo(19.4f, 4.8f)
                lineTo(10.2f, 13.2f)
                close()
                moveTo(19.4f, 4.8f)
                lineTo(14.8f, 19.3f)
                lineTo(11.2f, 13.8f)
                close()
            }
        }
    }

    val Cleanup: ImageVector by lazy {
        flatVector("FlatCleanup") {
            // 斜置小扫帚手柄与闪烁星点用柔白层，前景圆头束腰和扇形扫头用纯白层
            strokeLayer(SoftWhite, width = 2.3f) {
                moveTo(13.6f, 11.4f)
                lineTo(18.2f, 6.8f)
            }
            fillLayer(SoftWhite) {
                sparklePath(6.8f, 7.2f, 2.1f, 0.72f)
                sparklePath(16.8f, 16.8f, 2.0f, 0.68f)
            }
            strokeLayer(PureWhite, width = 2.1f) {
                moveTo(11.1f, 9.7f)
                lineTo(14.3f, 12.9f)
            }
            fillLayer(PureWhite) {
                moveTo(9.6f, 11.2f)
                lineTo(12.8f, 14.4f)
                curveTo(11.6f, 16.7f, 10.4f, 18.3f, 9.1f, 19.0f)
                curveTo(7.4f, 18.1f, 6.0f, 16.7f, 5.0f, 14.9f)
                curveTo(5.7f, 13.6f, 7.3f, 12.4f, 9.6f, 11.2f)
                close()
            }
        }
    }

    val Storage: ImageVector by lazy {
        flatVector("FlatStorage") {
            fillLayer(SoftWhite) {
                moveTo(6.8f, 4.0f)
                lineTo(14.4f, 4.0f)
                lineTo(18.6f, 8.2f)
                lineTo(18.6f, 18.0f)
                curveTo(18.6f, 19.2f, 17.6f, 20.2f, 16.4f, 20.2f)
                lineTo(6.8f, 20.2f)
                curveTo(5.6f, 20.2f, 4.6f, 19.2f, 4.6f, 18.0f)
                lineTo(4.6f, 6.2f)
                curveTo(4.6f, 5.0f, 5.6f, 4.0f, 6.8f, 4.0f)
                close()
            }
            fillLayer(PureWhite) {
                roundedRectPath(8.2f, 6.8f, 9.6f, 10.2f, 0.7f)
                roundedRectPath(11.0f, 6.8f, 12.4f, 10.2f, 0.7f)
                roundedRectPath(13.8f, 7.6f, 15.2f, 10.2f, 0.7f)
                roundedRectPath(7.4f, 13.6f, 15.8f, 16.8f, 1.4f)
            }
        }
    }

    val NotificationKeepAlive: ImageVector by lazy {
        flatVector("FlatNotificationKeepAlive") {
            fillLayer(SoftWhite) {
                circlePath(12.0f, 5.0f, 1.4f)
                roundedRectPath(9.7f, 17.4f, 14.3f, 19.6f, 1.1f)
            }
            fillLayer(PureWhite) {
                moveTo(12.0f, 5.8f)
                curveTo(8.5f, 5.8f, 6.4f, 8.2f, 6.4f, 11.4f)
                lineTo(6.4f, 14.2f)
                lineTo(5.0f, 15.8f)
                curveTo(4.5f, 16.5f, 5.0f, 17.4f, 5.9f, 17.4f)
                lineTo(18.1f, 17.4f)
                curveTo(19.0f, 17.4f, 19.5f, 16.5f, 19.0f, 15.8f)
                lineTo(17.6f, 14.2f)
                lineTo(17.6f, 11.4f)
                curveTo(17.6f, 8.2f, 15.5f, 5.8f, 12.0f, 5.8f)
                close()
            }
        }
    }

    val BatteryOptimization: ImageVector by lazy {
        flatVector("FlatBatteryOptimization") {
            fillLayer(SoftWhite) {
                roundedRectPath(9.8f, 3.8f, 14.2f, 5.4f, 0.8f)
                roundedRectPath(6.8f, 5.8f, 17.2f, 20.0f, 3.0f)
            }
            fillLayer(PureWhite) {
                moveTo(13.1f, 8.6f)
                lineTo(9.2f, 13.4f)
                lineTo(12.0f, 13.4f)
                lineTo(10.9f, 17.4f)
                lineTo(14.8f, 12.6f)
                lineTo(12.0f, 12.6f)
                close()
            }
        }
    }

    val AutoStart: ImageVector by lazy {
        flatVector("FlatAutoStart") {
            fillLayer(SoftWhite) { circlePath(12.0f, 12.0f, 8.4f) }
            fillLayer(PureWhite) {
                moveTo(9.6f, 8.2f)
                curveTo(9.6f, 7.3f, 10.6f, 6.8f, 11.3f, 7.3f)
                lineTo(16.7f, 11.1f)
                curveTo(17.4f, 11.6f, 17.4f, 12.4f, 16.7f, 12.9f)
                lineTo(11.3f, 16.7f)
                curveTo(10.6f, 17.2f, 9.6f, 16.7f, 9.6f, 15.8f)
                close()
            }
        }
    }

    val TaskLock: ImageVector by lazy {
        flatVector("FlatTaskLock") {
            strokeLayer(SoftWhite, width = 2.4f) {
                moveTo(8.4f, 10.6f)
                lineTo(8.4f, 8.2f)
                arcTo(3.6f, 3.6f, 0f, false, true, 15.6f, 8.2f)
                lineTo(15.6f, 10.6f)
            }
            path(
                fill = SolidColor(PureWhite),
                pathFillType = PathFillType.EvenOdd
            ) {
                roundedRectPath(5.8f, 10.4f, 18.2f, 19.6f, 3.2f)
                circlePath(12.0f, 15.0f, 1.55f)
            }
        }
    }

    val Accessibility: ImageVector by lazy {
        flatVector("FlatAccessibility") {
            fillLayer(SoftWhite) { circlePath(12.0f, 12.0f, 8.0f) }
            fillLayer(PureWhite) { circlePath(12.0f, 12.0f, 3.9f) }
        }
    }

    val DeviceAdmin: ImageVector by lazy {
        flatVector("FlatDeviceAdmin") {
            fillLayer(SoftWhite) { shieldPath() }
            fillLayer(PureWhite) { sparklePath(12.0f, 12.0f, 4.1f, 1.45f) }
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
    moveTo(12.0f, 3.6f)
    curveTo(15.4f, 4.8f, 18.4f, 5.3f, 19.4f, 5.6f)
    lineTo(19.4f, 11.4f)
    curveTo(19.4f, 16.0f, 15.8f, 19.0f, 12.0f, 20.5f)
    curveTo(8.2f, 19.0f, 4.6f, 16.0f, 4.6f, 11.4f)
    lineTo(4.6f, 5.6f)
    curveTo(5.6f, 5.3f, 8.6f, 4.8f, 12.0f, 3.6f)
    close()
}
