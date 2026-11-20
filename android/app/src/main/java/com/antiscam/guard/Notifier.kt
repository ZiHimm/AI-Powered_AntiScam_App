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
    private const val CH_FAMILY = "family_requests"
    private const val CH_KEEP = "family_connection"
    private const val ID_PROMPT = 3
    private const val ID_FAMILY_ALERT = 4
    const val ID_KEEP = 5

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
        nm.createNotificationChannel(
            NotificationChannel(CH_FAMILY, "Family questions and alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "\"Are you calling Mum?\" and guardian alerts"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 150, 400, 150, 400)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_KEEP, "Family connection", NotificationManager.IMPORTANCE_MIN)
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

    // ---------------------------------------------------------------- family mode (Son's / Daughter's phone)

    private fun openFamily(ctx: Context) = PendingIntent.getActivity(
        ctx, 1, Intent(ctx, FamilyActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE)

    /** "Are you calling Mum?": pops up (heads-up, or full screen where allowed) and opens the family screen. */
    fun familyPrompt(ctx: Context, title: String, text: String) = postTo(ctx, ID_PROMPT,
        NotificationCompat.Builder(ctx, CH_FAMILY)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(openFamily(ctx), true)
            .setContentIntent(openFamily(ctx))
            .setAutoCancel(true))

    fun clearFamilyPrompt(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(ID_PROMPT)

    /** Called only AFTER the alert's signature, nonce and age were checked, so its text can be shown. */
    fun familyAlert(ctx: Context, title: String, text: String, urgent: Boolean) = postTo(ctx, ID_FAMILY_ALERT,
        NotificationCompat.Builder(ctx, CH_FAMILY)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setColor(if (urgent) 0xFFDC2626.toInt() else 0xFF2563EB.toInt())
            .setPriority(if (urgent) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openFamily(ctx))
            .setAutoCancel(true))

    /** The quiet "connected" notification a foreground service must show. */
    fun keepAlive(ctx: Context, text: String) = NotificationCompat.Builder(ctx, CH_KEEP)
        .setSmallIcon(R.drawable.ic_notif)
        .setContentTitle("Family alerts are on")
        .setContentText(text)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setContentIntent(openFamily(ctx))
        .build()

    private fun postTo(ctx: Context, id: Int, b: NotificationCompat.Builder) {
        if (!canPost(ctx)) return
        try { NotificationManagerCompat.from(ctx).notify(id, b.build()) } catch (_: SecurityException) {}
    }

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
