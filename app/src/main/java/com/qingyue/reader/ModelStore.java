package com.qingyue.reader;

/**
 * 原「离线语音包（78MB 模型）」的存储管理。
 *
 * 已随离线 native 引擎一并移除：下载、解压 78MB 会带来额外崩溃与占用，
 * 而该引擎在部分机型上加载即崩溃。保留同名方法，返回「未安装」，兼容上层调用。
 */
public class ModelStore {

    public static boolean isInstalled(android.content.Context ctx) {
        return false;
    }

    public static long usedBytes(android.content.Context ctx) {
        return 0L;
    }

    public static void clear(android.content.Context ctx) {
    }
}
