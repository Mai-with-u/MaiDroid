package org.maiwithu.maidroid.pet

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot

/** 悬浮桌宠窗口左上角 `x` 与底边 `bottom` 的屏幕可移动闭合边界（像素）。 */
internal data class PetMotionBounds(
    val minX: Float,
    val maxX: Float,
    val minBottom: Float,
    val maxBottom: Float
)

/** 桌宠抬手或滑行瞬间的二维屏幕像素速度（px/s）。 */
internal data class PetVelocity(
    val vx: Float,
    val vy: Float
)

/**
 * 基于屏幕绝对坐标（`rawX` / `rawY`）的轻量速度估算器：
 * 悬浮窗自身在拖动时位置会实时改变，系统 `VelocityTracker` 依赖窗口局部坐标会产生跳变，
 * 因此必须直接采样屏幕坐标并过滤抬手前短暂停稳的精准摆放动作。
 */
internal class PetVelocityTracker {
    private data class Sample(
        val x: Float,
        val y: Float,
        val timeMs: Long
    )

    private val samples = ArrayList<Sample>(MAX_SAMPLES)
    private var lastMeaningfulMoveTimeMs = 0L

    /** 清空历史样本，准备跟踪新一次按下后的拖动轨迹。 */
    fun clear() {
        samples.clear()
        lastMeaningfulMoveTimeMs = 0L
    }

    /** 按下时重置采样窗并写入初始屏幕坐标。 */
    fun reset(x: Float, y: Float, timeMs: Long) {
        clear()
        if (!x.isFinite() || !y.isFinite()) return
        samples.add(Sample(x = x, y = y, timeMs = timeMs))
        lastMeaningfulMoveTimeMs = timeMs
    }

    /** 追加拖动/抬手样本，剔除超过滑动时间窗的陈旧坐标，并记录最近一次有效位移时刻。 */
    fun addSample(x: Float, y: Float, timeMs: Long) {
        if (!x.isFinite() || !y.isFinite()) return
        val prev = samples.lastOrNull()
        if (prev != null && timeMs < prev.timeMs) return

        val movedDistance = if (prev != null) {
            hypot((x - prev.x).toDouble(), (y - prev.y).toDouble()).toFloat()
        } else {
            0f
        }
        if (prev == null || movedDistance >= MEANINGFUL_MOVE_PX) {
            lastMeaningfulMoveTimeMs = timeMs
        }

        if (prev != null && timeMs == prev.timeMs) {
            samples[samples.lastIndex] = Sample(x = x, y = y, timeMs = timeMs)
        } else {
            samples.add(Sample(x = x, y = y, timeMs = timeMs))
        }

        while (samples.size > 1 &&
            (samples.size > MAX_SAMPLES || timeMs - samples.first().timeMs > WINDOW_MS)
        ) {
            samples.removeAt(0)
        }
    }

    /**
     * 计算抬手瞬间的屏幕像素速度（px/s）。
     * 若抬手前手指已停稳超过 [PAUSE_CANCEL_MS]，视为用户精准摆放位置，返回零速度不触发滑行；
     * 若高速甩动超过上限，保持方向等比例封顶，防止触屏采样间隔极短导致飞出异常。
     */
    fun computeVelocity(
        upTimeMs: Long,
        density: Float,
        maxSpeedDpPerSec: Float = PetFlingEngine.MAX_LAUNCH_SPEED_DP_PER_SEC
    ): PetVelocity {
        if (samples.size < 2) return PetVelocity(0f, 0f)
        if (upTimeMs - lastMeaningfulMoveTimeMs > PAUSE_CANCEL_MS) {
            return PetVelocity(0f, 0f)
        }
        val windowSamples = samples.filter { upTimeMs - it.timeMs <= WINDOW_MS }
        if (windowSamples.size < 2) return PetVelocity(0f, 0f)

        val first = windowSamples.first()
        val last = windowSamples.last()
        val dtMs = last.timeMs - first.timeMs
        if (dtMs < MIN_DT_MS) return PetVelocity(0f, 0f)

        val dtSeconds = dtMs / 1000f
        var vx = (last.x - first.x) / dtSeconds
        var vy = (last.y - first.y) / dtSeconds
        val safeDensity = if (density.isFinite() && density > 0f) density else 1f
        val maxSpeedPx = maxSpeedDpPerSec * safeDensity
        val speed = hypot(vx.toDouble(), vy.toDouble()).toFloat()
        if (!speed.isFinite()) return PetVelocity(0f, 0f)
        if (speed > maxSpeedPx && speed > 0f) {
            val scale = maxSpeedPx / speed
            vx *= scale
            vy *= scale
        }
        return PetVelocity(vx = vx, vy = vy)
    }

    private companion object {
        const val WINDOW_MS = 90L
        const val PAUSE_CANCEL_MS = 70L
        const val MIN_DT_MS = 4L
        const val MAX_SAMPLES = 24
        const val MEANINGFUL_MOVE_PX = 0.5f
    }
}

/**
 * 悬浮桌宠惯性滑行与屏幕四壁弹性碰撞引擎（纯数学积分，独立于 Android 窗口帧循环）：
 * 抬手速度超过起步阈值时进入滑行，按指数空气阻尼顺滑减速；撞到左/右/上/下屏幕边界时
 * 按恢复系数镜像反弹并保留切向滑移分量，降到停稳阈值后自动结束。
 */
