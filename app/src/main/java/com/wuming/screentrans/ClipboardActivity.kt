package com.wuming.screentrans

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * 透明 Activity：剪贴板读写的"前台代理"。
 *
 * 为什么必须绕这一下：Android 10+ 起，**不在前台**的 App 读写剪贴板都会被系统拦
 * （读直接返空、写静默丢弃，都不报错）。我们的悬浮球/卡片是 overlay，
 * App 本身在后台，所以：
 *  - 长按悬浮球"翻译剪贴板" → 直接读永远读到空
 *  - 卡片上点"复制" → toast 会说已复制，但剪贴板里什么都没进
 * 拉一个透明页把 App 顶回前台，读写才合法。
 *
 * ⚠️ 还要等窗口真正拿到焦点（onWindowFocusChanged）再动手：
 * 在 onCreate 里读，窗口还没焦点，照样读不到。
 */
class ClipboardActivity : Activity() {

    companion object {
        private const val EXTRA_WRITE = "write_text"

        /** 读剪贴板并翻译 */
        fun requestRead(ctx: Context) {
            launch(ctx, Intent(ctx, ClipboardActivity::class.java))
        }

        /** 把译文写进剪贴板 */
        fun requestWrite(ctx: Context, text: String) {
            launch(ctx, Intent(ctx, ClipboardActivity::class.java).putExtra(EXTRA_WRITE, text))
        }

        private fun launch(ctx: Context, i: Intent) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            runCatching { ctx.startActivity(i) }
                .onFailure { FloatingBallService.instance?.toast("操作失败：${it.message}") }
        }
    }

    private var handled = false
    private var pendingWrite: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingWrite = intent?.getStringExtra(EXTRA_WRITE)
    }

    /** 等到窗口真正有焦点，这时读写才是合法的 */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled) return
        handled = true

        val cm = getSystemService(ClipboardManager::class.java)

        val toWrite = pendingWrite
        if (toWrite != null) {
            cm?.setPrimaryClip(ClipData.newPlainText("translation", toWrite))
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val text = cm?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()

        if (text.isNullOrBlank()) {
            Toast.makeText(this, "剪贴板里没有文字", Toast.LENGTH_SHORT).show()
        } else {
            TranslateFlow.onText(this, text)
        }
        finish()
    }

    override fun onDestroy() {
        overridePendingTransition(0, 0)
        super.onDestroy()
    }
}
