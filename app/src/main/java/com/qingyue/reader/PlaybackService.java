package com.qingyue.reader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

/**
 * 听书前台服务：锁屏 / 切后台时保持朗读继续。
 *
 * 做三件事：
 *  1) startForeground —— 让进程不被系统回收，通知栏显示「正在朗读」并可一键停止
 *  2) PARTIAL_WAKE_LOCK —— 锁屏后 CPU 不休眠，联网合成（Edge TTS）与 MediaPlayer 才能继续跑
 *  3) WifiLock —— 避免锁屏后 WiFi 被省电策略降频导致合成超时
 *
 * 服务本身不持有播放器：播放状态由 NativeTTS 单例持有，这里只做保活与通知。
 */
public class PlaybackService extends Service {

    private static final String TAG = "PlaybackService";
    private static final String CHANNEL_ID = "qingyue_playback";
    private static final int NOTI_ID = 1001;

    public static final String ACTION_START = "com.qingyue.reader.action.PLAY_START";
    public static final String ACTION_UPDATE = "com.qingyue.reader.action.PLAY_UPDATE";
    public static final String ACTION_STOP = "com.qingyue.reader.action.PLAY_STOP";
    public static final String ACTION_PAUSE = "com.qingyue.reader.action.PLAY_PAUSE";
    public static final String ACTION_RESUME = "com.qingyue.reader.action.PLAY_RESUME";

    public static final String EXTRA_TITLE = "title";     // 书名 · 章节
    public static final String EXTRA_TEXT = "text";       // 当前句 / 进度
    public static final String EXTRA_PLAYING = "playing"; // 是否正在播放（否则为暂停）

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    /**
     * 耳机拔出 / 蓝牙耳机断开监听。
     *
     * 系统在「正在通过耳机出声的媒体应用」失去输出设备时会广播 ACTION_AUDIO_BECOMING_NOISY，
     * 但它**不会**替应用暂停播放，必须应用自己处理，否则声音会突然从外放放出来
     * （地铁/办公室场景非常尴尬）。Edge / 百度 / 系统语音都是媒体流，
     * 一个接收器就能全部覆盖。
     */
    private BroadcastReceiver noisyReceiver;

    /** 前台服务是否真的生效（失败时锁屏大概率被冻结，用于诊断） */
    public static volatile boolean foregroundOk = false;
    public static volatile String lastError = "";

    /* ---------------- 对外静态入口 ---------------- */

    public static void start(Context ctx) {
        try {
            Intent i = new Intent(ctx, PlaybackService.class).setAction(ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Throwable t) {
            Log.w(TAG, "start: " + t);
        }
    }

    /** 更新通知内容（每次换句调用一次，节流交给调用方） */
    public static void update(Context ctx, String title, String text, boolean playing) {
        try {
            Intent i = new Intent(ctx, PlaybackService.class)
                    .setAction(ACTION_UPDATE)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_TEXT, text)
                    .putExtra(EXTRA_PLAYING, playing);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Throwable t) {
            Log.w(TAG, "update: " + t);
        }
    }

    public static void stop(Context ctx) {
        try {
            Intent i = new Intent(ctx, PlaybackService.class).setAction(ACTION_STOP);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Throwable t) {
            Log.w(TAG, "stop: " + t);
        }
    }

    /* ---------------- 生命周期 ---------------- */

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 系统重建服务（intent 为空）时播放状态已随进程消失，没有续播的意义
        if (intent == null) { stopSelfSafely(); return START_NOT_STICKY; }
        String action = String.valueOf(intent.getAction());

        if (ACTION_STOP.equals(action)) {
            // 通知栏「停止」按钮：同步让播放层停下来，再退出服务
            try {
                NativeTTS t = NativeTTS.instance();
                if (t != null) t.stopQueueFromService();   // 注意：不能调 stopQueue()，否则会再次回调本服务形成死循环
            } catch (Throwable ignore) { }
            // 即便只是来停止，也必须先 startForeground 满足「5 秒内必须前台」的规则，
            // 否则系统会抛 ForegroundServiceDidNotStartInTimeException
            try { startForegroundCompat("轻阅", "已停止", false); } catch (Throwable ignore) { }
            stopSelfSafely();
            return START_NOT_STICKY;
        }

        // 通知栏「暂停 / 继续」：拔掉耳机自动暂停后，插回耳机不必再打开 App
        if (ACTION_PAUSE.equals(action) || ACTION_RESUME.equals(action)) {
            boolean resume = ACTION_RESUME.equals(action);
            try {
                NativeTTS t = NativeTTS.instance();
                if (t != null) {
                    if (resume) t.resumeQueue(); else t.pauseQueue();
                }
            } catch (Throwable ignore) { }
            String tt = safe(intent.getStringExtra(EXTRA_TITLE));
            String tx = safe(intent.getStringExtra(EXTRA_TEXT));
            startForegroundCompat(tt, tx, resume);
            acquireLocks();
            registerNoisyReceiver();
            return START_STICKY;
        }

