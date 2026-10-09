package com.wuming.screentrans

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/** 通知渠道 + 前台服务通知。两个前台服务各用一个渠道，方便用户单独静音。 */
object Notify {

    const val CH_BALL = "ball"
    const val CH_CAPTURE = "capture"
    const val ID_BALL = 1001
    const val ID_CAPTURE = 1002

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_BALL, "悬浮球", NotificationManager.IMPORTANCE_MIN).apply {
                description = "悬浮球常驻通知"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_CAPTURE, "截屏", NotificationManager.IMPORTANCE_LOW).apply {
                description = "截屏服务运行中"
                setShowBadge(false)
            }
        )
    }

    fun ballNotification(ctx: Context, running: Boolean = true): Notification {
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            ctx, 1,
            Intent(ctx, FloatingBallService::class.java).setAction(FloatingBallService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val b = Notification.Builder(ctx, CH_BALL)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle(if (running) "屏幕翻译已开启" else "屏幕翻译已停止")
            .setContentText(if (running) "点悬浮球框选翻译；长按翻译剪贴板" else "点此重新开启")
            .setOngoing(running)
            .setContentIntent(open)
        if (running) b.addAction(Notification.Action.Builder(null, "停止", stop).build())
        return b.build()
    }

    fun captureNotification(ctx: Context): Notification =
        Notification.Builder(ctx, CH_CAPTURE)
            .setSmallIcon(android.R.drawable.ic_menu_crop)
            .setContentTitle("正在截取屏幕")
            .setContentText("仅用于本次识别，不保存")
            .setOngoing(true)
            .build()
}
