package org.maiwithu.maidroid.remote

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.serialization.Serializable
import org.maiwithu.maidroid.pet.PetOverlayService
import org.maiwithu.maidroid.runtime.ProcessCpuTicks
import org.maiwithu.maidroid.runtime.computeTreeCpuPercent
import org.maiwithu.maidroid.runtime.readOwnTreeCpuTicks
import org.maiwithu.maidroid.settings.AppSettings
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 随在线心跳上报的匿名耗电与温控指标快照：全部为数值、布尔或枚举，不含任何个人或敏感信息。
 */
@Serializable
internal data class PowerTelemetryPayload(
    val batteryPct: Int? = null,
    val batteryTempC: Double? = null,
    val batteryVoltageMv: Int? = null,
    val batteryCharging: Boolean? = null,
    val chargePlug: String? = null,
    val batteryCurrentMa: Int? = null,
    val dischargeRatePctPerHr: Double? = null,
    val chargeRatePctPerHr: Double? = null,
    val chargingDrain: Boolean? = null,
    val thermalStatus: Int? = null,
    val appCpuPct: Double? = null,
    val screenOn: Boolean? = null,
    val screenOnPct: Int? = null,
    val keepScreenOn: Boolean? = null,
    val foregroundActive: Boolean? = null,
    val cpuAffinityPinned: Boolean? = null,
    val desktopPetRunning: Boolean? = null
)

/** 充放电速率滑动窗口评估结果。 */
internal data class PowerRateSnapshot(
    val dischargeRatePctPerHr: Double? = null,
    val chargeRatePctPerHr: Double? = null,
    val chargingDrain: Boolean = false
)

private data class BatterySample(
    val elapsedMs: Long,
    val pct: Int
)

private data class BatterySnapshot(
    val pct: Int?,
    val tempC: Double?,
    val voltageMv: Int?,
    val plugged: Boolean?,
    val plugType: String?
)

/**
 * 基于同一充放电状态（插电/拔电）下 10~60 分钟滑动样本窗口估算耗电与充电净速率，
 * 并在持续插电期间识别“边充边掉电”。插拔电切换立即清空样本，避免充放电混算。
 */
internal class PowerRateWindow {
    companion object {
        const val MIN_RATE_WINDOW_MS = 10 * 60_000L
        const val MAX_RATE_WINDOW_MS = 60 * 60_000L
    }

    private var currentPlugged: Boolean? = null
    private val samples = ArrayDeque<BatterySample>()

    fun record(elapsedMs: Long, pct: Int, plugged: Boolean) {
        if (pct !in 0..100) return
        if (currentPlugged != plugged) {
            samples.clear()
            currentPlugged = plugged
        }
        // 只保留最近 60 分钟窗口内的样本；时间未推进且百分比未变化时不重复追加冗余点。
        while (samples.size > 1 && elapsedMs - samples.first().elapsedMs > MAX_RATE_WINDOW_MS) {
            samples.removeFirst()
        }
        val tail = samples.lastOrNull()
        if (tail != null) {
            if (elapsedMs < tail.elapsedMs) return
            if (elapsedMs == tail.elapsedMs && pct == tail.pct) return
        }
        samples.addLast(BatterySample(elapsedMs, pct))
    }

    fun evaluate(plugged: Boolean?): PowerRateSnapshot {
        if (plugged == null || currentPlugged != plugged || samples.size < 2) {
            return PowerRateSnapshot()
        }
        val first = samples.first()
        val last = samples.last()
        val spanMs = last.elapsedMs - first.elapsedMs
        if (spanMs < MIN_RATE_WINDOW_MS) {
            return PowerRateSnapshot()
        }

        val deltaPct = last.pct - first.pct
        val ratePctPerHr = roundOneDecimal(deltaPct.toDouble() * 3_600_000.0 / spanMs.toDouble())
        return if (plugged) {
            // 满电 100% 稳态涓流不上报净充速率，但 100% 跌到 <=98% 仍会算作边充边掉电。
            val isTrickleFull = first.pct == 100 && last.pct == 100
            val drain = deltaPct <= -2 || (deltaPct <= -1 && last.pct < 98)
            PowerRateSnapshot(
                dischargeRatePctPerHr = null,
                chargeRatePctPerHr = if (isTrickleFull) null else ratePctPerHr,
                chargingDrain = drain
            )
        } else {
            val dischargeRate = if (deltaPct <= 0) (-ratePctPerHr).coerceAtLeast(0.0) else null
            PowerRateSnapshot(
                dischargeRatePctPerHr = dischargeRate,
                chargeRatePctPerHr = null,
                chargingDrain = false
            )
        }
    }
}

