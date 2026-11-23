package org.maiwithu.maidroid.ui.home

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Test

class PillTiltDamperTest {

    private val stepNanos = 50_000_000L

    @Test
    fun `flat phone posture preserves both left and right dock sides`() {
        val flatPostures = listOf(
            Triple(0f, 0f, 9.81f),
            Triple(1.6f, 0.8f, 9.6f),
            Triple(0.5f, -0.5f, -9.7f)
        )
        PillDockSide.entries.forEach { initialSide ->
            val damper = PillTiltDamper()
            var side = initialSide
            var now = 1_000_000_000L
            flatPostures.forEach { (gx, gy, gz) ->
                repeat(20) {
                    side = damper.onSensorSample(
                        rawX = gx,
                        rawY = gy,
                        rawZ = gz,
                        timestampNanos = now,
                        currentSide = side
                    )
                    now += stepNanos
                    assertEquals(initialSide, side)
                }
            }
        }
    }

    @Test
    fun `brief shake tilt does not switch dock side`() {
        val damper = PillTiltDamper()
        var now = 1_000_000_000L
        var side = PillDockSide.Left

        // 先稳持竖直状态建好滤波基线，再短促向右甩动 200ms 后立刻回正
        side = damper.onSensorSample(0f, 8.8f, 4.3f, now, currentSide = side)
        repeat(4) {
            now += stepNanos
            side = damper.onSensorSample(-4.5f, 8.5f, 2.0f, now, currentSide = side)
            assertEquals(PillDockSide.Left, side)
        }
        repeat(10) {
            now += stepNanos
            side = damper.onSensorSample(0f, 8.8f, 4.3f, now, currentSide = side)
            assertEquals(PillDockSide.Left, side)
        }
    }

    @Test
    fun `sustained tilt switches side with dwell damping and cooldown`() {
        val damper = PillTiltDamper()
        var now = 1_000_000_000L
        var side = PillDockSide.Left

        // 先稳定在竖直正姿，再持续向右倾斜：前 150ms 仍在阻尼过滤期不得秒切，500ms 内平稳切到右侧
        side = damper.onSensorSample(0f, 8.8f, 4.3f, now, currentSide = side)
        repeat(3) {
            now += stepNanos
            side = damper.onSensorSample(-3.8f, 8.8f, 2.0f, now, currentSide = side)
            assertEquals(PillDockSide.Left, side)
        }
        repeat(7) {
            now += stepNanos
            side = damper.onSensorSample(-3.8f, 8.8f, 2.0f, now, currentSide = side)
        }
        assertEquals(PillDockSide.Right, side)

        // 切至右侧后的 0.70s 冷却期内即便向左倾斜也不回弹，待冷却结束持续左倾再平滑切回左侧
        repeat(12) {
            now += stepNanos
            side = damper.onSensorSample(3.8f, 8.8f, 2.0f, now, currentSide = side)
            assertEquals(PillDockSide.Right, side)
        }
        repeat(14) {
            now += stepNanos
            side = damper.onSensorSample(3.8f, 8.8f, 2.0f, now, currentSide = side)
        }
        assertEquals(PillDockSide.Left, side)
    }

    @Test
    fun `small angle hand switch triggers side change while near-upright hold stays steady`() {
        // 端正握持略微偏斜（约 3.8 度）持续 2 秒仍应保持原停靠边不误切
        val steadyDamper = PillTiltDamper()
        var now = 1_000_000_000L
        var steadySide = steadyDamper.onSensorSample(0f, 8.8f, 4.3f, now, currentSide = PillDockSide.Left)
        repeat(40) {
            now += stepNanos
            steadySide = steadyDamper.onSensorSample(-0.65f, 8.8f, 4.3f, now, currentSide = steadySide)
            assertEquals(PillDockSide.Left, steadySide)
        }

        // 换手轻幅偏转（从略左倾 +1.1 转为略右倾 -1.1，约 6.4 度）在 1 秒内平稳切换至右侧
        val switchDamper = PillTiltDamper()
        now = 1_000_000_000L
        var switchSide = switchDamper.onSensorSample(1.1f, 8.7f, 4.3f, now, currentSide = PillDockSide.Left)
        repeat(20) {
            now += stepNanos
            switchSide = switchDamper.onSensorSample(-1.1f, 8.7f, 4.3f, now, currentSide = switchSide)
        }
        assertEquals(PillDockSide.Right, switchSide)

        // 低俯角单手握持（距桌面约 18 度）时轻微右偏也在 1 秒内正常换边，不被平放阈值拦截
        val lowPitchDamper = PillTiltDamper()
        now = 1_000_000_000L
        var lowPitchSide = lowPitchDamper.onSensorSample(0f, 2.9f, 9.3f, now, currentSide = PillDockSide.Left)
        repeat(20) {
            now += stepNanos
            lowPitchSide = lowPitchDamper.onSensorSample(-1.2f, 2.9f, 9.3f, now, currentSide = lowPitchSide)
        }
        assertEquals(PillDockSide.Right, lowPitchSide)
    }

