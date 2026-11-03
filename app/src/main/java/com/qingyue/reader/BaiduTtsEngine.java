package com.qingyue.reader;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.PowerManager;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URLEncoder;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 百度翻译的语音合成接口（免费、无需 key），作为 Edge TTS 之外的第二条在线音色。
 * 纯 HTTP GET 返回 mp3，再用系统 MediaPlayer 播放，不涉及 native 库。
 *
 * v1.20：与 Edge 引擎一致引入会话代际 seq + 独立临时文件，
 * 避免慢请求迟到后被当成当前句播放（两路声音重叠）。
 */
public class BaiduTtsEngine {
    private static final String TAG = "QingyueBaiduTts";
    private static final String BASE = "https://fanyi.baidu.com/gettts";

    private final Context ctx;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build();
    private final Handler main = new Handler(Looper.getMainLooper());

    private final AtomicInteger seq = new AtomicInteger(0);
    private volatile MediaPlayer player;
    private volatile int playerGen = -1;
    private volatile boolean stopRequested = false;
    private volatile String lastError = "";

    public BaiduTtsEngine(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public String getLastError() {
        return lastError == null ? "" : lastError;
    }

    public interface Callback {
        void onResult(boolean ok);
    }

    public void speak(String text, float rate, Callback cb) {
        final int my = seq.incrementAndGet();
        stopRequested = false;
        lastError = "";
        String clean = text == null ? "" : text.trim();
        if (clean.isEmpty()) {
            cb.onResult(true);
            return;
        }
        // spd 取值范围 1~15，5 为默认语速
        int spd = Math.max(1, Math.min(15, Math.round(5 * (rate <= 0 ? 1f : rate))));
        final String url;
        try {
            url = BASE + "?lan=zh&text=" + URLEncoder.encode(clean, "UTF-8")
                    + "&spd=" + spd + "&source=web";
        } catch (Exception e) {
            lastError = "url:" + e;
            cb.onResult(false);
            return;
        }
        Request req = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                        + " (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0")
                .header("Referer", "https://fanyi.baidu.com/")
                .build();
        new Thread(() -> {
            try (Response resp = client.newCall(req).execute()) {
                if (my != seq.get()) return;                 // 请求期间已被切句/停止
                ResponseBody body = resp.body();
                String type = resp.header("Content-Type", "");
                if (!resp.isSuccessful() || body == null) {
                    lastError = "BaiduTts:" + resp.code();
                    if (my == seq.get()) cb.onResult(false);
                    return;
                }
                if (type != null && type.contains("json")) {
                    lastError = "BaiduTts:文本过长";
                    if (my == seq.get()) cb.onResult(false);
                    return;
                }
                byte[] mp3 = body.bytes();
                if (my != seq.get() || stopRequested) return;
                if (mp3.length < 1000) {
                    lastError = "BaiduTts:音频为空";
                    cb.onResult(false);
                    return;
                }
                play(mp3, cb, my);
            } catch (Throwable t) {
                lastError = "BaiduTts:" + t;
                Log.w(TAG, "speak failed " + t);
                if (my == seq.get() && !stopRequested) cb.onResult(false);
            }
        }, "qingyue-baidu-tts").start();
    }

    private void play(byte[] mp3, Callback cb, final int my) {
        if (my != seq.get()) return;
        final File f = new File(ctx.getCacheDir(), "baidu_tts_" + my + ".mp3");
        try {
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(mp3);
            }
        } catch (Throwable t) {
            lastError = "write:" + t;
            if (my == seq.get()) cb.onResult(false);
            return;
        }
        main.post(() -> {
            if (my != seq.get()) { deleteQuiet(f); return; }
            try {
                release();                                   // 先停旧的一路，避免重叠
                MediaPlayer p = new MediaPlayer();
                player = p;
                playerGen = my;
                // 播放期间由 MediaPlayer 自己持 PARTIAL_WAKE_LOCK：
                // 比服务层的 WakeLock 更贴合，锁屏后音频回调不容易被系统掐断
                try { p.setWakeMode(ctx, PowerManager.PARTIAL_WAKE_LOCK); } catch (Throwable ignore) { }
                try {
                    p.setAudioAttributes(new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build());
                } catch (Throwable ignore) { }
                p.setDataSource(f.getAbsolutePath());
                p.setOnCompletionListener(mp -> {
                    if (my != seq.get()) return;
                    release();
                    deleteQuiet(f);
                    cb.onResult(true);
                });
                p.setOnErrorListener((mp, what, extra) -> {
                    lastError = "MediaPlayer:" + what + "/" + extra;
                    if (my == seq.get()) release();
                    deleteQuiet(f);
                    if (my == seq.get()) cb.onResult(false);
                    return true;
                });
                p.prepare();
                p.start();
            } catch (Throwable t) {
                lastError = "play:" + t;
                release();
                deleteQuiet(f);
                if (my == seq.get()) cb.onResult(false);
            }
        });
    }

    private void release() {
        try {
            MediaPlayer p = player;
            player = null;
            playerGen = -1;
            if (p != null) {
                try { if (p.isPlaying()) p.stop(); } catch (Exception ignore) {}
                p.reset();
                p.release();
            }
        } catch (Exception ignore) {}
    }

    private static void deleteQuiet(File f) {
        try { if (f != null && f.exists()) f.delete(); } catch (Throwable ignore) {}
    }

    public void stop() {
        seq.incrementAndGet();
        stopRequested = true;
        main.post(() -> {
            if (playerGen != seq.get()) release();
        });
    }

    public void shutdown() {
        stop();
        main.post(this::release);
    }
}
