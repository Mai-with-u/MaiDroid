package org.maiwithu.maidroid.maibot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MaiBotProjectVersionTest {
    @Test
    fun `reads the version of the project table only`() {
        val pyproject = """
            [tool.uv]
            version = "0.13.0"

            [project]
            name = "MaiBot"
            version = "1.3.19"
            requires-python = ">=3.12"
        """.trimIndent()

        assertEquals("1.3.19", maiBotProjectVersion(pyproject))
    }

    @Test
    fun `missing or dynamic version is unknown`() {
        assertNull(maiBotProjectVersion("[project]\nname = \"MaiBot\"\n"))
        assertNull(maiBotProjectVersion("[project]\ndynamic = [\"version\"]\n"))
        assertNull(maiBotProjectVersion(""))
    }
}
