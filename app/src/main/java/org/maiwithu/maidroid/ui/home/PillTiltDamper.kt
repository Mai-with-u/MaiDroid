package org.maiwithu.maidroid.ui.home

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sqrt

/** 竖屏 WebUI 侧边胶囊在屏幕左右缘的停靠边枚举。 */
internal enum class PillDockSide {
    Left,
    Right
}

/** 竖屏初次进入 WebUI 时悬浮胶囊默认停靠在左侧，符合右手滑屏时左手拇指与视觉习惯。 */
internal val DefaultPillDockSide = PillDockSide.Left

// 有效重力样本总模长下限与上限（m/s^2），用于过滤剧烈甩动导致加速度失真的采样帧
private const val MinValidGravity = 5.0f
private const val MaxValidGravity = 15.0f

// 屏幕平面（X/Y 轴）重力合量低于此值时判定为平放（约 <20 度倾角，覆盖桌面摄像头凸起带来的微小斜度）
private const val FlatPlaneGravityLimit = 3.4f

// 左右倾斜触发阈值（m/s^2，约 12.6 度+）：+X 指向屏幕右缘，左倾时重力在 +X 投影为正值，右倾为负值
private const val TiltTriggerThreshold = 2.15f

// 一阶低通滤波时间常数（秒）：滤除步行颠簸和点击屏幕瞬间的微震动
private const val LowPassTauSeconds = 0.28f

// 反向倾斜持续驻留阻尼时长（秒）：必须连续朝对侧倾斜稳定达标后才换边，杜绝轻微晃动秒切
private const val DwellThresholdSeconds = 0.65f

// 切换完成或手动拖拽后的冷却时长（秒），防止在临界倾角附近来回反复横跳
private const val SwitchCooldownSeconds = 1.2f

/**
 * 将传感器坐标系下的重力向量 (rawX, rawY) 旋正到当前窗口旋转方向对应的屏幕坐标系 (screenGx, screenGy)。
 * 约定屏幕坐标中 +X 指向屏幕右缘，+Y 指向屏幕上缘；Android 重力传感器回报支撑反力方向，
 * 故竖屏朝向用户时，手机向左倾斜（左边缘下沉）会测得 screenGx > 0，向右倾斜则 screenGx < 0。
 */
internal fun projectScreenGravity(rawX: Float, rawY: Float, surfaceRotation: Int): Pair<Float, Float> =
    when (surfaceRotation) {
        Surface.ROTATION_90 -> -rawY to rawX
        Surface.ROTATION_180 -> -rawX to -rawY
        Surface.ROTATION_270 -> rawY to -rawX
        else -> rawX to rawY
    }

/**
 * 悬浮胶囊重力倾斜阻尼状态机：
 * 1. 先做模长检验与一阶 EMA 低通滤波，消除握持微抖与点按震动；
 * 2. 平放时直接衰减对侧计数并保持原有停靠侧不变；
 * 3. 非平放且达到对侧倾角阈值时，累计驻留时间达标才返回新停靠侧，并进入冷却锁；
 * 4. 用户手动拖拽停靠后若姿态仍指回对侧，会锁定等待姿态先回正或平放后再恢复自动切换。
 */
internal class PillTiltDamper {
    private var hasFilterState = false
    private var filteredGx = 0f
    private var filteredGy = 0f
    private var filteredGz = 0f
    private var lastTimestampNanos = 0L
    private var oppositeHoldSeconds = 0f
    private var cooldownRemaining = 0f
    private var awaitNeutralAfterManualDock = false

