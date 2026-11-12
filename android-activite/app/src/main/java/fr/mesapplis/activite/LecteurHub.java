package fr.mesapplis.activite;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lit le fichier choisi pour l'import et prépare « import.json » pour la page.
 *
 * Une sauvegarde du Hub pèse des dizaines de Mo (elle contient le code de toutes les applis).
 * On la lit donc AU FIL DE L'EAU : ce qui ne sert pas est sauté sans jamais être gardé en mémoire,
 * et on ne garde que le Journal d'activité (reconnu à sa clé « footing-courses », car il n'a pas d'identifiant fixe).
 *
 * Résultat écrit dans le fichier de sortie :
 *  - sauvegarde du Hub     → {"type":"hub","hub":…,"creeeLe":…,"journaux":[{"id","nom","version","localStorage":{…}}]}
 *  - sauvegarde de l'appli → le fichier tel quel (format « journal-activite »)
 *
 * Cette classe n'utilise que du Java simple (rien d'Android) : elle est testée seule à chaque fabrication
 * (android-activite/tests/TestLecteurHub.java).
 */
final class LecteurHub {
    static final String FORMAT_HUB = "mon-hub-sauvegarde";
    static final String FORMAT_APPLI = "journal-activite";
    static final String CLE_JOURNAL = "footing-courses";
    static final String REFUS = "Ce fichier n'est ni une sauvegarde du Hub, ni une sauvegarde du Journal d'activité.";
    static final String ABIME = "Ce fichier est abîmé ou n'est pas une sauvegarde.";

    /** Ouvre le fichier choisi : on peut avoir besoin de le lire deux fois. */
    interface Source { InputStream ouvrir() throws IOException; }

    private final Reader entree;
    private final char[] bloc = new char[65536];
    private int pos = 0, fin = 0;

    private LecteurHub(Reader entree) { this.entree = entree; }

