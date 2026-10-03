package jp.osakakon.guitarchordsheet;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final String HOME_SHORTCUT_ID = "guitar_chord_sheet_home";

    private WebView web;
    private Updater updater;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        updater = new Updater(this);
        web = new WebView(this);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xfff3f4f6);
        root.addView(web, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());

            int fallbackTop = getSystemBarDimension("status_bar_height");
            int fallbackBottom = getSystemBarDimension("navigation_bar_height");

            int top = Math.max(bars.top, fallbackTop);
            int bottom = Math.max(bars.bottom, fallbackBottom);

            v.setPadding(bars.left, top, bars.right, bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        setContentView(root);
        ViewCompat.requestApplyInsets(root);

        loadAppPage();
        requestHomeScreenShortcut();
    }

    void reloadAppPage() {
        loadAppPage();
    }

    private void loadAppPage() {
        File downloaded = new File(new File(getFilesDir(), "web"), "index.html");
        if (!downloaded.exists()) {
            web.loadUrl("file:///android_asset/index.html");
            return;
        }

        try (FileInputStream in = new FileInputStream(downloaded);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            String html = new String(out.toByteArray(), StandardCharsets.UTF_8);
            web.loadDataWithBaseURL(
                "file:///android_asset/",
                html,
                "text/html",
                "UTF-8",
                null);
        } catch (Exception e) {
            web.loadUrl("file:///android_asset/index.html");
        }
    }

    private void requestHomeScreenShortcut() {
        ShortcutManager shortcutManager = getSystemService(ShortcutManager.class);
        if (shortcutManager == null || !shortcutManager.isRequestPinShortcutSupported()) return;

        for (ShortcutInfo info : shortcutManager.getPinnedShortcuts()) {
            if (HOME_SHORTCUT_ID.equals(info.getId())) return;
        }

        Intent launchIntent = new Intent(this, MainActivity.class);
        launchIntent.setAction(Intent.ACTION_MAIN);
        launchIntent.addCategory(Intent.CATEGORY_LAUNCHER);

        ShortcutInfo shortcut = new ShortcutInfo.Builder(this, HOME_SHORTCUT_ID)
            .setShortLabel(getString(R.string.app_name))
            .setLongLabel(getString(R.string.app_name))
            .setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher))
            .setIntent(launchIntent)
            .build();

        shortcutManager.requestPinShortcut(shortcut, null);
    }

    private int getSystemBarDimension(String name) {
        int id = getResources().getIdentifier(name, "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    @Override protected void onResume() {
        super.onResume();
        if (updater != null) updater.resumePendingInstall();
    }

    @Override public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    public final class Bridge {
        @JavascriptInterface public void checkForUpdate() {
            runOnUiThread(() -> Toast.makeText(
                MainActivity.this, "最新版を確認しています", Toast.LENGTH_SHORT).show());
            updater.check();
        }
    }
}
