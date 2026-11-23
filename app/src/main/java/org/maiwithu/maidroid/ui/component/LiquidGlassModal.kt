package org.maiwithu.maidroid.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.kyant.backdrop.Backdrop

/** 全局同窗口液态玻璃弹窗宿主，由应用根容器提供并在页面与悬浮导航之上统一渲染弹窗。 */
internal val LocalLiquidModalHost = compositionLocalOf<LiquidModalHostState?> { null }

internal class LiquidModalEntry(
    val id: Long,
    initialContent: @Composable (Backdrop) -> Unit
) {
    var content by mutableStateOf(initialContent)
}

/**
 * 同窗口液态玻璃模态栈状态：
 * - 维护按打开顺序排列的弹窗条目，支持嵌套确认弹窗叠放在父弹窗之上；
 * - 仅在栈非空时对外暴露 [hasModals] = true，从而让背景图层按需录制、平时保持零开销。
 */
@Stable
internal class LiquidModalHostState {
    private var nextId by mutableLongStateOf(1L)
    internal val entries = mutableStateListOf<LiquidModalEntry>()

    val hasModals: Boolean
        get() = entries.isNotEmpty()

    fun allocateId(): Long {
        val assigned = nextId
        nextId += 1L
        return assigned
    }

    fun register(id: Long, content: @Composable (Backdrop) -> Unit) {
        val existing = entries.firstOrNull { it.id == id }
        if (existing != null) {
            existing.content = content
        } else {
            entries.add(LiquidModalEntry(id, content))
        }
    }

    fun update(id: Long, content: @Composable (Backdrop) -> Unit) {
        entries.firstOrNull { it.id == id }?.let { it.content = content }
    }

    fun unregister(id: Long) {
        entries.removeAll { it.id == id }
    }
}

@Composable
internal fun rememberLiquidModalHostState(): LiquidModalHostState = remember { LiquidModalHostState() }

/** 铺设在应用主界面与悬浮导航最上层的模态覆盖容器，为每层弹窗传递实时动态背景采样。 */
@Composable
internal fun LiquidModalHostOverlay(
    host: LiquidModalHostState,
    backdrop: Backdrop,
    modifier: Modifier = Modifier
) {
    if (!host.hasModals) return
    Box(modifier = modifier.fillMaxSize()) {
        host.entries.forEach { entry ->
            key(entry.id) {
                entry.content(backdrop)
            }
        }
    }
}
