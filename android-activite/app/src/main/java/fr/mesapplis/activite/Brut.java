package fr.mesapplis.activite;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Mesures brutes de l'accéléromètre de la ceinture (50 par seconde), gardées dans un fichier par séance :
 * « enregistrements/brut-<début>.bin ». Elles servent à rejouer une séance après coup (réglage des impacts, comptage).
 *
 * Format (nombres « petit-boutiste ») :
 *   en-tête de 16 octets : "JAB1", heure de début (8 octets, millisecondes), fréquence (2 octets : 50), 2 octets à zéro
 *   puis un bloc par trame reçue : AB CD, millisecondes depuis le début (4 octets), nombre de mesures n (2 octets),
 *   et n mesures de 3 × 2 octets (x, y, z en millièmes de g).
 * Chaque bloc est écrit d'un seul coup : si l'appli est tuée, le fichier reste lisible et la séance reprise continue à la suite.
 * Java simple (rien d'Android). Lecture : activite/outils/lire-brut.js.
 */
final class Brut {
    private final long debut;
    private OutputStream sortie;
    private boolean enPanne = false;

    private Brut(long debut) { this.debut = debut; }

    static Brut ouvrir(File fichier, long debut) {
        Brut b = new Brut(debut);
        try {
            File parent = fichier.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            boolean nouveau = !fichier.exists() || fichier.length() == 0;
            b.sortie = new FileOutputStream(fichier, true);
            if (nouveau) {
                ByteBuffer t = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
                t.put((byte) 'J').put((byte) 'A').put((byte) 'B').put((byte) '1').putLong(debut).putShort((short) 50).putShort((short) 0);
                b.sortie.write(t.array());
            }
        } catch (IOException e) {
            b.enPanne = true;
        }
        return b;
    }

    /** Une trame de mesures à plat (x,y,z,x,y,z…), reçue à l'heure « horloge » du téléphone. */
    synchronized void trame(int[] xyz, long horloge) {
        if (enPanne || sortie == null || xyz == null) return;
        int n = Math.min(xyz.length / 3, 2000);
        if (n == 0) return;
        ByteBuffer b = ByteBuffer.allocate(8 + n * 6).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 0xAB).put((byte) 0xCD).putInt((int) Math.max(0, Math.min(Integer.MAX_VALUE, horloge - debut))).putShort((short) n);
        for (int i = 0; i < n * 3; i++) b.putShort((short) Math.max(-32768, Math.min(32767, xyz[i])));
        try { sortie.write(b.array()); } catch (IOException e) { enPanne = true; }
    }

    synchronized void fermer() {
        try { if (sortie != null) sortie.close(); } catch (IOException ignored) {}
        sortie = null;
    }
}
