package com.imscripts.pioneerremote;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

public class MainActivity extends Activity {
    private static final int REQ_CAMERA = 7021;
    private static final String DEFAULT_BROKER = "wss://broker.emqx.io:8084/mqtt";

    private WebView webView;
    private boolean waitingCameraScan = false;

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

        webView.addJavascriptInterface(new AndroidBridge(), "Android");
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleWebNavigation(request != null ? request.getUrl() : null);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Uri uri = null;
                try { uri = Uri.parse(url); } catch (Exception ignored) {}
                return handleWebNavigation(uri);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                installNativePairButtons();
            }
        });

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
        if (data != null && (openPairUri(data) || openHttpPairUri(data))) return;
        showStart();
    }

    private boolean handleWebNavigation(Uri uri) {
        if (uri == null) return false;
        String scheme = safe(uri.getScheme()).toLowerCase();

        if ("pioneer-scan".equals(scheme)) {
            startQrScanner();
            return true;
        }

        if ("pioneer-code".equals(scheme)) {
            String code = digits6(uri.getQueryParameter("code"));
            if (code.length() == 6) openManualCode(code);
            else Toast.makeText(this, "Digite os 6 números do Pioneer.", Toast.LENGTH_SHORT).show();
            return true;
        }

        if ("pioneer".equals(scheme)) {
            if (!openPairUri(uri)) {
                Toast.makeText(this, "Link de pareamento inválido.", Toast.LENGTH_SHORT).show();
            }
            return true;
        }

        return false;
    }

    private boolean openPairUri(Uri data) {
        if (data == null) return false;
        if (!"pioneer".equalsIgnoreCase(data.getScheme()) || !"pair".equalsIgnoreCase(data.getHost())) return false;

        String mode = safe(data.getQueryParameter("mode"));
        String broker = safe(data.getQueryParameter("broker"));
        String channel = safe(data.getQueryParameter("channel"));
        String code = digits6(data.getQueryParameter("code"));

        if (code.length() != 6) return false;
        if (broker.isEmpty()) broker = DEFAULT_BROKER;

        // QR antigo/alternativo sem channel: usa o canal determinístico do código.
        if (channel.isEmpty()) channel = "code-" + code;

        if (mode.isEmpty() || "internet".equalsIgnoreCase(mode)) {
            loadPairPage(broker, channel, code);
            return true;
        }
        return false;
    }

    private boolean openHttpPairUri(Uri data) {
        if (data == null) return false;
        String scheme = safe(data.getScheme());
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return false;

        String code = digits6(data.getQueryParameter("code"));
        if (code.length() != 6) return false;

        String broker = safe(data.getQueryParameter("broker"));
        String channel = safe(data.getQueryParameter("channel"));
        if (broker.isEmpty()) broker = DEFAULT_BROKER;
        if (channel.isEmpty()) channel = "code-" + code;
        loadPairPage(broker, channel, code);
        return true;
    }

    private void openManualCode(String code) {
        code = digits6(code);
        if (code.length() != 6) {
            Toast.makeText(this, "Digite os 6 números do Pioneer.", Toast.LENGTH_SHORT).show();
            return;
        }
        loadPairPage(DEFAULT_BROKER, "code-" + code, code);
    }

    private void loadPairPage(String broker, String channel, String code) {
        String hash = "mode=internet"
                + "&broker=" + Uri.encode(broker)
                + "&channel=" + Uri.encode(channel)
                + "&code=" + Uri.encode(code)
                + "&autoconnect=1";
        webView.loadUrl("file:///android_asset/start.html#" + hash);
    }

    private void showStart() {
        webView.loadUrl("file:///android_asset/start.html");
    }

    private void installNativePairButtons() {
        if (webView == null) return;

        // Não depende da ponte Android.scanQr. O clique vira uma URL interna,
        // interceptada nativamente acima. Também faz o código manual funcionar
        // mesmo se o JavaScript original do painel falhar em algum WebView.
        String js = "(function(){"
                + "var s=document.getElementById('scanBtn');"
                + "if(s){s.onclick=function(e){if(e)e.preventDefault();window.location.href='pioneer-scan://qr';return false;};}"
                + "var i=document.getElementById('manualCode');"
                + "var v=document.getElementById('pairCodeView');"
                + "if(i){i.oninput=function(){var c=(i.value||'').replace(/\\D/g,'').slice(0,6);i.value=c;if(v)v.textContent=c||'------';};}"
                + "var p=document.getElementById('pairBtn');"
                + "if(p){p.onclick=function(e){if(e)e.preventDefault();var c=i?(i.value||'').replace(/\\D/g,'').slice(0,6):'';"
                + "if(c.length!==6){if(v)v.textContent=c||'------';return false;}"
                + "window.location.href='pioneer-code://pair?code='+encodeURIComponent(c);return false;};}"
                + "})();";
        webView.evaluateJavascript(js, null);
    }

    private void startQrScanner() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            waitingCameraScan = true;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        launchQrScanner();
    }

    private void launchQrScanner() {
        waitingCameraScan = false;
        try {
            IntentIntegrator integrator = new IntentIntegrator(this);
            integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
            integrator.setPrompt("Aponte para o QR Code da aba Bluetooth do Pioneer");
            integrator.setBeepEnabled(false);
            integrator.setOrientationLocked(false);
            integrator.setCameraId(0);
            integrator.initiateScan();
        } catch (Exception e) {
            Toast.makeText(this, "Não foi possível abrir a câmera. Verifique a permissão da câmera.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (waitingCameraScan) launchQrScanner();
            } else {
                waitingCameraScan = false;
                Toast.makeText(this, "Permita o acesso à câmera para escanear o QR.", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null) {
            String contents = result.getContents();
            if (contents != null && !contents.trim().isEmpty()) {
                handleScannedContents(contents.trim());
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void handleScannedContents(String contents) {
        if (contents == null) return;
        String text = contents.trim();

        if (text.toUpperCase().startsWith("PIONEER-PAIR:")) {
            String code = digits6(text.substring("PIONEER-PAIR:".length()));
            if (code.length() == 6) {
                openManualCode(code);
                return;
            }
        }

        if (text.matches("\\d{6}")) {
            openManualCode(text);
            return;
        }

        Uri uri = null;
        try { uri = Uri.parse(text); } catch (Exception ignored) {}
        if (uri != null && (openPairUri(uri) || openHttpPairUri(uri))) return;

        Toast.makeText(this, "Este QR não pertence ao Pioneer Remote.", Toast.LENGTH_LONG).show();
    }

    private String digits6(String value) {
        return safe(value).replaceAll("\\D", "").substring(0, Math.min(6, safe(value).replaceAll("\\D", "").length()));
    }

    private String safe(String v) {
        return v == null ? "" : v.trim();
    }

    private class AndroidBridge {
        @JavascriptInterface
        public void scanQr() {
            runOnUiThread(() -> startQrScanner());
        }

        @JavascriptInterface
        public void pairCode(String code) {
            runOnUiThread(() -> openManualCode(code));
        }
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

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
