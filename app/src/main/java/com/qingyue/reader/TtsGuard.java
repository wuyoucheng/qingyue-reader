package com.qingyue.reader;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.util.List;

/**
 * 离线语音崩溃熔断。
 *
 * 内置引擎在个别机型上会在加载/合成阶段把进程干掉（native crash 拦不住），
 * 表现为「打开就闪退、一直闪退」。这里用两层检测在下次启动时自动停用内置语音：
 *   1. 朗读标记：speak 开始置位、结束清除；启动时发现残留 → 上次朗读时崩了
 *   2. ApplicationExitInfo（Android 11+）：本进程上次退出原因是崩溃/低内存
 * 停用后 App 照常可用（系统语音兜底），用户可在听书面板手动重新启用。
 */
public class TtsGuard {

    private static final String TAG = "TtsGuard";
    private static final String PREFS = "qingyue_tts";

    public static final String KEY_ENABLED = "tts_enabled";   // 默认 true
    public static final String KEY_REASON = "tts_disable_reason";
    public static final String KEY_SPEAKING = "speaking_flag";

    public static SharedPreferences sp(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 启动时调用：检查上次退出原因，必要时熔断 */
    public static void check(Context ctx) {
        SharedPreferences sp = sp(ctx);
        if (sp.getBoolean(KEY_SPEAKING, false)) {
            disable(ctx, "上次朗读语音时异常退出，已自动停用离线语音（可点「重新启用」再试）");
        }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
                List<ApplicationExitInfo> l =
                        am.getHistoricalProcessExitReasons(ctx.getPackageName(), 0, 3);
                for (ApplicationExitInfo ei : l) {
                    int r = ei.getReason();
                    boolean bad = r == ApplicationExitInfo.REASON_CRASH
                            || r == ApplicationExitInfo.REASON_CRASH_NATIVE
                            || r == ApplicationExitInfo.REASON_LOW_MEMORY;
                    if (!bad) continue;
                    String desc = ei.getDescription();
                    String msg = "检测到上次异常退出（" + exitName(r)
                            + (desc == null || desc.isEmpty() ? "" : "：" + desc)
                            + "），已自动停用离线语音，改用系统语音；可在下方点「重新启用」再试";
                    disable(ctx, msg);
                    Log.w(TAG, msg);
                    break;
                }
            } catch (Throwable t) {
                Log.w(TAG, "exit info: " + t);
            }
        }
    }

    public static void disable(Context ctx, String reason) {
        sp(ctx).edit().putBoolean(KEY_ENABLED, false).putString(KEY_REASON, reason).apply();
    }

    public static void enable(Context ctx) {
        sp(ctx).edit().putBoolean(KEY_ENABLED, true).remove(KEY_REASON)
                .putBoolean(KEY_SPEAKING, false).apply();
    }

    public static boolean isEnabled(Context ctx) {
        return sp(ctx).getBoolean(KEY_ENABLED, true);
    }

    public static String getReason(Context ctx) {
        return sp(ctx).getString(KEY_REASON, "");
    }

    private static String exitName(int r) {
        switch (r) {
            case ApplicationExitInfo.REASON_CRASH: return "Java 崩溃";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "原生层崩溃";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "内存不足被杀";
            default: return "原因码 " + r;
        }
    }
}
