package fr.mesapplis.activite;

import java.util.ArrayList;
import java.util.List;

/**
 * Suit chaque mouvement net de la ceinture (« pic ») et dit s'il compte comme un impact, selon le réglage.
 * Même règle que la v31 (picSuivre) : un pic commence au-dessus de 1,25 g, finit sous 1,15 g,
 * est retenu s'il dépasse 1,4 g et s'il est à plus de 0,4 s du précédent ; il compte s'il dépasse le seuil.
 * Java simple (rien d'Android).
 */
final class SuiviImpacts {
    static final int RIEN = 0, PIC = 1, PIC_COMPTE = 2;

    int seuil = 2500, rearme = 1800;          // millièmes de g
    boolean bip = true;

    private boolean enPic = false;
    private double picM = 0;
    private boolean aDernier = false;
    private long dernierT = 0;
    double dernierM = 0;                      // dernier pic retenu (0 = aucun)
    boolean dernierCompte = false;
    int dernierSeuil = 0;

    /** Réglage « N coups » : pendant un essai, on garde les mesures fortes pour que la page cherche le bon seuil. */
    private List<double[]> essai = null;

    synchronized void regler(int seuil, int rearme, boolean bip) { this.seuil = seuil; this.rearme = rearme; this.bip = bip; }

    synchronized void essaiDemarrer() { essai = new ArrayList<>(); }
    synchronized void essaiArreter() { essai = null; }
    synchronized boolean essaiEnCours() { return essai != null; }
    /** Mesures fortes de l'essai : [[t, m], …] (t en millisecondes, m en millièmes de g). */
    synchronized String essaiJson() {
        StringBuilder sb = new StringBuilder("[");
        if (essai != null) for (int i = 0; i < essai.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('[').append((long) essai.get(i)[0]).append(',').append(Math.round(essai.get(i)[1])).append(']');
        }
        return sb.append(']').toString();
    }

    /** Une mesure (m = force en millièmes de g, t = heure en millisecondes). Renvoie RIEN, PIC ou PIC_COMPTE. */
    synchronized int mesure(double m, long t) {
        if (essai != null && m >= 1250 && essai.size() < 200000) essai.add(new double[]{t, m});
        if (!enPic) {
            if (m > 1250) { enPic = true; picM = m; }
            return RIEN;
        }
        if (m > picM) picM = m;
        if (m >= 1150) return RIEN;
        enPic = false;
        if (picM > 1400 && (!aDernier || t - dernierT > 400)) {
            aDernier = true; dernierT = t; dernierM = picM; dernierSeuil = seuil; dernierCompte = picM > seuil;
            return dernierCompte ? PIC_COMPTE : PIC;
        }
        return RIEN;
    }
}
