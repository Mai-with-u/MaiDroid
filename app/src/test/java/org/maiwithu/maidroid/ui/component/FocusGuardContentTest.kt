package org.maiwithu.maidroid.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusGuardContentTest {

    @Test
    fun touchModeIgnoresRootFallbackFocus() {
        assertEquals(
            RootFocusDispatchDecision.IgnoreInTouchMode,
            decideRootFocusDispatch(isInTouchMode = true)
        )
    }

    @Test
    fun nonTouchModeDefersRootFallbackFocusUntilFrameSettles() {
        assertEquals(
            RootFocusDispatchDecision.DeferNonTouchFocus,
            decideRootFocusDispatch(isInTouchMode = false)
        )
    }

    @Test
    fun deferredFocusRunsOnlyWhenAttachedNonTouchAndUnfocused() {
        assertTrue(
            shouldRunDeferredRootFocus(
                isAttachedToWindow = true,
                isInTouchMode = false,
                hasFocus = false
            )
        )
        assertFalse(
            shouldRunDeferredRootFocus(
                isAttachedToWindow = false,
                isInTouchMode = false,
                hasFocus = false
            )
        )
        assertFalse(
            shouldRunDeferredRootFocus(
                isAttachedToWindow = true,
                isInTouchMode = true,
                hasFocus = false
            )
        )
        assertFalse(
            shouldRunDeferredRootFocus(
                isAttachedToWindow = true,
                isInTouchMode = false,
                hasFocus = true
            )
        )
    }
}
