package org.maiwithu.maidroid.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maiwithu.maidroid.runtime.ProcessCpuTicks
import org.maiwithu.maidroid.runtime.computeTreeCpuPercent
import org.maiwithu.maidroid.runtime.parseProcStatCpuTicks
import java.io.File
import java.nio.file.Files

class PowerTelemetryTrackerTest {

    @Test
    fun `parseProcStatCpuTicks handles comm with spaces and nested parentheses`() {
        val line = "6679 (Worker (IO) Thread) S 787 787 0 0 -1 1077952832 10 0 2 0 1500 500 0 0 20 0 12 0 78401535 12345 67"
        val parsed = parseProcStatCpuTicks(line)
        assertEquals(ProcessCpuTicks(startTimeTicks = 78401535L, cpuTicks = 2000L), parsed)
        assertNull(parseProcStatCpuTicks("invalid stat line"))
    }

    @Test
    fun `computeTreeCpuPercent aggregates delta ticks and new children while ignoring short windows`() {
        val prev = mapOf(
            100 to ProcessCpuTicks(startTimeTicks = 1000L, cpuTicks = 500L),
            101 to ProcessCpuTicks(startTimeTicks = 1005L, cpuTicks = 1200L)
        )
        val curr = mapOf(
            100 to ProcessCpuTicks(startTimeTicks = 1000L, cpuTicks = 2000L), // +1500 ticks
            101 to ProcessCpuTicks(startTimeTicks = 2000L, cpuTicks = 600L),  // PID 复用，取新进程 600 ticks
            102 to ProcessCpuTicks(startTimeTicks = 2100L, cpuTicks = 900L)   // 新派生子进程 900 ticks
        )
        // 总增量 = 3000 ticks = 30.0 CPU 秒；经过 20 秒墙钟 = 150.0% 单核占用
        assertEquals(150.0, computeTreeCpuPercent(prev, curr, elapsedMs = 20_000L)!!, 0.01)
        assertNull(computeTreeCpuPercent(prev, curr, elapsedMs = 5_000L))
        assertNull(computeTreeCpuPercent(emptyMap(), curr, elapsedMs = 20_000L))
    }

    @Test
    fun `PowerRateWindow measures charging drain and ignores 100 percent trickle`() {
        val window = PowerRateWindow()
        // 持续插电 30 分钟掉了 3%（80% -> 77%）：充电净速率 -6.0%/h，判定为边充边掉电
        window.record(elapsedMs = 0L, pct = 80, plugged = true)
        window.record(elapsedMs = 30 * 60_000L, pct = 77, plugged = true)
        val drainSnap = window.evaluate(plugged = true)
        assertTrue(drainSnap.chargingDrain)
        assertEquals(-6.0, drainSnap.chargeRatePctPerHr!!, 0.01)
        assertNull(drainSnap.dischargeRatePctPerHr)

        // 切换到拔电状态：立即清空历史充电样本，等待满 10 分钟重新统计放电速率
        window.record(elapsedMs = 31 * 60_000L, pct = 77, plugged = false)
        assertFalse(window.evaluate(plugged = false).chargingDrain)
        assertNull(window.evaluate(plugged = false).dischargeRatePctPerHr)

        window.record(elapsedMs = 51 * 60_000L, pct = 72, plugged = false)
        val dischargeSnap = window.evaluate(plugged = false)
        assertFalse(dischargeSnap.chargingDrain)
        assertEquals(15.0, dischargeSnap.dischargeRatePctPerHr!!, 0.01)
        assertNull(dischargeSnap.chargeRatePctPerHr)

        // 满电 100% 保持 30 分钟：不计为掉电也不报无效净充电率
        val fullWindow = PowerRateWindow()
        fullWindow.record(elapsedMs = 0L, pct = 100, plugged = true)
        fullWindow.record(elapsedMs = 30 * 60_000L, pct = 100, plugged = true)
        val fullSnap = fullWindow.evaluate(plugged = true)
        assertFalse(fullSnap.chargingDrain)
        assertNull(fullSnap.chargeRatePctPerHr)
    }

    @Test
    fun `ScreenOnAccumulator computes percentage across interactive transitions`() {
        val acc = ScreenOnAccumulator(initiallyScreenOn = true, nowMs = 0L)
        acc.onInteractiveChanged(screenOn = false, nowMs = 250_000L)
        acc.onInteractiveChanged(screenOn = true, nowMs = 750_000L)
        val (screenOnNow, pct) = acc.sampleAndReset(nowMs = 1_000_000L)
        assertTrue(screenOnNow)
        // [0..250s] + [750..1000s] = 500s / 1000s = 50%
        assertEquals(50, pct)
    }

    @Test
    fun `battery current normalization learns OEM polarity and converts microamps`() {
        // 小米等 OEM 拔电时正数代表放电，自动判定 oemDischargePositive = true
        val sign = inferOemDischargePositive(rawCurrent = 450_000, plugged = false, previousSign = null)
        assertEquals(true, sign)
        // 插电读到 -320_000 µA 时翻转回 +320mA（净充入）；读到 +180_000 µA 时翻转成 -180mA（边充边耗）
        assertEquals(320, normalizeBatteryCurrentMa(rawCurrent = -320_000, plugged = true, oemDischargePositive = sign))
        assertEquals(-180, normalizeBatteryCurrentMa(rawCurrent = 180_000, plugged = true, oemDischargePositive = sign))

        // 标准设备拔电时负数代表放电，符号保持不变
        val stdSign = inferOemDischargePositive(rawCurrent = -400, plugged = false, previousSign = null)
        assertEquals(false, stdSign)
        assertEquals(-400, normalizeBatteryCurrentMa(rawCurrent = -400, plugged = false, oemDischargePositive = stdSign))
    }

    @Test
    fun `detectTopCpuClusterPinned reflects readability and multi-cluster frequencies`() {
        val root = Files.createTempDirectory("cpu_sys_test").toFile()
        try {
            fun writeFreq(cpu: Int, freq: Long) {
                val dir = File(root, "cpu$cpu/cpufreq").apply { mkdirs() }
                File(dir, "cpuinfo_max_freq").writeText("$freq\n")
            }
            // 单簇视为未拆顶核
            writeFreq(0, 2_000_000L)
            writeFreq(1, 2_000_000L)
            assertEquals(false, detectTopCpuClusterPinned(root))

            // 存在第二个更高频簇时视为可排除最高频簇
            writeFreq(4, 2_850_000L)
            assertEquals(true, detectTopCpuClusterPinned(root))
        } finally {
            root.deleteRecursively()
        }
    }
}
