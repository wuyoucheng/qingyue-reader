package com.qingyue.reader;

/**
 * 原「离线语音包下载器」。离线引擎已移除，这里保留同名接口，启动即返回不可用。
 */
public class ModelDownloader {

    public interface Listener {
        void onProgress(int percent, long bytes, long total);
        void onDone(boolean ok, String message);
    }

    public void start(android.content.Context ctx, Listener l) {
        if (l != null) l.onDone(false, "离线语音包已不再需要，请使用在线音色「晓晓」或系统语音");
    }
}
