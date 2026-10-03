package jp.osakakon.guitarchordsheet;

import android.widget.Toast;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Updater {
    private static final String UI_URL =
        "https://raw.githubusercontent.com/osakakon-ai/fx-souba-yosou-update/main/guitar/ui/index.html";
    private static final int MAX_UI_BYTES = 2 * 1024 * 1024;

    private final MainActivity activity;

    Updater(MainActivity a) {
        activity = a;
    }

    void check() {
        new Thread(() -> {
            try {
                byte[] remote = downloadLatestUi();
                validateUi(remote);

                String remoteHash = sha256(remote);
                File local = activity.downloadedUiFile();

                String currentHash;
                if (local.exists()) {
                    currentHash = sha256(local);
                } else {
                    currentHash = assetSha256("index.html");
                }

                String version = detectVersion(remote);

                if (remoteHash.equals(currentHash)) {
                    toast("最新版です" + (version.isEmpty() ? "" : "（" + version + "）"));
                    return;
                }

                installUi(remote, remoteHash);
                activity.runOnUiThread(() -> {
                    Toast.makeText(
                        activity,
                        "更新しました" + (version.isEmpty() ? "" : "（" + version + "）"),
                        Toast.LENGTH_SHORT
                    ).show();
                    activity.reloadAppPage();
                });
            } catch (Exception e) {
                toast("更新に失敗しました: " + safeMessage(e));
            }
        }).start();
    }

    private byte[] downloadLatestUi() throws Exception {
        String sep = UI_URL.contains("?") ? "&" : "?";
        URL url = new URL(UI_URL + sep + "t=" + System.currentTimeMillis());

        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setUseCaches(false);
        c.setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0");
        c.setRequestProperty("Pragma", "no-cache");
        c.setRequestProperty("Accept", "text/html,*/*");
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);

        try {
            int status = c.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("HTTP " + status);
            }

            int declared = c.getContentLength();
            if (declared > MAX_UI_BYTES) {
                throw new IOException("更新データが大きすぎます");
            }

            try (InputStream in = c.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_UI_BYTES) {
                        throw new IOException("更新データが大きすぎます");
                    }
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    private void validateUi(byte[] data) throws Exception {
        if (data == null || data.length < 1000) {
            throw new IOException("更新データが不完全です");
        }
        String head = new String(data, 0, Math.min(data.length, 4096), "UTF-8");
        String tail = new String(
            data,
            Math.max(0, data.length - Math.min(data.length, 4096)),
            Math.min(data.length, 4096),
            "UTF-8"
        );
        if (!head.contains("<!doctype html") ||
            !head.contains("ギターコード譜") ||
            !tail.contains("</html>")) {
            throw new IOException("更新データの形式が正しくありません");
        }
    }

    private void installUi(byte[] data, String expectedHash) throws Exception {
        File dst = activity.downloadedUiFile();
        File dir = dst.getParentFile();
        if (dir == null) throw new IOException("更新先が不正です");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("更新用フォルダを作成できません");
        }

        File tmp = new File(dir, "index.html.new");
        File bak = new File(dir, "index.html.bak");

        if (tmp.exists() && !tmp.delete()) {
            throw new IOException("一時ファイルを削除できません");
        }

        try (FileOutputStream out = new FileOutputStream(tmp, false)) {
            out.write(data);
            out.flush();
            out.getFD().sync();
        }

        if (!expectedHash.equals(sha256(tmp))) {
            tmp.delete();
            throw new IOException("保存前検証に失敗しました");
        }

        if (bak.exists() && !bak.delete()) {
            tmp.delete();
            throw new IOException("バックアップを削除できません");
        }

        boolean hadOld = dst.exists();
        if (hadOld && !dst.renameTo(bak)) {
            tmp.delete();
            throw new IOException("旧画面を退避できません");
        }

        if (!tmp.renameTo(dst)) {
            if (hadOld && bak.exists()) bak.renameTo(dst);
            tmp.delete();
            throw new IOException("新しい画面へ切り替えられません");
        }

        if (!expectedHash.equals(sha256(dst))) {
            dst.delete();
            if (hadOld && bak.exists()) bak.renameTo(dst);
            throw new IOException("保存後検証に失敗しました");
        }

        if (bak.exists()) bak.delete();
    }

    private String detectVersion(byte[] data) {
        try {
            String s = new String(data, "UTF-8");
            Matcher m = Pattern.compile("v\\d+\\.\\d+\\.\\d+").matcher(s);
            return m.find() ? m.group() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private String assetSha256(String name) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = activity.getAssets().open(name)) {
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) md.update(b, 0, n);
        }
        return toHex(md.digest());
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

    private String safeMessage(Exception e) {
        String m = e.getMessage();
        return (m == null || m.trim().isEmpty())
            ? e.getClass().getSimpleName()
            : m;
    }

    private void toast(String s) {
        activity.runOnUiThread(() ->
            Toast.makeText(activity, s, Toast.LENGTH_LONG).show());
    }
}
