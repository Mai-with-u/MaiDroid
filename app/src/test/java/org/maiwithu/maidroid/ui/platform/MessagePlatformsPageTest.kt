package org.maiwithu.maidroid.ui.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagePlatformsPageTest {

    @Test
    fun `column count keeps cards above their minimum width`() {
        assertEquals(1, calculatePlatformGridColumnCount(555f))
        assertEquals(2, calculatePlatformGridColumnCount(556f))
    }

    @Test
    fun `column count adds columns when cards would exceed their maximum width`() {
        assertEquals(2, calculatePlatformGridColumnCount(852f))
        assertEquals(3, calculatePlatformGridColumnCount(853f))
        assertEquals(3, calculatePlatformGridColumnCount(1_284f))
        assertEquals(4, calculatePlatformGridColumnCount(1_285f))
    }

    @Test
    fun `three adapter selector fits inside the narrowest card`() {
        // 卡片最窄 272dp，左右各 16dp 内边距，选择器最多 240dp。
        assertEquals(224f, adapterSelectorWidth(3).value)
        assertTrue(adapterSelectorWidth(3).value <= 240f)
        assertEquals(76f, adapterSelectorWidth(1).value)
    }

    @Test
    fun `official bot sits leftmost and each adapter has its own provider text`() {
        val qq = AvailablePlatforms.first { it.name == "QQ" }
        assertEquals(listOf(PlatformAdapter.QqOfficial, PlatformAdapter.NapCat, PlatformAdapter.SnowLuma), qq.adapters)
        assertEquals("将由 QQ 官方接口提供服务", qq.adapterProviders[PlatformAdapter.QqOfficial] ?: qq.provider)
        assertEquals("将由 NapCat 提供服务", qq.adapterProviders[PlatformAdapter.NapCat] ?: qq.provider)
    }

    @Test
    fun `column count handles narrow and very wide content widths`() {
        assertEquals(1, calculatePlatformGridColumnCount(-1f))
        assertEquals(1, calculatePlatformGridColumnCount(328f))
        assertEquals(5, calculatePlatformGridColumnCount(1_888f))
    }

    @Test
    fun `platform search filters by category and keyword`() {
        assertEquals(AvailablePlatforms.size, filterPlatforms(AvailablePlatforms, "全部", "").size)
        assertEquals(listOf("QQ"), filterPlatforms(AvailablePlatforms, "全部", " qq ").map { it.name })
        assertEquals(listOf("Discord"), filterPlatforms(AvailablePlatforms, "海外", "游戏").map { it.name })
        assertTrue(filterPlatforms(AvailablePlatforms, "国内", "Discord").isEmpty())
    }

    @Test
    fun `short windows scroll the whole page instead of pinning the header`() {
        // 手机横屏全屏只有 360dp 左右高，竖屏手机 700dp 以上
        assertTrue(platformsUseSingleScroll(360f))
        assertFalse(platformsUseSingleScroll(755f))
    }

    @Test
    fun `unsupported platforms show plain placeholder copy`() {
        AvailablePlatforms.filter { it.adapters.isEmpty() }.forEach { platform ->
            assertEquals("暂未适配", platform.provider)
        }
    }
}
