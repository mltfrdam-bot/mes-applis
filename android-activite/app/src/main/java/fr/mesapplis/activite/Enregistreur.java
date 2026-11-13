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

/**
 * Enregistrement d'une séance à la seconde : fréquence cardiaque + mouvement résumé + impacts.
 *
 * Même calcul que la v31 (recEch / recFermer / recResume), avec deux différences voulues pour l'APK :
 *  - chaque seconde est écrite tout de suite dans un journal sur le téléphone : appli tuée = rien de perdu, on reprend ;
 *  - les coupures de la ceinture sont notées avec leur durée.
 * Une ligne par seconde : [seconde, FC, mouvement moyen, mouvement max, impacts]. Pas de ligne = rien reçu (trou).
 * Java simple (rien d'Android) : testé hors téléphone (activite/tests/test-enregistreur.js).
 */
final class Enregistreur {
    final long debut;                                   // heure de début (millisecondes)
    private String act;
    private final List<int[]> l = new ArrayList<>();    // secondes
    private final List<int[]> pics = new ArrayList<>(); // mouvements nets : [seconde, force, compté 0/1, seuil]
    private final List<int[]> deco = new ArrayList<>(); // coupures de la ceinture : [seconde de début, durée]
    private int coupures = 0;                           // reprises après fermeture de l'appli
    private final File fichier;
    private Writer journal;
    private boolean journalKo = false;

    // seconde en cours (comme recSec dans la v31)
    private int sS = -1, sN = 0, sPics = 0;
    private double sSom = 0, sMax = 0;
    private boolean sEnPic = false;
    private boolean aImpact = false;
    private long dernierImpactT = 0;
    private int derniereSeconde = -1;

    private int fc = 0;
    private long fcT = 0;
    private boolean relie = true;
    private int decoDebut = -1;

    // totaux tenus à jour
    private int actif = 0, totalPics = 0, fcN = 0, fcMax = 0;
    private long fcSomme = 0;

    private Enregistreur(File fichier, long debut, String act) { this.fichier = fichier; this.debut = debut; this.act = propre(act); }

    private static String propre(String s) { return s == null ? "" : s.replace('\n', ' ').replace('\r', ' '); }

