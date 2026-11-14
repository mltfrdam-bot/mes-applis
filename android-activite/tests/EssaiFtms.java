package fr.mesapplis.activite;

import java.io.File;
import java.nio.file.Files;

/** Essai hors téléphone de Ftms.java et Velo.java (appelé par activite/tests/test-ftms.js). Écrit les réponses dans un fichier JSON. */
public class EssaiFtms {
    static String hex(byte[] b) { if (b == null) return "null"; StringBuilder s = new StringBuilder("\""); for (byte x : b) s.append(String.format("%02x", x & 0xFF)); return s.append('"').toString(); }
    static byte[] oct(String h) { byte[] b = new byte[h.length() / 2]; for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16); return b; }

    public static void main(String[] a) throws Exception {
        StringBuilder sb = new StringBuilder("[");
        Ftms.Mesure m = new Ftms.Mesure();
        // 1. trame KICKR : vitesse 30,50 km/h, cadence 90, puissance 215 W (drapeaux 0x0044)
        sb.append(Ftms.lire(oct("4400ea0bb400d700"), m)).append(',').append(m.vitesse).append(',').append(m.cadence).append(',').append(m.puissance).append(',');
        // 2. trame en deux morceaux : « plus de données » (pas de vitesse) avec distance 1 234 m et puissance 180 W
        sb.append(Ftms.lire(oct("5100d20400b400"), m)).append(',').append(m.distance).append(',').append(m.puissance).append(',').append(m.vitesse).append(',');
        // 3. trame abîmée
        sb.append(Ftms.lire(oct("4400ea"), m)).append(',');
        // 4. commandes
        sb.append(hex(Ftms.puissance(215))).append(',').append(hex(Ftms.pente(6.5))).append(',').append(hex(Ftms.pente(-3))).append(',')
          .append(hex(Ftms.resistance(50, 0, 1000))).append(',').append(hex(Ftms.resistance(50, 0, 200))).append(',').append(hex(Ftms.commande(Ftms.LIBRE, 0, 0, 1000))).append(',');
        // 5. réponses
        sb.append(Fx(Ftms.reponse(oct("800501")))).append(',').append(Fx(Ftms.reponse(oct("801102")))).append(',').append(Fx(Ftms.reponse(oct("800505")))).append(',').append(Fx(Ftms.reponse(oct("4400")))).append(',');
        // 6. plage de résistance
        int[] p = Ftms.plage(oct("0000e8030a00")); sb.append(p == null ? "null" : "[" + p[0] + "," + p[1] + "]").append(',');
        // 7. Velo : 3 secondes, puis relecture du fichier (appli tuée)
        File f = new File(a[0]); f.delete();
        Velo v = Velo.nouveau(f, 1000L); v.regler(Ftms.ERG, 150);
        Ftms.Mesure x = new Ftms.Mesure(); x.puissance = 150; x.cadence = 85; x.vitesse = 36;
        v.seconde(0, x); v.seconde(1, x); x.puissance = 210; v.regler(Ftms.PENTE, 6); v.seconde(2, x); v.seconde(2, x);
        Velo r = Velo.reprendre(f, 1000L);
        sb.append(v.resumeJson()).append(',').append(r.resumeJson()).append(',').append(r.mode()).append(',').append(r.consigne());
        Files.write(new File(a[1]).toPath(), sb.append(']').toString().getBytes("UTF-8"));
        System.out.println("OK");
    }
    static String Fx(String s) { return s == null ? "null" : "\"" + s.replace("\"", "\\\"") + "\""; }
}
