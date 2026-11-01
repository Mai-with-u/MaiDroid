package org.maiwithu.maidroid.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.maiwithu.maidroid.diagnostics.FailureStep

class NetworkStatusTest {
    @Test
    fun `offline check only fails when the phone has no network`() {
        assertEquals(
            "UV-SYNC/NET-OFFLINE",
            NetworkStatus(connected = false).offlineFailure(FailureStep.UvSync)?.code
        )
        // 系统没验证过能上网也照常放行：国内不少网络连不上系统的检测地址
        assertNull(NetworkStatus(connected = true, validated = false).offlineFailure(FailureStep.UvSync))
    }
}
