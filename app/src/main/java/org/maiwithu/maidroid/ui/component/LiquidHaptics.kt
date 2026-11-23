package org.maiwithu.maidroid.ui.component

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.CombinedVibration
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private const val TabStepMinIntervalMs = 24L
private const val DefaultHapticHysteresis = 0.06f

/** 马达硬件能力快照，用于独立于系统框架做纯函数分档测试与机型自适应挑选。 */
internal data class VibratorCapabilities(
    val sdkInt: Int,
    val hasVibrator: Boolean,
    val supportsPrimitiveTick: Boolean,
    val supportsPrimitiveLowTick: Boolean,
    val supportsPrimitiveClick: Boolean,
    val supportsEffectTick: Boolean,
    val hasAmplitudeControl: Boolean
)

/** 触觉波形分档：按硬件精细度从高到低降级，确保线性马达清脆“哒”、转子马达也有短促反馈。 */
internal enum class LiquidHapticTier {
    None,
    PrimitiveTick,
    PrimitiveLowTick,
    PrimitiveClick,
    PredefinedTick,
    AmplitudePulse,
    RotorPulse
}

/**
 * 探测硬件能力后选择最优触觉档位：
 * - 线性马达（API 30+ 且支持刻度/轻击原语）优先使用极短机械原语，敲出清脆利落的“哒”；
 * - 仅支持预置效果或振幅控制的机型次之；
 * - 无原语也无振幅控制的传统转子马达走短促固定脉冲兜底，避免完全没手感。
 */
internal fun selectLiquidHapticTier(capabilities: VibratorCapabilities): LiquidHapticTier = when {
    !capabilities.hasVibrator -> LiquidHapticTier.None
    capabilities.sdkInt >= Build.VERSION_CODES.R && capabilities.supportsPrimitiveTick -> LiquidHapticTier.PrimitiveTick
    capabilities.sdkInt >= Build.VERSION_CODES.S && capabilities.supportsPrimitiveLowTick -> LiquidHapticTier.PrimitiveLowTick
    capabilities.sdkInt >= Build.VERSION_CODES.R && capabilities.supportsPrimitiveClick -> LiquidHapticTier.PrimitiveClick
    capabilities.sdkInt >= Build.VERSION_CODES.Q && capabilities.supportsEffectTick -> LiquidHapticTier.PredefinedTick
    capabilities.hasAmplitudeControl -> LiquidHapticTier.AmplitudePulse
    else -> LiquidHapticTier.RotorPulse
}

/**
 * 计算拖拽滑动过程中的滞回刻度槽位：
 * 在相邻两格的中点线（`±0.5f`）外侧留出 `[hysteresis]` 死区，手指在中点附近微颤时不会连续乱震，
 * 一旦越过中点+死区立刻锁入新格触发一次触觉反馈，快速甩动跨越多格也能准确落到对应终点格。
 */
internal fun resolveLiquidHapticSlot(
    currentSlot: Int,
    continuousValue: Float,
    itemCount: Int,
    hysteresis: Float = DefaultHapticHysteresis
): Int {
    if (itemCount <= 1) return 0
    val maxSlot = itemCount - 1
    val safeCurrent = currentSlot.coerceIn(0, maxSlot)
    val clampedValue = continuousValue.coerceIn(0f, maxSlot.toFloat())
    val safeHysteresis = hysteresis.coerceIn(0f, 0.45f)

    val nearest = clampedValue.roundToInt().coerceIn(0, maxSlot)
    if (nearest == safeCurrent) return safeCurrent

    val rightThreshold = safeCurrent + 0.5f + safeHysteresis
    val leftThreshold = safeCurrent - 0.5f - safeHysteresis
    return when {
        clampedValue >= rightThreshold ->
            // 加极小容差抵消十进制小数在二进制浮点中的舍入误差，确保恰好落在阈值上也能精准跨入目标格
            floor(clampedValue + 0.5f - safeHysteresis + 1e-4f).toInt().coerceIn(safeCurrent + 1, maxSlot)
        clampedValue <= leftThreshold ->
            ceil(clampedValue - 0.5f + safeHysteresis - 1e-4f).toInt().coerceIn(0, safeCurrent - 1)
        else -> safeCurrent
    }
}

/** 记住当前界面上下文绑定的触觉控制器，供导航栏、侧栏和分段滑块跨格复用。 */
@Composable
internal fun rememberLiquidHaptics(): LiquidHaptics {
    val context = LocalContext.current
    val view = LocalView.current
    return remember(context, view) { LiquidHaptics(context, view) }
}

