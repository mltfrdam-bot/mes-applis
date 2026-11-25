package fr.mesapplis.activite;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Pattern;

/**
 * Journal d'activité — coquille Android (étape 1 : socle).
 *
 * La page de l'appli est rangée DANS l'APK (assets/www) et s'affiche sans internet.
 * Les données sont des fichiers du téléphone (dossier privé « donnees ») : base.json, sa copie précédente,
 * et une sauvegarde automatique par jour (les 7 dernières).
 * Pont « AndroidApp » : tout ce que la page ne sait pas faire seule (fichiers, presse-papiers, partage, batterie, voix).
 */
public class MainActivity extends Activity {
    LienManettes manTest;

    private static final int CHOIX_FICHIER = 41;
    private static final int CHOIX_CARNET = 42;
    /** 1.20 : le carnet intégré utilise un champ « fichier » classique (import de sauvegarde) : Android le sert ici. */
    private ValueCallback<Uri[]> choixCarnet;
    private static final int DEMANDE_NOTIF = 42;
    private static final int DEMANDE_BLUETOOTH = 43;
    private static final int DEMANDE_GPS = 44;
    private static final int NB_SAUVEGARDES = 7;
    private static final Pattern NOM_OK = Pattern.compile("^(sauvegardes/|enregistrements/)?[A-Za-z0-9._-]{1,80}$");
    private static final Pattern NOM_BRUT = Pattern.compile("^brut-[0-9]{1,16}\\.bin$");
    private static final Pattern NOM_TRACE = Pattern.compile("^gps-[0-9]{1,16}\\.txt$");
    private static final TimeZone REUNION = TimeZone.getTimeZone("Indian/Reunion");

    /** Vrai quand l'écran de l'appli est affiché (le service s'en sert pour s'arrêter quand il ne sert plus). */
    static volatile boolean visible = false;

