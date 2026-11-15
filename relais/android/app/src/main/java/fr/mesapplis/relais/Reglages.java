package fr.mesapplis.relais;

import android.content.Context;
import android.content.SharedPreferences;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Réglages gardés dans le téléphone : code de sécurité, fiche Entreprise, démarrage automatique. */
final class Reglages {
    private Reglages() {}

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("relais", Context.MODE_PRIVATE);
    }

    /** Code à 6 chiffres demandé une seule fois par la tablette. Créé au premier lancement. */
    static synchronized String code(Context c) {
        String k = p(c).getString("code", null);
        if (k == null || k.length() != 6) k = nouveauCode(c);
        return k;
    }

    static synchronized String nouveauCode(Context c) {
        String k = String.format("%06d", new SecureRandom().nextInt(1_000_000));
        p(c).edit().putString("code", k).apply();
        return k;
    }

    /** Fiche « Entreprise » : tableau JSON [{nom, numero}] dans l'ordre choisi. */
    static String favoris(Context c) {
        return p(c).getString("favoris", "[]");
    }

    static void favoris(Context c, String json) {
        p(c).edit().putString("favoris", json).apply();
    }

    /** Relancer le relais tout seul après un redémarrage du téléphone. */
    static boolean auto(Context c) {
        return p(c).getBoolean("auto", false);
    }

    static void auto(Context c, boolean oui) {
        p(c).edit().putBoolean("auto", oui).apply();
    }

    /** Adresses à taper sur la tablette (wifi de la maison, point d'accès…). */
    static List<String[]> adresses() {
        List<String[]> l = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName();
                // Les données mobiles ne sont pas joignables depuis la tablette
                if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("v4-") || n.startsWith("dummy")
                        || n.startsWith("tun") || n.startsWith("ipsec") || n.startsWith("ims")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    String type;
                    if (n.startsWith("swlan") || n.startsWith("ap") || n.startsWith("softap") || n.contains("hotspot")) type = "Point d'accès du téléphone";
                    else if (n.startsWith("wlan")) type = "Wifi";
                    else if (n.startsWith("rndis") || n.startsWith("usb")) type = "Câble USB";
                    else type = "Réseau (" + n + ")";
                    l.add(new String[]{type, "http://" + a.getHostAddress() + ":" + RelaisService.PORT});
                }
            }
        } catch (Exception ignored) {}
        return l;
    }
}
