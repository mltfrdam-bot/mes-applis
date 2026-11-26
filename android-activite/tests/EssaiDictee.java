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
        List<Dictee.Fav> favs = new ArrayList<>();
        for (String l : Files.readAllLines(Paths.get(a[0]), StandardCharsets.UTF_8)) { String[] x = l.split("\\|", -1); if (x.length < 6) continue; favs.add(new Dictee.Fav(x[0], x[1], x[2], Double.parseDouble(x[3]), x[4], x[5].equals("1"))); }
        for (String p : Files.readAllLines(Paths.get(a[1]), StandardCharsets.UTF_8)) {
            if (p.trim().isEmpty()) continue;
            Dictee.Resultat r = Dictee.comprendre(p, favs);
            StringBuilder sb = new StringBuilder();
            for (Dictee.Item it : r.items) { if (sb.length() > 0) sb.append(" ; "); sb.append(it.id).append('=').append(Dictee.nb(it.q)).append(it.defaut ? "*" : ""); }
            sb.append(" | ?").append(String.join(" / ", r.incompris));
            if (r.heure != null) sb.append(" @").append(r.heure);
            System.out.println(sb);
        }
    }
}
