package fr.mesapplis.activite;

import java.util.Locale;

/**
 * Fantôme de course : une course passée contre laquelle on court.
 *
 * Le fantôme est la courbe « distance selon le temps » de la course de référence : une distance toutes les « pas » secondes,
 * comptée depuis le début de la séance de référence ; « premier » = seconde de sa première position GPS (son départ).
 * Les deux courses sont comparées à partir de leur première position GPS.
 *   ecartM > 0 : on est devant le fantôme, de tant de mètres.
 *   ecartS > 0 : on est en avance, de tant de secondes (le fantôme passera ici dans tant de secondes).
 *
 * Écrit par la page dans « fantome-en-cours.json » : {"ref":…,"nom":"…","premier":…,"duree":…,"dist":…,"pas":5,"prof":[…]}.
 * Java simple (rien d'Android) : testé hors téléphone (activite/tests/test-fantome.js).
 */
final class Fantome {
    final long ref;            // début de la course de référence (son identifiant)
    final String nom;          // « Course du 13/11 à 17:46 »
    final int premier, duree, dist, pas;   // duree = durée totale de la séance de référence, en secondes
    private final int[] prof;
    private final String source;

    private Fantome(long ref, String nom, int premier, int duree, int dist, int pas, int[] prof, String source) {
        this.ref = ref; this.nom = nom; this.premier = premier; this.duree = duree; this.dist = dist; this.pas = pas; this.prof = prof; this.source = source;
    }

    /** Lit le fichier écrit par la page. Null si le texte n'est pas un fantôme utilisable. */
    static Fantome lire(String json) {
        try {
            if (json == null || json.length() < 20) return null;
            int a = json.indexOf("\"prof\":["), b = a < 0 ? -1 : json.indexOf(']', a);
            if (b < 0) return null;
            String[] x = json.substring(a + 8, b).split(",");
            int[] prof = new int[x.length];
            for (int i = 0; i < x.length; i++) prof[i] = Integer.parseInt(x[i].trim());
            int pas = (int) nombre(json, "pas", 5), premier = (int) Math.max(0, nombre(json, "premier", 0)), duree = (int) nombre(json, "duree", (prof.length - 1) * pas), dist = (int) nombre(json, "dist", prof[prof.length - 1]);
            if (prof.length < 3 || pas < 1 || dist < 50 || duree - premier < 30) return null;
            return new Fantome((long) nombre(json, "ref", 0), texte(json, "nom"), premier, duree, dist, pas, prof, json);
        } catch (Exception e) {
            return null;
        }
    }

    private static double nombre(String json, String cle, double defaut) {
        int i = json.indexOf("\"" + cle + "\":");
        if (i < 0) return defaut;
        int d = i + cle.length() + 3, f = d;
        while (f < json.length() && "-0123456789.".indexOf(json.charAt(f)) >= 0) f++;
        return f > d ? Double.parseDouble(json.substring(d, f)) : defaut;
    }

    private static String texte(String json, String cle) {
        int i = json.indexOf("\"" + cle + "\":\"");
        if (i < 0) return "";
        int d = i + cle.length() + 4, f = json.indexOf('"', d);
        return f < 0 ? "" : json.substring(d, f).replace("\\", "");
    }

    /** Le fantôme tel qu'il a été reçu : rangé dans la séance, pour pouvoir refaire le bilan plus tard. */
    String json() { return source; }

    /** Temps de course du fantôme (depuis sa première position). */
    int temps() { return Math.max(0, duree - premier); }

    /** Distance du fantôme (m) après « sec » secondes de course. */
    double distA(double sec) {
        if (sec <= 0) return 0;                          // à sa première position, le fantôme est à 0 m
        if (sec >= temps()) return dist;
        return Math.min(dist, distBrute(sec + premier));
    }

    /** Distance à la seconde s de la séance de référence. */
    private double distBrute(double s) {
        double x = s / pas;
        int i = (int) Math.floor(x);
        if (i < 0) return prof[0];
        if (i >= prof.length - 1) {
            double t0 = (prof.length - 1) * (double) pas, reste = duree - t0;
            return reste > 0 && s < duree ? prof[prof.length - 1] + (dist - prof[prof.length - 1]) * (s - t0) / reste : dist;
        }
        return prof[i] + (prof[i + 1] - prof[i]) * (x - i);
    }

    /** Temps de course (s) auquel le fantôme a atteint cette distance ; son temps final s'il ne l'a jamais atteinte. */
    double tempsA(double metres) {
        if (metres <= 0) return 0;
        if (metres >= dist) return temps();
        int bas = premier, haut = duree;                 // la distance ne recule jamais : recherche par moitiés, à la seconde
        while (haut - bas > 1) { int milieu = (bas + haut) / 2; if (distBrute(milieu) < metres) bas = milieu; else haut = milieu; }
        double d0 = distBrute(bas), d1 = distBrute(haut);
        return bas - premier + (d1 > d0 ? (metres - d0) / (d1 - d0) : 0);
    }

    /** Mètres d'avance (positif) ou de retard (négatif) après « sec » secondes de course et « maDist » mètres. */
    double ecartM(double sec, double maDist) { return maDist - distA(sec); }

    /** Secondes d'avance (positif) ou de retard (négatif). Une fois la distance du fantôme dépassée : l'avance sur son temps final. */
    double ecartS(double sec, double maDist) {
        if (!fini(sec) && Math.abs(ecartM(sec, maDist)) < 3) return 0;   // au même endroit que lui (même s'il est à l'arrêt) : égalité
        return tempsA(maDist) - sec;
    }

    /** Le fantôme a-t-il fini sa course après « sec » secondes ? */
    boolean fini(double sec) { return sec >= temps(); }

