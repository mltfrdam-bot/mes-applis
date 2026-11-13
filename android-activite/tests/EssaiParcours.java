package fr.mesapplis.activite;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Rejoue un scénario GPS (points, tours, appli tuée) dans le calcul du parcours, hors du téléphone.
    java … EssaiParcours <scénario> <fichier du tracé> <sortie.json>. Utilisé par activite/tests/test-parcours.js.
    Lignes : N <début> <vMax> <source> · P <t> <lat> <lon> <précision> <altitude> <vitesse> <satellites> · T <t> <fc> · K <t> (appli tuée puis relancée) · E <t> (état) · F <t> (fin). */
public class EssaiParcours {
    public static void main(String[] a) throws Exception {
        File fichier = new File(a[1]);
        Parcours p = null;
        int km = 0, premiers = 0;
        StringBuilder etats = new StringBuilder("[");
        String resume = "null";
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(a[0]), StandardCharsets.UTF_8))) {
            String ligne;
            while ((ligne = r.readLine()) != null) {
                String[] x = ligne.split(" ");
                switch (x[0]) {
                    case "N": p = Parcours.nouveau(fichier, Long.parseLong(x[1]), Double.parseDouble(x[2]), x[3]); break;
                    case "P": {
                        int f = p.point(Long.parseLong(x[1]), Double.parseDouble(x[2]), Double.parseDouble(x[3]), Double.parseDouble(x[4]), Double.parseDouble(x[5]), Double.parseDouble(x[6]), Integer.parseInt(x[7]));
                        if ((f & Parcours.KM) != 0) km++;
                        if ((f & Parcours.PREMIER) != 0) premiers++;
                        p.tic(p.debut + Long.parseLong(x[1]));
                        break;
                    }
                    case "T": p.tour(Long.parseLong(x[1]), Integer.parseInt(x[2])); break;
                    case "K": if (p != null) p.fermer(); p = Parcours.reprendre(fichier, Parcours.relire(fichier).debut + Long.parseLong(x[1])); break;
                    case "E": if (etats.length() > 1) etats.append(','); etats.append(p.etatJson(p.debut + Long.parseLong(x[1]))); break;
                    case "F": resume = p.resumeJson(p.debut + Long.parseLong(x[1])); p.fermer(); break;
                    default: break;
                }
            }
        }
        String json = "{\"km\":" + km + ",\"premiers\":" + premiers + ",\"etats\":" + etats + "],\"resume\":" + resume + "}";
        try (FileOutputStream o = new FileOutputStream(a[2])) { o.write(json.getBytes(StandardCharsets.UTF_8)); }
        System.out.println("OK");
    }
}
