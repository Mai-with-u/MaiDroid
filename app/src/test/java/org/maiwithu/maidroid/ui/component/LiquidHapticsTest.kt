package org.maiwithu.maidroid.ui.component

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test

class LiquidHapticsTest {

    @Test
    fun `haptic tier picks sharpest linear primitive first and falls back cleanly for rotor motors`() {
        assertEquals(
            LiquidHapticTier.None,
            selectLiquidHapticTier(
                VibratorCapabilities(
                    sdkInt = Build.VERSION_CODES.TIRAMISU,
                    hasVibrator = false,
                    supportsPrimitiveTick = true,
                    supportsPrimitiveLowTick = true,
                    supportsPrimitiveClick = true,
                    supportsEffectTick = true,
                    hasAmplitudeControl = true
                )
            )
        )

        // 具备线性马达 PRIMITIVE_TICK 原语的机型优先用最利落的“哒”
        assertEquals(
            LiquidHapticTier.PrimitiveTick,
            selectLiquidHapticTier(
                VibratorCapabilities(
                    sdkInt = Build.VERSION_CODES.R,
                    hasVibrator = true,
                    supportsPrimitiveTick = true,
                    supportsPrimitiveLowTick = true,
                    supportsPrimitiveClick = true,
                    supportsEffectTick = true,
                    hasAmplitudeControl = true
                )
            )
        )

        // 仅支持 LOW_TICK 的 Android 12+ 机型自动顺延至低音刻度原语
        assertEquals(
            LiquidHapticTier.PrimitiveLowTick,
            selectLiquidHapticTier(
                VibratorCapabilities(
                    sdkInt = Build.VERSION_CODES.S,
                    hasVibrator = true,
                    supportsPrimitiveTick = false,
                    supportsPrimitiveLowTick = true,
                    supportsPrimitiveClick = true,
                    supportsEffectTick = true,
                    hasAmplitudeControl = true
                )
            )
        )

        // 不支持 TICK/LOW_TICK 但支持 CLICK 的机型退到轻 CLICK
        assertEquals(
            LiquidHapticTier.PrimitiveClick,
            selectLiquidHapticTier(
                VibratorCapabilities(
                    sdkInt = Build.VERSION_CODES.R,
                    hasVibrator = true,
                    supportsPrimitiveTick = false,
                    supportsPrimitiveLowTick = false,
                    supportsPrimitiveClick = true,
                    supportsEffectTick = true,
                    hasAmplitudeControl = true
                )
            )
        )

        // 无原语但支持 EFFECT_TICK 预置波形（API 29+）
        assertEquals(
            LiquidHapticTier.PredefinedTick,
            selectLiquidHapticTier(
                VibratorCapabilities(
                    sdkInt = Build.VERSION_CODES.Q,
                    hasVibrator = true,
                    supportsPrimitiveTick = false,
                    supportsPrimitiveLowTick = false,
                    supportsPrimitiveClick = false,
                    supportsEffectTick = true,
                    hasAmplitudeControl = true
                )
            )
        )

        // 无系统预置但可调振幅的马达使用短促控幅脉冲
        assertEquals(
            LiquidHapticTier.AmplitudePulse,
            selectLiquidHapticTier(
                VibratorCapabilities(
                    sdkInt = Build.VERSION_CODES.O,
                    hasVibrator = true,
                    supportsPrimitiveTick = false,
                    supportsPrimitiveLowTick = false,
                    supportsPrimitiveClick = false,
                    supportsEffectTick = false,
                    hasAmplitudeControl = true
                )
            )
        )

        // 无原语、无预置、无振幅控制的传统转子马达走短促固定脉冲兜底
        assertEquals(
            LiquidHapticTier.RotorPulse,
            selectLiquidHapticTier(
                VibratorCapabilities(
                    sdkInt = Build.VERSION_CODES.S,
                    hasVibrator = true,
                    supportsPrimitiveTick = false,
                    supportsPrimitiveLowTick = false,
                    supportsPrimitiveClick = false,
                    supportsEffectTick = false,
                    hasAmplitudeControl = false
                )
            )
        )
    }

    @Test
    fun `haptic slot crosses on hysteresis threshold, resists midpoint jitter, and handles multi-slot jumps`() {
        // 右移过半后必须越过 0.06f 滞回死区才切换到第 2 格
        assertEquals(1, resolveLiquidHapticSlot(currentSlot = 1, continuousValue = 1.50f, itemCount = 4))
        assertEquals(1, resolveLiquidHapticSlot(currentSlot = 1, continuousValue = 1.55f, itemCount = 4))
        assertEquals(2, resolveLiquidHapticSlot(currentSlot = 1, continuousValue = 1.56f, itemCount = 4))

        // 左移过半后必须越过 0.06f 滞回死区才切回第 1 格（校验浮点边界 1.44f）
        assertEquals(2, resolveLiquidHapticSlot(currentSlot = 2, continuousValue = 1.50f, itemCount = 4))
        assertEquals(2, resolveLiquidHapticSlot(currentSlot = 2, continuousValue = 1.45f, itemCount = 4))
        assertEquals(1, resolveLiquidHapticSlot(currentSlot = 2, continuousValue = 1.44f, itemCount = 4))

        // 停在两格交界线附近来回微颤时不连续乱震
        var slot = 1
        listOf(1.48f, 1.53f, 1.46f, 1.55f, 1.44f).forEach { value ->
            slot = resolveLiquidHapticSlot(currentSlot = slot, continuousValue = value, itemCount = 4)
        }
        assertEquals(1, slot)

        // 快速甩动一次性跨越两格（含恰好踩在第二道滞回边界）直接锁入对应终点格
        assertEquals(2, resolveLiquidHapticSlot(currentSlot = 0, continuousValue = 2.559f, itemCount = 4))
        assertEquals(3, resolveLiquidHapticSlot(currentSlot = 0, continuousValue = 2.56f, itemCount = 4))
        assertEquals(2, resolveLiquidHapticSlot(currentSlot = 3, continuousValue = 1.441f, itemCount = 4))
        assertEquals(1, resolveLiquidHapticSlot(currentSlot = 3, continuousValue = 1.44f, itemCount = 4))
        assertEquals(0, resolveLiquidHapticSlot(currentSlot = 3, continuousValue = 0.40f, itemCount = 4))

        // 单项及越界输入安全钳位
        assertEquals(0, resolveLiquidHapticSlot(currentSlot = 2, continuousValue = 3.5f, itemCount = 1))
        assertEquals(3, resolveLiquidHapticSlot(currentSlot = 1, continuousValue = 99f, itemCount = 4))
        assertEquals(0, resolveLiquidHapticSlot(currentSlot = 2, continuousValue = -10f, itemCount = 4))
    }
}
