package org.maiwithu.maidroid.pet

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max

/** 悬浮桌宠在屏幕坐标系下的逻辑位置点（左上角 `x` 与底边 `bottom`，单位 px）。 */
internal data class PetPoint(
    val x: Float,
    val bottom: Float
)

/** 单帧碰撞屏幕边界的方向与法向撞击速度（px/s）：`-1` 表示左/上墙，`+1` 表示右/下墙，`0` 为未碰撞。 */
internal data class PetWallImpact(
    val horizontalEdge: Int,
    val verticalEdge: Int,
    val impactSpeedPxPerSec: Float
)

/** 单帧物理推进结果：是否仍在滑行，以及本帧若发生碰壁则携带碰撞方向与速度详情。 */
internal data class PetFlingStepResult(
    val running: Boolean,
    val impact: PetWallImpact?
)

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
     * 按单帧时间步长推进滑行状态（兼容便捷重载）：返回 true 表示仍需继续下一帧。
     */
    fun step(
        dtSeconds: Float,
        density: Float,
        bounds: PetMotionBounds
    ): Boolean = stepWithImpact(dtSeconds, density, bounds).running

    /**
     * 按单帧时间步长推进滑行并返回碰撞事件：位移 -> 边界镜像弹性反射 + 切向擦壁衰减 -> 指数空气阻尼；
     * 若本帧撞墙则附带墙面方向与碰撞速度，供外部驱动果冻回弹形变。
     */
    fun stepWithImpact(
        dtSeconds: Float,
        density: Float,
        bounds: PetMotionBounds
    ): PetFlingStepResult {
        if (!isRunning) return PetFlingStepResult(running = false, impact = null)
        val safeDensity = sanitizeDensity(density)
        val dt = if (dtSeconds.isFinite()) dtSeconds.coerceIn(0f, MAX_STEP_SECONDS) else 0f
        if (dt <= 0f) return PetFlingStepResult(running = true, impact = null)

        val preVx = vx
        val preVy = vy
        val xStep = resolveAxisBounds(x + vx * dt, vx, bounds.minX, bounds.maxX)
        val yStep = resolveAxisBounds(bottom + vy * dt, vy, bounds.minBottom, bounds.maxBottom)
        x = xStep.position
        bottom = yStep.position

        val impact = if (xStep.bouncedEdge != 0 || yStep.bouncedEdge != 0) {
            val impactX = if (xStep.bouncedEdge != 0) abs(preVx) else 0f
            val impactY = if (yStep.bouncedEdge != 0) abs(preVy) else 0f
            PetWallImpact(
                horizontalEdge = xStep.bouncedEdge,
                verticalEdge = yStep.bouncedEdge,
                impactSpeedPxPerSec = max(impactX, impactY)
            )
        } else {
            null
        }

        var nextVx = xStep.velocity
        var nextVy = yStep.velocity
        if (yStep.bouncedEdge != 0) nextVx *= WALL_TANGENTIAL_RETENTION
        if (xStep.bouncedEdge != 0) nextVy *= WALL_TANGENTIAL_RETENTION

        val decay = exp((-DAMPING_PER_SECOND * dt).toDouble()).toFloat()
        vx = nextVx * decay
        vy = nextVy * decay

        val remainingSpeed = hypot(vx.toDouble(), vy.toDouble()).toFloat()
        if (!remainingSpeed.isFinite() || remainingSpeed < STOP_SPEED_DP_PER_SEC * safeDensity) {
            stop()
            return PetFlingStepResult(running = false, impact = impact)
        }
        return PetFlingStepResult(running = true, impact = impact)
    }

    /**
     * 基于当前确定性积分模型，从给定初值预演整段滑行与反弹直到停稳后的最终落点，
     * 供 Android 8–12 在 GPU 滑行舞台启动第 0 毫秒提前把隐藏主小窗静默落位。
     */
    fun predictRestingPosition(
        startX: Float,
        startBottom: Float,
        initialVx: Float,
        initialVy: Float,
        density: Float,
        bounds: PetMotionBounds
    ): PetPoint {
        val probe = PetFlingEngine()
        if (!probe.start(startX, startBottom, initialVx, initialVy, density, bounds)) {
            return PetPoint(x = probe.x, bottom = probe.bottom)
        }
        var steps = 0
        while (probe.stepWithImpact(PREDICT_STEP_SECONDS, density, bounds).running &&
            steps < MAX_PREDICT_STEPS
        ) {
            steps++
        }
        return PetPoint(x = probe.x, bottom = probe.bottom)
    }

    private data class AxisStepResult(
        val position: Float,
        val velocity: Float,
        val bouncedEdge: Int
    )

    private fun resolveAxisBounds(
        rawPos: Float,
        velocity: Float,
        minBound: Float,
        maxBound: Float
    ): AxisStepResult {
        if (minBound >= maxBound) {
            return AxisStepResult(position = minBound, velocity = 0f, bouncedEdge = 0)
        }
        var p = rawPos
        var v = velocity
        var bouncedEdge = 0
        var bounces = 0
        while ((p < minBound || p > maxBound) && bounces < MAX_BOUNCES_PER_STEP) {
            if (p < minBound) {
                p = 2f * minBound - p
                v = abs(v) * RESTITUTION
                if (bouncedEdge == 0) bouncedEdge = -1
            } else if (p > maxBound) {
                p = 2f * maxBound - p
                v = -abs(v) * RESTITUTION
                if (bouncedEdge == 0) bouncedEdge = 1
            }
            bounces++
        }
        // 高速或极窄区间在单帧内连撞多次时强行钳位，防止坐标越出可见区域。
        p = p.coerceIn(minBound, maxBound)
        return AxisStepResult(position = p, velocity = v, bouncedEdge = bouncedEdge)
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
        const val MAX_STEP_SECONDS = 0.05f
        const val MAX_BOUNCES_PER_STEP = 4
        private const val PREDICT_STEP_SECONDS = 1f / 60f
        private const val MAX_PREDICT_STEPS = 600
    }
}

