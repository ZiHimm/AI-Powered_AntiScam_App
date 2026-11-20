package com.familyverify.demo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

/**
 * Keeps the app's process un-frozen while it is in the background (e.g. while WhatsApp is in front),
 * so the WebView's WebSocket to the family relay keeps answering pings and messages keep arriving.
 * Without it, Android freezes the app within seconds and the relay drops the phone until it is reopened.
 *
 * It does no work itself: being a foreground service is what keeps the process alive.
 */
public class RelayKeepAliveService extends Service {
    private static final String CHANNEL = "fv-keepalive";
    private static final int ID = 4711;

    static void start(Context ctx) {
        Intent i = new Intent(ctx, RelayKeepAliveService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Exception ignored) {
            // e.g. not allowed to start from the background right now; the app still works in the foreground
        }
    }

    static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, RelayKeepAliveService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Connected to family", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_shield)
                .setContentTitle("Family Verify is connected")
                .setContentText("Ready to confirm calls to your family.")
                .setContentIntent(open)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING);
            } else {
                startForeground(ID, n);
            }
        } catch (Exception e) {
            stopSelf();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
