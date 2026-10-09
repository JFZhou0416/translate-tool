package com.wuming.screentrans

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast

/**
 * 透明 Activity：只为了拿一次 MediaProjection 授权。
 *
 * 从悬浮球直接要权限是不行的 —— createScreenCaptureIntent 必须在 Activity 里发起。
 * 好在这类"后台启动 Activity"的限制对有悬浮窗权限的 App 是豁免的，所以这条路能走。
 */
class CapturePermissionActivity : Activity() {

    companion object {
        private const val REQ = 0x51
        const val EXTRA_RECT = "rect"

        /** @param rect 不为空时，授权成功后自动继续这次框选 */
        fun request(ctx: Context, rect: Rect?) {
            val i = Intent(ctx, CapturePermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (rect != null) i.putExtra(EXTRA_RECT, rect)
            runCatching { ctx.startActivity(i) }
                .onFailure { FloatingBallService.instance?.toast("无法打开授权页：${it.message}") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        if (mpm == null) {
            Toast.makeText(this, "系统不支持截屏 API", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ) return

        if (resultCode == RESULT_OK && data != null) {
            ScreenCaptureService.start(this, resultCode, data)

            @Suppress("DEPRECATION")
            val rect: Rect? = intent.getParcelableExtra(EXTRA_RECT)
            if (rect != null) {
                SelectionOverlay.resumeAfterPermission(applicationContext, rect)
            } else {
                Toast.makeText(this, "截屏已授权", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "已取消截屏授权", Toast.LENGTH_SHORT).show()
            TranslatorCard.dismissIfLoading(applicationContext)
        }
        finish()
    }

    override fun onDestroy() {
        overridePendingTransition(0, 0)
        super.onDestroy()
    }
}
