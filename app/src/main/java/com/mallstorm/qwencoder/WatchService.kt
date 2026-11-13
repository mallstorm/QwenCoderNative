package com.mallstorm.qwencoder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/** Polls running tasks while the app is in background and notifies when one finishes. */
class WatchService : Service() {
    @Volatile private var alive = false
    private var thread: Thread? = null
    private var socket: NotifySocket? = null
    private val lock = Object()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CH_WATCH, "Наблюдение за задачами", NotificationManager.IMPORTANCE_MIN))
            nm.createNotificationChannel(NotificationChannel(CH_DONE, "Задача готова", NotificationManager.IMPORTANCE_HIGH))
        }
        val n = Notification.Builder(this, CH_WATCH)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Qwen Coder следит за задачей")
            .setOngoing(true)
            .build()
        try {
            startForeground(1, n)
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!alive) {
            alive = true
            thread = Thread { loop(nm) }.also { it.start() }
        }
        return START_NOT_STICKY
    }

    private fun loop(nm: NotificationManager) {
        val api = Api(this)
        socket = NotifySocket(api) { synchronized(lock) { lock.notifyAll() } }.also { it.start() }
        var known = HashMap<String, TaskItem>()
        var empty = 0
        var rounds = 0
        while (alive && rounds++ < 360) {
            try {
                val now = api.running()
                val ids = now.map { it.id }.toSet()
                for ((id, t) in known) if (id !in ids && !visible) notifyDone(nm, t)
                known = HashMap(now.associateBy { it.id })
                empty = if (now.isEmpty()) empty + 1 else 0
            } catch (_: Exception) {
                empty++
            }
            if (empty >= 3 || visible) break
            try {
                synchronized(lock) { lock.wait(20_000) }
            } catch (_: InterruptedException) {
                break
            }
        }
        alive = false
        socket?.stop()
        stopForeground(true)
        stopSelf()
    }

    private fun notifyDone(nm: NotificationManager, t: TaskItem) {
        val i = Intent(this, MainActivity::class.java)
            .putExtra("task_id", t.id).putExtra("task_title", t.title)
            .putExtra("task_repo", t.repo).putExtra("task_branch", t.branch)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(this, t.id.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CH_DONE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Задача готова")
            .setContentText(t.title.ifEmpty { "Qwen Coder" })
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(t.id.hashCode(), n)
    }

    override fun onDestroy() {
        alive = false
        socket?.stop()
        thread?.interrupt()
        super.onDestroy()
    }

    companion object {
        const val CH_WATCH = "watch"
        const val CH_DONE = "done"
        @Volatile var visible = true

        fun start(ctx: Context) {
            try {
                val i = Intent(ctx, WatchService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (_: Exception) {
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, WatchService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
