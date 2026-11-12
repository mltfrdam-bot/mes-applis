package fr.mesapplis.activite;

import android.util.JsonReader;
import android.util.JsonToken;
import android.util.MalformedJsonException;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lit le fichier choisi pour l'import et prépare « import.json » pour la page.
 *
 * Une sauvegarde du Hub pèse des dizaines de Mo (elle contient le code de toutes les applis).
 * On la lit donc AU FIL DE L'EAU, sans la charger en mémoire, et on n'en garde que le Journal d'activité.
 * Le Journal n'a pas d'identifiant fixe dans le Hub : on le reconnaît à sa clé « footing-courses ».
 *
 * Résultat écrit dans le fichier de sortie :
 *  - sauvegarde du Hub     → {"type":"hub","hub":…,"creeeLe":…,"journaux":[{"id","nom","version","localStorage":{…}}]}
 *  - sauvegarde de l'appli → le fichier tel quel (format « journal-activite »)
 */
final class LecteurHub {
    static final String FORMAT_HUB = "mon-hub-sauvegarde";
    static final String FORMAT_APPLI = "journal-activite";
    static final String CLE_JOURNAL = "footing-courses";
    static final String REFUS = "Ce fichier n'est ni une sauvegarde du Hub, ni une sauvegarde du Journal d'activité.";

    /** Ouvre le fichier choisi : on peut avoir besoin de le lire deux fois. */
    interface Source { InputStream ouvrir() throws IOException; }

    private LecteurHub() {}

    static void preparer(Source source, File sortie) throws IOException {
        String format = null, hub = "", creeeLe = "";
        Map<String, String[]> applis = new HashMap<>();      // id → {nom, version}
        List<String> ids = new ArrayList<>();
        List<JSONObject> stockages = new ArrayList<>();

        InputStream in = source.ouvrir();
        if (in == null) throw new IOException("Impossible d'ouvrir ce fichier.");
        try (JsonReader r = new JsonReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            if (r.peek() != JsonToken.BEGIN_OBJECT) throw new IOException(REFUS);
            r.beginObject();
            while (r.hasNext()) {
                String nom = r.nextName();
                if (nom.equals("format")) {
                    format = chaine(r);
                    if (!FORMAT_HUB.equals(format)) break;           // autre format : inutile de lire la suite ici
                } else if (nom.equals("hub")) {
                    hub = chaine(r);
                } else if (nom.equals("creeeLe")) {
                    creeeLe = chaine(r);
                } else if (nom.equals("applis")) {
                    lireApplis(r, applis);
                } else if (nom.equals("donnees")) {
                    lireDonnees(r, ids, stockages);
                } else {
                    r.skipValue();
                }
            }
        } catch (MalformedJsonException | IllegalStateException | NumberFormatException e) {
            throw new IOException("Ce fichier est abîmé ou n'est pas une sauvegarde.");
        }

        if (FORMAT_APPLI.equals(format)) { copier(source, sortie); return; }
        if (!FORMAT_HUB.equals(format)) throw new IOException(REFUS);
        if (stockages.isEmpty()) throw new IOException("Je ne trouve pas le Journal d'activité dans cette sauvegarde du Hub.");

        try {
            JSONArray journaux = new JSONArray();
            for (int i = 0; i < stockages.size(); i++) {
                String id = ids.get(i);
                String[] a = applis.get(id);
                JSONObject j = new JSONObject();
                j.put("id", id);
                j.put("nom", a != null && a[0] != null && !a[0].isEmpty() ? a[0] : id);
                j.put("version", a != null && a[1] != null ? a[1] : "");
                j.put("localStorage", stockages.get(i));
                journaux.put(j);
            }
            JSONObject res = new JSONObject();
            res.put("type", "hub");
            res.put("hub", hub);
            res.put("creeeLe", creeeLe);
            res.put("journaux", journaux);
            ecrire(sortie, res.toString().getBytes(StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException("Lecture du Journal impossible dans cette sauvegarde.");
        }
    }

    /** Lit une valeur simple comme un texte (texte, nombre, vrai/faux) ; saute le reste. */
    private static String chaine(JsonReader r) throws IOException {
        JsonToken t = r.peek();
        if (t == JsonToken.STRING || t == JsonToken.NUMBER) return r.nextString();
        if (t == JsonToken.BOOLEAN) return String.valueOf(r.nextBoolean());
        r.skipValue();
        return "";
    }

    /** Liste des applis du Hub : on ne garde que id, nom, version (le code, très gros, est sauté). */
    private static void lireApplis(JsonReader r, Map<String, String[]> applis) throws IOException {
        if (r.peek() != JsonToken.BEGIN_ARRAY) { r.skipValue(); return; }
        r.beginArray();
        while (r.hasNext()) {
            if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue; }
            String id = null, nom = "", version = "";
            r.beginObject();
            while (r.hasNext()) {
                String n = r.nextName();
                if (n.equals("id")) id = chaine(r);
                else if (n.equals("nom")) nom = chaine(r);
                else if (n.equals("version")) version = chaine(r);
                else r.skipValue();
            }
            r.endObject();
            if (id != null) applis.put(id, new String[]{nom, version});
        }
        r.endArray();
    }

    /** Données de chaque appli : on garde le « localStorage » de celles qui contiennent le Journal. */
    private static void lireDonnees(JsonReader r, List<String> ids, List<JSONObject> stockages) throws IOException {
        if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); return; }
        r.beginObject();
        while (r.hasNext()) {
            String id = r.nextName();
            if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue; }
            JSONObject ls = null;
            r.beginObject();
            while (r.hasNext()) {
                String n = r.nextName();
                if (n.equals("localStorage") && r.peek() == JsonToken.BEGIN_OBJECT) {
                    ls = new JSONObject();
                    r.beginObject();
                    while (r.hasNext()) {
                        String cle = r.nextName();
                        if (r.peek() == JsonToken.STRING) {
                            String valeur = r.nextString();
                            try { ls.put(cle, valeur); } catch (JSONException e) { throw new IOException("Clé illisible : " + cle); }
                        } else {
                            r.skipValue();
                        }
                    }
                    r.endObject();
                } else {
                    r.skipValue();
                }
            }
            r.endObject();
            if (ls != null && ls.has(CLE_JOURNAL)) { ids.add(id); stockages.add(ls); }
        }
        r.endObject();
    }

    private static void copier(Source source, File sortie) throws IOException {
        File tmp = new File(sortie.getPath() + ".tmp");
        InputStream in = source.ouvrir();
        if (in == null) throw new IOException("Impossible d'ouvrir ce fichier.");
        try (InputStream i = in; OutputStream o = new FileOutputStream(tmp)) {
            byte[] bloc = new byte[65536];
            int n;
            while ((n = i.read(bloc)) > 0) o.write(bloc, 0, n);
        }
        if (sortie.exists() && !sortie.delete()) throw new IOException("Écriture impossible.");
        if (!tmp.renameTo(sortie)) throw new IOException("Écriture impossible.");
    }

    private static void ecrire(File sortie, byte[] octets) throws IOException {
        File tmp = new File(sortie.getPath() + ".tmp");
        try (OutputStream o = new FileOutputStream(tmp)) { o.write(octets); }
        if (sortie.exists() && !sortie.delete()) throw new IOException("Écriture impossible.");
        if (!tmp.renameTo(sortie)) throw new IOException("Écriture impossible.");
    }
}