    static void preparer(Source source, File sortie) throws IOException {
        String format = null, hub = "", creeeLe = "";
        Map<String, String[]> applis = new HashMap<>();                     // id → {nom, version}
        List<String> ids = new ArrayList<>();
        List<Map<String, String>> stockages = new ArrayList<>();

        InputStream in = source.ouvrir();
        if (in == null) throw new IOException("Impossible d'ouvrir ce fichier.");
        try (Reader lecteur = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            LecteurHub l = new LecteurHub(lecteur);
            if (l.regarder() == 0xFEFF) l.pos++;                             // marque de début de fichier éventuelle
            if (l.regarder() != '{') throw new IOException(REFUS);
            l.pos++;
            boolean premier = true;
            while (true) {
                int c = l.regarder();
                if (c == '}') break;
                if (!premier) { if (c != ',') throw new IOException(ABIME); l.pos++; }
                premier = false;
                String nom = l.cle();
                if (nom.equals("format")) {
                    format = l.simple();
                    if (!FORMAT_HUB.equals(format)) break;                   // autre format : inutile de lire la suite ici
                } else if (nom.equals("hub")) {
                    hub = l.simple();
                } else if (nom.equals("creeeLe")) {
                    creeeLe = l.simple();
                } else if (nom.equals("applis")) {
                    l.lireApplis(applis);
                } else if (nom.equals("donnees")) {
                    l.lireDonnees(ids, stockages);
                } else {
                    l.sauter();
                }
            }
        }

        if (FORMAT_APPLI.equals(format)) { copier(source, sortie); return; }
        if (!FORMAT_HUB.equals(format)) throw new IOException(REFUS);
        if (stockages.isEmpty()) throw new IOException("Je ne trouve pas le Journal d'activité dans cette sauvegarde du Hub.");

        File tmp = new File(sortie.getPath() + ".tmp");
        try (Writer w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8))) {
            w.write("{\"type\":\"hub\",\"hub\":"); guillemets(w, hub);
            w.write(",\"creeeLe\":"); guillemets(w, creeeLe);
            w.write(",\"journaux\":[");
            for (int i = 0; i < stockages.size(); i++) {
                String id = ids.get(i);
                String[] a = applis.get(id);
                if (i > 0) w.write(',');
                w.write("{\"id\":"); guillemets(w, id);
                w.write(",\"nom\":"); guillemets(w, a != null && !a[0].isEmpty() ? a[0] : id);
                w.write(",\"version\":"); guillemets(w, a != null ? a[1] : "");
                w.write(",\"localStorage\":{");
                boolean p = true;
                for (Map.Entry<String, String> e : stockages.get(i).entrySet()) {
                    if (!p) w.write(',');
                    p = false;
                    guillemets(w, e.getKey()); w.write(':'); guillemets(w, e.getValue());
                }
                w.write("}}");
            }
            w.write("]}");
        }
        remplacer(tmp, sortie);
    }

    // ---------- Lecture au fil de l'eau ----------

    /** Prochain caractère utile (les blancs sont passés), sans l'avaler. -1 = fin du fichier. */
    private int regarder() throws IOException {
        while (true) {
            if (pos >= fin && !remplir()) return -1;
            char c = bloc[pos];
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') pos++; else return c;
        }
    }

    private boolean remplir() throws IOException {
        int n = entree.read(bloc, 0, bloc.length);
        if (n <= 0) { pos = 0; fin = 0; return false; }
        pos = 0; fin = n;
        return true;
    }

    private int lire() throws IOException {
        if (pos >= fin && !remplir()) throw new IOException(ABIME);
        return bloc[pos++];
    }

    private void attendre(char voulu) throws IOException {
        if (regarder() != voulu) throw new IOException(ABIME);
        pos++;
    }

    /** Nom d'une clé, suivi de ses deux-points. */
    private String cle() throws IOException {
        if (regarder() != '"') throw new IOException(ABIME);
        String nom = texte();
        attendre(':');
        return nom;
    }

    /** Lit un texte entre guillemets (le curseur est sur le guillemet ouvrant). */
    private String texte() throws IOException {
        pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= fin && !remplir()) throw new IOException(ABIME);
            int debut = pos;
            while (pos < fin && bloc[pos] != '"' && bloc[pos] != '\\') pos++;
            sb.append(bloc, debut, pos - debut);
            if (pos >= fin) continue;
            char c = bloc[pos++];
            if (c == '"') return sb.toString();
            int e = lire();
            switch (e) {
                case 'n': sb.append('\n'); break;
                case 't': sb.append('\t'); break;
                case 'r': sb.append('\r'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'u': {
                    int v = 0;
                    for (int i = 0; i < 4; i++) {
                        int h = Character.digit(lire(), 16);
                        if (h < 0) throw new IOException(ABIME);
                        v = v * 16 + h;
                    }
                    sb.append((char) v);
                    break;
                }
                default: sb.append((char) e);                                // \" \\ \/
            }
        }
    }

    /** Passe un texte entre guillemets SANS le garder (c'est ce qui évite de charger le code des applis). */
    private void sauterTexte() throws IOException {
        pos++;
        while (true) {
            if (pos >= fin && !remplir()) throw new IOException(ABIME);
            while (pos < fin && bloc[pos] != '"' && bloc[pos] != '\\') pos++;
            if (pos >= fin) continue;
            char c = bloc[pos++];
            if (c == '"') return;
            lire();                                                          // le caractère qui suit la barre oblique
        }
    }

    /** Passe une valeur entière, quelle qu'elle soit. */
    private void sauter() throws IOException {
        int c = regarder();
        if (c == '"') { sauterTexte(); return; }
        if (c == '{' || c == '[') {
            int niveau = 0;
            while (true) {
                c = regarder();
                if (c < 0) throw new IOException(ABIME);
                if (c == '"') { sauterTexte(); continue; }
                pos++;
                if (c == '{' || c == '[') niveau++;
                else if (c == '}' || c == ']') { niveau--; if (niveau == 0) return; }
            }
        }
        if (c < 0) throw new IOException(ABIME);
        nu();
    }

    /** Lit un nombre, vrai, faux ou vide, tel qu'il est écrit. */
    private String nu() throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= fin && !remplir()) break;
            char c = bloc[pos];
            if (c == ',' || c == '}' || c == ']' || c == ' ' || c == '\n' || c == '\r' || c == '\t') break;
            if (c == '"' || c == '{' || c == '[' || c == ':' || sb.length() > 40) throw new IOException(ABIME);
            sb.append(c); pos++;
        }
        if (sb.length() == 0) throw new IOException(ABIME);
        return sb.toString();
    }

    /** Valeur simple lue comme un texte (texte, nombre, vrai/faux ; vide → "") ; le reste est sauté. */
    private String simple() throws IOException {
        int c = regarder();
        if (c == '"') return texte();
        if (c == '{' || c == '[') { sauter(); return ""; }
        String v = nu();
        return v.equals("null") ? "" : v;
    }

    /** Liste des applis du Hub : on ne garde que id, nom, version (le code, très gros, est sauté). */
    private void lireApplis(Map<String, String[]> applis) throws IOException {
        if (regarder() != '[') { sauter(); return; }
        pos++;
        boolean premier = true;
        while (true) {
            int c = regarder();
            if (c == ']') { pos++; return; }
            if (!premier) { if (c != ',') throw new IOException(ABIME); pos++; c = regarder(); }
            premier = false;
            if (c != '{') { sauter(); continue; }
            pos++;
            String id = null, nom = "", version = "";
            boolean p = true;
            while (true) {
                c = regarder();
                if (c == '}') { pos++; break; }
                if (!p) { if (c != ',') throw new IOException(ABIME); pos++; }
                p = false;
                String n = cle();
                if (n.equals("id")) id = simple();
                else if (n.equals("nom")) nom = simple();
                else if (n.equals("version")) version = simple();
                else sauter();
            }
            if (id != null) applis.put(id, new String[]{nom, version});
        }
    }

    /** Données de chaque appli : on garde le « localStorage » de celles qui contiennent le Journal. */
    private void lireDonnees(List<String> ids, List<Map<String, String>> stockages) throws IOException {
        if (regarder() != '{') { sauter(); return; }
        pos++;
        boolean premier = true;
        while (true) {
            int c = regarder();
            if (c == '}') { pos++; return; }
            if (!premier) { if (c != ',') throw new IOException(ABIME); pos++; }
            premier = false;
            String id = cle();
            if (regarder() != '{') { sauter(); continue; }
            pos++;
            Map<String, String> ls = null;
            boolean p = true;
            while (true) {
                c = regarder();
                if (c == '}') { pos++; break; }
                if (!p) { if (c != ',') throw new IOException(ABIME); pos++; }
                p = false;
                String n = cle();
                if (n.equals("localStorage") && regarder() == '{') ls = lireStockage(); else sauter();
            }
            if (ls != null && ls.containsKey(CLE_JOURNAL)) { ids.add(id); stockages.add(ls); }
        }
    }

    private Map<String, String> lireStockage() throws IOException {
        Map<String, String> ls = new LinkedHashMap<>();
        pos++;
        boolean premier = true;
        while (true) {
            int c = regarder();
            if (c == '}') { pos++; return ls; }
            if (!premier) { if (c != ',') throw new IOException(ABIME); pos++; }
            premier = false;
            String k = cle();
            if (regarder() == '"') ls.put(k, texte()); else sauter();
        }
    }

    // ---------- Écriture ----------

    private static void guillemets(Writer w, String s) throws IOException {
        w.write('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': w.write("\\\""); break;
                case '\\': w.write("\\\\"); break;
                case '\n': w.write("\\n"); break;
                case '\r': w.write("\\r"); break;
                case '\t': w.write("\\t"); break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) w.write(String.format("\\u%04x", (int) c));
                    else w.write(c);
            }
        }
        w.write('"');
    }

    private static void copier(Source source, File sortie) throws IOException {
        File tmp = new File(sortie.getPath() + ".tmp");
        InputStream in = source.ouvrir();
        if (in == null) throw new IOException("Impossible d'ouvrir ce fichier.");
        try (InputStream i = in; OutputStream o = new FileOutputStream(tmp)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = i.read(b)) > 0) o.write(b, 0, n);
        }
        remplacer(tmp, sortie);
    }

    private static void remplacer(File tmp, File sortie) throws IOException {
        if (sortie.exists() && !sortie.delete()) throw new IOException("Écriture impossible dans le téléphone.");
        if (!tmp.renameTo(sortie)) throw new IOException("Écriture impossible dans le téléphone.");
    }
}
