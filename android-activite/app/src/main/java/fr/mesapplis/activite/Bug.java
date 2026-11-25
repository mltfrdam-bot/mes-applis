package fr.mesapplis.activite;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 🐞 Journal des bugs (essai 23) : chaque étape importante ou erreur (note vocale, manettes, télécommande…) est écrite
 * dans « bugs.txt ». ⚙️ Réglages → 🐞 Rapport de bug → 📋 Copier, puis coller dans la conversation avec Claude.
 * Fichier limité à ~120 Ko (on garde la fin).
 */
final class Bug {
    private Bug() {}
    static final String FICHIER = "bugs.txt";
    private static final Object VERROU = new Object();

    static File fichier(Context c) { return new File(ServiceSeance.dossierDonnees(c), FICHIER); }

    static void note(Context c, String source, String texte) {
        if (c == null) return;
        String ligne = new SimpleDateFormat("dd/MM HH:mm:ss", Locale.FRANCE).format(new Date()) + " | " + source + " | " + texte + "\n";
        synchronized (VERROU) {
            try {
                File f = fichier(c);
                File d = f.getParentFile(); if (d != null && !d.exists()) d.mkdirs();
                if (f.length() > 120_000) {
                    byte[] b = Files.readAllBytes(f.toPath());
                    String t = new String(b, StandardCharsets.UTF_8); t = t.substring(t.length() / 2); int i = t.indexOf('\n'); if (i >= 0) t = t.substring(i + 1);
                    try (FileOutputStream o = new FileOutputStream(f, false)) { o.write(t.getBytes(StandardCharsets.UTF_8)); }
                }
                try (FileOutputStream o = new FileOutputStream(f, true)) { o.write(ligne.getBytes(StandardCharsets.UTF_8)); }
            } catch (Exception ignored) {}
        }
    }

    static String lire(Context c) {
        synchronized (VERROU) { try { File f = fichier(c); return f.exists() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : ""; } catch (Exception e) { return ""; } }
    }

    static void effacer(Context c) { synchronized (VERROU) { try { fichier(c).delete(); } catch (Exception ignored) {} } }

    /** Le sens des codes d'erreur de la reconnaissance vocale d'Android. */
    static String erreurVoix(int e) {
        switch (e) {
            case 1: return "1 réseau trop lent";
            case 2: return "2 réseau absent";
            case 3: return "3 problème de micro (audio)";
            case 4: return "4 erreur du serveur de reconnaissance";
            case 5: return "5 erreur côté appli (client)";
            case 6: return "6 rien entendu (silence)";
            case 7: return "7 parole non reconnue";
            case 8: return "8 reconnaissance occupée";
            case 9: return "9 autorisation du micro manquante";
            case 10: return "10 trop de demandes";
            case 11: return "11 serveur déconnecté";
            case 12: return "12 langue non prise en charge";
            case 13: return "13 langue pas téléchargée (hors ligne)";
            case 14: return "14 appareil bloqué";
            default: return e + " (inconnu)";
        }
    }
}
