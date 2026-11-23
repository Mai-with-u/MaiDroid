package org.maiwithu.maidroid.ui.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maiwithu.maidroid.pet.DesktopPetPhase
import org.maiwithu.maidroid.pet.DesktopPetUiState

class ExtensionsPageTest {

    @Test
    fun `extensions layout picks compact for phones, two pane for phone landscape, and wide for tablets`() {
        // 手机竖屏：单列滚动
        assertEquals(ExtensionsLayout.Compact, extensionsLayout(widthDp = 360f, heightDp = 755f))
        // 手机横屏且高度矮于 560dp：左右双栏
        assertEquals(ExtensionsLayout.TwoPane, extensionsLayout(widthDp = 659f, heightDp = 360f))
        // 又矮又窄分屏窗口退回单列避免两栏挤占
        assertEquals(ExtensionsLayout.Compact, extensionsLayout(widthDp = 480f, heightDp = 360f))
        // 平板竖屏、横屏与折叠屏内屏：双列仪表盘
        assertEquals(ExtensionsLayout.Wide, extensionsLayout(widthDp = 800f, heightDp = 1180f))
        assertEquals(ExtensionsLayout.Wide, extensionsLayout(widthDp = 1180f, heightDp = 800f))
        assertEquals(ExtensionsLayout.Wide, extensionsLayout(widthDp = 673f, heightDp = 841f))
    }

    @Test
    fun `extensions header subtitle reflects pet runtime phase and webui availability`() {
        assertEquals(
            "悬浮桌宠运行中 · 已接入本机 MaiBot",
            extensionsHeaderSubtitle(
                petState = DesktopPetUiState(
                    phase = DesktopPetPhase.Running,
                    overlayActive = true,
                    connected = true,
                    overlayVisible = true
                ),
                webUiOnline = true
            )
        )
        assertEquals(
            "悬浮桌宠已隐藏 · 点击下方即可恢复显示",
            extensionsHeaderSubtitle(
                petState = DesktopPetUiState(
                    phase = DesktopPetPhase.Running,
                    overlayActive = true,
                    connected = true,
                    overlayVisible = false
                ),
                webUiOnline = true
            )
        )
        assertEquals(
            "正在连接本机悬浮桌宠服务…",
            extensionsHeaderSubtitle(DesktopPetUiState(phase = DesktopPetPhase.Connecting), webUiOnline = true)
        )
        assertEquals(
            "需要完成本机 WebUI 登录以同步桌宠会话",
            extensionsHeaderSubtitle(DesktopPetUiState(phase = DesktopPetPhase.AuthRequired), webUiOnline = true)
        )
        assertEquals(
            "需先一键开启远端桌宠配置",
            extensionsHeaderSubtitle(DesktopPetUiState(phase = DesktopPetPhase.ConfigRequired), webUiOnline = true)
        )
        assertEquals(
            "悬浮桌宠连接异常，可点击重连恢复",
            extensionsHeaderSubtitle(DesktopPetUiState(phase = DesktopPetPhase.Error), webUiOnline = true)
        )
        assertEquals(
            "本机 WebUI 已在线，随时可唤起悬浮桌宠",
            extensionsHeaderSubtitle(DesktopPetUiState(phase = DesktopPetPhase.Idle), webUiOnline = true)
        )
        assertEquals(
            "管理本机扩展组件与麦麦生态外部工具",
            extensionsHeaderSubtitle(DesktopPetUiState(phase = DesktopPetPhase.Idle), webUiOnline = false)
        )
    }

    @Test
    fun `extension catalog keeps desktop pet primary and user facing copy concise`() {
        assertTrue(ExtensionCatalog.primaryEntry.available)
        assertEquals(ExtensionId.DesktopPet, ExtensionCatalog.primaryEntry.id)
        assertFalse(ExtensionCatalog.upcomingEntries.isEmpty())
        ExtensionCatalog.upcomingEntries.forEach { entry ->
            assertFalse(entry.available)
            assertFalse(entry.subtitle.contains("导航架构"))
            assertFalse(entry.title.isBlank())
        }
    }
}