/**
 * 桌宠撞墙果冻挤压回弹弹簧：
 * 正值形变代表撞击左右墙产生的水平压扁、竖直拉长；负值形变代表撞击上下墙产生的竖直压扁、水平拉长；
 * 锚点会自动贴紧被撞一侧边缘，并通过二阶欠阻尼弹簧完成 1~2 次轻柔 Q 弹回振后自然归 1.0。
 */
internal class PetBounceSquashSpring {
    var scaleX: Float = 1f
        private set
    var scaleY: Float = 1f
        private set
    var pivotXFraction: Float = 0.5f
        private set
    var pivotYFraction: Float = 0.5f
        private set
    var isActive: Boolean = false
        private set

    private var deformation = 0f
    private var deformationVelocity = 0f

    /** 碰撞墙面时按法向速度注入压缩脉冲并锁定贴墙缩放支点。 */
    fun onImpact(impact: PetWallImpact, density: Float) {
        if (impact.horizontalEdge == 0 && impact.verticalEdge == 0) return
        val safeDensity = if (density.isFinite() && density > 0f) density else 1f
        if (!impact.impactSpeedPxPerSec.isFinite() ||
            impact.impactSpeedPxPerSec < MIN_IMPACT_SPEED_DP_PER_SEC * safeDensity
        ) {
            return
        }

        val normalized = (impact.impactSpeedPxPerSec / (REFERENCE_SPEED_DP_PER_SEC * safeDensity))
            .coerceIn(0f, 1.5f)
        val strength = (normalized * MAX_DEFORMATION).coerceIn(MIN_DEFORMATION, MAX_DEFORMATION)
        val direction = if (impact.horizontalEdge != 0) 1f else -1f

        // 根据撞击墙面设定贴墙锚点，确保挤压瞬间边缘贴紧屏幕不露出缝隙。
        pivotXFraction = when {
            impact.horizontalEdge < 0 -> 0f
            impact.horizontalEdge > 0 -> 1f
            else -> 0.5f
        }
        pivotYFraction = when {
            impact.verticalEdge < 0 -> 0f
            impact.verticalEdge > 0 -> 1f
            else -> 0.5f
        }

        deformation = (deformation + direction * strength * 0.85f)
            .coerceIn(-MAX_DEFORMATION, MAX_DEFORMATION)
        deformationVelocity = direction * strength * IMPACT_VELOCITY_BOOST
        isActive = true
        applyScaleFromDeformation()
    }

