package com.qingyue.reader;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 百度翻译的语音合成接口（免费、无需 key），作为 Edge TTS 之外的第二条在线音色。
 * 纯 HTTP GET 返回 mp3，再用系统 MediaPlayer 播放，不涉及 native 库。
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

    private volatile MediaPlayer player;
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
                ResponseBody body = resp.body();
                String type = resp.header("Content-Type", "");
                if (!resp.isSuccessful() || body == null) {
                    lastError = "BaiduTts:" + resp.code();
                    cb.onResult(false);
                    return;
                }
                if (type != null && type.contains("json")) {
                    lastError = "BaiduTts:文本过长";
                    cb.onResult(false);
                    return;
                }
                byte[] mp3 = body.bytes();
                if (stopRequested) { cb.onResult(false); return; }
                if (mp3.length < 1000) {
                    lastError = "BaiduTts:音频为空";
                    cb.onResult(false);
                    return;
                }
                play(mp3, cb);
            } catch (Throwable t) {
                lastError = "BaiduTts:" + t;
                Log.w(TAG, "speak failed " + t);
                if (!stopRequested) cb.onResult(false);
            }
        }, "qingyue-baidu-tts").start();
    }

    private void play(byte[] mp3, Callback cb) {
        try {
            File f = new File(ctx.getCacheDir(), "baidu_tts.mp3");
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(mp3);
            }
            main.post(() -> {
                try {
                    release();
                    MediaPlayer p = new MediaPlayer();
                    player = p;
                    p.setDataSource(f.getAbsolutePath());
                    p.setOnCompletionListener(mp -> {
                        release();
                        cb.onResult(true);
                    });
                    p.setOnErrorListener((mp, what, extra) -> {
                        lastError = "MediaPlayer:" + what + "/" + extra;
                        release();
                        cb.onResult(false);
                        return true;
                    });
                    p.prepare();
                    p.start();
                } catch (Throwable t) {
                    lastError = "play:" + t;
                    cb.onResult(false);
                }
            });
        } catch (Throwable t) {
            lastError = "write:" + t;
            cb.onResult(false);
        }
    }

    private void release() {
        try {
            MediaPlayer p = player;
            player = null;
            if (p != null) {
                try { if (p.isPlaying()) p.stop(); } catch (Exception ignore) {}
                p.reset();
                p.release();
            }
        } catch (Exception ignore) {}
    }

    public void stop() {
        stopRequested = true;
        main.post(this::release);
    }

    public void shutdown() {
        stop();
        release();
    }
}
