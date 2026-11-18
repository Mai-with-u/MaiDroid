package org.maiwithu.maidroid.ui.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteActionTargetTest {

    @Test
    fun parsesBuiltInActionsAndHttpsLinksWhileRejectingUnsafeSchemes() {
        assertEquals(RemoteActionTarget.AppUpdate, parseRemoteActionTarget("open:app_update"))
        assertEquals(RemoteActionTarget.MaiBotUpdate, parseRemoteActionTarget("OpenMaiBotUpdate"))
        assertEquals(RemoteActionTarget.Cleanup, parseRemoteActionTarget("open:cleanup"))
        assertEquals(RemoteActionTarget.QqGroup, parseRemoteActionTarget("open:qq_group"))
        assertEquals(RemoteActionTarget.AppPermissions, parseRemoteActionTarget("open:permissions"))
        assertEquals(
            RemoteActionTarget.WebUrl("https://github.com/Mai-with-u/MaiDroid"),
            parseRemoteActionTarget("url:https://github.com/Mai-with-u/MaiDroid")
        )

        // 明文 http、javascript、intent 等非安全 scheme 一律拒绝
        assertNull(parseRemoteActionTarget("url:http://example.com"))
        assertNull(parseRemoteActionTarget("javascript:alert(1)"))
        assertNull(parseRemoteActionTarget("intent://scan/#Intent;end"))
        assertNull(parseRemoteActionTarget(""))
    }
}