    /** 按时间步长积分弹簧状态；完全归稳后清零并返回 false。 */
    fun step(dtSeconds: Float): Boolean {
        if (!isActive) return false
        val totalDt = if (dtSeconds.isFinite()) {
            dtSeconds.coerceIn(0f, PetFlingEngine.MAX_STEP_SECONDS)
        } else {
            0f
        }
        if (totalDt <= 0f) return true

        // 将单帧拆分为 <= 10ms 的半隐式欧拉子步，避免偶发长帧下高刚度弹簧震荡发散。
        var remaining = totalDt
        while (remaining > 0f) {
            val subDt = minOf(remaining, MAX_SUBSTEP_SECONDS)
            val accel = -STIFFNESS * deformation - DAMPING * deformationVelocity
            deformationVelocity += accel * subDt
            deformation += deformationVelocity * subDt
            remaining -= subDt
        }

        if (abs(deformation) < STOP_DEFORMATION_EPSILON &&
            abs(deformationVelocity) < STOP_VELOCITY_EPSILON
        ) {
            reset()
            return false
        }
        applyScaleFromDeformation()
        return true
    }

    /** 立即清零形变与速率，供手动接住或销毁时恢复标准比例。 */
    fun reset() {
        deformation = 0f
        deformationVelocity = 0f
        scaleX = 1f
        scaleY = 1f
        pivotXFraction = 0.5f
        pivotYFraction = 0.5f
        isActive = false
    }

    private fun applyScaleFromDeformation() {
        scaleX = (1f - deformation).coerceIn(MIN_SCALE, MAX_SCALE)
        scaleY = (1f + deformation * ORTHOGONAL_STRETCH_RATIO).coerceIn(MIN_SCALE, MAX_SCALE)
        if (deformation < 0f) {
            // 撞击上下墙时应改为纵向压扁（1 + deformation）、横向拉长，对齐面积互补直觉。
            scaleY = (1f + deformation).coerceIn(MIN_SCALE, MAX_SCALE)
            scaleX = (1f - deformation * ORTHOGONAL_STRETCH_RATIO).coerceIn(MIN_SCALE, MAX_SCALE)
        }
    }

    companion object {
        const val MIN_SCALE = 0.85f
        const val MAX_SCALE = 1.14f
        const val MAX_DEFORMATION = 0.14f
        private const val MIN_DEFORMATION = 0.03f
        private const val ORTHOGONAL_STRETCH_RATIO = 0.78f
        private const val MIN_IMPACT_SPEED_DP_PER_SEC = 120f
        private const val REFERENCE_SPEED_DP_PER_SEC = 2400f
        private const val IMPACT_VELOCITY_BOOST = 3.2f
        private const val STIFFNESS = 340f
        private const val DAMPING = 16f
        private const val MAX_SUBSTEP_SECONDS = 0.01f
        private const val STOP_DEFORMATION_EPSILON = 0.002f
        private const val STOP_VELOCITY_EPSILON = 0.02f
    }
}

/**
 * 计算 Android 8–12 悬浮窗防抖上裁剪位置：
 * 当气泡/菜单向上弹出越过当前 [currentClipTop] 时立即上扩防截断；
 * 当菜单关闭留出超过 [headroomPx] + [shrinkDeadbandPx] 的大块空白时再收缩，
 * 过滤皮肤呼吸动效导致的几像素往复抖动，消除静止期无意义的窗口重排与 400ms 位移动画。
 */
