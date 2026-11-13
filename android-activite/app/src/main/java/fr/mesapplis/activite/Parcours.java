package fr.mesapplis.activite;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parcours GPS d'une séance : distance, allure, tours au kilomètre, tours marqués à la main.
 *
 * Deux règles :
 *  - TOUT ce que le GPS envoie est écrit tout de suite dans « enregistrements/gps-<début>.txt », un point par ligne,
 *    même les points écartés. Rien n'est jeté : on pourra recalculer, couper ou découper un parcours plus tard.
 *    Appli tuée = rien de perdu : à la reprise, le fichier est relu et le calcul repart au même endroit.
 *  - la distance est calculée sur des points filtrés : points trop imprécis écartés, sauts impossibles écartés,
 *    rien de compté à l'arrêt. Elle additionne la vitesse donnée par le GPS (mesurée directement sur les satellites,
 *    bien plus régulière que la position) ; quand la vitesse manque (trou de réception, position « mélangée »),
 *    elle se sert des positions. Une deuxième distance, calculée seulement avec les positions lissées, est gardée
 *    à côté pour comparer avec la montre.
 *
 * Fichier (texte, une ligne par événement, temps en millisecondes depuis le début de la séance) :
 *   G <début> <vitesse max m/s> <source>      en-tête
 *   P <t> <latitude> <longitude> <précision m> <altitude m> <vitesse m/s ou -1> <satellites>
 *   T <t> <FC>                                tour marqué à la main
 *   R <t>                                     reprise après fermeture de l'appli
 *
 * Java simple (rien d'Android) : testé hors téléphone (activite/tests/test-parcours.js).
 */
final class Parcours {
    static final int KM = 1, PREMIER = 2;
    static final double PREC_MAX = 35;                  // au-delà de 35 m de précision annoncée, le point est écarté
    private static final double PREC_DEPART = 15;       // la toute première position doit être bonne (15 m)…
    private static final long ATTENTE_DEPART = 30000;   // …sauf si ça fait 30 s qu'on attend : on prend alors ce qu'on a
    private static final int PAS = 5;                   // le profil (distance, position) est gardé toutes les 5 secondes
    private static final double PAS_POSITION = 10;      // distance par les positions : comptée par bouts de 10 m (sinon les petites erreurs s'additionnent)
    private static final double BRUIT = 3;              // lissage : on suppose qu'on peut changer de vitesse de 3 m/s en 1 s
    private static final long ATTENTE_SAUT = 10000;     // un « saut » qui dure 10 s n'en est pas un : on repart de la nouvelle position

    final long debut;
    private final File fichier;
    private Writer journal;
    private boolean journalKo = false, rejoue = false;
    private double vMax = 12;
    private String source = "sat";
    private int reprises = 0;

    // position lissée, dernier point compté dans la distance, dernier point brut accepté
    private boolean aPos = false;
    private double fLat, fLon, fVar, aLat, aLon, bLat, bLon, cLat, cLon;
    private long fT = 0, bT = -1, refusT = -1, dernierT = -1, dernierOk = -1, attenteT = -1;
    private double dist = 0, distP = 0, precSomme = 0, precDerniere = -1, vitDerniere = -1;
    private int points = 0, gardes = 0, rejetes = 0, sauts = 0, sats = 0, premier = -1;

    private final List<Integer> km = new ArrayList<>();        // seconde de passage de chaque kilomètre
    private final List<int[]> tours = new ArrayList<>();       // tours à la main : [seconde, distance en m, FC]
    private final List<Integer> prof = new ArrayList<>();      // distance (m) toutes les 5 s
    private final List<Integer> trace = new ArrayList<>();     // latitude, longitude (× 100 000) toutes les 5 s ; 0,0 = pas encore de position
    private final long[] fenT = new long[24];                  // derniers points comptés, pour l'allure du moment
    private final double[] fenD = new double[24];
    private int fenN = 0;

    private Parcours(File fichier, long debut) { this.fichier = fichier; this.debut = debut; }

    static File fichierDe(File dossierEnregistrements, long debut) { return new File(dossierEnregistrements, "gps-" + debut + ".txt"); }

    /** Nouveau parcours : le fichier est créé tout de suite. vMax = vitesse au-delà de laquelle un déplacement est un saut du GPS. */
    static Parcours nouveau(File fichier, long debut, double vMax, String source) throws IOException {
        Parcours p = new Parcours(fichier, debut);
        p.vMax = vMax > 0 ? vMax : 12;
        p.source = propre(source);
        File parent = fichier.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        p.journal = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(fichier, false), StandardCharsets.UTF_8));
        p.ecrire("G " + debut + " " + nombre(p.vMax, 1) + " " + p.source);
        return p;
    }

    /** Reprise après fermeture de l'appli : relit le fichier, refait le calcul, et continue à écrire à la suite. Null s'il n'y a rien. */
    static Parcours reprendre(File fichier, long maintenant) throws IOException {
        Parcours p = relire(fichier);
        if (p == null) return null;
        p.journal = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(fichier, true), StandardCharsets.UTF_8));
        p.ecrire("");                                    // termine une éventuelle ligne coupée
        p.reprises++;
        p.ecrire("R " + Math.max(0, maintenant - p.debut));
        return p;
    }

    /** Relit un fichier de parcours sans rien y écrire. */
    static Parcours relire(File fichier) throws IOException {
        if (!fichier.exists() || fichier.length() == 0) return null;
        Parcours p = null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(fichier), StandardCharsets.UTF_8))) {
            String ligne;
            while ((ligne = r.readLine()) != null) {
                if (ligne.length() < 3) continue;
                String[] x = ligne.trim().split(" ");
                try {
                    char type = ligne.charAt(0);
                    if (type == 'G') {
                        if (p == null && x.length >= 2) { p = new Parcours(fichier, Long.parseLong(x[1])); p.rejoue = true; if (x.length > 2) p.vMax = Double.parseDouble(x[2]); if (x.length > 3) p.source = x[3]; }
                        continue;
                    }
                    if (p == null) continue;
                    if (type == 'P' && x.length == 8) p.point(Long.parseLong(x[1]), Double.parseDouble(x[2]), Double.parseDouble(x[3]), Double.parseDouble(x[4]), Double.parseDouble(x[5]), Double.parseDouble(x[6]), Integer.parseInt(x[7]));
                    else if (type == 'T' && x.length == 3) p.tour(Long.parseLong(x[1]), Integer.parseInt(x[2]));
                    else if (type == 'R') p.reprises++;
                } catch (NumberFormatException abimee) { /* dernière ligne coupée par la fermeture : ignorée */ }
            }
        }
        if (p != null) p.rejoue = false;
        return p;
    }

    private static String propre(String s) { return s == null || s.isEmpty() ? "sat" : s.replaceAll("[^A-Za-z0-9]", ""); }
    private static String nombre(double v, int decimales) { return String.format(Locale.US, "%." + decimales + "f", v); }

    private void ecrire(String ligne) {
        if (journal == null || rejoue) return;
        try { journal.write(ligne); journal.write('\n'); journal.flush(); } catch (IOException e) { journalKo = true; }
    }

    /** Distance en mètres entre deux positions. */
    static double metres(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2), dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 6371000.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** Remplit le profil (une valeur toutes les 5 secondes) jusqu'au temps t. */
    private void avancer(long t) {
        while ((long) prof.size() * PAS * 1000 <= t && prof.size() < 20000) {
            prof.add((int) Math.round(dist));
            trace.add(aPos ? (int) Math.round(fLat * 1e5) : 0);
            trace.add(aPos ? (int) Math.round(fLon * 1e5) : 0);
        }
    }

    private void repartirDe(long t, double lat, double lon, double prec) {
        fLat = lat; fLon = lon; fVar = prec * prec; fT = t;
        aLat = lat; aLon = lon;
        cLat = lat; cLon = lon;
        bLat = lat; bLon = lon; bT = t;
        refusT = -1;
    }

    private void noter(long t) { fenT[fenN % fenT.length] = t; fenD[fenN % fenT.length] = dist; fenN++; }

    /**
     * Un point reçu du GPS. t = millisecondes depuis le début ; précision en mètres ; vitesse en m/s (-1 si le GPS ne la donne pas).
     * Renvoie KM quand un kilomètre vient d'être franchi, PREMIER pour la toute première position utilisable.
     */
    synchronized int point(long t, double lat, double lon, double prec, double alt, double vit, int satellites) {
        ecrire("P " + t + " " + nombre(lat, 6) + " " + nombre(lon, 6) + " " + nombre(prec, 1) + " " + nombre(alt, 1) + " " + nombre(vit, 2) + " " + satellites);
        points++;
        if (!(prec > 0)) prec = 50;                      // précision inconnue : point traité comme mauvais
        boolean valide = t > dernierT && prec <= PREC_MAX && Math.abs(lat) <= 90 && Math.abs(lon) <= 180 && !(lat == 0 && lon == 0);
        if (t > dernierT) dernierT = t;
        if (!valide) { rejetes++; return 0; }
        avancer(t);
        sats = satellites;
        if (!aPos) {
            if (attenteT < 0) attenteT = t;
            if (prec > PREC_DEPART && t - attenteT < ATTENTE_DEPART) { rejetes++; return 0; }   // GPS pas encore stabilisé
            aPos = true;
            repartirDe(t, lat, lon, prec);
            premier = (int) (t / 1000);
            dernierOk = t; precDerniere = prec; vitDerniere = vit; precSomme += prec; gardes++;
            noter(t);
            return PREMIER;
        }
        double dt = Math.max(0.2, (t - fT) / 1000.0), ecart = metres(fLat, fLon, lat, lon);
        if (ecart > vMax * dt + prec + Math.sqrt(fVar) + 5) {
            // déplacement impossible : saut du GPS. S'il dure, c'est que la nouvelle position est la bonne (on ne compte pas le saut).
            if (refusT < 0) refusT = t;
            if (t - refusT < ATTENTE_SAUT) { rejetes++; return 0; }
            sauts++;
            repartirDe(t, lat, lon, prec);
            dernierOk = t; precDerniere = prec; vitDerniere = vit; precSomme += prec; gardes++;
            noter(t);
            return 0;
        }
        refusT = -1;
        // position lissée : chaque point pèse selon sa précision
        fVar += dt * BRUIT * BRUIT;
        double k = fVar / (fVar + prec * prec);
        fLat += k * (lat - fLat);
        fLon += k * (lon - fLon);
        fVar *= (1 - k);
        fT = t;
        // distance par les positions lissées : rien à l'arrêt (vitesse GPS presque nulle), et par bouts de 10 m
        boolean arret = vit >= 0 && vit < 0.3;
        double pas = metres(aLat, aLon, fLat, fLon);
        if (!arret && pas >= PAS_POSITION) { distP += pas; aLat = fLat; aLon = fLon; }
        // distance retenue : vitesse du GPS × temps ; après un manque de réception, la ligne droite ; sans vitesse, les positions
        double db = (t - bT) / 1000.0;
        if (vit < 0) {
            double secours = metres(cLat, cLon, fLat, fLon);
            if (secours >= Math.max(PAS_POSITION, prec)) { dist += secours; cLat = fLat; cLon = fLon; }
        } else {
            if (db <= 3) dist += arret ? 0 : Math.min(vit, vMax) * db;
            else { double droit = metres(bLat, bLon, lat, lon); if (!arret && droit > 2 * prec) dist += droit; }
            cLat = fLat; cLon = fLon;
        }
        bLat = lat; bLon = lon; bT = t;
        dernierOk = t; precDerniere = prec; vitDerniere = vit; precSomme += prec; gardes++;
        noter(t);
        int r = 0;
        while (dist >= (km.size() + 1) * 1000.0) { km.add((int) Math.round(t / 1000.0)); r |= KM; }
        return r;
    }

    /** Tour marqué à la main (bouton « Tour »). */
    synchronized void tour(long t, int fc) {
        ecrire("T " + t + " " + fc);
        tours.add(new int[]{(int) Math.round(t / 1000.0), (int) Math.round(dist), fc});
    }

    /** À appeler une fois par seconde. */
    synchronized void tic(long horloge) { avancer(horloge - debut); }

    /** Vitesse du moment (m/s) sur les 12 dernières secondes ; -1 si on ne sait pas. */
    private double vitesse(long t) {
        if (fenN < 2 || t - dernierOk > 6000) return -1;
        int n = Math.min(fenN, fenT.length), dernier = (fenN - 1) % fenT.length, choisi = dernier;
        for (int i = 1; i < n; i++) { int j = ((fenN - 1 - i) % fenT.length + fenT.length) % fenT.length; if (fenT[dernier] - fenT[j] > 12000) break; choisi = j; }
        double duree = (fenT[dernier] - fenT[choisi]) / 1000.0;
        return duree < 3 ? -1 : Math.max(0, (fenD[dernier] - fenD[choisi]) / duree);
    }

    synchronized double distance() { return dist; }
    /** Heure du dernier point reçu (pour clôturer une vieille séance à sa vraie fin). */
    synchronized long finConnue() { return debut + Math.max(0, dernierT); }
    synchronized boolean journalEnPanne() { return journalKo; }
    synchronized int nbTours() { return tours.size(); }
    synchronized int nbKm() { return km.size(); }
    /** Durée (s) du dernier kilomètre entier, ou -1. */
    synchronized int dernierKm() { int n = km.size(); return n == 0 ? -1 : km.get(n - 1) - (n > 1 ? km.get(n - 2) : Math.max(0, premier)); }
    /** Vrai si une position utilisable est arrivée depuis moins de 6 secondes. */
    synchronized boolean frais(long horloge) { return dernierOk >= 0 && horloge - debut - dernierOk < 6000; }
    synchronized boolean aPosition() { return aPos; }
    synchronized double allure(long horloge) { return vitesse(horloge - debut); }

    /** État pour l'écran et la notification. */
    synchronized String etatJson(long horloge) {
        long t = horloge - debut;
        double v = vitesse(t);
        int[] dt = tours.isEmpty() ? null : tours.get(tours.size() - 1);
        int depart = dt != null ? dt[0] : Math.max(0, premier);
        return "{\"dist\":" + Math.round(dist) + ",\"vit\":" + (v < 0 ? "null" : nombre(v, 2)) + ",\"prec\":" + (precDerniere < 0 ? "null" : nombre(precDerniere, 1))
                + ",\"sats\":" + sats + ",\"points\":" + points + ",\"rejetes\":" + rejetes + ",\"premier\":" + premier
                + ",\"age\":" + (dernierOk < 0 ? -1 : Math.max(0, (t - dernierOk) / 1000)) + ",\"km\":" + km.size() + ",\"dernierKm\":" + dernierKm()
                + ",\"tours\":" + tours.size() + ",\"tourT\":" + Math.max(0, t / 1000 - depart) + ",\"tourD\":" + Math.round(dist - (dt != null ? dt[1] : 0))
                + ",\"journalKo\":" + journalKo + "}";
    }

    /** Fin de séance : le résumé du parcours, rangé dans l'enregistrement. Le fichier complet des points reste sur le téléphone. */
    synchronized String resumeJson(long horloge) {
        long t = Math.max(0, horloge - debut);
        avancer(t);
        StringBuilder sb = new StringBuilder(prof.size() * 24 + 400);
        sb.append("{\"dist\":").append(Math.round(dist)).append(",\"distP\":").append(Math.round(distP)).append(",\"duree\":").append(t / 1000)
          .append(",\"premier\":").append(premier).append(",\"dernier\":").append(dernierOk < 0 ? -1 : dernierOk / 1000)
          .append(",\"points\":").append(points).append(",\"gardes\":").append(gardes).append(",\"rejetes\":").append(rejetes).append(",\"sauts\":").append(sauts)
          .append(",\"precMoy\":").append(gardes > 0 ? nombre(precSomme / gardes, 1) : "null").append(",\"reprises\":").append(reprises)
          .append(",\"source\":").append(Json.texte(source)).append(",\"fichier\":").append(Json.texte(fichier.getName())).append(",\"km\":[");
        for (int i = 0; i < km.size(); i++) { if (i > 0) sb.append(','); sb.append(km.get(i)); }
        sb.append("],\"tours\":[");
        for (int i = 0; i < tours.size(); i++) { int[] x = tours.get(i); if (i > 0) sb.append(','); sb.append('[').append(x[0]).append(',').append(x[1]).append(',').append(x[2]).append(']'); }
        sb.append("],\"pas\":").append(PAS).append(",\"prof\":[");
        for (int i = 0; i < prof.size(); i++) { if (i > 0) sb.append(','); sb.append(prof.get(i)); }
        sb.append("],\"trace\":[");
        for (int i = 0; i < trace.size(); i++) { if (i > 0) sb.append(','); sb.append(trace.get(i)); }
        return sb.append("]}").toString();
    }

    /** Ferme le fichier. Il n'est jamais effacé : c'est le tracé complet de la séance. */
    synchronized void fermer() {
        try { if (journal != null) journal.close(); } catch (IOException ignored) {}
        journal = null;
    }
}
