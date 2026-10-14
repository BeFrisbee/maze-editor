package com.befrisbee.mazeeditor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 업데이트 두 가지
 *  - 편집기(HTML)만 바뀜: 켤 때 몰래 받아 두었다가 다음에 켤 때 적용
 *  - 앱(APK)이 바뀜(native-level 상승): 창으로 알리고, 누르면 받아서 설치 화면을 띄움
 * 같은 키로 서명된 APK만 기존 앱 위에 설치된다.
 */
class Updater {
    private static final String LATEST = "https://api.github.com/repos/BeFrisbee/maze-editor/releases/latest";
    private static final String DL = "https://github.com/BeFrisbee/maze-editor/releases/download/";

    private final Activity act;
    private final WebView web;
    private volatile boolean busy = false;
    private File pending;

    Updater(Activity act, WebView web) {
        this.act = act;
        this.web = web;
    }

    // ── 버전 ──
    static long apkCode(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    static int nativeLevel(Context c) {
        return c.getResources().getInteger(R.integer.native_level);
    }

    static long contentVersion(Context c) {
        return Math.max(apkCode(c), readLong(new File(dir(c), "current.ver")));
    }

    // ── 받아 둔 편집기 ──
    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "web");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** 앱을 켤 때: 받아 둔 새 편집기를 적용. 적용했으면 그 버전 이름을 돌려줌 */
    static String prepare(Context c) {
        File d = dir(c), next = new File(d, "next.html"), nextVer = new File(d, "next.ver");
        String applied = null;
        long nv = readLong(nextVer);
        if (next.exists() && nv > contentVersion(c)) {
            File cur = new File(d, "current.html");
            cur.delete();
            if (next.renameTo(cur)) {
                writeText(new File(d, "current.ver"), String.valueOf(nv));
                applied = "1.0." + nv;
            }
        }
        next.delete();
        nextVer.delete();
        if (readLong(new File(d, "current.ver")) <= apkCode(c)) {   // 앱을 새로 깔았으면 앱에 든 것이 최신
            new File(d, "current.html").delete();
            new File(d, "current.ver").delete();
        }
        return applied;
    }

    static File currentFile(Context c) {
        File d = dir(c), cur = new File(d, "current.html");
        return cur.exists() && readLong(new File(d, "current.ver")) > apkCode(c) ? cur : null;
    }

    boolean canInstall() {
        return Build.VERSION.SDK_INT < 26 || act.getPackageManager().canRequestPackageInstalls();
    }

    // ── 확인 ──
    void check() {
        new Thread(() -> {
            try {
                JSONObject rel = new JSONObject(get(LATEST));
                String tag = rel.optString("tag_name", "");
                String name = tag.startsWith("v") ? tag.substring(1) : tag;
                String apk = null, html = null, metaUrl = null;
                JSONArray assets = rel.optJSONArray("assets");
                for (int i = 0; assets != null && i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    String n = a.optString("name"), u = a.optString("browser_download_url");
                    if (!u.startsWith(DL)) continue;
                    if (n.endsWith(".apk")) apk = u;
                    else if (n.equals("editor.html")) html = u;
                    else if (n.equals("app-meta.json")) metaUrl = u;
                }
                if (metaUrl == null) return;
                JSONObject meta = new JSONObject(get(metaUrl));
                long content = meta.optLong("content", 0);
                if (meta.optInt("native", 0) > nativeLevel(act)) {
                    if (apk == null) return;
                    String notes = meta.optString("nativeNotes", meta.optString("notes", ""));
                    final String fApk = apk;
                    act.runOnUiThread(() -> askInstall(name, notes, fApk, content));
                    return;
                }
                if (content <= contentVersion(act) || html == null) return;
                String page = get(html);
                if (page.length() < 100000 || !page.contains("미궁 제도실")) return;   // 정말 편집기 파일인지 확인
                File d = dir(act), tmp = new File(d, "next.tmp"), next = new File(d, "next.html");
                writeText(tmp, page);
                next.delete();
                if (!tmp.renameTo(next)) return;
                writeText(new File(d, "next.ver"), String.valueOf(content));
                final String n = meta.optString("notes", "");
                act.runOnUiThread(() -> Toast.makeText(act,
                        "새 편집기 " + name + "을(를) 받았어요. 다음에 켤 때 바뀌어요" + (n.isEmpty() ? "" : "\n" + n),
                        Toast.LENGTH_LONG).show());
            } catch (Exception ignored) { }
        }).start();
    }

