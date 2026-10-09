package com.wuming.screentrans

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log

/** 把「拿到图/文本」到「弹出译文」这条线串起来，顺手记一下耗时（验收要 ≤3s）。 */
object TranslateFlow {

    private const val TAG = "Flow"

    fun onRegion(ctx: Context, bmp: Bitmap, rect: Rect) {
        val start = SystemClock.uptimeMillis()
        Log.i(TAG, "识图翻译开始：区域 ${rect.width()}x${rect.height()}，图 ${bmp.width}x${bmp.height}")

        DeepSeekClient.translateImage(ctx, bmp) { out ->
            if (!bmp.isRecycled) bmp.recycle()
            val ms = SystemClock.uptimeMillis() - start
            when (out) {
                is DeepSeekClient.Out.Ok -> {
                    Log.i(TAG, "识图翻译成功：${ms}ms，出 ${out.completionTokens} tokens")
                    TranslatorCard.showResult(ctx, out.text, "耗时 ${ms}ms")
                }

                is DeepSeekClient.Out.Err -> {
                    Log.w(TAG, "识图翻译失败：${out.message}")
                    TranslatorCard.showError(ctx, out.message)
                }
            }
        }
    }

    fun onText(ctx: Context, text: String) {
        val start = SystemClock.uptimeMillis()
        TranslatorCard.showLoading(ctx)
        DeepSeekClient.translateText(ctx, text) { out ->
            val ms = SystemClock.uptimeMillis() - start
            when (out) {
                is DeepSeekClient.Out.Ok -> TranslatorCard.showResult(ctx, out.text, "耗时 ${ms}ms")
                is DeepSeekClient.Out.Err -> TranslatorCard.showError(ctx, out.message)
            }
        }
    }
}