        String title = intent.getStringExtra(EXTRA_TITLE) == null ? "" : safe(intent.getStringExtra(EXTRA_TITLE));
        String text = intent.getStringExtra(EXTRA_TEXT) == null ? "" : safe(intent.getStringExtra(EXTRA_TEXT));
        boolean playing = intent.getBooleanExtra(EXTRA_PLAYING, true);

        startForegroundCompat(title, text, playing);
        acquireLocks();
        registerNoisyReceiver();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy（锁屏保活结束）");
        unregisterNoisyReceiver();
        releaseLocks();
        super.onDestroy();
    }

    /* ---------------- 耳机断开自动暂停 ---------------- */

    private void registerNoisyReceiver() {
        if (noisyReceiver != null) return;         // 服务被多次 start 时只注册一次
        noisyReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || !AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) return;
                Log.i(TAG, "耳机断开，自动暂停朗读");
                try {
                    NativeTTS t = NativeTTS.instance();
                    if (t != null) t.pauseForNoisy();
                } catch (Throwable ignore) { }
                // 服务与通知都保留：插回耳机后点「继续」即可接着听
            }
        };
        IntentFilter f = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // 系统广播在 RECEIVER_NOT_EXPORTED 下依然能收到
                registerReceiver(noisyReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(noisyReceiver, f);
            }
        } catch (Throwable t) {
            // 个别 ROM 对 NOT_EXPORTED 处理异常：退回无 flag 的注册方式
            try {
                registerReceiver(noisyReceiver, f);
            } catch (Throwable t2) {
                Log.w(TAG, "registerReceiver: " + t2);
                noisyReceiver = null;
            }
        }
    }

    private void unregisterNoisyReceiver() {
        if (noisyReceiver == null) return;
        try { unregisterReceiver(noisyReceiver); } catch (Throwable ignore) { }
        noisyReceiver = null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 用户从最近任务里划掉：不要顺带停掉朗读
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /* ---------------- 通知 ---------------- */

    private void startForegroundCompat(String title, String text, boolean playing) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "听书播放", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("朗读时的常驻通知，锁屏后保持播放");
                ch.setShowBadge(false);
                ch.setSound(null, null);
                ch.enableVibration(false);
                nm.createNotificationChannel(ch);
            }

            Intent open = new Intent(this, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) piFlags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent contentPi = PendingIntent.getActivity(this, 0, open, piFlags);

            PendingIntent stopPi = PendingIntent.getService(this, 1,
                    new Intent(this, PlaybackService.class).setAction(ACTION_STOP), piFlags);
            // 暂停/继续：带着当前文案，切换时通知不会变成空白
            PendingIntent togglePi = PendingIntent.getService(this, playing ? 2 : 3,
                    new Intent(this, PlaybackService.class)
                            .setAction(playing ? ACTION_PAUSE : ACTION_RESUME)
                            .putExtra(EXTRA_TITLE, title)
                            .putExtra(EXTRA_TEXT, text), piFlags);

            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                b = new Notification.Builder(this, CHANNEL_ID);
            } else {
                b = new Notification.Builder(this);
                b.setPriority(Notification.PRIORITY_LOW);
            }
            b.setContentTitle(title.isEmpty() ? "轻阅 · 正在朗读" : title)
                    .setContentText(text.isEmpty() ? "轻阅听书" : text)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentIntent(contentPi)
                    .setOngoing(playing)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .addAction(new Notification.Action.Builder(
                            playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                            playing ? "暂停" : "继续", togglePi).build())
                    .addAction(new Notification.Action.Builder(
                            android.R.drawable.ic_delete, "停止", stopPi).build());

            Notification n = b.build();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTI_ID, n);
            }
            foregroundOk = true;
        } catch (Throwable t) {
            foregroundOk = false;
            lastError = String.valueOf(t);
            Log.w(TAG, "startForeground failed: " + t);
        }
    }

    private void stopSelfSafely() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                //noinspection deprecation
                stopForeground(true);
            }
        } catch (Throwable ignore) { }
        stopSelf();
    }

    /* ---------------- 唤醒锁 ---------------- */

    private void acquireLocks() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "qingyue:tts");
                    wakeLock.setReferenceCounted(false);
                }
            }
            // 不加超时：带超时的锁在长时听到 3 小时后会静默失效，
            // 表现就是「听很久之后锁屏会停」。释放由 stop / onDestroy 负责。
            if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
        } catch (Throwable t) {
            Log.w(TAG, "wakeLock: " + t);
        }
        try {
            if (wifiLock == null) {
                WifiManager wm = (WifiManager) getApplicationContext()
                        .getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "qingyue:tts");
                    wifiLock.setReferenceCounted(false);
                }
            }
            if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();
        } catch (Throwable t) {
            // 缺少 CHANGE_WIFI_STATE 或机型限制，忽略即可（有 WakeLock 通常也够）
            Log.w(TAG, "wifiLock: " + t);
        }
    }

    private void releaseLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignore) { }
        wakeLock = null;
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Throwable ignore) { }
        wifiLock = null;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
