package com.wuming.screentrans

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * 悬浮球：前台服务 + 系统级 overlay。
 *
 * 交互约定：
 *  - 点按  → 进入框选模式（M2）
 *  - 长按  → 翻译剪贴板（M4）
 *  - 拖动  → 移动，松手吸边
 */
class FloatingBallService : Service() {

    companion object {
        private const val TAG = "BallService"

        const val ACTION_START = "com.wuming.screentrans.START"
        const val ACTION_STOP = "com.wuming.screentrans.STOP"

        private const val LONG_PRESS_MS = 500L
        private const val TAP_SLOP_DP = 8

        @Volatile
        var isRunning = false
            private set

        /** 当前存活的实例，供框选/卡片等组件回调 */
        @Volatile
        var instance: FloatingBallService? = null
            private set

        fun start(ctx: Context) {
            val i = Intent(ctx, FloatingBallService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, FloatingBallService::class.java).setAction(ACTION_STOP))
        }

        fun canDrawOverlay(ctx: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx)
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager

    private var ball: View? = null
    private var lp: WindowManager.LayoutParams? = null

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var moved = false
    private var longPressFired = false

    private val longPress = Runnable {
        longPressFired = true
        onLongPress()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(WindowManager::class.java)
        Notify.ensureChannels(this)

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                Notify.ID_BALL,
                Notify.ballNotification(this),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(Notify.ID_BALL, Notify.ballNotification(this))
        }

        if (!canDrawOverlay(this)) {
            Log.w(TAG, "没有悬浮窗权限，球无法显示")
            Toast.makeText(this, "缺少悬浮窗权限，请回 App 授权", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }

        addBall()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        instance = null
        ball?.let { runCatching { wm.removeView(it) } }
        ball = null
        super.onDestroy()
    }

    // ---------------- 悬浮球 ----------------

    private fun ballSizePx(): Int = Ui.dp(this, 52f)

    private fun addBall() {
        if (ball != null) return
        val size = ballSizePx()

        val v = TextView(this).apply {
            text = "译"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = Ui.rounded(0xE62F6BFF.toInt(), size / 2, 0x66FFFFFF, Ui.dp(this@FloatingBallService, 1f))
            elevation = Ui.dp(this@FloatingBallService, 6f).toFloat()
            alpha = 0.94f
        }

        val screen = screenSize()
        val savedX = Prefs.ballX(this)
        val savedY = Prefs.ballY(this)
        val p = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                // 以整屏为坐标基准，跟框选遮罩/截图的坐标系对齐
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (savedX >= 0) savedX.coerceIn(0, (screen.x - size).coerceAtLeast(0)) else screen.x - size - Ui.dp(this@FloatingBallService, 8f)
            y = if (savedY >= 0) savedY.coerceIn(0, (screen.y - size).coerceAtLeast(0)) else screen.y / 3
        }

        v.setOnTouchListener { _, e -> onBallTouch(e) }

        try {
            wm.addView(v, p)
        } catch (t: Throwable) {
            Log.e(TAG, "addView 失败：${t.message}")
            Toast.makeText(this, "悬浮球添加失败：${t.message}", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        ball = v
        lp = p
    }

    private fun onBallTouch(e: MotionEvent): Boolean {
        val p = lp ?: return false
        val v = ball ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = e.rawX
                downRawY = e.rawY
                startX = p.x
                startY = p.y
                moved = false
                longPressFired = false
                main.removeCallbacks(longPress)
                main.postDelayed(longPress, LONG_PRESS_MS)
                v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(90).start()
                true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = (e.rawX - downRawX).toInt()
                val dy = (e.rawY - downRawY).toInt()
                if (!moved && (abs(dx) > Ui.dp(this, TAP_SLOP_DP.toFloat()) ||
                            abs(dy) > Ui.dp(this, TAP_SLOP_DP.toFloat()))
                ) {
                    moved = true
                    main.removeCallbacks(longPress)
                }
                if (moved) {
                    p.x = startX + dx
                    p.y = startY + dy
                    clamp(p)
                    runCatching { wm.updateViewLayout(v, p) }
                }
                true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(longPress)
                v.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
                if (!moved && !longPressFired && e.actionMasked == MotionEvent.ACTION_UP) {
                    onTap()
                } else if (moved) {
                    snapToEdge(p)
                    Prefs.setBallPos(this, p.x, p.y)
                }
                true
            }

            else -> false
        }
        return true
    }

    private fun clamp(p: WindowManager.LayoutParams) {
        val s = screenSize()
        val size = ballSizePx()
        p.x = p.x.coerceIn(0, (s.x - size).coerceAtLeast(0))
        p.y = p.y.coerceIn(0, (s.y - size).coerceAtLeast(0))
    }

    /** 松手吸到最近的左右边缘 */
    private fun snapToEdge(p: WindowManager.LayoutParams) {
        val s = screenSize()
        val size = ballSizePx()
        p.x = if (p.x + size / 2 < s.x / 2) 0 else (s.x - size).coerceAtLeast(0)
        runCatching { wm.updateViewLayout(ball, p) }
    }

    // ---------------- 行为 ----------------

    private fun onTap() {
        if (SystemClock.uptimeMillis() - lastTapAt < 700) return   // 防连点重复开框选
        lastTapAt = SystemClock.uptimeMillis()
        onBallTapped()
    }

    private var lastTapAt = 0L

    /** 点按：进框选模式 */
    private fun onBallTapped() {
        SelectionOverlay.start(this)
    }

    /**
     * 长按：翻译剪贴板。
     *
     * 不能在这里直接读剪贴板 —— Android 10+ 后台读会被系统拦，拿到的永远是空
     * （这是 M1.4 与 M4.1 打架的那处，按"透明 Activity"方案做）。
     * 所以拉一个透明页把 App 顶回前台，由它在前台合法地读一次。
     */
    private fun onLongPress() {
        ClipboardActivity.requestRead(this)
    }

    fun toast(msg: String) {
        main.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    /**
     * 截图期间把自己的球藏起来 —— 否则球会被截进图里，跟着一起发给模型。
     * 卡片同理，由调用方在截图后再显示。
     */
    fun setBallVisible(visible: Boolean) {
        main.post { ball?.visibility = if (visible) View.VISIBLE else View.INVISIBLE }
    }

    /**
     * 把球重新加到最上层。
     *
     * 同类 overlay（TYPE_APPLICATION_OVERLAY）按加入顺序决定层级，后加的在上。
     * 译文卡片后加，会盖在球上面 → 球点不动（用户想再框一次就卡住）。
     * 所以卡片出现后喊一次这个，把球抬回最上面。
     */
    fun bringToFront() {
        main.post {
            val v = ball ?: return@post
            val p = lp ?: return@post
            runCatching {
                wm.removeView(v)
                wm.addView(v, p)
            }.onFailure { Log.w(TAG, "球置顶失败：${it.message}") }
        }
    }

    private fun screenSize(): Point {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            val p = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(p)
            p
        }
    }
}
