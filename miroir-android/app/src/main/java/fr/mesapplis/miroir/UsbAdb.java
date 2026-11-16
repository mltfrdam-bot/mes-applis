package fr.mesapplis.miroir;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Liaison ADB par le câble USB (la tablette est « hôte », le téléphone est l'appareil branché). */
public class UsbAdb implements AdbProtocole.Transport {
    private final UsbDeviceConnection connexion;
    private final UsbInterface interfaceAdb;
    private final UsbEndpoint entree, sortie;
    private final byte[] tampon;
    private final ByteArrayOutputStream reste = new ByteArrayOutputStream();

    /** Interface ADB : classe 255, sous-classe 66, protocole 1. */
    public static UsbInterface trouverInterface(UsbDevice d) {
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface f = d.getInterface(i);
            if (f.getInterfaceClass() == 255 && f.getInterfaceSubclass() == 66 && f.getInterfaceProtocol() == 1) return f;
        }
        return null;
    }

    public static UsbDevice trouverTelephone(UsbManager m) {
        for (UsbDevice d : m.getDeviceList().values()) if (trouverInterface(d) != null) return d;
        return null;
    }

    public UsbAdb(UsbManager m, UsbDevice d) throws IOException {
        interfaceAdb = trouverInterface(d);
        if (interfaceAdb == null) throw new IOException("pas-adb");
        UsbEndpoint e = null, s = null;
        for (int i = 0; i < interfaceAdb.getEndpointCount(); i++) {
            UsbEndpoint p = interfaceAdb.getEndpoint(i);
            if (p.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
            if (p.getDirection() == UsbConstants.USB_DIR_IN) e = p; else s = p;
        }
        if (e == null || s == null) throw new IOException("pas-adb");
        entree = e; sortie = s;
        connexion = m.openDevice(d);
        if (connexion == null) throw new IOException("ouverture");
        if (!connexion.claimInterface(interfaceAdb, true)) { connexion.close(); throw new IOException("occupe"); }
        tampon = new byte[Math.max(16384, entree.getMaxPacketSize())];
    }

    @Override public void ecrire(byte[] d) throws IOException {
        int fait = 0;
        while (fait < d.length) {
            int n = Math.min(16384, d.length - fait);
            int r = connexion.bulkTransfer(sortie, d, fait, n, 5000);
            if (r < 0) throw new IOException("envoi");
            fait += r;
        }
        // paquet de longueur nulle si la taille tombe pile sur un multiple du paquet USB
        if (d.length > 0 && d.length % sortie.getMaxPacketSize() == 0) connexion.bulkTransfer(sortie, new byte[0], 0, 1000);
    }

    @Override public byte[] lire(int n, int delaiMs) throws IOException {
        long fin = System.currentTimeMillis() + delaiMs;
        while (reste.size() < n) {
            int attente = (int) Math.max(1, Math.min(1000, fin - System.currentTimeMillis()));
            int r = connexion.bulkTransfer(entree, tampon, tampon.length, attente);
            if (r > 0) reste.write(tampon, 0, r);
            else if (System.currentTimeMillis() >= fin) throw new IOException("delai");
        }
        byte[] tout = reste.toByteArray();
        byte[] res = new byte[n];
        System.arraycopy(tout, 0, res, 0, n);
        reste.reset();
        reste.write(tout, n, tout.length - n);
        return res;
    }

    /** Lecture de ce qui arrive (pour le relais) : nombre d'octets, 0 si rien pendant le délai. */
    public int lireBrut(byte[] dest, int delaiMs) {
        int r = connexion.bulkTransfer(entree, dest, dest.length, delaiMs);
        return Math.max(r, 0);
    }

    public void fermer() {
        try { connexion.releaseInterface(interfaceAdb); } catch (Exception ignored) {}
        try { connexion.close(); } catch (Exception ignored) {}
    }
}
