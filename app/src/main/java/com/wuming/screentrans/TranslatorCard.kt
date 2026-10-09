package com.wuming.screentrans

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** 译文卡片：悬浮显示，可拖动 / 复制 / 切换原文 / 关闭。 */
object TranslatorCard {

    private const val TAG = "Card"

    private val main = Handler(Looper.getMainLooper())

    private var wm: WindowManager? = null
    private var lp: WindowManager.LayoutParams? = null
    private var root: View? = null
    private var body: TextView? = null
    private var footer: LinearLayout? = null
    private var metaView: TextView? = null

    private var content: CardContent? = null
    private var showSource = false

    data class CardContent(
        val source: String?,
        val target: String,
        val meta: String? = null,
        val isError: Boolean = false
    )

    fun isShowing(): Boolean = root != null

    /** 从模型返回里拆出【原文】和【译文】两段 */
    fun split(raw: String): Pair<String?, String> {
        val sIdx = raw.indexOf("【原文】")
        val tIdx = raw.indexOf("【译文】")
        if (sIdx >= 0 && tIdx > sIdx) {
            val source = raw.substring(sIdx + 4, tIdx).trim()
            val target = raw.substring(tIdx + 4).trim()
            return source.ifBlank { null } to target
        }
        return null to raw.trim()
    }

    fun showLoading(ctx: Context) {
        main.post { render(ctx.applicationContext, null, loading = true) }
    }

    fun showResult(ctx: Context, raw: String, meta: String?) {
        val (s, t) = split(raw)
        main.post { render(ctx.applicationContext, CardContent(s, t, meta), loading = false) }
    }

    fun showError(ctx: Context, msg: String) {
        main.post { render(ctx.applicationContext, CardContent(null, msg, null, true), loading = false) }
    }

    fun dismissIfLoading(ctx: Context) {
        main.post { if (content == null) dismiss() }
    }

    fun dismiss() {
        val w = wm ?: return
        root?.let { v -> runCatching { w.removeView(v) } }
        root = null
        body = null
        footer = null
        content = null
        showSource = false
    }

    // ---------------- 渲染 ----------------

    private fun render(app: Context, c: CardContent?, loading: Boolean) {
        // 没有悬浮窗权限 → 退化成对话框，至少别什么都不显示
        if (!FloatingBallService.canDrawOverlay(app)) {
            if (c != null) FallbackDialog.show(app, c)
            return
        }

        content = c
        if (c != null) {
            // 新结果默认显示译文
            if (c.isError) showSource = false else showSource = false
        }

        if (root == null) buildCard(app) else refresh(app, loading, c)
    }

    private fun buildCard(app: Context) {
        val w = app.getSystemService(WindowManager::class.java)
        val screen = screenSize(app)

        val col = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(0xF5FFFFFF.toInt(), Ui.dp(app, 16f), 0x33202226, Ui.dp(app, 1f))
            elevation = Ui.dp(app, 10f).toFloat()
            setPadding(Ui.dp(app, 14f), Ui.dp(app, 10f), Ui.dp(app, 14f), Ui.dp(app, 10f))
        }

        // 头部：标题 + 关闭；拖动也走这里
        val header = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = Ui.text(app, "译文", 14f, Ui.BLUE, bold = true)
        val metaTv = Ui.text(app, "", 11f, Ui.GRAY).apply {
            setPadding(0, 0, Ui.dp(app, 8f), 0)
        }
        metaView = metaTv
        val close = TextView(app).apply {
            text = "✕"
            textSize = 16f
            setTextColor(Ui.GRAY)
            setPadding(Ui.dp(app, 10f), 0, 0, 0)
            setOnClickListener { dismiss() }
        }
        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(metaTv)
        header.addView(close)
        col.addView(header)

        col.addView(Ui.space(app, 6f))

