package fr.mesapplis.relais;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Le relais : tourne en permanence sur le téléphone (notification fixe) et attend les ordres de la tablette
 * sur le réseau local (port 8080). Il sert aussi la page de la tablette : il suffit d'ouvrir l'adresse dans Chrome.
 */
public class RelaisService extends Service {
    static final int PORT = 8080;
    private static final String CANAL = "relais";

    static volatile boolean actif = false;

    // État de l'appel, mis à jour par le téléphone
    private static volatile String etat = "libre";      // libre | sonne | compose | en_appel
    private static volatile String numero = "";
    private static volatile long depuis = 0;

    private ServerSocket serveur;
    private boolean lance = false;
    private final ExecutorService pool = Executors.newFixedThreadPool(6);
    private PowerManager.WakeLock reveil;
    private WifiManager.WifiLock verrouWifi;
    private BroadcastReceiver ecouteAppels;

    // Protection contre les essais de code au hasard
    private int echecs = 0;
    private long bloqueJusqua = 0;

    static void demarrer(Context c) {
        Intent i = new Intent(c, RelaisService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CANAL, "Relais actif", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Montre que le relais d'appels fonctionne");
        nm.createNotificationChannel(ch);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = notification();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);

        if (!lance) {
            lance = true;
            garderEveille();
            ecouterAppels();
            Thread t = new Thread(this::boucleServeur, "relais-serveur");
            t.setDaemon(true);
            t.start();
        }
        actif = true;
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        actif = false;
        try { if (serveur != null) serveur.close(); } catch (IOException ignored) {}
        serveur = null;
        pool.shutdownNow();
        try { if (ecouteAppels != null) unregisterReceiver(ecouteAppels); } catch (Exception ignored) {}
        try { if (reveil != null && reveil.isHeld()) reveil.release(); } catch (Exception ignored) {}
        try { if (verrouWifi != null && verrouWifi.isHeld()) verrouWifi.release(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    private Notification notification() {
        List<String[]> ad = Reglages.adresses();
        String texte = ad.isEmpty() ? "En attente d'un wifi ou du point d'accès" : ad.get(0)[1];
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CANAL)
                .setSmallIcon(android.R.drawable.stat_sys_phone_call)
                .setContentTitle("Relais Appels actif")
                .setContentText(texte)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    @SuppressWarnings("deprecation")
    private void garderEveille() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            reveil = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "relais:serveur");
            reveil.acquire();
        } catch (Exception ignored) {}
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            verrouWifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "relais:wifi");
            verrouWifi.acquire();
        } catch (Exception ignored) {}
    }

    // ---------- Suivi des appels ----------

    @SuppressWarnings("deprecation")
    private void ecouterAppels() {
        ecouteAppels = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                String st = i.getStringExtra(TelephonyManager.EXTRA_STATE);
                String num = i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
                if (TelephonyManager.EXTRA_STATE_RINGING.equals(st)) {
                    if (!"sonne".equals(etat)) depuis = System.currentTimeMillis();
                    etat = "sonne";
                    if (num != null && !num.isEmpty()) numero = num;
                } else if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(st)) {
                    if (!"en_appel".equals(etat)) depuis = System.currentTimeMillis();
                    etat = "en_appel";
                    if (num != null && !num.isEmpty()) numero = num;
                } else if (TelephonyManager.EXTRA_STATE_IDLE.equals(st)) {
                    etat = "libre";
                    numero = "";
                    depuis = 0;
                }
            }
        };
        IntentFilter f = new IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(ecouteAppels, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(ecouteAppels, f);
    }

    private boolean permis(String p) {
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    private JSONObject etatJson() throws Exception {
        // Recalage : si le téléphone dit qu'il n'y a plus d'appel, on repasse à « libre »
        try {
            if (permis(Manifest.permission.READ_PHONE_STATE)) {
                TelecomManager tm = (TelecomManager) getSystemService(TELECOM_SERVICE);
                if (!tm.isInCall() && !"compose".equals(etat)) { etat = "libre"; numero = ""; depuis = 0; }
                if ("compose".equals(etat) && System.currentTimeMillis() - depuis > 8000 && !tm.isInCall()) {
                    etat = "libre"; numero = ""; depuis = 0;
                }
            }
        } catch (Exception ignored) {}
        JSONObject o = new JSONObject();
        o.put("etat", etat);
        o.put("numero", numero);
        o.put("nom", numero.isEmpty() ? "" : nomDe(numero));
        o.put("depuis", depuis);
        o.put("maintenant", System.currentTimeMillis());
        return o;
    }

    private String nomDe(String num) {
        if (!permis(Manifest.permission.READ_CONTACTS)) return "";
        try (Cursor c = getContentResolver().query(
                Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(num)),
                new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {}
        return "";
    }

    // ---------- Actions ----------

    private JSONObject appeler(String num, boolean hautParleur) throws Exception {
        JSONObject r = new JSONObject();
        num = num == null ? "" : num.replaceAll("[^0-9+*#]", "");
        if (num.isEmpty() || num.length() > 20) return r.put("ok", false).put("message", "Numéro invalide");
        if (!permis(Manifest.permission.CALL_PHONE))
            return r.put("ok", false).put("message", "Autorisation « Appels » manquante sur le téléphone");
        Uri tel = Uri.fromParts("tel", num, null);
        try {
            TelecomManager tm = (TelecomManager) getSystemService(TELECOM_SERVICE);
            Bundle extras = new Bundle();
            extras.putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, hautParleur);
            tm.placeCall(tel, extras);
        } catch (Exception e) {
            // Solution de secours : demander l'appel à l'appli Téléphone
            try {
                Intent i = new Intent(Intent.ACTION_CALL, tel);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception e2) {
                return r.put("ok", false).put("message", "Le téléphone a refusé l'appel : " + e2.getMessage());
            }
        }
        etat = "compose";
        numero = num;
        depuis = System.currentTimeMillis();
        return r.put("ok", true);
    }

    @SuppressWarnings("deprecation")
    private JSONObject raccrocher() throws Exception {
        JSONObject r = new JSONObject();
        if (!permis(Manifest.permission.ANSWER_PHONE_CALLS))
            return r.put("ok", false).put("message", "Autorisation « Gérer les appels » manquante sur le téléphone");
        try {
            TelecomManager tm = (TelecomManager) getSystemService(TELECOM_SERVICE);
            boolean ok = Build.VERSION.SDK_INT >= 28 && tm.endCall();
            if (ok) { etat = "libre"; numero = ""; depuis = 0; }
            return r.put("ok", ok).put("message", ok ? "" : "Le téléphone n'a pas raccroché");
        } catch (Exception e) {
            return r.put("ok", false).put("message", "Raccrocher refusé : " + e.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    private JSONObject decrocher() throws Exception {
        JSONObject r = new JSONObject();
        if (!permis(Manifest.permission.ANSWER_PHONE_CALLS))
            return r.put("ok", false).put("message", "Autorisation « Gérer les appels » manquante sur le téléphone");
        try {
            TelecomManager tm = (TelecomManager) getSystemService(TELECOM_SERVICE);
            tm.acceptRingingCall();
            return r.put("ok", true);
        } catch (Exception e) {
            return r.put("ok", false).put("message", "Décrocher refusé : " + e.getMessage());
        }
    }

    private JSONObject contacts() throws Exception {
        JSONObject r = new JSONObject();
        if (!permis(Manifest.permission.READ_CONTACTS))
            return r.put("ok", false).put("message", "Autorisation « Contacts » manquante sur le téléphone");
        LinkedHashMap<String, String> noms = new LinkedHashMap<>();
        HashMap<String, LinkedHashSet<String>> nums = new HashMap<>();
        String[] proj = {
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER};
        try (Cursor c = getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj, null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE LOCALIZED ASC")) {
            while (c != null && c.moveToNext()) {
                String id = c.getString(0), nom = c.getString(1), num = c.getString(2);
                if (num == null || num.trim().isEmpty()) continue;
                if (!noms.containsKey(id)) { noms.put(id, nom == null ? num : nom); nums.put(id, new LinkedHashSet<>()); }
                nums.get(id).add(num.trim());
            }
        }
        JSONArray l = new JSONArray();
        for (Map.Entry<String, String> e : noms.entrySet()) {
            // Évite les doublons du même numéro écrit de deux façons
            LinkedHashSet<String> vus = new LinkedHashSet<>();
            JSONArray n = new JSONArray();
            for (String s : nums.get(e.getKey())) {
                String cle = s.replaceAll("[^0-9+]", "").replaceFirst("^\\+262", "0").replaceFirst("^\\+33", "0");
                if (vus.add(cle)) n.put(s);
            }
            l.put(new JSONObject().put("nom", e.getValue()).put("numeros", n));
        }
        return r.put("ok", true).put("contacts", l);
    }

    private JSONObject historique() throws Exception {
        JSONObject r = new JSONObject();
        if (!permis(Manifest.permission.READ_CALL_LOG))
            return r.put("ok", false).put("message", "Autorisation « Journal d'appels » manquante sur le téléphone");
        JSONArray l = new JSONArray();
        String[] proj = {CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION};
        try (Cursor c = getContentResolver().query(CallLog.Calls.CONTENT_URI, proj, null, null, CallLog.Calls.DATE + " DESC")) {
            int n = 0;
            while (c != null && c.moveToNext() && n < 40) {
                String num = c.getString(0);
                if (num == null || num.isEmpty()) continue;
                String type;
                switch (c.getInt(2)) {
                    case CallLog.Calls.INCOMING_TYPE: type = "recu"; break;
                    case CallLog.Calls.OUTGOING_TYPE: type = "emis"; break;
                    case CallLog.Calls.MISSED_TYPE: type = "manque"; break;
                    case CallLog.Calls.REJECTED_TYPE: type = "refuse"; break;
                    default: type = "autre";
                }
                l.put(new JSONObject().put("numero", num).put("nom", c.getString(1) == null ? "" : c.getString(1))
                        .put("type", type).put("date", c.getLong(3)).put("duree", c.getLong(4)));
                n++;
            }
        }
        return r.put("ok", true).put("appels", l);
    }

    // ---------- Mini-serveur web ----------

    private void boucleServeur() {
        try {
            serveur = new ServerSocket();
            serveur.setReuseAddress(true);
            serveur.bind(new InetSocketAddress(PORT));
            while (serveur != null && !serveur.isClosed()) {
                final Socket s = serveur.accept();
                pool.execute(() -> traiter(s));
            }
        } catch (Exception ignored) {
            // Serveur arrêté
        }
    }

    private static String lireLigne(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) throw new IOException("ligne trop longue");
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString("ISO-8859-1");
    }

    private void traiter(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(10000);
            InputStream in = sock.getInputStream();
            OutputStream out = sock.getOutputStream();
            String premiere = lireLigne(in);
            if (premiere == null) return;
            String[] parts = premiere.split(" ");
            if (parts.length < 2) return;
            String methode = parts[0];
            String cible = parts[1];

            Map<String, String> entetes = new HashMap<>();
            String l;
            while ((l = lireLigne(in)) != null && !l.isEmpty()) {
                int i = l.indexOf(':');
                if (i > 0) entetes.put(l.substring(0, i).trim().toLowerCase(Locale.ROOT), l.substring(i + 1).trim());
            }
            String corps = "";
            int taille = 0;
            try { taille = Integer.parseInt(entetes.getOrDefault("content-length", "0")); } catch (Exception ignored) {}
            if (taille > 0 && taille < 200_000) {
                byte[] b = new byte[taille];
                int lu = 0;
                while (lu < taille) {
                    int n = in.read(b, lu, taille - lu);
                    if (n < 0) break;
                    lu += n;
                }
                corps = new String(b, 0, lu, StandardCharsets.UTF_8);
            }

            String chemin = cible;
            Map<String, String> params = new HashMap<>();
            int q = cible.indexOf('?');
            if (q >= 0) {
                chemin = cible.substring(0, q);
                for (String kv : cible.substring(q + 1).split("&")) {
                    int e = kv.indexOf('=');
                    if (e > 0) params.put(URLDecoder.decode(kv.substring(0, e), "UTF-8"), URLDecoder.decode(kv.substring(e + 1), "UTF-8"));
                }
            }

            if (chemin.equals("/") || chemin.equals("/index.html")) {
                envoyer(out, 200, "text/html; charset=utf-8", lirePage());
                return;
            }
            if (chemin.equals("/favicon.ico")) { envoyer(out, 404, "text/plain", new byte[0]); return; }
            if (!chemin.startsWith("/api/")) { envoyer(out, 404, "text/plain; charset=utf-8", "Introuvable".getBytes(StandardCharsets.UTF_8)); return; }

            // Toutes les commandes demandent le code affiché sur le téléphone
            String code = entetes.containsKey("x-code") ? entetes.get("x-code") : params.getOrDefault("code", "");
            synchronized (this) {
                long t = System.currentTimeMillis();
                if (t < bloqueJusqua) { json(out, 429, new JSONObject().put("ok", false).put("message", "Trop d'essais : patiente une minute")); return; }
                if (!Reglages.code(this).equals(code)) {
                    if (++echecs >= 8) { bloqueJusqua = t + 60_000; echecs = 0; }
                    json(out, 401, new JSONObject().put("ok", false).put("message", "Code incorrect"));
                    return;
                }
                echecs = 0;
            }

            JSONObject rep;
            JSONObject donnees = corps.isEmpty() || !corps.trim().startsWith("{") ? new JSONObject() : new JSONObject(corps);
            switch (chemin) {
                case "/api/etat": rep = etatJson().put("ok", true); break;
                case "/api/contacts": rep = contacts(); break;
                case "/api/historique": rep = historique(); break;
                case "/api/favoris":
                    if ("POST".equals(methode)) {
                        JSONArray f = new JSONArray(corps.trim().startsWith("[") ? corps : "[]");
                        Reglages.favoris(this, f.toString());
                    }
                    rep = new JSONObject().put("ok", true).put("favoris", new JSONArray(Reglages.favoris(this)));
                    break;
                case "/api/appeler":
                    rep = appeler(donnees.optString("numero", params.getOrDefault("numero", "")), donnees.optBoolean("hautParleur", true));
                    break;
                case "/api/raccrocher": rep = raccrocher(); break;
                case "/api/decrocher": rep = decrocher(); break;
                default:
                    json(out, 404, new JSONObject().put("ok", false).put("message", "Commande inconnue"));
                    return;
            }
            json(out, 200, rep);
        } catch (Exception ignored) {
            // Connexion coupée ou demande abîmée : on ignore
        }
    }

    private byte[] pageEnCache;

    private byte[] lirePage() throws IOException {
        if (pageEnCache != null) return pageEnCache;
        try (InputStream in = getAssets().open("tablette.html")) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] t = new byte[8192];
            int n;
            while ((n = in.read(t)) > 0) b.write(t, 0, n);
            pageEnCache = b.toByteArray();
            return pageEnCache;
        }
    }

    private static void json(OutputStream o, int code, JSONObject j) throws IOException {
        envoyer(o, code, "application/json; charset=utf-8", j.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void envoyer(OutputStream o, int code, String type, byte[] corps) throws IOException {
        String texte;
        switch (code) {
            case 200: texte = "OK"; break;
            case 401: texte = "Unauthorized"; break;
            case 404: texte = "Not Found"; break;
            case 429: texte = "Too Many Requests"; break;
            default: texte = "Error";
        }
        String h = "HTTP/1.1 " + code + " " + texte + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + corps.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        o.write(h.getBytes(StandardCharsets.ISO_8859_1));
        o.write(corps);
        o.flush();
    }
}
