package com.wuming.screentrans

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** 造控件的小工具：全程序化 View，不写 XML 布局，方便快速改样式。 */
object Ui {

    const val BLUE = 0xFF2F6BFF.toInt()
    const val DARK = 0xFF1B1D22.toInt()
    const val GRAY = 0xFF6B7280.toInt()
    const val RED = 0xFFE5484D.toInt()
    const val GREEN = 0xFF2E9E5B.toInt()

    /** 橙：需要注意 / 被系统限制（不是报错，别用红的吓人） */
    const val WARN = 0xFFC2410C.toInt()

    fun dp(ctx: Context, v: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics
        ).toInt()

    fun rounded(fill: Int, radiusPx: Int, stroke: Int = Color.TRANSPARENT, strokePx: Int = 0):
        GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = radiusPx.toFloat()
        if (strokePx > 0) setStroke(strokePx, stroke)
    }

    fun text(
        ctx: Context,
        s: String,
        sizeSp: Float = 15f,
        color: Int = DARK,
        bold: Boolean = false
    ): TextView = TextView(ctx).apply {
        text = s
        textSize = sizeSp
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setLineSpacing(dp(ctx, 4f).toFloat(), 1f)
    }

    fun button(ctx: Context, label: String, primary: Boolean = false, onClick: () -> Unit): Button =
        Button(ctx).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            setTextColor(if (primary) Color.WHITE else DARK)
            background = rounded(
                if (primary) BLUE else 0xFFEDEFF3.toInt(),
                dp(ctx, 10f)
            )
            setPadding(dp(ctx, 16f), dp(ctx, 8f), dp(ctx, 16f), dp(ctx, 8f))
            setOnClickListener { onClick() }
        }

    /** 引导页用的大按钮：字大、好点（引导页要「大字号」） */
    fun buttonTall(
        ctx: Context,
        label: String,
        primary: Boolean = true,
        onClick: () -> Unit
    ): Button = button(ctx, label, primary, onClick).apply {
        textSize = 16f
        setPadding(dp(ctx, 20f), dp(ctx, 14f), dp(ctx, 20f), dp(ctx, 14f))
    }

    fun column(ctx: Context, padDp: Float = 16f): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, padDp), dp(ctx, padDp), dp(ctx, padDp), dp(ctx, padDp))
        }

    fun space(ctx: Context, hDp: Float): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(ctx, hDp))
    }

    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Color.WHITE, dp(ctx, 14f), 0xFFE3E6EC.toInt(), dp(ctx, 1f))
        setPadding(dp(ctx, 14f), dp(ctx, 12f), dp(ctx, 14f), dp(ctx, 12f))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(ctx, 12f) }
    }

    /** 一行：左边标题，右边状态 */
    fun statusRow(ctx: Context, label: String): Pair<LinearLayout, TextView> {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val l = text(ctx, label, 15f)
        val r = text(ctx, "", 14f, GRAY)
        row.addView(l, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(r)
        return row to r
    }

    // ---------------- 暗色适配 ----------------
    //
    // 引导页独立用 GuideTheme（values / values-night 两套主题），页面里的颜色跟着
    // 系统深色模式走：亮色下和主界面一套观感，暗色下底色 #12141A、正文浅灰，
    // 不刺眼也不糊。主界面保持原样（不在本次改动范围）。

    fun isNight(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** 正文色 */
    fun ink(ctx: Context): Int = if (isNight(ctx)) 0xFFE7E9EE.toInt() else DARK

    /** 次要文字色 */
    fun subInk(ctx: Context): Int = if (isNight(ctx)) 0xFF9AA1AE.toInt() else GRAY

    /** 卡片底色 */
    fun surface(ctx: Context): Int = if (isNight(ctx)) 0xFF1C1F26.toInt() else Color.WHITE

    /** 卡片描边 */
    fun hairline(ctx: Context): Int = if (isNight(ctx)) 0xFF2E3340.toInt() else 0xFFE3E6EC.toInt()

    fun okInk(ctx: Context): Int = if (isNight(ctx)) 0xFF6BD79A.toInt() else GREEN
    fun warnInk(ctx: Context): Int = if (isNight(ctx)) 0xFFFFB86B.toInt() else WARN

    /** 提醒块（浅黄底）三件套 */
    fun noteBg(ctx: Context): Int = if (isNight(ctx)) 0xFF2C2416.toInt() else 0xFFFFF5E6.toInt()
    fun noteStroke(ctx: Context): Int = if (isNight(ctx)) 0xFF6B4E1F.toInt() else 0xFFE8B473.toInt()
    fun noteInk(ctx: Context): Int = if (isNight(ctx)) 0xFFFFD8A8.toInt() else 0xFF8A4B00.toInt()

    /** 跟随深色模式的卡片 */
    fun cardAdaptive(ctx: Context): LinearLayout = card(ctx).apply {
        background = rounded(surface(ctx), dp(ctx, 14f), hairline(ctx), dp(ctx, 1f))
    }

    /** 跟随深色模式的文字（sub = true 用次要色） */
    fun textAdaptive(
        ctx: Context,
        s: String,
        sizeSp: Float = 15f,
        bold: Boolean = false,
        sub: Boolean = false
    ): TextView = text(ctx, s, sizeSp, if (sub) subInk(ctx) else ink(ctx), bold)
}