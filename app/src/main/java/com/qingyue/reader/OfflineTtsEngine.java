package com.qingyue.reader;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;

/**
 * 内置离线中文 TTS：sherpa-onnx + vits-piper 中文女声（小燕）。
 * 模型随 APK 打包（assets/tts/huayan），不依赖系统语音引擎与联网。
 */
public class OfflineTtsEngine {

    private static final String TAG = "OfflineTts";

    public interface Callback { void onDone(boolean ok); }

    private OfflineTts tts;
    private volatile boolean playing = false;
    private volatile AudioTrack track;
    private Thread worker;
    private int sampleRate = 22050;

    /** 加载模型（首次约 1~2 秒），成功返回 true */
    public boolean init(Context ctx) {
        try {
            OfflineTtsVitsModelConfig vits = new OfflineTtsVitsModelConfig(
                    "tts/huayan/zh_CN-huayan-medium.onnx",  // model
                    "",                                      // lexicon（piper 用 espeak，不需要）
                    "tts/huayan/tokens.txt",                 // tokens
                    "tts/huayan/espeak-ng-data",             // dataDir（音素化数据）
                    "",                                      // dictDir
                    0.667f, 0.8f, 1.0f);
            OfflineTtsModelConfig mc = new OfflineTtsModelConfig(vits, null, null, 2, false, "cpu");
            OfflineTtsConfig cfg = new OfflineTtsConfig(mc, "", "", 1, 1.0f);
            long t0 = System.currentTimeMillis();
            tts = new OfflineTts(ctx.getAssets(), cfg);
            sampleRate = tts.sampleRate();
            Log.i(TAG, "init ok, sr=" + sampleRate + ", " + (System.currentTimeMillis() - t0) + "ms");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "init: " + t);
            tts = null;
            return false;
        }
    }

    public boolean isReady() { return tts != null; }

    public int getSampleRate() { return sampleRate; }

    /** 合成并播放一句；结束/失败/被停止后回调（合成在独立线程，回调线程随机） */
    public synchronized void speak(final String text, final float speed, final Callback cb) {
        stop();
        if (tts == null) { cb.onDone(false); return; }
        playing = true;
        worker = new Thread(() -> {
            boolean ok = false;
            final AudioTrack[] holder = new AudioTrack[1];
            try {
                holder[0] = new AudioTrack(
                        new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build(),
                        new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                                .setSampleRate(sampleRate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build(),
                        Math.max(sampleRate * 4, 65536),   // 缓冲：约 0.5s
                        AudioTrack.MODE_STREAM,
                        AudioManager.AUDIO_SESSION_ID_GENERATE);
                final AudioTrack t = holder[0];
                track = t;
                t.play();
                // 流式合成：每生成一块就写入播放缓冲；回调返回 0 让引擎停止生成
                tts.generateWithCallback(text, 0, speed <= 0 ? 1.0f : speed, (float[] chunk) -> {
                    if (!playing || t == null || t.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                        return Integer.valueOf(0);
                    }
                    t.write(chunk, 0, chunk.length, AudioTrack.WRITE_BLOCKING);
                    return Integer.valueOf(1);
                });
                ok = playing;
            } catch (Throwable e) {
                Log.w(TAG, "speak: " + e);
                ok = false;
            } finally {
                try { if (holder[0] != null) { holder[0].stop(); holder[0].release(); } } catch (Exception ignore) {}
                if (track == holder[0]) track = null;
                playing = false;
            }
            final boolean okF = ok;
            if (cb != null) cb.onDone(okF);
        }, "qingyue-tts");
        worker.start();
    }

    /** 立即停止当前播放与合成 */
    public synchronized void stop() {
        playing = false;
        AudioTrack t = track;
        if (t != null) {
            try { t.pause(); t.flush(); } catch (Exception ignore) {}
        }
        if (worker != null) worker.interrupt();
    }

    public void shutdown() {
        stop();
        try { if (tts != null) tts.release(); } catch (Exception ignore) {}
        tts = null;
    }
}
