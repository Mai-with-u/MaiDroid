package org.maiwithu.maidroid.pet

import android.content.Context
import android.view.View
import org.json.JSONArray

/** 悬浮桌宠原生窗口宿主契约：隔离总控状态机与 Android Service 窗口操作，便于单测与模块化演进。 */
internal interface PetOverlayHost {
    fun attachTransportView(authView: View)
    fun fit(cssHeight: Double)
    fun updateTouchableRegion(rects: JSONArray)
    fun updateInputFocus(editing: Boolean)
    fun dragBegin()
    fun dragMove()
    fun dragEnd()
    fun setOverlayVisible(visible: Boolean)
    fun updateStatusNotification(text: String)
    fun shutdownFromManager()
}

/** 负责拉起悬浮窗服务与开关探索记录独立页的平台启动器接口。 */
internal interface PetWindowLauncher {
    fun startOverlay(context: Context)
    fun openRecords(context: Context)
    fun closeRecords()
}
