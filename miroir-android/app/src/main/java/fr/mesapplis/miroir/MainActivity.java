package fr.mesapplis.miroir;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
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
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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

    // ---------- Câble USB : activer le sans fil sur le téléphone ----------
    private static final String ACTION_PERMISSION = "fr.mesapplis.miroir.PERMISSION_USB";
    private UsbManager usb;
    private List<byte[]> clesEnAttente;
    private volatile boolean usbEnCours = false;

    private void versPage(String type, String texte) {
        if (web == null) return;
        final String js = "window.miroirUsbEtat && miroirUsbEtat(" + JSONObject.quote(type) + "," + JSONObject.quote(texte) + ")";
        web.post(() -> web.evaluateJavascript(js, null));
    }

    private void prevenirPageCable() {
        if (web != null) web.post(() -> web.evaluateJavascript("window.miroirCableBranche && miroirCableBranche()", null));
    }

    private final BroadcastReceiver recepteurUsb = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (ACTION_PERMISSION.equals(a)) {
                boolean ok = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                List<byte[]> cles = clesEnAttente; clesEnAttente = null;
                if (cles == null) {   // demande venue de la page (affichage par câble)
                    final String js = "window.miroirUsbPermission && miroirUsbPermission(" + ok + ")";
                    if (web != null) web.post(() -> web.evaluateJavascript(js, null));
                } else if (ok) demarrerSansFil(cles);
                else { usbEnCours = false; versPage("erreur", "Miroir n'a pas reçu l'autorisation d'utiliser le câble. Rebranche le câble et appuie sur « OK » (coche « Toujours » si proposé)."); }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a)) {
                prevenirPageCable();
            }
        }
    };

    private void activerSansFil(List<byte[]> cles) {
        if (usbEnCours) return;
        UsbDevice d = usb != null ? UsbAdb.trouverTelephone(usb) : null;
        if (d == null) { versPage("erreur", "Je ne vois pas le téléphone par le câble. Vérifie que le câble est bien branché et que le débogage USB est activé. Si la tablette affiche ⚡ (elle se recharge), le téléphone a pris la main sur le câble : débranche et rebranche."); return; }
        usbEnCours = true;
        if (!usb.hasPermission(d)) {
            clesEnAttente = cles;
            Intent i = new Intent(ACTION_PERMISSION).setPackage(getPackageName());
            int drapeaux = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
            usb.requestPermission(d, PendingIntent.getBroadcast(this, 0, i, drapeaux | PendingIntent.FLAG_UPDATE_CURRENT));
            versPage("info", "👉 Appuie sur « OK » pour autoriser Miroir à utiliser le câble.");
            return;
        }
        demarrerSansFil(cles);
    }

    private void demarrerSansFil(List<byte[]> cles) {
        usbEnCours = true;
        new Thread(() -> {
            UsbAdb lien = null;
            try {
                UsbDevice d = UsbAdb.trouverTelephone(usb);
                if (d == null) throw new java.io.IOException("absent");
                versPage("info", "🔌 Câble branché : passage du téléphone en sans fil…");
                lien = new UsbAdb(usb, d);
                String rep = new AdbProtocole(lien, cles).passerEnSansFil(5555, texte -> versPage("info", texte));
                versPage("ok", rep);
            } catch (Exception e) {
                String m = String.valueOf(e.getMessage());
                if (m.contains("occupe")) versPage("erreur", "Le câble est déjà utilisé par une autre appli (Chrome ?). Ferme Miroir dans Chrome, débranche et rebranche le câble.");
                else if (m.contains("refuse") || m.contains("delai")) versPage("erreur", "Le téléphone n'a pas accepté la connexion. Regarde l'écran du téléphone (« Autoriser » ?), puis débranche et rebranche le câble.");
                else versPage("erreur", "Le passage en sans fil a échoué (" + m + "). Débranche et rebranche le câble pour réessayer.");
            } finally {
                if (lien != null) lien.fermer();
                usbEnCours = false;
            }
        }, "usb-sansfil").start();
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        usb = (UsbManager) getSystemService(Context.USB_SERVICE);
        IntentFilter f = new IntentFilter(ACTION_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(recepteurUsb, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(recepteurUsb, f);

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
        // Taille du texte fixe : la taille de police de la tablette ne fait plus déborder les boutons
        s.setTextZoom(100);

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

        // L'appli se place entre les barres du système quand elles sont visibles (rien ne passe dessous)
        android.widget.FrameLayout cadre = new android.widget.FrameLayout(this);
        cadre.addView(web, new android.widget.FrameLayout.LayoutParams(-1, -1));
        if (Build.VERSION.SDK_INT >= 30) {
            cadre.setOnApplyWindowInsetsListener((v, ins) -> {
                android.graphics.Insets b2 = ins.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(b2.left, b2.top, b2.right, b2.bottom);
                return WindowInsets.CONSUMED;
            });
        } else {
            cadre.setFitsSystemWindows(true);
        }
        setContentView(cadre);
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


    /** Le téléphone répond-il sur le port ADB sans fil ? */
    private static boolean portOuvert(String ip, int delai) {
        try (Socket s = new Socket()) { s.connect(new InetSocketAddress(ip, 5555), delai); return true; } catch (Exception e) { return false; }
    }

    /**
     * Cherche le téléphone : d'abord les adresses connues (dernière adresse, passerelle Wi-Fi),
     * puis tous les appareils des réseaux locaux de cet appareil — y compris SON PROPRE point
     * d'accès (cas où c'est le Xiaomi / la tablette qui partage la connexion et le S23 qui s'y connecte).
     */
    private String chercherTelephoneSync(String indices) {
        List<String> connues = new ArrayList<>();
        for (String x : (indices == null ? "" : indices).split(",")) { x = x.trim(); if (!x.isEmpty() && !connues.contains(x)) connues.add(x); }
        String g = passerelleWifi(); if (!g.isEmpty() && !connues.contains(g)) connues.add(g);
        for (String ip : connues) if (portOuvert(ip, 600)) return ip;
        // Balayage des réseaux locaux (Wi-Fi client et point d'accès), en parallèle
        List<String> moi = new ArrayList<>();
        List<String> cibles = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String nom = ni.getName();
                if (nom.startsWith("rmnet") || nom.startsWith("ccmni") || nom.startsWith("dummy") || nom.startsWith("tun") || nom.startsWith("ims")) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (!(ia.getAddress() instanceof Inet4Address) || !ia.getAddress().isSiteLocalAddress()) continue;
                    byte[] b = ia.getAddress().getAddress();
                    moi.add(ia.getAddress().getHostAddress());
                    String base = (b[0] & 0xff) + "." + (b[1] & 0xff) + "." + (b[2] & 0xff) + ".";
                    for (int i = 1; i < 255; i++) { String ip = base + i; if (!cibles.contains(ip)) cibles.add(ip); }
                }
            }
        } catch (Exception ignored) {}
        cibles.removeAll(moi); cibles.removeAll(connues);
        if (cibles.isEmpty()) return "";
        AtomicReference<String> trouve = new AtomicReference<>("");
        ExecutorService pool = Executors.newFixedThreadPool(64);
        for (String ip : cibles) pool.execute(() -> { if (trouve.get().isEmpty() && portOuvert(ip, 400)) trouve.compareAndSet("", ip); });
        pool.shutdown();
        try { pool.awaitTermination(8, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        pool.shutdownNow();
        return trouve.get();
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
        @JavascriptInterface public void chercherTelephone(String indices) {
            new Thread(() -> {
                String ip = chercherTelephoneSync(indices);
                String js = "window.miroirTelephoneTrouve && miroirTelephoneTrouve(" + JSONObject.quote(ip) + ")";
                if (web != null) web.post(() -> web.evaluateJavascript(js, null));
            }, "recherche-tel").start();
        }

        /** Un téléphone avec le débogage USB est-il branché par câble ? */
        @JavascriptInterface public boolean usbPresent() { return usb != null && UsbAdb.trouverTelephone(usb) != null; }

        /** Miroir a-t-il le droit d'utiliser le téléphone branché par câble ? */
        @JavascriptInterface public boolean usbAutorise() {
            UsbDevice d = usb != null ? UsbAdb.trouverTelephone(usb) : null;
            return d != null && usb.hasPermission(d);
        }

        /** Demande l'autorisation (fenêtre Android) ; la réponse arrive dans miroirUsbPermission(oui/non). */
        @JavascriptInterface public void demanderUsb() {
            runOnUiThread(() -> {
                UsbDevice d = usb != null ? UsbAdb.trouverTelephone(usb) : null;
                if (d == null) { if (web != null) web.evaluateJavascript("window.miroirUsbPermission && miroirUsbPermission(false)", null); return; }
                clesEnAttente = null;
                Intent i = new Intent(ACTION_PERMISSION).setPackage(getPackageName());
                int drapeaux = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
                usb.requestPermission(d, PendingIntent.getBroadcast(MainActivity.this, 1, i, drapeaux | PendingIntent.FLAG_UPDATE_CURRENT));
            });
        }

        /** Passe le téléphone branché en mode sans fil. cles = liste JSON de clés (PKCS#8 en base64). */
        @JavascriptInterface public void activerSansFil(String clesJson) {
            List<byte[]> cles = new ArrayList<>();
            try {
                JSONArray a = new JSONArray(clesJson);
                for (int i = 0; i < a.length(); i++) cles.add(Base64.decode(a.getString(i), Base64.DEFAULT));
            } catch (Exception ignored) {}
            runOnUiThread(() -> MainActivity.this.activerSansFil(cles));
        }

        /** Cacher Miroir tout de suite (retour à l'écran d'accueil de la tablette) */
        @JavascriptInterface public void cacher() { runOnUiThread(() -> moveTaskToBack(true)); }
        /** Fermer complètement Miroir (après la mise en veille du téléphone) */
        @JavascriptInterface public void fermer() { runOnUiThread(() -> finishAndRemoveTask()); }

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

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (i != null && UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(i.getAction())) prevenirPageCable();
    }

    @Override public void onBackPressed() { if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed(); }
    @Override protected void onSaveInstanceState(Bundle o) { super.onSaveInstanceState(o); if (web != null) web.saveState(o); }
    // ---------- Arrière-plan : au bout de 30 s, Miroir se déconnecte et met le téléphone en veille ----------
    private final android.os.Handler minuteur = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean deconnecteEnArrierePlan = false;
    private final Runnable quitter = () -> {
        deconnecteEnArrierePlan = true;
        if (web != null) web.evaluateJavascript("window.miroirQuitter && miroirQuitter()", null);
    };

    @Override protected void onStop() {
        super.onStop();
        minuteur.postDelayed(quitter, 30000);
    }

    @Override protected void onStart() {
        super.onStart();
        minuteur.removeCallbacks(quitter);
        if (deconnecteEnArrierePlan && web != null) {
            deconnecteEnArrierePlan = false;
            web.evaluateJavascript("window.miroirRetour && miroirRetour()", null);
        }
    }

    @Override protected void onDestroy() { minuteur.removeCallbacks(quitter); if (web != null) web.evaluateJavascript("window.miroirQuitter && miroirQuitter()", null); try { unregisterReceiver(recepteurUsb); } catch (Exception ignored) {} if (relais != null) relais.arreter(); if (web != null) web.destroy(); super.onDestroy(); }
}
