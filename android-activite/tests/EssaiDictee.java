package fr.mesapplis.activite;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** Comprend des phrases dictées hors du téléphone. java … EssaiDictee <favoris.txt> <phrases.txt>.
    favoris.txt : une ligne par favori « id|nom|unité|quantité par défaut|mots-codes|eau(0/1) ». Utilisé par activite/tests/test-dictee.js. */
public class EssaiDictee {
    public static void main(String[] a) throws Exception {
        System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8"));
        List<Dictee.Fav> favs = new ArrayList<>();
        for (String l : Files.readAllLines(Paths.get(a[0]), StandardCharsets.UTF_8)) { String[] x = l.split("\\|", -1); if (x.length < 6) continue; favs.add(new Dictee.Fav(x[0], x[1], x[2], Double.parseDouble(x[3]), x[4], x[5].equals("1"))); }
        for (String p : Files.readAllLines(Paths.get(a[1]), StandardCharsets.UTF_8)) {
            if (p.trim().isEmpty()) continue;
            Dictee.Resultat r = Dictee.analyser(p, favs);
            StringBuilder sb = new StringBuilder();
            for (Dictee.Item it : r.items) { if (sb.length() > 0) sb.append(" ; "); sb.append(it.id).append('=').append(Dictee.nb(it.q)).append(it.defaut ? "*" : ""); }
            for (Dictee.Sante x : r.sante) { if (sb.length() > 0) sb.append(" ; "); sb.append(x.type).append('=').append(x.type.equals("tension") ? Dictee.nb(x.sys) + "/" + Dictee.nb(x.dia) + (x.pouls > 0 ? "p" + Dictee.nb(x.pouls) : "") : x.type.equals("poids") ? Dictee.nb(x.kg) : x.zone + ":" + Dictee.nb(x.niveau) + (x.fini ? ":fini" : "") + (x.note.isEmpty() ? "" : ":" + x.note)); }
            sb.append(" | ?").append(String.join(" / ", r.incompris));
            if (r.heure != null) sb.append(" @").append(r.heure);
            System.out.println(sb);
        }
    }
}
