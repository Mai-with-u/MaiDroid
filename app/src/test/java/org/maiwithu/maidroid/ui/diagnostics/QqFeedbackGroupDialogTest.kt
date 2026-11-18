package org.maiwithu.maidroid.ui.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test
import org.maiwithu.maidroid.VersionNumber
import org.maiwithu.maidroid.appupdate.AppRelease

class QqFeedbackGroupDialogTest {
    private fun release(versionText: String) = AppRelease(
        tag = "v$versionText",
        version = checkNotNull(VersionNumber.parse(versionText)),
        pageUrl = "https://github.com/Mai-with-u/MaiDroid/releases/tag/v$versionText",
        publishedAtEpochSeconds = null,
        notes = "",
        apk = null,
        apkSha256 = null,
        certSha256 = null
    )

    @Test
    fun groupConstantsMatchOfficialInvite() {
        assertEquals("麦麦不吃苹果派", QQ_FEEDBACK_GROUP_NAME)
        assertEquals("750194887", QQ_FEEDBACK_GROUP_NUMBER)
        assertEquals("https://qm.qq.com/q/8t59i2bhIc", QQ_FEEDBACK_GROUP_URL)
    }

    @Test
    fun fallsBackToInstalledVersionWhenReleaseNotLoaded() {
        assertEquals("0.2.4", resolveLatestAppVersion("0.2.4", release = null))
        assertEquals("0.2.4", resolveLatestAppVersion("v0.2.4", release = null))
    }

    @Test
    fun prefersNewerOnlineReleaseForOutdatedClients() {
        assertEquals("0.2.5", resolveLatestAppVersion("0.2.4", release = release("0.2.5")))
    }

    @Test
    fun keepsInstalledVersionWhenNewerOrEqualToOnlineRelease() {
        assertEquals("0.2.4", resolveLatestAppVersion("0.2.4", release = release("0.2.4")))
        assertEquals("0.3.0", resolveLatestAppVersion("0.3.0", release = release("0.2.4")))
    }
}
