package com.qingyue.reader;

/**
 * 原「内置离线语音（sherpa-onnx + 小燕）」。
 *
 * 已移除：该 native 引擎在部分机型（含小米 HyperOS）加载即发生原生崩溃（SIGSEGV），
 * Java 层无法捕获；还需额外下载 78MB 语音包，下载/解压又会带来崩溃与占用。
 * 现在只有两条不涉 native 的路线：在线音色（Edge TTS）与系统 TTS。
 *
 * 本类保留同样的接口名，让上层调用点无需改动，但始终返回「不可用」。
 */
public class OfflineTtsEngine {

    public interface Callback {
        void onResult(boolean ok);
    }

    /** 保留兼容：历史上通过它查询模型状态 */
    public static boolean modelInstalled(android.content.Context ctx) {
        return false;
    }

    public boolean init(android.content.Context ctx) {
        return false;
    }

    public boolean isReady() {
        return false;
    }

    public void speak(String text, float rate, Callback cb) {
        if (cb != null) cb.onResult(false);
    }

    public void stop() {
    }

    public void shutdown() {
    }

    public String getLastError() {
        return "离线语音引擎已移除";
    }
}
