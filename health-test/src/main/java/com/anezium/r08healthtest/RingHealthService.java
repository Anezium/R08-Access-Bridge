package com.anezium.r08healthtest;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import com.anezium.ringhealth.RingHealthBackend;
import com.anezium.ringhealth.RingHealthSnapshot;

public final class RingHealthService extends Service implements RingHealthBackend.Listener {
    private static final String CHANNEL_ID = "r08-health-connection";
    private static final int NOTIFICATION_ID = 808;
    private RingHealthBackend repository;

    public static void start(Context context) {
        Intent intent = new Intent(context, RingHealthService.class);
        context.startForegroundService(intent);
    }

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, notification("Подготовка подключения"));
        repository = RingHealthRuntime.repository(this);
        repository.addListener(this);
        repository.start();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        repository.start();
        return START_STICKY;
    }

    @Override public void onSnapshot(RingHealthSnapshot snapshot) {
        String text = snapshot.ringName + " · " + snapshot.connectionState;
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.notify(NOTIFICATION_ID, notification(text));
    }

    @Override public void onDestroy() {
        repository.removeListener(this);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Постоянное BLE-подключение к кольцу R08");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("R08 Health Test")
                .setContentText(text)
                .setContentIntent(pending)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }
}
