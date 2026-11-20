package com.antiscam.guard

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat

/**
 * Family mode only (Son's / Daughter's phone): keeps the relay connection alive while other apps (e.g. WhatsApp)
 * are in front, so "Are you calling Mum?" still pops up. Android would otherwise freeze the app in the background.
 * Same idea as Family Verify's RelayKeepAliveService. Mum's phone doesn't need this: its accessibility service
 * already keeps the app alive.
 */
class FamilyService : Service() {

    companion object {
        fun start(ctx: Context) {
            val i = Intent(ctx, FamilyService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, FamilyService::class.java))
    }

    private val main = Handler(Looper.getMainLooper())
    private var lastText = ""

    override fun onCreate() {
        super.onCreate()
        FamilyLink.init(this); MemberHub.init(this); Notifier.ensureChannels(this)
        ServiceCompat.startForeground(this, Notifier.ID_KEEP, Notifier.keepAlive(this, "Connecting…"),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0)
        FamilyLink.listener = MemberHub
        FamilyLink.start()
        main.post(refresh)
    }

    /** Keep the ongoing notification's text in sync with the connection. */
    private val refresh = object : Runnable {
        override fun run() {
            val t = if (FamilyLink.connected) "Connected · Mum ${if (FamilyLink.isOnline("mum")) "online" else "offline"}"
                    else FamilyLink.status
            if (t != lastText && Notifier.canPost(this@FamilyService)) {
                lastText = t
                try { NotificationManagerCompat.from(this@FamilyService).notify(Notifier.ID_KEEP, Notifier.keepAlive(this@FamilyService, t)) }
                catch (_: SecurityException) {}
            }
            main.postDelayed(this, 3000)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (FamilyLink.listener === MemberHub) FamilyLink.listener = null
        FamilyLink.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
