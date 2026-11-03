package com.qingyue.reader;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.PowerManager;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * 微软 Edge TTS 在线合成（免费、无需 key）。
 *
 * 鉴权用 Sec-MS-GEC 令牌，算法已升级到 v2（CS64 + 微软变体 SHA256），
 * 旧版 v1（纯 SHA256）目前一律返回 403。实现见 {@link EdgeGec}。
 *
 * 纯 Java + OkHttp + MediaPlayer，不涉及任何 native 库，不会造成进程崩溃。
 *
 * v1.20：引入「会话代际」seq。切句/停止时 seq++，所有在途的 WebSocket 帧、
 * 播放回调、错误回调一律按代际作废；每句话写独立的临时 mp3。
 * 这两点是「有时两个声音一起读」的根因：旧句子合成慢，迟到返回的音频
 * 会被当成当前句播放，与当前句重叠。
 */
public class EdgeTtsEngine {
    private static final String TAG = "QingyueEdgeTts";

    private static final String TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
    private static final String WSS_URL =
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1";
    // 版本号必须与 v2 令牌配套，否则同样 403
    private static final String CHROMIUM_FULL_VERSION = "143.0.3650.75";
    private static final String SEC_MS_GEC_VERSION = "2-" + CHROMIUM_FULL_VERSION;
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0";
    private static final String ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold";
    private static final String OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3";

    private final Context ctx;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build();
    private final Handler main = new Handler(Looper.getMainLooper());

    /** 每次 speak/stop 自增；回调只有代际匹配才生效 */
    private final AtomicInteger seq = new AtomicInteger(0);

    private volatile WebSocket ws;
    private volatile MediaPlayer player;
    private volatile int playerGen = -1;
    private volatile boolean stopRequested = false;
    private volatile String lastError = "";

    public EdgeTtsEngine(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        cleanupCache(this.ctx);
    }

    /** 每句一个临时 mp3，启动时清掉上次残留（被打断的那句不会被 onCompletion 删掉） */
    private static void cleanupCache(Context ctx) {
        try {
            File dir = ctx.getCacheDir();
            File[] fs = dir == null ? null : dir.listFiles();
            if (fs == null) return;
            long now = System.currentTimeMillis();
            for (File f : fs) {
                String n = f.getName();
                if (!n.startsWith("edge_tts_") && !n.startsWith("baidu_tts_")) continue;
                if (now - f.lastModified() > 60 * 60 * 1000L) f.delete();
            }
        } catch (Throwable ignore) { }
    }

    public String getLastError() {
        return lastError == null ? "" : lastError;
    }

    public interface Callback {
        void onResult(boolean ok);
    }

    /** 合成并朗读一句；voice 传空用晓晓 */
    public void speak(String text, String voice, float rate, Callback cb) {
        final int my = seq.incrementAndGet();
        connect(text, voice, rate, cb, false, my);
    }

