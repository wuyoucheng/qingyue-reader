package com.qingyue.reader;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Log;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 原生朗读桥接：把 Android TextToSpeech 暴露给网页 JS（window.AndroidTTS）。
 * 网页侧仍负责逐句切分与高亮，Java 侧只负责「说一句 + 回调完成事件」。
 *
 * 引擎策略：先用系统默认 TTS 引擎；若它不支持中文，自动遍历已安装的其它
 * TTS 引擎找到第一个支持中文的并切换；把「当前引擎 / 中文可用性 / 音色数」
 * 通过 window.__ttsState 推给网页展示与引导。
 */
public class NativeTTS {

    private static final String TAG = "NativeTTS";

    private final Activity activity;
    private final WebView webView;
    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private volatile boolean ready = false;
    private volatile boolean zhAvailable = false;
    private String engineName = "";        // 当前使用的引擎包名
    private float rate = 1.0f;
    private float pitch = 1.0f;
    private String voiceName = null;
    private boolean engineProbing = false; // 防止重复探测

    /* 内置离线引擎（sherpa-onnx + 小燕中文女声）：优先使用 */
    private final OfflineTtsEngine builtin = new OfflineTtsEngine();
    private volatile boolean builtinReady = false;
    private volatile String builtinErr = "";   // 内置引擎初始化失败原因（诊断用）
    private static final String BUILTIN_NAME = "小燕 · 离线女声";
    private static final String APP_VER = "v1.8";

    public NativeTTS(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        this.appContext = activity.getApplicationContext();

        // 内置引擎在后台加载模型（不阻塞 UI），成功后接管朗读
        new Thread(() -> {
            boolean ok;
            try {
                ok = builtin.init(appContext);
            } catch (Throwable t) {
                ok = false;
                builtinErr = String.valueOf(t);
            }
            builtinReady = ok;
            if (ok) {
                engineName = "内置离线语音";
                zhAvailable = true;
                ready = true;
                Log.i(TAG, "builtin tts ready");
            } else if (builtinErr.isEmpty()) {
                builtinErr = builtin.getLastError();
            }
            publishState();
        }, "qingyue-tts-init").start();

        // 系统引擎照常初始化，作为内置引擎的兜底
        initEngine(null);
    }

    /** 页面加载完成后由 Activity 调用：重推一次状态（解决推送早于页面注册的时序问题） */
    public void notifyPageReady() {
        main.post(this::publishState);
    }

    /** JS 可同步拉取的完整诊断状态（解决一切时序竞争） */
    @JavascriptInterface
    public String getDiag() {
        try {
            JSONObject o = new JSONObject();
            o.put("ver", APP_VER);
            o.put("builtinReady", builtinReady);
            o.put("builtinErr", builtinErr == null ? "" : builtinErr);
            o.put("engine", builtinReady ? "内置离线语音（小燕）" : safeStr(engineName));
            o.put("zh", builtinReady || zhAvailable);
            o.put("voices", builtinReady ? 1 : systemVoiceCount());
            o.put("ready", builtinReady || ready);
            return o.toString();
        } catch (Exception e) {
            return "{\"ver\":\"" + APP_VER + "\",\"builtinReady\":false,\"builtinErr\":\"diag error\",\"engine\":\"\",\"zh\":false,\"voices\":0,\"ready\":false}";
        }
    }

    private String safeStr(String s) { return s == null ? "" : s; }

    private int systemVoiceCount() {
        try {
            Set<Voice> vs = tts != null ? tts.getVoices() : null;
            return vs == null ? 0 : vs.size();
        } catch (Exception ignore) { return 0; }
    }

    /* ---------------- 引擎初始化与选择 ---------------- */

    private void initEngine(final String preferEngine) {
        try {
            engineProbing = true;
            ready = false;
            if (preferEngine == null) {
                tts = new TextToSpeech(appContext, this::onInit);
            } else {
                engineName = preferEngine;
                tts = new TextToSpeech(appContext, this::onInit, preferEngine);
            }
        } catch (Exception e) {
            Log.w(TAG, "tts create: " + e.getMessage());
            ready = false;
            publishState();
        }
    }

