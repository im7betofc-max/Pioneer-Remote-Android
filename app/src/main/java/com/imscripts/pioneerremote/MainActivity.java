package com.imscripts.pioneerremote;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.widget.Toast;

public class MainActivity extends Activity {
    private WebView webView;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Color.rgb(11, 11, 15));
        getWindow().setNavigationBarColor(Color.rgb(11, 11, 15));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        prefs = getSharedPreferences("pioneer", MODE_PRIVATE);
        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(8, 8, 11));
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        hideSystemBars();
        handleIntent(getIntent(), true);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent, false);
    }

    private void handleIntent(Intent intent, boolean allowLastServer) {
        Uri data = intent != null ? intent.getData() : null;
        if (data != null && "pioneer".equalsIgnoreCase(data.getScheme()) && "pair".equalsIgnoreCase(data.getHost())) {
            String server = data.getQueryParameter("server");
            String code = data.getQueryParameter("code");
            if (server != null && code != null && code.matches("\\d{6}")) {
                openPair(server, code);
                return;
            }
        }

        if (allowLastServer) {
            String lastServer = prefs.getString("lastServer", "");
            if (!lastServer.isEmpty()) {
                webView.loadUrl(lastServer);
                return;
            }
        }
        showStart();
    }

    private void openPair(String server, String code) {
        server = normalizeServer(server);
        if (server.isEmpty()) {
            Toast.makeText(this, "Endereco do Pioneer invalido.", Toast.LENGTH_LONG).show();
            showStart();
            return;
        }
        prefs.edit().putString("lastServer", server).apply();
        webView.loadUrl(server + "/?code=" + Uri.encode(code) + "&autoconnect=1");
    }

    private String normalizeServer(String value) {
        if (value == null) return "";
        String s = value.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (!(s.startsWith("http://") || s.startsWith("https://"))) return "";
        return s;
    }

    private void showStart() {
        webView.loadUrl("file:///android_asset/start.html");
    }

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
        else showStart();
    }
}
