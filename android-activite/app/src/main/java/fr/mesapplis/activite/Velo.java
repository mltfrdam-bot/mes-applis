package fr.mesapplis.activite;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * L'enregistrement d'une séance de home-trainer, à la seconde : watts, cadence, vitesse, mode et consigne.
 * Écrit au fur et à mesure dans « enregistrements/velo-<début>.txt » (appli tuée = rien de perdu, la séance reprend).
 * Lignes : « V seconde watts cadence vitesse×10 mode consigne×10 ». Java simple (testé hors du téléphone).
 */
final class Velo {
    final long debut;
    private final File fichier;
    private final List<int[]> l = new ArrayList<>();
    private int mode = Ftms.LIBRE;
    private double consigne = 0;
    private int derniere = -1;

    private Velo(File fichier, long debut) { this.fichier = fichier; this.debut = debut; }

    static File fichierDe(File dossier, long debut) { return new File(dossier, "velo-" + debut + ".txt"); }

    static Velo nouveau(File fichier, long debut) {
        File d = fichier.getParentFile();
        if (d != null && !d.exists()) d.mkdirs();
        Velo v = new Velo(fichier, debut);
        v.ajouter("G " + debut);
        return v;
    }

    /** Relit une séance en cours (après appli tuée). null si le fichier manque. */
    static Velo reprendre(File fichier, long debut) {
        if (!fichier.exists()) return null;
        Velo v = new Velo(fichier, debut);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(fichier), StandardCharsets.UTF_8))) {
            String ligne;
            while ((ligne = r.readLine()) != null) {
                String[] x = ligne.trim().split(" ");
                try {
                    if (x[0].equals("V") && x.length >= 7) {
                        int[] c = new int[6];
                        for (int i = 0; i < 6; i++) c[i] = Integer.parseInt(x[i + 1]);
                        v.l.add(c); v.derniere = c[0]; v.mode = c[4]; v.consigne = c[5] / 10.0;
                    }
                } catch (Exception ignored) {}           // une ligne coupée par l'arrêt de l'appli : on la saute
            }
        } catch (Exception e) {
            return null;
        }
        return v;
    }

    synchronized int mode() { return mode; }
    synchronized double consigne() { return consigne; }
    synchronized void regler(int mode, double consigne) { this.mode = mode; this.consigne = consigne; }

    /** Une seconde de séance. Les mesures absentes (-1) sont gardées comme -1. */
    synchronized void seconde(int s, Ftms.Mesure m) {
        if (s <= derniere) return;
        derniere = s;
        int[] c = {s, m.puissance, m.cadence < 0 ? -1 : (int) Math.round(m.cadence), m.vitesse < 0 ? -1 : (int) Math.round(m.vitesse * 10), mode, (int) Math.round(consigne * 10)};
        l.add(c);
        ajouter(String.format(Locale.ROOT, "V %d %d %d %d %d %d", c[0], c[1], c[2], c[3], c[4], c[5]));
    }

    synchronized int secondes() { return l.size(); }

    /** Distance roulée (vitesse du home-trainer × temps), en mètres. */
    synchronized double distance() {
        double d = 0;
        for (int[] c : l) if (c[3] > 0) d += c[3] / 36.0;
        return d;
    }

    /** {"pMoy","pMax","cadMoy","vitMoy","dist","kj","l":[[s,W,cad,vit×10,mode,consigne×10]…]} pour l'enregistrement de la séance. */
    synchronized String resumeJson() {
        long pS = 0, cS = 0, vS = 0; int pN = 0, cN = 0, vN = 0, pMax = 0;
        for (int[] c : l) {
            if (c[1] >= 0) { pS += c[1]; pN++; pMax = Math.max(pMax, c[1]); }
            if (c[2] > 0) { cS += c[2]; cN++; }
            if (c[3] >= 0) { vS += c[3]; vN++; }
        }
        StringBuilder sb = new StringBuilder(l.size() * 24 + 200);
        sb.append("{\"pMoy\":").append(pN > 0 ? String.valueOf(Math.round((double) pS / pN)) : "null")
          .append(",\"pMax\":").append(pN > 0 ? String.valueOf(pMax) : "null")
          .append(",\"cadMoy\":").append(cN > 0 ? String.valueOf(Math.round((double) cS / cN)) : "null")
          .append(",\"vitMoy\":").append(vN > 0 ? String.format(Locale.ROOT, "%.1f", vS / 10.0 / vN) : "null")
          .append(",\"dist\":").append(Math.round(distance()))
          .append(",\"kj\":").append(Math.round(pS / 1000.0))
          .append(",\"l\":[");
        for (int i = 0; i < l.size(); i++) {
            int[] c = l.get(i);
            if (i > 0) sb.append(',');
            sb.append('[').append(c[0]).append(',').append(c[1]).append(',').append(c[2]).append(',').append(c[3]).append(',').append(c[4]).append(',').append(c[5]).append(']');
        }
        return sb.append("]}").toString();
    }

    private void ajouter(String ligne) {
        try (FileOutputStream o = new FileOutputStream(fichier, true)) { o.write((ligne + "\n").getBytes(StandardCharsets.UTF_8)); } catch (Exception ignored) {}
    }

    void effacer() { try { fichier.delete(); } catch (Exception ignored) {} }
}
