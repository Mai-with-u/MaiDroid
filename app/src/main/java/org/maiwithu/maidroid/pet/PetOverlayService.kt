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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
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
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.maiwithu.maidroid.MainActivity
import org.maiwithu.maidroid.R
import org.maiwithu.maidroid.appDependencies
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 承载本机悬浮桌宠的独立前景服务（TYPE_APPLICATION_OVERLAY + specialUse）：
 * - Android 13+（API 33+）：常驻透明全屏窗口 + 内部 `petContainer` 纯 GPU 平移/缩放，
 *   按页面上报热区实时更新 `setTouchableRegion`，彻底避免拖动与滑行调用 `updateViewLayout`；
 * - Android 8–12（API 26–32）：平时采用紧凑小窗 + 裁剪迟滞防抖避免待机呼吸重排；
 *   拖动/滑行/反弹期间切换至固定全屏 `FLAG_NOT_TOUCHABLE` GPU 舞台以 60fps 推进位移与撞墙果冻回弹，
 *   并提前在后台完成小窗一次性落位，彻底屏蔽系统 `WindowManager` 400ms 位移动画导致的粘滞与掉帧。
 */
class PetOverlayService : Service(), PetOverlayHost {

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "maidroid_desktop_pet"
        private const val NOTIFICATION_ID = 1002
        private const val ACTION_STOP = "org.maiwithu.maidroid.action.STOP_DESKTOP_PET"

        // 系统 MoveAnimation 通常持续 ~400ms，轻放或极短滑行时留足缓冲再切回主小窗可完全隐藏系统窗口跳变。
        private const val MOVE_ANIMATION_SETTLE_MS = 420L
        // 前端 skin.js 存在 160ms 状态交叉淡入淡出过渡，需等过渡完全结束再补抓拖动贴图，避免将双影混合帧冻结在舞台上。
        private const val SNAPSHOT_REFRESH_DELAY_MS = 220L
        private const val CLIP_HEADROOM_DP = 16.0
        private const val CLIP_SHRINK_DEADBAND_DP = 24.0
        private const val BASE_PET_WIDTH_DP = 260.0
        private const val BASE_MIN_VISIBLE_HEIGHT_DP = 180.0

        // 读取皮肤动画画布（及当前可见气泡区域）的视口矩形与透明 PNG，
        // 弥补 WebView 软件 draw() 与硬件画布之间的绘制差异，同时防止双影叠加。
        private const val CAPTURE_SKIN_CANVAS_JS =
            "(() => {" +
                "const c = document.getElementById('skin-canvas');" +
                "if (!c || getComputedStyle(c).display === 'none' || !c.width || !c.height) {" +
                    "return { canvasVisible: false };" +
                "}" +
                "const r = c.getBoundingClientRect();" +
                "if (!r || r.width <= 0 || r.height <= 0) {" +
                    "return { canvasVisible: false };" +
                "}" +
                "const sayEl = document.getElementById('say');" +
                "let say = null;" +
                "if (sayEl && sayEl.classList.contains('show')) {" +
                    "const sr = sayEl.getBoundingClientRect();" +
                    "if (sr && sr.width > 0 && sr.height > 0) {" +
                        "say = { left: sr.left, top: sr.top, width: sr.width, height: sr.height };" +
                    "}" +
                "}" +
                "return {" +
                    "canvasVisible: true," +
                    "left: r.left," +
                    "top: r.top," +
                    "width: r.width," +
                    "height: r.height," +
                    "dpr: window.devicePixelRatio || 1," +
                    "say: say," +
                    "png: c.toDataURL('image/png')" +
                "};" +
            "})();"

        private class CapturedCanvasFrame(
            val generation: Int,
            val canvasVisible: Boolean,
            val bitmap: Bitmap?,
            val leftCss: Float,
            val topCss: Float,
            val widthCss: Float,
            val heightCss: Float,
            val dpr: Float,
            val sayRectCss: CssRect? = null
        )

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
    private var localHitRectsCss: List<CssRect> = emptyList()
    private var localHitRects: List<Rect> = emptyList()
    private var topHitCss: Double = 0.0
    private var hasReportedHitRects = false
    private var regionUpdateFrameCounter = 0
    private var requestedScale = DesktopPetScaleSpec.DEFAULT
    private var effectiveScale = DesktopPetScaleSpec.DEFAULT

