package fr.mesapplis.miroir;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Relais « WebSocket → TCP », uniquement sur la tablette elle-même (127.0.0.1).
 * La page de Miroir ne peut pas ouvrir de connexion TCP directe : elle ouvre une WebSocket ici,
 * et le relais transmet les octets au téléphone (ADB sans fil, port 5555) par le Wi-Fi.
 * Un jeton secret empêche les autres applis de la tablette de s'en servir.
 */
public class Relais implements Runnable {
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private final ServerSocket serveur;
    private final String jeton;
    private final Context ctx;

    public Relais(Context ctx) throws IOException {
        this.ctx = ctx.getApplicationContext();
        serveur = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        byte[] alea = new byte[16];
        new SecureRandom().nextBytes(alea);
        StringBuilder sb = new StringBuilder();
        for (byte b : alea) sb.append(String.format(Locale.ROOT, "%02x", b));
        jeton = sb.toString();
    }

    public String adresse() { return "ws://127.0.0.1:" + serveur.getLocalPort() + "/" + jeton; }

    @Override public void run() {
        while (!serveur.isClosed()) {
            try {
                final Socket s = serveur.accept();
                new Thread(() -> traiter(s), "relais-client").start();
            } catch (IOException e) {
                if (serveur.isClosed()) return;
            }
        }
    }

    public void arreter() { try { serveur.close(); } catch (IOException ignored) {} }

