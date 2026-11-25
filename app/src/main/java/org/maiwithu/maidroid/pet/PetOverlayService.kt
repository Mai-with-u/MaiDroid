package org.maiwithu.maidroid.pet

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Region
import android.hardware.input.InputManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Choreographer
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.maiwithu.maidroid.MainActivity
import org.maiwithu.maidroid.R
import org.maiwithu.maidroid.appDependencies
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 承载本机悬浮桌宠的独立前景服务（TYPE_APPLICATION_OVERLAY + specialUse）：
 * - Android 13+（API 33+）：固定透明全高画布，按页面上报热区实时设置 `setTouchableRegion` 实现精确点击穿透；
 * - Android 8–12（API 26–32）：使用动态上裁剪高度保证上方空白不拦触摸；
 * - 默认置入 `FLAG_NOT_FOCUSABLE` 不抢底层焦点，仅在前端文本框获焦时短暂接管键盘并自动上移避让输入法。
 */
class PetOverlayService : Service(), PetOverlayHost {

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "maidroid_desktop_pet"
        private const val NOTIFICATION_ID = 1002
        private const val ACTION_STOP = "org.maiwithu.maidroid.action.STOP_DESKTOP_PET"

        private const val CLIP_HEADROOM_DP = 16.0
        private const val CLIP_SHRINK_DEADBAND_DP = 24.0
        private const val BASE_PET_WIDTH_DP = 260.0
        private const val BASE_MIN_VISIBLE_HEIGHT_DP = 180.0

