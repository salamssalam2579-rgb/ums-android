package org.ums.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Base64;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** غلاف أصلي لنظام UMS: يفتح النظام بملء الشاشة، يسلّم التنزيلات لعارض الجهاز، ويفحص التحديثات بنفسه. */
public class MainActivity extends Activity {
    private WebView web;
    private ValueCallback<Uri[]> chooser;
    private long lastCheck = 0, lastDiscover = 0;
    private boolean errorShown = false;

    private String serverUrl() {
        SharedPreferences p = getSharedPreferences("ums", MODE_PRIVATE);
        String u = p.getString("server", BuildConfig.SERVER_URL);
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    private String lastDiscovery = "لم تُجرَ محاولة بعد";

    private String httpText(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setUseCaches(false);
        c.setRequestProperty("Cache-Control", "no-cache");
        c.setRequestProperty("User-Agent", "ums-android");
        if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
        StringBuilder sb = new StringBuilder();
        try (InputStream in = c.getInputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0 && sb.length() < 65536) sb.append(new String(buf, 0, n, "UTF-8"));
        }
        return sb.toString();
    }

    private String cleanUrl(String t) {
        String u = t == null ? "" : t.trim();
        if (!u.startsWith("https://") || u.contains(" ") || u.contains("\n")) return "";
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    /** يقرأ العنوان من ملف الاكتشاف؛ إن فشل أو كان قديماً من ذاكرة CDN يجرب واجهة GitHub API. */
    private String readDiscovery(String d) {
        String err = "";
        try {
            String u = cleanUrl(httpText(d + (d.contains("?") ? "&" : "?") + "t=" + System.currentTimeMillis()));
            if (!u.isEmpty() && !u.equals(serverUrl())) return u;   // عنوان جديد مختلف
            if (u.isEmpty()) err = "محتوى غير صالح";
            else lastDiscovery = "ملف الاكتشاف يعطي العنوان الحالي نفسه";
        } catch (Exception e) { err = String.valueOf(e.getMessage()); }
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("gist\\.githubusercontent\\.com/([^/]+)/([0-9a-f]+)/raw/([^/?]+)").matcher(d);
            if (m.find()) {
                JSONObject j = new JSONObject(httpText("https://api.github.com/gists/" + m.group(2) + "?t=" + System.currentTimeMillis()));
                String u = cleanUrl(j.getJSONObject("files").getJSONObject(m.group(3)).getString("content"));
                if (!u.isEmpty()) { if (err.isEmpty()) lastDiscovery = "تم التحقق عبر GitHub API"; return u; }
            }
        } catch (Exception e) { if (!err.isEmpty()) err += " / API: " + e.getMessage(); }
        if (!err.isEmpty()) lastDiscovery = "تعذّرت القراءة: " + err;
        return "";
    }

    /** يقرأ العنوان الحالي من ملف الاكتشاف (Gist) ويحفظه إن تغيّر. يُرجع true إن تغيّر. يجب استدعاؤه خارج خيط الواجهة. */
    private boolean discover() {
        String d = BuildConfig.DISCOVERY_URL;
        if (d == null || d.isEmpty()) { lastDiscovery = "ملف الاكتشاف غير مضبوط في هذه النسخة"; return false; }
        String u = readDiscovery(d);
        if (u.isEmpty() || u.equals(serverUrl())) return false;
        getSharedPreferences("ums", MODE_PRIVATE).edit().putString("server", u).apply();
        lastDiscovery = "تم العثور على عنوان جديد";
        return true;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        web.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setUserAgentString(s.getUserAgentString() + " UMSApp/" + BuildConfig.VERSION_NAME);
        CookieManager.getInstance().setAcceptCookie(true);
        web.addJavascriptInterface(new Bridge(), "UMSNative");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                Uri base = Uri.parse(serverUrl());
                if (u.getHost() != null && u.getHost().equals(base.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception e) { /* لا شيء */ }
                return true;
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) showConnectionError();
            }

