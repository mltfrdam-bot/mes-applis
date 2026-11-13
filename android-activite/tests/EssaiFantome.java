package fr.mesapplis.activite;

import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Interroge le fantôme de course hors du téléphone. java … EssaiFantome <fantome.json> <questions.txt> <sortie.json>.
    Questions : « E <secondes> <ma distance> » (état) · « P <mètres> <secondes> » (phrase). Utilisé par activite/tests/test-fantome.js. */
public class EssaiFantome {
    public static void main(String[] a) throws Exception {
        Fantome f = Fantome.lire(new String(Files.readAllBytes(Paths.get(a[0])), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder("{\"lu\":" + (f != null) + ",\"reponses\":[");
        boolean premier = true;
        for (String ligne : Files.readAllLines(Paths.get(a[1]), StandardCharsets.UTF_8)) {
            String[] x = ligne.split(" ");
            if (x.length < 3) continue;
            if (!premier) sb.append(',');
            premier = false;
            if (x[0].equals("P")) sb.append(Json.texte(Fantome.phrase(Double.parseDouble(x[1]), Double.parseDouble(x[2]))));
            else sb.append(f == null ? "null" : f.etatJson(Double.parseDouble(x[1]), Double.parseDouble(x[2])));
        }
        sb.append("]}");
        try (FileOutputStream o = new FileOutputStream(a[2])) { o.write(sb.toString().getBytes(StandardCharsets.UTF_8)); }
        System.out.println("OK");
    }
}
