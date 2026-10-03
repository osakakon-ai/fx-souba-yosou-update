package jp.osakakon.guitarchordsheet;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

final class Updater {
    private static final String UI_MANIFEST_URL =
        "https://raw.githubusercontent.com/osakakon-ai/fx-souba-yosou-update/main/guitar/ui/latest.json";
    private static final String APK_MANIFEST_URL =
        "https://raw.githubusercontent.com/osakakon-ai/fx-souba-yosou-update/main/guitar/latest.json";
    private static final int BUILTIN_UI_VERSION = 207;

    private final MainActivity activity;
    private volatile File pendingApk;

    Updater(MainActivity a) { activity = a; }

    void check() {
        new Thread(() -> {
            try {
                if (checkUiUpdate()) return;
                checkApkUpdate();
            } catch (Exception e) {
                toast("更新に失敗しました: " + e.getMessage());
            }
        }).start();
    }

    private boolean checkUiUpdate() throws Exception {
        JSONObject j = new JSONObject(readTextNoCache(UI_MANIFEST_URL));
        int remoteUi = j.getInt("uiVersion");
        String remoteName = j.optString("versionName", "");

        SharedPreferences prefs =
            activity.getSharedPreferences("gcs_updater", MainActivity.MODE_PRIVATE);
        int localUi = prefs.getInt("uiVersion", BUILTIN_UI_VERSION);

        if (remoteUi <= localUi) return false;

        toast("画面データ " + remoteName + " を更新しています");

        byte[] html = readBytesNoCache(j.getString("htmlUrl"));
        String expected = j.getString("sha256").trim().toLowerCase();
        String actual = sha256(html);
        if (!actual.equals(expected)) {
            throw new IOException("画面データの検証に失敗しました");
        }

        File dir = new File(activity.getFilesDir(), "web");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("更新用フォルダを作成できません");
        }

        File tmp = new File(dir, "index.html.tmp");
        File dst = new File(dir, "index.html");
        try (OutputStream os = new FileOutputStream(tmp, false)) {
            os.write(html);
            os.flush();
        }

        if (dst.exists() && !dst.delete()) {
            tmp.delete();
            throw new IOException("旧画面データを置き換えられません");
        }
        if (!tmp.renameTo(dst)) {
            tmp.delete();
            throw new IOException("新しい画面データを保存できません");
        }

        prefs.edit().putInt("uiVersion", remoteUi).apply();

        activity.runOnUiThread(() -> {
            Toast.makeText(activity, "更新しました", Toast.LENGTH_SHORT).show();
            activity.reloadAppPage();
        });
        return true;
    }

    private void checkApkUpdate() throws Exception {
        JSONObject j = new JSONObject(readTextNoCache(APK_MANIFEST_URL));
        int remoteCode = j.getInt("versionCode");
        String remoteName = j.optString("versionName", "");
        long installed = activity.getPackageManager()
            .getPackageInfo(activity.getPackageName(), 0).getLongVersionCode();
        int localCode = installed > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) installed;

        if (remoteCode <= localCode) {
            toast("最新版です");
            return;
        }

        toast("v" + remoteName + " をダウンロードしています");

        File dir = new File(activity.getCacheDir(), "updates");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("更新用フォルダを作成できません");
        }

        File apk = new File(dir, "Guitar-Chord-Sheet.apk");
        download(j.getString("apkUrl"), apk);

        String expected = j.getString("sha256").trim().toLowerCase();
        String actual = sha256(apk);
        if (!actual.equals(expected)) {
            apk.delete();
            throw new IOException("ダウンロードしたAPKの検証に失敗しました");
        }

        pendingApk = apk;
        install(apk);
    }

    void resumePendingInstall() {
        File apk = pendingApk;
        if (apk == null || !apk.exists()) return;
        if (Build.VERSION.SDK_INT < 26 ||
            activity.getPackageManager().canRequestPackageInstalls()) {
            install(apk);
        }
    }

    private String readTextNoCache(String u) throws Exception {
        return new String(readBytesNoCache(u), StandardCharsets.UTF_8);
    }

    private byte[] readBytesNoCache(String u) throws Exception {
        String sep = u.contains("?") ? "&" : "?";
        HttpURLConnection c = (HttpURLConnection)new URL(
            u + sep + "t=" + System.currentTimeMillis()).openConnection();
        c.setUseCaches(false);
        c.setRequestProperty("Cache-Control", "no-cache");
        c.setRequestProperty("Pragma", "no-cache");
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        try {
            int status = c.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("更新情報 HTTP " + status);
            }
            try (InputStream in = c.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    private void download(String u, File out) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL(u).openConnection();
        c.setUseCaches(false);
        c.setRequestProperty("Cache-Control", "no-cache");
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        try {
            int status = c.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("APK HTTP " + status);
            }
            try (InputStream in = c.getInputStream();
                 OutputStream os = new FileOutputStream(out, false)) {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) os.write(b, 0, n);
            }
        } finally {
            c.disconnect();
        }
    }

    private String sha256(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(data);
        return toHex(md.digest());
    }

    private String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) md.update(b, 0, n);
        }
        return toHex(md.digest());
    }

    private String toHex(byte[] digest) {
        StringBuilder s = new StringBuilder();
        for (byte x : digest) s.append(String.format("%02x", x));
        return s.toString();
    }

    private void install(File apk) {
        activity.runOnUiThread(() -> {
            if (Build.VERSION.SDK_INT >= 26 &&
                !activity.getPackageManager().canRequestPackageInstalls()) {
                Intent p = new Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(p);
                toast("このアプリからの更新を許可してください");
                return;
            }

            Uri uri = FileProvider.getUriForFile(
                activity, activity.getPackageName() + ".fileprovider", apk);

            Intent i = new Intent(Intent.ACTION_INSTALL_PACKAGE)
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
        });
    }

    private void toast(String s) {
        activity.runOnUiThread(() ->
            Toast.makeText(activity, s, Toast.LENGTH_LONG).show());
    }
}