internal class PetFlingEngine {
    var x: Float = 0f
        private set
    var bottom: Float = 0f
        private set
    var vx: Float = 0f
        private set
    var vy: Float = 0f
        private set
    var isRunning: Boolean = false
        private set

    /**
     * 尝试启动一次惯性滑行：低于 [MIN_LAUNCH_SPEED_DP_PER_SEC] 的轻放不启动并立刻停止；
     * 启动时将坐标先夹紧到当前边界内，超过上限的初始速度按方向等比限幅。
     */
    fun start(
        startX: Float,
        startBottom: Float,
        initialVx: Float,
        initialVy: Float,
        density: Float,
        bounds: PetMotionBounds
    ): Boolean {
        val safeDensity = sanitizeDensity(density)
        x = clampToAxis(startX, bounds.minX, bounds.maxX)
        bottom = clampToAxis(startBottom, bounds.minBottom, bounds.maxBottom)

        val speed = hypot(initialVx.toDouble(), initialVy.toDouble()).toFloat()
        if (!initialVx.isFinite() || !initialVy.isFinite() || !speed.isFinite()) {
            stop()
            return false
        }
        if (speed < MIN_LAUNCH_SPEED_DP_PER_SEC * safeDensity) {
            stop()
            return false
        }

        val maxSpeed = MAX_LAUNCH_SPEED_DP_PER_SEC * safeDensity
        if (speed > maxSpeed) {
            val scale = maxSpeed / speed
            vx = initialVx * scale
            vy = initialVy * scale
        } else {
            vx = initialVx
            vy = initialVy
        }
        isRunning = true
        return true
    }

    /** 立即清零速度并结束滑行，用于手指再次接住桌宠或窗口隐藏/销毁时省电停帧。 */
    fun stop() {
        vx = 0f
        vy = 0f
        isRunning = false
    }

    /**
     * 按单帧时间步长推进滑行状态：位移 -> 边界镜像弹性反射 + 切向轻微擦壁衰减 -> 指数空气阻尼；
     * 若合速度降至停稳阈值以下则返回 false 并自动停机。
     */
    fun step(
        dtSeconds: Float,
        density: Float,
        bounds: PetMotionBounds
    ): Boolean {
        if (!isRunning) return false
        val safeDensity = sanitizeDensity(density)
        val dt = if (dtSeconds.isFinite()) dtSeconds.coerceIn(0f, MAX_STEP_SECONDS) else 0f
        if (dt <= 0f) return true

        val xStep = resolveAxisBounds(x + vx * dt, vx, bounds.minX, bounds.maxX)
        val yStep = resolveAxisBounds(bottom + vy * dt, vy, bounds.minBottom, bounds.maxBottom)
        x = xStep.position
        bottom = yStep.position

        var nextVx = xStep.velocity
        var nextVy = yStep.velocity
        if (yStep.bounced) nextVx *= WALL_TANGENTIAL_RETENTION
        if (xStep.bounced) nextVy *= WALL_TANGENTIAL_RETENTION

        val decay = exp((-DAMPING_PER_SECOND * dt).toDouble()).toFloat()
        vx = nextVx * decay
        vy = nextVy * decay

        val remainingSpeed = hypot(vx.toDouble(), vy.toDouble()).toFloat()
        if (!remainingSpeed.isFinite() || remainingSpeed < STOP_SPEED_DP_PER_SEC * safeDensity) {
            stop()
            return false
        }
        return true
    }

    private data class AxisStepResult(
        val position: Float,
        val velocity: Float,
        val bounced: Boolean
    )

    private fun resolveAxisBounds(
        rawPos: Float,
        velocity: Float,
        minBound: Float,
        maxBound: Float
    ): AxisStepResult {
        if (minBound >= maxBound) {
            return AxisStepResult(position = minBound, velocity = 0f, bounced = false)
        }
        var p = rawPos
        var v = velocity
        var bounced = false
        var bounces = 0
        while ((p < minBound || p > maxBound) && bounces < MAX_BOUNCES_PER_STEP) {
            if (p < minBound) {
                p = 2f * minBound - p
                v = abs(v) * RESTITUTION
                bounced = true
            } else if (p > maxBound) {
                p = 2f * maxBound - p
                v = -abs(v) * RESTITUTION
                bounced = true
            }
            bounces++
        }
        // 高速或极窄区间在单帧内连撞多次时强行钳位，防止坐标越出可见区域。
        p = p.coerceIn(minBound, maxBound)
        return AxisStepResult(position = p, velocity = v, bounced = bounced)
    }

    private fun sanitizeDensity(density: Float): Float =
        if (density.isFinite() && density > 0f) density else 1f

    private fun clampToAxis(value: Float, minBound: Float, maxBound: Float): Float =
        if (!value.isFinite() || minBound >= maxBound) minBound else value.coerceIn(minBound, maxBound)

    companion object {
        const val MIN_LAUNCH_SPEED_DP_PER_SEC = 140f
        const val MAX_LAUNCH_SPEED_DP_PER_SEC = 4200f
        const val DAMPING_PER_SECOND = 2.0f
        const val RESTITUTION = 0.78f
        const val WALL_TANGENTIAL_RETENTION = 0.96f
        const val STOP_SPEED_DP_PER_SEC = 18f
        const val MAX_STEP_SECONDS = 0.032f
        const val MAX_BOUNCES_PER_STEP = 4
    }
}
