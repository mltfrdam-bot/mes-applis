package fr.mesapplis.activite;

import android.app.Activity;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
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
    private static final int NB_SAUVEGARDES = 7;
    private static final Pattern NOM_OK = Pattern.compile("^(sauvegardes/|enregistrements/)?[A-Za-z0-9._-]{1,80}$");
    private static final TimeZone REUNION = TimeZone.getTimeZone("Indian/Reunion");

    /** Vrai quand l'écran de l'appli est affiché (le service s'en sert pour s'arrêter quand il ne sert plus). */
    static volatile boolean visible = false;

    private WebView web;
    private File dossier;
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
            if (ServiceSeance.actif == null && ServiceSeance.aReprendre(this) && LienH10.droit(this))
                startForegroundService(new Intent(this, ServiceSeance.class).setAction(ServiceSeance.ACTION_REPRENDRE));
        } catch (Exception ignored) {}
    }

    private boolean lancerService(String action, String act) {
        try {
            if (!LienH10.droit(this)) return false;
            Intent i = new Intent(this, ServiceSeance.class).setAction(action);
            if (act != null) i.putExtra(ServiceSeance.EXTRA_ACT, act);
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

        /** Termine l'enregistrement : renvoie le nom du fichier « enregistrements/rec-….json », ou "" en cas d'échec. */
        @JavascriptInterface public String seanceArreter() { ServiceSeance s = ServiceSeance.actif; return s == null ? "" : s.terminer(); }

        @JavascriptInterface public String seanceEtat() {
            ServiceSeance s = ServiceSeance.actif;
            try { return s != null ? s.etatJson() : ServiceSeance.etatHorsService(MainActivity.this); } catch (Exception e) { return "{\"service\":false,\"enr\":null}"; }
        }

        @JavascriptInterface public void seanceActivite(String act) { ServiceSeance s = ServiceSeance.actif; if (s != null && act != null) s.activite(act); }

        /** Réglage des impacts (seuil et réarmement en millièmes de g, bip oui/non) : gardé aussi côté Android pour une reprise sans écran. */
        @JavascriptInterface public void impRegler(int seuil, int rearme, boolean bip) {
            getSharedPreferences("reglages", MODE_PRIVATE).edit().putInt("impSeuil", seuil).putInt("impRearme", rearme).putBoolean("impBip", bip).apply();
            ServiceSeance s = ServiceSeance.actif;
            if (s != null) s.regler(seuil, rearme, bip);
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
        if (code == DEMANDE_NOTIF || code == DEMANDE_BLUETOOTH) js("window.surReprise && window.surReprise()");
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
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
        super.onDestroy();
    }
}
