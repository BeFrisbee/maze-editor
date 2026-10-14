package com.befrisbee.mazeeditor;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** 미궁 제도실 편집기 HTML을 앱 안에서 띄운다. 인터넷은 새 버전 확인과 받기에만 쓴다. */
public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String START = "https://" + HOST + "/assets/index.html";
    private static final int PICK_FILE = 41;
    private static final int SAVE_FILE = 42;

    private WebView web;
    private long lastBack = 0;
    private ValueCallback<Uri[]> pickCallback;
    private String pendingSave;   // 저장 위치를 고르는 동안 기다리는 내용 (안드로이드 9 이하)
    private Updater updater;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        web.setBackgroundColor(0xff12100c);
        setContentView(web);

        String applied = Updater.prepare(this);   // 받아 둔 새 편집기가 있으면 이번에 적용
        final WebViewAssetLoader.AssetsPathHandler assets = new WebViewAssetLoader.AssetsPathHandler(this);
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", path -> {
                    if ("index.html".equals(path)) {
                        File f = Updater.currentFile(MainActivity.this);
                        if (f != null) {
                            try {
                                return new WebResourceResponse("text/html", "utf-8", new FileInputStream(f));
                            } catch (IOException ignored) { }
                        }
                    }
                    return assets.handle(path);
                })
                .build();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);   // 작업 내용 자동 저장
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setTextZoom(100);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (HOST.equals(uri.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(MainActivity.this, "링크를 열 앱이 없어요", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });

        // JSON 불러오기: 휴대폰 저장공간에서 파일 고르기 (alert·confirm 창도 이 클라이언트가 띄움)
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (pickCallback != null) pickCallback.onReceiveValue(null);
                pickCallback = callback;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
                try {
                    startActivityForResult(i, PICK_FILE);
                } catch (ActivityNotFoundException e) {
                    pickCallback = null;
                    Toast.makeText(MainActivity.this, "파일을 고를 앱이 없어요", Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }
        });

        updater = new Updater(this, web);
        web.addJavascriptInterface(new Bridge(), "MZNative");

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(START);

        if (applied != null) Toast.makeText(this, "편집기를 " + applied + "(으)로 바꿨어요", Toast.LENGTH_LONG).show();
        updater.check();
    }

    /** 편집기(JS)에서 부르는 기능 */
    private class Bridge {
        /** JSON 파일을 휴대폰의 다운로드/미궁 제도실 폴더에 저장 */
        @JavascriptInterface
        public void saveFile(String name, String text) {
            final String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 29) {
                    try {
                        ContentValues v = new ContentValues();
                        v.put(MediaStore.MediaColumns.DISPLAY_NAME, safe);
                        v.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
                        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/미궁 제도실");
                        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                        if (uri == null) throw new IOException("insert");
                        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                            out.write(text.getBytes(StandardCharsets.UTF_8));
                        }
                        Toast.makeText(MainActivity.this, "다운로드 › 미궁 제도실 폴더에 저장했어요", Toast.LENGTH_LONG).show();
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "파일을 저장하지 못했어요", Toast.LENGTH_LONG).show();
                    }
                } else {   // 안드로이드 9 이하: 저장할 곳을 직접 고름
                    pendingSave = text;
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("application/json");
                    i.putExtra(Intent.EXTRA_TITLE, safe);
                    try {
                        startActivityForResult(i, SAVE_FILE);
                    } catch (ActivityNotFoundException e) {
                        pendingSave = null;
                        Toast.makeText(MainActivity.this, "파일을 저장하지 못했어요", Toast.LENGTH_LONG).show();
                    }
                }
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == PICK_FILE) {
            if (pickCallback != null) {
                pickCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                pickCallback = null;
            }
            return;
        }
        if (requestCode == SAVE_FILE) {
            String text = pendingSave;
            pendingSave = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null && text != null) {
                try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                    out.write(text.getBytes(StandardCharsets.UTF_8));
                    Toast.makeText(this, "파일을 저장했어요", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(this, "파일을 저장하지 못했어요", Toast.LENGTH_LONG).show();
                }
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (updater != null) updater.onResume();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    /** 뒤로 가기: 먼저 편집기에 맡김(열린 창 닫기·지우개 끄기). 그 밖에는 두 번 눌러야 종료 */
    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.MZBack&&window.MZBack())?1:0", r -> {
            if ("1".equals(r)) return;
            long now = System.currentTimeMillis();
            if (now - lastBack < 2000) {
                finish();
            } else {
                lastBack = now;
                Toast.makeText(this, "한 번 더 누르면 종료돼요", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