    private void askInstall(String name, String notes, String url, long code) {
        if (act.isFinishing()) return;
        new AlertDialog.Builder(act)
                .setTitle("새 버전 " + name)
                .setMessage((notes.isEmpty() ? "" : notes + "\n\n") + "앱을 새로 설치하는 업데이트예요. 설치 확인 화면이 한 번 떠요.")
                .setPositiveButton("업데이트", (d, w) -> download(url, code))
                .setNegativeButton("나중에", null)
                .show();
    }

    // ── APK 받기 → 확인 → 설치 ──
    private void download(String url, long expectCode) {
        if (busy) return;
        busy = true;
        ProgressBar bar = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        TextView label = new TextView(act);
        label.setText("받는 중…");
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * act.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(label);
        box.addView(bar);
        AlertDialog dlg = new AlertDialog.Builder(act).setTitle("새 버전 받기").setView(box).setCancelable(false).show();
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                File d = new File(act.getCacheDir(), "update");
                if (!d.exists()) d.mkdirs();
                File f = new File(d, "update.apk");
                c = open(url);
                long total = c.getContentLengthLong();
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(f)) {
                    byte[] buf = new byte[64 * 1024];
                    long got = 0;
                    int n, last = -1;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        got += n;
                        if (total > 0) {
                            final int p = (int) (got * 100 / total);
                            if (p != last) { last = p; act.runOnUiThread(() -> { bar.setProgress(p); label.setText("받는 중… " + p + "%"); }); }
                        }
                    }
                }
                PackageInfo pi = act.getPackageManager().getPackageArchiveInfo(f.getAbsolutePath(), 0);
                long code = pi == null ? -1 : (Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode);
                if (pi == null || !act.getPackageName().equals(pi.packageName) || code < expectCode) {
                    f.delete();
                    toast("받은 파일이 맞지 않아요. 잠시 뒤 다시 해 주세요");
                    return;
                }
                act.runOnUiThread(() -> install(f));
            } catch (Exception e) {
                toast("받지 못했어요. 인터넷 연결을 확인해 주세요");
            } finally {
                if (c != null) c.disconnect();
                busy = false;
                act.runOnUiThread(dlg::dismiss);
            }
        }).start();
    }

    private void install(File f) {
        if (!canInstall()) {
            pending = f;
            Toast.makeText(act, "'이 출처 허용'을 켜고 뒤로 돌아와 주세요", Toast.LENGTH_LONG).show();
            try {
                act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + act.getPackageName())));
            } catch (ActivityNotFoundException e) {
                toast("설치 허용 화면을 열지 못했어요");
            }
            return;
        }
        Uri uri = FileProvider.getUriForFile(act, act.getPackageName() + ".files", f);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            act.startActivity(i);
        } catch (ActivityNotFoundException e) {
            toast("설치 화면을 열지 못했어요");
        }
    }

    void onResume() {
        if (pending == null) return;
        File f = pending;
        pending = null;
        if (canInstall()) install(f);
        else toast("설치 허용이 꺼져 있어요");
    }

    // ── 도우미 ──
    private void toast(String s) {
        act.runOnUiThread(() -> Toast.makeText(act, s, Toast.LENGTH_LONG).show());
    }

    private static long readLong(File f) {
        try { return Long.parseLong(readText(f).trim()); } catch (Exception e) { return 0; }
    }

    private static String readText(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void writeText(File f, String s) {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }

    private static HttpURLConnection open(String url) throws Exception {
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "maze-editor-app");
            c.setRequestProperty("Accept", url.startsWith("https://api.github.com") ? "application/vnd.github+json" : "*/*");
            int rc = c.getResponseCode();
            if (rc >= 300 && rc < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null || !loc.startsWith("https://")) throw new Exception("redirect");
                url = loc;
                continue;
            }
            if (rc != 200) {
                c.disconnect();
                throw new Exception("http " + rc);
            }
            return c;
        }
        throw new Exception("too many redirects");
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString(StandardCharsets.UTF_8.name());
        } finally {
            c.disconnect();
        }
    }
}