    // ---------- Prévision d'arrivée (version 1.13) ----------
    // On compare ses FENETRE dernières secondes au fantôme sur la même portion du parcours (la montée est donc comptée),
    // et on suppose qu'on garde ce rapport jusqu'au bout. Le même calcul existe dans noyau.js (prevFantome) pour le bilan.
    static final int FENETRE = 30;
    private final double[] histS = new double[64], histD = new double[64];
    private int nHist = 0;

    /** Une fois par seconde : où on en est (pour la fenêtre de 30 s). */
    void noter(double sec, double maDist) {
        if (nHist > 0 && sec <= histS[(nHist - 1) % 64]) return;
        histS[nHist % 64] = sec; histD[nHist % 64] = maDist; nHist++;
    }

    /** Prévision avec les points notés : null tant qu'elle n'est pas fiable. */
    double[] prevision(double sec, double maDist) {
        for (int k = nHist - 1; k >= Math.max(0, nHist - 64); k--) {
            int i = k % 64;
            if (histS[i] <= sec - FENETRE) return prevision(sec, maDist, histS[i], histD[i]);
        }
        return null;
    }

    /** {arrivée prévue (s), écart prévu (s, positif = plus vite que le fantôme), à tenir maintenant (s/km), moyenne sur le restant (s/km)}.
        À tenir et moyenne : -1 si le fantôme ne peut plus être battu. Null avant le 1er km, après la distance du fantôme ou sans mouvement. */
    double[] prevision(double sec, double maDist, double sec0, double dist0) {
        if (maDist < 1000 || maDist >= dist || sec - sec0 < FENETRE * 0.6 || maDist - dist0 < 20) return null;
        double lui = tempsA(maDist) - tempsA(dist0);
        if (lui < 5) return null;
        double r = Math.max(0.5, Math.min(2.0, (sec - sec0) / lui)), resteLui = temps() - tempsA(maDist);
        if (resteLui <= 0) return null;
        double prevu = sec + r * resteLui, budget = temps() - sec;
        double a = Math.max(0, maDist - 50), b = Math.min(dist, maDist + 50), allureLui = (tempsA(b) - tempsA(a)) / Math.max(1, b - a) * 1000;
        double tenir = budget > 0 ? allureLui * budget / resteLui : -1, moy = budget > 0 ? budget / ((dist - maDist) / 1000.0) : -1;
        return new double[] { prevu, temps() - prevu, tenir, moy };
    }

    /** Temps du fantôme sur la portion [maDist - tourD, maDist] : pour comparer le tour en cours. */
    double tempsPortion(double maDist, double tourD) { return tempsA(Math.min(dist, maDist)) - tempsA(Math.max(0, Math.min(dist, maDist - tourD))); }

    /** « Arrivée prévue 29 minutes 41, 11 secondes d'avance. » */
    static String phrasePrev(double prevu, double ecart) {
        long t = Math.round(prevu), e = Math.round(Math.abs(ecart));
        String quand = (t / 60) + (t / 60 > 1 ? " minutes" : " minute") + (t % 60 > 0 ? " " + (t % 60) : "");
        String ec = e < 1 ? "à égalité" : (e < 60 ? e + (e > 1 ? " secondes" : " seconde") : (e / 60) + (e / 60 > 1 ? " minutes" : " minute") + (e % 60 > 0 ? " " + (e % 60) : "")) + (ecart > 0 ? " d'avance" : " de retard");
        return "Arrivée prévue " + quand + ", " + ec + ".";
    }

    /** La phrase dite par la voix. */
    static String phrase(double metres, double secondes) {
        long m = Math.round(Math.abs(metres));
        if (m < 5) return "À égalité avec le fantôme.";
        String distance = m >= 1000 ? String.format(Locale.FRANCE, "%.1f", m / 1000.0).replace(",0", "") + (m >= 1950 ? " kilomètres" : " kilomètre") : (m >= 100 ? (m + 5) / 10 * 10 : m) + " mètres";
        long s = Math.round(Math.abs(secondes));
        String temps = s < 1 ? "" : s < 60 ? ", " + s + (s > 1 ? " secondes" : " seconde") : ", " + (s / 60) + (s / 60 > 1 ? " minutes" : " minute") + (s % 60 > 0 ? " " + (s % 60) : "");
        return distance + (metres > 0 ? " d'avance" : " de retard") + temps + ".";
    }

    /** État pour l'écran : {"nom", "m", "s", "dist" (où en est le fantôme), "fini"}. */
    String etatJson(double sec, double maDist) { return etatJson(sec, maDist, null, -1, 0); }

    /** Avec la prévision ({"t", "e", "tenir", "moy"} ou null) et le temps du fantôme sur le tour en cours ("tour", -1 sans tour). */
    String etatJson(double sec, double maDist, double[] prev, double tourT, double tourD) {
        String p = prev == null ? "null" : "{\"t\":" + Math.round(prev[0]) + ",\"e\":" + Math.round(prev[1]) + ",\"tenir\":" + Math.round(prev[2]) + ",\"moy\":" + Math.round(prev[3]) + "}";
        return "{\"nom\":" + Json.texte(nom) + ",\"m\":" + Math.round(ecartM(sec, maDist)) + ",\"s\":" + Math.round(ecartS(sec, maDist))
                + ",\"dist\":" + Math.round(distA(sec)) + ",\"total\":" + dist + ",\"temps\":" + temps() + ",\"fini\":" + fini(sec)
                + ",\"prev\":" + p + ",\"tour\":" + (tourT >= 0 && tourD > 0 ? Math.round(tempsPortion(maDist, tourD)) : -1) + "}";
    }
}
