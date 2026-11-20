package com.antiscam.guard

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Notifier {
    private const val CH_ALERT = "caller_alerts"
    private const val CH_STATUS = "call_check_status"
    private const val ID_STATUS = 1
    private const val ID_ALERT = 2

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Caller alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Warnings about who is on a video call"
                enableVibration(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "Call check in progress", NotificationManager.IMPORTANCE_LOW)
        )
    }

    fun canPost(ctx: Context): Boolean =
        NotificationManagerCompat.from(ctx).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    fun status(ctx: Context, text: String) = post(ctx, ID_STATUS,
        NotificationCompat.Builder(ctx, CH_STATUS)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Checking this video call")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW))

    fun clearStatus(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(ID_STATUS)

    fun alert(ctx: Context, title: String, text: String, urgent: Boolean) = post(ctx, ID_ALERT,
        NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setColor(if (urgent) 0xFFDC2626.toInt() else 0xFFF59E0B.toInt())
            .setColorized(urgent)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(if (urgent) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true))

    private fun post(ctx: Context, id: Int, b: NotificationCompat.Builder) {
        if (!canPost(ctx)) return
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE)
        try {
            NotificationManagerCompat.from(ctx).notify(id, b.setContentIntent(open).build())
        } catch (_: SecurityException) { /* permission revoked mid-flight */ }
    }
}
