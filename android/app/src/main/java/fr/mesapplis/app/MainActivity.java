package fr.mesapplis.app;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
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
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Coquille d'appli : affiche la page de l'appli rangée DANS l'APK (dossier assets/www).
 * Adresse interne sécurisée (https://appassets.androidplatform.net) : le capteur de mouvement est autorisé.
 * Aucun accès à internet. Les données restent dans la tablette.
 * Pont « AndroidApp » pour ce que la page ne sait pas faire seule ici : voix, vibration, enregistrer un fichier.
 */
public class MainActivity extends Activity {
    private static final int CHOIX_FICHIER = 41;
    private WebView web;
    private ValueCallback<Uri[]> rappelFichier;
    private TextToSpeech voix;
    private boolean voixPrete = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

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

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return chargeur.shouldInterceptRequest(r.getUrl());
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
                try { startActivityForResult(Intent.createChooser(i, "Choisir un fichier"), CHOIX_FICHIER); }
                catch (Exception e) { rappelFichier = null; return false; }
                return true;
            }
        });
        web.addJavascriptInterface(new Pont(), "AndroidApp");

        voix = new TextToSpeech(this, st -> {
            if (st == TextToSpeech.SUCCESS) { voix.setLanguage(Locale.FRANCE); voix.setSpeechRate(1.15f); voixPrete = true; }
        });

        setContentView(web);
        if (b != null) web.restoreState(b);
        else web.loadUrl("https://appassets.androidplatform.net/assets/www/index.html");
    }

    /** Fonctions appelées depuis la page */
    class Pont {
        @JavascriptInterface public boolean estAppli() { return true; }

        @JavascriptInterface public void parler(String t) {
            if (voixPrete) voix.speak(t, TextToSpeech.QUEUE_FLUSH, null, "c");
        }

        @JavascriptInterface public void vibrer(long ms) {
            try {
                Vibrator vb = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                if (vb == null) return;
                if (Build.VERSION.SDK_INT >= 26) vb.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
                else vb.vibrate(ms);
            } catch (Exception ignored) {}
        }

        /** Enregistre un fichier texte dans « Téléchargements ». Renvoie l'emplacement, ou "" en cas d'échec. */
        @JavascriptInterface public String enregistrer(String nom, String contenu, String type) {
            byte[] octets = contenu.getBytes(StandardCharsets.UTF_8);
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Downloads.DISPLAY_NAME, nom);
                    cv.put(MediaStore.Downloads.MIME_TYPE, type == null || type.isEmpty() ? "application/octet-stream" : type);
                    cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (u == null) return "";
                    try (OutputStream o = getContentResolver().openOutputStream(u)) { o.write(octets); }
                } else {
                    File d = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!d.exists()) d.mkdirs();
                    try (FileOutputStream o = new FileOutputStream(new File(d, nom))) { o.write(octets); }
                }
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Enregistré dans Téléchargements : " + nom, Toast.LENGTH_LONG).show());
                return "Téléchargements/" + nom;
            } catch (Exception e) {
                return "";
            }
        }
    }

    @Override
    protected void onActivityResult(int code, int res, Intent data) {
        if (code == CHOIX_FICHIER && rappelFichier != null) {
            Uri[] r = null;
            if (res == RESULT_OK && data != null && data.getData() != null) r = new Uri[]{ data.getData() };
            rappelFichier.onReceiveValue(r);
            rappelFichier = null;
            return;
        }
        super.onActivityResult(code, res, data);
    }

    @Override public void onBackPressed() { if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed(); }
    @Override protected void onSaveInstanceState(Bundle o) { super.onSaveInstanceState(o); if (web != null) web.saveState(o); }
    @Override protected void onDestroy() { if (voix != null) voix.shutdown(); super.onDestroy(); }
}
