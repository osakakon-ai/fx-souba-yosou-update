package jp.osakakon.guitarchordsheet;

import android.app.Activity;
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

public class MainActivity extends Activity {
    private WebView web;
    private Updater updater;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);

        // Android 15でもWebViewをシステムバーの下へ確実に配置する。
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

            // 一部端末/WebViewでinsetsが0になる場合の保険。
            int fallbackTop = getSystemBarDimension("status_bar_height");
            int fallbackBottom = getSystemBarDimension("navigation_bar_height");

            int top = Math.max(bars.top, fallbackTop);
            int bottom = Math.max(bars.bottom, fallbackBottom);

            v.setPadding(bars.left, top, bars.right, bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        setContentView(root);
        ViewCompat.requestApplyInsets(root);

        web.loadUrl("file:///android_asset/index.html");
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
