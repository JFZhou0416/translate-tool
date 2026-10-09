package com.wuming.screentrans

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * 系统文本选中菜单里的「翻译」入口（ACTION_PROCESS_TEXT）。
 *
 * 用户在任何可选中文字的界面（浏览器、编辑器、聊天）选中 → 菜单点「屏幕翻译」→ 直接出译文卡片。
 * 局限：只对"可选中文字"有效 —— 游戏画面、图片、扫描版 PDF 用不了，
 * 那些情况走截屏那条路，两条是互补关系。
 */
class ProcessTextActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        if (text.isNullOrBlank()) {
            Toast.makeText(this, "没有拿到选中的文字", Toast.LENGTH_SHORT).show()
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
