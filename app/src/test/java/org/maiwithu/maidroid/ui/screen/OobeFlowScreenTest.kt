package org.maiwithu.maidroid.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OobeFlowScreenTest {
    @Test
    fun `phone portrait keeps the compact layout`() {
        assertFalse(oobeUsesAdaptiveLayout(widthDp = 360f, heightDp = 800f))
        assertFalse(oobeUsesAdaptiveLayout(widthDp = 412f, heightDp = 917f))
    }

    @Test
    fun `landscape uses the adaptive layout including narrow screens`() {
        assertTrue(oobeUsesAdaptiveLayout(widthDp = 800f, heightDp = 360f))
        assertTrue(oobeUsesAdaptiveLayout(widthDp = 480f, heightDp = 320f))
    }

    @Test
    fun `large screens use the adaptive layout in both orientations`() {
        assertTrue(oobeUsesAdaptiveLayout(widthDp = 1280f, heightDp = 800f))
        assertTrue(oobeUsesAdaptiveLayout(widthDp = 800f, heightDp = 1280f))
    }

    @Test
    fun `adaptive hero occupies one third of the screen`() {
        listOf(480f, 800f, 1280f, 2400f).forEach { width ->
            val heroWidth = oobeHeroWidthDp(width)
            assertEquals(width / 3f, heroWidth, 0f)
            assertEquals(2f, (width - heroWidth) / heroWidth, 0.001f)
        }
        assertEquals(2f, OOBE_WORK_PANE_WEIGHT / OOBE_HERO_PANE_WEIGHT, 0f)
    }

    @Test
    fun `only large landscape screens embed terminal output`() {
        assertTrue(oobeUsesEmbeddedTerminal(widthDp = 1280f, heightDp = 800f))
        assertTrue(oobeUsesEmbeddedTerminal(widthDp = 800f, heightDp = 600f))
        assertTrue(oobeUsesEmbeddedTerminal(widthDp = 601f, heightDp = 600f))

        assertFalse(oobeUsesEmbeddedTerminal(widthDp = 800f, heightDp = 599f))
        assertFalse(oobeUsesEmbeddedTerminal(widthDp = 800f, heightDp = 360f))
        assertFalse(oobeUsesEmbeddedTerminal(widthDp = 480f, heightDp = 320f))
        assertFalse(oobeUsesEmbeddedTerminal(widthDp = 800f, heightDp = 1280f))
        assertFalse(oobeUsesEmbeddedTerminal(widthDp = 600f, heightDp = 601f))
        assertFalse(oobeUsesEmbeddedTerminal(widthDp = 600f, heightDp = 600f))
    }

    @Test
    fun `embedded terminal starts at step two and respects its toggle`() {
        assertFalse(
            oobeShowsEmbeddedTerminal(
                embeddedMode = true,
                currentStep = 0,
                terminalVisible = true
            )
        )
        assertTrue(
            oobeShowsEmbeddedTerminal(
                embeddedMode = true,
                currentStep = 1,
                terminalVisible = true
            )
        )
        assertTrue(
            oobeShowsEmbeddedTerminal(
                embeddedMode = true,
                currentStep = 2,
                terminalVisible = true
            )
        )
        assertFalse(
            oobeShowsEmbeddedTerminal(
                embeddedMode = true,
                currentStep = 2,
                terminalVisible = false
            )
        )
        assertFalse(
            oobeShowsEmbeddedTerminal(
                embeddedMode = false,
                currentStep = 2,
                terminalVisible = true
            )
        )
        assertEquals(
            2f,
            OOBE_WORK_CONTENT_WEIGHT / OOBE_TERMINAL_PANE_WEIGHT,
            0f
        )
    }

    @Test
    fun `terminal visibility survives steps and layout changes after default is applied`() {
        val initial = OobeTerminalPresentationState(
            visible = false,
            embeddedDefaultApplied = false
        )
        val stepTwo = oobeTerminalStateForEnvironment(
            currentStep = 1,
            embeddedMode = true,
            visible = initial.visible,
            embeddedDefaultApplied = initial.embeddedDefaultApplied
        )
        assertEquals(
            OobeTerminalPresentationState(
                visible = true,
                embeddedDefaultApplied = true
            ),
            stepTwo
        )

        val manuallyClosed = stepTwo.copy(visible = false)
        val stepThree = oobeTerminalStateForEnvironment(
            currentStep = 2,
            embeddedMode = true,
            visible = manuallyClosed.visible,
            embeddedDefaultApplied = manuallyClosed.embeddedDefaultApplied
        )
        assertEquals(manuallyClosed, stepThree)

        val portrait = oobeTerminalStateForEnvironment(
            currentStep = 2,
            embeddedMode = false,
            visible = stepThree.visible,
            embeddedDefaultApplied = stepThree.embeddedDefaultApplied
        )
        assertEquals(manuallyClosed, portrait)

        val reset = oobeTerminalStateForEnvironment(
            currentStep = 0,
            embeddedMode = false,
            visible = true,
            embeddedDefaultApplied = true
        )
        assertEquals(initial, reset)
    }
}
