package fr.mesapplis.dwg;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;
import androidx.webkit.WebViewAssetLoader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Appli « Plans DWG » : affiche la page du Lecteur DWG chantier rangée dans l'APK (assets/www),
 * ou sa dernière mise à jour installée depuis l'appli (dossier interne « maj »).
 * Aucun accès à internet. Les plans et réglages restent dans le téléphone.
 */
public class MainActivity extends Activity {
    private static final int CHOIX_FICHIER = 41;
    private static final int CHOIX_DOSSIER = 42;
    private static final int CREER_FICHIER = 43;
    private static final String HOTE = "https://appassets.androidplatform.net";
    private static final String MARQUE = "content=\"lecteur-dwg-chantier\"";

    private WebView web;
    private ValueCallback<Uri[]> rappelFichier;
    private File dossierMaj, dossierPrec;
    private volatile String fichierNom = null;
    private volatile String fichierB64 = null;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dossierMaj = new File(getFilesDir(), "maj");
        dossierPrec = new File(getFilesDir(), "precedente");
        verifierVersionApk();

        final WebViewAssetLoader chargeur = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .addPathHandler("/maj/", new WebViewAssetLoader.InternalStoragePathHandler(this, dossierMaj))
                .build();

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
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

        lireIntention(getIntent());
        setContentView(web);
        web.loadUrl(urlDepart());
    }

    private String urlDepart() {
        return new File(dossierMaj, "index.html").exists()
                ? HOTE + "/maj/index.html"
                : HOTE + "/assets/www/index.html";
    }

    /** Une nouvelle APK installée remplace les mises à jour faites depuis l'appli (elle contient la dernière page). */
    private void verifierVersionApk() {
        SharedPreferences p = getSharedPreferences("reglages", MODE_PRIVATE);
        int actuelle = versionCode();
        if (p.getInt("versionApk", -1) != actuelle) {
            supprimer(dossierMaj);
            supprimer(dossierPrec);
            p.edit().putInt("versionApk", actuelle).apply();
        }
    }

    private int versionCode() {
        try {
            android.content.pm.PackageInfo i = getPackageManager().getPackageInfo(getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) i.getLongVersionCode() : i.versionCode;
        } catch (Exception e) { return 0; }
    }

    private static void supprimer(File f) {
        if (f == null || !f.exists()) return;
        File[] l = f.listFiles();
        if (l != null) for (File c : l) supprimer(c);
        f.delete();
    }

    /** Plan reçu par « Ouvrir avec… » ou « Partager » */
    private void lireIntention(Intent it) {
        if (it == null) return;
        Uri u = null;
        if (Intent.ACTION_VIEW.equals(it.getAction())) u = it.getData();
        else if (Intent.ACTION_SEND.equals(it.getAction())) u = it.getParcelableExtra(Intent.EXTRA_STREAM);
        if (u == null) return;
        String nom = "plan.dwg";
        try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0 && c.getString(i) != null) nom = c.getString(i);
            }
        } catch (Exception ignored) {
            String d = u.getLastPathSegment();
            if (d != null) nom = d;
        }
        try (InputStream in = getContentResolver().openInputStream(u)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] t = new byte[65536]; int n;
            while ((n = in.read(t)) > 0) o.write(t, 0, n);
            fichierB64 = Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP);
            fichierNom = nom;
        } catch (Exception e) {
            Toast.makeText(this, "Impossible de lire ce fichier : " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        it.setAction(Intent.ACTION_MAIN); // ne pas le rouvrir en revenant dans l'appli
    }

    @Override
    protected void onNewIntent(Intent it) {
        super.onNewIntent(it);
        lireIntention(it);
        if (fichierNom != null && web != null)
            web.evaluateJavascript("window.ouvrirFichierAppli && window.ouvrirFichierAppli()", null);
    }

    /** Fonctions appelées depuis la page */
    class Pont {
        @JavascriptInterface public boolean estAppli() { return true; }

        @JavascriptInterface public String versionApk() {
            try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
            catch (Exception e) { return "?"; }
        }

        @JavascriptInterface public boolean surMiseAJour() { return new File(dossierMaj, "index.html").exists(); }

        @JavascriptInterface public boolean aVersionPrecedente() {
            return new File(dossierPrec, "index.html").exists() || new File(dossierPrec, "apk").exists();
        }

        /** Installe la page reçue ; l'ancienne est gardée pour pouvoir y revenir. */
        @JavascriptInterface public String installerMiseAJour(String html) {
            if (html == null || !html.contains(MARQUE)) return "Ce fichier n'est pas une mise à jour du Lecteur DWG chantier.";
            try {
                File tmp = new File(getFilesDir(), "maj-nouvelle.html");
                try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(html.getBytes(StandardCharsets.UTF_8)); }
                supprimer(dossierPrec);
                dossierPrec.mkdirs();
                File actuelle = new File(dossierMaj, "index.html");
                if (actuelle.exists()) { if (!actuelle.renameTo(new File(dossierPrec, "index.html"))) return "Échec de la copie de sauvegarde."; }
                else new File(dossierPrec, "apk").createNewFile(); // la précédente = celle d'origine de l'APK
                dossierMaj.mkdirs();
                if (!tmp.renameTo(new File(dossierMaj, "index.html"))) return "Échec de l'installation.";
                return "ok";
            } catch (Exception e) { return "Erreur : " + e.getMessage(); }
        }

        /** Revient à la version d'avant la dernière mise à jour (et inversement). */
        @JavascriptInterface public String versionPrecedente() {
            try {
                File prec = new File(dossierPrec, "index.html"), actu = new File(dossierMaj, "index.html");
                File echange = new File(getFilesDir(), "echange");
                supprimer(echange); echange.mkdirs();
                if (actu.exists()) actu.renameTo(new File(echange, "index.html")); else new File(echange, "apk").createNewFile();
                supprimer(dossierMaj);
                if (prec.exists()) { dossierMaj.mkdirs(); prec.renameTo(new File(dossierMaj, "index.html")); }
                else if (!new File(dossierPrec, "apk").exists()) { return "Aucune version précédente."; }
                supprimer(dossierPrec);
                echange.renameTo(dossierPrec);
                return "ok";
            } catch (Exception e) { return "Erreur : " + e.getMessage(); }
        }

        @JavascriptInterface public void recharger() { runOnUiThread(() -> web.loadUrl(urlDepart())); }

        @JavascriptInterface public String nomFichierEnAttente() { return fichierNom == null ? "" : fichierNom; }

        @JavascriptInterface public String prendreFichierEnAttente() {
            String r = fichierB64; fichierB64 = null; fichierNom = null; return r == null ? "" : r;
        }

        /** Ouvre le sélecteur de dossier d'Android (clé USB, téléphone, tablette…). Réponse : window.dossierChoisi(nom). */
        @JavascriptInterface public void choisirDossier() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                try { startActivityForResult(i, CHOIX_DOSSIER); }
                catch (Exception e) { repondreDossier(""); }
            });
        }

        /** « Enregistrer sous » : Android demande le nom et l'emplacement. Réponse : window.fichierCree(uri, nom) ("" si annulé). */
        @JavascriptInterface public void creerFichier(String nomPropose) {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/octet-stream");
                i.putExtra(Intent.EXTRA_TITLE, nomPropose);
                try { startActivityForResult(i, CREER_FICHIER); }
                catch (Exception e) { repondreFichier("", ""); }
            });
        }

        /** Écrit (remplace) le contenu d'un fichier déjà choisi. "ok", "absent" (fichier introuvable / clé débranchée) ou un message. */
        @JavascriptInterface public String ecrireUri(String uri, String contenu) {
            try {
                Uri u = Uri.parse(uri);
                OutputStream o;
                try { o = getContentResolver().openOutputStream(u, "wt"); }
                catch (Exception e) { o = getContentResolver().openOutputStream(u, "w"); }
                if (o == null) return "absent";
                try (OutputStream os = o) { os.write(contenu.getBytes(StandardCharsets.UTF_8)); }
                return "ok";
            } catch (SecurityException e) {
                return "absent";
            } catch (java.io.FileNotFoundException e) {
                return "absent";
            } catch (Exception e) {
                return "erreur : " + e.getMessage();
            }
        }

        /** Nom affiché d'un fichier choisi. */
        @JavascriptInterface public String nomUri(String uri) { return nomAffiche(Uri.parse(uri)); }

        /** Nom lisible du dossier d'export retenu, ou "" s'il n'y en a pas. */
        @JavascriptInterface public String nomDossier() { return nomLisibleDossier(); }

        /** Écrit (ou remplace) un fichier texte dans le dossier retenu. "ok", "absent" (pas de dossier / clé débranchée) ou un message d'erreur. */
        @JavascriptInterface public String ecrireDansDossier(String nom, String contenu) {
            String u = getSharedPreferences("reglages", MODE_PRIVATE).getString("dossier", null);
            if (u == null) return "absent";
            try {
                DocumentFile d = DocumentFile.fromTreeUri(MainActivity.this, Uri.parse(u));
                if (d == null || !d.exists() || !d.canWrite()) return "absent";
                DocumentFile f = d.findFile(nom);
                if (f == null) f = d.createFile("application/octet-stream", nom);
                if (f == null) return "création du fichier impossible";
                OutputStream o;
                try { o = getContentResolver().openOutputStream(f.getUri(), "wt"); }
                catch (Exception e) { o = getContentResolver().openOutputStream(f.getUri(), "w"); }
                if (o == null) return "écriture impossible";
                try (OutputStream os = o) { os.write(contenu.getBytes(StandardCharsets.UTF_8)); }
                return "ok";
            } catch (SecurityException e) {
                return "absent";
            } catch (Exception e) {
                String m = e.getMessage();
                return (m != null && (m.contains("No such") || m.contains("not found") || m.contains("ENOENT"))) ? "absent" : "erreur : " + m;
            }
        }

        /** Enregistre un fichier texte dans « Téléchargements » (servira pour l'export TS11). */
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

    private String nomLisibleDossier() {
        String u = getSharedPreferences("reglages", MODE_PRIVATE).getString("dossier", null);
        if (u == null) return "";
        try {
            String id = DocumentsContract.getTreeDocumentId(Uri.parse(u)); // ex. "primary:Download/TS11" ou "1A2B-3C4D:DATA"
            int p = id.indexOf(':');
            String vol = p > 0 ? id.substring(0, p) : id, chemin = p > 0 ? id.substring(p + 1) : "";
            String app = "primary".equals(vol) ? "Mémoire interne" : "Clé USB / carte";
            return app + (chemin.isEmpty() ? "" : " › " + chemin.replace("/", " › "));
        } catch (Exception e) { return "dossier choisi"; }
    }

    private String nomAffiche(Uri u) {
        try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) { int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0 && c.getString(i) != null) return c.getString(i); }
        } catch (Exception ignored) {}
        String d = u.getLastPathSegment(); return d == null ? "fichier" : d;
    }

    private void repondreFichier(String uri, String nom) {
        String js = "window.fichierCree && window.fichierCree(" + org.json.JSONObject.quote(uri) + "," + org.json.JSONObject.quote(nom) + ")";
        if (web != null) web.evaluateJavascript(js, null);
    }

    private void repondreDossier(String nom) {
        String js = "window.dossierChoisi && window.dossierChoisi(" + org.json.JSONObject.quote(nom) + ")";
        if (web != null) web.evaluateJavascript(js, null);
    }

    @Override
    protected void onActivityResult(int code, int res, Intent data) {
        if (code == CREER_FICHIER) {
            if (res == RESULT_OK && data != null && data.getData() != null) {
                Uri u = data.getData();
                try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION); }
                catch (Exception ignored) {}
                repondreFichier(u.toString(), nomAffiche(u));
            } else repondreFichier("", "");
            return;
        }
        if (code == CHOIX_DOSSIER) {
            if (res == RESULT_OK && data != null && data.getData() != null) {
                Uri u = data.getData();
                try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION); }
                catch (Exception ignored) {}
                getSharedPreferences("reglages", MODE_PRIVATE).edit().putString("dossier", u.toString()).apply();
                repondreDossier(nomLisibleDossier());
            } else repondreDossier("");
            return;
        }
        if (code == CHOIX_FICHIER && rappelFichier != null) {
            Uri[] r = null;
            if (res == RESULT_OK && data != null && data.getData() != null) r = new Uri[]{ data.getData() };
            rappelFichier.onReceiveValue(r);
            rappelFichier = null;
            return;
        }
        super.onActivityResult(code, res, data);
    }

    /** Retour : l'appli passe en arrière-plan sans se fermer (on retrouve le plan tel quel). */
    @Override public void onBackPressed() { moveTaskToBack(true); }
}
