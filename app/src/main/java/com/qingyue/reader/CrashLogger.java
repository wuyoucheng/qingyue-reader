package com.qingyue.reader;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 崩溃/异常记录器：把未捕获异常、渲染进程崩溃等写入 filesDir/crash.log，
 * 下次启动时由 JS 通过 AndroidFile.lastCrash() 读出来展示，便于远程定位闪退原因。
 */
public class CrashLogger {

    private static final String TAG = "CrashLogger";
    private static final String FILE = "crash.log";

    /** 安装全局未捕获异常处理器（进程级闪退会留下堆栈） */
    public static void install(final Context ctx) {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                write(ctx, "未捕获异常 @ " + t.getName(), e);
            } catch (Throwable ignore) { }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    /** 记录一个可恢复的异常（例如渲染进程崩溃） */
    public static void log(Context ctx, String tag, Throwable e) {
        try {
            write(ctx, tag, e);
        } catch (Throwable ignore) { }
    }

    public static void logText(Context ctx, String tag, String msg) {
        try {
            File f = new File(ctx.getFilesDir(), FILE);
            try (FileWriter w = new FileWriter(f, true)) {
                w.write("[" + now() + "] " + tag + "\n" + msg + "\n\n");
            }
        } catch (Throwable ignore) { }
    }

    private static void write(Context ctx, String tag, Throwable e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        Log.w(TAG, tag + ": " + sw);
        logText(ctx, tag + " :: " + e, sw.toString());
    }

    /** 读取上一次记录的崩溃信息（没有则返回空串） */
    public static String read(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), FILE);
            if (!f.exists()) return "";
            long len = f.length();
            if (len <= 0) return "";
            // 只取末尾 4KB，避免过长
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r");
            long start = Math.max(0, len - 4096);
            raf.seek(start);
            byte[] buf = new byte[(int) (len - start)];
            raf.readFully(buf);
            raf.close();
            return new String(buf, "UTF-8");
        } catch (Throwable e) {
            return "";
        }
    }

    public static void clear(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), FILE);
            if (f.exists()) f.delete();
        } catch (Throwable ignore) { }
    }

    private static String now() {
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date());
    }
}