        /** 悬浮桌宠服务当前是否存活，供遥测统计耗时热点而不触发管理器的懒加载初始化。 */
        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, PetOverlayService::class.java)
            context.startForegroundService(intent)
        }
    }

    private lateinit var manager: DesktopPetManager
    private lateinit var windowManager: WindowManager
    private lateinit var frame: FrameLayout
    private lateinit var params: WindowManager.LayoutParams

    // Android 13+ 常驻全屏窗口内部的子舞台容器，直接通过 translation/scale 驱动 GPU 渲染与物理反弹。
    private var petContainer: FrameLayout? = null
    private var localHitRects: List<Rect> = emptyList()
    private var topHitCss: Double = 0.0
    private var hasReportedHitRects = false
    private var regionUpdateFrameCounter = 0
    private var pendingClipRawTopPx: Int? = null

    private var petView: PetWebView? = null
    private var density = 1f
    private var rawX = 0f
    private var rawY = 0f
    private var downX = 0f
    private var downY = 0f
    private var fullHeight = 0
    private var clipTop = 0
    private var petX = 0f
    private var petBottom = 0f
    private var dragStartX = 0f
    private var dragStartBottom = 0f
    private var keyboardTop = Int.MAX_VALUE
    private var dragging = false
    private var moved = false
    private var pointerActive = false
    private var closing = false
    private var gestureId = ""
    private var pendingDragEndGestureId: String? = null
    private val velocityTracker = PetVelocityTracker()
    private val flingEngine = PetFlingEngine()
    private val bounceSquash = PetBounceSquashSpring()
    private lateinit var choreographer: Choreographer
    private var motionFrameScheduled = false
    private var lastMotionFrameNanos = 0L
    private val motionFrameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        onMotionFrame(frameTimeNanos)
    }
    private val supportsTouchRegions = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    private var screenReceiverRegistered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> manager.onScreenInteractiveChanged(true)
                Intent.ACTION_SCREEN_OFF -> manager.onScreenInteractiveChanged(false)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility", "RtlHardcoded")
    override fun onCreate() {
        super.onCreate()
        manager = appDependencies.desktopPet
        ensureNotificationChannel()
        promoteToForeground(buildNotification("麦麦桌宠运行中"))

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        windowManager = getSystemService(WindowManager::class.java)
        choreographer = Choreographer.getInstance()
        density = resources.displayMetrics.density
        val displayHeight = resources.displayMetrics.heightPixels
        val displayWidth = resources.displayMetrics.widthPixels

        fullHeight = min(dp(900.0), displayHeight)
        val petWidthPx = dp(BASE_PET_WIDTH_DP)
        petX = max(0, displayWidth - petWidthPx).toFloat()
        petBottom = max(dp(BASE_MIN_VISIBLE_HEIGHT_DP), displayHeight - dp(80.0)).toFloat()

        frame = FrameLayout(this).apply { clipChildren = true }
        val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH

        if (supportsTouchRegions) {
            // Android 13+ 外层悬浮窗永久固定满屏且坐标锁死 (0,0)，仅靠内部子视图平移和热区裁剪零 IPC 跑满刷新率。
            params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                baseFlags or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                x = 0
                y = 0
                applyOverlayWindowChrome(this)
            }
        } else {
            params = WindowManager.LayoutParams(
                petWidthPx,
                dp(BASE_MIN_VISIBLE_HEIGHT_DP),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                baseFlags,
                PixelFormat.TRANSLUCENT
            ).apply {
                // 前端上报坐标按屏幕左上角基准对齐，必须显式使用 Gravity.LEFT 避免 RTL 镜像；紧凑小窗保持 1.0 不透明。
                gravity = Gravity.TOP or Gravity.LEFT
                x = petX.roundToInt()
                y = max(0, petBottom.roundToInt() - height)
            }
        }
        @Suppress("DEPRECATION")
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE

        val boundPet = manager.bindOverlayService(this, this)
        if (boundPet == null) {
            stopSelf()
            return
        }
        petView = boundPet

        if (supportsTouchRegions) {
            val container = FrameLayout(this).apply { clipChildren = true }
            val containerParams = FrameLayout.LayoutParams(petWidthPx, fullHeight).apply {
                gravity = Gravity.TOP or Gravity.LEFT
            }
            val pageLayout = FrameLayout.LayoutParams(petWidthPx, fullHeight)
            container.addView(boundPet, pageLayout)
            frame.addView(container, containerParams)
            petContainer = container
            clipTop = 0
        } else {
            val pageLayout = FrameLayout.LayoutParams(petWidthPx, fullHeight)
            clipTop = max(0, fullHeight - params.height)
            pageLayout.topMargin = -clipTop
            frame.addView(boundPet, pageLayout)
        }

        setupImeAvoidance()
        setupTouchAndKeyListeners(boundPet)

        windowManager.addView(frame, params)
        if (supportsTouchRegions) {
            frame.post { applyFullScreenContainerTransform(updateRegionNow = true) }
        }
        isRunning = true
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        screenReceiverRegistered = true
        boundPet.loadUrl(DesktopPetProtocol.PET_PAGE_URL)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            manager.stop()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::frame.isInitialized) return
        stopMotionNow()
        clampLogicalCoordinates(currentMotionBounds())
        if (supportsTouchRegions) {
            applyFullScreenContainerTransform(updateRegionNow = true)
        } else {
            place(forceLayout = true)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopMotionNow()
        isRunning = false
        if (screenReceiverRegistered) {
            screenReceiverRegistered = false
            runCatching { unregisterReceiver(screenReceiver) }
        }
        if (!closing) {
            closing = true
            removeOverlayFrame()
            manager.onServiceDestroyed(this)
        }
        super.onDestroy()
    }

    override fun attachTransportView(authView: View) {
        if (!::frame.isInitialized) return
        if (authView.parent !== frame) {
            (authView.parent as? ViewGroup)?.removeView(authView)
            frame.addView(authView, 0, FrameLayout.LayoutParams(1, 1))
        }
        authView.alpha = 0f
    }

    override fun fit(cssHeight: Double) {
        if (!cssHeight.isFinite() || cssHeight < 1.0 || cssHeight > 2000.0) return
        val pet = petView ?: return
        fullHeight = dp(max(460.0, min(900.0, cssHeight)))
        val pageLayout = pet.layoutParams as FrameLayout.LayoutParams
        pageLayout.height = fullHeight
        if (supportsTouchRegions) {
            clipTop = 0
            pageLayout.topMargin = 0
            pet.layoutParams = pageLayout
            petContainer?.let { container ->
                val containerLayout = container.layoutParams as FrameLayout.LayoutParams
                containerLayout.height = fullHeight
                container.layoutParams = containerLayout
            }
            applyFullScreenContainerTransform(updateRegionNow = true)
            return
        }
        clipTop = max(0, fullHeight - params.height)
        pageLayout.topMargin = -clipTop
        pet.layoutParams = pageLayout
    }

    override fun updateTouchableRegion(rects: JSONArray) {
        if (rects.length() > 256) return
        val parsedRects = ArrayList<Rect>(rects.length())
        var topCss = fullHeight / density.toDouble()
        for (i in 0 until rects.length()) {
            val rect = rects.optJSONArray(i) ?: continue
            if (rect.length() < 4) continue
            val x = rect.getDouble(0)
            val y = rect.getDouble(1)
            val w = rect.getDouble(2)
            val h = rect.getDouble(3)
            if (!x.isFinite() || !y.isFinite() || !w.isFinite() || !h.isFinite()) continue
            if (w > 0.0 && h > 0.0) {
                topCss = min(topCss, y)
                parsedRects.add(Rect(dp(x), dp(y), dp(x + w), dp(y + h)))
            }
        }

        localHitRects = parsedRects
        topHitCss = topCss
        hasReportedHitRects = parsedRects.isNotEmpty()

        if (supportsTouchRegions) {
            applyCurrentTouchableRegion()
            return
        }

        val rawTopPx = max(0, min(fullHeight - dp(1.0), dp(topCss)))
        if (dragging || moved || isMotionActive()) {
            pendingClipRawTopPx = rawTopPx
            return
        }
        applyStableLegacyClipTop(rawTopPx)
    }

    override fun updateInputFocus(editing: Boolean) {
        if (!::frame.isInitialized || !frame.isAttachedToWindow) return
        if (editing) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(frame.windowToken, 0)
        }
        windowManager.updateViewLayout(frame, params)
    }

    override fun dragBegin() {
        pendingDragEndGestureId?.let { previousId ->
            pendingDragEndGestureId = null
            manager.sendDragEnded(previousId)
        }
        // 新手势开始或中途按住滑行中的桌宠时，立即停掉上一轮滑行动画。
        stopMotionNow()
        if (!pointerActive) {
            dragging = false
            moved = false
            return
        }
        dragging = true
        moved = false
        dragStartX = petX
        dragStartBottom = petBottom
        gestureId = UUID.randomUUID().toString().replace("-", "")
    }

    override fun dragMove() {
        if (!dragging || !pointerActive || flingEngine.isRunning) return
        advanceDragPosition()
    }

    override fun dragEnd() {
        pendingDragEndGestureId?.let { activeId ->
            pendingDragEndGestureId = null
            manager.sendDragEnded(activeId)
        }
        dragging = false
    }

    override fun setOverlayVisible(visible: Boolean) {
        if (!visible) {
            stopMotionNow()
        }
        if (::frame.isInitialized) {
            frame.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    override fun updateStatusNotification(text: String) {
        if (closing) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun shutdownFromManager() {
        stopMotionNow()
        closing = true
        removeOverlayFrame()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchAndKeyListeners(pet: PetWebView) {
        frame.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                updateInputFocus(false)
            }
            false
        }
        pet.setOnTouchListener { _, event ->
            rawX = event.rawX
            rawY = event.rawY
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pointerActive = true
                    stopMotionNow()
                    downX = rawX
                    downY = rawY
                    velocityTracker.reset(rawX, rawY, event.eventTime)
                }

                MotionEvent.ACTION_MOVE -> {
                    velocityTracker.addSample(rawX, rawY, event.eventTime)
                    if (dragging) {
                        advanceDragPosition()
                    }
                }

                MotionEvent.ACTION_UP -> {
                    velocityTracker.addSample(rawX, rawY, event.eventTime)
                    // dragEnd() 由前端经 JS 异步送达，必须在抬手瞬间就结算速度并启动滑行。
                    if (dragging && moved) {
                        val velocity = velocityTracker.computeVelocity(event.eventTime, density)
                        startFling(velocity.vx, velocity.vy)
                    }
                    moved = false
                    dragging = false
                    pointerActive = false
                    velocityTracker.clear()
                    manager.onOverlayInputEditingCheck()
                }

                MotionEvent.ACTION_CANCEL -> {
                    moved = false
                    dragging = false
                    pointerActive = false
                    velocityTracker.clear()
                    pendingDragEndGestureId?.let { activeId ->
                        pendingDragEndGestureId = null
                        manager.sendDragEnded(activeId)
                    }
                    manager.onOverlayInputEditingCheck()
                }
            }
            false
        }
        pet.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                updateInputFocus(false)
                true
            } else {
                false
            }
        }
    }

    private fun advanceDragPosition() {
        if (!dragging) return
        if (!moved && hypot((rawX - downX).toDouble(), (rawY - downY).toDouble()) >= dp(4.0)) {
            moved = true
            pendingDragEndGestureId = gestureId
            manager.sendDragStarted(
                DesktopPetProtocol.computeDragStartPayload(
                    gestureId = gestureId,
                    windowX = dragStartX.roundToInt(),
                    windowWidth = dp(BASE_PET_WIDTH_DP),
                    windowBottom = dragStartBottom.roundToInt(),
                    pointerDownX = downX,
                    pointerDownY = downY,
                    density = density
                )
            )
        }
        if (moved) {
            val bounds = currentMotionBounds()
            petX = clampToAxis(dragStartX + (rawX - downX), bounds.minX, bounds.maxX)
            petBottom = clampToAxis(dragStartBottom + (rawY - downY), bounds.minBottom, bounds.maxBottom)
            if (supportsTouchRegions) {
                applyFullScreenContainerTransform(updateRegionNow = false)
            } else {
                params.x = petX.roundToInt()
                place(forceLayout = false)
            }
        }
    }

    private fun startFling(vx: Float, vy: Float) {
        if (closing || !::frame.isInitialized || frame.visibility != View.VISIBLE) return
        bounceSquash.reset()
        val started = flingEngine.start(
            startX = petX,
            startBottom = petBottom,
            initialVx = vx,
            initialVy = vy,
            density = density,
            bounds = currentMotionBounds()
        )
        if (started) {
            lastMotionFrameNanos = 0L
            scheduleNextMotionFrame()
        } else if (supportsTouchRegions) {
            applyFullScreenContainerTransform(updateRegionNow = true)
        } else {
            flushPendingClipTop()
        }
    }

    private fun stopMotionNow() {
        flingEngine.stop()
        bounceSquash.reset()
        lastMotionFrameNanos = 0L
        if (motionFrameScheduled && ::choreographer.isInitialized) {
            choreographer.removeFrameCallback(motionFrameCallback)
            motionFrameScheduled = false
        }
        if (supportsTouchRegions) {
            applyFullScreenContainerTransform(updateRegionNow = true)
        }
    }

    private fun scheduleNextMotionFrame() {
        if (motionFrameScheduled || closing || !::choreographer.isInitialized) return
        motionFrameScheduled = true
        choreographer.postFrameCallback(motionFrameCallback)
    }

    private fun isMotionActive(): Boolean = flingEngine.isRunning || bounceSquash.isActive

    private fun onMotionFrame(frameTimeNanos: Long) {
        motionFrameScheduled = false
        if (!isMotionActive() || closing || !::frame.isInitialized || frame.visibility != View.VISIBLE) {
            stopMotionNow()
            flushPendingClipTop()
            return
        }
        val dtSeconds = if (lastMotionFrameNanos == 0L) {
            1f / 60f
        } else {
            ((frameTimeNanos - lastMotionFrameNanos) / 1_000_000_000f).coerceAtLeast(0f)
        }
        lastMotionFrameNanos = frameTimeNanos

        var flingRunning = flingEngine.isRunning
        if (flingRunning) {
            val stepResult = flingEngine.stepWithImpact(dtSeconds, density, currentMotionBounds())
            petX = flingEngine.x
            petBottom = flingEngine.bottom
            stepResult.impact?.let { bounceSquash.onImpact(it, density) }
            flingRunning = stepResult.running
        }
        val squashActive = bounceSquash.step(dtSeconds)

        if (supportsTouchRegions) {
            regionUpdateFrameCounter = (regionUpdateFrameCounter + 1) and 1
            applyFullScreenContainerTransform(updateRegionNow = regionUpdateFrameCounter == 0)
        } else {
            params.x = petX.roundToInt()
            place(forceLayout = false)
        }

        if (flingRunning || squashActive) {
            scheduleNextMotionFrame()
        } else {
            stopMotionNow()
            flushPendingClipTop()
        }
    }

    private fun applyFullScreenContainerTransform(updateRegionNow: Boolean) {
        val container = petContainer ?: return
        val effectiveBottom = min(petBottom, keyboardTop.toFloat())
        val containerTop = effectiveBottom - fullHeight.toFloat()
        val topVisible = if (hasReportedHitRects) {
            dp(topHitCss).coerceIn(0, max(0, fullHeight - dp(BASE_MIN_VISIBLE_HEIGHT_DP))).toFloat()
        } else {
            max(0f, fullHeight - dp(BASE_MIN_VISIBLE_HEIGHT_DP).toFloat())
        }
        val visibleHeight = (fullHeight.toFloat() - topVisible).coerceAtLeast(1f)
        applySquashTransform(
            target = container,
            translationX = petX,
            translationY = containerTop,
            width = container.width.takeIf { it > 0 }?.toFloat() ?: dp(BASE_PET_WIDTH_DP).toFloat(),
            visibleTop = topVisible,
            visibleHeight = visibleHeight
        )
        if (updateRegionNow) {
            applyCurrentTouchableRegion()
        }
    }

    private fun applySquashTransform(
        target: View,
        translationX: Float,
        translationY: Float,
        width: Float,
        visibleTop: Float,
        visibleHeight: Float
    ) {
        target.translationX = translationX
        target.translationY = translationY
        target.pivotX = width * bounceSquash.pivotXFraction
        target.pivotY = visibleTop + visibleHeight * bounceSquash.pivotYFraction
        target.scaleX = bounceSquash.scaleX
        target.scaleY = bounceSquash.scaleY
    }

    private fun applyCurrentTouchableRegion() {
        if (!supportsTouchRegions || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val offsetX = petX.roundToInt()
        val offsetY = (min(petBottom, keyboardTop.toFloat()) - fullHeight).roundToInt()
        val touchable = Region()
        for (rect in localHitRects) {
            touchable.op(
                rect.left + offsetX,
                rect.top + offsetY,
                rect.right + offsetX,
                rect.bottom + offsetY,
                Region.Op.UNION
            )
        }
        frame.rootSurfaceControl?.setTouchableRegion(touchable)
    }

    private fun applyStableLegacyClipTop(rawTopPx: Int) {
        val pet = petView ?: return
        val nextClipTop = resolveStableClipTop(
            currentClipTop = clipTop,
            rawTopPx = rawTopPx,
            fullHeight = fullHeight,
            minVisiblePx = dp(1.0),
            headroomPx = dp(CLIP_HEADROOM_DP),
            shrinkDeadbandPx = dp(CLIP_SHRINK_DEADBAND_DP)
        )
        if (nextClipTop == clipTop) return
        clipTop = nextClipTop
        params.height = fullHeight - clipTop
        val layout = pet.layoutParams as FrameLayout.LayoutParams
        layout.topMargin = -clipTop
        pet.layoutParams = layout
        place(forceLayout = true)
    }

    private fun flushPendingClipTop() {
        if (supportsTouchRegions) return
        val pending = pendingClipRawTopPx ?: return
        pendingClipRawTopPx = null
        applyStableLegacyClipTop(pending)
    }

    private fun setupImeAvoidance() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            frame.setOnApplyWindowInsetsListener { _, insets ->
                val visible = insets.isVisible(WindowInsets.Type.ime())
                val overlap = insets.getInsets(WindowInsets.Type.ime()).bottom
                val nextTop = when {
                    !visible -> Int.MAX_VALUE
                    overlap > 0 -> imeLimitTopFromOverlap(overlap)
                    else -> keyboardTop
                }
                if (keyboardTop != nextTop) {
                    keyboardTop = nextTop
                    onMotionBoundsChanged()
                }
                insets
            }
        } else {
            frame.viewTreeObserver.addOnGlobalLayoutListener {
                val visible = Rect()
                val display = DisplayMetrics()
                val location = IntArray(2)
                frame.getWindowVisibleDisplayFrame(visible)
                frame.getLocationOnScreen(location)
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(display)
                val nextTop = if (display.heightPixels - visible.bottom > dp(100.0)) {
                    visible.bottom - (location[1] - params.y)
                } else {
                    Int.MAX_VALUE
                }
                if (keyboardTop != nextTop) {
                    keyboardTop = nextTop
                    onMotionBoundsChanged()
                }
            }
        }
    }

    private fun imeLimitTopFromOverlap(overlapPx: Int): Int {
        if (supportsTouchRegions) {
            return max(dp(BASE_MIN_VISIBLE_HEIGHT_DP), currentDisplayHeightPx() - overlapPx)
        }
        return params.y + params.height - overlapPx
    }

    private fun onMotionBoundsChanged() {
        clampLogicalCoordinates(currentMotionBounds())
        if (supportsTouchRegions) {
            applyFullScreenContainerTransform(updateRegionNow = true)
        } else {
            place(forceLayout = true)
        }
    }

    private fun currentDisplayWidthPx(): Int {
        if (supportsTouchRegions) {
            if (::frame.isInitialized && frame.width > 0) return frame.width
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return windowManager.currentWindowMetrics.bounds.width()
            }
        }
        return resources.displayMetrics.widthPixels
    }

    private fun currentDisplayHeightPx(): Int {
        if (supportsTouchRegions) {
            if (::frame.isInitialized && frame.height > 0) return frame.height
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return windowManager.currentWindowMetrics.bounds.height()
            }
        }
        return resources.displayMetrics.heightPixels
    }

    private fun currentMotionBounds(): PetMotionBounds {
        val displayWidth = currentDisplayWidthPx()
        val displayHeight = currentDisplayHeightPx()
        val petWidthPx = dp(BASE_PET_WIDTH_DP)
        val maxX = max(0, displayWidth - petWidthPx)
        val effectiveMaxBottom = min(displayHeight, keyboardTop)
        val rawMinBottom = if (supportsTouchRegions) {
            val contentHeight = if (hasReportedHitRects) {
                max(dp(BASE_MIN_VISIBLE_HEIGHT_DP), fullHeight - dp(topHitCss).coerceAtLeast(0))
            } else {
                dp(BASE_MIN_VISIBLE_HEIGHT_DP)
            }
            contentHeight
        } else {
            params.height
        }
        val effectiveMinBottom = min(rawMinBottom, effectiveMaxBottom)
        return PetMotionBounds(
            minX = 0f,
            maxX = maxX.toFloat(),
            minBottom = effectiveMinBottom.toFloat(),
            maxBottom = effectiveMaxBottom.toFloat()
        )
    }

    private fun clampLogicalCoordinates(bounds: PetMotionBounds) {
        petX = clampToAxis(petX, bounds.minX, bounds.maxX)
        petBottom = clampToAxis(petBottom, bounds.minBottom, bounds.maxBottom)
    }

    private fun clampToAxis(value: Float, minBound: Float, maxBound: Float): Float =
        if (!value.isFinite() || minBound >= maxBound) minBound else value.coerceIn(minBound, maxBound)

    private fun place(forceLayout: Boolean = true) {
        if (supportsTouchRegions) {
            clampLogicalCoordinates(currentMotionBounds())
            applyFullScreenContainerTransform(updateRegionNow = true)
            return
        }
        val bounds = currentMotionBounds()
        val prevX = params.x
        val prevY = params.y
        clampLogicalCoordinates(bounds)
        params.x = petX.roundToInt()
        params.y = max(0, min(petBottom.roundToInt(), keyboardTop) - params.height)
        if (::frame.isInitialized && frame.isAttachedToWindow &&
            (forceLayout || params.x != prevX || params.y != prevY)
        ) {
            windowManager.updateViewLayout(frame, params)
        }
    }

    private fun applyOverlayWindowChrome(targetParams: WindowManager.LayoutParams) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            targetParams.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            targetParams.fitInsetsTypes = 0
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            targetParams.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            targetParams.alpha = getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            targetParams.setCanPlayMoveAnimation(false)
        }
    }

    private fun removeOverlayFrame() {
        if (::frame.isInitialized && frame.isAttachedToWindow) {
            runCatching { windowManager.removeView(frame) }
        }
    }

    private fun ensureNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "悬浮桌宠",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun promoteToForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, PetOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("麦麦悬浮桌宠")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "停止桌宠", stopIntent)
            .build()
    }

    private fun dp(value: Double): Int = (value * density).roundToInt()
}