    /** Nouvelle séance : le journal est créé tout de suite. */
    static Enregistreur nouveau(File fichier, long debut, String act) throws IOException {
        Enregistreur e = new Enregistreur(fichier, debut, act);
        e.journal = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(fichier, false), StandardCharsets.UTF_8));
        e.ecrire("D " + debut);
        e.ecrire("A " + e.act);
        return e;
    }

    /** Reprise après fermeture de l'appli : relit le journal. Renvoie null s'il n'y a rien à reprendre. */
    static Enregistreur reprendre(File fichier, long maintenant) throws IOException {
        if (!fichier.exists() || fichier.length() == 0) return null;
        Enregistreur e = null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(fichier), StandardCharsets.UTF_8))) {
            String ligne;
            while ((ligne = r.readLine()) != null) {
                if (ligne.length() < 2) continue;
                char type = ligne.charAt(0);
                String reste = ligne.substring(2);
                try {
                    if (type == 'D') { if (e == null) e = new Enregistreur(fichier, Long.parseLong(reste.trim()), ""); continue; }
                    if (e == null) continue;
                    if (type == 'A') { e.act = reste; continue; }
                    String[] p = reste.trim().split(" ");
                    if (type == 'L' && p.length == 5) e.ajouterLigne(new int[]{n(p[0]), n(p[1]), n(p[2]), n(p[3]), n(p[4])});
                    else if (type == 'P' && p.length == 4) e.pics.add(new int[]{n(p[0]), n(p[1]), n(p[2]), n(p[3])});
                    else if (type == 'C' && p.length == 2) e.deco.add(new int[]{n(p[0]), n(p[1])});
                    else if (type == 'R') e.coupures++;
                } catch (NumberFormatException abimee) { /* dernière ligne coupée par la fermeture : ignorée */ }
            }
        }
        if (e == null) return null;
        e.coupures++;
        e.journal = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(fichier, true), StandardCharsets.UTF_8));
        e.ecrire("");                                    // termine une éventuelle ligne coupée
        e.ecrire("R " + maintenant);
        return e;
    }

    private static int n(String s) { return Integer.parseInt(s); }

    private void ecrire(String ligne) {
        if (journal == null) return;
        try { journal.write(ligne); journal.write('\n'); journal.flush(); } catch (IOException e) { journalKo = true; }
    }

    private void ajouterLigne(int[] x) {
        l.add(x);
        derniereSeconde = Math.max(derniereSeconde, x[0]);
        if (x[2] > 120) actif++;
        totalPics += x[4];
        if (x[1] > 0) { fcN++; fcSomme += x[1]; if (x[1] > fcMax) fcMax = x[1]; }
    }

    private int seconde(long horloge) { return (int) Math.max(0, (horloge - debut) / 1000); }
    private int fcFraiche(long horloge) { return fc > 0 && horloge - fcT < 6000 ? fc : 0; }

    synchronized void activite(String a) { act = propre(a); ecrire("A " + act); }
    synchronized String activite() { return act; }

    synchronized void fc(int bpm, long horloge) { fc = bpm; fcT = horloge; }

    /** La ceinture vient d'être perdue ou retrouvée. */
    synchronized void lien(boolean ok, long horloge) {
        if (ok == relie) return;
        relie = ok;
        int s = seconde(horloge);
        if (!ok) { decoDebut = s; fc = 0; }
        else if (decoDebut >= 0) { int[] c = {decoDebut, Math.max(0, s - decoDebut)}; deco.add(c); ecrire("C " + c[0] + " " + c[1]); decoDebut = -1; }
    }

    /**
     * Une mesure de mouvement. m = force (millièmes de g), t = heure précise de la mesure (ms), horloge = heure du téléphone (ms).
     * Renvoie vrai quand un impact vient d'être compté ET qu'il faut biper.
     */
    synchronized boolean mesure(double m, long t, long horloge, SuiviImpacts suivi) {
        boolean biper = false;
        int sec = seconde(horloge);
        int r = suivi.mesure(m, t);
        if (r != SuiviImpacts.RIEN) {
            int[] p = {sec, (int) Math.round(suivi.dernierM), r == SuiviImpacts.PIC_COMPTE ? 1 : 0, suivi.dernierSeuil};
            pics.add(p);
            ecrire("P " + p[0] + " " + p[1] + " " + p[2] + " " + p[3]);
            biper = r == SuiviImpacts.PIC_COMPTE && suivi.bip;
        }
        if (sS != sec) { if (sS >= 0) fermer(horloge); sS = sec; sN = 0; sSom = 0; sMax = 0; sPics = 0; sEnPic = false; }
        double dv = Math.abs(m - 1000);
        sN++; sSom += dv; if (dv > sMax) sMax = dv;
        if (m > suivi.seuil && !sEnPic && (!aImpact || t - dernierImpactT > 400)) { sPics++; aImpact = true; dernierImpactT = t; sEnPic = true; }
        else if (m < suivi.rearme) sEnPic = false;
        return biper;
    }

    private void fermer(long horloge) {
        int[] x = {sS, fcFraiche(horloge), (int) Math.round(sSom / Math.max(1, sN)), (int) Math.round(sMax), sPics};
        sS = -1;
        if (x[0] <= derniereSeconde) return;              // l'horloge a reculé : on ne réécrit jamais une seconde
        ajouterLigne(x);
        ecrire("L " + x[0] + " " + x[1] + " " + x[2] + " " + x[3] + " " + x[4]);
    }

    /** À appeler une fois par seconde : ferme la seconde finie, et note la FC même sans mesure de mouvement. */
    synchronized void tic(long horloge) {
        int sec = seconde(horloge);
        if (sS >= 0 && sS < sec) fermer(horloge);
        int avant = sec - 1;
        if (avant > derniereSeconde && sS != avant && relie) {
            int f = fcFraiche(horloge);
            if (f > 0) { int[] x = {avant, f, 0, 0, 0}; ajouterLigne(x); ecrire("L " + x[0] + " " + x[1] + " 0 0 0"); }
        }
    }

    synchronized boolean journalEnPanne() { return journalKo; }

    /** État pour l'écran et la notification. */
    synchronized String etatJson(long horloge) {
        int ecoule = seconde(horloge);
        return "{\"debut\":" + debut + ",\"act\":" + Json.texte(act) + ",\"ecoule\":" + ecoule + ",\"duree\":" + l.size() + ",\"actif\":" + actif
                + ",\"pics\":" + (totalPics + (sS >= 0 ? sPics : 0)) + ",\"fcMoy\":" + (fcN > 0 ? String.valueOf(Math.round((double) fcSomme / fcN)) : "null")
                + ",\"fcMax\":" + (fcN > 0 ? String.valueOf(fcMax) : "null") + ",\"coupures\":" + coupures + ",\"deconnexions\":" + (deco.size() + (decoDebut >= 0 ? 1 : 0))
                + ",\"perdu\":" + Math.max(0, ecoule - l.size()) + ",\"mouvements\":" + pics.size() + ",\"journalKo\":" + journalKo + "}";
    }

    synchronized int duree() { return l.size(); }
    synchronized int impacts() { return totalPics + (sS >= 0 ? sPics : 0); }

    /** Fin de séance : renvoie l'enregistrement complet, au même format que la v31 (plus les coupures de ceinture). */
    synchronized String terminer(long horloge, String source) {
        if (sS >= 0) fermer(horloge);
        if (!relie && decoDebut >= 0) { int[] c = {decoDebut, Math.max(0, seconde(horloge) - decoDebut)}; deco.add(c); ecrire("C " + c[0] + " " + c[1]); decoDebut = -1; }
        StringBuilder sb = new StringBuilder(l.size() * 20 + 400);
        sb.append("{\"debut\":").append(debut).append(",\"act\":").append(Json.texte(act));
        sb.append(",\"res\":{\"duree\":").append(l.size()).append(",\"actif\":").append(actif).append(",\"pics\":").append(totalPics);
        sb.append(",\"fcMoy\":").append(fcN > 0 ? String.valueOf(Math.round((double) fcSomme / fcN)) : "null");
        sb.append(",\"fcMax\":").append(fcN > 0 ? String.valueOf(fcMax) : "null").append("},\"l\":");
        liste(sb, l);
        sb.append(",\"coupures\":").append(coupures).append(",\"pics\":");
        liste(sb, pics);
        sb.append(",\"deconnexions\":");
        liste(sb, deco);
        sb.append(",\"fin\":").append(horloge).append(",\"source\":").append(Json.texte(source)).append('}');
        return sb.toString();
    }

    private static void liste(StringBuilder sb, List<int[]> lignes) {
        sb.append('[');
        for (int i = 0; i < lignes.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('[');
            int[] x = lignes.get(i);
            for (int k = 0; k < x.length; k++) { if (k > 0) sb.append(','); sb.append(x[k]); }
            sb.append(']');
        }
        sb.append(']');
    }

    /** Ferme le journal. Il n'est effacé que si l'enregistrement final a bien été écrit ailleurs. */
    synchronized void fermerJournal(boolean effacer) {
        try { if (journal != null) journal.close(); } catch (IOException ignored) {}
        journal = null;
        if (effacer) fichier.delete();
    }
}
