package com.imscripts.pioneerremote;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    private static final int REQ_CAMERA = 7021;
    private static final String RELAY = "https://ntfy.sh";
    private static final String NAMESPACE = "im-pioneer-7f39c24a-v3";

    private WebView webView;
    private boolean waitingCameraScan = false;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final SecureRandom secureRandom = new SecureRandom();
    private final Set<String> seenIds = Collections.synchronizedSet(new HashSet<>());
    private final AtomicBoolean pairTickBusy = new AtomicBoolean(false);
    private final AtomicBoolean sessionTickBusy = new AtomicBoolean(false);

    private ScheduledFuture<?> pairFuture;
    private ScheduledFuture<?> sessionFuture;
    private volatile boolean destroyed = false;
    private volatile boolean pairing = false;
    private volatile String code = "";
    private volatile String replyTopic = "";
    private volatile String channel = "";
    private volatile String token = "";
    private volatile long pairStartedAt = 0L;
    private volatile long lastPairPublishAt = 0L;
    private volatile int networkFailures = 0;

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
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);

        webView.addJavascriptInterface(new AndroidBridge(), "Android");
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                restoreSessionAfterPageLoad();
            }
        });

        loadSavedSession();
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
        webView.loadUrl("file:///android_asset/start.html");
        if (data == null) return;

        String scannedCode = digits6(data.getQueryParameter("code"));
        if (scannedCode.length() == 6) {
            final String c = scannedCode;
            webView.postDelayed(() -> startPairing(c), 500);
        }
    }

    private void loadSavedSession() {
        SharedPreferences p = getSharedPreferences("pioneer_cloud_v3", MODE_PRIVATE);
        code = p.getString("code", "");
        channel = p.getString("channel", "");
        token = p.getString("token", "");
    }

    private void saveSession() {
        getSharedPreferences("pioneer_cloud_v3", MODE_PRIVATE).edit()
                .putString("code", code)
                .putString("channel", channel)
                .putString("token", token)
                .apply();
    }

    private void clearSavedSession() {
        getSharedPreferences("pioneer_cloud_v3", MODE_PRIVATE).edit().clear().apply();
    }

    private void restoreSessionAfterPageLoad() {
        if (!token.isEmpty() && !channel.isEmpty()) {
            JSONObject j = new JSONObject();
            try {
                j.put("type", "pair_result");
                j.put("ok", true);
                j.put("restored", true);
                j.put("code", code);
            } catch (Exception ignored) {}
            emitMessage(j);
            startSessionPolling();
        } else if (!code.isEmpty()) {
            emitPairCode(code);
        }
    }

    private String pairTopic(String c) {
        return NAMESPACE + "-pair-" + c;
    }

    private String toMtaTopic() {
        return NAMESPACE + "-" + channel + "-mta";
    }

    private String toPhoneTopic() {
        return NAMESPACE + "-" + channel + "-phone";
    }

    private void startPairing(String rawCode) {
        final String c = digits6(rawCode);
        if (c.length() != 6) {
            emitError("Digite os 6 números do Pioneer.");
            return;
        }

        cancelPairingOnly();
        stopSessionPolling();
        code = c;
        channel = "";
        token = "";
        replyTopic = NAMESPACE + "-reply-" + randomHex(14);
        pairStartedAt = System.currentTimeMillis();
        lastPairPublishAt = 0L;
        networkFailures = 0;
        pairing = true;
        seenIds.clear();
        clearSavedSession();
        emitPairCode(c);
        emitPairStatus("CONECTANDO...", true);

        pairFuture = scheduler.scheduleAtFixedRate(this::pairTick, 0, 650, TimeUnit.MILLISECONDS);
    }

    private void pairTick() {
        if (destroyed || !pairing || !pairTickBusy.compareAndSet(false, true)) return;
        try {
            long now = System.currentTimeMillis();
            if (now - pairStartedAt > 30000L) {
                pairing = false;
                emitPairStatus("SEM RESPOSTA", false);
                emitError("Sem resposta do MTA. Gere um código novo e tente novamente.");
                cancelPairingOnly();
                return;
            }

            if (now - lastPairPublishAt > 1700L) {
                JSONObject req = new JSONObject();
                req.put("type", "pair");
                req.put("code", code);
                req.put("replyTopic", replyTopic);
                req.put("deviceName", "Android");
                req.put("requestId", randomHex(8));
                req.put("ts", now);
                if (postTopic(pairTopic(code), req.toString())) {
                    lastPairPublishAt = now;
                    setTransport(true);
                }
            }

            pollTopic(replyTopic, message -> {
                if (!pairing) return;
                String type = message.optString("type", "");
                if (!"pair_result".equals(type)) return;
                if (!message.optBoolean("ok", false)) {
                    pairing = false;
                    cancelPairingOnly();
                    emitPairStatus("NÃO CONECTOU", false);
                    emitError(message.optString("error", "Não foi possível conectar."));
                    return;
                }

                String newChannel = safeTopicPart(message.optString("channel", ""));
                String newToken = message.optString("token", "");
                if (newChannel.isEmpty() || newToken.length() < 16) {
                    emitError("Resposta de pareamento inválida.");
                    return;
                }

                channel = newChannel;
                token = newToken;
                pairing = false;
                saveSession();
                cancelPairingOnly();
                seenIds.clear();
                emitMessage(message);
                emitPairStatus("CONECTADO", false);
                startSessionPolling();
            });
        } catch (Exception e) {
            onNetworkFailure();
        } finally {
            pairTickBusy.set(false);
        }
    }

    private void startSessionPolling() {
        if (destroyed || token.isEmpty() || channel.isEmpty()) return;
        stopSessionPolling();
        sessionFuture = scheduler.scheduleAtFixedRate(this::sessionTick, 0, 550, TimeUnit.MILLISECONDS);
    }

    private void sessionTick() {
        if (destroyed || token.isEmpty() || channel.isEmpty() || !sessionTickBusy.compareAndSet(false, true)) return;
        try {
            pollTopic(toPhoneTopic(), message -> {
                String type = message.optString("type", "");
                if ("state".equals(type) && !token.equals(message.optString("token", ""))) return;
                emitMessage(message);
            });
            setTransport(true);
        } catch (Exception e) {
            onNetworkFailure();
        } finally {
            sessionTickBusy.set(false);
        }
    }

    private void sendCommand(String command, String payloadJson) {
        if (token.isEmpty() || channel.isEmpty()) {
            emitError("Conecte o celular ao Pioneer primeiro.");
            return;
        }
        io.execute(() -> {
            try {
                JSONObject payload;
                try { payload = new JSONObject(payloadJson == null || payloadJson.isEmpty() ? "{}" : payloadJson); }
                catch (Exception ignored) { payload = new JSONObject(); }
                JSONObject req = new JSONObject();
                req.put("type", "command");
                req.put("requestId", randomHex(8));
                req.put("token", token);
                req.put("command", command == null ? "" : command);
                req.put("payload", payload);
                if (!postTopic(toMtaTopic(), req.toString())) onNetworkFailure();
                else setTransport(true);
            } catch (Exception e) {
                onNetworkFailure();
            }
        });
    }

    private void disconnectCloud() {
        final String oldToken = token;
        final String oldChannel = channel;
        token = "";
        channel = "";
        code = "";
        pairing = false;
        cancelPairingOnly();
        stopSessionPolling();
        clearSavedSession();
        seenIds.clear();
        emitDisconnected();

        if (!oldToken.isEmpty() && !oldChannel.isEmpty()) {
            io.execute(() -> {
                try {
                    JSONObject req = new JSONObject();
                    req.put("type", "disconnect");
                    req.put("requestId", randomHex(8));
                    req.put("token", oldToken);
                    postTopic(NAMESPACE + "-" + oldChannel + "-mta", req.toString());
                } catch (Exception ignored) {}
            });
        }
    }

    private interface MessageHandler { void onMessage(JSONObject message) throws Exception; }

    private void pollTopic(String topic, MessageHandler handler) throws Exception {
        URL url = new URL(RELAY + "/" + topic + "/json?poll=1&since=30s");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(3500);
        c.setReadTimeout(4500);
        c.setUseCaches(false);
        c.setRequestProperty("Cache-Control", "no-cache");

        int status = c.getResponseCode();
        if (status < 200 || status >= 300) {
            c.disconnect();
            throw new IllegalStateException("HTTP " + status);
        }

        try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                JSONObject event;
                try { event = new JSONObject(line); }
                catch (Exception ignored) { continue; }
                if (!"message".equals(event.optString("event", ""))) continue;
                String id = event.optString("id", "");
                if (!id.isEmpty() && !seenIds.add(id)) continue;
                if (seenIds.size() > 350) seenIds.clear();
                String body = event.optString("message", "");
                if (body.isEmpty()) continue;
                try { handler.onMessage(new JSONObject(body)); }
                catch (Exception ignored) {}
            }
        } finally {
            c.disconnect();
        }
    }

    private boolean postTopic(String topic, String body) {
        HttpURLConnection c = null;
        try {
            URL url = new URL(RELAY + "/" + topic);
            c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(3500);
            c.setReadTimeout(4500);
            c.setDoOutput(true);
            c.setUseCaches(false);
            c.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(data.length);
            try (OutputStream os = c.getOutputStream()) { os.write(data); }
            int status = c.getResponseCode();
            InputStream stream = status >= 200 && status < 300 ? c.getInputStream() : c.getErrorStream();
            if (stream != null) stream.close();
            return status >= 200 && status < 300;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void onNetworkFailure() {
        networkFailures++;
        if (networkFailures >= 3) setTransport(false);
    }

    private void setTransport(boolean ok) {
        if (ok) networkFailures = 0;
        callJs("window.PioneerNative&&PioneerNative.onTransport(" + (ok ? "true" : "false") + ");");
    }

    private void emitMessage(JSONObject message) {
        callJs("window.PioneerNative&&PioneerNative.onMessage(" + JSONObject.quote(message.toString()) + ");");
    }

    private void emitPairCode(String c) {
        callJs("window.PioneerNative&&PioneerNative.onPairCode(" + JSONObject.quote(c) + ");");
    }

    private void emitPairStatus(String text, boolean busy) {
        callJs("window.PioneerNative&&PioneerNative.onPairStatus(" + JSONObject.quote(text) + "," + (busy ? "true" : "false") + ");");
    }

    private void emitError(String text) {
        callJs("window.PioneerNative&&PioneerNative.onError(" + JSONObject.quote(text) + ");");
    }

    private void emitDisconnected() {
        callJs("window.PioneerNative&&PioneerNative.onDisconnected();");
    }

    private void callJs(String js) {
        if (webView == null || destroyed) return;
        runOnUiThread(() -> {
            if (webView != null && !destroyed) webView.evaluateJavascript(js, null);
        });
    }

    private void cancelPairingOnly() {
        if (pairFuture != null) {
            pairFuture.cancel(false);
            pairFuture = null;
        }
    }

    private void stopSessionPolling() {
        if (sessionFuture != null) {
            sessionFuture.cancel(false);
            sessionFuture = null;
        }
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
            Toast.makeText(this, "Não foi possível abrir a câmera.", Toast.LENGTH_LONG).show();
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
            if (contents != null && !contents.trim().isEmpty()) handleScannedContents(contents.trim());
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void handleScannedContents(String text) {
        String c = "";
        if (text.toUpperCase().startsWith("PIONEER-PAIR:")) {
            c = digits6(text.substring("PIONEER-PAIR:".length()));
        } else if (text.matches("\\d{6}")) {
            c = text;
        } else {
            try { c = digits6(Uri.parse(text).getQueryParameter("code")); }
            catch (Exception ignored) {}
        }

        if (c.length() == 6) {
            startPairing(c);
        } else {
            Toast.makeText(this, "Este QR não pertence ao Pioneer Remote.", Toast.LENGTH_LONG).show();
        }
    }

    private String digits6(String value) {
        String d = value == null ? "" : value.replaceAll("\\D", "");
        return d.substring(0, Math.min(6, d.length()));
    }

    private String safeTopicPart(String value) {
        if (value == null) return "";
        String cleaned = value.replaceAll("[^A-Za-z0-9_-]", "");
        return cleaned.length() > 80 ? cleaned.substring(0, 80) : cleaned;
    }

    private String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        secureRandom.nextBytes(b);
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    private class AndroidBridge {
        @JavascriptInterface public void scanQr() { runOnUiThread(() -> startQrScanner()); }
        @JavascriptInterface public void pairCode(String value) { startPairing(value); }
        @JavascriptInterface public void command(String kind, String payloadJson) { sendCommand(kind, payloadJson); }
        @JavascriptInterface public void disconnect() { disconnectCloud(); }
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
        else if (token.isEmpty()) finish();
        else webView.loadUrl("file:///android_asset/start.html");
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        pairing = false;
        cancelPairingOnly();
        stopSessionPolling();
        scheduler.shutdownNow();
        io.shutdownNow();
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
