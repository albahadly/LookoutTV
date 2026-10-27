package com.albahadly.swanntv.floating

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.albahadly.swanntv.AppLog

/**
 * Keeps the app running while the floating camera window plays over other apps. Without a
 * foreground service Android freezes a background app within seconds and the picture stops.
 * It does nothing else: [FloatingCamera] owns the window and the stream.
 */
class FloatingCameraService : Service() {

    companion object {
        private const val CHANNEL = "floating"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, FloatingCameraService::class.java)) }
                .onFailure { AppLog.w("floating window service refused: ${it.message}") }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingCameraService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Floating camera", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("SwannTV camera window")
            .setContentText("Open SwannTV to close it")
            .setOngoing(true)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure { AppLog.w("floating window could not stay in the foreground: ${it.message}") }
        if (!FloatingCamera.isShowing) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        FloatingCamera.hide() // e.g. Android stopped the service: never leave a window behind
        super.onDestroy()
    }
}
