package com.example.pockettmux

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Foreground service that keeps the process (and so the SSH session and prompt detector) alive
 * while the app is in the background. It does no work itself.
 */
class KeepAliveService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = PromptNotifier(this).serviceNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(PromptNotifier.SERVICE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(PromptNotifier.SERVICE_ID, notification)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
