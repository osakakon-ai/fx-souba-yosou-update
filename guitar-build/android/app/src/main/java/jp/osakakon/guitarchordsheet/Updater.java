package jp.osakakon.guitarchordsheet;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

final class Updater {
    private static final String MANIFEST_URL =
        "https://raw.githubusercontent.com/osakakon-ai/fx-souba-yosou-update/main/guitar/latest.json";

    private final Activity activity;
    private volatile File pendingApk;

    Updater(Activity a) { activity = a; }

    void check() {
        new Thread(() -> {
            try {
                JSONObject j = new JSONObject(readTextNoCache(MANIFEST_URL));
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
            } catch (Exception e) {
                toast("更新に失敗しました: " + e.getMessage());
            }
        }).start();
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
        String sep = u.contains("?") ? "&" : "?";
        HttpURLConnection c = (HttpURLConnection)new URL(
            u + sep + "t=" + System.currentTimeMillis()).openConnection();
        c.setUseCaches(false);
        c.setRequestProperty("Cache-Control", "no-cache");
        c.setRequestProperty("Pragma", "no-cache");
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        try {
            int status = c.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("更新情報 HTTP " + status);
            }
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(c.getInputStream()))) {
                StringBuilder b = new StringBuilder();
                String x;
                while ((x = r.readLine()) != null) b.append(x);
                return b.toString();
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

    private String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) md.update(b, 0, n);
        }
        StringBuilder s = new StringBuilder();
        for (byte x : md.digest()) s.append(String.format("%02x", x));
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
