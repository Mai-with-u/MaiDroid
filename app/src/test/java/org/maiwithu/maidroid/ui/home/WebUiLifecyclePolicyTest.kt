package org.maiwithu.maidroid.ui.home

import android.content.ComponentCallbacks2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maiwithu.maidroid.pet.computeEffectivePetVisibility
import org.maiwithu.maidroid.shouldPollHomeUiStatus

class WebUiLifecyclePolicyTest {

    @Test
    fun `shouldWebUiSessionBeActive is true only when foregrounded and WebUI tab is selected`() {
        assertTrue(shouldWebUiSessionBeActive(isStarted = true, isWebUiTabSelected = true))
        assertFalse(shouldWebUiSessionBeActive(isStarted = true, isWebUiTabSelected = false))
        assertFalse(shouldWebUiSessionBeActive(isStarted = false, isWebUiTabSelected = true))
        assertFalse(shouldWebUiSessionBeActive(isStarted = false, isWebUiTabSelected = false))
    }

    @Suppress("DEPRECATION")
    @Test
    fun `shouldHibernateWebUiOnTrimMemory triggers only when backgrounded at or above TRIM_MEMORY_BACKGROUND`() {
        assertEquals(2 * 60_000L, WEB_UI_HIBERNATE_DELAY_MS)
        assertFalse(shouldHibernateWebUiOnTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND, isStarted = true))
        assertFalse(shouldHibernateWebUiOnTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE, isStarted = true))
        assertFalse(shouldHibernateWebUiOnTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN, isStarted = false))
        assertTrue(shouldHibernateWebUiOnTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND, isStarted = false))
        assertTrue(shouldHibernateWebUiOnTrimMemory(ComponentCallbacks2.TRIM_MEMORY_MODERATE, isStarted = false))
        assertTrue(shouldHibernateWebUiOnTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE, isStarted = false))
    }

    @Test
    fun `shouldPollHomeUiStatus requires completed OOBE and visible foreground`() {
        assertTrue(shouldPollHomeUiStatus(setupComplete = true, uiForeground = true))
        assertFalse(shouldPollHomeUiStatus(setupComplete = true, uiForeground = false))
        assertFalse(shouldPollHomeUiStatus(setupComplete = false, uiForeground = true))
        assertFalse(shouldPollHomeUiStatus(setupComplete = false, uiForeground = false))
    }

    @Test
    fun `computeEffectivePetVisibility pauses overlay rendering whenever hidden or screen off`() {
        assertTrue(computeEffectivePetVisibility(overlayVisible = true, screenInteractive = true))
        assertFalse(computeEffectivePetVisibility(overlayVisible = true, screenInteractive = false))
        assertFalse(computeEffectivePetVisibility(overlayVisible = false, screenInteractive = true))
        assertFalse(computeEffectivePetVisibility(overlayVisible = false, screenInteractive = false))
    }
}