internal fun resolveStableClipTop(
    currentClipTop: Int,
    rawTopPx: Int,
    fullHeight: Int,
    minVisiblePx: Int,
    headroomPx: Int,
    shrinkDeadbandPx: Int
): Int {
    val maxClip = (fullHeight - minVisiblePx).coerceAtLeast(0)
    val clampedCurrent = currentClipTop.coerceIn(0, maxClip)
    val safeHeadroom = headroomPx.coerceAtLeast(0)
    val safeDeadband = shrinkDeadbandPx.coerceAtLeast(0)
    val desired = (rawTopPx - safeHeadroom).coerceIn(0, maxClip)
    return when {
        rawTopPx < clampedCurrent -> desired
        rawTopPx > clampedCurrent + safeHeadroom + safeDeadband -> desired
        else -> clampedCurrent
    }
}

/** 从 `#skin-canvas` JS 抓帧回调解析出的有效画布负载。 */
internal data class CapturedCanvasPayload(
    val canvasVisible: Boolean,
    val pngBase64: String? = null,
    val leftCss: Float = 0f,
    val topCss: Float = 0f,
    val widthCss: Float = 0f,
    val heightCss: Float = 0f,
    val dpr: Float = 1f
)

/** 从 `data:image/png;base64,...` 中提取纯 Base64 数据；格式不合法时返回 null。 */
internal fun extractPngBase64Payload(dataUrl: String?): String? {
    if (dataUrl.isNullOrBlank()) return null
    val trimmed = dataUrl.trim()
    val prefix = "data:image/png;base64,"
    if (!trimmed.startsWith(prefix, ignoreCase = true)) return null
    val payload = trimmed.substring(prefix.length).trim()
    return payload.takeIf { it.isNotEmpty() }
}

/**
 * 解析 WebView `evaluateJavascript` 返回的画布抓帧结果：
 * 兼容外层包有 JSON 字符串转义的返回形式，严格校验宽高与 PNG DataURL，
 * 防止格式异常时误当无画布而掩盖抓帧失败。
 */
internal fun parseCapturedCanvasPayload(rawEvalResult: String?): CapturedCanvasPayload? {
    val trimmed = rawEvalResult?.trim().orEmpty()
    if (trimmed.isEmpty() || trimmed == "null" || trimmed == "undefined") return null

    val jsonObject = runCatching {
        val first = Json.parseToJsonElement(trimmed)
        when {
            first is JsonObject -> first
            first is JsonPrimitive && first.isString ->
                Json.parseToJsonElement(first.content) as? JsonObject
            else -> null
        }
    }.getOrNull() ?: return null

    val canvasVisible = (jsonObject["canvasVisible"] as? JsonPrimitive)?.booleanOrNull ?: return null
    if (!canvasVisible) {
        return CapturedCanvasPayload(canvasVisible = false)
    }

    val left = (jsonObject["left"] as? JsonPrimitive)?.floatOrNull ?: return null
    val top = (jsonObject["top"] as? JsonPrimitive)?.floatOrNull ?: return null
    val width = (jsonObject["width"] as? JsonPrimitive)?.floatOrNull ?: return null
    val height = (jsonObject["height"] as? JsonPrimitive)?.floatOrNull ?: return null
    val dpr = (jsonObject["dpr"] as? JsonPrimitive)?.floatOrNull ?: return null
    if (!left.isFinite() || !top.isFinite() ||
        !width.isFinite() || !height.isFinite() || !dpr.isFinite() ||
        width <= 0f || height <= 0f || dpr <= 0f
    ) {
        return null
    }

    val rawPng = (jsonObject["png"] as? JsonPrimitive)?.contentOrNull
    val pngBase64 = extractPngBase64Payload(rawPng) ?: return null
    return CapturedCanvasPayload(
        canvasVisible = true,
        pngBase64 = pngBase64,
        leftCss = left,
        topCss = top,
        widthCss = width,
        heightCss = height,
        dpr = dpr
    )
}
