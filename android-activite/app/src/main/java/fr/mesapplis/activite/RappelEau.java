package fr.mesapplis.activite;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;

/**
 * 💧 Rappels d'eau (essai n° 3).
 * Le carnet intégré écrit un résumé (« etat ») à chaque ouverture et chaque minute : total du jour, objectif,
 * heure de la dernière boisson, heures de lever et de coucher (son profil).
 * Toutes les 30 min (alarme Android, même appli fermée), on vérifie :
 *   - entre lever + 30 min et coucher − 1 h (rien la nuit, rien juste avant de dormir),
 *   - objectif pas atteint,
 *   - rien bu depuis « intervalle » (2 h par défaut), et pas déjà rappelé dans cet intervalle.
 * Les boutons de la notification (+ 25 cl, + 50 cl) rangent la boisson en attente : le carnet l'ajoute à sa prochaine ouverture.
 */
public class RappelEau extends BroadcastReceiver {
    static final String PREFS = "eau";
    static final String ACTION_VERIFIER = "fr.mesapplis.activite.EAU_VERIFIER";
    static final String ACTION_BOIRE = "fr.mesapplis.activite.EAU_BOIRE";
    static final String ACTION_PLUS_TARD = "fr.mesapplis.activite.EAU_PLUS_TARD";
    static final String CANAL = "eau";
    static final String CANAL_ALARME = "eau-alarme";
    static final long DUREE_ALARME = 15000L;
    static final int ID_NOTIF = 2001;
    static final long PERIODE = 30 * 60 * 1000L;

    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction() == null ? "" : i.getAction();
        if (ACTION_BOIRE.equals(a)) { boire(c, i.getIntExtra("ml", 250), i.getBooleanExtra("discret", false)); return; }
        if (ACTION_PLUS_TARD.equals(a)) { prefs(c).edit().putLong("dernierRappel", System.currentTimeMillis()).apply(); annuler(c); return; }
        if (ACTION_VERIFIER.equals(a)) { verifier(c, false); rafraichir(c); }   // aussi : le widget change de jour à minuit
        else ServiceEau.lancerSiVoulu(c);   // redémarrage du téléphone / mise à jour : la notification permanente revient
        programmer(c);   // BOOT_COMPLETED, mise à jour de l'appli, ou vérification : la suivante dans 30 min
    }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static JSONObject reglages(Context c) {
        JSONObject r = new JSONObject();
        try { r = new JSONObject(prefs(c).getString("reglages", "{}")); } catch (Exception ignored) {}
        try {
            if (!r.has("on")) r.put("on", true);
            if (!r.has("intervalle")) r.put("intervalle", 120);
            if (!r.has("ml1")) r.put("ml1", 250);
            if (!r.has("ml2")) r.put("ml2", 500);
            if (!r.has("q1")) r.put("q1", 100);
            if (!r.has("q2")) r.put("q2", 150);
            if (!r.has("q3")) r.put("q3", 200);
            if (!r.has("alarme")) r.put("alarme", false);
        } catch (Exception ignored) {}
        return r;
    }

    static void programmer(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            PendingIntent pi = PendingIntent.getBroadcast(c, 70, new Intent(c, RappelEau.class).setAction(ACTION_VERIFIER), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + PERIODE, pi);
        } catch (Exception ignored) {}
    }

    static String aujourdhui(Calendar k) { return String.format(Locale.ROOT, "%04d-%02d-%02d", k.get(Calendar.YEAR), k.get(Calendar.MONTH) + 1, k.get(Calendar.DAY_OF_MONTH)); }

    /** Ce qu'on sait de la journée : résumé du carnet + boissons en attente notées par les boutons. */
    static int[] journee(Context c, String jour) {
        int ml = 0, dernier = -1, goal = 2500, lever = 5 * 60, bed = 20 * 60 + 30;
        try {
            JSONObject e = new JSONObject(prefs(c).getString("etat", "{}"));
            lever = e.optInt("lever", lever); bed = e.optInt("bed", bed);
            if (jour.equals(e.optString("date"))) { ml = e.optInt("ml", 0); dernier = e.optInt("dernier", -1); goal = e.optInt("goal", goal); }
            JSONArray l = new JSONArray(prefs(c).getString("attente", "[]"));
            for (int k = 0; k < l.length(); k++) {
                JSONObject x = l.getJSONObject(k);
                if (!jour.equals(x.optString("date"))) continue;
                ml += x.optInt("ml", 0);
                String[] hm = x.optString("t", "00:00").split(":");
                dernier = Math.max(dernier, Integer.parseInt(hm[0]) * 60 + Integer.parseInt(hm[1]));
            }
        } catch (Exception ignored) {}
        return new int[] { ml, dernier, goal, lever, bed };
    }

    /** @param essai vrai pour « Tester la notification » : on montre tout de suite, sans les conditions. */
    static String verifier(Context c, boolean essai) {
        JSONObject r = reglages(c);
        Calendar k = Calendar.getInstance();
        int maintenant = k.get(Calendar.HOUR_OF_DAY) * 60 + k.get(Calendar.MINUTE);
        int[] j = journee(c, aujourdhui(k));
        int ml = j[0], dernier = j[1], goal = j[2], lever = j[3], bed = j[4], intervalle = r.optInt("intervalle", 120);
        if (!essai) {
            if (!r.optBoolean("on", true)) return "rappels coupés";
            if (maintenant < lever + 30 || maintenant >= bed - 60) return "en dehors de la journée";
            if (ml >= goal) return "objectif atteint";
            int depuis = maintenant - Math.max(dernier, lever);
            if (depuis < intervalle) return "bu il y a " + depuis + " min";
            long dr = prefs(c).getLong("dernierRappel", 0);
            if (System.currentTimeMillis() - dr < intervalle * 60000L) return "déjà rappelé";
        }
        int depuis = dernier >= 0 ? maintenant - dernier : -1;
        String titre = "💧 " + (depuis >= 60 ? "Rien bu depuis " + (depuis / 60) + " h" + (depuis % 60 >= 10 ? String.format(Locale.ROOT, "%02d", depuis % 60) : "") : depuis >= 0 ? "Pense à boire" : "Pas encore d'eau notée aujourd'hui");
        String texte = String.format(Locale.FRANCE, "%.2f L sur %.1f L (%d %%). Une touche pour noter ce que tu bois.", ml / 1000.0, goal / 1000.0, Math.round(100f * ml / Math.max(1, goal))).replace('.', ',');
        montrer(c, titre, texte, r.optInt("ml1", 250), r.optInt("ml2", 500), r.optBoolean("alarme", false) ? 1 : 0);
        prefs(c).edit().putLong("dernierRappel", System.currentTimeMillis()).apply();
        return "notification envoyée";
    }

    /** @param mode 0 = bip normal, 1 = ⏰ alarme (son de réveil qui se répète ~15 s), 2 = la même sans son (fin de l'alarme). */
    static void montrer(Context c, String titre, String texte, int ml1, int ml2, int mode) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || !nm.areNotificationsEnabled()) return;
        NotificationChannel canal = new NotificationChannel(CANAL, "Rappels d'eau", NotificationManager.IMPORTANCE_DEFAULT);
        canal.setDescription("Rappelle de boire dans la journée (jamais la nuit ni juste avant le coucher).");
        nm.createNotificationChannel(canal);
        String id = CANAL;
        if (mode == 1) {
            NotificationChannel a = new NotificationChannel(CANAL_ALARME, "Rappels d'eau (alarme)", NotificationManager.IMPORTANCE_HIGH);
            a.setDescription("Rappel d'eau qui sonne comme un réveil, même en mode silencieux.");
            android.net.Uri son = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM);
            if (son == null) son = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION);
            a.setSound(son, new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            a.enableVibration(true);
            a.setVibrationPattern(new long[] { 0, 800, 400, 800, 400, 800 });
            a.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            a.setBypassDnd(true);
            nm.createNotificationChannel(a);
            id = CANAL_ALARME;
        }
        PendingIntent ouvrir = PendingIntent.getActivity(c, 71, new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, id)
                .setSmallIcon(R.drawable.ic_seance)
                .setContentTitle(titre)
                .setContentText(texte)
                .setStyle(new Notification.BigTextStyle().bigText(texte))
                .setContentIntent(ouvrir)
                .setAutoCancel(true)
                .setCategory(mode == 1 ? Notification.CATEGORY_ALARM : Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setSilent(mode == 2)
                .addAction(new Notification.Action.Builder((android.graphics.drawable.Icon) null, "+ " + cl(ml1), boisson(c, ml1, 72)).build())
                .addAction(new Notification.Action.Builder((android.graphics.drawable.Icon) null, "+ " + cl(ml2), boisson(c, ml2, 73)).build())
                .addAction(new Notification.Action.Builder((android.graphics.drawable.Icon) null, "Plus tard", PendingIntent.getBroadcast(c, 74, new Intent(c, RappelEau.class).setAction(ACTION_PLUS_TARD), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).build())
                .build();
        if (mode == 1) n.flags |= Notification.FLAG_INSISTENT;   // le son se répète tant qu'on n'a pas répondu
        nm.notify(ID_NOTIF, n);
        if (mode == 1) {
            // Au bout de ~15 s : on remet la même notification, sans son (elle reste affichée avec ses boutons).
            final long quand = System.currentTimeMillis();
            prefs(c).edit().putLong("alarmeDepuis", quand).apply();
            final Context app = c.getApplicationContext();
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (prefs(app).getLong("alarmeDepuis", 0) != quand) return;
                for (android.service.notification.StatusBarNotification sb : nm.getActiveNotifications())
                    if (sb.getId() == ID_NOTIF) { montrer(app, titre, texte, ml1, ml2, 2); break; }
            }, DUREE_ALARME);
        }
    }

    static String cl(int ml) { return ml % 10 == 0 ? (ml / 10) + " cl" : ml + " ml"; }

    static PendingIntent boisson(Context c, int ml, int code) {
        return PendingIntent.getBroadcast(c, code, new Intent(c, RappelEau.class).setAction(ACTION_BOIRE).putExtra("ml", ml), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Bouton de la notification : la boisson attend dans « attente » ; le carnet la range à sa prochaine ouverture. */
    static void boire(Context c, int ml) { boire(c, ml, false); }

    /** @param discret vrai depuis le widget ou la notification permanente : pas de notification « ✅ notés » (un petit message suffit). */
    static void boire(Context c, int ml, boolean discret) {
        try {
            Calendar k = Calendar.getInstance();
            JSONArray l = new JSONArray(prefs(c).getString("attente", "[]"));
            l.put(new JSONObject().put("date", aujourdhui(k)).put("t", String.format(Locale.ROOT, "%02d:%02d", k.get(Calendar.HOUR_OF_DAY), k.get(Calendar.MINUTE))).put("ml", ml));
            prefs(c).edit().putString("attente", l.toString()).putLong("dernierRappel", System.currentTimeMillis()).apply();
        } catch (Exception ignored) {}
        annuler(c);
        rafraichir(c);
        if (discret) { try { android.widget.Toast.makeText(c, "💧 " + cl(ml) + " notés", android.widget.Toast.LENGTH_SHORT).show(); } catch (Exception ignored) {} return; }
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(ID_NOTIF + 1, new Notification.Builder(c, CANAL).setSmallIcon(R.drawable.ic_seance).setContentTitle("✅ " + cl(ml) + " d'eau notés").setContentText("Rangé dans ton carnet à sa prochaine ouverture.").setAutoCancel(true).setTimeoutAfter(8000).build());
        } catch (Exception ignored) {}
    }

    /** Texte du jour pour le widget et la notification permanente : « 1,05 L / 2,5 L · 42 % ». */
    static String resume(Context c) {
        int[] j = journee(c, aujourdhui(Calendar.getInstance()));
        return String.format(Locale.FRANCE, "%.2f L / %.1f L · %d %%", j[0] / 1000.0, j[2] / 1000.0, Math.round(100f * j[0] / Math.max(1, j[2])));
    }

    static void rafraichir(Context c) {
        try { WidgetEau.majTous(c); } catch (Exception ignored) {}
        try { ServiceEau.maj(c); } catch (Exception ignored) {}
    }

    static PendingIntent boissonDiscrete(Context c, int ml, int code) {
        return PendingIntent.getBroadcast(c, code, new Intent(c, RappelEau.class).setAction(ACTION_BOIRE).putExtra("ml", ml).putExtra("discret", true), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void annuler(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(ID_NOTIF);
    }
}
