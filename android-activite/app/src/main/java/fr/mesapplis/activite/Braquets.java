package fr.mesapplis.activite;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 🚲 « Mon VTT » (essai 37) : les vitesses du vrai vélo imitées sur le home-trainer (KICKR Core + Zwift Cog, un seul pignon).
 * Le rapport choisi (plateau × pignon) donne le développement ; avec la cadence, on en tire la vitesse du vrai vélo,
 * puis les watts qu'il faudrait sur la route (roulement + air + pente de la route). Le home-trainer reçoit ces watts (mode ERG),
 * recalculés chaque seconde : mouliner sur le 46 = presque dans le vide, appuyer sur le 11 = dur.
 * Java simple (testé hors du téléphone, android-activite/tests/EssaiBraquets.java).
 *
 * Réglages (texte venu de la page, ⚙️ Réglages → 🚲 Mon VTT) :
 * « plateaux|pignons|circonférence mm|poids cycliste kg|poids vélo kg|roulement×10000|air×100|pignon de départ »
 * ex. « 30|46,37,32,28,24,21,18,15,13,11|2290|62|11|80|51|21 ».
 */
final class Braquets {
    static final String DEFAUT = "30|46,37,32,28,24,21,18,15,13,11|2290|62|11|80|51|21";

    final int[] plateaux;          // du plus petit au plus grand
    final int[] pignons;           // du plus grand (facile) au plus petit (dur)
    final double circ;             // m
    final double masse;            // cycliste + vélo, kg
    final double crr, cw;          // roulement, prise au vent (kg/m)
    final int depart;              // dents du pignon de départ
    private int p = 0, i = 0;      // plateau et pignon engagés (indices)

    private Braquets(int[] plateaux, int[] pignons, double circ, double masse, double crr, double cw, int depart) {
        this.plateaux = plateaux; this.pignons = pignons; this.circ = circ; this.masse = masse; this.crr = crr; this.cw = cw; this.depart = depart;
        auDepart();
    }

    private static int[] liste(String t, boolean croissant) {
        List<Integer> l = new ArrayList<>();
        for (String x : t.split("[,; ]+")) { try { int n = Integer.parseInt(x.trim()); if (n >= 8 && n <= 80 && !l.contains(n)) l.add(n); } catch (Exception ignored) {} }
        int[] r = new int[l.size()];
        for (int k = 0; k < r.length; k++) r[k] = l.get(k);
        Arrays.sort(r);
        if (!croissant) for (int a = 0, b = r.length - 1; a < b; a++, b--) { int z = r[a]; r[a] = r[b]; r[b] = z; }
        return r;
    }

    private static double nombre(String[] x, int k, double defaut, double min, double max) {
        try { double v = Double.parseDouble(x[k].trim().replace(',', '.')); return v >= min && v <= max ? v : defaut; } catch (Exception e) { return defaut; }
    }

    /** Lit les réglages ; une valeur abîmée ou absente reprend celle de mon VTT (Rockrider EXPL 540). */
    static Braquets lire(String texte) {
        String[] d = DEFAUT.split("\\|", -1);
        String[] x = (texte == null || texte.trim().isEmpty() ? DEFAUT : texte).split("\\|", -1);
        int[] pl = x.length > 0 ? liste(x[0], true) : new int[0];
        int[] pi = x.length > 1 ? liste(x[1], false) : new int[0];
        if (pl.length == 0) pl = liste(d[0], true);
        if (pi.length == 0) pi = liste(d[1], false);
        double circ = nombre(x, 2, 2290, 1000, 3000) / 1000.0;
        double masse = nombre(x, 3, 62, 25, 200) + nombre(x, 4, 11, 3, 40);
        double crr = nombre(x, 5, 80, 10, 400) / 10000.0;
        double cw = nombre(x, 6, 51, 10, 150) / 100.0;
        int dep = (int) nombre(x, 7, 21, 8, 80);
        return new Braquets(pl, pi, circ, masse, crr, cw, dep);
    }

    /** Remet le pignon de départ (le plus proche s'il n'existe pas) sur le plus petit plateau. */
    void auDepart() {
        p = 0; i = 0;
        for (int k = 0; k < pignons.length; k++) if (Math.abs(pignons[k] - depart) < Math.abs(pignons[i] - depart)) i = k;
    }

    int plateau() { return plateaux[p]; }
    int pignon() { return pignons[i]; }
    boolean plusieursPlateaux() { return plateaux.length > 1; }

    /** Code gardé dans la séance (consigne) : plateau × 100 + pignon, ex. 3021 = 30×21. */
    int code() { return plateau() * 100 + pignon(); }

    /** Remet le rapport d'un code (après appli tuée). */
    void depuisCode(int c) {
        int pl = c / 100, pi = c % 100;
        for (int k = 0; k < plateaux.length; k++) if (plateaux[k] == pl) p = k;
        for (int k = 0; k < pignons.length; k++) if (pignons[k] == pi) i = k;
    }

    boolean plusDur() { if (i >= pignons.length - 1) return false; i++; return true; }
    boolean plusFacile() { if (i <= 0) return false; i--; return true; }
    boolean plateauPlus() { if (p >= plateaux.length - 1) return false; p++; return true; }
    boolean plateauMoins() { if (p <= 0) return false; p--; return true; }

    /** Développement : mètres parcourus par tour de pédale. */
    double developpement() { return (double) plateau() / pignon() * circ; }

    /** Vitesse du vrai vélo, km/h. */
    double vitesse(double cadence) { return cadence <= 0 ? 0 : developpement() * cadence * 0.06; }

    /**
     * Watts qu'il faudrait sur la route à cette cadence (pente de la route en %), pertes de la transmission comprises (3 %).
     * En dessous de 20 tr/min (arrêt, roue libre) : 0.
     */
    int watts(double cadence, double pente) {
        if (cadence < 20) return 0;
        double v = vitesse(cadence) / 3.6;
        double force = masse * 9.81 * (crr + pente / 100.0) + cw * v * v;
        return (int) Math.round(Math.max(0, Math.min(1500, force * v / 0.97)));
    }

    /** Ce que dit la voix au changement de vitesse. */
    String phrase() { return (plusieursPlateaux() ? "Plateau " + plateau() + ", pignon " : "Pignon ") + pignon(); }

    /** « 30×21 » pour l'écran et la notification. */
    String texte() { return plateau() + "×" + pignon(); }

    String etatJson(double pente, int cible) {
        return String.format(Locale.ROOT, "{\"pl\":%d,\"pi\":%d,\"dev\":%.2f,\"pente\":%.1f,\"cible\":%d,\"nbPl\":%d,\"nbPi\":%d,\"rang\":%d}",
                plateau(), pignon(), developpement(), pente, cible, plateaux.length, pignons.length, i + 1);
    }
}