/** 液态玻璃滑块触觉播放器：屏蔽 Android 版本与马达硬件差异，同时自带最小间隔节流与视图兜底。 */
internal class LiquidHaptics(
    context: Context,
    private val view: View
) {
    private val vibratorManager: VibratorManager? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)
    } else {
        null
    }

    @Suppress("DEPRECATION")
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        vibratorManager?.defaultVibrator
    } else {
        context.getSystemService(Vibrator::class.java)
    }

    private val capabilities: VibratorCapabilities = probeCapabilities(vibrator)
    private val tier: LiquidHapticTier = selectLiquidHapticTier(capabilities)
    private val effect: VibrationEffect? = createEffectForTier(tier)
    private var lastTriggerRealtimeMs: Long = 0L

    /** 滑块跨入新选项或点击切换选项时触发一次短促刻度震动。 */
    fun performTabStep() {
        val targetEffect = effect ?: return
        val now = SystemClock.elapsedRealtime()
        // 连续急滑跨格时做最小时间间隔保护，避免多发脉冲挤成一段糊震
        if (now - lastTriggerRealtimeMs < TabStepMinIntervalMs) return
        lastTriggerRealtimeMs = now

        val played = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && vibratorManager != null) {
                // API 31-32 没有 Vibrator(effect, VibrationAttributes) 重载，统一经由 VibratorManager 显式声明触摸触觉用途
                val attributes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH)
                } else {
                    VibrationAttributes.Builder()
                        .setUsage(VibrationAttributes.USAGE_TOUCH)
                        .build()
                }
                vibratorManager.vibrate(CombinedVibration.createParallel(targetEffect), attributes)
            } else if (vibrator != null) {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                @Suppress("DEPRECATION")
                vibrator.vibrate(targetEffect, audioAttributes)
            }
        }.isSuccess

        // 个别定制 ROM 拦截直接 Vibrator 调用时，退回视图层标准时钟刻度反馈
        if (!played) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    private fun probeCapabilities(target: Vibrator?): VibratorCapabilities {
        if (target == null || !target.hasVibrator()) {
            return VibratorCapabilities(
                sdkInt = Build.VERSION.SDK_INT,
                hasVibrator = false,
                supportsPrimitiveTick = false,
                supportsPrimitiveLowTick = false,
                supportsPrimitiveClick = false,
                supportsEffectTick = false,
                hasAmplitudeControl = false
            )
        }
        val sdkInt = Build.VERSION.SDK_INT
        val supportsPrimitiveTick = sdkInt >= Build.VERSION_CODES.R &&
            target.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK)
        val supportsPrimitiveLowTick = sdkInt >= Build.VERSION_CODES.S &&
            target.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)
        val supportsPrimitiveClick = sdkInt >= Build.VERSION_CODES.R &&
            target.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)
        val supportsEffectTick = when {
            sdkInt >= Build.VERSION_CODES.R ->
                target.areAllEffectsSupported(VibrationEffect.EFFECT_TICK) == Vibrator.VIBRATION_EFFECT_SUPPORT_YES
            sdkInt >= Build.VERSION_CODES.Q -> target.hasAmplitudeControl()
            else -> false
        }
        return VibratorCapabilities(
            sdkInt = sdkInt,
            hasVibrator = true,
            supportsPrimitiveTick = supportsPrimitiveTick,
            supportsPrimitiveLowTick = supportsPrimitiveLowTick,
            supportsPrimitiveClick = supportsPrimitiveClick,
            supportsEffectTick = supportsEffectTick,
            hasAmplitudeControl = target.hasAmplitudeControl()
        )
    }

    private fun createEffectForTier(targetTier: LiquidHapticTier): VibrationEffect? = when (targetTier) {
        LiquidHapticTier.None -> null
        LiquidHapticTier.PrimitiveTick -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.72f)
                .compose()
        } else {
            VibrationEffect.createOneShot(14L, 145)
        }
        LiquidHapticTier.PrimitiveLowTick -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.85f)
                .compose()
        } else {
            VibrationEffect.createOneShot(14L, 145)
        }
        LiquidHapticTier.PrimitiveClick -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.45f)
                .compose()
        } else {
            VibrationEffect.createOneShot(14L, 145)
        }
        LiquidHapticTier.PredefinedTick -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        } else {
            VibrationEffect.createOneShot(14L, 145)
        }
        LiquidHapticTier.AmplitudePulse -> VibrationEffect.createOneShot(14L, 145)
        LiquidHapticTier.RotorPulse -> VibrationEffect.createOneShot(26L, VibrationEffect.DEFAULT_AMPLITUDE)
    }
}
