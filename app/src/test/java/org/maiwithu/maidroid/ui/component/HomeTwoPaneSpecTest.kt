package org.maiwithu.maidroid.ui.component

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeTwoPaneSpecTest {

    @Test
    fun `two pane weights lock to exact 38 to 62 split`() {
        assertEquals(0.38f, HomeTwoPaneSpec.LeftPaneWeight, 0.0001f)
        assertEquals(0.62f, HomeTwoPaneSpec.RightPaneWeight, 0.0001f)
        assertEquals(1f, HomeTwoPaneSpec.LeftPaneWeight + HomeTwoPaneSpec.RightPaneWeight, 0.0001f)
    }

    @Test
    fun `left pane width uses fixed 38 percent and clamps negatives to zero`() {
        assertEquals(0f, HomeTwoPaneSpec.leftPaneWidthDp(-120f), 0.001f)
        assertEquals(0f, HomeTwoPaneSpec.leftPaneWidthDp(0f), 0.001f)
        assertEquals(520f * 0.38f, HomeTwoPaneSpec.leftPaneWidthDp(520f), 0.001f)
        assertEquals(659f * 0.38f, HomeTwoPaneSpec.leftPaneWidthDp(659f), 0.001f)
        assertEquals(1000f * 0.38f, HomeTwoPaneSpec.leftPaneWidthDp(1000f), 0.001f)
        assertEquals(1280f * 0.38f, HomeTwoPaneSpec.leftPaneWidthDp(1280f), 0.001f)
        assertEquals(1920f * 0.38f, HomeTwoPaneSpec.leftPaneWidthDp(1920f), 0.001f)
    }

    @Test
    fun `header top spacing aligns compact landscape and portrait extremes`() {
        assertEquals(8.dp, HomeTwoPaneSpec.headerTopSpacing(360f))
        assertEquals(8.dp, HomeTwoPaneSpec.headerTopSpacing(480f))
        assertEquals(24.dp, HomeTwoPaneSpec.headerTopSpacing(600f))
        assertEquals(40.dp, HomeTwoPaneSpec.headerTopSpacing(720f))
        assertEquals(40.dp, HomeTwoPaneSpec.headerTopSpacing(917f))
    }
}
