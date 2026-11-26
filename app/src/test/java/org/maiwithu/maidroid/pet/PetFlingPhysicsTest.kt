package org.maiwithu.maidroid.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun `stepWithImpact reports wall edges and predictRestingPosition matches frame simulation`() {
        val engine = PetFlingEngine()
        val density = 3f

        // 校验左/右/上/下四壁碰撞方向与法向速度记录
        assertTrue(engine.start(10f, 1000f, -1500f, 200f, density, defaultBounds))
        val leftImpact = engine.stepWithImpact(0.02f, density, defaultBounds).impact!!
        assertEquals(-1, leftImpact.horizontalEdge)
        assertEquals(0, leftImpact.verticalEdge)
        assertEquals(1500f, leftImpact.impactSpeedPxPerSec, 1f)

        assertTrue(engine.start(790f, 1990f, 1200f, 1800f, density, defaultBounds))
        val cornerImpact = engine.stepWithImpact(0.02f, density, defaultBounds).impact!!
        assertEquals(1, cornerImpact.horizontalEdge)
        assertEquals(1, cornerImpact.verticalEdge)
        assertEquals(1800f, cornerImpact.impactSpeedPxPerSec, 1f)

        // 校验确定性终点预测与 60fps 逐帧推进终点误差 < 1px
        val predicted = engine.predictRestingPosition(
            startX = 240f,
            startBottom = 900f,
            initialVx = 2400f,
            initialVy = -1900f,
            density = density,
            bounds = defaultBounds
        )
        assertTrue(engine.start(240f, 900f, 2400f, -1900f, density, defaultBounds))
        while (engine.step(1f / 60f, density, defaultBounds)) {
            // 持续推演直到停止
        }
        assertEquals(engine.x, predicted.x, 0.5f)
        assertEquals(engine.bottom, predicted.bottom, 0.5f)
    }

    @Test
    fun `bounce squash spring squashes along collided axis, clamps bounds and settles to identity`() {
        val spring = PetBounceSquashSpring()
        val density = 3f

        // 撞右墙：水平压扁、竖直拉长，X 支点贴右边
        spring.onImpact(PetWallImpact(horizontalEdge = 1, verticalEdge = 0, impactSpeedPxPerSec = 6000f), density)
        assertTrue(spring.isActive)
        assertTrue(spring.scaleX < 1f)
        assertTrue(spring.scaleY > 1f)
        assertEquals(1f, spring.pivotXFraction, 0.001f)
        assertEquals(0.5f, spring.pivotYFraction, 0.001f)

        // 撞底墙：竖直压扁、水平拉长，Y 支点贴底边
        spring.reset()
        spring.onImpact(PetWallImpact(horizontalEdge = 0, verticalEdge = 1, impactSpeedPxPerSec = 5000f), density)
        assertTrue(spring.isActive)
        assertTrue(spring.scaleY < 1f)
        assertTrue(spring.scaleX > 1f)
        assertEquals(0.5f, spring.pivotXFraction, 0.001f)
        assertEquals(1f, spring.pivotYFraction, 0.001f)

        var ticks = 0
        while (spring.step(1f / 60f) && ticks < 240) {
            assertTrue(spring.scaleX in PetBounceSquashSpring.MIN_SCALE..PetBounceSquashSpring.MAX_SCALE)
            assertTrue(spring.scaleY in PetBounceSquashSpring.MIN_SCALE..PetBounceSquashSpring.MAX_SCALE)
            ticks++
        }
        assertFalse(spring.isActive)
        assertEquals(1f, spring.scaleX, 0.001f)
        assertEquals(1f, spring.scaleY, 0.001f)
        assertTrue(ticks in 5..120)
    }

    @Test
    fun `resolveStableClipTop ignores tiny breathing jitter and reacts to menu expand and collapse`() {
        val fullHeight = 2000
        val minVisible = 3
        val headroom = 48
        val deadband = 72
        val baseline = 1352

        // 呼吸动画在 [1388, 1412] 范围内微小上下晃动，clipTop 必须保持纹丝不动
        assertEquals(baseline, resolveStableClipTop(baseline, 1388, fullHeight, minVisible, headroom, deadband))
        assertEquals(baseline, resolveStableClipTop(baseline, 1412, fullHeight, minVisible, headroom, deadband))

        // 弹出菜单/气泡导致内容顶边突破当前 clipTop，立即向上扩张预留 headroom
        assertEquals(852, resolveStableClipTop(baseline, 900, fullHeight, minVisible, headroom, deadband))

        // 关闭菜单内容回落出明显空白，立即向下收缩
        assertEquals(1352, resolveStableClipTop(852, 1400, fullHeight, minVisible, headroom, deadband))
    }

    @Test
    fun `parseCapturedCanvasPayload handles visible, builtin hidden, quoted and invalid payloads`() {
        assertNull(parseCapturedCanvasPayload(null))
        assertNull(parseCapturedCanvasPayload("null"))
        assertNull(parseCapturedCanvasPayload("not-json"))

        val hidden = parseCapturedCanvasPayload("""{"canvasVisible":false}""")
        assertFalse(hidden!!.canvasVisible)
        assertNull(hidden.pngBase64)

        val visibleJson = """{"canvasVisible":true,"left":20,"top":528.5,"width":220,"height":240,"dpr":3,"png":"DATA:IMAGE/PNG;BASE64, iVBORw0KGgo= "}"""
        val visible = parseCapturedCanvasPayload(visibleJson)!!
        assertTrue(visible.canvasVisible)
        assertEquals(20f, visible.leftCss, 0.001f)
        assertEquals(528.5f, visible.topCss, 0.001f)
        assertEquals(220f, visible.widthCss, 0.001f)
        assertEquals(240f, visible.heightCss, 0.001f)
        assertEquals(3f, visible.dpr, 0.001f)
        assertEquals("iVBORw0KGgo=", visible.pngBase64)

        // 兼容外层带 JSON 字符串转义的 evaluateJavascript 返回形态
        val wrapped = parseCapturedCanvasPayload(
            """"{\"canvasVisible\":true,\"left\":10,\"top\":20,\"width\":100,\"height\":120,\"dpr\":2,\"png\":\"data:image/png;base64,QUJD\"}""""
        )!!
        assertTrue(wrapped.canvasVisible)
        assertEquals("QUJD", wrapped.pngBase64)

        // 校验带可见气泡 say 矩形与 say: null/非法字段的兼容解析
        val withSay = parseCapturedCanvasPayload(
            """{"canvasVisible":true,"left":20,"top":536,"width":220,"height":240,"dpr":3,"say":{"left":10,"top":400,"width":240,"height":88},"png":"data:image/png;base64,QUJD"}"""
        )!!
        assertEquals(CssRect(10f, 400f, 240f, 88f), withSay.sayRectCss)

        val withNullSay = parseCapturedCanvasPayload(
            """{"canvasVisible":true,"left":20,"top":536,"width":220,"height":240,"dpr":3,"say":null,"png":"data:image/png;base64,QUJD"}"""
        )!!
        assertNull(withNullSay.sayRectCss)

        val withInvalidSay = parseCapturedCanvasPayload(
            """{"canvasVisible":true,"left":20,"top":536,"width":220,"height":240,"dpr":3,"say":{"left":10,"top":400,"width":0,"height":88},"png":"data:image/png;base64,QUJD"}"""
        )!!
        assertNull(withInvalidSay.sayRectCss)

        // 缺前缀或非正尺寸一律拒绝，避免舞台误当空画布隐藏真身
        assertNull(parseCapturedCanvasPayload("""{"canvasVisible":true,"left":0,"top":0,"width":220,"height":240,"dpr":3,"png":"invalid-url"}"""))
        assertNull(parseCapturedCanvasPayload("""{"canvasVisible":true,"left":0,"top":0,"width":0,"height":240,"dpr":3,"png":"data:image/png;base64,QUJD"}"""))
        assertNull(parseCapturedCanvasPayload("""{"canvasVisible":true,"left":0,"top":0,"width":220,"height":240,"dpr":3}"""))
    }

    @Test
    fun `computeDraggingSnapshotRefreshDelayMs waits for web transition and returns zero once settled`() {
        // 网页尚未回调 dragMove() 前继续等待，避免按进入舞台时间过早截下交叉过渡双影帧
        assertNull(computeDraggingSnapshotRefreshDelayMs(draggingStartedUptimeMs = 0L, nowUptimeMs = 1500L, settleDelayMs = 220L))

        // 刚进入网页拖动态时保留完整 220ms 过渡等待
        assertEquals(220L, computeDraggingSnapshotRefreshDelayMs(1000L, 1000L, 220L))

        // 抓帧延迟进舞台时仅等待剩余窗口
        assertEquals(140L, computeDraggingSnapshotRefreshDelayMs(1000L, 1080L, 220L))

        // 超过过渡收敛窗口后立即刷新，不返回负延迟
        assertEquals(0L, computeDraggingSnapshotRefreshDelayMs(1000L, 1300L, 220L))
    }

    @Test
    fun `remapPetPositionForBounds keeps bottom-docked pet inside screen after portrait to landscape rotation`() {
        val landscapeBounds = PetMotionBounds(minX = 0f, maxX = 2300f, minBottom = 360f, maxBottom = 1256f)
        // 竖屏拖至底部（petBottom = 2700）切换横屏后，底边坐标必须被夹紧到横屏底边 1256 以内
        val remapped = remapPetPositionForBounds(
            currentX = 720f,
            currentBottom = 2700f,
            bounds = landscapeBounds
        )
        assertEquals(720f, remapped.x, 0.001f)
        assertEquals(1256f, remapped.bottom, 0.001f)
    }

    @Test
    fun `remapPetPositionForBounds clamps horizontal coordinate from landscape back to portrait and guards invalid bounds`() {
        val portraitBounds = PetMotionBounds(minX = 0f, maxX = 756f, minBottom = 360f, maxBottom = 2808f)
        // 横屏拖至右侧（petX = 2200）切回竖屏后，X 坐标必须被夹紧到竖屏右边 756 以内
        val remapped = remapPetPositionForBounds(
            currentX = 2200f,
            currentBottom = 1100f,
            bounds = portraitBounds
        )
        assertEquals(756f, remapped.x, 0.001f)
        assertEquals(1100f, remapped.bottom, 0.001f)

        // 非法浮点与极矮视口边界倒挂兜底
        val collapsedBounds = PetMotionBounds(minX = 0f, maxX = 0f, minBottom = 400f, maxBottom = 400f)
        val fallback = remapPetPositionForBounds(
            currentX = Float.NaN,
            currentBottom = Float.POSITIVE_INFINITY,
            bounds = collapsedBounds
        )
        assertEquals(0f, fallback.x, 0.001f)
        assertEquals(400f, fallback.bottom, 0.001f)
    }

    @Test
    fun `clipHitRectToViewport clips edge overlap and drops completely offscreen rects`() {
        assertEquals(
            PixelRect(left = 100, top = 0, right = 400, bottom = 180),
            clipHitRectToViewport(left = 100, top = -40, right = 400, bottom = 180, viewportWidth = 2808, viewportHeight = 1256)
        )
        assertEquals(
            PixelRect(left = 200, top = 800, right = 500, bottom = 1100),
            clipHitRectToViewport(left = 200, top = 800, right = 500, bottom = 1100, viewportWidth = 2808, viewportHeight = 1256)
        )
        assertNull(
            clipHitRectToViewport(left = 100, top = 1300, right = 400, bottom = 1500, viewportWidth = 2808, viewportHeight = 1256)
        )
    }
}
