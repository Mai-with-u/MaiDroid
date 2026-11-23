package org.maiwithu.maidroid.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineHeartbeatThrottleTest {

    @Test
    fun `shouldSendOnlineHeartbeat immediately allows upgrade and throttles same version`() {
        // 版本升级后无视 1 秒前刚刚报过的心跳，立刻上报新版本
        assertTrue(
            shouldSendOnlineHeartbeat(
                nowSec = 1001L,
                lastSentSec = 1000L,
                lastVersionCode = 12,
                lastVersionName = "0.4.0",
                currentVersionCode = 13,
                currentVersionName = "0.4.1",
                force = false
            )
        )
        // 同版本 30 秒内强制触发被节流，满 60 秒才放行
        assertFalse(
            shouldSendOnlineHeartbeat(
                nowSec = 1030L,
                lastSentSec = 1000L,
                lastVersionCode = 13,
                lastVersionName = "0.4.1",
                currentVersionCode = 13,
                currentVersionName = "0.4.1",
                force = true
            )
        )
        assertTrue(
            shouldSendOnlineHeartbeat(
                nowSec = 1060L,
                lastSentSec = 1000L,
                lastVersionCode = 13,
                lastVersionName = "0.4.1",
                currentVersionCode = 13,
                currentVersionName = "0.4.1",
                force = true
            )
        )
        // 后台周期非强制心跳需满 10 分钟（600 秒）
        assertFalse(
            shouldSendOnlineHeartbeat(
                nowSec = 1599L,
                lastSentSec = 1000L,
                lastVersionCode = 13,
                lastVersionName = "0.4.1",
                currentVersionCode = 13,
                currentVersionName = "0.4.1",
                force = false
            )
        )
        assertTrue(
            shouldSendOnlineHeartbeat(
                nowSec = 1600L,
                lastSentSec = 1000L,
                lastVersionCode = 13,
                lastVersionName = "0.4.1",
                currentVersionCode = 13,
                currentVersionName = "0.4.1",
                force = false
            )
        )
    }
}
