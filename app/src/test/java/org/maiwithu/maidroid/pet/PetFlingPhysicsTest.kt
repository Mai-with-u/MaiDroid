package org.maiwithu.maidroid.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.hypot

class PetFlingPhysicsTest {

    private val defaultBounds = PetMotionBounds(
        minX = 0f,
        maxX = 800f,
        minBottom = 300f,
        maxBottom = 2000f
    )

    @Test
    fun `velocity tracker measures constant velocity and preserves direction`() {
        val tracker = PetVelocityTracker()
        tracker.reset(100f, 200f, 0L)
        tracker.addSample(130f, 240f, 15L)
        tracker.addSample(160f, 280f, 30L)
        tracker.addSample(220f, 360f, 60L)

        val velocity = tracker.computeVelocity(upTimeMs = 65L, density = 3f)
        // 60ms 内 X 位移 +120px -> 2000 px/s，Y 位移 +160px -> 2666.67 px/s
        assertEquals(2000f, velocity.vx, 25f)
        assertEquals(2666.67f, velocity.vy, 25f)
    }

    @Test
    fun `velocity tracker zeros velocity after holding finger still before release`() {
        val tracker = PetVelocityTracker()
        tracker.reset(100f, 200f, 0L)
        tracker.addSample(220f, 300f, 20L)
        tracker.addSample(340f, 400f, 40L)
        // 停在目标位置 80ms 后再松手（超过 70ms），即使最后发生抬手回调也不触发惯性滑行
        tracker.addSample(340f, 400f, 120L)

        val velocity = tracker.computeVelocity(upTimeMs = 120L, density = 3f)
        assertEquals(0f, velocity.vx, 0.001f)
        assertEquals(0f, velocity.vy, 0.001f)
    }

    @Test
    fun `velocity tracker caps extreme flick speeds proportionally while preserving direction`() {
        val tracker = PetVelocityTracker()
        tracker.reset(0f, 0f, 0L)
        tracker.addSample(300f, 400f, 10L) // 合速度 50,000 px/s

        val density = 2f
        val velocity = tracker.computeVelocity(upTimeMs = 10L, density = density)
        val expectedMax = PetFlingEngine.MAX_LAUNCH_SPEED_DP_PER_SEC * density
        val actualSpeed = hypot(velocity.vx.toDouble(), velocity.vy.toDouble()).toFloat()

        assertEquals(expectedMax, actualSpeed, 1f)
        assertEquals(0.75f, velocity.vx / velocity.vy, 0.001f)
    }

    @Test
    fun `fling engine ignores slow release and decelerates smoothly to stop`() {
        val engine = PetFlingEngine()
        val density = 3f

        assertFalse(
            engine.start(
                startX = 200f,
                startBottom = 800f,
                initialVx = 100f * density,
                initialVy = 50f * density,
                density = density,
                bounds = defaultBounds
            )
        )
        assertFalse(engine.isRunning)

        assertTrue(
            engine.start(
                startX = 200f,
                startBottom = 800f,
                initialVx = 500f * density,
                initialVy = 0f,
                density = density,
                bounds = defaultBounds
            )
        )

        var frames = 0
        while (engine.step(1f / 60f, density, defaultBounds) && frames < 600) {
            frames++
        }
        assertFalse(engine.isRunning)
        assertTrue(frames in 20..300)
        assertTrue(engine.x > 200f)
    }

    @Test
    fun `fling engine bounces off left right top and bottom walls with restitution`() {
        val engine = PetFlingEngine()
        val density = 1f

        // 撞右墙并检验镜像位置、法向恢复系数与切向保留系数
        val dt = 0.02f
        val decay = exp((-PetFlingEngine.DAMPING_PER_SECOND * dt).toDouble()).toFloat()
        assertTrue(engine.start(790f, 1000f, 1000f, 500f, density, defaultBounds))
        assertTrue(engine.step(dt, density, defaultBounds))
        assertEquals(790f, engine.x, 0.001f)
        assertEquals(-1000f * PetFlingEngine.RESTITUTION * decay, engine.vx, 0.5f)
        assertEquals(500f * PetFlingEngine.WALL_TANGENTIAL_RETENTION * decay, engine.vy, 0.5f)

        // 撞左墙
        assertTrue(engine.start(10f, 1000f, -1000f, 0f, density, defaultBounds))
        assertTrue(engine.step(0.02f, density, defaultBounds))
        assertTrue(engine.x > 0f)
        assertTrue(engine.vx > 0f)

        // 撞上墙（minBottom）
        assertTrue(engine.start(400f, 310f, 0f, -1000f, density, defaultBounds))
        assertTrue(engine.step(0.02f, density, defaultBounds))
        assertTrue(engine.bottom > 300f)
        assertTrue(engine.vy > 0f)

        // 撞下墙（maxBottom）
        assertTrue(engine.start(400f, 1990f, 300f, 1000f, density, defaultBounds))
        assertTrue(engine.step(0.02f, density, defaultBounds))
        assertTrue(engine.bottom < 2000f)
        assertTrue(engine.vy < 0f)
        assertTrue(engine.vx > 0f)
    }

    @Test
    fun `fling engine stays clamped across multi-bounce step and collapsed narrow bounds`() {
        val engine = PetFlingEngine()
        val tightBounds = PetMotionBounds(minX = 0f, maxX = 40f, minBottom = 500f, maxBottom = 530f)

        assertTrue(engine.start(20f, 515f, 4000f, 4000f, density = 1f, bounds = tightBounds))
        repeat(10) {
            engine.step(0.03f, density = 1f, bounds = tightBounds)
            assertTrue(engine.x in tightBounds.minX..tightBounds.maxX)
            assertTrue(engine.bottom in tightBounds.minBottom..tightBounds.maxBottom)
        }

        val zeroSpanBounds = PetMotionBounds(minX = 120f, maxX = 120f, minBottom = 600f, maxBottom = 600f)
        assertTrue(engine.start(999f, 100f, 1000f, 1000f, density = 1f, bounds = zeroSpanBounds))
        assertFalse(engine.step(0.016f, density = 1f, bounds = zeroSpanBounds))
        assertEquals(120f, engine.x, 0.001f)
        assertEquals(600f, engine.bottom, 0.001f)
    }
}
