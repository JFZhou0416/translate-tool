package com.wuming.screentrans

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 全屏框选遮罩。
 *
 * 一个必须记住的坑：松手后**先移除遮罩、等 1~2 帧再抓帧**，
 * 否则截出来的图里会带上这层半透明遮罩和选框本身。
 */
object SelectionOverlay {

    private const val TAG = "Selection"
    private const val SETTLE_MS = 160L

    private val main = Handler(Looper.getMainLooper())

    private var wm: android.view.WindowManager? = null
    private var view: SelectionView? = null
    private var params: android.view.WindowManager.LayoutParams? = null

    fun isShowing(): Boolean = view != null

    fun start(ctx: Context) {
        if (view != null) return
        // 上一张译文卡片先收起：不然它会出现在这次截图里
        TranslatorCard.dismiss()
        val app = ctx.applicationContext
        val w = app.getSystemService(android.view.WindowManager::class.java)

        val size = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = w.currentWindowMetrics.bounds
            Pair(b.width(), b.height())
        } else {
            val p = android.graphics.Point()
            @Suppress("DEPRECATION")
            w.defaultDisplay.getRealSize(p)
            Pair(p.x, p.y)
        }

        val v = SelectionView(app, onDone = { rect -> onPicked(app, rect) })
        val p = android.view.WindowManager.LayoutParams(
            size.first, size.second,
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 不抢焦点（不影响别的 App 输入），但仍然接收触摸
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                // 让窗口以"整个屏幕"为坐标基准：否则原点会被压到状态栏下方，
                // 遮罩盖不住状态栏，取到的坐标也会整体偏一条状态栏的高度
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }

        try {
            w.addView(v, p)
        } catch (t: Throwable) {
            Log.e(TAG, "遮罩添加失败：${t.message}")
            FloatingBallService.instance?.toast("框选失败：${t.message}")
            return
        }
        wm = w
        view = v
        params = p
    }

    fun cancel() {
        val w = wm ?: return
        val v = view ?: return
        view = null
        runCatching { w.removeView(v) }
    }

    private fun onPicked(app: Context, rect: android.graphics.Rect) {
        cancel()

        val minPx = Ui.dp(app, 16f)
        if (rect.width() < minPx || rect.height() < minPx) {
            FloatingBallService.instance?.toast("框选区域太小，已取消")
            return
        }

        // 关键：等遮罩真正从屏幕上消失再截
        main.postDelayed({
            if (!ScreenCaptureService.isReady()) {
                // 还没授权截屏 → 拉透明 Activity 去要权限，授权后重试这次框选
                CapturePermissionActivity.request(app, rect)
                return@postDelayed
            }
            doCapture(app, rect)
        }, SETTLE_MS)
    }

    private fun doCapture(app: Context, rect: android.graphics.Rect) {
        // 截图前把自家的悬浮窗收起来（球藏掉、旧卡片关掉、loading 卡片等截完再显示），
        // 否则球和卡片会被一起截进去，跟着发给模型。
        FloatingBallService.instance?.setBallVisible(false)
        TranslatorCard.dismiss()
        // 记下"悬浮窗已隐藏"的时刻：抓帧时只要晚于这一刻的画面，避免截到球还在的旧帧
        val since = System.nanoTime()

        main.postDelayed({
            ScreenCaptureService.capture(rect, since) { bmp, err ->
                FloatingBallService.instance?.setBallVisible(true)
                if (bmp == null) {
                    TranslatorCard.showError(app, err ?: "截图失败")
                    return@capture
                }
                TranslatorCard.showLoading(app)
                TranslateFlow.onRegion(app, bmp, rect)
            }
        }, SETTLE_MS)
    }

    /** 授权回来后继续之前那次框选（由 CapturePermissionActivity 调用） */
    fun resumeAfterPermission(app: Context, rect: android.graphics.Rect) {
        main.postDelayed({
            if (ScreenCaptureService.isReady()) doCapture(app, rect)
            else TranslatorCard.showError(app, "截屏授权未完成")
        }, 300)
    }

    fun startPermissionThenSelect(ctx: Context) {
        CapturePermissionActivity.request(ctx, null)
    }
}