    /** Réseau Wi-Fi de la tablette (le point d'accès du téléphone), même sans internet. */
    private Network reseauWifi() {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** L'adresse fait-elle partie du réseau Wi-Fi donné ? */
    private boolean surReseau(Network n, String hote) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp == null) return true;
            byte[] h = InetAddress.getByName(hote).getAddress();
            for (LinkAddress la : lp.getLinkAddresses()) {
                if (!(la.getAddress() instanceof Inet4Address)) continue;
                byte[] a = la.getAddress().getAddress();
                int lg = la.getPrefixLength(), i = 0;
                boolean ok = true;
                for (; lg >= 8 && ok; lg -= 8, i++) ok = a[i] == h[i];
                if (ok && lg > 0) { int m = (0xff << (8 - lg)) & 0xff; ok = (a[i] & m) == (h[i] & m); }
                if (ok) return true;
            }
            return false;
        } catch (Exception e) { return true; }
    }

    private static String lireLigne(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) throw new IOException("ligne trop longue");
        }
        if (c == -1 && b.size() == 0) throw new IOException("fin");
        return b.toString("UTF-8");
    }

    private void traiter(Socket client) {
        Socket tel = null;
        try {
            client.setTcpNoDelay(true);
            InputStream brut = new BufferedInputStream(client.getInputStream(), 65536);
            DataInputStream in = new DataInputStream(brut);
            OutputStream out = client.getOutputStream();

            // 1) Demande d'ouverture de la WebSocket
            String premiere = lireLigne(in);
            Map<String, String> entetes = new HashMap<>();
            for (String l = lireLigne(in); !l.isEmpty(); l = lireLigne(in)) {
                int i = l.indexOf(':');
                if (i > 0) entetes.put(l.substring(0, i).trim().toLowerCase(Locale.ROOT), l.substring(i + 1).trim());
            }
            String[] parties = premiere.split(" ");
            if (parties.length < 2) { client.close(); return; }
            String chemin = parties[1];
            String requete = "";
            int q = chemin.indexOf('?');
            if (q >= 0) { requete = chemin.substring(q + 1); chemin = chemin.substring(0, q); }
            if (!chemin.equals("/" + jeton)) { refuser(out, "403 Forbidden"); client.close(); return; }
            Map<String, String> params = new HashMap<>();
            for (String p : requete.split("&")) {
                int e = p.indexOf('=');
                if (e > 0) params.put(p.substring(0, e), URLDecoder.decode(p.substring(e + 1), "UTF-8"));
            }
            if ("1".equals(params.get("usb"))) { traiterUsb(client, in, out, entetes.get("sec-websocket-key")); return; }
            String hote = params.get("h");
            int port = 5555;
            try { port = Integer.parseInt(params.get("p")); } catch (Exception ignored) {}
            String cle = entetes.get("sec-websocket-key");
            if (hote == null || cle == null) { refuser(out, "400 Bad Request"); client.close(); return; }

            // 2) Connexion au téléphone, par le Wi-Fi
            // Par le Wi-Fi seulement si le téléphone est sur CE Wi-Fi ; sinon (le téléphone est
            // connecté au point d'accès de cet appareil), connexion directe.
            Network wifi = reseauWifi();
            tel = (wifi != null && surReseau(wifi, hote)) ? wifi.getSocketFactory().createSocket() : new Socket();
            try {
                tel.connect(new InetSocketAddress(hote, port), 5000);
            } catch (IOException e) {
                refuser(out, "502 Bad Gateway");
                client.close();
                return;
            }
            tel.setTcpNoDelay(true);
            tel.setKeepAlive(true);

            // 3) Réponse d'acceptation de la WebSocket
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            String accepte = Base64.encodeToString(sha1.digest((cle + GUID).getBytes(StandardCharsets.US_ASCII)), Base64.NO_WRAP);
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
                    + accepte + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();

            // 4) Téléphone → page
            final Socket telF = tel;
            final InputStream telIn = tel.getInputStream();
            Thread retour = new Thread(() -> {
                byte[] tampon = new byte[256 * 1024];
                try {
                    int n;
                    while ((n = telIn.read(tampon)) > 0) {
                        synchronized (out) { envoyerTrame(out, 2, tampon, n); }
                    }
                } catch (IOException ignored) {
                } finally {
                    try { synchronized (out) { envoyerTrame(out, 8, new byte[0], 0); } } catch (IOException ignored) {}
                    fermer(client); fermer(telF);
                }
            }, "relais-retour");
            retour.start();

            // 5) Page → téléphone
            OutputStream telOut = tel.getOutputStream();
            while (true) {
                int b0 = in.readUnsignedByte();
                int b1 = in.readUnsignedByte();
                int code = b0 & 0x0F;
                boolean masque = (b1 & 0x80) != 0;
                long longueur = b1 & 0x7F;
                if (longueur == 126) longueur = in.readUnsignedShort();
                else if (longueur == 127) longueur = in.readLong();
                byte[] m = new byte[4];
                if (masque) in.readFully(m);
                if (longueur > 64L * 1024 * 1024) throw new IOException("trame trop grande");
                byte[] donnees = new byte[(int) longueur];
                in.readFully(donnees);
                if (masque) for (int i = 0; i < donnees.length; i++) donnees[i] ^= m[i & 3];
                if (code == 8) break;                                   // fermeture
                if (code == 9) { synchronized (out) { envoyerTrame(out, 10, donnees, donnees.length); } continue; } // ping → pong
                if (code == 10) continue;                               // pong
                if (donnees.length > 0) { telOut.write(donnees); telOut.flush(); }
            }
        } catch (Exception ignored) {
        } finally {
            fermer(client);
            fermer(tel);
        }
    }

    /** Même relais, mais vers le téléphone branché par câble (affichage par câble dans l'appli). */
    private void traiterUsb(Socket client, DataInputStream in, OutputStream out, String cle) {
        UsbAdb lien = null;
        try {
            UsbManager m = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
            UsbDevice d = UsbAdb.trouverTelephone(m);
            if (cle == null || d == null || !m.hasPermission(d)) { refuser(out, "502 Bad Gateway"); return; }
            try { lien = new UsbAdb(m, d); } catch (IOException e) { refuser(out, "409 Conflict"); return; }
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            String accepte = Base64.encodeToString(sha1.digest((cle + GUID).getBytes(StandardCharsets.US_ASCII)), Base64.NO_WRAP);
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
                    + accepte + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();

            final UsbAdb l = lien;
            final boolean[] fini = { false };
            Thread retour = new Thread(() -> {
                byte[] tampon = new byte[256 * 1024];
                try {
                    while (!fini[0]) {
                        int n = l.lireBrut(tampon, 1000);
                        if (n > 0) { synchronized (out) { envoyerTrame(out, 2, tampon, n); } }
                        else if (UsbAdb.trouverTelephone(m) == null) break; // câble débranché
                    }
                } catch (IOException ignored) {
                } finally {
                    try { synchronized (out) { envoyerTrame(out, 8, new byte[0], 0); } } catch (IOException ignored) {}
                    fermer(client);
                }
            }, "relais-usb-retour");
            retour.start();

            while (true) {
                int b0 = in.readUnsignedByte();
                int b1 = in.readUnsignedByte();
                int code = b0 & 0x0F;
                boolean masque = (b1 & 0x80) != 0;
                long longueur = b1 & 0x7F;
                if (longueur == 126) longueur = in.readUnsignedShort();
                else if (longueur == 127) longueur = in.readLong();
                byte[] mq = new byte[4];
                if (masque) in.readFully(mq);
                if (longueur > 64L * 1024 * 1024) throw new IOException("trame trop grande");
                byte[] donnees = new byte[(int) longueur];
                in.readFully(donnees);
                if (masque) for (int i = 0; i < donnees.length; i++) donnees[i] ^= mq[i & 3];
                if (code == 8) break;
                if (code == 9) { synchronized (out) { envoyerTrame(out, 10, donnees, donnees.length); } continue; }
                if (code == 10) continue;
                if (donnees.length > 0) l.ecrire(donnees);   // un message = un envoi USB (en-tête, puis données)
            }
            fini[0] = true;
        } catch (Exception ignored) {
        } finally {
            if (lien != null) lien.fermer();
            fermer(client);
        }
    }

    private static void refuser(OutputStream out, String statut) {
        try { out.write(("HTTP/1.1 " + statut + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); out.flush(); }
        catch (IOException ignored) {}
    }

    private static void envoyerTrame(OutputStream out, int code, byte[] donnees, int n) throws IOException {
        byte[] tete;
        if (n < 126) tete = new byte[]{(byte) (0x80 | code), (byte) n};
        else if (n < 65536) tete = new byte[]{(byte) (0x80 | code), 126, (byte) (n >> 8), (byte) n};
        else {
            tete = new byte[10];
            tete[0] = (byte) (0x80 | code); tete[1] = 127;
            long l = n;
            for (int i = 0; i < 8; i++) tete[9 - i] = (byte) (l >> (8 * i));
        }
        out.write(tete);
        if (n > 0) out.write(donnees, 0, n);
        out.flush();
    }

    private static void fermer(Socket s) { if (s != null) try { s.close(); } catch (IOException ignored) {} }
}
