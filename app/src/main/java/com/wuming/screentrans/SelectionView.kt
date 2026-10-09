package com.wuming.screentrans

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * 框选遮罩本体：半透明压暗 + 拖拽出选框 + 顶部提示 + 右上角取消。
 *
 * 注意 overlay 窗口是 FLAG_NOT_FOCUSABLE 的，收不到返回键，
 * 所以"取消"必须由界面自己提供（右上角按钮 / 点空白）。
 */
class SelectionView(
    ctx: Context,
    private val onDone: (Rect) -> Unit
) : View(ctx) {

    private val dim = Paint().apply { color = 0x8C000000.toInt() }

    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = Ui.dp(ctx, 2f).toFloat()
    }

    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.BLUE
        style = Paint.Style.STROKE
        strokeWidth = Ui.dp(ctx, 4f).toFloat()
        strokeCap = Paint.Cap.ROUND
    }

    private val hintBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC101216.toInt() }
    private val hintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = Ui.dp(ctx, 14f).toFloat()
    }

    private val cancelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6303238.toInt() }

    private var startX = 0f
    private var startY = 0f
    private var curX = 0f
    private var curY = 0f
    private var dragging = false
    private var finished = false

    private val touchSlop = Ui.dp(ctx, 6f).toFloat()

    private fun cancelRect(): RectF {
        val w = Ui.dp(context, 88f).toFloat()
        val h = Ui.dp(context, 40f).toFloat()
        val m = Ui.dp(context, 16f).toFloat()
        val top = Ui.dp(context, 36f).toFloat()
        return RectF(width - w - m, top, width - m, top + h)
    }

    private fun selection(): Rect? {
        if (!dragging) return null
        val l = minOf(startX, curX).toInt()
        val t = minOf(startY, curY).toInt()
        val r = maxOf(startX, curX).toInt()
        val b = maxOf(startY, curY).toInt()
        if (r - l < 2 || b - t < 2) return null
        return Rect(l, t, r, b)
    }

    /**
     * 把「视图内坐标」换成「屏幕坐标」。
     *
     * 必须换：overlay 窗口的坐标系原点不一定在屏幕左上角。
     * 实测这台 vivo（Android 14）上窗口 parent frame 是 [0,126]（状态栏下方），
     * 于是 lp.y=933 实际落在屏幕 y=1059 —— 直接拿视图坐标去裁图会整体偏掉一条状态栏的高度。
     * 用 getLocationOnScreen 动态取偏移，换机型/换 ROM 都不会错。
     */
    private fun toScreen(r: Rect): Rect {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        return Rect(r.left + loc[0], r.top + loc[1], r.right + loc[0], r.bottom + loc[1])
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val sel = selection()

        canvas.save()
        sel?.let { canvas.clipOutRect(it) }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        canvas.restore()

        sel?.let {
            canvas.drawRect(it, border)
            drawCornerTicks(canvas, it)
            // 尺寸提示
            val label = "${it.width()} × ${it.height()}"
            val labelX = it.left + Ui.dp(context, 6f).toFloat()
            val labelY = (it.top - Ui.dp(context, 8f)).toFloat().coerceAtLeast(hintText.textSize)
            canvas.drawText(label, labelX, labelY, hintText)
        }

        drawHint(canvas)
        drawCancel(canvas)
    }

    private fun drawCornerTicks(canvas: Canvas, r: Rect) {
        val len = Ui.dp(context, 18f).toFloat()
        val l = r.left.toFloat()
        val t = r.top.toFloat()
        val rr = r.right.toFloat()
        val b = r.bottom.toFloat()
        // 左上
        canvas.drawLine(l, t, l + len, t, tick)
        canvas.drawLine(l, t, l, t + len, tick)
        // 右上
        canvas.drawLine(rr, t, rr - len, t, tick)
        canvas.drawLine(rr, t, rr, t + len, tick)
        // 左下
        canvas.drawLine(l, b, l + len, b, tick)
        canvas.drawLine(l, b, l, b - len, tick)
        // 右下
        canvas.drawLine(rr, b, rr - len, b, tick)
        canvas.drawLine(rr, b, rr, b - len, tick)
    }

    private fun drawHint(canvas: Canvas) {
        val text = "拖拽框选要翻译的区域 · 点空白取消"
        val pad = Ui.dp(context, 12f).toFloat()
        val tw = hintText.measureText(text)
        val h = Ui.dp(context, 34f).toFloat()
        val left = (width - tw) / 2f - pad
        val right = (width + tw) / 2f + pad
        val top = Ui.dp(context, 84f).toFloat()
        val rf = RectF(left, top, right, top + h)
        canvas.drawRoundRect(rf, h / 2f, h / 2f, hintBg)
        val baseline = rf.centerY() - (hintText.descent() + hintText.ascent()) / 2f
        canvas.drawText(text, left + pad, baseline, hintText)
    }

    private fun drawCancel(canvas: Canvas) {
        val rf = cancelRect()
        canvas.drawRoundRect(rf, rf.height() / 2f, rf.height() / 2f, cancelBg)
        val text = "✕ 取消"
        hintText.color = Color.WHITE
        val tw = hintText.measureText(text)
        val baseline = rf.centerY() - (hintText.descent() + hintText.ascent()) / 2f
        canvas.drawText(text, rf.centerX() - tw / 2f, baseline, hintText)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (finished) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (cancelRect().contains(event.x, event.y)) {
                    finished = true
                    onDone(Rect())
                    return true
                }
                startX = event.x
                startY = event.y
                curX = event.x
                curY = event.y
                dragging = false
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) {
                    if (abs(event.x - startX) > touchSlop || abs(event.y - startY) > touchSlop) {
                        dragging = true
                    }
                }
                curX = event.x
                curY = event.y
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                finished = true
                val sel = selection()
                // 点空白 = 取消（传空 Rect，外层判为太小 → 取消）
                onDone(sel?.let { toScreen(it) } ?: Rect())
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                finished = true
                onDone(Rect())
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
