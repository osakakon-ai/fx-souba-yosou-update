package jp.osakakon.guitarchordsheet;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

public class MainActivity extends Activity {
    private WebView web;
    private Updater updater;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        updater = new Updater(this);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);
    }

    @Override public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    public final class Bridge {
        @JavascriptInterface public void checkForUpdate() {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "最新版を確認しています", Toast.LENGTH_SHORT).show());
            updater.check();
        }
    }
}
