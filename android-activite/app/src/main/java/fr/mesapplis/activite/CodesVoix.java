package fr.mesapplis.activite;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 🎙️ Mots-codes de fin de dictée (essai 36, décidés par Frédéric le 27/11/2026).
 * Un mot-code ne compte que s'il est le DERNIER mot dit, suivi d'un petit silence :
 *  « terminé »          → la note est validée tout de suite, sans relecture ;
 *  « lire » / « relire » → la note est relue à voix haute, puis validée 3 s après (sauf « effacer » ou ✕) ;
 *  « effacer »          → toute la note est effacée, le micro reste ouvert pour recommencer.
 * Les mots-codes ne sont jamais écrits dans la note. Pas de drapeau U (refusé par Android).
 */
public final class CodesVoix {
    public static final int AUCUN = 0, TERMINE = 1, LIRE = 2, EFFACER = 3;

    private static final String AVANT = "(?<![\\p{L}\\p{N}_])";
    private static final Pattern FIN = Pattern.compile("(?iu)[\\s,.;:!?-]*" + AVANT
            + "(?:(re)?lire(?:[\\s,.;:!?-]+termin(?:é|ée|er|e|és|ées))?|relis|relit"
            + "|termin(?:é|ée|er|e|és|ées)"
            + "|effac(?:er|e|é|ée|és|ez))[\\s.!?]*$");

    private CodesVoix() { }

    /** Le mot-code qui finit la phrase (AUCUN, TERMINE, LIRE, EFFACER). */
    public static int code(String t) {
        if (t == null) return AUCUN;
        Matcher m = FIN.matcher(t);
        if (!m.find()) return AUCUN;
        String c = m.group().toLowerCase(java.util.Locale.ROOT).replaceAll("[\\s,.;:!?-]", "");
        if (c.startsWith("effac")) return EFFACER;
        if (c.startsWith("termin")) return TERMINE;
        return LIRE;
    }

    /** La phrase sans le mot-code final (inchangée s'il n'y en a pas). */
    public static String sans(String t) {
        if (t == null) return "";
        Matcher m = FIN.matcher(t);
        return (m.find() ? t.substring(0, m.start()) : t).trim();
    }
}
