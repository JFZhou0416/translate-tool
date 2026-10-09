package com.wuming.screentrans

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 截屏服务：持有 MediaProjection，按 Rect 裁一块出来。
 *
 * 两个必须守住的顺序问题（踩了就是直接崩）：
 *  1. Android 14+ 必须先 startForeground(type=mediaProjection) 再 getMediaProjection()，反了必抛异常。
 *  2. Android 14+ 必须先注册 MediaProjection.Callback 再 createVirtualDisplay()。
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "CaptureService"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        @Volatile
        private var inst: ScreenCaptureService? = null

        /** 投影是否还活着（用户可能在系统里撤销，或换设备后失效） */
        fun isReady(): Boolean = inst?.projection != null

        fun start(ctx: Context, resultCode: Int, data: Intent) {
            val i = Intent(ctx, ScreenCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            inst?.stopSelf()
        }

        /**
         * 抓取指定区域。回调在主线程执行。
         *
         * @param rect 屏幕像素坐标（左上角为原点）
         * @param sinceNanos 只要「晚于这个时刻」的画面帧（System.nanoTime 基准）。
         *        调用方在隐藏自家悬浮窗之后取这个值，用来避开"球还在画面上"的旧帧。
         * @param onDone (bitmap, errorMessage) —— 成功时 errorMessage 为 null
         */
        fun capture(rect: Rect, sinceNanos: Long, onDone: (Bitmap?, String?) -> Unit) {
            val s = inst
            if (s == null || s.projection == null) {
                onDone(null, "截屏未授权或已失效，请在 App 里重新授权")
                return
            }
            s.grab(rect, sinceNanos, onDone)
        }
    }

    private val main = Handler(android.os.Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var bg: Handler? = null

    private var projection: MediaProjection? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "投射被系统/用户停止")
            projection = null
            bg?.post { releaseTarget() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        inst = this
        Notify.ensureChannels(this)
        val t = HandlerThread("capture").also { it.start() }
        thread = t
        bg = Handler(t.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 顺序：先前台（带 mediaProjection 类型），再取 projection
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                Notify.ID_CAPTURE,
                Notify.captureNotification(this),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(Notify.ID_CAPTURE, Notify.captureNotification(this))
        }

        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        if (data == null) {
            // 服务被系统重启时没有授权数据 → 退到后台等下一次显式授权
            Log.w(TAG, "缺少授权数据，等待重新授权")
            return START_NOT_STICKY
        }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        try {
            val mp = mpm.getMediaProjection(code, data)
            if (mp == null) {
                Log.e(TAG, "getMediaProjection 返回 null")
                main.post { FloatingBallService.instance?.toast("截屏授权失败，请重试") }
                return START_NOT_STICKY
            }
            mp.registerCallback(projectionCallback, main)   // 必须先注册
            projection = mp
            Log.i(TAG, "截屏已授权")
        } catch (t: Throwable) {
            Log.e(TAG, "getMediaProjection 异常：${t.message}")
            main.post { FloatingBallService.instance?.toast("截屏授权异常：${t.message}") }
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        inst = null
        releaseTarget()
        runCatching { projection?.unregisterCallback(projectionCallback) }
        runCatching { projection?.stop() }
        projection = null
        thread?.quitSafely()
        super.onDestroy()
    }

    // ---------------- 抓帧 ----------------
    //
    // ⚠️ Android 14 起的硬限制：一个 MediaProjection 实例只允许 createVirtualDisplay **一次**。
    // 每张图重建虚拟屏的写法在 Android 13 及以前没事，在 14 上第二次就抛
    // "Don't re-use the resultData ... Don't take multiple captures by invoking
    //  MediaProjection#createVirtualDisplay multiple times on the same instance."
    // 所以：虚拟屏建一次就常驻复用，永不重建。屏幕尺寸变了走 resize/setSurface（这两个不受限）。

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var displaySize: Point? = null

    private fun grab(rect: Rect, sinceNanos: Long, onDone: (Bitmap?, String?) -> Unit) {
        val mp = projection
        if (mp == null) {
            onDone(null, "截屏已失效，请在 App 里重新授权")
            return
        }
        val h = bg ?: run {
            onDone(null, "截屏线程未就绪")
            return
        }
        h.post { doGrab(mp, rect, sinceNanos, onDone) }
    }

    /** 建好或复用虚拟屏；返回错误信息（null = 可用）。必须在后台线程调用。 */
    private fun ensureTarget(mp: MediaProjection): String? {
        val screen = screenSize()
        val density = resources.displayMetrics.densityDpi
        if (imageReader != null && virtualDisplay != null && displaySize == screen) return null

        val old = imageReader
        val reader = try {
            ImageReader.newInstance(screen.x, screen.y, PixelFormat.RGBA_8888, 3)
        } catch (t: Throwable) {
            return "创建图像缓冲失败：${t.message}"
        }
        try {
            val vd = virtualDisplay
            if (vd == null) {
                virtualDisplay = mp.createVirtualDisplay(
                    "screentrans", screen.x, screen.y, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface, null, null
                )
            } else {
                // 屏幕尺寸变了（旋转屏幕）：resize + 换 surface 都允许，不算"多次 createVirtualDisplay"
                vd.resize(screen.x, screen.y, density)
                vd.setSurface(reader.surface)
            }
        } catch (t: Throwable) {
            reader.close()
            return "创建虚拟屏失败：${t.message}"
        }
        runCatching { old?.close() }
        imageReader = reader
        displaySize = screen
        Log.i(TAG, "虚拟屏就绪 ${screen.x}x${screen.y}")
        return null
    }

    private fun releaseTarget() {
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        virtualDisplay = null
        imageReader = null
        displaySize = null
    }

    private fun doGrab(
        mp: MediaProjection,
        rect: Rect,
        sinceNanos: Long,
        onDone: (Bitmap?, String?) -> Unit
    ) {
        ensureTarget(mp)?.let { err ->
            main.post { onDone(null, err) }
            return
        }
        val reader = imageReader ?: run {
            main.post { onDone(null, "图像缓冲未就绪") }
            return
        }
        val screen = displaySize ?: screenSize()

        // 取帧策略（踩过的坑，别改回"先清空再等新帧"）：
        // 屏幕静止时合成器不会推新帧，清空队列后再等 = 干等 → 高频超时。
        // 正确做法：不丢弃，只按时间戳挑「隐藏悬浮窗之后」产生的那一帧；
        // 实在挑不到就用拿到的最新一帧兜底（宁可图里多半个球，也别让用户失败）。
        var attempts = 0
        val maxAttempts = 24          // ~1.2s
        var staleFallback: android.media.Image? = null
        var skipCount = 0

        /** 裁剪并回调；负责关闭 image */
        fun consume(image: android.media.Image) {
            var cropped: Bitmap? = null
            var err: String? = null
            try {
                image.use {
                    val plane = it.planes[0]
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    // rowStride 通常比 width*pixelStride 大，按 stride 反推真实宽度，避免右边出现花边
                    val realWidth = rowStride / pixelStride
                    val full = Bitmap.createBitmap(realWidth, screen.y, Bitmap.Config.ARGB_8888)
                    full.copyPixelsFromBuffer(plane.buffer)

                    val safe = Rect(rect)
                    // intersect 会原地改 safe；返回值是"到底有没有交集"，必须看 ——
                    // 没有交集时它不改 safe，后面 createBitmap 会越界抛异常
                    if (!safe.intersect(0, 0, full.width, full.height)) {
                        full.recycle()
                        err = "框选区域不在屏幕范围内（坐标异常）"
                        return@use
                    }
                    if (safe.width() <= 1 || safe.height() <= 1) {
                        full.recycle()
                        err = "框选区域太小"
                        return@use
                    }
                    val out = Bitmap.createBitmap(full, safe.left, safe.top, safe.width(), safe.height())
                    if (out !== full) full.recycle()

                    Log.i(TAG, "截图 rect=$safe 尺寸=${out.width}x${out.height} 屏幕=${screen.x}x${screen.y}")
                    dumpIfDebug(out, safe, screen)
                    cropped = out
                }
            } catch (t: Throwable) {
                err = "裁图失败：${t.message}"
            }
            // 注意：reader/virtualDisplay 留着复用，不在这里释放
            main.post { onDone(cropped, err) }
        }

        fun tryAcquire() {
            val image = try {
                reader.acquireLatestImage()
            } catch (t: Throwable) {
                main.post { onDone(null, "读取截图失败：${t.message}") }
                return
            }

            if (image != null) {
                if (image.timestamp >= sinceNanos) {
                    staleFallback?.close()
                    staleFallback = null
                    consume(image)
                    return
                }
                // 还是"球没隐藏时"的旧帧：留着兜底，再等等看有没有更新的
                skipCount++
                staleFallback?.close()
                staleFallback = image
            }

            if (attempts++ < maxAttempts) {
                bg?.postDelayed({ tryAcquire() }, 50)
            } else {
                val fb = staleFallback
                if (fb != null) {
                    Log.w(TAG, "没等到新帧，用旧帧兜底（跳过 $skipCount 帧）")
                    staleFallback = null
                    consume(fb)
                } else {
                    main.post { onDone(null, "截图超时（没拿到画面帧）") }
                }
            }
        }

        bg?.postDelayed({ tryAcquire() }, 60)
    }

    private fun screenSize(): Point {
        val wm = getSystemService(WindowManager::class.java)
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

    /** 调试用：把截图落到 App 私有目录，方便 adb pull 出来人眼核对坐标有没有偏。 */
    private fun dumpIfDebug(bmp: Bitmap, rect: Rect, screen: Point) {
        if (!Prefs.pngDebug(this)) return
        runCatching {
            val dir = File(getExternalFilesDir(null), "shots").apply { mkdirs() }
            val stamp = SimpleDateFormat("MMdd_HHmmss", Locale.US).format(Date())
            val f = File(dir, "shot_${stamp}_${rect.left}_${rect.top}_${rect.width()}x${rect.height()}.png")
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Log.i(TAG, "调试截图已存：${f.absolutePath}（屏幕 ${screen.x}x${screen.y}）")
        }
    }
}