            @Override
            public void onReceivedHttpError(WebView v, WebResourceRequest r, android.webkit.WebResourceResponse e) {
                // نفق منتهٍ يردّ 530 من Cloudflare لا خطأ شبكة: نعامله كانقطاع ونعيد قراءة العنوان
                if (r.isForMainFrame() && e.getStatusCode() >= 500) showConnectionError();
            }
            @Override
            public void onPageFinished(WebView v, String url) { errorShown = false; }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (chooser != null) chooser.onReceiveValue(null);
                chooser = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try { startActivityForResult(Intent.createChooser(i, "اختر ملفاً"), 7); }
                catch (Exception e) { chooser = null; return false; }
                return true;
            }
        });
        if (b != null) web.restoreState(b); else web.loadUrl(serverUrl() + "/");
        // العنوان قد يكون تغيّر (النفق بلا دومين): نتحقق في الخلفية ونعيد التحميل إن تغيّر
        new Thread(() -> { if (discover()) runOnUiThread(() -> web.loadUrl(serverUrl() + "/")); }).start();
    }

    @Override
    protected void onSaveInstanceState(Bundle o) { super.onSaveInstanceState(o); web.saveState(o); }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == 7 && chooser != null) {
            Uri[] r = null;
            if (res == RESULT_OK && data != null) {
                if (data.getData() != null) r = new Uri[]{data.getData()};
                else if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    r = new Uri[n];
                    for (int i = 0; i < n; i++) r[i] = data.getClipData().getItemAt(i).getUri();
                }
            }
            chooser.onReceiveValue(r);
            chooser = null;
        }
        super.onActivityResult(req, res, data);
    }

    @Override
    public void onBackPressed() { if (web.canGoBack()) web.goBack(); else super.onBackPressed(); }

    @Override
    protected void onPause() { CookieManager.getInstance().flush(); super.onPause(); }

    @Override
    protected void onResume() {
        super.onResume();
        long now = System.currentTimeMillis();
        if (now - lastCheck > 30 * 60 * 1000L) { lastCheck = now; checkForUpdate(); }
        if (now - lastDiscover > 60 * 1000L) { lastDiscover = now; new Thread(() -> { if (discover()) runOnUiThread(() -> web.loadUrl(serverUrl() + "/")); }).start(); }
    }

    // ---------- أخطاء الاتصال وتغيير العنوان ----------
    private void showConnectionError() {
        if (errorShown) return;
        errorShown = true;
        new Thread(() -> {
            if (discover()) { errorShown = false; runOnUiThread(() -> web.loadUrl(serverUrl() + "/")); return; }
            runOnUiThread(this::showErrorDialog);
        }).start();
    }

    private void showErrorDialog() {
        runOnUiThread(() -> new AlertDialog.Builder(this)
            .setTitle("تعذر الاتصال بالمخدم")
            .setMessage("تحقق من الإنترنت ثم أعد المحاولة. إن استمر الخطأ فربما تغيّر عنوان النظام بعد إعادة تشغيل المخدم.\n\nالعنوان الحالي:\n" + serverUrl() + "\n\nآخر فحص للعنوان الجديد:\n" + lastDiscovery)
            .setCancelable(false)
            .setPositiveButton("إعادة المحاولة", (d, w) -> { errorShown = false; web.loadUrl(serverUrl() + "/"); })
            .setNeutralButton("تغيير العنوان", (d, w) -> askServer())
            .setNegativeButton("إغلاق", (d, w) -> finish())
            .show());
    }

    private void askServer() {
        EditText t = new EditText(this);
        t.setText(serverUrl());
        t.setTextDirection(android.view.View.TEXT_DIRECTION_LTR);
        new AlertDialog.Builder(this).setTitle("عنوان النظام").setView(t)
            .setPositiveButton("حفظ", (d, w) -> {
                String u = t.getText().toString().trim();
                if (u.startsWith("http://") || u.startsWith("https://")) {
                    getSharedPreferences("ums", MODE_PRIVATE).edit().putString("server", u).apply();
                    errorShown = false;
                    web.loadUrl(serverUrl() + "/");
                } else { Toast.makeText(this, "يجب أن يبدأ العنوان بـ https://", Toast.LENGTH_LONG).show(); showConnectionError(); }
            })
            .setNegativeButton("إلغاء", (d, w) -> { errorShown = false; showConnectionError(); }).show();
    }

    // ---------- جسر الملفات: تنزيلات الواجهة (PDF، Excel، نسخ احتياطي) ----------
    private class Bridge {
        /** تستدعيها الواجهة حين تكتشف أن عنوان المخدم تغيّر: تحفظه وتعيد التحميل */
        @JavascriptInterface
        public void setServer(String u) {
            if (u == null) return;
            final String url = u.trim().replaceAll("/+$", "");
            if (!url.startsWith("https://") || url.contains(" ") || url.length() > 300) return;
            getSharedPreferences("ums", MODE_PRIVATE).edit().putString("server", url).apply();
            runOnUiThread(() -> { errorShown = false; web.loadUrl(serverUrl() + "/"); });
        }
        @JavascriptInterface
        public void saveFile(String name, String mime, String b64) {
            try {
                String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
                if (safe.isEmpty()) safe = "file";
                File dir = new File(getCacheDir(), "shared");
                dir.mkdirs();
                File f = new File(dir, safe);
                try (OutputStream o = new FileOutputStream(f)) { o.write(Base64.decode(b64, Base64.DEFAULT)); }
                final Uri uri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".files", f);
                final String m = (mime == null || mime.isEmpty()) ? "application/octet-stream" : mime;
                runOnUiThread(() -> {
                    Intent v = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, m).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try { startActivity(v); }
                    catch (ActivityNotFoundException e) {
                        Intent s = new Intent(Intent.ACTION_SEND).setType(m).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(s, "مشاركة الملف"));
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "تعذر حفظ الملف", Toast.LENGTH_LONG).show());
            }
        }
    }

    // ---------- تحديث التطبيق ----------
    private void checkForUpdate() {
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(serverUrl() + "/api/app/android?version=" + BuildConfig.VERSION_NAME).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                if (c.getResponseCode() != 200) return;
                StringBuilder sb = new StringBuilder();
                try (InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
                }
                JSONObject j = new JSONObject(sb.toString());
                if (!j.optBoolean("available")) return;
                final String ver = j.getString("version");
                final String path = j.getString("url");
                StringBuilder msg = new StringBuilder();
                JSONArray notes = j.optJSONArray("notes");
                if (notes != null) for (int i = 0; i < notes.length(); i++) {
                    JSONObject n = notes.getJSONObject(i);
                    msg.append("◼ ").append(n.optString("version")).append(" — ").append(n.optString("title")).append("\n");
                    JSONArray it = n.optJSONArray("items");
                    if (it != null) for (int k = 0; k < it.length(); k++) msg.append("   • ").append(it.getString(k)).append("\n");
                    msg.append("\n");
                }
                runOnUiThread(() -> new AlertDialog.Builder(this)
                    .setTitle("تحديث جديد للتطبيق " + ver)
                    .setMessage(msg.length() == 0 ? "يتوفر إصدار أحدث من التطبيق." : msg.toString())
                    .setPositiveButton("تحديث الآن", (d, w) -> downloadAndInstall(path))
                    .setNegativeButton("لاحقاً", null)
                    .show());
            } catch (Exception e) { /* لا إنترنت أو لا تحديث: نتجاهل بصمت */ }
        }).start();
    }

    private void downloadAndInstall(final String path) {
        final AlertDialog prog = new AlertDialog.Builder(this).setTitle("جارٍ تنزيل التحديث").setMessage("0%").setCancelable(false).show();
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(serverUrl() + path).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(30000);
                final int total = c.getContentLength();
                File dir = new File(getCacheDir(), "update");
                dir.mkdirs();
                File f = new File(dir, "UMS.apk");
                int got = 0, last = -1;
                try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(f)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        o.write(buf, 0, n);
                        got += n;
                        if (total > 0) {
                            final int pct = got * 100 / total;
                            if (pct != last) { last = pct; runOnUiThread(() -> prog.setMessage(pct + "%")); }
                        }
                    }
                }
                runOnUiThread(() -> { prog.dismiss(); installApk(f); });
            } catch (Exception e) {
                runOnUiThread(() -> { prog.dismiss(); Toast.makeText(this, "فشل تنزيل التحديث، حاول لاحقاً", Toast.LENGTH_LONG).show(); });
            }
        }).start();
    }

    private void installApk(File f) {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, "اسمح لـ UMS بتثبيت التطبيقات ثم ارجع وأعد فتح التطبيق للتحديث", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            lastCheck = 0;
            return;
        }
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", f);
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }
}