    private WebView web;
    /** Essai n° 4 : le carnet a sa propre page, gardée en mémoire à côté du Journal (passage instantané, sans rechargement). */
    private WebView webCarnet;
    private FrameLayout cadre;
    private WebViewAssetLoader chargeurPages;
    static final String URL_JOURNAL = "https://appassets.androidplatform.net/assets/www/index.html";
    static final String URL_CARNET = "https://appassets.androidplatform.net/assets/www/carnet/index.html";
    private File dossier;
    private ToneGenerator son;
    private TextToSpeech voix;
    private boolean voixPrete = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dossier = new File(getFilesDir(), "donnees");
        if (!dossier.exists()) dossier.mkdirs();
        try { sauvegardeDuJour(); } catch (Exception ignored) {}
        reprendreSeance();
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::preparerCarnet, 4000);
        RappelEau.programmer(this);   // 💧 la vérification de l'eau tourne toutes les 30 min
        ServiceEau.lancerSiVoulu(this);   // 💧 notification permanente (si choisie) : garde aussi l'appli active

        chargeurPages = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();
        web = creerVue();

        voix = new TextToSpeech(this, st -> {
            if (st == TextToSpeech.SUCCESS) { voix.setLanguage(Locale.FRANCE); voix.setSpeechRate(1.15f); voixPrete = true; }
        });

        cadre = new FrameLayout(this);
        cadre.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(cadre);
        web.loadUrl(URL_JOURNAL);
        if ("carnet".equals(getIntent().getStringExtra("page"))) montrerCarnet();
    }

    /** Une page web de l'appli : réglages, fichiers de l'appli, liens externes, choix de fichier, pont « AndroidApp ». */
    private WebView creerVue() {
        WebView v0 = new WebView(this);
        WebSettings s = v0.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setTextZoom(100);
        v0.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return chargeurPages.shouldInterceptRequest(r.getUrl());
            }
            /** Journal ↔ Carnet : on montre l'autre page déjà ouverte au lieu de recharger.
             *  Les liens vers l'extérieur (WhatsApp, SMS, sites) s'ouvrent dans l'appli qui convient. */
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("appassets.androidplatform.net".equals(u.getHost())) {
                    String chemin = u.getPath() == null ? "" : u.getPath();
                    if (v == web && chemin.startsWith("/assets/www/carnet/")) { montrerCarnet(); return true; }
                    if (v == webCarnet && chemin.equals("/assets/www/index.html")) { montrerJournal(); return true; }
                    return false;
                }
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception e) { Toast.makeText(MainActivity.this, "Aucune appli pour ouvrir ce lien.", Toast.LENGTH_SHORT).show(); }
                return true;
            }
        });
        v0.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (choixCarnet != null) choixCarnet.onReceiveValue(null);
                choixCarnet = cb;
                try {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    startActivityForResult(i, CHOIX_CARNET);
                    return true;
                } catch (Exception e) {
                    choixCarnet = null;
                    return false;
                }
            }
        });
        v0.addJavascriptInterface(new Pont(), "AndroidApp");
        return v0;
    }

    /** Le carnet est chargé en arrière-plan quelques secondes après l'ouverture : l'eau et la santé notées depuis le Journal y sont rangées tout de suite. */
    void preparerCarnet() {
        if (cadre == null || webCarnet != null) return;
        webCarnet = creerVue();
        webCarnet.setVisibility(View.GONE);
        cadre.addView(webCarnet, 0, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        webCarnet.loadUrl(URL_CARNET);
    }

    void montrerCarnet() {
        if (cadre == null) return;
        preparerCarnet();
        webCarnet.setVisibility(View.VISIBLE);
        web.setVisibility(View.GONE);
        webCarnet.evaluateJavascript("window.dispatchEvent && window.dispatchEvent(new Event('apk-visible'))", null);
    }

    void montrerJournal() {
        if (webCarnet != null) webCarnet.setVisibility(View.GONE);
        web.setVisibility(View.VISIBLE);
    }

    static String sansMaj(String j) { return j == null ? "" : j.replaceAll("\"maj\":\\d+", ""); }

    boolean carnetVisible() { return webCarnet != null && webCarnet.getVisibility() == View.VISIBLE; }

    /** Une séance était en cours quand l'appli a été fermée : on relance le service, qui reprend l'enregistrement. */
    private void reprendreSeance() {
        try {
            if (ServiceSeance.actif == null) ServiceSeance.cloturerVieuxJournal(this);
            if (ServiceSeance.actif == null && ServiceSeance.aReprendre(this) && (LienH10.droit(this) || ServiceSeance.droitGps(this)))
                startForegroundService(new Intent(this, ServiceSeance.class).setAction(ServiceSeance.ACTION_REPRENDRE));
        } catch (Exception ignored) {}
    }

    private boolean lancerService(String action, String act) { return lancerService(action, act, false); }

    /** Le service a besoin d'au moins une raison de tourner : la ceinture (Bluetooth autorisé) ou le GPS (position autorisée). */
    private boolean lancerService(String action, String act, boolean gps) {
        try {
            boolean avecGps = gps && ServiceSeance.droitGps(this);
            if (!LienH10.droit(this) && !avecGps) return false;
            Intent i = new Intent(this, ServiceSeance.class).setAction(action);
            if (act != null) i.putExtra(ServiceSeance.EXTRA_ACT, act);
            if (avecGps) i.putExtra(ServiceSeance.EXTRA_GPS, true);
            startForegroundService(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Lance un bout de code dans la page (depuis n'importe quel fil). */
    private void js(final String code) {
        runOnUiThread(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }

    // ---------- Fichiers de données ----------

    private File fichier(String nom) {
        if (nom == null || nom.contains("..") || !NOM_OK.matcher(nom).matches()) return null;
        return new File(dossier, nom);
    }

    private static String lireTexte(File f) throws IOException {
        try (InputStream in = new FileInputStream(f); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] bloc = new byte[65536];
            int n;
            while ((n = in.read(bloc)) > 0) out.write(bloc, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void copier(File de, File vers) throws IOException {
        try (InputStream in = new FileInputStream(de); OutputStream out = new FileOutputStream(vers)) {
            byte[] bloc = new byte[65536];
            int n;
            while ((n = in.read(bloc)) > 0) out.write(bloc, 0, n);
        }
    }

    /** Écrit un fichier en entier, ou pas du tout. Pour base.json, la version d'avant est gardée en « .prec ». */
    private synchronized boolean ecrireFichier(File f, String contenu) {
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            File tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream o = new FileOutputStream(tmp)) {
                o.write(contenu.getBytes(StandardCharsets.UTF_8));
                o.getFD().sync();
            }
            if (f.exists()) {
                if (f.getName().equals("base.json")) {
                    File prec = new File(f.getPath() + ".prec");
                    if (prec.exists()) prec.delete();
                    if (!f.renameTo(prec)) f.delete();
                } else if (!f.delete()) {
                    return false;
                }
            }
            return tmp.renameTo(f);
        } catch (Exception e) {
            return false;
        }
    }

    /** Une sauvegarde automatique par jour, à l'ouverture de l'appli. Les 7 dernières sont gardées. */
    private void sauvegardeDuJour() throws IOException {
        File base = new File(dossier, "base.json");
        if (!base.exists() || base.length() == 0) return;
        File dir = new File(dossier, "sauvegardes");
        if (!dir.exists()) dir.mkdirs();
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(REUNION);
        File duJour = new File(dir, "journal-" + f.format(new Date()) + ".json");
        if (!duJour.exists()) copier(base, duJour);
        File[] tous = dir.listFiles();
        if (tous == null) return;
        Arrays.sort(tous, Collections.reverseOrder());
        for (int i = NB_SAUVEGARDES; i < tous.length; i++) tous[i].delete();
    }

    // ---------- Fonctions appelées depuis la page ----------

    class Pont {
        @JavascriptInterface public boolean estAppli() { return true; }

        @JavascriptInterface public String version() {
            try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
        }

        @JavascriptInterface public String lire(String nom) {
            File f = fichier(nom);
            if (f == null || !f.exists()) return "";
            try { return lireTexte(f); } catch (Exception e) { return ""; }
        }

        @JavascriptInterface public boolean ecrire(String nom, String contenu) {
            File f = fichier(nom);
            if (f == null || contenu == null || nom.contains("/")) return false;
            return ecrireFichier(f, contenu);
        }

        /** Liste des sauvegardes automatiques : [{nom, jour, taille}], la plus récente d'abord. */
        @JavascriptInterface public String sauvegardesAuto() {
            JSONArray l = new JSONArray();
            try {
                File[] tous = new File(dossier, "sauvegardes").listFiles();
                if (tous == null) return "[]";
                Arrays.sort(tous, Collections.reverseOrder());
                for (File f : tous) {
                    String n = f.getName();                                   // journal-2026-11-12.json
                    if (!n.startsWith("journal-") || !n.endsWith(".json") || n.length() != 23) continue;
                    JSONObject o = new JSONObject();
                    o.put("nom", n);
                    o.put("jour", n.substring(16, 18) + "/" + n.substring(13, 15) + "/" + n.substring(8, 12));
                    o.put("taille", f.length());
                    l.put(o);
                }
            } catch (Exception ignored) {}
            return l.toString();
        }

        @JavascriptInterface public boolean copier(final String texte) {
            runOnUiThread(() -> {
                try {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Journal d'activité", texte));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Copie impossible : texte trop long. Utilise 💾 Fichier.", Toast.LENGTH_LONG).show();
                }
            });
            return true;
        }

        /** 1.20 : un fichier quelconque (PDF du carnet…) donné en base64, rangé dans « Téléchargements » avec un petit message. */
        @JavascriptInterface public String enregistrerB64(String nom, String b64, String type) {
            String r;
            try { r = enregistrerOctets(nom, android.util.Base64.decode(b64, android.util.Base64.DEFAULT), type); } catch (Exception e) { r = ""; }
            final String m = r.isEmpty() ? "⚠️ Le fichier n'a pas pu être enregistré." : "✅ Enregistré dans " + r;
            runOnUiThread(() -> Toast.makeText(MainActivity.this, m, Toast.LENGTH_LONG).show());
            return r;
        }

        /** Enregistre un fichier texte dans « Téléchargements ». Renvoie l'emplacement, ou "" en cas d'échec. */
        @JavascriptInterface public String enregistrer(String nom, String contenu, String type) {
            return enregistrerOctets(nom, contenu.getBytes(StandardCharsets.UTF_8), type);
        }

        String enregistrerOctets(String nom, byte[] octets, String type) {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Downloads.DISPLAY_NAME, nom);
                    cv.put(MediaStore.Downloads.MIME_TYPE, type == null || type.isEmpty() ? "application/octet-stream" : type);
                    cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (u == null) return "";
                    try (OutputStream o = getContentResolver().openOutputStream(u)) {
                        if (o == null) return "";
                        o.write(octets);
                    }
                } else {
                    File d = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!d.exists()) d.mkdirs();
                    try (FileOutputStream o = new FileOutputStream(new File(d, nom))) { o.write(octets); }
                }
                return "Téléchargements/" + nom;
            } catch (Exception e) {
                return "";
            }
        }

        /** Ouvre le menu « Partager » d'Android avec un fichier (Google Drive, etc.). */
        @JavascriptInterface public boolean partager(String nom, String contenu, String type) {
            try {
                if (nom == null || nom.contains("/") || nom.contains("..")) return false;
                File dir = new File(getCacheDir(), "partage");
                if (!dir.exists()) dir.mkdirs();
                File[] vieux = dir.listFiles();
                if (vieux != null) for (File v : vieux) v.delete();
                File f = new File(dir, nom);
                try (FileOutputStream o = new FileOutputStream(f)) { o.write(contenu.getBytes(StandardCharsets.UTF_8)); }
                final Uri u = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fichiers", f);
                final Intent i = new Intent(Intent.ACTION_SEND);
                i.setType(type == null || type.isEmpty() ? "application/octet-stream" : type);
                i.putExtra(Intent.EXTRA_STREAM, u);
                i.putExtra(Intent.EXTRA_SUBJECT, nom);
                i.setClipData(ClipData.newRawUri(nom, u));
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                runOnUiThread(() -> {
                    try { startActivity(Intent.createChooser(i, "Envoyer la sauvegarde")); } catch (Exception ignored) {}
                });
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        /** Laisse choisir un fichier (sauvegarde du Hub ou de l'appli). La suite arrive dans la page par surFichier(). */
        @JavascriptInterface public void choisirFichier() {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    startActivityForResult(i, CHOIX_FICHIER);
                } catch (Exception e) {
                    js("window.surFichier && window.surFichier(false, " + JSONObject.quote("Impossible d'ouvrir le choix de fichier sur ce téléphone.") + ")");
                }
            });
        }

        /** Vrai quand l'appli est sortie de l'optimisation de batterie (« Non restreinte » chez Samsung). */
        @JavascriptInterface public boolean batterieOk() {
            try {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                return pm == null || pm.isIgnoringBatteryOptimizations(getPackageName());
            } catch (Exception e) {
                return true;
            }
        }

        @JavascriptInterface public void ouvrirBatterie() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) {}
                }
            });
        }

        @JavascriptInterface public boolean notifOk() {
            try {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                return nm == null || nm.areNotificationsEnabled();
            } catch (Exception e) {
                return true;
            }
        }

        /** 1re fois : la question d'Android. Ensuite (si refusé) : l'écran des notifications de l'appli. */
        @JavascriptInterface public void demanderNotif() {
            runOnUiThread(() -> {
                try {
                    SharedPreferences p = getSharedPreferences("reglages", MODE_PRIVATE);
                    if (Build.VERSION.SDK_INT >= 33 && !p.getBoolean("notifDemandee", false)) {
                        p.edit().putBoolean("notifDemandee", true).apply();
                        requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, DEMANDE_NOTIF);
                    } else if (Build.VERSION.SDK_INT >= 26) {
                        startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
                    } else {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
                    }
                } catch (Exception ignored) {}
            });
        }

        // ----- Ceinture H10 et séance (étape 2) -----

        /** {"present", "droit", "allume"} : état du Bluetooth du téléphone. */
        @JavascriptInterface public String btEtat() {
            boolean droit = LienH10.droit(MainActivity.this), present = false, allume = false;
            try {
                BluetoothAdapter a = LienH10.adaptateur(MainActivity.this);
                present = a != null;
                allume = a != null && a.isEnabled();
            } catch (Exception ignored) {}
            return "{\"present\":" + present + ",\"droit\":" + droit + ",\"allume\":" + allume + "}";
        }

        /** Demande l'autorisation Bluetooth (1re fois : la question d'Android ; ensuite : l'écran de l'appli), puis d'allumer le Bluetooth. */
        @JavascriptInterface public void btDemander() {
            runOnUiThread(() -> {
                try {
                    if (!LienH10.droit(MainActivity.this)) {
                        SharedPreferences p = getSharedPreferences("reglages", MODE_PRIVATE);
                        if (!p.getBoolean("btDemande", false) || shouldShowRequestPermissionRationale("android.permission.BLUETOOTH_CONNECT")) {
                            p.edit().putBoolean("btDemande", true).apply();
                            requestPermissions(new String[]{"android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"}, DEMANDE_BLUETOOTH);
                        } else {
                            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
                        }
                        return;
                    }
                    BluetoothAdapter a = LienH10.adaptateur(MainActivity.this);
                    if (a != null && !a.isEnabled()) startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
                } catch (Exception ignored) {}
            });
        }

        /** Connecte la ceinture (démarre le service permanent). Faux si le Bluetooth n'est pas autorisé. */
        @JavascriptInterface public boolean h10Connecter() { return lancerService(ServiceSeance.ACTION_CONNECTER, null); }

        /** Déconnecte la ceinture. Refusé (faux) pendant un enregistrement. */
        @JavascriptInterface public boolean h10Arreter() { ServiceSeance s = ServiceSeance.actif; return s == null || s.arreterSiLibre(); }

        /** Démarre l'enregistrement d'une séance pour cette activité. */
        @JavascriptInterface public boolean seanceDemarrer(String act) { return lancerService(ServiceSeance.ACTION_DEMARRER, act == null ? "" : act); }

        // ----- GPS (étape 4) -----

        /** {"droit", "toujours", "allume"} : la position est-elle autorisée (précise), autorisée même appli fermée, et allumée sur le téléphone ? */
        @JavascriptInterface public String gpsEtat() {
            boolean droit = ServiceSeance.droitGps(MainActivity.this), toujours = false, allume = false;
            try { toujours = checkSelfPermission("android.permission.ACCESS_BACKGROUND_LOCATION") == PackageManager.PERMISSION_GRANTED; } catch (Exception ignored) {}
            try {
                LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
                allume = lm != null && lm.isLocationEnabled() && lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
            } catch (Exception ignored) {}
            return "{\"droit\":" + droit + ",\"toujours\":" + toujours + ",\"allume\":" + allume + "}";
        }

        /** Demande l'autorisation de position (1re fois : la question d'Android ; ensuite : l'écran de l'appli), puis d'allumer la position. */
        @JavascriptInterface public void gpsDemander() {
            runOnUiThread(() -> {
                try {
                    if (!ServiceSeance.droitGps(MainActivity.this)) {
                        SharedPreferences p = getSharedPreferences("reglages", MODE_PRIVATE);
                        if (!p.getBoolean("gpsDemande", false) || shouldShowRequestPermissionRationale("android.permission.ACCESS_FINE_LOCATION")) {
                            p.edit().putBoolean("gpsDemande", true).apply();
                            requestPermissions(new String[]{"android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"}, DEMANDE_GPS);
                        } else {
                            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
                        }
                        return;
                    }
                    LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
                    if (lm == null || !lm.isLocationEnabled() || !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                } catch (Exception ignored) {}
            });
        }

        /** Position « toujours autorisée » (conseillé sur Samsung) : le GPS peut repartir tout seul si le téléphone a coupé l'appli. Android ouvre son écran de réglage. */
        @JavascriptInterface public void gpsToujours() {
            runOnUiThread(() -> {
                try {
                    if (!ServiceSeance.droitGps(MainActivity.this)) return;
                    SharedPreferences p = getSharedPreferences("reglages", MODE_PRIVATE);
                    if (!p.getBoolean("gpsToujoursDemande", false) || shouldShowRequestPermissionRationale("android.permission.ACCESS_BACKGROUND_LOCATION")) {
                        p.edit().putBoolean("gpsToujoursDemande", true).apply();
                        requestPermissions(new String[]{"android.permission.ACCESS_BACKGROUND_LOCATION"}, DEMANDE_GPS);
                    } else {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
                    }
                } catch (Exception ignored) {}
            });
        }

        /** Réglages du GPS gardés côté Android : source ("sat" = satellites seuls, "mix" = position mélangée) et vibration à chaque kilomètre. */
        @JavascriptInterface public void gpsRegler(String source, boolean km) {
            getSharedPreferences("reglages", MODE_PRIVATE).edit().putString("gpsSource", "mix".equals(source) ? "mix" : "sat").putBoolean("gpsKm", km).apply();
            ServiceSeance s = ServiceSeance.actif;
            if (s != null) s.reglerGps(km);
        }

        /** Refait le calcul d'un tracé gardé (« gps-….txt ») avec le calcul d'aujourd'hui et renvoie son résumé. Vide si le fichier manque. Sert au fantôme. */
        @JavascriptInterface public String traceResume(String nom) {
            try {
                if (nom == null || !NOM_TRACE.matcher(nom).matches()) return "";
                Parcours p = Parcours.relire(new File(new File(dossier, "enregistrements"), nom));
                return p == null ? "" : p.resumeJson(p.finConnue());
            } catch (Exception e) {
                return "";
            }
        }

        /** Rythme des annonces du fantôme : "m500", "km1", "min1", "min2" ou "non" (pas de voix). */
        @JavascriptInterface public void fantomeRegler(String rythme) {
            getSharedPreferences("reglages", MODE_PRIVATE).edit().putString("fantomeRythme", rythme == null ? "m500" : rythme).apply();
            ServiceSeance s = ServiceSeance.actif;
            if (s != null) s.reglerFantome(rythme);
        }

        /** 1.14 : mode du home-trainer (0 libre, 1 ERG en watts, 2 pente en %, 3 résistance en %). */
        @JavascriptInterface public boolean kickrRegler(int mode, double valeur) { ServiceSeance s = ServiceSeance.actif; return s != null && s.reglerKickr(mode, valeur); }

        /** 1.13 : coupe ou remet la voix (renvoie true si la voix parle). */
        @JavascriptInterface public boolean voixBasculer() {
            ServiceSeance s = ServiceSeance.actif;
            if (s != null) return s.basculerVoix();
            SharedPreferences p = getSharedPreferences("reglages", MODE_PRIVATE); boolean v = !p.getBoolean("voixOn", true); p.edit().putBoolean("voixOn", v).apply(); return v;
        }

        /** 1.13 : montre ou cache la prévision d'arrivée (renvoie true si elle est montrée). */
        @JavascriptInterface public boolean prevBasculer() {
            ServiceSeance s = ServiceSeance.actif;
            if (s != null) return s.basculerPrev();
            SharedPreferences p = getSharedPreferences("reglages", MODE_PRIVATE); boolean v = !p.getBoolean("prevOn", true); p.edit().putBoolean("prevOn", v).apply(); return v;
        }

        /** Démarre l'enregistrement d'une séance avec le GPS (la ceinture reste utilisée si le Bluetooth est autorisé). */
        @JavascriptInterface public boolean seanceDemarrerGps(String act) { return lancerService(ServiceSeance.ACTION_DEMARRER, act == null ? "" : act, true); }

        /** Marque un tour à cet instant. Faux s'il n'y a pas de séance GPS en cours. */
        @JavascriptInterface public boolean seanceTour() { ServiceSeance s = ServiceSeance.actif; return s != null && s.tour(); }

        /** Tracés GPS complets gardés dans le téléphone : [{"nom":"gps-….txt","taille":…}, …]. */
        @JavascriptInterface public String traces() {
            JSONArray l = new JSONArray();
            try {
                File[] tous = new File(dossier, "enregistrements").listFiles();
                if (tous != null) {
                    Arrays.sort(tous);
                    for (File f : tous) {
                        if (!NOM_TRACE.matcher(f.getName()).matches()) continue;
                        JSONObject o = new JSONObject();
                        o.put("nom", f.getName());
                        o.put("taille", f.length());
                        l.put(o);
                    }
                }
            } catch (Exception ignored) {}
            return l.toString();
        }

        /** Termine l'enregistrement : renvoie le nom du fichier « enregistrements/rec-….json », ou "" en cas d'échec. */
        @JavascriptInterface public String seanceArreter() { ServiceSeance s = ServiceSeance.actif; return s == null ? "" : s.terminer(); }

        @JavascriptInterface public String seanceEtat() {
            ServiceSeance s = ServiceSeance.actif;
            try { return s != null ? s.etatJson() : ServiceSeance.etatHorsService(MainActivity.this); } catch (Exception e) { return "{\"service\":false,\"enr\":null}"; }
        }

        @JavascriptInterface public void seanceActivite(String act) { ServiceSeance s = ServiceSeance.actif; if (s != null && act != null) s.activite(act); }

        /** Réglage des impacts (seuil et réarmement en millièmes de g, bip oui/non) : gardé aussi côté Android pour une reprise sans écran. */
        @JavascriptInterface public void impRegler(int seuil, int rearme, boolean bip) { impRegler4(seuil, rearme, bip, 400); }

        /** Pareil, avec le temps minimum entre deux impacts en millisecondes (propre à chaque activité depuis la version 1.9). */
        @JavascriptInterface public void impRegler4(int seuil, int rearme, boolean bip, int ecart) {
            getSharedPreferences("reglages", MODE_PRIVATE).edit().putInt("impSeuil", seuil).putInt("impRearme", rearme).putBoolean("impBip", bip).putInt("impEcart", ecart).apply();
            ServiceSeance s = ServiceSeance.actif;
            if (s != null) s.regler(seuil, rearme, bip, ecart);
        }

        /** Réglage « N coups » : début / fin de l'essai, et mesures fortes relevées pendant l'essai ([[t, m], …]). */
        @JavascriptInterface public boolean essaiDemarrer() { ServiceSeance s = ServiceSeance.actif; if (s == null) return false; s.essai(true); return true; }
        @JavascriptInterface public void essaiArreter() { ServiceSeance s = ServiceSeance.actif; if (s != null) s.essai(false); }
        @JavascriptInterface public String essaiDonnees() { ServiceSeance s = ServiceSeance.actif; return s == null ? "[]" : s.essaiJson(); }

        /** Noms des enregistrements terminés gardés dans le téléphone : ["rec-….json", …]. */
        @JavascriptInterface public String enregistrements() {
            JSONArray l = new JSONArray();
            try {
                String[] noms = new File(dossier, "enregistrements").list();
                if (noms != null) { Arrays.sort(noms); for (String n : noms) if (n.startsWith("rec-") && n.endsWith(".json")) l.put(n); }
            } catch (Exception ignored) {}
            return l.toString();
        }

        /** Fichiers de mesures brutes gardés dans le téléphone : [{"nom":"brut-….bin","taille":…}, …]. */
        @JavascriptInterface public String brut() {
            JSONArray l = new JSONArray();
            try {
                File[] tous = new File(dossier, "enregistrements").listFiles();
                if (tous != null) {
                    Arrays.sort(tous);
                    for (File f : tous) {
                        if (!NOM_BRUT.matcher(f.getName()).matches()) continue;
                        JSONObject o = new JSONObject();
                        o.put("nom", f.getName());
                        o.put("taille", f.length());
                        l.put(o);
                    }
                }
            } catch (Exception ignored) {}
            return l.toString();
        }

        /** Envoie un fichier de mesures brutes par le menu « Partager » d'Android (Google Drive, etc.). */
        @JavascriptInterface public boolean partagerBrut(String nom) {
            try {
                if (nom == null || !(NOM_BRUT.matcher(nom).matches() || NOM_TRACE.matcher(nom).matches())) return false;
                File source = new File(new File(dossier, "enregistrements"), nom);
                if (!source.exists()) return false;
                File dir = new File(getCacheDir(), "partage");
                if (!dir.exists()) dir.mkdirs();
                File[] vieux = dir.listFiles();
                if (vieux != null) for (File v : vieux) v.delete();
                File f = new File(dir, nom);
                MainActivity.copier(source, f);
                final Uri u = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fichiers", f);
                final Intent i = new Intent(Intent.ACTION_SEND);
                i.setType(nom.endsWith(".txt") ? "text/plain" : "application/octet-stream");
                i.putExtra(Intent.EXTRA_STREAM, u);
                i.putExtra(Intent.EXTRA_SUBJECT, nom);
                i.setClipData(ClipData.newRawUri(nom, u));
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                runOnUiThread(() -> {
                    try { startActivity(Intent.createChooser(i, nom.endsWith(".txt") ? "Envoyer le tracé GPS" : "Envoyer les mesures brutes")); } catch (Exception ignored) {}
                });
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        /** n bips courts (signal de reprise, record…). */
        @JavascriptInterface public void bip(final int n) {
            runOnUiThread(() -> {
                try {
                    if (son == null) son = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
                    for (int i = 0; i < Math.max(1, Math.min(5, n)); i++) web.postDelayed(() -> { try { if (son != null) son.startTone(ToneGenerator.TONE_PROP_BEEP, 200); } catch (Exception ignored) {} }, i * 350L);
                } catch (Exception ignored) {}
            });
        }

        /** Garde l'écran allumé pendant une séance au toucher (pompes, exercices à répétitions). */
        @JavascriptInterface public void ecranAllume(final boolean oui) {
            runOnUiThread(() -> {
                try {
                    if (oui) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                } catch (Exception ignored) {}
            });
        }

        /** 1.19 : une notification simple (alerte du suivi du matériel). Touchée, elle ouvre l'appli. */
        @JavascriptInterface public boolean notifier(String titre, String texte, int numero) {
            try {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm == null || !nm.areNotificationsEnabled()) return false;
                NotificationChannel c = new NotificationChannel("materiel", "Suivi du matériel", NotificationManager.IMPORTANCE_DEFAULT);
                c.setDescription("Prévient quand un matériel (cordage, balles, baskets…) dépasse son seuil.");
                nm.createNotificationChannel(c);
                Intent ouvrir = new Intent(MainActivity.this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                PendingIntent pi = PendingIntent.getActivity(MainActivity.this, 10, ouvrir, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                Notification n = new Notification.Builder(MainActivity.this, "materiel")
                        .setSmallIcon(R.drawable.ic_seance)
                        .setContentTitle(titre == null ? "" : titre)
                        .setContentText(texte == null ? "" : texte)
                        .setStyle(new Notification.BigTextStyle().bigText(texte == null ? "" : texte))
                        .setContentIntent(pi)
                        .setAutoCancel(true)
                        .build();
                nm.notify(1000 + Math.max(0, Math.min(899, numero)), n);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        /* ===== 💧 Rappels d'eau (essai n° 3) : le carnet donne l'état de la journée, Android rappelle ===== */
        @JavascriptInterface public void eauEtat(String json) {
            String avant = RappelEau.prefs(MainActivity.this).getString("etat", "");
            RappelEau.prefs(MainActivity.this).edit().putString("etat", json == null ? "{}" : json).apply(); RappelEau.programmer(MainActivity.this);
            if (json != null && !sansMaj(json).equals(sansMaj(avant))) RappelEau.rafraichir(MainActivity.this);   // widget et notification permanente
        }
        /** 🎮 Essai des manettes depuis ⚙️ Réglages, sans séance : connexion, boutons vus, appareils Bluetooth vus. */
        @JavascriptInterface public String manettesTest(boolean marche) {
            if (marche) {
                if (!LienH10.droit(MainActivity.this)) { btDemander(); return "{\"droit\":false}"; }
                if (manTest == null) manTest = new LienManettes(MainActivity.this, new LienManettes.Ecoute() {
                    @Override public void bouton(String nom) { runOnUiThread(() -> { if (web != null) web.evaluateJavascript("window.manetteAppui && window.manetteAppui(" + Json.texte(nom) + ")", null); }); }
                    @Override public void etatManettesChange() { }
                });
                manTest.demarrer();
                return manTest.etatJson();
            }
            if (manTest != null) { manTest.arreter(); manTest = null; }
            return "{}";
        }
        @JavascriptInterface public String manettesEtat() { return manTest == null ? "{}" : manTest.etatJson(); }
        @JavascriptInterface public void manettesRegler(String json) { getSharedPreferences("reglages", MODE_PRIVATE).edit().putString("manettes", json).apply(); }
        /** Boissons notées avec la manette pendant la séance : la page les range dans la séance, puis vide la liste. */
        @JavascriptInterface public String manetteEvts() { SharedPreferences p = getSharedPreferences("reglages", MODE_PRIVATE); String t = p.getString("manetteEvts", "[]"); p.edit().putString("manetteEvts", "[]").apply(); return t; }
        @JavascriptInterface public void santeEtat(String json) { RappelEau.prefs(MainActivity.this).edit().putString("sante", json).apply(); }
        @JavascriptInterface public String santeLire() { return RappelEau.prefs(MainActivity.this).getString("sante", "{}"); }
        @JavascriptInterface public boolean santeNoter(String json) { boolean ok = RappelEau.noter(MainActivity.this, json); if (webCarnet != null) runOnUiThread(() -> webCarnet.evaluateJavascript("window.dispatchEvent && window.dispatchEvent(new Event('apk-visible'))", null)); return ok; }
        @JavascriptInterface public String eauEnAttente() { return RappelEau.prefs(MainActivity.this).getString("attente", "[]"); }
        @JavascriptInterface public void eauVider() { RappelEau.prefs(MainActivity.this).edit().putString("attente", "[]").apply(); }
        @JavascriptInterface public String eauReglages() { return RappelEau.reglages(MainActivity.this).toString(); }
        @JavascriptInterface public void eauRegler(String json) { RappelEau.prefs(MainActivity.this).edit().putString("reglages", json == null ? "{}" : json).apply(); RappelEau.programmer(MainActivity.this); RappelEau.rafraichir(MainActivity.this); }
        @JavascriptInterface public String eauTest() {
            // ⏰ Mode alarme : l'écran par-dessus le verrouillage demande l'autorisation « plein écran » (Android 14).
            try {
                android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (RappelEau.reglages(MainActivity.this).optBoolean("alarme", false) && android.os.Build.VERSION.SDK_INT >= 34 && nm != null && !nm.canUseFullScreenIntent()) {
                    startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, android.net.Uri.parse("package:" + getPackageName())));
                    return "Autorise « notifications en plein écran » pour l'appli, puis touche à nouveau Tester.";
                }
            } catch (Exception ignored) {}
            return RappelEau.verifier(MainActivity.this, true);
        }
        @JavascriptInterface public boolean eauNoter(String json) { return RappelEau.noter(MainActivity.this, json); }
        @JavascriptInterface public boolean eauAnnuler(long ts) { return RappelEau.annulerNote(MainActivity.this, ts); }
        @JavascriptInterface public String eauRecup(long debut, long fin) { return RappelEau.recupDemarrer(MainActivity.this, debut, fin); }
        @JavascriptInterface public String eauVerifier() { return RappelEau.verifier(MainActivity.this, false); }
        @JavascriptInterface public void eauPermanente(boolean oui) { ServiceEau.choisir(MainActivity.this, oui); }

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
    }

    // ---------- Retours d'Android ----------

    /** Le fichier choisi en entier s'il commence comme un fichier .fit de montre (« .FIT » aux octets 8 à 11), sinon null. 20 Mo au plus. */
    private byte[] lireFit(Uri u) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(u)) {
            if (in == null) throw new IOException("Impossible d'ouvrir ce fichier.");
            ByteArrayOutputStream tout = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            boolean vu = false;
            while ((n = in.read(b)) > 0) {
                tout.write(b, 0, n);
                if (!vu && tout.size() >= 12) {
                    byte[] t = tout.toByteArray();
                    if (t[8] != '.' || t[9] != 'F' || t[10] != 'I' || t[11] != 'T') return null;
                    vu = true;
                }
                if (tout.size() > 20 * 1024 * 1024) throw new IOException("Fichier .fit trop gros (plus de 20 Mo).");
            }
            byte[] t = tout.toByteArray();
            return t.length >= 12 && t[8] == '.' && t[9] == 'F' && t[10] == 'I' && t[11] == 'T' ? t : null;
        }
    }

    private String nomDuFichier(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null) return n;
            }
        } catch (Exception ignored) {}
        return "";
    }

    @Override
    protected void onActivityResult(int code, int res, Intent data) {
        if (code == CHOIX_CARNET) {
            ValueCallback<Uri[]> cb = choixCarnet; choixCarnet = null;
            if (cb != null) cb.onReceiveValue(res == RESULT_OK && data != null && data.getData() != null ? new Uri[] { data.getData() } : null);
            return;
        }
        if (code == CHOIX_FICHIER) {
            if (res != RESULT_OK || data == null || data.getData() == null) return;      // choix annulé : rien à faire
            final Uri u = data.getData();
            js("window.surLecture && window.surLecture()");
            new Thread(() -> {
                try {
                    byte[] fit = lireFit(u);                                    // sortie de montre (.fit) : la page la lit elle-même
                    if (fit != null) {
                        if (!ecrireFichier(new File(dossier, "import-fit.txt"), android.util.Base64.encodeToString(fit, android.util.Base64.NO_WRAP))) throw new IOException("Le téléphone a refusé d'écrire le fichier.");
                        js("window.surFit && window.surFit(true, " + JSONObject.quote(nomDuFichier(u)) + ")");
                        return;
                    }
                    LecteurHub.preparer(() -> getContentResolver().openInputStream(u), new File(dossier, "import.json"));
                    js("window.surFichier && window.surFichier(true, " + JSONObject.quote(nomDuFichier(u)) + ")");
                } catch (Throwable e) {
                    String m = e instanceof IOException && e.getMessage() != null ? e.getMessage() : "Lecture impossible : fichier trop gros ou illisible.";
                    js("window.surFichier && window.surFichier(false, " + JSONObject.quote(m) + ")");
                }
            }).start();
            return;
        }
        super.onActivityResult(code, res, data);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] resultats) {
        super.onRequestPermissionsResult(code, permissions, resultats);
        if (code == DEMANDE_NOTIF || code == DEMANDE_BLUETOOTH || code == DEMANDE_GPS) js("window.surReprise && window.surReprise()");
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        ServiceSeance s = ServiceSeance.actif;
        if (s != null) s.ecranRevenu();
        js("window.surReprise && window.surReprise()");
    }

    @Override
    protected void onPause() {
        visible = false;
        super.onPause();
    }

    /** Bouton Retour : la page ferme d'abord ce qui est ouvert ; quand il n'y a plus rien, l'appli passe en arrière-plan. */
    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        if ("carnet".equals(i.getStringExtra("page"))) montrerCarnet();
    }

    @Override
    public void onBackPressed() {
        if (web == null) { super.onBackPressed(); return; }
        if (carnetVisible()) { montrerJournal(); return; }
        web.evaluateJavascript("(window.surRetour ? window.surRetour() : false)", v -> { if (!"true".equals(v)) moveTaskToBack(true); });
    }

    @Override
    protected void onDestroy() {
        try { if (manTest != null) manTest.arreter(); } catch (Exception ignored) {}
        if (voix != null) voix.shutdown();
        try { if (son != null) son.release(); } catch (Exception ignored) {}
        super.onDestroy();
    }
}