/**
 * 统计单个采样窗口内的亮屏时长占比（0..100%），在屏幕开关切换与周期上报时结算。
 */
internal class ScreenOnAccumulator(
    initiallyScreenOn: Boolean,
    nowMs: Long
) {
    private var currentScreenOn = initiallyScreenOn
    private var windowStartMs = nowMs
    private var lastChangedMs = nowMs
    private var screenOnMs = 0L

    fun onInteractiveChanged(screenOn: Boolean, nowMs: Long) {
        advanceTo(nowMs)
        currentScreenOn = screenOn
    }

    fun sampleAndReset(nowMs: Long): Pair<Boolean, Int?> {
        advanceTo(nowMs)
        val totalMs = nowMs - windowStartMs
        val pct = if (totalMs > 0L) {
            ((screenOnMs.toDouble() * 100.0) / totalMs.toDouble()).roundToInt().coerceIn(0, 100)
        } else {
            if (currentScreenOn) 100 else 0
        }
        windowStartMs = nowMs
        lastChangedMs = nowMs
        screenOnMs = 0L
        return currentScreenOn to pct
    }

    private fun advanceTo(nowMs: Long) {
        if (nowMs <= lastChangedMs) return
        if (currentScreenOn) {
            screenOnMs += nowMs - lastChangedMs
        }
        lastChangedMs = nowMs
    }
}

/**
 * 将 OEM 差异巨大的 BATTERY_PROPERTY_CURRENT_NOW 换算为毫安，并统一符号约定（正=净充入，负=净放电）。
 * 在拔电状态读到非零值时自动学习该机型的电流正负号定义。
 */
internal fun inferOemDischargePositive(rawCurrent: Int, plugged: Boolean, previousSign: Boolean?): Boolean? {
    if (plugged) return previousSign
    val ma = rawCurrentToMilliAmperes(rawCurrent) ?: return previousSign
    if (abs(ma) < 20) return previousSign
    return ma > 0
}

internal fun normalizeBatteryCurrentMa(
    rawCurrent: Int,
    plugged: Boolean,
    oemDischargePositive: Boolean?
): Int? {
    val ma = rawCurrentToMilliAmperes(rawCurrent) ?: return null
    val normalized = when {
        oemDischargePositive == true -> -ma
        !plugged && oemDischargePositive == null && ma > 0 -> -ma
        else -> ma
    }
    return normalized.takeIf { it in -15_000..15_000 }
}

private fun rawCurrentToMilliAmperes(rawCurrent: Int): Int? {
    if (rawCurrent == Int.MIN_VALUE || rawCurrent == Int.MAX_VALUE) return null
    // 大多数现代 Android 机型返回微安（µA），少量老驱动直接返回毫安（mA）。
    val ma = if (abs(rawCurrent) > 20_000) rawCurrent / 1000 else rawCurrent
    return ma.takeIf { abs(it) <= 20_000 }
}

/**
 * 与 ProotCommand.excludeTopCpuClusterPrelude() 使用完全一致的探测条件：
 * 从 /sys 读取各核心最大频率，只有读得到且存在 2 个及以上不同频段时，启动脚本的绑核才真正排除最高频簇。
 */
internal fun detectTopCpuClusterPinned(cpuSysDir: File = File("/sys/devices/system/cpu")): Boolean? =
    runCatching {
        val entries = cpuSysDir.listFiles() ?: return@runCatching false
        val freqs = HashSet<Long>()
        var readableCount = 0
        for (dir in entries) {
            if (!dir.isDirectory) continue
            val name = dir.name
            if (!name.startsWith("cpu")) continue
            val suffix = name.substring(3)
            if (suffix.isEmpty() || !suffix.all(Char::isDigit)) continue
            val freqFile = File(dir, "cpufreq/cpuinfo_max_freq")
            if (!freqFile.isFile || !freqFile.canRead()) continue
            val freq = freqFile.readText().trim().toLongOrNull() ?: continue
            if (freq > 0L) {
                readableCount++
                freqs.add(freq)
            }
        }
        readableCount >= 2 && freqs.size >= 2
    }.getOrNull()

