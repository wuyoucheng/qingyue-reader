package com.qingyue.reader;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
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

    public NativeTTS(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        this.appContext = activity.getApplicationContext();
        initEngine(null);
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
        int n = 0;
        try {
            Set<Voice> vs = tts != null ? tts.getVoices() : null;
            n = vs == null ? 0 : vs.size();
        } catch (Exception ignore) {}
        final String eng = engineName == null ? "" : engineName;
        final boolean zh = zhAvailable;
        final int cnt = n;
        final boolean rdy = ready;
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

    /* ---------------- 朗读事件 ---------------- */

    private final UtteranceProgressListener progressListener = new UtteranceProgressListener() {
        @Override public void onStart(String utteranceId) { fire("start", utteranceId); }
        @Override public void onDone(String utteranceId) { fire("done", utteranceId); }
        @Override public void onError(String utteranceId) { fire("error", utteranceId); }
    };

    private void fire(String type, String id) {
        main.post(() -> {
            if (webView == null) return;
            webView.evaluateJavascript("window.__ttsEvent&&window.__ttsEvent('" + type + "','" + id + "')", null);
        });
    }

    /** 朗读一句；id 为递增序号，用于与网页侧的播放会话校验 */
    @JavascriptInterface
    public void speak(final String text, final String id) {
        main.post(() -> {
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
        });
    }

    @JavascriptInterface
    public void stop() {
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
            if ("系统默认音色".equals(voiceName)) return;   // 不设置，用引擎默认
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

    /** 跳转系统「文字转语音」设置页（安装/切换语音引擎） */
    @JavascriptInterface
    public void openTtsSettings() {
        main.post(() -> {
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
