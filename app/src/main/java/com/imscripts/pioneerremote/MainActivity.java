package com.imscripts.pioneerremote;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Color.rgb(9, 10, 14));
        getWindow().setNavigationBarColor(Color.rgb(9, 10, 14));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(8, 9, 13));
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        hideSystemBars();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        Uri data = intent != null ? intent.getData() : null;
        if (data != null && "pioneer".equalsIgnoreCase(data.getScheme()) && "pair".equalsIgnoreCase(data.getHost())) {
            String mode = safe(data.getQueryParameter("mode"));
            String broker = safe(data.getQueryParameter("broker"));
            String room = safe(data.getQueryParameter("room"));
            String access = safe(data.getQueryParameter("access"));
            String code = safe(data.getQueryParameter("code"));
            if ("internet".equalsIgnoreCase(mode) && code.matches("\\d{6}") && !room.isEmpty() && !access.isEmpty()) {
                String hash = "mode=internet"
                    + "&broker=" + Uri.encode(broker)
                    + "&room=" + Uri.encode(room)
                    + "&access=" + Uri.encode(access)
                    + "&code=" + Uri.encode(code)
                    + "&autoconnect=1";
                webView.loadUrl("file:///android_asset/start.html#" + hash);
                return;
            }

            // Compatibilidade com o modo LAN antigo.
            String server = safe(data.getQueryParameter("server"));
            if (!server.isEmpty() && code.matches("\\d{6}")) {
                while (server.endsWith("/")) server = server.substring(0, server.length() - 1);
                webView.loadUrl(server + "/?code=" + Uri.encode(code) + "&autoconnect=1");
                return;
            }
        }
        webView.loadUrl("file:///android_asset/start.html");
    }

    private String safe(String v) { return v == null ? "" : v.trim(); }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else webView.loadUrl("file:///android_asset/start.html");
    }

    @Override
    protected void onDestroy() {
        if (webView != null) { webView.destroy(); webView = null; }
        super.onDestroy();
    }
}