    @Test
    fun `walking sway oscillation does not switch dock side`() {
        val damper = PillTiltDamper()
        var now = 1_000_000_000L
        var side = damper.onSensorSample(0f, 8.8f, 4.3f, now, currentSide = PillDockSide.Left)

        // 模拟步行手持时每 150ms 左右来回晃动：过滤与瞬时余尾校验共同阻断误切
        repeat(10) {
            repeat(3) {
                now += stepNanos
                side = damper.onSensorSample(-1.5f, 8.7f, 4.3f, now, currentSide = side)
                assertEquals(PillDockSide.Left, side)
            }
            repeat(3) {
                now += stepNanos
                side = damper.onSensorSample(1.5f, 8.7f, 4.3f, now, currentSide = side)
                assertEquals(PillDockSide.Left, side)
            }
        }
    }

    @Test
    fun `manual dock locks against opposite tilt until phone returns neutral or flat`() {
        val damper = PillTiltDamper()
        var now = 1_000_000_000L
        // 手机保持左倾姿态，用户手动将胶囊拖到右侧并松手锁定
        var side = damper.onSensorSample(4.0f, 8.5f, 2.0f, now, currentSide = PillDockSide.Right)
        damper.onManualDock()

        // 保持左倾超过 2 秒也必须尊重手动停靠，绝不抢回左边
        repeat(45) {
            now += stepNanos
            side = damper.onSensorSample(4.0f, 8.5f, 2.0f, now, currentSide = side)
            assertEquals(PillDockSide.Right, side)
        }

        // 先平放回正一段时间解除锁定，之后再持续左倾才会正常吸附回左侧
        repeat(20) {
            now += stepNanos
            side = damper.onSensorSample(0f, 0f, 9.81f, now, currentSide = side)
            assertEquals(PillDockSide.Right, side)
        }
        repeat(24) {
            now += stepNanos
            side = damper.onSensorSample(4.0f, 8.5f, 2.0f, now, currentSide = side)
        }
        assertEquals(PillDockSide.Left, side)
    }

    @Test
    fun `interaction pause prevents switching during drag or expanded menu`() {
        val damper = PillTiltDamper()
        var now = 1_000_000_000L
        var side = PillDockSide.Left

        side = damper.onSensorSample(0f, 8.8f, 4.3f, now, currentSide = side, interactionPaused = true)
        repeat(30) {
            now += stepNanos
            side = damper.onSensorSample(
                rawX = -4.5f,
                rawY = 8.5f,
                rawZ = 2.0f,
                timestampNanos = now,
                currentSide = side,
                interactionPaused = true
            )
            assertEquals(PillDockSide.Left, side)
        }
    }

    @Test
    fun `surface rotation projects gravity axes to screen coordinates`() {
        val (gx0, gy0) = projectScreenGravity(1.5f, 8.0f, Surface.ROTATION_0)
        assertEquals(1.5f, gx0, 0.001f)
        assertEquals(8.0f, gy0, 0.001f)

        val (gx90, gy90) = projectScreenGravity(1.5f, 8.0f, Surface.ROTATION_90)
        assertEquals(-8.0f, gx90, 0.001f)
        assertEquals(1.5f, gy90, 0.001f)
    }
}
