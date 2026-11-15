package fr.mesapplis.miroir;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;

/**
 * Miroir (version sans fil) : affiche et pilote le téléphone depuis la tablette, par le point d'accès du téléphone.
 * La page de Miroir est rangée DANS l'APK (assets/www) ; le relais (Relais.java) lui donne accès au réseau local.
 */
public class MainActivity extends Activity {
    private static final int CHOIX_FICHIER = 41;
    private WebView web;
    private ValueCallback<Uri[]> rappelFichier;
    private Relais relais;
    private boolean pleinEcran = true;
    private final Map<Integer, OutputStream> fichiers = new HashMap<>();
    private final Map<Integer, String> nomsFichiers = new HashMap<>();
    private int prochainFichier = 1;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        try {
            relais = new Relais(this);
            new Thread(relais, "relais").start();
        } catch (Exception e) {
            Toast.makeText(this, "Impossible de démarrer le relais sans fil", Toast.LENGTH_LONG).show();
        }

        final WebViewAssetLoader chargeur = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return chargeur.shouldInterceptRequest(r.getUrl());
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("appassets.androidplatform.net".equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (rappelFichier != null) rappelFichier.onReceiveValue(null);
                rappelFichier = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                if (p != null && p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try { startActivityForResult(Intent.createChooser(i, "Choisir un fichier"), CHOIX_FICHIER); }
                catch (Exception e) { rappelFichier = null; return false; }
                return true;
            }
        });
        web.addJavascriptInterface(new Pont(), "MiroirApp");

        setContentView(web);
        appliquerPleinEcran();
        if (b != null) web.restoreState(b);
        else web.loadUrl("https://appassets.androidplatform.net/assets/www/index.html");
    }

    private void appliquerPleinEcran() {
        View d = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = d.getWindowInsetsController();
            if (c == null) return;
            if (pleinEcran) {
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                c.hide(WindowInsets.Type.systemBars());
            } else {
                c.show(WindowInsets.Type.systemBars());
            }
        } else {
            d.setSystemUiVisibility(pleinEcran
                    ? (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN)
                    : 0);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) appliquerPleinEcran();
    }

    /** Adresse du point d'accès (= le téléphone) vue depuis le Wi-Fi de la tablette. */
    private String passerelleWifi() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c == null || !c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
                LinkProperties lp = cm.getLinkProperties(n);
                if (lp == null) continue;
                for (RouteInfo r : lp.getRoutes()) {
                    InetAddress g = r.getGateway();
                    if (r.isDefaultRoute() && g instanceof Inet4Address && !g.isAnyLocalAddress()) return g.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            int g = wm.getDhcpInfo().gateway;
            if (g != 0) return (g & 0xff) + "." + ((g >> 8) & 0xff) + "." + ((g >> 16) & 0xff) + "." + ((g >> 24) & 0xff);
        } catch (Exception ignored) {}
        return "";
    }

    /** Fonctions appelées depuis la page */
    class Pont {
        @JavascriptInterface public boolean estAppli() { return true; }
        @JavascriptInterface public String relais() { return relais != null ? relais.adresse() : ""; }
        @JavascriptInterface public String passerelle() { return passerelleWifi(); }

        @JavascriptInterface public void pleinEcran(boolean oui) {
            runOnUiThread(() -> { pleinEcran = oui; appliquerPleinEcran(); });
        }

        /** Enregistrement d'un fichier dans « Téléchargements », envoyé par morceaux (base64). */
        @JavascriptInterface public int fichierDebut(String nom, String type) {
            try {
                OutputStream o;
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Downloads.DISPLAY_NAME, nom);
                    cv.put(MediaStore.Downloads.MIME_TYPE, type == null || type.isEmpty() ? "application/octet-stream" : type);
                    cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (u == null) return -1;
                    o = getContentResolver().openOutputStream(u);
                } else {
                    File d = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!d.exists()) d.mkdirs();
                    o = new FileOutputStream(new File(d, nom));
                }
                synchronized (fichiers) {
                    int id = prochainFichier++;
                    fichiers.put(id, o);
                    nomsFichiers.put(id, nom);
                    return id;
                }
            } catch (Exception e) { return -1; }
        }

        @JavascriptInterface public boolean fichierMorceau(int id, String base64) {
            OutputStream o;
            synchronized (fichiers) { o = fichiers.get(id); }
            if (o == null) return false;
            try { o.write(Base64.decode(base64, Base64.DEFAULT)); return true; } catch (Exception e) { return false; }
        }

        @JavascriptInterface public boolean fichierFin(int id) {
            OutputStream o; String nom;
            synchronized (fichiers) { o = fichiers.remove(id); nom = nomsFichiers.remove(id); }
            if (o == null) return false;
            try { o.close(); } catch (Exception e) { return false; }
            final String n = nom;
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Enregistré dans Téléchargements : " + n, Toast.LENGTH_LONG).show());
            return true;
        }
    }

    @Override
    protected void onActivityResult(int code, int res, Intent data) {
        if (code == CHOIX_FICHIER && rappelFichier != null) {
            Uri[] r = null;
            if (res == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    r = new Uri[n];
                    for (int i = 0; i < n; i++) r[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    r = new Uri[]{ data.getData() };
                }
            }
            rappelFichier.onReceiveValue(r);
            rappelFichier = null;
            return;
        }
        super.onActivityResult(code, res, data);
    }

    @Override public void onBackPressed() { if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed(); }
    @Override protected void onSaveInstanceState(Bundle o) { super.onSaveInstanceState(o); if (web != null) web.saveState(o); }
    @Override protected void onDestroy() { if (relais != null) relais.arreter(); if (web != null) web.destroy(); super.onDestroy(); }
}
