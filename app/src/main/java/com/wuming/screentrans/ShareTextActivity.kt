package com.wuming.screentrans

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/** 分享入口：选中文字 → 分享 → 屏幕翻译。 */
class ShareTextActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (text.isNullOrBlank()) {
            Toast.makeText(this, "分享内容里没有文字", Toast.LENGTH_SHORT).show()
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
