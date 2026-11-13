package fr.mesapplis.activite;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Rejoue un scénario (trames de la ceinture, FC, coupures, appli tuée) dans l'enregistreur, hors du téléphone.
    java … EssaiEnregistreur <scénario> <journal> <sortie.json>. Utilisé par activite/tests/test-enregistreur.js. */
public class EssaiEnregistreur {
    private static byte[] hex(String s) { byte[] b = new byte[s.length() / 2]; for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16); return b; }

    public static void main(String[] a) throws Exception {
        File journal = new File(a[1]);
        SuiviImpacts suivi = new SuiviImpacts();
        Enregistreur e = null;
        Brut brut = null;
        File fichierBrut = a.length > 3 ? new File(a[3]) : null;
        int bips = 0;
        StringBuilder etats = new StringBuilder("[");
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(a[0]), StandardCharsets.UTF_8))) {
            String ligne;
            while ((ligne = r.readLine()) != null) {
                String[] p = ligne.split(" ", 4);
                switch (p[0]) {
                    case "S": { String[] q = p[3].split(" "); if (q.length > 1) suivi.regler(Integer.parseInt(p[1]), Integer.parseInt(p[2]), q[0].equals("1"), Integer.parseInt(q[1])); else suivi.regler(Integer.parseInt(p[1]), Integer.parseInt(p[2]), q[0].equals("1")); break; }   // S seuil réarmement bip [temps minimum en ms]
                    case "N": e = Enregistreur.nouveau(journal, Long.parseLong(p[1]), ligne.substring(ligne.indexOf(' ', 2) + 1)); if (fichierBrut != null) brut = Brut.ouvrir(fichierBrut, e.debut); break;
                    case "H": e.fc(Trames.fc(hex(p[2])), Long.parseLong(p[1])); break;
                    case "F": {
                        long horloge = Long.parseLong(p[1]), t = Long.parseLong(p[2]);
                        int[] v = Trames.acc(hex(p[3]));
                        if (v == null) break;
                        if (brut != null) brut.trame(v, horloge);
                        int n = v.length / 3;
                        for (int i = 0; i < n; i++) {
                            double m = Math.sqrt((double) v[3 * i] * v[3 * i] + (double) v[3 * i + 1] * v[3 * i + 1] + (double) v[3 * i + 2] * v[3 * i + 2]);
                            if (e.mesure(m, t - (long) (n - 1 - i) * 20, horloge, suivi)) bips++;
                        }
                        break;
                    }
                    case "T": e.tic(Long.parseLong(p[1])); break;
                    case "X": e.lien(p[2].equals("1"), Long.parseLong(p[1])); break;
                    case "A": e.activite(ligne.substring(2)); break;
                    case "Q": if (etats.length() > 1) etats.append(','); etats.append(e.etatJson(Long.parseLong(p[1]))); break;
                    case "K": e = null; suivi = copie(suivi); e = Enregistreur.reprendre(journal, Long.parseLong(p[1])); if (brut != null) { brut = null; brut = Brut.ouvrir(fichierBrut, e.debut); } break;   // appli tuée puis relancée
                    case "V": {   // vieille séance jamais terminée : clôturée depuis le journal, à sa vraie heure de fin
                        e = null;
                        Enregistreur v = Enregistreur.relire(journal);
                        String json = v.terminer(v.finConnue(), "cloture");
                        journal.delete();
                        try (FileOutputStream o = new FileOutputStream(a[2])) { o.write(("{\"rec\":" + json + ",\"bips\":0,\"etats\":[]}").getBytes(StandardCharsets.UTF_8)); }
                        break;
                    }
                    case "E": {
                        String json = e.terminer(Long.parseLong(p[1]), "essai");
                        e.fermerJournal(true);
                        if (brut != null) brut.fermer();
                        try (FileOutputStream o = new FileOutputStream(a[2])) {
                            o.write(("{\"rec\":" + json + ",\"bips\":" + bips + ",\"etats\":" + etats + "]}").getBytes(StandardCharsets.UTF_8));
                        }
                        break;
                    }
                    default: break;
                }
            }
        }
        System.out.println("OK");
    }

    private static SuiviImpacts copie(SuiviImpacts s) { SuiviImpacts n = new SuiviImpacts(); n.regler(s.seuil, s.rearme, s.bip, s.ecart); return n; }
}
