package com.qingyue.reader;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;

/**
 * 轻阅读书器主界面：WebView 加载 assets/www 中的网页（通过虚拟 HTTPS 域，
 * 使 IndexedDB / ES Module / 本地存储 正常工作），并桥接系统 TTS 与文件选择器。
 *
 * 文件导入有两条通道：
 *  1) AndroidFile.pickFiles() —— 原生 SAF 选择器（主通道，稳定可靠）
 *  2) WebChromeClient.onShowFileChooser —— <input type="file"> 兜底
 */
public class MainActivity extends ComponentActivity {

    private static final String APP_URL = "https://appassets.androidplatform.net/assets/www/index.html";
    private static final String LOCAL_PATH = "/__local__/";
    private static final String TAG = "MainActivity";

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private NativeTTS nativeTTS;
    private long lastBackPress = 0;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /* ---------------- 原生文件选择器（主通道） ---------------- */

    private final ActivityResultLauncher<Intent> nativePickLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                List<Uri> picked = new ArrayList<>();
                Intent data = result.getData();
                if (result.getResultCode() == Activity.RESULT_OK && data != null) {
                    ClipData clip = data.getClipData();
                    if (clip != null) {
                        for (int i = 0; i < clip.getItemCount(); i++) {
                            Uri u = clip.getItemAt(i).getUri();
                            if (u != null) picked.add(u);
                        }
                    } else if (data.getData() != null) {
                        picked.add(data.getData());
                    }
                }
                if (picked.isEmpty()) {
                    Log.i(TAG, "pick cancelled");
                    return;
                }
                deliverPickedFiles(picked);
            });

    /** 把选中结果（uri / 显示名 / mime）以 Base64(JSON) 形式回调给 JS */
    private void deliverPickedFiles(List<Uri> uris) {
        try {
            final int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
            JSONArray arr = new JSONArray();
            for (Uri u : uris) {
                try {
                    getContentResolver().takePersistableUriPermission(u, flags);
                } catch (Exception ignore) { /* 部分 provider 不支持持久化授权 */ }
                JSONObject o = new JSONObject();
                o.put("uri", u.toString());
                o.put("name", queryDisplayName(u));
                o.put("mime", safeType(u));
                arr.put(o);
            }
            String b64 = Base64.encodeToString(arr.toString().getBytes("UTF-8"), Base64.NO_WRAP);
            mainHandler.post(() -> {
                if (webView != null) {
                    webView.evaluateJavascript("window.__onFilesPickedB64&&window.__onFilesPickedB64('" + b64 + "')", null);
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "deliver: " + e.getMessage());
            toast("读取所选文件失败");
        }
    }

    private String queryDisplayName(Uri uri) {
        ContentResolver cr = getContentResolver();
        try (Cursor c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) {
                    String n = c.getString(i);
                    if (n != null && !n.isEmpty()) return n;
                }
            }
        } catch (Exception ignore) { /* fallthrough */ }
        String last = uri.getLastPathSegment();
        return last == null ? "book.txt" : last;
    }

    private String safeType(Uri uri) {
        try {
            String t = getContentResolver().getType(uri);
            return t == null ? "" : t;
        } catch (Exception e) {
            return "";
        }
    }

    /** 打开系统文件选择器：优先 ACTION_OPEN_DOCUMENT（可多选 + 持久授权），失败降级 ACTION_GET_CONTENT */
    void startPick() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            nativePickLauncher.launch(Intent.createChooser(intent, "选择小说文件（TXT / EPUB）"));
        } catch (ActivityNotFoundException e) {
            Intent alt = new Intent(Intent.ACTION_GET_CONTENT);
            alt.addCategory(Intent.CATEGORY_OPENABLE);
            alt.setType("*/*");
            alt.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            alt.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                nativePickLauncher.launch(Intent.createChooser(alt, "选择小说文件（TXT / EPUB）"));
            } catch (ActivityNotFoundException e2) {
                toast("未找到可用的文件管理器");
            }
        } catch (Exception e) {
            Log.w(TAG, "startPick: " + e.getMessage());
            toast("无法打开文件选择器：" + e.getMessage());
        }
    }

    private void toast(String msg) {
        mainHandler.post(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    /** 暴露给网页的原生文件接口：window.AndroidFile */
    public class FileBridge {
        @JavascriptInterface
        public void pickFiles() {
            mainHandler.post(MainActivity.this::startPick);
        }

        @JavascriptInterface
        public boolean available() {
            return true;
        }
    }

    private final FileBridge fileBridge = new FileBridge();

    /* ---------------- <input type="file"> 兜底通道 ---------------- */

    private final ActivityResultLauncher<Intent> filePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (filePathCallback == null) return;
                Uri[] uris = null;
                Intent data = result.getData();
                if (result.getResultCode() == Activity.RESULT_OK && data != null) {
                    if (data.getClipData() != null) {
                        int n = data.getClipData().getItemCount();
                        uris = new Uri[n];
                        for (int i = 0; i < n; i++) {
                            uris[i] = data.getClipData().getItemAt(i).getUri();
                        }
                    } else if (data.getData() != null) {
                        uris = new Uri[]{data.getData()};
                    }
                }
                ValueCallback<Uri[]> cb = filePathCallback;
                filePathCallback = null;
                try {
                    cb.onReceiveValue(uris);
                } catch (Exception e) {
                    Log.w(TAG, "legacy callback: " + e.getMessage());
                }
            });

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(0xFFF7F3E8);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        webView = new WebView(this);
        setContentView(webView);

        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .setDomain("appassets.androidplatform.net")
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setAllowContentAccess(true);
        ws.setAllowFileAccess(true);
        ws.setLoadWithOverviewMode(false);
        ws.setUseWideViewPort(true);
        ws.setSupportZoom(false);
        ws.setBuiltInZoomControls(false);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setDefaultTextEncodingName("UTF-8");
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        ws.setUserAgentString(ws.getUserAgentString() + " QingYueReader/1.1");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                // 通道：虚拟域 /__local__/?u=<content uri> —— 把选中的本地文件喂给网页
                if (LOCAL_PATH.equals(url.getPath())) {
                    String u = url.getQueryParameter("u");
                    if (u != null) {
                        try {
                            Uri target = Uri.parse(u);
                            InputStream in = getContentResolver().openInputStream(target);
                            if (in != null) {
                                String mime = safeType(target);
                                if (mime == null || mime.isEmpty()) mime = "application/octet-stream";
                                return new WebResourceResponse(mime, null, in);
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "local read: " + e.getMessage());
                        }
                    }
                }
                WebResourceResponse r = assetLoader.shouldInterceptRequest(url);
                return r != null ? r : super.shouldInterceptRequest(view, request);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                return !url.startsWith("https://appassets.androidplatform.net/");
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams fileChooserParams) {
                filePathCallback = callback;
                try {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    filePickerLauncher.launch(Intent.createChooser(intent, "选择小说文件（TXT / EPUB）"));
                    return true;
                } catch (Exception e) {
                    Log.w(TAG, "showFileChooser: " + e.getMessage());
                    filePathCallback = null;
                    try { callback.onReceiveValue(null); } catch (Exception ignored) {}
                    toast("无法打开文件选择器");
                    return false;
                }
            }
        });

        nativeTTS = new NativeTTS(this, webView);
        webView.addJavascriptInterface(nativeTTS, "AndroidTTS");
        webView.addJavascriptInterface(fileBridge, "AndroidFile");

        if (savedInstanceState == null) {
            webView.loadUrl(APP_URL);
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) webView.saveState(outState);
    }

    @SuppressLint("MissingSuperCall")
    @Override
    public void onBackPressed() {
        webView.evaluateJavascript(
                "(function(){ if (window.__onBack) { return window.__onBack() === true ? '1' : '0'; } return '0'; })()",
                value -> {
                    if (value != null && value.contains("1")) return;
                    long now = System.currentTimeMillis();
                    if (now - lastBackPress < 2000) {
                        finish();
                    } else {
                        lastBackPress = now;
                        Toast.makeText(this, "再按一次退出轻阅", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onDestroy() {
        if (nativeTTS != null) nativeTTS.shutdown();
        if (webView != null) {
            webView.getSettings().setJavaScriptEnabled(false);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
