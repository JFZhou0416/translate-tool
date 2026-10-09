package com.wuming.screentrans

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 主界面 = 权限引导台。
 * 日常使用不需要打开这个界面，但四个开关（悬浮窗 / 通知 / 截屏 / 电池白名单）
 * 一个不齐就各种不工作，所以摆在一页里一眼看全。
 *
 * v0.2 起升级成「权限体检」：每一项都是实时状态，onResume 必刷一次
 * （从系统设置页回来立刻能看到变化）。
 *
 * 安卓 15 起，直装（侧载）的 App 会被系统「限制敏感权限」，悬浮窗就在名单里。
 * 检测手法不做任何系统私有标志位猜测，只用「用户行为 + 结果」推断：
 *   用户点过「检查悬浮窗权限」去试了一次 → 回来 canDrawOverlays() 还是 false
 *   → 界面冒出「我打不开这个开关」→ 用户一点 ⇒ 判定被限制，直接送进
 *   [LimitGuideActivity] 教他解除（应用信息 → ⋮ → 允许受限设置）。
 */
class MainActivity : Activity() {

    private lateinit var root: LinearLayout

    private var rowOverlay: TextView? = null
    private var rowNotify: TextView? = null
    private var rowCapture: TextView? = null
    private var rowBattery: TextView? = null
    private var rowBall: TextView? = null
    private var rowUsage: TextView? = null
    private var btnBall: Button? = null

