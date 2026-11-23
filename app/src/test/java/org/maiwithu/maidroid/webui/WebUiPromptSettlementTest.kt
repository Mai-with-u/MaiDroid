package org.maiwithu.maidroid.webui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebUiPromptSettlementTest {

    /** 模拟 GeckoView BasePrompt 状态机：二次 confirm/dismiss 会直接抛出 RuntimeException。 */
    private class FakePrompt {
        var isComplete: Boolean = false
            private set
        var confirmCount: Int = 0
            private set
        var dismissCount: Int = 0
            private set

        fun confirm(choice: String): String {
            if (isComplete) {
                throw RuntimeException("Cannot confirm/dismiss a Prompt twice.")
            }
            isComplete = true
            confirmCount++
            return choice
        }

        fun dismiss(): String {
            if (isComplete) {
                throw RuntimeException("Cannot confirm/dismiss a Prompt twice.")
            }
            isComplete = true
            dismissCount++
            return "DISMISS"
        }

        fun markExternalComplete() {
            isComplete = true
        }
    }

    @Test
    fun buttonConfirmFollowedByDialogDismissDoesNotEvaluateDismissTwice() {
        val prompt = FakePrompt()
        val completed = mutableListOf<String>()
        val guard = PromptSettlementGuard<String>(
            isAlreadyComplete = { prompt.isComplete }
        ) { completed.add(it) }

        // 精确复现 ops 线上闪退时序：点击确定按钮完成 confirm，紧接着触发 Dialog.onDismiss 兜底闭包。
        assertTrue(guard.settleOnce { prompt.confirm("POSITIVE") })
        assertFalse(guard.settleOnce { prompt.dismiss() })

        assertEquals(1, prompt.confirmCount)
        assertEquals(0, prompt.dismissCount)
        assertEquals(listOf("POSITIVE"), completed)
    }

    @Test
    fun externalCancelSettlesOnceWithDismiss() {
        val prompt = FakePrompt()
        val completed = mutableListOf<String>()
        val guard = PromptSettlementGuard<String>(
            isAlreadyComplete = { prompt.isComplete }
        ) { completed.add(it) }

        assertTrue(guard.settleOnce { prompt.dismiss() })
        assertFalse(guard.settleOnce { prompt.dismiss() })

        assertEquals(0, prompt.confirmCount)
        assertEquals(1, prompt.dismissCount)
        assertEquals(listOf("DISMISS"), completed)
    }

    @Test
    fun rapidDoubleTapAndDismissOnlyExecutesFirstAction() {
        val prompt = FakePrompt()
        val completed = mutableListOf<String>()
        val guard = PromptSettlementGuard<String>(
            isAlreadyComplete = { prompt.isComplete }
        ) { completed.add(it) }

        assertTrue(guard.settleOnce { prompt.confirm("NEGATIVE") })
        assertFalse(guard.settleOnce { prompt.confirm("POSITIVE") })
        assertFalse(guard.settleOnce { prompt.dismiss() })

        assertEquals(1, prompt.confirmCount)
        assertEquals(0, prompt.dismissCount)
        assertEquals(listOf("NEGATIVE"), completed)
    }

    @Test
    fun skipsWhenPromptAlreadyCompletedExternally() {
        val prompt = FakePrompt()
        prompt.markExternalComplete()
        val completed = mutableListOf<String>()
        var producerEvaluated = false
        val guard = PromptSettlementGuard<String>(
            isAlreadyComplete = { prompt.isComplete }
        ) { completed.add(it) }

        assertFalse(
            guard.settleOnce {
                producerEvaluated = true
                prompt.dismiss()
            }
        )
        assertFalse(producerEvaluated)
        assertTrue(completed.isEmpty())
    }
}
