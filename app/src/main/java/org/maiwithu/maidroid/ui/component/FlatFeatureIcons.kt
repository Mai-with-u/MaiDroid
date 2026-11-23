package org.maiwithu.maidroid.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.maiwithu.maidroid.ui.theme.TextPrimary

private val IconForeground = TextPrimary
private val IconSecondary = TextPrimary.copy(alpha = 0.56f)

/**
 * 清新扁平圆形功能图标徽章：明快实色圆底搭配双层柔白+纯白圆润几何图标。
 */
@Composable
internal fun FlatFeatureIconBadge(
    icon: ImageVector,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    iconSize: Dp = 25.dp,
    dimmed: Boolean = false
) {
    val bg = if (dimmed) TextPrimary.copy(alpha = 0.12f) else backgroundColor
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
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
            fillLayer(IconSecondary) { roundedRectPath(2.8f, 4.0f, 21.2f, 20.0f, 4.2f) }
            fillLayer(IconForeground) {
                roundedRectPath(5.6f, 6.5f, 14.0f, 8.5f, 1.0f)
                circlePath(17.6f, 7.5f, 1.05f)
                sparklePath(12.0f, 14.3f, 4.7f, 1.6f)
            }
        }
    }

    /** 协议端管理页或内置网页控制台入口图标（独立于专指麦麦的 WebUi）。 */
    val BrowserConsole: ImageVector by lazy {
        flatVector("FlatBrowserConsole") {
            fillLayer(IconSecondary) { roundedRectPath(2.8f, 4.0f, 21.2f, 20.0f, 4.0f) }
            fillLayer(IconForeground) {
                circlePath(6.0f, 7.0f, 1.0f)
                circlePath(9.0f, 7.0f, 1.0f)
                circlePath(12.0f, 7.0f, 1.0f)
                roundedRectPath(5.4f, 10.2f, 18.6f, 17.2f, 2.2f)
            }
        }
    }

    /** 虚拟桌面或远程应用窗口查看图标（用于 SnowLuma 查看 QQ 窗口）。 */
    val DesktopWindow: ImageVector by lazy {
        flatVector("FlatDesktopWindow") {
            fillLayer(IconSecondary) { roundedRectPath(2.8f, 4.2f, 21.2f, 17.8f, 3.4f) }
            fillLayer(IconForeground) {
                roundedRectPath(5.4f, 7.0f, 18.6f, 15.0f, 2.0f)
                roundedRectPath(8.6f, 19.0f, 15.4f, 20.8f, 0.9f)
            }
        }
    }

    /** 首次引导后台保活总卡片图标：圆环常驻轮廓配前景能量闪电。 */
    val BackgroundKeepAlive: ImageVector by lazy {
        flatVector("FlatBackgroundKeepAlive") {
            fillLayer(IconSecondary) { circlePath(12.0f, 12.0f, 9.2f) }
            fillLayer(IconForeground) {
                moveTo(13.2f, 6.2f)
                lineTo(8.3f, 12.8f)
                lineTo(11.6f, 12.8f)
                lineTo(10.5f, 17.8f)
                lineTo(15.7f, 11.2f)
                lineTo(12.3f, 11.2f)
                close()
            }
        }
    }

    val MaiBotUpdate: ImageVector by lazy {
        flatVector("FlatMaiBotUpdate") {
            fillLayer(IconSecondary) {
                circlePath(7.3f, 12.8f, 3.9f)
                circlePath(12.2f, 9.0f, 4.8f)
                circlePath(16.8f, 12.6f, 3.7f)
                roundedRectPath(3.4f, 10.2f, 20.6f, 16.8f, 3.2f)
            }
            strokeLayer(IconForeground, width = 2.5f) {
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
            fillLayer(IconSecondary) { roundedRectPath(2.6f, 4.2f, 21.4f, 19.8f, 4.2f) }
            strokeLayer(IconForeground, width = 2.4f) {
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
            fillLayer(IconSecondary) {
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
            fillLayer(IconForeground) { roundedRectPath(2.8f, 8.6f, 21.2f, 19.4f, 2.8f) }
        }
    }

    val Permissions: ImageVector by lazy {
        flatVector("FlatPermissions") {
            fillLayer(IconSecondary) { shieldPath() }
            strokeLayer(IconForeground, width = 2.6f) {
                moveTo(8.0f, 12.2f)
                lineTo(10.8f, 15.0f)
                lineTo(16.2f, 9.2f)
            }
        }
    }

    val Platforms: ImageVector by lazy {
        flatVector("FlatPlatforms") {
            fillLayer(IconSecondary) {
                roundedRectPath(8.8f, 3.8f, 21.4f, 14.0f, 4.5f)
                circlePath(19.2f, 13.8f, 1.7f)
            }
            fillLayer(IconForeground) {
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
            fillLayer(IconSecondary) {
                sparklePath(18.0f, 5.8f, 3.3f, 1.15f)
                circlePath(4.8f, 6.4f, 1.35f)
            }
            fillLayer(IconForeground) { sparklePath(10.2f, 13.4f, 7.2f, 2.45f) }
        }
    }

    val Feedback: ImageVector by lazy {
        flatVector("FlatFeedback") {
            fillLayer(IconSecondary) {
                moveTo(10.2f, 13.5f)
                lineTo(11.0f, 19.2f)
                lineTo(14.3f, 15.8f)
                close()
            }
            fillLayer(IconForeground) {
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

    /** 用户反馈群图标：后排柔白群友与前排纯白群友叠合，表达群聊互助交流。 */
    val QqGroup: ImageVector by lazy {
        flatVector("FlatQqGroup") {
            fillLayer(IconSecondary) {
                circlePath(16.2f, 8.2f, 2.7f)
                moveTo(12.2f, 18.2f)
                curveTo(12.2f, 14.8f, 14.0f, 12.8f, 16.5f, 12.8f)
                curveTo(19.0f, 12.8f, 20.8f, 14.8f, 20.8f, 18.2f)
                curveTo(20.8f, 19.0f, 20.1f, 19.6f, 19.3f, 19.6f)
                lineTo(13.7f, 19.6f)
                curveTo(12.9f, 19.6f, 12.2f, 19.0f, 12.2f, 18.2f)
                close()
            }
            fillLayer(IconForeground) {
                circlePath(9.2f, 7.6f, 3.3f)
                moveTo(3.4f, 18.6f)
                curveTo(3.4f, 14.4f, 5.8f, 12.2f, 9.2f, 12.2f)
                curveTo(12.6f, 12.2f, 15.0f, 14.4f, 15.0f, 18.6f)
                curveTo(15.0f, 19.6f, 14.2f, 20.2f, 13.2f, 20.2f)
                lineTo(5.2f, 20.2f)
                curveTo(4.2f, 20.2f, 3.4f, 19.6f, 3.4f, 18.6f)
                close()
            }
        }
    }

    /** 匿名运行统计图标：柔白双短柱配纯白主趋势高柱，表达轻量运行健康统计。 */
    val Telemetry: ImageVector by lazy {
        flatVector("FlatTelemetry") {
            fillLayer(IconSecondary) {
                roundedRectPath(4.2f, 12.6f, 8.2f, 19.6f, 1.8f)
                roundedRectPath(10.0f, 9.2f, 14.0f, 19.6f, 1.8f)
            }
            fillLayer(IconForeground) {
                roundedRectPath(15.8f, 5.2f, 19.8f, 19.6f, 1.8f)
            }
        }
    }

    val Cleanup: ImageVector by lazy {
        flatVector("FlatCleanup") {
            // 斜置小扫帚手柄与闪烁星点用柔白层，前景圆头束腰和扇形扫头用纯白层
            strokeLayer(IconSecondary, width = 2.6f) {
                moveTo(14.0f, 11.0f)
                lineTo(19.6f, 5.4f)
            }
            fillLayer(IconSecondary) {
                sparklePath(6.0f, 6.4f, 2.5f, 0.85f)
                sparklePath(17.6f, 17.4f, 2.3f, 0.8f)
            }
            strokeLayer(IconForeground, width = 2.4f) {
                moveTo(11.0f, 9.2f)
                lineTo(14.8f, 13.0f)
            }
            fillLayer(IconForeground) {
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
            fillLayer(IconSecondary) {
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
            fillLayer(IconForeground) {
                roundedRectPath(7.8f, 6.0f, 9.4f, 9.8f, 0.8f)
                roundedRectPath(11.0f, 6.0f, 12.6f, 9.8f, 0.8f)
                roundedRectPath(14.2f, 7.0f, 15.8f, 9.8f, 0.8f)
                roundedRectPath(7.0f, 13.8f, 16.6f, 17.4f, 1.6f)
            }
        }
    }

    val NotificationKeepAlive: ImageVector by lazy {
        flatVector("FlatNotificationKeepAlive") {
            fillLayer(IconSecondary) {
                circlePath(12.0f, 4.2f, 1.55f)
                roundedRectPath(9.4f, 17.8f, 14.6f, 20.2f, 1.2f)
            }
            fillLayer(IconForeground) {
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
            fillLayer(IconSecondary) {
                roundedRectPath(9.4f, 2.8f, 14.6f, 4.6f, 0.9f)
                roundedRectPath(6.0f, 5.0f, 18.0f, 20.8f, 3.3f)
            }
            fillLayer(IconForeground) {
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
            fillLayer(IconSecondary) { circlePath(12.0f, 12.0f, 9.2f) }
            fillLayer(IconForeground) {
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
            strokeLayer(IconSecondary, width = 2.6f) {
                moveTo(7.8f, 10.2f)
                lineTo(7.8f, 7.5f)
                arcTo(4.2f, 4.2f, 0f, false, true, 16.2f, 7.5f)
                lineTo(16.2f, 10.2f)
            }
            path(
                fill = SolidColor(IconForeground),
                pathFillType = PathFillType.EvenOdd
            ) {
                roundedRectPath(5.0f, 10.0f, 19.0f, 20.4f, 3.6f)
                circlePath(12.0f, 15.2f, 1.75f)
            }
        }
    }

    val Accessibility: ImageVector by lazy {
        flatVector("FlatAccessibility") {
            fillLayer(IconSecondary) { circlePath(12.0f, 12.0f, 8.8f) }
            fillLayer(IconForeground) { circlePath(12.0f, 12.0f, 4.3f) }
        }
    }

    val DeviceAdmin: ImageVector by lazy {
        flatVector("FlatDeviceAdmin") {
            fillLayer(IconSecondary) { shieldPath() }
            fillLayer(IconForeground) { sparklePath(12.0f, 12.0f, 4.7f, 1.65f) }
        }
    }

    /** 悬浮桌宠图标：圆润猫耳麦麦剪影与灵动笑眼，专用于「扩展组件」悬浮桌宠卡片。 */
    val DesktopPet: ImageVector by lazy {
        flatVector("FlatDesktopPet") {
            fillLayer(IconSecondary) {
                moveTo(4.4f, 12.2f)
                curveTo(4.1f, 8.8f, 4.6f, 4.8f, 5.8f, 4.5f)
                curveTo(6.9f, 4.2f, 8.6f, 5.8f, 9.5f, 6.9f)
                curveTo(11.1f, 6.4f, 12.9f, 6.4f, 14.5f, 6.9f)
                curveTo(15.4f, 5.8f, 17.1f, 4.2f, 18.2f, 4.5f)
                curveTo(19.4f, 4.8f, 19.9f, 8.8f, 19.6f, 12.2f)
                curveTo(19.8f, 16.9f, 16.8f, 19.8f, 12.0f, 19.8f)
                curveTo(7.2f, 19.8f, 4.2f, 16.9f, 4.4f, 12.2f)
                close()
            }
            fillLayer(IconForeground) {
                roundedRectPath(7.8f, 10.4f, 9.8f, 13.4f, 1.0f)
                roundedRectPath(14.2f, 10.4f, 16.2f, 13.4f, 1.0f)
            }
            strokeLayer(IconForeground, width = 1.85f) {
                moveTo(9.8f, 15.1f)
                quadTo(10.9f, 16.4f, 12.0f, 15.1f)
                quadTo(13.1f, 16.4f, 14.2f, 15.1f)
            }
        }
    }

    /** 语音合成（TTS）扩展图标：柔白声学圆底配前景三柱居中声纹，表达语音部署与音色合成。 */
    val TtsVoice: ImageVector by lazy {
        flatVector("FlatTtsVoice") {
            fillLayer(IconSecondary) {
                roundedRectPath(3.0f, 3.6f, 21.0f, 20.4f, 6.2f)
            }
            fillLayer(IconForeground) {
                roundedRectPath(6.6f, 9.6f, 9.0f, 14.4f, 1.2f)
                roundedRectPath(10.8f, 6.8f, 13.2f, 17.2f, 1.2f)
                roundedRectPath(15.0f, 8.6f, 17.4f, 15.4f, 1.2f)
            }
        }
    }

    /** 生态联动桥接图标：斜向双主节点连桥与次级卫星节点，表达外部麦系工具协同。 */
    val EcosystemBridge: ImageVector by lazy {
        flatVector("FlatEcosystemBridge") {
            strokeLayer(IconSecondary, width = 2.6f) {
                moveTo(7.5f, 16.5f)
                lineTo(16.5f, 7.5f)
            }
            fillLayer(IconSecondary) {
                circlePath(6.8f, 6.8f, 2.3f)
                circlePath(17.2f, 17.2f, 2.3f)
            }
            fillLayer(IconForeground) {
                circlePath(7.5f, 16.5f, 3.3f)
                circlePath(16.5f, 7.5f, 3.3f)
                sparklePath(12.0f, 12.0f, 2.5f, 0.9f)
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