    private void onInit(int status) {
        engineProbing = false;
        if (status != TextToSpeech.SUCCESS || tts == null) {
            Log.w(TAG, "tts init failed");
            publishState();
            return;
        }
        engineName = safeEngineName(tts);
        zhAvailable = isChineseAvailable(tts);
        if (zhAvailable) {
            applyEngineConfig();
            ready = true;
            publishState();
            return;
        }
        // 默认引擎不支持中文 → 尝试其它引擎
        probeNextEngine(0);
    }

    private void probeNextEngine(final int index) {
        try {
            List<TextToSpeech.EngineInfo> engines = tts.getEngines();
            if (engines == null || index >= engines.size()) {
                // 没有可用中文引擎：保留默认引擎（部分引擎 isLanguageAvailable 不可靠，仍尝试朗读）
                applyEngineConfig();
                ready = true;
                publishState();
                return;
            }
            final TextToSpeech.EngineInfo ei = engines.get(index);
            if (ei.name == null || ei.name.equals(engineName)) {
                probeNextEngine(index + 1);
                return;
            }
            final TextToSpeech[] holder = new TextToSpeech[1];
            holder[0] = new TextToSpeech(appContext, status -> {
                TextToSpeech probe = holder[0];
                boolean ok = status == TextToSpeech.SUCCESS && probe != null && isChineseAvailable(probe);
                try { if (probe != null) probe.shutdown(); } catch (Exception ignore) {}
                if (ok) {
                    Log.i(TAG, "switch to engine: " + ei.name);
                    try { tts.shutdown(); } catch (Exception ignore) {}
                    initEngine(ei.name);   // 用该引擎重建主实例（onInit 中会置 ready）
                } else {
                    probeNextEngine(index + 1);
                }
            }, ei.name);
            if (holder[0] == null) probeNextEngine(index + 1);
        } catch (Exception e) {
            Log.w(TAG, "probe: " + e.getMessage());
            applyEngineConfig();
            ready = true;
            publishState();
        }
    }

