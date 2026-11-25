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

    private var petView: PetWebView? = null
    private var density = 1f
    private var rawX = 0f
    private var rawY = 0f
    private var downX = 0f
    private var downY = 0f
    private var fullHeight = 0
    private var clipTop = 0
    private var bottom = 0
    private var dragX = 0
    private var dragBottom = 0
    private var keyboardTop = Int.MAX_VALUE
    private var dragging = false
    private var moved = false
    private var pointerActive = false
    private var closing = false
    private var gestureId = ""
    private val velocityTracker = PetVelocityTracker()
    private val flingEngine = PetFlingEngine()
    private lateinit var choreographer: Choreographer
    private var flingFrameScheduled = false
    private var lastFlingFrameNanos = 0L
    private val flingFrameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        onFlingFrame(frameTimeNanos)
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
        bottom = max(dp(180.0), displayHeight - dp(80.0))

        frame = FrameLayout(this).apply { clipChildren = true }
        params = WindowManager.LayoutParams(
            dp(260.0),
            dp(180.0),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )

        if (supportsTouchRegions) {
            params.height = fullHeight
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val inputManager = getSystemService(InputManager::class.java)
                params.alpha = inputManager.maximumObscuringOpacityForTouch
            }
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }

        // 前端上报坐标按屏幕左上角基准对齐，必须显式使用 Gravity.LEFT 避免 RTL 镜像。
        params.gravity = Gravity.TOP or Gravity.LEFT
        params.x = max(0, displayWidth - params.width)
        params.y = if (supportsTouchRegions) bottom - params.height else max(0, bottom - params.height)
        @Suppress("DEPRECATION")
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE

        val boundPet = manager.bindOverlayService(this, this)
        if (boundPet == null) {
            stopSelf()
            return
        }
        petView = boundPet

        val pageLayout = FrameLayout.LayoutParams(dp(260.0), fullHeight)
        clipTop = fullHeight - params.height
        pageLayout.topMargin = -clipTop
        frame.addView(boundPet, pageLayout)

        setupImeAvoidance()
        setupTouchAndKeyListeners(boundPet)

        windowManager.addView(frame, params)
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
        if (::frame.isInitialized) place()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopFling()
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
        if (supportsTouchRegions) params.height = fullHeight
        val layout = pet.layoutParams as FrameLayout.LayoutParams
        layout.height = fullHeight
        clipTop = max(0, fullHeight - params.height)
        layout.topMargin = -clipTop
        pet.layoutParams = layout
        if (supportsTouchRegions) place()
    }

    override fun updateTouchableRegion(rects: JSONArray) {
        if (rects.length() > 256) return
        val touchable = Region()
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
                touchable.op(dp(x), dp(y), dp(x + w), dp(y + h), Region.Op.UNION)
            }
        }

        if (supportsTouchRegions && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            frame.rootSurfaceControl?.setTouchableRegion(touchable)
            return
        }

        val nextClipTop = max(0, min(fullHeight - dp(1.0), dp(topCss)))
        if (nextClipTop == clipTop) return
        val pet = petView ?: return
        clipTop = nextClipTop
        params.height = fullHeight - clipTop
        val layout = pet.layoutParams as FrameLayout.LayoutParams
        layout.topMargin = -clipTop
        pet.layoutParams = layout
        place()
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
        if (dragging && moved) {
            manager.sendDragEnded(gestureId)
        }
        // 新手势开始或中途按住滑行中的桌宠时，立即停掉上一轮惯性动画。
        stopFling()
        if (!pointerActive) {
            dragging = false
            moved = false
            return
        }
        dragging = true
        moved = false
        dragX = params.x
        dragBottom = bottom
        gestureId = UUID.randomUUID().toString().replace("-", "")
    }

    override fun dragMove() {
        if (!dragging || !pointerActive || flingEngine.isRunning) return
        advanceDragPosition()
    }

    override fun dragEnd() {
        if (dragging && moved) {
            manager.sendDragEnded(gestureId)
        }
        dragging = false
    }

    override fun setOverlayVisible(visible: Boolean) {
        if (!visible) {
            stopFling()
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
        stopFling()
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
                    stopFling()
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
                    // dragEnd() 由前端经 JS 异步送达，必须在抬手瞬间就结算速度并启动滑行，不能在 dragEnd 里停滑。
                    if (dragging && moved) {
                        val velocity = velocityTracker.computeVelocity(event.eventTime, density)
                        startFling(velocity.vx, velocity.vy)
                    }
                    pointerActive = false
                    velocityTracker.clear()
                    manager.onOverlayInputEditingCheck()
                }

                MotionEvent.ACTION_CANCEL -> {
                    pointerActive = false
                    velocityTracker.clear()
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
            manager.sendDragStarted(
                DesktopPetProtocol.computeDragStartPayload(
                    gestureId = gestureId,
                    windowX = dragX,
                    windowWidth = params.width,
                    windowBottom = dragBottom,
                    pointerDownX = downX,
                    pointerDownY = downY,
                    density = density
                )
            )
        }
        if (moved) {
            params.x = dragX + (rawX - downX).roundToInt()
            bottom = dragBottom + (rawY - downY).roundToInt()
            place(forceLayout = false)
        }
    }

    private fun startFling(vx: Float, vy: Float) {
        if (closing || !::frame.isInitialized || frame.visibility != View.VISIBLE) return
        val started = flingEngine.start(
            startX = params.x.toFloat(),
            startBottom = bottom.toFloat(),
            initialVx = vx,
            initialVy = vy,
            density = density,
            bounds = currentMotionBounds()
        )
        if (started) {
            lastFlingFrameNanos = 0L
            scheduleNextFlingFrame()
        }
    }

    private fun stopFling() {
        flingEngine.stop()
        lastFlingFrameNanos = 0L
        if (flingFrameScheduled && ::choreographer.isInitialized) {
            choreographer.removeFrameCallback(flingFrameCallback)
            flingFrameScheduled = false
        }
    }

    private fun scheduleNextFlingFrame() {
        if (flingFrameScheduled || closing || !::choreographer.isInitialized) return
        flingFrameScheduled = true
        choreographer.postFrameCallback(flingFrameCallback)
    }

    private fun onFlingFrame(frameTimeNanos: Long) {
        flingFrameScheduled = false
        if (!flingEngine.isRunning || closing || !::frame.isInitialized || frame.visibility != View.VISIBLE) {
            stopFling()
            return
        }
        val dtSeconds = if (lastFlingFrameNanos == 0L) {
            1f / 60f
        } else {
            ((frameTimeNanos - lastFlingFrameNanos) / 1_000_000_000f).coerceAtLeast(0f)
        }
        lastFlingFrameNanos = frameTimeNanos

        val keepGoing = flingEngine.step(dtSeconds, density, currentMotionBounds())
        params.x = flingEngine.x.roundToInt()
        bottom = flingEngine.bottom.roundToInt()
        place(forceLayout = false)

        if (keepGoing) {
            scheduleNextFlingFrame()
        } else {
            stopFling()
        }
    }

    private fun setupImeAvoidance() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            frame.setOnApplyWindowInsetsListener { _, insets ->
                val visible = insets.isVisible(WindowInsets.Type.ime())
                val overlap = insets.getInsets(WindowInsets.Type.ime()).bottom
                val nextTop = when {
                    !visible -> Int.MAX_VALUE
                    overlap > 0 -> params.y + params.height - overlap
                    else -> keyboardTop
                }
                if (keyboardTop != nextTop) {
                    keyboardTop = nextTop
                    place()
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
                    place()
                }
            }
        }
    }

    private fun currentMotionBounds(): PetMotionBounds {
        val displayWidth = resources.displayMetrics.widthPixels
        val displayHeight = resources.displayMetrics.heightPixels
        val maxX = max(0, displayWidth - params.width)
        val effectiveMaxBottom = min(displayHeight, keyboardTop)
        val rawMinBottom = if (supportsTouchRegions) dp(180.0) else params.height
        val effectiveMinBottom = min(rawMinBottom, effectiveMaxBottom)
        return PetMotionBounds(
            minX = 0f,
            maxX = maxX.toFloat(),
            minBottom = effectiveMinBottom.toFloat(),
            maxBottom = effectiveMaxBottom.toFloat()
        )
    }

    private fun place(forceLayout: Boolean = true) {
        val bounds = currentMotionBounds()
        val prevX = params.x
        val prevY = params.y
        params.x = max(bounds.minX.roundToInt(), min(params.x, bounds.maxX.roundToInt()))
        bottom = max(bounds.minBottom.roundToInt(), min(bottom, bounds.maxBottom.roundToInt()))
        params.y = min(bottom, keyboardTop) - params.height
        if (!supportsTouchRegions) {
            params.y = max(0, params.y)
        }
        if (::frame.isInitialized && frame.isAttachedToWindow &&
            (forceLayout || params.x != prevX || params.y != prevY)
        ) {
            windowManager.updateViewLayout(frame, params)
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