    private void connect(String text, String voice, float rate, Callback cb, boolean retried, final int my) {
        stopRequested = false;
        lastError = "";
        final String v = (voice == null || voice.isEmpty()) ? "zh-CN-XiaoxiaoNeural" : voice;
        final ByteArrayOutputStream audio = new ByteArrayOutputStream();
        final String clean = text == null ? "" : text.trim();
        if (clean.isEmpty()) {
            cb.onResult(true);
            return;
        }
        final String url = WSS_URL + "?TrustedClientToken=" + TRUSTED_CLIENT_TOKEN
                + "&Sec-MS-GEC=" + EdgeGec.generateSecMsGec(System.currentTimeMillis())
                + "&Sec-MS-GEC-Version=" + SEC_MS_GEC_VERSION
                + "&ConnectionId=" + UUID.randomUUID().toString().replace("-", "");
        Request req = new Request.Builder()
                .url(url)
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .header("Origin", ORIGIN)
                .header("Sec-WebSocket-Version", "13")
                .header("User-Agent", UA)
                .header("Accept-Encoding", "gzip, deflate, br")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cookie", "muid=" + randomHex32() + ";")
                .build();

        client.newWebSocket(req, new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                if (my != seq.get()) { try { webSocket.close(1000, "stale"); } catch (Exception ignore) {} return; }
                ws = webSocket;
                String ts = dateStr();
                // 1) 配置帧（Path 必须是 speech.config，写成 ssml 会被服务端判为非法请求）
                String cfg = "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{"
                        + "\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},"
                        + "\"outputFormat\":\"" + OUTPUT_FORMAT + "\"}}}}";
                webSocket.send("X-Timestamp:" + ts + "\r\n"
                        + "Content-Type:application/json; charset=utf-8\r\n"
                        + "Path:speech.config\r\n\r\n" + cfg);
                // 2) 文本帧
                int pct = Math.round((rate - 1f) * 100);
                String ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='zh-CN'>"
                        + "<voice name='" + v + "'>"
                        + "<prosody pitch='+0Hz' rate='" + (pct >= 0 ? "+" : "") + pct + "%' volume='+0%'>"
                        + escapeXml(clean) + "</prosody></voice></speak>";
                webSocket.send("X-RequestId:" + UUID.randomUUID().toString().replace("-", "") + "\r\n"
                        + "X-Timestamp:" + ts + "\r\n"
                        + "Content-Type:application/ssml+xml\r\n"
                        + "Path:ssml\r\n\r\n" + ssml);
            }

            @Override public void onMessage(WebSocket webSocket, String text) {
                if (my != seq.get()) return;                  // 过期会话，丢弃
                if (text != null && text.contains("Path:turn.end")) {
                    webSocket.close(1000, "done");
                    playBytes(audio.toByteArray(), cb, my);
                }
            }

            @Override public void onMessage(WebSocket webSocket, okio.ByteString bytes) {
                if (my != seq.get()) return;
                byte[] arr = bytes.toByteArray();
                if (arr.length <= 2) return;
                int headerLen = ((arr[0] & 0xff) << 8) | (arr[1] & 0xff);
                if (arr.length < 2 + headerLen) return;
                String header = new String(arr, 2, headerLen, StandardCharsets.UTF_8);
                if (!header.contains("Path:audio")) return;
                audio.write(arr, 2 + headerLen, arr.length - 2 - headerLen);
            }

            @Override public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                if (my != seq.get()) return;
                int code = response != null ? response.code() : 0;
                // 403 常见于本机时间不准：令牌按 5 分钟窗口计算，偏差过大会被拒。
                // 用服务端 Date 头校正后重试一次。
                if (code == 403 && !retried && correctClock(response)) {
                    Log.w(TAG, "403, clock corrected by " + EdgeGec.getClockSkew() + "s, retry");
                    connect(clean, v, rate, cb, true, my);
                    return;
                }
                lastError = "EdgeTts:" + (code > 0 ? String.valueOf(code) : String.valueOf(t));
                Log.w(TAG, "ws failure " + lastError);
                if (!stopRequested) cb.onResult(false);
            }
        });
    }

    /** 用响应头里的服务端时间校正本地时钟偏差；成功返回 true */
    private static boolean correctClock(Response response) {
        try {
            String date = response.header("Date");
            if (date == null || date.isEmpty()) return false;
            SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
            long server = f.parse(date).getTime();
            double skew = (server - System.currentTimeMillis()) / 1000.0;
            if (Math.abs(skew) < 1) return false;
            EdgeGec.adjustClockSkew(skew);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "clock correct failed: " + t);
            return false;
        }
    }

    private void playBytes(byte[] mp3, Callback cb, final int my) {
        if (my != seq.get()) return;
        if (mp3 == null || mp3.length == 0) { cb.onResult(false); return; }
        final File f = new File(ctx.getCacheDir(), "edge_tts_" + my + ".mp3");
        try {
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(mp3);
            }
        } catch (Throwable t) {
            lastError = "write:" + t;
            cb.onResult(false);
            return;
        }
        main.post(() -> {
            if (my != seq.get()) { deleteQuiet(f); return; }   // 排队期间已被切句
            try {
                releasePlayer();                                // 先停掉上一代，绝不允许两路同时出声
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
                    if (my != seq.get()) return;                // 旧句播完，不要推进队列
                    releasePlayer();
                    deleteQuiet(f);
                    cb.onResult(true);
                });
                p.setOnErrorListener((mp, what, extra) -> {
                    lastError = "MediaPlayer:" + what + "/" + extra;
                    if (my == seq.get()) releasePlayer();
                    deleteQuiet(f);
                    if (my == seq.get()) cb.onResult(false);
                    return true;
                });
                p.prepare();
                p.start();
            } catch (Throwable t) {
                lastError = "play:" + t;
                Log.w(TAG, "play failed " + t);
                releasePlayer();
                deleteQuiet(f);
                if (my == seq.get()) cb.onResult(false);
            }
        });
    }

    private void releasePlayer() {
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

    /** 立即作废当前会话：在途的合成、迟到的音频、旧的播放器全部不再生效 */
    public void stop() {
        seq.incrementAndGet();
        stopRequested = true;
        final WebSocket old = ws;
        ws = null;
        try { if (old != null) old.close(1000, "stop"); } catch (Exception ignore) {}
        main.post(() -> {
            // 只停掉旧代际的播放器（stop 之后立刻开新句时，新播放器不能被误停）
            if (playerGen >= 0 && playerGen < seq.get()) releasePlayer();
            else if (playerGen < 0) releasePlayer();
        });
    }

    public void shutdown() {
        stop();
        main.post(this::releasePlayer);
    }

    private static String randomHex32() {
        StringBuilder sb = new StringBuilder(32);
        java.util.Random r = new java.util.Random();
        for (int i = 0; i < 32; i++) sb.append(Integer.toHexString(r.nextInt(16)).toUpperCase(Locale.US));
        return sb.toString();
    }

    private static String dateStr() {
        SimpleDateFormat f = new SimpleDateFormat(
                "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("GMT"));
        return f.format(new Date());
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
