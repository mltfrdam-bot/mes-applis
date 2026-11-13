package fr.mesapplis.activite;

/**
 * Décodage de ce que la ceinture Polar H10 envoie. Java simple (rien d'Android) : testé hors téléphone
 * contre le code de la v31 (activite/tests/test-enregistreur.js).
 */
final class Trames {
    private Trames() {}

    /** Fréquence cardiaque d'une mesure « heart rate measurement » (0 si illisible). */
    static int fc(byte[] v) {
        if (v == null || v.length < 2) return 0;
        if ((v[0] & 1) != 0) return v.length >= 3 ? (v[1] & 0xFF) | ((v[2] & 0xFF) << 8) : 0;
        return v[1] & 0xFF;
    }

    private static int i16(byte[] d, int o) { return (short) ((d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8)); }

    /**
     * Trame d'accéléromètre (flux PMD) → mesures à plat x,y,z,x,y,z… en millièmes de g.
     * Renvoie null si ce n'est pas une trame d'accéléromètre. Types gérés comme dans la v31 : 0, 1 et « delta » compressé.
     */
    static int[] acc(byte[] d) {
        if (d == null || d.length < 10 || (d[0] & 0xFF) != 2) return null;
        int ft = d[9] & 0xFF, o = 10, n = 0;
        int[] out = new int[Math.max(3, d.length * 8)];
        if (ft == 1) {
            while (o + 6 <= d.length) { out[n++] = i16(d, o); out[n++] = i16(d, o + 2); out[n++] = i16(d, o + 4); o += 6; }
        } else if (ft == 0) {
            while (o + 3 <= d.length) { out[n++] = d[o]; out[n++] = d[o + 1]; out[n++] = d[o + 2]; o += 3; }
        } else if ((ft & 0x80) != 0) {
            if (o + 6 > d.length) return new int[0];
            int x = i16(d, o), y = i16(d, o + 2), z = i16(d, o + 4);
            o += 6;
            out[n++] = x; out[n++] = y; out[n++] = z;
            while (o + 2 <= d.length) {
                int nb = d[o] & 0xFF, cnt = d[o + 1] & 0xFF;
                o += 2;
                if (nb > 16) break;                                          // trame abîmée
                int octets = (cnt * 3 * nb + 7) / 8;
                if (o + octets > d.length) break;                            // trame coupée : on garde ce qui est complet
                int bit = 0;
                for (int i = 0; i < cnt; i++) {
                    int[] delta = new int[3];
                    for (int a = 0; a < 3; a++) {
                        int v = 0;
                        for (int k = 0; k < nb; k++) {
                            int by = d[o + ((bit + k) >> 3)] & 0xFF;
                            v |= ((by >> ((bit + k) & 7)) & 1) << k;
                        }
                        bit += nb;
                        if (nb > 0 && (v & (1 << (nb - 1))) != 0) v -= (1 << nb);
                        delta[a] = v;
                    }
                    x += delta[0]; y += delta[1]; z += delta[2];
                    if (n + 3 > out.length) out = java.util.Arrays.copyOf(out, out.length * 2);
                    out[n++] = x; out[n++] = y; out[n++] = z;
                }
                o += octets;
            }
        } else {
            return null;
        }
        return java.util.Arrays.copyOf(out, n);
    }
}
