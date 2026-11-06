package com.lxdl.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

object Notifier {
    const val CHANNEL = "lxdl_download"
    const val PROGRESS_ID = 1001
    const val DONE_ID = 1002

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL, "下载", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun progress(ctx: Context, title: String, done: Long, total: Long): Notification {
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在下载")
            .setContentText(title)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(1000, if (total > 0) ((done * 1000 / total).toInt()) else 0, total <= 0)
        return b.build()
    }

    fun start(ctx: Context, title: String) {
        ensureChannel(ctx)
        val i = Intent(ctx, DownloadService::class.java).putExtra("title", title)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
    }

    fun update(ctx: Context, title: String, done: Long, total: Long) {
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        runCatching { mgr.notify(PROGRESS_ID, progress(ctx, title, done, total)) }
    }

    fun stop(ctx: Context) {
        runCatching { ctx.stopService(Intent(ctx, DownloadService::class.java)) }
        runCatching {
            ctx.getSystemService(NotificationManager::class.java)?.cancel(PROGRESS_ID)
        }
    }

    fun done(ctx: Context, title: String, uri: String, path: String) {
        ensureChannel(ctx)
        val open = if (uri.isNotBlank()) {
            PendingIntent.getActivity(
                ctx, 10,
                Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), "audio/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else null
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("下载完成")
            .setContentText(title + " · " + path)
            .setAutoCancel(true)
            .setContentIntent(open)
        runCatching {
            ctx.getSystemService(NotificationManager::class.java)?.notify(DONE_ID, b.build())
        }
    }
}

class DownloadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra("title") ?: "下载中"
        startForeground(
            Notifier.PROGRESS_ID,
            NotificationCompat.Builder(this, Notifier.CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("正在下载")
                .setContentText(title)
                .setOngoing(true)
                .setProgress(0, 0, true)
                .build()
        )
        return START_NOT_STICKY
    }
}
