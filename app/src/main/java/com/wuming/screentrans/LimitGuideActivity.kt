package com.wuming.screentrans

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 「悬浮窗被系统限制」全屏引导页。
 *
 * 背景（vivo / OriginOS 6 · 安卓 15 真机实测）：安卓 15 起，**直装（侧载）**的 App
 * 会被系统限制敏感权限，名单里有「显示在其他应用上层（悬浮窗）」。表现是：
 * 申请时弹「风险受限」，或者开关点完自己弹回去 —— 用户根本猜不到是自己被限制了。
 *
 * 有效解法（已验证）：设置 → 应用管理 → <应用> → 右上角 ⋮ → 允许受限设置 / 解除限制
 * → 验证机主身份 → 再开悬浮窗。
 *
 * 这一页要做的就三件事：说清「不是 App 坏了」、**一步直达应用详情页**、回来后能重新检查。
 * 入口有三条：首页常驻「用不了？点这里」、首页「我打不开这个开关」、首次启动自动进。
 *
 * 暗色适配：本页用 GuideTheme（values / values-night 两套）+ Ui 里的自适应色，
 * 亮色暗色都能看，字号按引导页放大。
 */
class LimitGuideActivity : Activity() {

    private var status: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    private fun render() {
        val root = Ui.column(this, 18f)
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        root.addView(Ui.textAdaptive(this, "悬浮窗开不了？", 24f, bold = true))
        root.addView(Ui.space(this, 6f))
        root.addView(
            Ui.textAdaptive(
                this,
                "安卓 15 起，直装的 App 会被系统限制敏感权限。照下面三步解除，之后一劳永逸。",
                15f, sub = true
            )
        )
        root.addView(Ui.space(this, 12f))

        // ---- 当前状态 ----
        val st = Ui.cardAdaptive(this)
        status = Ui.textAdaptive(this, "", 15f)
        st.addView(status)
        st.addView(Ui.space(this, 6f))
        st.addView(Ui.textAdaptive(this, "这一页的状态和首页一样，回来会自动刷新。", 12f, sub = true))
        root.addView(st)

        // ---- ① 为什么 ----
        val why = Ui.cardAdaptive(this)
        why.addView(Ui.textAdaptive(this, "① 为什么？", 17f, bold = true))
        why.addView(Ui.space(this, 6f))
        why.addView(
            Ui.textAdaptive(
                this,
                "安卓 15 起，非应用商店安装（直装 / 侧载）的 App 默认被系统「限制敏感权限」，" +
                    "显示在其他应用上层（悬浮窗）就在受限名单里。",
                15f
            )
        )
        why.addView(Ui.space(this, 6f))
        why.addView(
            Ui.textAdaptive(
                this,
                "你点开关时弹「风险受限」、或者开关点完自己弹回去 —— 都是这个政策，" +
                    "不是 App 坏了，也不是手机故障。",
                15f
            )
        )
        root.addView(why)

        // ---- ② 怎么做 ----
        val how = Ui.cardAdaptive(this)
        how.addView(Ui.textAdaptive(this, "② 怎么做？", 17f, bold = true))
        how.addView(Ui.space(this, 6f))
        how.addView(
            Ui.textAdaptive(
                this,
                "1. 点下面的按钮，直达「应用信息」页\n" +
                    "2. 点右上角「⋮」→「允许受限设置 / 解除限制」\n" +
                    "3. 验证指纹或密码\n" +
                    "4. 再回到上面的「显示在其他应用上层」开关，把它打开",
                15f
            )
        )
        how.addView(Ui.space(this, 10f))
        how.addView(Ui.buttonTall(this, "打开「应用信息」页") { openAppDetails() })
        how.addView(Ui.space(this, 10f))

        val warn = LinearLayout(this)
        warn.background = Ui.rounded(
            Ui.noteBg(this), Ui.dp(this, 10f), Ui.noteStroke(this), Ui.dp(this, 1f)
        )
        warn.setPadding(Ui.dp(this, 12f), Ui.dp(this, 10f), Ui.dp(this, 12f), Ui.dp(this, 10f))
        warn.addView(
            Ui.text(
                this,
                "⚠️ 必须从右上角「⋮」里进；直接在权限列表点开关是没用的（开关会自己弹回去）。",
                13f, Ui.noteInk(this)
            )
        )
        how.addView(warn)
        root.addView(how)

        // ---- 小抄：各机型入口叫法 ----
        val cheat = Ui.cardAdaptive(this)
        cheat.addView(Ui.textAdaptive(this, "入口叫法小抄（各机型不一样）", 17f, bold = true))
        cheat.addView(Ui.space(this, 6f))
        cheat.addView(
            Ui.textAdaptive(
                this,
                "· vivo / OriginOS：应用信息 → 右上角「⋮」→「允许受限设置」或「解除限制」\n" +
                    "· 小米 / 红米（HyperOS）：应用信息 → 右上角「⋮」→「特殊权限设置」\n" +
                    "· OPPO / 一加 / realme（ColorOS）：应用信息 → 右上角「⋮」→「特殊应用权限」\n" +
                    "· 华为 / 荣耀：应用信息 → 右上角「⋮」→「应用权限」或「特殊访问权限」\n" +
                    "· 三星（One UI）：应用信息 →「权限」→ 右上角「⋮」→「允许受限设置」\n" +
                    "· 其它机型：找「更多设置 / 特殊权限 / 高级」这类入口",
                14f
            )
        )
        cheat.addView(Ui.space(this, 8f))
        cheat.addView(
            Ui.textAdaptive(
                this,
                "找不到「⋮」？直接问手机客服，或者在设置里搜「受限」两个字。",
                12f, sub = true
            )
        )
        root.addView(cheat)

        // ---- ③ 然后回来 ----
        val back = Ui.cardAdaptive(this)
        back.addView(Ui.textAdaptive(this, "③ 然后回来", 17f, bold = true))
        back.addView(Ui.space(this, 6f))
        back.addView(
            Ui.textAdaptive(
                this,
                "回来点下面的「重新检查」（或者直接返回首页也一样，状态会自动刷新）。" +
                    "开关能打开了 = 解除成功。",
                15f
            )
        )
        back.addView(Ui.space(this, 10f))
        back.addView(Ui.buttonTall(this, "重新检查悬浮窗权限", primary = false) { recheck() })
        root.addView(back)

        // ---- 隐私说明 ----
        root.addView(
            Ui.textAdaptive(
                this,
                "隐私：截图只用于当次识别，不落盘、不外传（除翻译 API）。",
                12f, sub = true
            )
        )
        root.addView(Ui.space(this, 12f))
        root.addView(Ui.buttonTall(this, "我知道了，返回首页") { finish() })
    }

    private fun refreshState() {
        val ok = FloatingBallService.canDrawOverlay(this)
        if (ok) {
            Prefs.setOverlayRestrictedSuspected(this, false)
            Prefs.setOverlayProbePending(this, false)
        }
        status?.text = if (ok) "悬浮窗权限：已授权 ✅" else "悬浮窗权限：还没有打开"
        status?.setTextColor(if (ok) Ui.okInk(this) else Ui.warnInk(this))
    }

    /** 一步直达「应用信息」页（不是应用管理列表） */
    private fun openAppDetails() {
        val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        runCatching { startActivity(i) }
            .onFailure {
                runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)) }
                toast("请手动找到「屏幕翻译」→ 应用信息")
            }
    }

    private fun recheck() {
        if (FloatingBallService.canDrawOverlay(this)) {
            refreshState()
            toast("✅ 悬浮窗权限已打开，回首页开悬浮球吧")
            finish()
            return
        }
        Toast.makeText(
            this,
            "还是没打开：先按 ② 解除系统限制（右上角「⋮」→ 允许受限设置），再回来点我",
            Toast.LENGTH_LONG
        ).show()
        refreshState()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}