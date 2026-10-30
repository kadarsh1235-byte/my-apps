package com.adarsh.voxnoxfx

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Foreground service of type "microphone": required on Android 11 to keep capturing off-screen. */
class VoxService : Service() {
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            Engine.stop(); stopForeground(true); stopSelf(); return START_NOT_STICKY
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("vox", "VoxNox FX", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "vox")
            .setContentTitle("VoxNox FX — microphone is ON")
            .setContentText("Processing your voice locally. Tap to open.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 30) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, n)
        Engine.onEnded = { stopForeground(true); stopSelf() }
        Engine.start(applicationContext)
        return START_NOT_STICKY
    }

    override fun onDestroy() { Engine.onEnded = null; Engine.stop(); super.onDestroy() }
}
