package com.example.m2ilda;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

/** Keeps the command bridge alive even when our activity is backgrounded. */
public class BridgeService extends Service {

    private static final String TAG = "BridgeService";
    private static final String CHANNEL_ID = "bridge";
    private static final int NOTIF_ID = 1;

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Bridge server", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE))
                    .createNotificationChannel(ch);
        }
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("M2ilda")
                .setContentText("Bridge server running (:5555/:5556)")
                .setSmallIcon(R.mipmap.ic_launcher)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, n);
        }
        BridgeServer.getInstance().start();
        Log.i(TAG, "Bridge service started");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        BridgeServer.getInstance().start();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