    // Android 8–12 在拖动与滑行期间启用的固定全屏无触控 GPU 舞台及帧快照。
    private var motionStageFrame: FrameLayout? = null
    private var motionStageParams: WindowManager.LayoutParams? = null
    private var motionStageImageView: ImageView? = null
    private var stageBitmap: Bitmap? = null
    private var usingMotionStage = false
    private var stageEntryPending = false
    private var gestureCaptureGeneration = 0
    private var gestureCanvasFrame: CapturedCanvasFrame? = null
    private var webDraggingStartedUptimeMs = 0L
    private var draggingRefreshScheduled = false
    private val stageDstOverPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OVER)
    }
    private val snapshotExecutor = Executors.newSingleThreadExecutor()
    private var legacyWindowOriginX = 0
    private var legacyWindowOriginY = 0
    private var pendingClipRawTopPx: Int? = null
    private var lastHiddenWindowMoveUptimeMs = 0L

    private var petView: PetWebView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
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
    private val refreshSnapshotRunnable = Runnable { refreshMotionStageSnapshot() }
    private val settleStageRunnable = Runnable { finalizeLegacyMotionStage() }
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
        requestedScale = manager.currentPetScale()
        effectiveScale = computeEffectiveScale()
        val displayHeight = resources.displayMetrics.heightPixels
        val displayWidth = resources.displayMetrics.widthPixels

        fullHeight = min(dp(900.0), displayHeight)
        val baseWidthPx = basePetWidthPx()
        val scaledWidthPx = scaledPetWidthPx()
        val scaledInitHeightPx = min(displayHeight, dpScaled(BASE_MIN_VISIBLE_HEIGHT_DP).coerceAtLeast(1))
        petX = max(0, displayWidth - scaledWidthPx).toFloat()
        petBottom = max(scaledInitHeightPx, displayHeight - dp(80.0)).toFloat()

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
                scaledWidthPx,
                scaledInitHeightPx,
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
            val containerParams = FrameLayout.LayoutParams(scaledWidthPx, scaledFullHeightPx()).apply {
                gravity = Gravity.TOP or Gravity.LEFT
            }
            val pageLayout = FrameLayout.LayoutParams(baseWidthPx, fullHeight)
            boundPet.pivotX = 0f
            boundPet.pivotY = 0f
            boundPet.scaleX = effectiveScale
            boundPet.scaleY = effectiveScale
            container.addView(boundPet, pageLayout)
            frame.addView(container, containerParams)
            petContainer = container
            clipTop = 0
        } else {
            val pageLayout = FrameLayout.LayoutParams(baseWidthPx, fullHeight)
            clipTop = max(0, fullHeight - dp(BASE_MIN_VISIBLE_HEIGHT_DP))
            frame.addView(boundPet, pageLayout)
            syncLegacyPetViewTransform()
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
        stopMotionAndStageNow()
        // 横竖屏切换后按新屏宽重新约束有效缩放比并夹紧坐标，避免窄屏超宽或出界。
        applyEffectiveScaleChange(preserveAnchorCenter = false)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        val alreadyClosing = closing
        closing = true
        stopMotionAndStageNow()
        snapshotExecutor.shutdownNow()
        isRunning = false
        if (screenReceiverRegistered) {
            screenReceiverRegistered = false
            runCatching { unregisterReceiver(screenReceiver) }
        }
        if (!alreadyClosing) {
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
        pageLayout.width = basePetWidthPx()
        pageLayout.height = fullHeight
        pageLayout.topMargin = 0
        pet.layoutParams = pageLayout
        if (supportsTouchRegions) {
            clipTop = 0
            petContainer?.let { container ->
                val containerLayout = container.layoutParams as FrameLayout.LayoutParams
                containerLayout.width = scaledPetWidthPx()
                containerLayout.height = scaledFullHeightPx()
                container.layoutParams = containerLayout
            }
            applyFullScreenContainerTransform(updateRegionNow = true)
            return
        }
        val unscaledVisible = (params.height / max(DesktopPetScaleSpec.MIN_ADAPTIVE, effectiveScale))
            .roundToInt()
            .coerceAtLeast(dp(1.0))
        clipTop = max(0, fullHeight - unscaledVisible)
        syncLegacyPetViewTransform()
    }

    override fun updateTouchableRegion(rects: JSONArray) {
        if (rects.length() > 256) return
        val parsedCssRects = ArrayList<CssRect>(rects.length())
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
                parsedCssRects.add(CssRect(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat()))
            }
        }

        localHitRectsCss = parsedCssRects
        topHitCss = topCss
        hasReportedHitRects = parsedCssRects.isNotEmpty()
        rebuildScaledHitRects()

        if (supportsTouchRegions) {
            applyCurrentTouchableRegion()
            return
        }

        val rawTopPx = max(0, min(fullHeight - dp(1.0), dp(topCss)))
        // 拖动、滑行或等待舞台退场期间暂存最新顶边，不触碰窗口尺寸，停稳后再一次结算。
        if (dragging || moved || usingMotionStage || isMotionActive()) {
            pendingClipRawTopPx = rawTopPx
            return
        }
        applyStableLegacyClipTop(rawTopPx)
    }

    override fun updateScale(scale: Float) {
        val normalized = normalizeDesktopPetScale(scale)
        if (abs(normalized - requestedScale) < 0.0001f) return
        requestedScale = normalized
        if (!::frame.isInitialized) return
        applyEffectiveScaleChange(preserveAnchorCenter = true)
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
        // 新手势开始或中途按住滑行中的桌宠时，立即停掉上一轮滑行与舞台缓冲并锁定当前所见位置。
        interruptActiveMotionForTouch()
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
        if (!supportsTouchRegions && webDraggingStartedUptimeMs == 0L) {
            // 网页在 stage.classList.add('dragging') 后立即回调 drag_move()，以此同步 160ms 交叉淡入淡出过渡起点。
            webDraggingStartedUptimeMs = SystemClock.uptimeMillis()
            maybeScheduleDraggingSnapshotRefresh()
        }
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
            stopMotionAndStageNow()
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
        stopMotionAndStageNow()
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
                    interruptActiveMotionForTouch()
                    downX = rawX
                    downY = rawY
                    velocityTracker.reset(rawX, rawY, event.eventTime)
                    if (!supportsTouchRegions) {
                        gestureCaptureGeneration += 1
                        stageEntryPending = false
                        webDraggingStartedUptimeMs = 0L
                        draggingRefreshScheduled = false
                        clearGestureCanvasFrame()
                        requestSkinCanvasCapture(gestureCaptureGeneration, isRefresh = false)
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    velocityTracker.addSample(rawX, rawY, event.eventTime)
                    if (dragging) {
                        advanceDragPosition()
                    }
                }

                MotionEvent.ACTION_UP -> {
                    velocityTracker.addSample(rawX, rawY, event.eventTime)
                    val wasMoved = dragging && moved
                    webDraggingStartedUptimeMs = 0L
                    draggingRefreshScheduled = false
                    if (wasMoved) {
                        val velocity = velocityTracker.computeVelocity(event.eventTime, density)
                        finishDragWithVelocity(velocity.vx, velocity.vy)
                    } else {
                        // 即使 JS 侧先一步触发 dragEnd 导致 dragging 置 false，若已进入舞台仍须推进停稳退场。
                        stageEntryPending = false
                        if (usingMotionStage) {
                            commitLegacyWindowCoordinates(petX.roundToInt(), petBottom.roundToInt())
                            applyLegacyMotionStageTransform()
                            scheduleLegacyStageSettleAfterHiddenMove()
                        } else if (!isMotionActive()) {
                            clearGestureCanvasFrame()
                        }
                    }
                    moved = false
                    dragging = false
                    pointerActive = false
                    velocityTracker.clear()
                    manager.onOverlayInputEditingCheck()
                }

                MotionEvent.ACTION_CANCEL -> {
                    stageEntryPending = false
                    webDraggingStartedUptimeMs = 0L
                    draggingRefreshScheduled = false
                    moved = false
                    dragging = false
                    pointerActive = false
                    velocityTracker.clear()
                    pendingDragEndGestureId?.let { activeId ->
                        pendingDragEndGestureId = null
                        manager.sendDragEnded(activeId)
                    }
                    if (usingMotionStage) {
                        commitLegacyWindowCoordinates(petX.roundToInt(), petBottom.roundToInt())
                        stopMotionAndStageNow()
                    } else if (!isMotionActive()) {
                        clearGestureCanvasFrame()
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
                    windowWidth = scaledPetWidthPx(),
                    windowBottom = dragStartBottom.roundToInt(),
                    pointerDownX = downX,
                    pointerDownY = downY,
                    density = density * effectiveScale
                )
            )
            if (!supportsTouchRegions) {
                tryEnterLegacyMotionStage()
            }
        }
        if (moved) {
            val bounds = currentMotionBounds()
            petX = clampToAxis(dragStartX + (rawX - downX), bounds.minX, bounds.maxX)
            petBottom = clampToAxis(dragStartBottom + (rawY - downY), bounds.minBottom, bounds.maxBottom)
            if (supportsTouchRegions) {
                applyFullScreenContainerTransform(updateRegionNow = false)
            } else if (usingMotionStage) {
                applyLegacyMotionStageTransform()
            } else {
                params.x = petX.roundToInt()
                place(forceLayout = false)
            }
        }
    }

    private fun finishDragWithVelocity(vx: Float, vy: Float) {
        if (closing || !::frame.isInitialized || frame.visibility != View.VISIBLE) {
            stopMotionAndStageNow()
            return
        }
        mainHandler.removeCallbacks(refreshSnapshotRunnable)
        val bounds = currentMotionBounds()
        bounceSquash.reset()

        val flingStarted = flingEngine.start(
            startX = petX,
            startBottom = petBottom,
            initialVx = vx,
            initialVy = vy,
            density = density,
            bounds = bounds
        )
        if (!flingStarted) {
            stageEntryPending = false
        }

        if (!supportsTouchRegions && usingMotionStage) {
            if (flingStarted) {
                // 确定性物理积分允许第 0 毫秒预知最终落点：趁 GPU 舞台在屏幕上丝滑飞行反弹时，
                // 让隐身中的主小窗一次性静默落位到终点，等待 400ms 系统 MoveAnimation 在后台走完。
                val resting = flingEngine.predictRestingPosition(
                    startX = petX,
                    startBottom = petBottom,
                    initialVx = vx,
                    initialVy = vy,
                    density = density,
                    bounds = bounds
                )
                commitLegacyWindowCoordinates(resting.x.roundToInt(), resting.bottom.roundToInt())
                lastMotionFrameNanos = 0L
                scheduleNextMotionFrame()
            } else {
                // 慢速轻放同样在舞台掩护下迁移隐身主窗，待系统 400ms 位移动画跑完再恢复主窗可见。
                commitLegacyWindowCoordinates(petX.roundToInt(), petBottom.roundToInt())
                applyLegacyMotionStageTransform()
                scheduleLegacyStageSettleAfterHiddenMove()
            }
            return
        }

        if (flingStarted) {
            lastMotionFrameNanos = 0L
            scheduleNextMotionFrame()
        } else if (supportsTouchRegions) {
            applyFullScreenContainerTransform(updateRegionNow = true)
        } else {
            params.x = petX.roundToInt()
            place(forceLayout = false)
            flushPendingClipTop()
        }
    }

    private fun scheduleNextMotionFrame() {
        if (motionFrameScheduled || closing || !::choreographer.isInitialized) return
        motionFrameScheduled = true
        choreographer.postFrameCallback(motionFrameCallback)
    }

    private fun onMotionFrame(frameTimeNanos: Long) {
        motionFrameScheduled = false
        if (!isMotionActive() || closing || !::frame.isInitialized || frame.visibility != View.VISIBLE) {
            onMotionSequenceSettled()
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
        } else if (usingMotionStage) {
            applyLegacyMotionStageTransform()
        } else {
            params.x = petX.roundToInt()
            place(forceLayout = false)
        }

        if (flingRunning || squashActive) {
            scheduleNextMotionFrame()
        } else {
            onMotionSequenceSettled()
        }
    }

    private fun onMotionSequenceSettled() {
        flingEngine.stop()
        bounceSquash.reset()
        lastMotionFrameNanos = 0L
        if (supportsTouchRegions) {
            applyFullScreenContainerTransform(updateRegionNow = true)
        } else if (usingMotionStage) {
            scheduleLegacyStageSettleAfterHiddenMove()
        } else {
            params.x = petX.roundToInt()
            place(forceLayout = false)
            flushPendingClipTop()
        }
    }

    private fun isMotionActive(): Boolean = flingEngine.isRunning || bounceSquash.isActive

    private fun interruptActiveMotionForTouch() {
        mainHandler.removeCallbacks(refreshSnapshotRunnable)
        mainHandler.removeCallbacks(settleStageRunnable)
        stageEntryPending = false
        webDraggingStartedUptimeMs = 0L
        draggingRefreshScheduled = false
        val wasStageVisible = usingMotionStage
        val activeFlingOrSquash = isMotionActive()
        flingEngine.stop()
        bounceSquash.reset()
        lastMotionFrameNanos = 0L
        if (motionFrameScheduled && ::choreographer.isInitialized) {
            choreographer.removeFrameCallback(motionFrameCallback)
            motionFrameScheduled = false
        }
        if (supportsTouchRegions) {
            applyFullScreenContainerTransform(updateRegionNow = true)
        } else if (wasStageVisible) {
            // 若在 420ms 退场缓冲期内再次按下，立即校准并显现主小窗，保证原地直接可拖。
            if (activeFlingOrSquash) {
                commitLegacyWindowCoordinates(petX.roundToInt(), petBottom.roundToInt())
            }
            finalizeLegacyMotionStage()
        }
    }

    private fun stopMotionAndStageNow() {
        mainHandler.removeCallbacks(refreshSnapshotRunnable)
        mainHandler.removeCallbacks(settleStageRunnable)
        stageEntryPending = false
        webDraggingStartedUptimeMs = 0L
        draggingRefreshScheduled = false
        flingEngine.stop()
        bounceSquash.reset()
        lastMotionFrameNanos = 0L
        if (motionFrameScheduled && ::choreographer.isInitialized) {
            choreographer.removeFrameCallback(motionFrameCallback)
            motionFrameScheduled = false
        }
        if (supportsTouchRegions) {
            applyFullScreenContainerTransform(updateRegionNow = false)
        } else {
            finalizeLegacyMotionStage()
        }
    }

    private fun requestSkinCanvasCapture(generation: Int, isRefresh: Boolean) {
        if (supportsTouchRegions || closing) return
        val pet = petView ?: return
        pet.evaluateJavascript(CAPTURE_SKIN_CANVAS_JS) { rawResult ->
            if (closing || generation != gestureCaptureGeneration) return@evaluateJavascript
            val payload = parseCapturedCanvasPayload(rawResult)
            if (payload == null) {
                onSkinCanvasCaptureFailed(generation, isRefresh)
                return@evaluateJavascript
            }
            if (!payload.canvasVisible) {
                onSkinCanvasCaptureReady(
                    captured = CapturedCanvasFrame(
                        generation = generation,
                        canvasVisible = false,
                        bitmap = null,
                        leftCss = 0f,
                        topCss = 0f,
                        widthCss = 0f,
                        heightCss = 0f,
                        dpr = density
                    ),
                    isRefresh = isRefresh
                )
                return@evaluateJavascript
            }

            val encoded = payload.pngBase64
            if (encoded == null) {
                onSkinCanvasCaptureFailed(generation, isRefresh)
                return@evaluateJavascript
            }
            val submitted = runCatching {
                snapshotExecutor.execute {
                    val decoded = runCatching {
                        val bytes = Base64.decode(encoded, Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }.getOrNull()
                    if (decoded == null) {
                        mainHandler.post { onSkinCanvasCaptureFailed(generation, isRefresh) }
                        return@execute
                    }
                    mainHandler.post {
                        if (closing || generation != gestureCaptureGeneration) {
                            decoded.recycle()
                            return@post
                        }
                        onSkinCanvasCaptureReady(
                            captured = CapturedCanvasFrame(
                                generation = generation,
                                canvasVisible = true,
                                bitmap = decoded,
                                leftCss = payload.leftCss,
                                topCss = payload.topCss,
                                widthCss = payload.widthCss,
                                heightCss = payload.heightCss,
                                dpr = payload.dpr,
                                sayRectCss = payload.sayRectCss
                            ),
                            isRefresh = isRefresh
                        )
                    }
                }
            }
            if (submitted.isFailure) {
                onSkinCanvasCaptureFailed(generation, isRefresh)
            }
        }
    }

    private fun onSkinCanvasCaptureFailed(generation: Int, isRefresh: Boolean) {
        if (closing || generation != gestureCaptureGeneration) return
        if (!isRefresh) {
            // 解码或脚本返回异常时及时退出挂起态，防止当次手势卡在待进入舞台状态。
            stageEntryPending = false
        }
    }

    private fun onSkinCanvasCaptureReady(captured: CapturedCanvasFrame, isRefresh: Boolean) {
        if (closing || captured.generation != gestureCaptureGeneration) {
            captured.bitmap?.recycle()
            return
        }
        val previous = gestureCanvasFrame
        gestureCanvasFrame = captured
        if (previous != null && previous.bitmap !== captured.bitmap) {
            previous.bitmap?.recycle()
        }

        if (isRefresh && usingMotionStage) {
            val imageView = motionStageImageView ?: return
            val refreshedBitmap = composeLegacyStageBitmap(captured) ?: return
            val oldStageBitmap = stageBitmap
            stageBitmap = refreshedBitmap
            imageView.setImageBitmap(refreshedBitmap)
            if (oldStageBitmap != null && oldStageBitmap !== refreshedBitmap) {
                oldStageBitmap.recycle()
            }
            return
        }

        if (!isRefresh && stageEntryPending && !usingMotionStage && (moved || isMotionActive())) {
            stageEntryPending = false
            tryEnterLegacyMotionStage()
            // 极快甩手在抓帧完成前已抬手进入滑行时，此刻刚隐身小窗需按剩余滑行曲线提前送往终点。
            if (usingMotionStage && !pointerActive && flingEngine.isRunning) {
                val resting = flingEngine.predictRestingPosition(
                    startX = petX,
                    startBottom = petBottom,
                    initialVx = flingEngine.vx,
                    initialVy = flingEngine.vy,
                    density = density,
                    bounds = currentMotionBounds()
                )
                commitLegacyWindowCoordinates(resting.x.roundToInt(), resting.bottom.roundToInt())
            }
        }
    }

    private fun tryEnterLegacyMotionStage() {
        if (usingMotionStage || !::frame.isInitialized) return
        val canvasFrame = gestureCanvasFrame
        if (canvasFrame == null || canvasFrame.generation != gestureCaptureGeneration) {
            // 抓帧解码尚未回来前绝不隐藏真身，先保持原生控件移动并在就绪瞬间无缝切入 GPU 舞台。
            stageEntryPending = true
            return
        }
        stageEntryPending = false
        val snapshot = composeLegacyStageBitmap(canvasFrame) ?: return

        val stageRoot = motionStageFrame ?: FrameLayout(this).apply {
            clipChildren = false
        }.also { motionStageFrame = it }
        val imageView = motionStageImageView ?: ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
        }.also {
            motionStageImageView = it
            stageRoot.addView(it, FrameLayout.LayoutParams(snapshot.width, snapshot.height))
        }

        val previousStageBitmap = stageBitmap
        stageBitmap = snapshot
        imageView.setImageBitmap(snapshot)
        if (previousStageBitmap != null && previousStageBitmap !== snapshot) {
            previousStageBitmap.recycle()
        }
        val imageParams = imageView.layoutParams as FrameLayout.LayoutParams
        imageParams.width = snapshot.width
        imageParams.height = snapshot.height
        imageParams.gravity = Gravity.TOP or Gravity.LEFT
        imageView.layoutParams = imageParams

        val stageParams = motionStageParams ?: WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = 0
            y = 0
            applyOverlayWindowChrome(this)
        }.also { motionStageParams = it }

        if (!stageRoot.isAttachedToWindow) {
            windowManager.addView(stageRoot, stageParams)
        }
        // 趁隐身小窗尚未触发任何移动之前先锁存其系统安全区原点偏移，防止后续 relayout 异步窗口延迟引起位移跳变。
        val windowLoc = IntArray(2)
        frame.getLocationOnScreen(windowLoc)
        legacyWindowOriginX = windowLoc[0] - params.x
        legacyWindowOriginY = windowLoc[1] - params.y

        stageRoot.visibility = View.VISIBLE
        usingMotionStage = true
        applyLegacyMotionStageTransform()
        stageRoot.post { if (usingMotionStage) applyLegacyMotionStageTransform() }
        petView?.alpha = 0f

        // 前端在拖动启动并收到 dragMove() 后，skin.js 会播放 160ms 的 idle->dragging 交叉淡入淡出；
        // 等到该过渡完全收敛（220ms）再补抓稳定拖动贴图，避免在过渡正中央截下两帧叠加的双影。
        maybeScheduleDraggingSnapshotRefresh()
    }

    private fun maybeScheduleDraggingSnapshotRefresh() {
        if (supportsTouchRegions || !usingMotionStage || !moved || draggingRefreshScheduled) return
        val delayMs = computeDraggingSnapshotRefreshDelayMs(
            draggingStartedUptimeMs = webDraggingStartedUptimeMs,
            nowUptimeMs = SystemClock.uptimeMillis(),
            settleDelayMs = SNAPSHOT_REFRESH_DELAY_MS
        ) ?: return
        draggingRefreshScheduled = true
        mainHandler.removeCallbacks(refreshSnapshotRunnable)
        if (delayMs == 0L) {
            mainHandler.post(refreshSnapshotRunnable)
        } else {
            mainHandler.postDelayed(refreshSnapshotRunnable, delayMs)
        }
    }

    private fun refreshMotionStageSnapshot() {
        if (!usingMotionStage || !moved) return
        requestSkinCanvasCapture(gestureCaptureGeneration, isRefresh = true)
    }

    private fun composeLegacyStageBitmap(canvasFrame: CapturedCanvasFrame): Bitmap? {
        val pet = petView ?: return null
        if (canvasFrame.canvasVisible && canvasFrame.bitmap == null) return null
        val w = frame.width
        val h = frame.height
        if (w <= 0 || h <= 0) return null
        val snapshot = runCatching {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            // 以实际缩放后画布与窗口底边的真实差值对齐，确保在极窄屏/高度钳位下仍与实体脚底像素吻合。
            val effectiveClipOffsetPx = fullHeight * effectiveScale - h.toFloat()
            canvas.save()
            canvas.translate(0f, -effectiveClipOffsetPx)
            canvas.scale(effectiveScale, effectiveScale)
            pet.draw(canvas)
            canvas.restore()

            val skinBitmap = canvasFrame.bitmap
            if (canvasFrame.canvasVisible && skinBitmap != null && !skinBitmap.isRecycled) {
                val scale = if (canvasFrame.dpr.isFinite() && canvasFrame.dpr > 0f) {
                    canvasFrame.dpr
                } else {
                    density
                }
                val combinedScale = scale * effectiveScale
                val dst = RectF(
                    canvasFrame.leftCss * combinedScale,
                    canvasFrame.topCss * combinedScale - effectiveClipOffsetPx,
                    (canvasFrame.leftCss + canvasFrame.widthCss) * combinedScale,
                    (canvasFrame.topCss + canvasFrame.heightCss) * combinedScale - effectiveClipOffsetPx
                )
                val sayRect = canvasFrame.sayRectCss
                val sayDst = if (sayRect != null) {
                    RectF(
                        sayRect.left * combinedScale,
                        sayRect.top * combinedScale - effectiveClipOffsetPx,
                        (sayRect.left + sayRect.width) * combinedScale,
                        (sayRect.top + sayRect.height) * combinedScale - effectiveClipOffsetPx
                    )
                } else {
                    null
                }
                // Chromium 多次回读后可能将 <canvas> 降级为软件绘制：先清空画布区域（若发言气泡可见则抠出气泡防裁切），
                // 再以 DST_OVER 将抓取的皮肤帧铺在气泡后方，彻底避免 pet.draw() 与 skinBitmap 叠加出双影。
                canvas.save()
                canvas.clipRect(dst)
                if (sayDst != null) {
                    canvas.clipOutRect(sayDst)
                }
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                canvas.restore()
                canvas.drawBitmap(skinBitmap, null, dst, stageDstOverPaint)
            }
            bitmap
        }.getOrNull() ?: return null

        if (!hasVisiblePixels(snapshot)) {
            snapshot.recycle()
            return null
        }
        return snapshot
    }

    private fun clearGestureCanvasFrame() {
        val current = gestureCanvasFrame
        gestureCanvasFrame = null
        current?.bitmap?.recycle()
    }

    private fun hasVisiblePixels(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return false
        val step = max(1, min(width, height) / 36)
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                if ((bitmap.getPixel(x, y) ushr 24) > 8) {
                    return true
                }
                x += step
            }
            y += step
        }
        return false
    }

    private fun commitLegacyWindowCoordinates(targetX: Int, targetBottom: Int) {
        val bounds = currentMotionBounds()
        val clampedX = clampToAxis(targetX.toFloat(), bounds.minX, bounds.maxX).roundToInt()
        val clampedBottom = clampToAxis(targetBottom.toFloat(), bounds.minBottom, bounds.maxBottom).roundToInt()
        val nextY = max(0, min(clampedBottom, keyboardTop) - params.height)
        val changed = params.x != clampedX || params.y != nextY
        params.x = clampedX
        params.y = nextY
        if (::frame.isInitialized && frame.isAttachedToWindow) {
            windowManager.updateViewLayout(frame, params)
        }
        if (changed) {
            lastHiddenWindowMoveUptimeMs = SystemClock.uptimeMillis()
        }
    }

    private fun scheduleLegacyStageSettleAfterHiddenMove() {
        mainHandler.removeCallbacks(settleStageRunnable)
        val elapsed = SystemClock.uptimeMillis() - lastHiddenWindowMoveUptimeMs
        val remaining = (MOVE_ANIMATION_SETTLE_MS - elapsed).coerceAtLeast(0L)
        if (remaining == 0L) {
            finalizeLegacyMotionStage()
        } else {
            mainHandler.postDelayed(settleStageRunnable, remaining)
        }
    }

    private fun finalizeLegacyMotionStage() {
        mainHandler.removeCallbacks(refreshSnapshotRunnable)
        mainHandler.removeCallbacks(settleStageRunnable)
        stageEntryPending = false
        webDraggingStartedUptimeMs = 0L
        draggingRefreshScheduled = false
        petView?.alpha = 1f
        usingMotionStage = false
        val stageRoot = motionStageFrame
        if (stageRoot != null && stageRoot.isAttachedToWindow) {
            runCatching { windowManager.removeView(stageRoot) }
        }
        motionStageImageView?.setImageDrawable(null)
        stageBitmap?.recycle()
        stageBitmap = null
        clearGestureCanvasFrame()
        flushPendingClipTop()
    }

    private fun applyLegacyMotionStageTransform() {
        val imageView = motionStageImageView ?: return
        val stageRoot = motionStageFrame ?: return
        val stageLoc = IntArray(2)
        val (stageOriginX, stageOriginY) = if (stageRoot.isAttachedToWindow && stageRoot.width > 0) {
            stageRoot.getLocationOnScreen(stageLoc)
            stageLoc[0] to stageLoc[1]
        } else {
            0 to 0
        }

        val currentTopInWindow = min(petBottom, keyboardTop.toFloat()) - params.height.toFloat()
        val clampedTopInWindow = max(0f, currentTopInWindow)
        val tx = petX + (legacyWindowOriginX - stageOriginX)
        val ty = clampedTopInWindow + (legacyWindowOriginY - stageOriginY)
        val w = (imageView.layoutParams?.width ?: params.width).toFloat()
        val h = (imageView.layoutParams?.height ?: params.height).toFloat()
        applySquashTransform(
            target = imageView,
            translationX = tx,
            translationY = ty,
            width = w,
            visibleTop = 0f,
            visibleHeight = h
        )
    }

    private fun applyFullScreenContainerTransform(updateRegionNow: Boolean) {
        val container = petContainer ?: return
        val scaledFullHeight = scaledFullHeightPx()
        val effectiveBottom = min(petBottom, keyboardTop.toFloat())
        val containerTop = effectiveBottom - scaledFullHeight.toFloat()
        val minVisibleScaled = dpScaled(BASE_MIN_VISIBLE_HEIGHT_DP)
        val topVisible = if (hasReportedHitRects) {
            dpScaled(topHitCss).coerceIn(0, max(0, scaledFullHeight - minVisibleScaled)).toFloat()
        } else {
            max(0f, scaledFullHeight - minVisibleScaled.toFloat())
        }
        val visibleHeight = (scaledFullHeight.toFloat() - topVisible).coerceAtLeast(1f)
        applySquashTransform(
            target = container,
            translationX = petX,
            translationY = containerTop,
            width = scaledPetWidthPx().toFloat(),
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
        val offsetY = (min(petBottom, keyboardTop.toFloat()) - scaledFullHeightPx()).roundToInt()
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

    private fun applyEffectiveScaleChange(preserveAnchorCenter: Boolean) {
        stopMotionAndStageNow()
        val oldWidth = scaledPetWidthPx()
        effectiveScale = computeEffectiveScale()
        val newWidth = scaledPetWidthPx()
        if (preserveAnchorCenter) {
            // 调节桌宠大小时保持水平中心与脚底原地缩放，防止左右跳偏。
            petX += (oldWidth - newWidth) / 2f
        }
        val pet = petView
        if (supportsTouchRegions) {
            pet?.let {
                it.pivotX = 0f
                it.pivotY = 0f
                it.scaleX = effectiveScale
                it.scaleY = effectiveScale
            }
            petContainer?.let { container ->
                val containerLayout = container.layoutParams as FrameLayout.LayoutParams
                containerLayout.width = newWidth
                containerLayout.height = scaledFullHeightPx()
                container.layoutParams = containerLayout
            }
            rebuildScaledHitRects()
            clampLogicalCoordinates(currentMotionBounds())
            applyFullScreenContainerTransform(updateRegionNow = true)
        } else {
            params.width = newWidth
            params.height = computeLegacyVisibleHeightPx()
            pet?.let {
                val layout = it.layoutParams as FrameLayout.LayoutParams
                layout.topMargin = 0
                it.layoutParams = layout
            }
            syncLegacyPetViewTransform()
            place(forceLayout = true)
        }
    }

    private fun rebuildScaledHitRects() {
        localHitRects = localHitRectsCss.map { css ->
            Rect(
                dpScaled(css.left.toDouble()),
                dpScaled(css.top.toDouble()),
                dpScaled((css.left + css.width).toDouble()),
                dpScaled((css.top + css.height).toDouble())
            )
        }
    }

    private fun syncLegacyPetViewTransform() {
        val pet = petView ?: return
        // Android 8–12 将 WebView 缩放基准定在左上角并平移底边对齐，确保触控坐标与画布可见区域一致。
        pet.pivotX = 0f
        pet.pivotY = 0f
        pet.scaleX = effectiveScale
        pet.scaleY = effectiveScale
        pet.translationY = params.height - fullHeight * effectiveScale
    }

    private fun computeLegacyVisibleHeightPx(): Int {
        val unscaledVisible = (fullHeight - clipTop).coerceAtLeast(dp(1.0))
        return min(currentDisplayHeightPx(), (unscaledVisible * effectiveScale).roundToInt().coerceAtLeast(1))
    }

    private fun computeEffectiveScale(): Float =
        resolveEffectivePetScale(requestedScale, currentDisplayWidthPx(), basePetWidthPx())

    private fun basePetWidthPx(): Int = dp(BASE_PET_WIDTH_DP)

    private fun scaledPetWidthPx(): Int = (basePetWidthPx() * effectiveScale).roundToInt().coerceAtLeast(1)

    private fun scaledFullHeightPx(): Int = (fullHeight * effectiveScale).roundToInt().coerceAtLeast(1)

    private fun dpScaled(value: Double): Int = (value * density * effectiveScale).roundToInt()

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
        params.width = scaledPetWidthPx()
        params.height = computeLegacyVisibleHeightPx()
        val layout = pet.layoutParams as FrameLayout.LayoutParams
        layout.topMargin = 0
        pet.layoutParams = layout
        syncLegacyPetViewTransform()
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
            return max(dpScaled(BASE_MIN_VISIBLE_HEIGHT_DP), currentDisplayHeightPx() - overlapPx)
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
        val petWidthPx = scaledPetWidthPx()
        val maxX = max(0, displayWidth - petWidthPx)
        val effectiveMaxBottom = min(displayHeight, keyboardTop)
        val rawMinBottom = if (supportsTouchRegions) {
            val scaledHeight = scaledFullHeightPx()
            val minVisibleScaled = dpScaled(BASE_MIN_VISIBLE_HEIGHT_DP)
            if (hasReportedHitRects) {
                max(minVisibleScaled, scaledHeight - dpScaled(topHitCss).coerceAtLeast(0))
            } else {
                minVisibleScaled
            }
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
