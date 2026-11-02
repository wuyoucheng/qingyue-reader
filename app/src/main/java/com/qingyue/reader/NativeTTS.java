package com.qingyue.reader;

import android.app.Activity;
import android.content.Context;
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
 */
public class NativeTTS {

    private static final String TAG = "NativeTTS";

    private final Activity activity;
    private final WebView webView;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private volatile boolean ready = false;
    private float rate = 1.0f;
    private float pitch = 1.0f;
    private String voiceName = null;

    public NativeTTS(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        try {
            tts = new TextToSpeech(activity.getApplicationContext(), status -> {
                if (status == TextToSpeech.SUCCESS && tts != null) {
                    try {
                        int r = tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
                        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                            tts.setLanguage(Locale.getDefault());
                        }
                        tts.setSpeechRate(rate);
                        tts.setPitch(pitch);
                        tts.setOnUtteranceProgressListener(progressListener);
                        ready = true;
                    } catch (Exception e) {
                        Log.w(TAG, "tts init: " + e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "tts create: " + e.getMessage());
        }
    }

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

    /** 返回可选音色 JSON：[{name, locale}]，中文排在前面 */
    @JavascriptInterface
    public String getVoices() {
        try {
            JSONArray arr = new JSONArray();
            if (tts == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return arr.toString();
            Set<Voice> set = tts.getVoices();
            if (set == null) return arr.toString();
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
            return arr.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    private void applyVoice() {
        try {
            if (tts == null || voiceName == null || voiceName.isEmpty()) return;
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
