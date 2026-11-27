package fr.mesapplis.activite;

/**
 * Le langage Bluetooth standard des home-trainers (FTMS, « Fitness Machine Service ») : Wahoo KICKR Core, Tacx, Elite…
 * Java simple (testé hors du téléphone, android-activite/tests/EssaiFtms.java).
 *
 * - lire()      : les mesures envoyées par le home-trainer (caractéristique « Indoor Bike Data », 0x2AD2).
 * - commandes   : ce qu'on écrit sur le « point de contrôle » (0x2AD9) pour le piloter.
 * - reponse()   : la réponse du home-trainer à une commande (0x80, commande, résultat).
 */
final class Ftms {
    private Ftms() {}

    /** Les modes de pilotage. */
    static final int LIBRE = 0, ERG = 1, PENTE = 2, RESISTANCE = 3, VTT = 4;   // VTT : vitesses de mon vélo imitées (Braquets), envoyées en ERG
    static final String[] NOMS = {"libre", "ERG", "pente", "résistance", "Mon VTT"};

    /** Dernières mesures connues (le home-trainer peut les envoyer en plusieurs morceaux). */
    static final class Mesure {
        double vitesse = -1;     // km/h
        double cadence = -1;     // tours/min
        int puissance = -1;      // watts
        long distance = -1;      // mètres (compteur du home-trainer)
        int fc = -1;             // si le home-trainer la relaie
    }

    private static int u16(byte[] v, int i) { return (v[i] & 0xFF) | ((v[i + 1] & 0xFF) << 8); }
    private static int s16(byte[] v, int i) { return (short) u16(v, i); }

    /** Met à jour m avec une trame « Indoor Bike Data ». Renvoie false si la trame est illisible. */
    static boolean lire(byte[] v, Mesure m) {
        if (v == null || v.length < 2) return false;
        int f = u16(v, 0), i = 2;
        try {
            if ((f & 0x0001) == 0) { m.vitesse = u16(v, i) / 100.0; i += 2; }      // bit 0 à 0 : la vitesse est là
            if ((f & 0x0002) != 0) i += 2;                                          // vitesse moyenne
            if ((f & 0x0004) != 0) { m.cadence = u16(v, i) / 2.0; i += 2; }
            if ((f & 0x0008) != 0) i += 2;                                          // cadence moyenne
            if ((f & 0x0010) != 0) { m.distance = (v[i] & 0xFF) | ((v[i + 1] & 0xFF) << 8) | ((long) (v[i + 2] & 0xFF) << 16); i += 3; }
            if ((f & 0x0020) != 0) i += 2;                                          // niveau de résistance
            if ((f & 0x0040) != 0) { m.puissance = s16(v, i); i += 2; }
            if ((f & 0x0080) != 0) i += 2;                                          // puissance moyenne
            if ((f & 0x0100) != 0) i += 5;                                          // énergie
            if ((f & 0x0200) != 0) { int fc = v[i] & 0xFF; if (fc > 0) m.fc = fc; i += 1; }
            return true;
        } catch (ArrayIndexOutOfBoundsException e) {
            return false;
        }
    }

    // ---------- Commandes ----------

    static byte[] prendreLaMain() { return new byte[]{0x00}; }
    static byte[] demarrer() { return new byte[]{0x07}; }

    /** ERG : des watts à tenir, quelle que soit la cadence. */
    static byte[] puissance(int watts) {
        int w = Math.max(0, Math.min(2000, watts));
        return new byte[]{0x05, (byte) w, (byte) (w >> 8)};
    }

    /** Pente (simulation) : pente en %, roulement 0,004, prise au vent 0,51 kg/m. */
    static byte[] pente(double pourcent) {
        int g = (int) Math.round(Math.max(-20, Math.min(20, pourcent)) * 100);
        return new byte[]{0x11, 0, 0, (byte) g, (byte) (g >> 8), 40, 51};
    }

    /**
     * Résistance de 0 à 100 % de la plage du home-trainer (min et max lus sur lui, en dixièmes ; 0 et 1000 s'ils sont inconnus).
     * Les home-trainers récents attendent un nombre sur 2 octets ; les anciens (plage jusqu'à 25,5) sur 1 octet.
     */
    static byte[] resistance(double pourcent, int min, int max) {
        double p = Math.max(0, Math.min(100, pourcent)) / 100.0;
        int r = (int) Math.round(min + p * (max - min));
        if (max <= 255) return new byte[]{0x04, (byte) Math.max(0, Math.min(255, r))};
        return new byte[]{0x04, (byte) r, (byte) (r >> 8)};
    }

    /** La commande qui correspond à un mode et sa valeur (null pour « libre »). */
    static byte[] commande(int mode, double valeur, int min, int max) {
        switch (mode) {
            case ERG: return puissance((int) Math.round(valeur));
            case PENTE: return pente(valeur);
            case RESISTANCE: return resistance(valeur, min, max);
            default: return null;
        }
    }

    /** Réponse du home-trainer : "" si tout va bien, sinon une phrase à montrer. null si ce n'est pas une réponse. */
    static String reponse(byte[] v) {
        if (v == null || v.length < 3 || (v[0] & 0xFF) != 0x80) return null;
        int op = v[1] & 0xFF, r = v[2] & 0xFF;
        if (r == 1) return "";
        String quoi = op == 0x05 ? "le mode ERG" : op == 0x11 ? "le mode pente" : op == 0x04 ? "le mode résistance" : op == 0x00 ? "la prise de contrôle" : "la commande " + op;
        switch (r) {
            case 2: return "Le home-trainer ne connaît pas " + quoi + ".";
            case 3: return "Valeur refusée par le home-trainer pour " + quoi + ".";
            case 5: return "Le home-trainer est piloté par une autre appli (Wahoo, COROS…) : ferme-la.";
            default: return "Le home-trainer a refusé " + quoi + " (code " + r + ").";
        }
    }

    /** Plage de résistance (caractéristique 0x2AD6) : {min, max} en dixièmes, ou null. */
    static int[] plage(byte[] v) {
        if (v == null || v.length < 4) return null;
        int min = s16(v, 0), max = s16(v, 2);
        return max > min ? new int[]{min, max} : null;
    }
}