        val tv = TextView(app).apply {
            textSize = 15f
            setTextColor(Ui.DARK)
            movementMethod = ScrollingMovementMethod()
            setLineSpacing(Ui.dp(app, 3f).toFloat(), 1f)
            setTextIsSelectable(true)
        }
        val sv = MaxHeightScrollView(app, (screen.y * 0.45f).toInt()).apply {
            isVerticalScrollBarEnabled = true
            addView(tv)
        }
        col.addView(
            sv,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        body = tv

        val f = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Ui.dp(app, 10f), 0, 0)
        }
        footer = f
        col.addView(f)

        val p = WindowManager.LayoutParams(
            (screen.x * 0.88f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screen.x * 0.06f).toInt()
            y = (screen.y * 0.22f).toInt()
        }

        attachDrag(app, header, p)

        try {
            w.addView(col, p)
        } catch (t: Throwable) {
            Log.e(TAG, "卡片添加失败：${t.message}")
            if (content != null) FallbackDialog.show(app, content!!)
            return
        }
        wm = w
        lp = p
        root = col

        // 卡片是后加的，会盖住悬浮球 → 把球抬回最上层，否则用户点不到球
        FloatingBallService.instance?.bringToFront()

        refresh(app, loading = content == null, c = content)
    }

    private fun attachDrag(app: Context, handle: View, p: WindowManager.LayoutParams) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = p.x
                    startY = p.y
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    p.x = startX + (e.rawX - downX).toInt()
                    p.y = startY + (e.rawY - downY).toInt()
                    val s = screenSize(app)
                    p.x = p.x.coerceIn(-(p.width / 4), s.x - p.width * 3 / 4)
                    p.y = p.y.coerceIn(0, (s.y - Ui.dp(app, 80f)).coerceAtLeast(0))
                    root?.let { runCatching { wm?.updateViewLayout(it, p) } }
                    true
                }

                else -> false
            }
        }
    }

    private fun refresh(app: Context, loading: Boolean, c: CardContent?) {
        val tv = body ?: return
        val f = footer ?: return
        // 耗时/用量显示在标题右边（验收要看端到端 ≤3s）
        metaView?.text = if (loading || c == null || c.isError) "" else (c.meta ?: "")

        when {
            loading -> {
                tv.text = "识别中…"
                tv.setTextColor(Ui.GRAY)
                f.removeAllViews()
            }

            c == null -> {
                tv.text = ""
                f.removeAllViews()
            }

            c.isError -> {
                tv.text = c.target
                tv.setTextColor(Ui.RED)
                f.removeAllViews()
                f.addView(Ui.button(app, "设置 Key", primary = true) {
                    dismiss()
                    openSettings(app)
                })
                f.addView(Ui.space(app, 0f))
                f.addView(viewSpacer(app))
                f.addView(Ui.button(app, "关闭") { dismiss() })
            }

            else -> {
                tv.setTextColor(Ui.DARK)
                tv.text = if (showSource && c.source != null) c.source else c.target
                f.removeAllViews()

                if (c.source != null) {
                    f.addView(
                        Ui.button(app, if (showSource) "显示译文" else "显示原文") {
                            showSource = !showSource
                            refresh(app, false, c)
                        }
                    )
                }
                f.addView(viewSpacer(app))
                f.addView(Ui.button(app, "复制", primary = true) {
                    copy(app, if (showSource && c.source != null) c.source!! else c.target)
                })
                f.addView(viewSpacer(app))
                f.addView(Ui.button(app, "关闭") { dismiss() })
            }
        }
    }

    private fun viewSpacer(app: Context): View = View(app).apply {
        layoutParams = LinearLayout.LayoutParams(Ui.dp(app, 8f), 1)
    }

    private fun copy(app: Context, text: String) {
        // 不能直接 setPrimaryClip：App 在后台时写剪贴板会被系统静默丢弃
        // （toast 会说"已复制"，实际什么都没进去）。走透明页，前台写入才生效。
        ClipboardActivity.requestWrite(app, text)
    }

    private fun openSettings(app: Context) {
        runCatching {
            app.startActivity(
                android.content.Intent(app, SettingsActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun screenSize(app: Context): Point {
        val w = app.getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = w.currentWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            val p = Point()
            @Suppress("DEPRECATION")
            w.defaultDisplay.getRealSize(p)
            p
        }
    }
}

/** 卡片的正文区：高度撑到自己需要的大小，但最多给 maxH（超出就滚动）。 */
private class MaxHeightScrollView(ctx: Context, private val maxH: Int) : ScrollView(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val capped = MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST)
        super.onMeasure(widthMeasureSpec, capped)
    }
}

/** 没有悬浮窗权限时的退路：直接用对话框显示，别静默失败。 */
private object FallbackDialog {
    fun show(ctx: Context, c: TranslatorCard.CardContent) {
        val tv = TextView(ctx).apply {
            text = c.target
            textSize = 15f
            setPadding(40, 30, 40, 30)
            movementMethod = ScrollingMovementMethod()
            setTextColor(if (c.isError) Ui.RED else Ui.DARK)
        }
        runCatching {
            AlertDialog.Builder(ctx)
                .setTitle(if (c.isError) "出错了" else "译文")
                .setView(tv)
                .setPositiveButton("知道了", null)
                .show()
        }
    }
}