    private void applyEngineConfig() {
        try {
            int r = tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.getDefault());
            }
            tts.setSpeechRate(rate);
            tts.setPitch(pitch);
            tts.setOnUtteranceProgressListener(progressListener);
        } catch (Exception e) {
            Log.w(TAG, "applyEngineConfig: " + e.getMessage());
        }
    }

    private boolean isChineseAvailable(TextToSpeech t) {
        try {
            int r = t.isLanguageAvailable(Locale.SIMPLIFIED_CHINESE);
            if (r < TextToSpeech.LANG_AVAILABLE) r = t.isLanguageAvailable(new Locale("zh", "CN"));
            if (r < TextToSpeech.LANG_AVAILABLE) r = t.isLanguageAvailable(Locale.CHINESE);
            if (r < TextToSpeech.LANG_AVAILABLE) r = t.isLanguageAvailable(new Locale("zh"));
            return r >= TextToSpeech.LANG_AVAILABLE;
        } catch (Exception e) {
            return false;
        }
    }

    private String safeEngineName(TextToSpeech t) {
        try { return String.valueOf(t.getDefaultEngine()); } catch (Exception e) { return ""; }
    }

    /** 把引擎状态推给网页：window.__ttsState(engine, zhAvailable, voiceCount, ready) */
    private void publishState() {
        final String eng;
        final boolean zh;
        final int cnt;
        final boolean rdy;
        if (builtinReady) {
            eng = "内置离线语音（小燕）"; zh = true; cnt = 1; rdy = true;
        } else {
            int n = 0;
            try {
                Set<Voice> vs = tts != null ? tts.getVoices() : null;
                n = vs == null ? 0 : vs.size();
            } catch (Exception ignore) {}
            eng = engineName == null ? "" : engineName;
            zh = zhAvailable;
            cnt = n;
            rdy = ready;
        }
        main.post(() -> {
            if (webView == null) return;
            webView.evaluateJavascript(
                    "window.__ttsState&&window.__ttsState(" +
                            "'" + esc(eng) + "'," + zh + "," + cnt + "," + rdy + ")", null);
        });
    }

    private String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("'", "\\'");
    }

    /** 试听结果回调 JS：window.__ttsTest(ok) */
    private void fireTest(final boolean ok) {
        main.post(() -> {
            if (webView == null) return;
            webView.evaluateJavascript("window.__ttsTest&&window.__ttsTest(" + ok + ")", null);
        });
    }

    /** 试读一句，验证当前引擎是否真的能出声 */
    @JavascriptInterface
    public void testSpeak() {
        if (builtinReady && builtin.isReady()) {
            builtin.speak("你好，这是轻阅读书的内置语音试听，现在可以正常朗读了。", rate, ok -> {
                if (!ok) Log.w(TAG, "builtin test failed: " + builtin.getLastError());
                fireTest(ok);
            });
            return;
        }
        main.post(() -> {
            if (tts == null || !ready) { fireTest(false); return; }
            try {
                tts.setSpeechRate(rate);
                tts.setPitch(pitch);
                Bundle params = new Bundle();
                params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "test");
                int r = tts.speak("你好，这是轻阅读书的语音试听。", TextToSpeech.QUEUE_FLUSH, params, "test");
                if (r != TextToSpeech.SUCCESS) fireTest(false);
            } catch (Exception e) {
                fireTest(false);
            }
        });
    }

    /** 列出已安装的 TTS 引擎 JSON：[{name,label}]；内置引擎可用时只返回它 */
    @JavascriptInterface
    public String getEngines() {
        try {
            JSONArray arr = new JSONArray();
            if (builtinReady) {
                JSONObject o = new JSONObject();
                o.put("name", "builtin");
                o.put("label", "内置离线语音（小燕）");
                arr.put(o);
                return arr.toString();
            }
            if (tts != null) {
                List<TextToSpeech.EngineInfo> list = tts.getEngines();
                if (list != null) {
                    for (TextToSpeech.EngineInfo ei : list) {
                        JSONObject o = new JSONObject();
                        o.put("name", ei.name);
                        o.put("label", ei.label == null || ei.label.isEmpty() ? ei.name : ei.label);
                        arr.put(o);
                    }
                }
            }
            return arr.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    /** 切换到指定引擎（异步重建，完成后推 __ttsState） */
    @JavascriptInterface
    public void useEngine(final String name) {
        if (name == null || name.isEmpty()) return;
        if ("builtin".equals(name)) {
            main.post(this::publishState);   // 内置引擎始终可用，切回它即可
            return;
        }
        main.post(() -> {
            if (name.equals(engineName)) return;
            try { if (tts != null) { tts.stop(); tts.shutdown(); } } catch (Exception ignore) {}
            tts = null;
            initEngine(name);
        });
    }

    /** 弹出系统风格的单选对话框让用户挑引擎 */
    @JavascriptInterface
    public void chooseEngine() {
        main.post(() -> {
            try {
                List<TextToSpeech.EngineInfo> list = tts != null ? tts.getEngines() : null;
                if (list == null || list.isEmpty()) { fireTest(false); return; }
                final String[] names = new String[list.size()];
                final String[] labels = new String[list.size()];
                int cur = 0;
                for (int i = 0; i < list.size(); i++) {
                    names[i] = list.get(i).name;
                    labels[i] = (list.get(i).label == null || list.get(i).label.isEmpty()
                            ? list.get(i).name : list.get(i).label)
                            + (names[i].equals(engineName) ? "（当前）" : "");
                    if (names[i].equals(engineName)) cur = i;
                }
                new android.app.AlertDialog.Builder(activity)
                        .setTitle("选择语音引擎")
                        .setSingleChoiceItems(labels, cur, (d, w) -> {
                            d.dismiss();
                            if (names[w] != null && !names[w].equals(engineName)) {
                                useEngine(names[w]);
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            } catch (Exception e) {
                Log.w(TAG, "chooseEngine: " + e.getMessage());
            }
        });
    }

    /* ---------------- 朗读事件 ---------------- */

    private final UtteranceProgressListener progressListener = new UtteranceProgressListener() {
        @Override public void onStart(String utteranceId) {
            if ("test".equals(utteranceId)) return;
            fire("start", utteranceId);
        }
        @Override public void onDone(String utteranceId) {
            if ("test".equals(utteranceId)) { fireTest(true); return; }
            fire("done", utteranceId);
        }
        @Override public void onError(String utteranceId) {
            if ("test".equals(utteranceId)) { fireTest(false); return; }
            fire("error", utteranceId);
        }
    };

    private void fire(String type, String id) {
        main.post(() -> {
            if (webView == null) return;
            webView.evaluateJavascript("window.__ttsEvent&&window.__ttsEvent('" + type + "','" + id + "')", null);
        });
    }

    /** 朗读一句；id 为递增序号，用于与网页侧的播放会话校验。内置引擎失败时自动回退系统引擎 */
    @JavascriptInterface
    public void speak(final String text, final String id) {
        if (builtinReady && builtin.isReady()) {
            builtin.speak(text, rate, ok -> {
                if (ok) { fire("done", id); return; }
                Log.w(TAG, "builtin speak failed, fallback to system tts");
                systemSpeak(text, id);
            });
            return;
        }
        main.post(() -> systemSpeak(text, id));
    }

    private void systemSpeak(final String text, final String id) {
        if (tts == null || !ready || text == null || text.isEmpty()) {
            fire("error", id);
            return;
        }
        try {
            tts.setSpeechRate(rate);
            tts.setPitch(pitch);
            applyVoice();
            Bundle params = new Bundle();
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id);
            int res = tts.speak(text, TextToSpeech.QUEUE_ADD, params, id);
            if (res != TextToSpeech.SUCCESS) fire("error", id);
        } catch (Exception e) {
            fire("error", id);
        }
    }

    @JavascriptInterface
    public void stop() {
        if (builtinReady && builtin.isReady()) { builtin.stop(); return; }
        main.post(() -> {
            try { if (tts != null) tts.stop(); } catch (Exception e) { /* ignore */ }
        });
    }

    @JavascriptInterface
    public void setRate(float r) {
        rate = r;
        main.post(() -> { try { if (tts != null) tts.setSpeechRate(rate); } catch (Exception e) { /* ignore */ } });
    }

    @JavascriptInterface
    public void setPitch(float p) {
        pitch = p;
        main.post(() -> { try { if (tts != null) tts.setPitch(pitch); } catch (Exception e) { /* ignore */ } });
    }

    @JavascriptInterface
    public void setVoice(String name) {
        voiceName = name;
        main.post(this::applyVoice);
    }

    @JavascriptInterface
    public boolean isSpeaking() {
        try { return tts != null && tts.isSpeaking(); } catch (Exception e) { return false; }
    }

    @JavascriptInterface
    public boolean isReady() {
        return ready;
    }

    /** 当前引擎包名 */
    @JavascriptInterface
    public String getEngine() {
        return engineName;
    }

    /** 是否检测到可用的中文语音 */
    @JavascriptInterface
    public boolean isChineseAvailable() {
        return zhAvailable;
    }

    /** 返回可选音色 JSON：[{name, locale}]，中文排在前面；为空时给一个「系统默认音色」占位 */
    @JavascriptInterface
    public String getVoices() {
        try {
            if (builtinReady && builtin.isReady()) {
                JSONArray arr = new JSONArray();
                JSONObject o = new JSONObject();
                o.put("name", BUILTIN_NAME);
                o.put("locale", "zh-CN");
                arr.put(o);
                return arr.toString();
            }
            JSONArray arr = new JSONArray();
            if (tts != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Set<Voice> set = tts.getVoices();
                if (set != null && !set.isEmpty()) {
                    List<Voice> list = new ArrayList<>(set);
                    Collections.sort(list, new Comparator<Voice>() {
                        @Override public int compare(Voice a, Voice b) {
                            return score(a) - score(b);
                        }
                        private int score(Voice v) {
                            String l = v.getLocale().getLanguage();
                            if ("zh".equals(l)) return 0;
                            if ("yue".equals(l)) return 1;
                            return 9;
                        }
                    });
                    for (Voice v : list) {
                        JSONObject o = new JSONObject();
                        o.put("name", v.getName());
                        o.put("locale", v.getLocale().toString());
                        arr.put(o);
                    }
                }
            }
            if (arr.length() == 0) {
                JSONObject def = new JSONObject();
                def.put("name", "系统默认音色");
                def.put("locale", "default");
                arr.put(def);
            }
            return arr.toString();
        } catch (Exception e) {
            return "[{\"name\":\"系统默认音色\",\"locale\":\"default\"}]";
        }
    }

    private void applyVoice() {
        try {
            if (tts == null || voiceName == null || voiceName.isEmpty()) return;
            if ("系统默认音色".equals(voiceName) || BUILTIN_NAME.equals(voiceName)) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Set<Voice> set = tts.getVoices();
                if (set == null) return;
                for (Voice v : set) {
                    if (voiceName.equals(v.getName())) {
                        tts.setVoice(v);
                        return;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "applyVoice: " + e.getMessage());
        }
    }

    /** 听书时保持屏幕常亮 */
    @JavascriptInterface
    public void keepScreenOn(final boolean on) {
        main.post(() -> {
            try {
                if (on) {
                    activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                } else {
                    activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
            } catch (Exception e) { /* ignore */ }
        });
    }

    /** 已安装引擎数量（0 = 一个语音引擎都没有；内置引擎可用时视为 1） */
    @JavascriptInterface
    public int engineCount() {
        if (builtinReady) return 1;
        try {
            List<TextToSpeech.EngineInfo> l = tts != null ? tts.getEngines() : null;
            return l == null ? 0 : l.size();
        } catch (Exception e) {
            return 0;
        }
    }

    /** 跳应用商店搜索语音引擎（手机无任何 TTS 引擎时的引导） */
    @JavascriptInterface
    public void openMarket() {
        main.post(() -> {
            Intent i = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://search?q=" + Uri.encode("讯飞语记")));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                activity.startActivity(i);
                return;
            } catch (Exception ignore) {}
            try {
                activity.startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://app.mi.com/search?keywords=" + Uri.encode("讯飞语记")))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                Log.w(TAG, "openMarket: " + e.getMessage());
            }
        });
    }

    /** 装好语音引擎后重新初始化检测；内置引擎失败时也一并重试 */
    @JavascriptInterface
    public void reinit() {
        main.post(() -> {
            try { if (tts != null) { tts.stop(); tts.shutdown(); } } catch (Exception ignore) {}
            tts = null;
            ready = false;
            zhAvailable = false;
            initEngine(null);
        });
        new Thread(() -> {
            if (!builtinReady) {
                try { builtin.shutdown(); } catch (Exception ignore) {}
                boolean ok = builtin.init(appContext);
                builtinReady = ok;
                if (ok) {
                    builtinErr = "";
                    engineName = "内置离线语音";
                    zhAvailable = true;
                    ready = true;
                } else {
                    builtinErr = builtin.getLastError();
                }
                publishState();
            }
        }, "qingyue-tts-reinit").start();
    }

    /** 跳转系统「文字转语音」设置页（安装/切换语音引擎） */
    @JavascriptInterface
    public void openTtsSettings() {        main.post(() -> {
            Intent i;
            try {
                i = new Intent("com.android.settings.TTS_SETTINGS");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            } catch (Exception e) {
                i = null;
            }
            try {
                if (i != null) {
                    activity.startActivity(i);
                } else {
                    activity.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                }
            } catch (Exception e) {
                try {
                    activity.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception ignore) {}
            }
        });
    }

    public void shutdown() {
        try { builtin.shutdown(); } catch (Exception ignore) {}
        try {
            if (tts != null) {
                tts.stop();
                tts.shutdown();
                tts = null;
            }
        } catch (Exception e) { /* ignore */ }
        ready = false;
    }
}
