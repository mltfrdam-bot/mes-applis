package fr.mesapplis.miroir;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;

/**
 * Juste ce qu'il faut du protocole ADB pour dire au téléphone « passe en mode sans fil (port 5555) ».
 * Le transport (câble USB) est fourni par UsbAdb ; ici, seulement les messages et la clé.
 */
public class AdbProtocole {
    public interface Transport {
        void ecrire(byte[] donnees) throws IOException;
        /** Lit exactement n octets (ou lève une exception après le délai). */
        byte[] lire(int n, int delaiMs) throws IOException;
    }
    public interface Suivi { void etape(String texte); }

    static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f, OKAY = 0x59414b4f, CLSE = 0x45534c43, WRTE = 0x45545257;
    static final int VERSION = 0x01000001, MAXDATA = 256 * 1024;
    static final byte[] PREFIXE_SHA1 = { 0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14 };

    static class Paquet { int cmd, a0, a1; byte[] donnees; }

    private final Transport t;
    private final List<RSAPrivateCrtKey> cles = new ArrayList<>();

    public AdbProtocole(Transport t, List<byte[]> clesPkcs8) {
        this.t = t;
        for (byte[] der : clesPkcs8) {
            try {
                PrivateKey k = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
                if (k instanceof RSAPrivateCrtKey) cles.add((RSAPrivateCrtKey) k);
            } catch (Exception ignored) {}
        }
    }

    void envoyer(int cmd, int a0, int a1, byte[] d) throws IOException {
        if (d == null) d = new byte[0];
        int somme = 0;
        for (byte b : d) somme += b & 0xff;
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(a0).putInt(a1).putInt(d.length).putInt(somme).putInt(~cmd);
        t.ecrire(h.array());
        if (d.length > 0) t.ecrire(d);
    }

    Paquet recevoir(int delaiMs) throws IOException {
        ByteBuffer h = ByteBuffer.wrap(t.lire(24, delaiMs)).order(ByteOrder.LITTLE_ENDIAN);
        Paquet p = new Paquet();
        p.cmd = h.getInt(); p.a0 = h.getInt(); p.a1 = h.getInt();
        int n = h.getInt();
        p.donnees = n > 0 ? t.lire(n, delaiMs) : new byte[0];
        return p;
    }

    /** Signature du jeton envoyé par le téléphone (comme le fait adb sur l'ordinateur). */
    static byte[] signer(RSAPrivateCrtKey k, byte[] jeton) throws Exception {
        byte[] a = new byte[PREFIXE_SHA1.length + jeton.length];
        System.arraycopy(PREFIXE_SHA1, 0, a, 0, PREFIXE_SHA1.length);
        System.arraycopy(jeton, 0, a, PREFIXE_SHA1.length, jeton.length);
        Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        c.init(Cipher.ENCRYPT_MODE, k);
        return c.doFinal(a);
    }

    /** Clé publique au format attendu par Android (pour la fenêtre « Autoriser le débogage USB ? »). */
    static byte[] clePublique(RSAPrivateCrtKey k) {
        BigInteger n = k.getModulus();
        int mots = 64; // RSA 2048 bits
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = n.mod(r32).modInverse(r32);
        n0inv = r32.subtract(n0inv);
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(mots);
        b.putInt(n0inv.intValue());
        for (int i = 0; i < mots; i++) b.putInt(n.shiftRight(32 * i).mod(r32).intValue());
        for (int i = 0; i < mots; i++) b.putInt(rr.shiftRight(32 * i).mod(r32).intValue());
        b.putInt(k.getPublicExponent().intValue());
        String texte = java.util.Base64.getEncoder().encodeToString(b.array()) + " miroir@tablette\0";
        return texte.getBytes(StandardCharsets.US_ASCII);
    }

    /** Connexion + passage en sans fil. Renvoie le message du téléphone. */
    public String passerEnSansFil(int port, Suivi suivi) throws Exception {
        envoyer(CNXN, VERSION, MAXDATA, "host::\0".getBytes(StandardCharsets.US_ASCII));
        int cleEssayee = 0;
        boolean clePubliqueEnvoyee = false;
        while (true) {
            Paquet p = recevoir(clePubliqueEnvoyee ? 90000 : 8000);
            if (p.cmd == CNXN) break;
            if (p.cmd != AUTH) continue;
            if (p.a0 != 1) continue; // 1 = jeton à signer
            if (cleEssayee < cles.size()) {
                envoyer(AUTH, 2, 0, signer(cles.get(cleEssayee++), p.donnees));
            } else if (!clePubliqueEnvoyee && !cles.isEmpty()) {
                suivi.etape("👉 Sur le téléphone : coche « Toujours autoriser », puis appuie sur « Autoriser ».");
                envoyer(AUTH, 3, 0, clePublique(cles.get(0)));
                clePubliqueEnvoyee = true;
            } else {
                throw new IOException("refuse");
            }
        }
        suivi.etape("Téléphone reconnu, passage en sans fil…");
        envoyer(OPEN, 1, 0, ("tcpip:" + port + "\0").getBytes(StandardCharsets.US_ASCII));
        StringBuilder reponse = new StringBuilder();
        try {
            while (true) {
                Paquet p = recevoir(5000);
                if (p.cmd == WRTE) {
                    reponse.append(new String(p.donnees, StandardCharsets.UTF_8));
                    envoyer(OKAY, 1, p.a0, null);
                } else if (p.cmd == CLSE) {
                    break;
                }
            }
        } catch (IOException e) {
            // le téléphone coupe souvent la liaison câble en passant en sans fil : c'est normal
            if (reponse.length() == 0) reponse.append("ok");
        }
        return reponse.toString().trim();
    }
}