internal fun chargePlugName(pluggedBits: Int): String = when {
    pluggedBits == 0 -> "none"
    (pluggedBits and BatteryManager.BATTERY_PLUGGED_AC) != 0 -> "ac"
    (pluggedBits and BatteryManager.BATTERY_PLUGGED_USB) != 0 -> "usb"
    (pluggedBits and BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0 -> "wireless"
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        (pluggedBits and BatteryManager.BATTERY_PLUGGED_DOCK) != 0 -> "dock"
    else -> "other"
}

private fun roundOneDecimal(value: Double): Double = (value * 10.0).roundToInt() / 10.0

/**
 * 进程级电池、温度、整树 CPU 占用与屏幕活跃状态的轻量采样器：
 * 只消费系统电量/屏幕广播及同 UID `/proc/<pid>/stat`，不额外启动任何子进程或常驻唤醒。
 */
internal class PowerTelemetryTracker(
    context: Context,
    private val settings: AppSettings,
    private val elapsedMsProvider: () -> Long = SystemClock::elapsedRealtime
) {
    private val appContext = context.applicationContext
    private val batteryManager = appContext.getSystemService(BatteryManager::class.java)
    private val powerManager = appContext.getSystemService(PowerManager::class.java)
    private val lock = Any()

    private val rateWindow = PowerRateWindow()
    private val screenAccumulator = ScreenOnAccumulator(
        initiallyScreenOn = runCatching { powerManager?.isInteractive ?: true }.getOrDefault(true),
        nowMs = elapsedMsProvider()
    )

    private var lastBattery: BatterySnapshot? = null
    private var oemDischargePositive: Boolean? = null
    private var cachedCpuPinned: Boolean? = null
    private var cpuPinChecked = false
    private var foregroundActive = false
    private var previousCpuTicks: Map<Int, ProcessCpuTicks> = emptyMap()
    private var previousCpuElapsedMs = 0L

    fun onBatteryChanged(intent: Intent) {
        val nowMs = elapsedMsProvider()
        val parsed = parseBatteryIntent(intent)
        val rawCurrent = readRawBatteryCurrent()
        synchronized(lock) {
            lastBattery = parsed
            if (rawCurrent != null && parsed.plugged != null) {
                oemDischargePositive = inferOemDischargePositive(rawCurrent, parsed.plugged, oemDischargePositive)
            }
            if (parsed.pct != null && parsed.plugged != null) {
                rateWindow.record(nowMs, parsed.pct, parsed.plugged)
            }
        }
    }

    fun onScreenInteractiveChanged(interactive: Boolean) {
        val nowMs = elapsedMsProvider()
        synchronized(lock) {
            screenAccumulator.onInteractiveChanged(interactive, nowMs)
        }
    }

    fun onUiForegroundChanged(foreground: Boolean) {
        synchronized(lock) {
            foregroundActive = foreground
        }
    }

    /** 采集一份匿名电源/温度/CPU/屏幕快照；请在 IO 线程调用（需扫本应用 /proc）。 */
    fun captureSnapshot(): PowerTelemetryPayload {
        // 尚无电量广播缓存时（例如主界面冷启先于后台服务触发上报），主动读一次系统粘性广播。
        val existingBattery = synchronized(lock) { lastBattery }
        if (existingBattery == null) {
            runCatching {
                appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            }.getOrNull()?.let(::onBatteryChanged)
        }

        val nowMs = elapsedMsProvider()
        val currentTicks = runCatching { readOwnTreeCpuTicks() }.getOrDefault(emptyMap())
        val rawCurrent = readRawBatteryCurrent()
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { powerManager?.currentThermalStatus }.getOrNull()
        } else {
            null
        }
        val cpuPinned = synchronized(lock) { cpuPinChecked to cachedCpuPinned }.let { (checked, cached) ->
            if (checked) cached else detectTopCpuClusterPinned()
        }

        return synchronized(lock) {
            if (!cpuPinChecked) {
                cachedCpuPinned = cpuPinned
                cpuPinChecked = true
            }
            val bat = lastBattery
            if (rawCurrent != null && bat?.plugged != null) {
                oemDischargePositive = inferOemDischargePositive(rawCurrent, bat.plugged, oemDischargePositive)
            }
            val currentMa = if (rawCurrent != null && bat?.plugged != null) {
                normalizeBatteryCurrentMa(rawCurrent, bat.plugged, oemDischargePositive)
            } else {
                null
            }
            val rates = rateWindow.evaluate(bat?.plugged)
            val appCpuPct = if (previousCpuElapsedMs > 0L && previousCpuTicks.isNotEmpty()) {
                computeTreeCpuPercent(
                    previous = previousCpuTicks,
                    current = currentTicks,
                    elapsedMs = nowMs - previousCpuElapsedMs
                )
            } else {
                null
            }
            if (currentTicks.isNotEmpty()) {
                previousCpuTicks = currentTicks
                previousCpuElapsedMs = nowMs
            }

            val (screenOnNow, screenOnPct) = screenAccumulator.sampleAndReset(nowMs)
            PowerTelemetryPayload(
                batteryPct = bat?.pct,
                batteryTempC = bat?.tempC,
                batteryVoltageMv = bat?.voltageMv,
                batteryCharging = bat?.plugged,
                chargePlug = bat?.plugType,
                batteryCurrentMa = currentMa,
                dischargeRatePctPerHr = rates.dischargeRatePctPerHr,
                chargeRatePctPerHr = rates.chargeRatePctPerHr,
                chargingDrain = if (bat?.plugged == true) rates.chargingDrain else false,
                thermalStatus = thermal,
                appCpuPct = appCpuPct,
                screenOn = screenOnNow,
                screenOnPct = screenOnPct,
                keepScreenOn = settings.keepScreenOn,
                foregroundActive = foregroundActive,
                cpuAffinityPinned = cachedCpuPinned,
                desktopPetRunning = PetOverlayService.isRunning
            )
        }
    }

    /** 供手动导出的诊断报告 report.txt 附带供电、温度与绑核摘要（不重置遥测采样窗口）。 */
    fun formatDiagnosticsSummary(): List<Pair<String, String>> {
        val existingBattery = synchronized(lock) { lastBattery }
        if (existingBattery == null) {
            runCatching {
                appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            }.getOrNull()?.let(::onBatteryChanged)
        }
        val cpuPinned = synchronized(lock) { cpuPinChecked to cachedCpuPinned }.let { (checked, cached) ->
            if (checked) cached else detectTopCpuClusterPinned()
        }
        val rawCurrent = readRawBatteryCurrent()
        val (bat, rates, currentMa) = synchronized(lock) {
            if (!cpuPinChecked) {
                cachedCpuPinned = cpuPinned
                cpuPinChecked = true
            }
            val currentBat = lastBattery
            val ma = if (rawCurrent != null && currentBat?.plugged != null) {
                normalizeBatteryCurrentMa(rawCurrent, currentBat.plugged, oemDischargePositive)
            } else {
                null
            }
            Triple(currentBat, rateWindow.evaluate(currentBat?.plugged), ma)
        }
        val batteryText = buildList {
            bat?.pct?.let { add("${it}%") }
            bat?.tempC?.let { add("${it}°C") }
            val chargeText = when (bat?.plugged) {
                true -> "充电中(${bat.plugType ?: "unknown"})"
                false -> "未插电"
                null -> null
            }
            if (chargeText != null) add(chargeText)
            currentMa?.let { add("${if (it > 0) "+" else ""}${it}mA") }
            if (bat?.plugged == true && rates.chargingDrain) add("检测到边充边掉电")
        }.joinToString(" · ").ifEmpty { "读不到" }
        val pinnedText = when (cpuPinned) {
            true -> "已启用（已排除最高频簇）"
            false -> "未生效（系统禁止读取频点或单簇 CPU）"
            null -> "未知"
        }
        return listOf(
            "电池状态" to batteryText,
            "屏幕常亮" to if (settings.keepScreenOn) "已开启" else "未开启（默认）",
            "容器 CPU 绑核" to pinnedText
        )
    }

    private fun parseBatteryIntent(intent: Intent): BatterySnapshot {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else null
        val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val tempC = if (tempTenths != Int.MIN_VALUE && tempTenths in -200..1200) {
            roundOneDecimal(tempTenths / 10.0)
        } else {
            null
        }
        val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1).takeIf { it in 1000..20000 }
        val pluggedBits = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        val plugged = if (pluggedBits >= 0) pluggedBits != 0 else null
        val plugType = if (pluggedBits >= 0) chargePlugName(pluggedBits) else null
        return BatterySnapshot(pct = pct, tempC = tempC, voltageMv = voltage, plugged = plugged, plugType = plugType)
    }

    private fun readRawBatteryCurrent(): Int? = runCatching {
        val value = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            ?: return@runCatching null
        value.takeUnless { it == Int.MIN_VALUE || it == Int.MAX_VALUE }
    }.getOrNull()
}
