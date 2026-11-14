package fr.mesapplis.activite;

import android.app.Activity;
import android.app.NotificationManager;
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
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
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
    private static final int CHOIX_FICHIER = 41;
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

        final WebViewAssetLoader chargeur = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setTextZoom(100);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return chargeur.shouldInterceptRequest(r.getUrl());
            }
        });
        web.addJavascriptInterface(new Pont(), "AndroidApp");

        voix = new TextToSpeech(this, st -> {
            if (st == TextToSpeech.SUCCESS) { voix.setLanguage(Locale.FRANCE); voix.setSpeechRate(1.15f); voixPrete = true; }
        });

        setContentView(web);
        web.loadUrl("https://appassets.androidplatform.net/assets/www/index.html");
    }

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
    public void onBackPressed() {
        if (web == null) { super.onBackPressed(); return; }
        web.evaluateJavascript("(window.surRetour ? window.surRetour() : false)", v -> { if (!"true".equals(v)) moveTaskToBack(true); });
    }

    @Override
    protected void onDestroy() {
        if (voix != null) voix.shutdown();
        try { if (son != null) son.release(); } catch (Exception ignored) {}
        super.onDestroy();
    }
}
