package org.maiwithu.maidroid.maibot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginConfigTomlTest {
    @Test
    fun `single-line values are complete`() {
        assertTrue(PluginConfigToml.isCompleteValue("true"))
        assertTrue(PluginConfigToml.isCompleteValue("\"a # not a comment\""))
        assertTrue(PluginConfigToml.isCompleteValue("[\"]\", '[']  # 注释"))
        assertTrue(PluginConfigToml.isCompleteValue("\"say \\\"hi\\\"\""))
        assertTrue(PluginConfigToml.isCompleteValue("{ a = [1, 2] }"))
    }

    @Test
    fun `values that continue on the next line are not`() {
        assertFalse(PluginConfigToml.isCompleteValue("["))
        assertFalse(PluginConfigToml.isCompleteValue("[1, 2,  # 下一行还有"))
        assertFalse(PluginConfigToml.isCompleteValue("\"\"\"多行"))
        assertFalse(PluginConfigToml.isCompleteValue("\"没闭合"))
        assertFalse(PluginConfigToml.isCompleteValue("# 只有注释"))
    }
}