    /** 「开关点了会弹回来？」这块提示，只在试过之后出现 */
    private var limitHint: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
        firstRunGuideOnce()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun render() {
        root = Ui.column(this, 18f)
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        root.addView(Ui.text(this, "屏幕翻译", 24f, bold = true))
        root.addView(Ui.space(this, 4f))
        root.addView(Ui.text(this, "任意 App 里框一下，AI 出中文", 14f, Ui.GRAY))
        root.addView(Ui.space(this, 14f))

        // ---- 常驻入口：悬浮球出不来的时候，用户从这儿进引导页 ----
        val help = Ui.card(this)
        help.background = Ui.rounded(Ui.BLUE, Ui.dp(this, 14f))
        help.setOnClickListener { openLimitGuide() }
        help.addView(Ui.text(this, "用不了？点这里 ›", 17f, Color.WHITE, bold = true))
        help.addView(Ui.space(this, 4f))
        help.addView(
            Ui.text(
                this,
                "悬浮球出不来 / 开关点了没反应 / 提示「风险受限」—— 大概率是安卓 15 对直装 App 的限制，30 秒能解除。",
                13f, 0xFFDCE6FF.toInt()
            )
        )
        root.addView(help)

        // ---- ① 权限体检 ----
        val perm = Ui.card(this)
        perm.addView(Ui.text(this, "① 权限体检", 16f, bold = true))
        perm.addView(Ui.space(this, 2f))
        perm.addView(Ui.text(this, "四项都齐才好用；从系统设置页回来会自动刷新", 12f, Ui.GRAY))
        perm.addView(Ui.space(this, 10f))

        val (r1, s1) = Ui.statusRow(this, "悬浮窗权限")
        rowOverlay = s1
        perm.addView(r1)
        perm.addView(Ui.button(this, "检查悬浮窗权限") { checkOverlayPermission() })

        // 试过一次还是不行 → 问一句「是不是打不开这个开关」（被系统限制的判定入口）
        val hint = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        hint.background = Ui.rounded(
            0xFFFFF5E6.toInt(), Ui.dp(this, 10f), 0xFFE8B473.toInt(), Ui.dp(this, 1f)
        )
        hint.setPadding(
            Ui.dp(this, 12f), Ui.dp(this, 10f), Ui.dp(this, 12f), Ui.dp(this, 12f)
        )
        hint.addView(
            Ui.text(this, "开关点了会自己弹回来？或者提示「风险受限」？", 13f, 0xFF8A4B00.toInt(), bold = true)
        )
        hint.addView(Ui.space(this, 6f))
        hint.addView(Ui.button(this, "我打不开这个开关 → 看怎么解除") {
            Prefs.setOverlayRestrictedSuspected(this, true)
            refresh()
            openLimitGuide()
        })
        hint.visibility = View.GONE
        limitHint = hint
        perm.addView(hint)
        perm.addView(Ui.space(this, 10f))

        val (r2, s2) = Ui.statusRow(this, "通知权限")
        rowNotify = s2
        perm.addView(r2)
        perm.addView(Ui.button(this, "去授权通知") { requestNotifications() })
        perm.addView(Ui.space(this, 10f))

        val (r3, s3) = Ui.statusRow(this, "截屏授权")
        rowCapture = s3
        perm.addView(r3)
        perm.addView(Ui.button(this, "授权截屏") { CapturePermissionActivity.request(this, null) })
        perm.addView(Ui.space(this, 6f))
        perm.addView(
            Ui.text(
                this,
                "Android 14 起每次重启 App 后，第一次框选会弹一次系统授权框（点「立即开始」即可）。",
                12f, Ui.GRAY
            )
        )
        perm.addView(Ui.space(this, 10f))

        val (r4, s4) = Ui.statusRow(this, "电池白名单（防被系统杀）")
        rowBattery = s4
        perm.addView(r4)
        perm.addView(Ui.button(this, "加入白名单") { requestBattery() })
        root.addView(perm)

        // ---- ② 悬浮球 ----
        val ball = Ui.card(this)
        ball.addView(Ui.text(this, "② 悬浮球", 16f, bold = true))
        ball.addView(Ui.space(this, 8f))
        val (r5, s5) = Ui.statusRow(this, "运行状态")
        rowBall = s5
        ball.addView(r5)
        ball.addView(Ui.space(this, 8f))

        val ballRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnBall = Ui.button(this, "开启悬浮球", primary = true) { toggleBall() }
        ballRow.addView(btnBall)
        ballRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(Ui.dp(this@MainActivity, 8f), 1) })
        ballRow.addView(Ui.button(this, "翻译剪贴板") { translateClipboard() })
        ball.addView(ballRow)
        ball.addView(Ui.space(this, 6f))
        ball.addView(
            Ui.text(
                this,
                "点悬浮球 → 框选翻译　｜　长按悬浮球 → 翻译剪贴板里的文字",
                12f, Ui.GRAY
            )
        )
        root.addView(ball)

        // ---- ③ 设置与用量 ----
        val other = Ui.card(this)
        other.addView(Ui.text(this, "③ 设置与用量", 16f, bold = true))
        other.addView(Ui.space(this, 8f))
        val (r6, s6) = Ui.statusRow(this, "今日调用")
        rowUsage = s6
        other.addView(r6)
        other.addView(Ui.space(this, 8f))
        val setRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        setRow.addView(Ui.button(this, "API Key 与模型设置", primary = true) {
            startActivity(Intent(this, SettingsActivity::class.java))
        })
        other.addView(setRow)
        other.addView(Ui.space(this, 6f))
        other.addView(Ui.text(this, "翻译用的 key 存在本机加密存储里，不会打进安装包。", 12f, Ui.GRAY))
        root.addView(other)

        root.addView(Ui.space(this, 8f))
        root.addView(
            Ui.text(
                this,
                "版本 0.2 · 一期不含无障碍服务，不读屏、不监听键盘；截图只用于当次识别，不落盘、不外传（除翻译 API）。",
                12f, Ui.GRAY
            )
        )
    }

    // ---------------- 状态刷新（onResume 必刷一次） ----------------

    private fun refresh() {
        // ---- 悬浮窗：已授权 / 未授权 / 被系统限制 ----
        val overlayOk = FloatingBallService.canDrawOverlay(this)
        if (overlayOk) {
            // 打开了就把「疑似被限制」的推断清掉，免得下次误报
            Prefs.setOverlayProbePending(this, false)
            Prefs.setOverlayRestrictedSuspected(this, false)
        }
        val restricted = !overlayOk && Prefs.overlayRestrictedSuspected(this)
        setStatus(
            rowOverlay,
            when {
                overlayOk -> "已授权"
                restricted -> "被系统限制"
                else -> "未授权"
            },
            when {
                overlayOk -> Ui.GREEN
                restricted -> Ui.WARN
                else -> Ui.RED
            }
        )
        // 试过一次还打不开 → 才把「是不是点了没用」这句问出来
        val ask = !overlayOk && (restricted || Prefs.overlayProbePending(this))
        limitHint?.visibility = if (ask) View.VISIBLE else View.GONE

        // ---- 通知 ----
        val notifyNeed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val notifyOk = hasNotificationPermission()
        setStatus(
            rowNotify,
            when {
                !notifyNeed -> "不需要（安卓 13 以下）"
                notifyOk -> "已授权"
                else -> "未授权"
            },
            when {
                !notifyNeed -> Ui.GRAY
                notifyOk -> Ui.GREEN
                else -> Ui.RED
            }
        )

        // ---- 截屏：系统授权是「一次会话一次」，没授权时如实写「首次使用时询问」 ----
        val capOk = ScreenCaptureService.isReady()
        setStatus(
            rowCapture,
            if (capOk) "已授权（本次会话）" else "首次使用时询问",
            if (capOk) Ui.GREEN else Ui.GRAY
        )

        // ---- 电池白名单 ----
        val batOk = isIgnoringBattery()
        setStatus(
            rowBattery,
            if (batOk) "已加入白名单" else "未加入",
            if (batOk) Ui.GREEN else Ui.RED
        )

        // ---- 悬浮球 / 用量 ----
        rowBall?.text = if (FloatingBallService.isRunning) "运行中" else "未运行"
        rowBall?.setTextColor(if (FloatingBallService.isRunning) Ui.GREEN else Ui.GRAY)
        rowUsage?.text = "${Prefs.usedToday(this)} / ${Prefs.dailyCap(this).let { if (it <= 0) "不限" else "$it" }}"

        btnBall?.text = if (FloatingBallService.isRunning) "重启悬浮球" else "开启悬浮球"
    }

    private fun setStatus(tv: TextView?, text: String, color: Int) {
        tv?.text = text
        tv?.setTextColor(color)
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun isIgnoringBattery(): Boolean {
        val pm = getSystemService(PowerManager::class.java)
        return pm?.isIgnoringBatteryOptimizations(packageName) == true
    }

    // ---------------- 动作 ----------------

    /** 「试一次」：送用户去悬浮窗授权页，并记下他试过（回来还是 false 才好下判断） */
    private fun checkOverlayPermission() {
        Prefs.setOverlayProbePending(this, true)
        openOverlaySettings()
    }

    private fun openOverlaySettings() {
        val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        runCatching { startActivity(i) }
            .onFailure {
                runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
                toast("请手动找到「屏幕翻译」并允许显示在其他应用上层")
            }
    }

    private fun openLimitGuide() {
        startActivity(Intent(this, LimitGuideActivity::class.java))
    }

    /** 第一次装上（或清数据后第一次）直接把「为什么悬浮球出不来」讲一遍 */
    private fun firstRunGuideOnce() {
        if (Prefs.limitGuideSeen(this)) return
        Prefs.setLimitGuideSeen(this, true)
        root.post { openLimitGuide() }
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
        } else {
            toast("此版本不需要单独授权通知")
        }
    }

    private fun requestBattery() {
        val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
        runCatching { startActivity(i) }
            .onFailure {
                runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                toast("请手动把「屏幕翻译」加入电池白名单")
            }
    }

    private fun toggleBall() {
        if (FloatingBallService.isRunning) {
            FloatingBallService.stop(this)
            toast("已停止")
            root.postDelayed({ refresh() }, 300)
            return
        }
        if (!FloatingBallService.canDrawOverlay(this)) {
            toast("先给「显示在其他应用上层」权限")
            checkOverlayPermission()
            return
        }
        if (!hasNotificationPermission()) requestNotifications()
        FloatingBallService.start(this)
        root.postDelayed({ refresh() }, 400)
    }

    private fun translateClipboard() {
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) {
            toast("剪贴板里没有文字")
            return
        }
        TranslateFlow.onText(this, text)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_NOTIFY = 0x21
    }
}