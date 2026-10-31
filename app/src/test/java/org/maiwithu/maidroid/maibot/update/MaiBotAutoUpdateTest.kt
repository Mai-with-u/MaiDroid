package org.maiwithu.maidroid.maibot.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaiBotAutoUpdateTest {
    private fun due(
        setupComplete: Boolean = true,
        lastLaunched: Int = 4,
        current: Int = 5,
        disabled: Boolean = false
    ) = shouldAutoUpdateMaiBot(setupComplete, lastLaunched, current, disabled)

    @Test
    fun `first launch after a MaiDroid update upgrades MaiBot`() {
        assertTrue(due())
        // 记录版本号之前的旧版本升上来时读到 0，同样算刚更新过
        assertTrue(due(lastLaunched = 0))
    }

    @Test
    fun `same version or downgrade does not upgrade`() {
        assertFalse(due(lastLaunched = 5, current = 5))
        assertFalse(due(lastLaunched = 6, current = 5))
    }

    @Test
    fun `fresh install still in OOBE does not upgrade`() {
        assertFalse(due(setupComplete = false, lastLaunched = 0))
    }

    @Test
    fun `switch in settings turns it off`() {
        assertFalse(due(disabled = true))
    }
}
