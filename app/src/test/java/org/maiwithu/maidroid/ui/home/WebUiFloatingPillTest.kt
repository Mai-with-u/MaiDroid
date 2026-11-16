package org.maiwithu.maidroid.ui.home

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebUiFloatingPillTest {

    private val stepNanos = 50_000_000L

    @Test
    fun `default dock side is left edge`() {
        assertFalse(DefaultDockRight)
    }

    @Test
    fun `pill docks to nearest horizontal side across container midpoint`() {
        assertFalse(shouldDockPillRight(centerX = 539f, containerWidth = 1080f))
        assertTrue(shouldDockPillRight(centerX = 540f, containerWidth = 1080f))
        assertTrue(shouldDockPillRight(centerX = 960f, containerWidth = 1080f))
    }

    @Test
    fun `vertical bias stays clamped inside safe range`() {
        assertEquals(0f, clampPillVerticalBias(-0.4f), 0.001f)
        assertEquals(0.65f, clampPillVerticalBias(0.65f), 0.001f)
        assertEquals(1f, clampPillVerticalBias(1.3f), 0.001f)
    }

    @Test
    fun `collapsed pill stays smaller than expanded pill and shifts up only near bottom`() {
        assertTrue(CollapsedPillWidthDp < ExpandedPillWidthDp)
        assertTrue(CollapsedPillHeightDp < ExpandedPillHeightDp)

        val upperCollapsedTop = computeDockedPillTopDp(
            minTopDp = 44f,
            maxHeightDp = 800f,
            bottomClearanceDp = 84f,
            collapsedHeightDp = CollapsedPillHeightDp,
            currentHeightDp = CollapsedPillHeightDp,
            verticalBias = 0.25f
        )
        val upperExpandedTop = computeDockedPillTopDp(
            minTopDp = 44f,
            maxHeightDp = 800f,
            bottomClearanceDp = 84f,
            collapsedHeightDp = CollapsedPillHeightDp,
            currentHeightDp = ExpandedPillHeightDp,
            verticalBias = 0.25f
        )
        assertEquals(upperCollapsedTop, upperExpandedTop, 0.001f)

        val bottomCollapsedTop = computeDockedPillTopDp(
            minTopDp = 44f,
            maxHeightDp = 800f,
            bottomClearanceDp = 84f,
            collapsedHeightDp = CollapsedPillHeightDp,
            currentHeightDp = CollapsedPillHeightDp,
            verticalBias = 1.0f
        )
        val bottomExpandedTop = computeDockedPillTopDp(
            minTopDp = 44f,
            maxHeightDp = 800f,
            bottomClearanceDp = 84f,
            collapsedHeightDp = CollapsedPillHeightDp,
            currentHeightDp = ExpandedPillHeightDp,
            verticalBias = 1.0f
        )
        assertTrue(bottomExpandedTop < bottomCollapsedTop)
        assertEquals(800f - 84f - ExpandedPillHeightDp, bottomExpandedTop, 0.001f)
    }

    @Test
    fun `flat phone posture preserves both left and right dock sides`() {
        val flatPostures = listOf(
            Triple(0f, 0f, 9.81f),
            Triple(1.6f, 0.8f, 9.6f),
            Triple(0.5f, -0.5f, -9.7f)
        )
        listOf(false, true).forEach { initialDockRight ->
            val damper = PillTiltDamper()
            var dockRight = initialDockRight
            var now = 1_000_000_000L
            flatPostures.forEach { (gx, gy, gz) ->
                repeat(20) {
                    dockRight = damper.onSensorSample(
                        rawX = gx,
                        rawY = gy,
                        rawZ = gz,
                        timestampNanos = now,
                        currentDockRight = dockRight
                    )
                    now += stepNanos
                    assertEquals(initialDockRight, dockRight)
                }
            }
        }
    }

    @Test
    fun `brief shake tilt does not switch dock side`() {
        val damper = PillTiltDamper()
        var now = 1_000_000_000L
        var dockRight = false

        // 先稳持竖直状态建好滤波基线，再短促向右甩动 200ms 后立刻回正
        dockRight = damper.onSensorSample(0f, 8.8f, 4.3f, now, currentDockRight = dockRight)
        repeat(4) {
            now += stepNanos
            dockRight = damper.onSensorSample(-4.5f, 8.5f, 2.0f, now, currentDockRight = dockRight)
            assertFalse(dockRight)
        }
        repeat(10) {
            now += stepNanos
            dockRight = damper.onSensorSample(0f, 8.8f, 4.3f, now, currentDockRight = dockRight)
            assertFalse(dockRight)
        }
    }

    @Test
    fun `sustained tilt switches side with dwell damping and cooldown`() {
        val damper = PillTiltDamper()
        var now = 1_000_000_000L
        var dockRight = false

        // 先稳定在竖直正姿，再持续向右倾斜：前 350ms 仍在阻尼过滤期不得秒切，1.2s 内平稳切到右侧
        dockRight = damper.onSensorSample(0f, 8.8f, 4.3f, now, currentDockRight = dockRight)
        repeat(7) {
            now += stepNanos
            dockRight = damper.onSensorSample(-3.8f, 8.8f, 2.0f, now, currentDockRight = dockRight)
            assertFalse(dockRight)
        }
        repeat(17) {
            now += stepNanos
            dockRight = damper.onSensorSample(-3.8f, 8.8f, 2.0f, now, currentDockRight = dockRight)
        }
        assertTrue(dockRight)

        // 切至右侧后的 1.2s 冷却期内即便向左倾斜也不回弹，待冷却结束持续左倾再平滑切回左侧
        repeat(18) {
            now += stepNanos
            dockRight = damper.onSensorSample(3.8f, 8.8f, 2.0f, now, currentDockRight = dockRight)
            assertTrue(dockRight)
        }
        repeat(20) {
            now += stepNanos
            dockRight = damper.onSensorSample(3.8f, 8.8f, 2.0f, now, currentDockRight = dockRight)
        }
        assertFalse(dockRight)
    }

    @Test
    fun `manual dock locks against opposite tilt until phone returns neutral or flat`() {
        val damper = PillTiltDamper()
        var now = 1_000_000_000L
        // 手机保持左倾姿态，用户手动将胶囊拖到右侧并松手锁定
        var dockRight = damper.onSensorSample(4.0f, 8.5f, 2.0f, now, currentDockRight = true)
        damper.onManualDock()

        // 保持左倾超过 2 秒也必须尊重手动停靠，绝不抢回左边
        repeat(45) {
            now += stepNanos
            dockRight = damper.onSensorSample(4.0f, 8.5f, 2.0f, now, currentDockRight = dockRight)
            assertTrue(dockRight)
        }

        // 先平放回正一段时间解除锁定，之后再持续左倾才会正常吸附回左侧
        repeat(20) {
            now += stepNanos
            dockRight = damper.onSensorSample(0f, 0f, 9.81f, now, currentDockRight = dockRight)
            assertTrue(dockRight)
        }
        repeat(24) {
            now += stepNanos
            dockRight = damper.onSensorSample(4.0f, 8.5f, 2.0f, now, currentDockRight = dockRight)
        }
        assertFalse(dockRight)
    }

    @Test
    fun `interaction pause prevents switching during drag or expanded menu`() {
        val damper = PillTiltDamper()
        var now = 1_000_000_000L
        var dockRight = false

        dockRight = damper.onSensorSample(0f, 8.8f, 4.3f, now, currentDockRight = dockRight, interactionPaused = true)
        repeat(30) {
            now += stepNanos
            dockRight = damper.onSensorSample(
                rawX = -4.5f,
                rawY = 8.5f,
                rawZ = 2.0f,
                timestampNanos = now,
                currentDockRight = dockRight,
                interactionPaused = true
            )
            assertFalse(dockRight)
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