    /**
     * 输入一帧重力（或加速度兜底）传感器样本，返回经过三级阻尼决策后的目标停靠侧（Left/Right）。
     */
    fun onSensorSample(
        rawX: Float,
        rawY: Float,
        rawZ: Float,
        timestampNanos: Long,
        surfaceRotation: Int = Surface.ROTATION_0,
        currentSide: PillDockSide,
        interactionPaused: Boolean = false
    ): PillDockSide {
        val (screenGx, screenGy) = projectScreenGravity(rawX, rawY, surfaceRotation)
        val magnitude = sqrt(screenGx * screenGx + screenGy * screenGy + rawZ * rawZ)

        // 总模长偏离正常重力区间过大时说明正在猛烈摇晃，跳过该帧避免误判
        if (magnitude !in MinValidGravity..MaxValidGravity) {
            lastTimestampNanos = timestampNanos
            return currentSide
        }

        if (!hasFilterState) {
            filteredGx = screenGx
            filteredGy = screenGy
            filteredGz = rawZ
            lastTimestampNanos = timestampNanos
            hasFilterState = true
            return currentSide
        }

        val dt = ((timestampNanos - lastTimestampNanos) / 1_000_000_000f).coerceIn(0.005f, 0.25f)
        lastTimestampNanos = timestampNanos

        val alpha = 1f - exp(-dt / LowPassTauSeconds)
        filteredGx += (screenGx - filteredGx) * alpha
        filteredGy += (screenGy - filteredGy) * alpha
        filteredGz += (rawZ - filteredGz) * alpha

        if (cooldownRemaining > 0f) {
            cooldownRemaining = (cooldownRemaining - dt).coerceAtLeast(0f)
        }

        // 正在拖拽或菜单展开期间冻结自动换边，确保交互过程胶囊绝对稳固
        if (interactionPaused) {
            oppositeHoldSeconds = 0f
            return currentSide
        }

        // 平放状态（正放桌面或反扣）优先保持上一次停靠位置，同时快速衰减残留的倾斜计时
        val planeMagnitude = hypot(filteredGx, filteredGy)
        if (planeMagnitude < FlatPlaneGravityLimit) {
            oppositeHoldSeconds = (oppositeHoldSeconds - dt * 2.5f).coerceAtLeast(0f)
            if (awaitNeutralAfterManualDock) {
                awaitNeutralAfterManualDock = false
            }
            return currentSide
        }

        // 左倾为正值目标停左，右倾为负值目标停右，死区内维持现状
        val desiredSide = when {
            filteredGx >= TiltTriggerThreshold -> PillDockSide.Left
            filteredGx <= -TiltTriggerThreshold -> PillDockSide.Right
            else -> null
        }

        // 手动拖拽刚把胶囊放到用户指定的边：必须等手机先回正/平放/同向倾斜一次再重新武装自动切换
        if (awaitNeutralAfterManualDock) {
            if (desiredSide == null || desiredSide == currentSide) {
                awaitNeutralAfterManualDock = false
            } else {
                oppositeHoldSeconds = 0f
                return currentSide
            }
        }

        if (desiredSide == null || desiredSide == currentSide) {
            oppositeHoldSeconds = (oppositeHoldSeconds - dt * 2.0f).coerceAtLeast(0f)
            return currentSide
        }

        if (cooldownRemaining > 0f) {
            return currentSide
        }

        // 倾角越明显驻留积攒略快但仍有上限，既保留阻尼感又兼顾主动甩手切换时的跟手度
        val speedFactor = (1f + (abs(filteredGx) - TiltTriggerThreshold) * 0.22f).coerceIn(1f, 1.75f)
        oppositeHoldSeconds += dt * speedFactor

        if (oppositeHoldSeconds >= DwellThresholdSeconds) {
            oppositeHoldSeconds = 0f
            cooldownRemaining = SwitchCooldownSeconds
            return desiredSide
        }

        return currentSide
    }

    /** 用户手势拖拽停靠后调用，锁定当前反倾角并开启冷却期，绝不立刻自动弹回。 */
    fun onManualDock() {
        oppositeHoldSeconds = 0f
        cooldownRemaining = SwitchCooldownSeconds
        awaitNeutralAfterManualDock = true
    }
}

/**
 * 绑定重力传感器（缺失时回退加速度传感器）并在生命周期可见期间驱动胶囊左右阻尼切换。
 * 随生命周期 ON_RESUME 注册、ON_PAUSE 与退出组合立即注销，避免后台耗电与泄露。
 */
@Composable
internal fun bindPillTiltSensor(
    currentSide: PillDockSide,
    interactionPaused: Boolean,
    damper: PillTiltDamper,
    onDockSideChanged: (PillDockSide) -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val sensorManager = remember(context) {
        context.getSystemService(SensorManager::class.java)
    }

    val latestDockSide by rememberUpdatedState(currentSide)
    val latestPaused by rememberUpdatedState(interactionPaused)
    val latestOnDockChanged by rememberUpdatedState(onDockSideChanged)

    DisposableEffect(lifecycleOwner, sensorManager, damper) {
        val manager = sensorManager
        val tiltSensor = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        if (manager == null || tiltSensor == null) {
            return@DisposableEffect onDispose {}
        }

        var isRegistered = false
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val values = event.values
                if (values.size < 3) return
                val rotation = view.display?.rotation ?: Surface.ROTATION_0
                val current = latestDockSide
                val next = damper.onSensorSample(
                    rawX = values[0],
                    rawY = values[1],
                    rawZ = values[2],
                    timestampNanos = event.timestamp,
                    surfaceRotation = rotation,
                    currentSide = current,
                    interactionPaused = latestPaused
                )
                if (next != current) {
                    latestOnDockChanged(next)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        val updateRegistration = { shouldRegister: Boolean ->
            if (shouldRegister && !isRegistered) {
                manager.registerListener(listener, tiltSensor, SensorManager.SENSOR_DELAY_UI)
                isRegistered = true
            } else if (!shouldRegister && isRegistered) {
                manager.unregisterListener(listener)
                isRegistered = false
            }
        }

        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            updateRegistration(true)
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> updateRegistration(true)
                Lifecycle.Event.ON_PAUSE -> updateRegistration(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            updateRegistration(false)
        }
    }
}
