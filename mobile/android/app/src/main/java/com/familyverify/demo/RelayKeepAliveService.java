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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Owns this phone's connection to the family relay (the stand-in for FCM push).
 *
 * Why native: while another app (WhatsApp) is in front, Android freezes background apps, and even when it doesn't,
 * the WebView puts the hidden page to sleep after a few minutes, so a WebSocket in JavaScript stops handling messages.
 * This foreground service keeps the socket here instead, answers the relay's pings, reconnects by itself, and pops up
 * a notification for "Are you calling Mum?" and family alerts when the app isn't on screen. Every message is also
 * handed to the web page (NativeRelayPlugin), which does the real work: signature checks, the Yes/No + fingerprint.
 *
 * Alert pop-ups are deliberately generic: signatures are checked in the app, so a forged alert must never show its
 * text here before it's verified.
 */
public class RelayKeepAliveService extends Service {
    interface Listener {
        void onRelayMessage(String text);
        void onRelayState(boolean open, int code, String reason);
    }

    private static final String CH_KEEP = "fv-keepalive";
    private static final String CH_REQ = "fv-requests";
    private static final int ID_KEEP = 4711;
    static final int ID_PING = 4712;
    static final int ID_ALERT = 4713;

    // shared with NativeRelayPlugin / MainActivity (same process, guarded by the main thread)
    static volatile RelayKeepAliveService instance;
    static volatile Listener listener;
    static volatile boolean appVisible = true;
    private static String url;
    private static String hello;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)   // notices a dead network even if the relay goes quiet
            .build();
    private WebSocket ws;
    private boolean open;
    private int generation;
    private String connectedUrl;

    // ------------------------------------------------------------------ API for the plugin / activity
    static void start(Context ctx) {
        Intent i = new Intent(ctx, RelayKeepAliveService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Exception ignored) {
            // not allowed to start right now; the plugin retries on the next connect()
        }
    }

    static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, RelayKeepAliveService.class));
    }

    /** Called by the web page: where to connect and what to say first (role + public key). */
    static void configure(Context ctx, String newUrl, String newHello) {
        new Handler(Looper.getMainLooper()).post(() -> {
            boolean sameUrl = newUrl.equals(url);
            boolean sameHello = newHello.equals(hello);
            url = newUrl;
            hello = newHello;
            RelayKeepAliveService s = instance;
            if (s == null) { start(ctx); return; }   // onStartCommand will connect
            if (sameUrl && s.open && s.ws != null) {
                // page reloaded but the socket is fine: say hello again (refreshes peers) unless the role changed
                if (sameHello) { s.ws.send(hello); s.emitState(true, 0, ""); }
                else s.reconnect(0);
            } else {
                s.reconnect(0);
            }
        });
    }

    static boolean sendText(String text) {
        RelayKeepAliveService s = instance;
        return s != null && s.open && s.ws != null && s.ws.send(text);
    }

    static void disconnect() {
        new Handler(Looper.getMainLooper()).post(() -> {
            url = null;
            hello = null;
            RelayKeepAliveService s = instance;
            if (s != null) s.closeSocket("role change");
        });
    }

    static void clearPopups(Context ctx) {
        NotificationManagerCompat nm = NotificationManagerCompat.from(ctx);
        nm.cancel(ID_PING);
        nm.cancel(ID_ALERT);
    }

    // ------------------------------------------------------------------ service lifecycle
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createChannels();
        PendingIntent openApp = openAppIntent();
        Notification n = new NotificationCompat.Builder(this, CH_KEEP)
                .setSmallIcon(R.drawable.ic_stat_shield)
                .setContentTitle("Family Verify is connected")
                .setContentText("Ready to confirm calls to your family.")
                .setContentIntent(openApp)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID_KEEP, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING);
            else startForeground(ID_KEEP, n);
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (url != null && ws == null) reconnect(0);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        closeSocket("service stopped");
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------ the socket (main thread)
    private void reconnect(long delayMs) {
        final int gen = ++generation;
        main.postDelayed(() -> {
            if (gen != generation || url == null) return;
            if (ws != null) { ws.cancel(); ws = null; }
            open = false;
            final String target = url.endsWith("/ws") ? url : url.replaceAll("/+$", "") + "/ws";
            Request req;
            try {
                req = new Request.Builder().url(target.replaceFirst("^ws", "http")).build();
            } catch (IllegalArgumentException e) {
                emitState(false, -1, "bad relay address");
                return;
            }
            connectedUrl = url;
            ws = http.newWebSocket(req, new WebSocketListener() {
                @Override public void onOpen(WebSocket socket, Response response) {
                    main.post(() -> {
                        if (gen != generation) return;
                        open = true;
                        if (hello != null) socket.send(hello);
                        emitState(true, 0, "");
                    });
                }

                @Override public void onMessage(WebSocket socket, String text) {
                    main.post(() -> {
                        if (gen != generation) return;
                        Listener l = listener;
                        if (l != null) l.onRelayMessage(text);
                        if (!appVisible) popupFor(text);
                    });
                }

                @Override public void onClosing(WebSocket socket, int code, String reason) {
                    socket.close(code, null);
                    main.post(() -> dropped(gen, code, reason));
                }

                @Override public void onFailure(WebSocket socket, Throwable t, Response response) {
                    main.post(() -> dropped(gen, -1, t.getMessage() == null ? "connection failed" : t.getMessage()));
                }
            });
        }, delayMs);
    }

    private void dropped(int gen, int code, String reason) {
        if (gen != generation) return;
        open = false;
        ws = null;
        emitState(false, code, reason);
        // 4000: another phone took this role on purpose. Don't fight over it; the page offers "reconnect".
        if (code != 4000 && url != null && url.equals(connectedUrl)) reconnect(1500);
    }

    private void closeSocket(String why) {
        generation++;
        open = false;
        if (ws != null) { ws.close(1000, why); ws = null; }
    }

    private void emitState(boolean isOpen, int code, String reason) {
        Listener l = listener;
        if (l != null) l.onRelayState(isOpen, code, reason);
    }

    // ------------------------------------------------------------------ pop-ups while the app isn't on screen
    private void popupFor(String text) {
        try {
            JSONObject m = new JSONObject(text);
            if (!"msg".equals(m.optString("t"))) return;
            JSONObject body = m.optJSONObject("body");
            if (body == null) return;
            String kind = body.optString("kind");
            if ("ping".equals(kind)) {
                popup(ID_PING, "Are you calling Mum right now?",
                        "Someone who looks like you is video-calling Mum. Tap to answer.", true);
            } else if ("alert".equals(kind)) {
                String level = "";
                JSONObject env = body.optJSONObject("env");
                if (env != null) {
                    try { level = new JSONObject(env.optString("body")).optString("level"); } catch (Exception ignored) { }
                }
                if ("log".equals(level)) return;   // silent entries stay silent
                popup(ID_ALERT, "urgent".equals(level) ? "🚨 Urgent family alert" : "Family Verify: news about Mum",
                        "Open to see it. Its signature is checked in the app first.", "urgent".equals(level));
            } else if ("guardian-call".equals(kind) || "guardian-warn".equals(kind)) {
                popup(ID_ALERT, "Message from your family", "Open Family Verify to see it.", true);
            }
        } catch (Exception ignored) {
            // not JSON / not for us
        }
    }

    private void popup(int id, String title, String text, boolean urgent) {
        Notification n = new NotificationCompat.Builder(this, CH_REQ)
                .setSmallIcon(R.drawable.ic_stat_shield)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(urgent ? NotificationCompat.CATEGORY_ALARM : NotificationCompat.CATEGORY_MESSAGE)
                .setVibrate(new long[]{0, 500, 200, 500, 200, 500})
                .build();
        try {
            NotificationManagerCompat.from(this).notify(id, n);
        } catch (SecurityException ignored) {
            // notifications not allowed; the in-app card still shows when the app is opened
        }
    }

    private PendingIntent openAppIntent() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel keep = new NotificationChannel(CH_KEEP, "Connected to family", NotificationManager.IMPORTANCE_MIN);
        keep.setShowBadge(false);
        nm.createNotificationChannel(keep);
        NotificationChannel req = new NotificationChannel(CH_REQ, "Family requests and alerts", NotificationManager.IMPORTANCE_HIGH);
        req.enableVibration(true);
        req.setVibrationPattern(new long[]{0, 500, 200, 500, 200, 500});
        nm.createNotificationChannel(req);
    }
}
