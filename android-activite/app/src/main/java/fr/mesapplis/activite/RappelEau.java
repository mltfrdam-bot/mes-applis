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
 *   - en retard sur le rythme du carnet (≥ 50 ml), on sonne tout de suite ; si on reste en retard, à nouveau après « intervalle ».
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
    static final long PERIODE = 10 * 60 * 1000L;   // vérification au moins toutes les 10 min

    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction() == null ? "" : i.getAction();
        if (ACTION_BOIRE.equals(a)) { boire(c, i.getIntExtra("ml", 250), i.getBooleanExtra("discret", false)); return; }
        if (ACTION_PLUS_TARD.equals(a)) { plusTard(c); return; }
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
            if (!r.has("intervalle") || r.optInt("intervalle") > 60) r.put("intervalle", 10);
            if (!r.has("ml1")) r.put("ml1", 250);
            if (!r.has("ml2")) r.put("ml2", 500);
            if (!r.has("q1")) r.put("q1", 100);
            if (!r.has("q2")) r.put("q2", 150);
            if (!r.has("q3")) r.put("q3", 200);
            if (!r.has("alarme")) r.put("alarme", false);
            if (!r.has("sport")) r.put("sport", 700);
            if (!r.has("seanceRappel")) r.put("seanceRappel", 2);
        } catch (Exception ignored) {}
        return r;
    }

    static void programmer(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            long dans = Math.max(60000L, Math.min(recupActive(c) ? 5 * 60000L : PERIODE, minutesAvantRetard(c) * 60000L));
            PendingIntent pi = PendingIntent.getBroadcast(c, 70, new Intent(c, RappelEau.class).setAction(ACTION_VERIFIER), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + dans, pi);
        } catch (Exception ignored) {}
    }

    /** Même courbe que le carnet : 0 % au lever, 40 % à midi, 70 % à 16 h, 90 % à 19 h, 100 % une heure avant le coucher. */
    static double fracA(int m, int lever, int bed) {
        int[][] p = { { lever, 0 }, { 12 * 60, 40 }, { 16 * 60, 70 }, { 19 * 60, 90 }, { Math.max(bed - 60, 19 * 60 + 1), 100 } };
        if (m <= p[0][0]) return 0;
        for (int i = 1; i < p.length; i++) {
            if (p[i][0] <= p[i - 1][0]) continue;
            if (m <= p[i][0]) return (p[i - 1][1] + (p[i][1] - p[i - 1][1]) * (double) (m - p[i - 1][0]) / (p[i][0] - p[i - 1][0])) / 100.0;
        }
        return 1;
    }

    /** Retard (ml) à la minute m : ce qui devrait être bu à cette heure − ce qui est bu. */
    static int retardA(int m, int ml, int goal, int lever, int bed) { return (int) Math.round(goal * fracA(m, lever, bed)) - ml; }

    static final int SEUIL_RETARD = 50;   // en retard dès 5 cl de moins que prévu (comme le carnet, arrondi à 50 ml)

    /** Dans combien de minutes on passera sous la barre (si on ne boit rien). Grand nombre si jamais aujourd'hui. */
    static int minutesAvantRetard(Context c) {
        Calendar k = Calendar.getInstance();
        int maintenant = k.get(Calendar.HOUR_OF_DAY) * 60 + k.get(Calendar.MINUTE);
        int[] j = journee(c, aujourdhui(k));
        for (int m = maintenant + 1; m < j[4] - 60; m++)
            if (m >= j[3] + 30 && retardA(m, j[0], j[2], j[3], j[4]) >= SEUIL_RETARD) return m - maintenant;
        return 100000;
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
                if (!jour.equals(x.optString("date")) || x.optInt("ml", 0) <= 0) continue;
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
        int retard = retardA(maintenant, ml, goal, lever, bed);
        if (!essai) {
            if (!r.optBoolean("on", true)) return "rappels coupés";
            if (ServiceSeance.seanceEnCours()) return "séance en cours (rappel de séance à la place)";
            if (maintenant < lever + 30 || maintenant >= bed - 60) return "en dehors de la journée";
            String rc = verifierRecup(c, r);
            if (rc != null) return rc;
            if (ml >= goal) return "objectif atteint";
            if (retard < SEUIL_RETARD) { prefs(c).edit().putBoolean("enRetard", false).apply(); return retard <= 0 ? "en avance de " + cl(-retard) : "presque à l'heure (" + retard + " ml)"; }
            boolean deja = prefs(c).getBoolean("enRetard", false);
            long dr = prefs(c).getLong("dernierRappel", 0);
            // On sonne dès qu'on passe en retard ; si on reste en retard, on rappelle toutes les « intervalle ».
            if (deja && System.currentTimeMillis() - dr < intervalle * 60000L) return "en retard, déjà rappelé";
        }
        prefs(c).edit().putBoolean("enRetard", true).apply();
        String titre = retard >= SEUIL_RETARD ? "💧 En retard de " + cl(Math.round(retard / 50f) * 50) + " d'eau" : "💧 Eau : tu es à l'heure";
        String texte = String.format(Locale.FRANCE, "Bu %.2f L, prévu à cette heure %.2f L (objectif %.1f L). Une touche pour noter ce que tu bois.", ml / 1000.0, (ml + Math.max(0, retard)) / 1000.0, goal / 1000.0);
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
        PendingIntent ouvrir;
        ouvrir = PendingIntent.getActivity(c, 71, new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder nb = new Notification.Builder(c, id);
        if (mode == 1) {
            // Écran d'alarme par-dessus le verrouillage : une touche = « j'ai bu » + l'alarme s'arrête.
            Intent ecran = new Intent(c, AlarmeEau.class).putExtra("titre", titre).putExtra("texte", texte).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            PendingIntent pe = PendingIntent.getActivity(c, 75, ecran, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            nb.setFullScreenIntent(pe, true);
            ouvrir = pe;
        }
        Notification n = nb
                .setSmallIcon(R.drawable.ic_seance)
                .setContentTitle(titre)
                .setContentText(texte)
                .setStyle(new Notification.BigTextStyle().bigText(texte))
                .setContentIntent(ouvrir)
                .setAutoCancel(true)
                .setCategory(mode == 1 ? Notification.CATEGORY_ALARM : Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(mode == 2)   // fin de l alarme : on remplace sans resonner
                .addAction(new Notification.Action.Builder((android.graphics.drawable.Icon) null, "+ " + cl(ml1), boisson(c, ml1, 72)).build())
                .addAction(new Notification.Action.Builder((android.graphics.drawable.Icon) null, "+ " + cl(ml2), boisson(c, ml2, 73)).build())
                .addAction(new Notification.Action.Builder((android.graphics.drawable.Icon) null, "Plus tard", PendingIntent.getBroadcast(c, 74, new Intent(c, RappelEau.class).setAction(ACTION_PLUS_TARD), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).build())
                .build();
        if (mode == 1) n.flags |= Notification.FLAG_INSISTENT;   // le son se répète tant que la notification est là   // le son se répète tant qu'on n'a pas répondu
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
            l.put(new JSONObject().put("date", aujourdhui(k)).put("t", String.format(Locale.ROOT, "%02d:%02d", k.get(Calendar.HOUR_OF_DAY), k.get(Calendar.MINUTE))).put("ml", ml).put("ts", k.getTimeInMillis()));
            journal(c, k.getTimeInMillis(), ml);
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

    // ---------- 💧 Journal des boissons (avec l'heure exacte) : sert pendant et après la séance ----------
    static void journal(Context c, long ts, int ml) {
        if (ml <= 0) return;
        try {
            JSONArray l = new JSONArray(prefs(c).getString("journal", "[]")), garde = new JSONArray();
            long limite = System.currentTimeMillis() - 2 * 86400000L;
            for (int k = 0; k < l.length(); k++) if (l.getJSONObject(k).optLong("ts") > limite) garde.put(l.getJSONObject(k));
            garde.put(new JSONObject().put("ts", ts).put("ml", ml));
            prefs(c).edit().putString("journal", garde.toString()).apply();
        } catch (Exception ignored) {}
    }

    static int buEntre(Context c, long a, long b) {
        int ml = 0;
        try { JSONArray l = new JSONArray(prefs(c).getString("journal", "[]")); for (int k = 0; k < l.length(); k++) { JSONObject x = l.getJSONObject(k); long t = x.optLong("ts"); if (t >= a && t <= b) ml += x.optInt("ml"); } } catch (Exception ignored) {}
        return ml;
    }

    /** Boisson ou aliment noté pendant la séance (🍇 raisins, 🥜 cacahuètes, 🍁 boisson érable-sel) : rangé dans le carnet à sa prochaine ouverture. */
    static boolean noter(Context c, String json) {
        try {
            JSONObject o = new JSONObject(json);
            long ts = o.optLong("ts", System.currentTimeMillis());
            Calendar k = Calendar.getInstance(); k.setTimeInMillis(ts);
            JSONObject x = new JSONObject(json).put("date", aujourdhui(k)).put("t", String.format(Locale.ROOT, "%02d:%02d", k.get(Calendar.HOUR_OF_DAY), k.get(Calendar.MINUTE))).put("ts", ts);
            int ml = o.optInt("ml", 0);
            if (ml > 0) x.put("ml", ml);
            if (o.has("g")) x.put("g", o.optDouble("g"));
            if (o.has("n")) x.put("n", o.optInt("n"));
            x.put("type", o.optString("type", "eau")).put("note", o.optString("note", ""));
            if (o.optString("heure", "").matches("\\d{2}:\\d{2}")) x.put("t", o.getString("heure"));
            if (o.optString("jour", "").matches("\\d{4}-\\d{2}-\\d{2}")) x.put("date", o.getString("jour"));   // mesure d'un autre jour
            JSONArray l = new JSONArray(prefs(c).getString("attente", "[]"));
            l.put(x);
            SharedPreferences.Editor ed = prefs(c).edit().putString("attente", l.toString());
            if (ml > 0) ed.putLong("dernierRappel", System.currentTimeMillis());
            ed.apply();
            journal(c, ts, ml);
            if (ml > 0) annuler(c);
            rafraichir(c);
            return true;
        } catch (Exception e) { return false; }
    }

    /** ↩ Annuler pendant la séance : retire la boisson si le carnet ne l'a pas encore rangée. */
    static boolean annulerNote(Context c, long ts) {
        boolean trouve = false;
        try {
            JSONArray l = new JSONArray(prefs(c).getString("attente", "[]")), g = new JSONArray();
            for (int k = 0; k < l.length(); k++) { if (!trouve && l.getJSONObject(k).optLong("ts") == ts) { trouve = true; continue; } g.put(l.getJSONObject(k)); }
            JSONArray j = new JSONArray(prefs(c).getString("journal", "[]")), gj = new JSONArray();
            for (int k = 0; k < j.length(); k++) if (j.getJSONObject(k).optLong("ts") != ts) gj.put(j.getJSONObject(k));
            prefs(c).edit().putString("attente", g.toString()).putString("journal", gj.toString()).apply();
            rafraichir(c);
        } catch (Exception ignored) {}
        return trouve;
    }

    /** Une fois par minute pendant la séance : phrase à dire si on est en retard sur l'objectif (ml par heure), sinon null. */
    static String seanceSuivi(Context c, long debut) {
        JSONObject r = reglages(c);
        if (r.optInt("seanceRappel", 2) == 0) return null;
        long now = System.currentTimeMillis(), el = now - debut;
        if (el < 15 * 60000L) return null;
        int cible = (int) (r.optInt("sport", 700) * el / 3600000L), bu = buEntre(c, debut - 60000L, now), manque = cible - bu;
        long der = prefs(c).getLong("seanceRappelT", 0);
        if (manque < 100 || now - der < 15 * 60000L) return null;
        prefs(c).edit().putLong("seanceRappelT", now).apply();
        int cl = Math.max(10, Math.min(30, Math.round(manque / 50f) * 5));
        return "Pense à boire : " + cl + " centilitres.";
    }

    static int modeRappelSeance(Context c) { return reglages(c).optInt("seanceRappel", 2); }

    /** Fin de séance : (prévu − bu) × 1,5 à boire dans les 1 h 30 (jamais dans l'heure avant le coucher). */
    static String recupDemarrer(Context c, long debut, long fin) {
        try {
            JSONObject r = reglages(c);
            int bu = buEntre(c, debut - 60000L, fin + 60000L);
            double manque = r.optInt("sport", 700) * (fin - debut) / 3600000.0 - bu;
            int cible = manque > 0 ? (int) (Math.round(manque * 1.5 / 50) * 50) : 0;
            Calendar k = Calendar.getInstance(); k.setTimeInMillis(fin);
            int[] j = journee(c, aujourdhui(k));
            Calendar cb = Calendar.getInstance(); cb.setTimeInMillis(fin); cb.set(Calendar.HOUR_OF_DAY, 0); cb.set(Calendar.MINUTE, 0); cb.set(Calendar.SECOND, 0);
            long limite = cb.getTimeInMillis() + (j[4] - 60) * 60000L, t1 = Math.min(fin + 90 * 60000L, limite);
            prefs(c).edit().putLong("seanceRappelT", 0).apply();
            if (cible < 100 || t1 - fin < 20 * 60000L) { prefs(c).edit().remove("recup").apply(); return new JSONObject().put("cible", t1 - fin < 20 * 60000L ? 0 : cible).put("fin", t1).put("bu", bu).toString(); }
            prefs(c).edit().putString("recup", new JSONObject().put("t0", fin).put("t1", t1).put("cible", cible).toString()).putBoolean("enRetardRecup", false).apply();
            programmer(c);
            return new JSONObject().put("cible", cible).put("fin", t1).put("bu", bu).toString();
        } catch (Exception e) { return "null"; }
    }

    static boolean recupActive(Context c) {
        try { JSONObject p = new JSONObject(prefs(c).getString("recup", "{}")); return System.currentTimeMillis() < p.optLong("t1", 0); } catch (Exception e) { return false; }
    }

    /** Pendant la récupération : rappel dès qu'on prend du retard sur le rattrapage (toutes les 15 min au plus). null = pas de récupération en cours. */
    static String verifierRecup(Context c, JSONObject r) {
        try {
            JSONObject p = new JSONObject(prefs(c).getString("recup", "{}"));
            long t0 = p.optLong("t0", 0), t1 = p.optLong("t1", 0), now = System.currentTimeMillis();
            if (now >= t1 || t0 == 0) { if (t0 != 0) prefs(c).edit().remove("recup").apply(); return null; }
            int cible = p.optInt("cible"), bu = buEntre(c, t0, now), reste = cible - bu;
            if (reste <= 0) { prefs(c).edit().remove("recup").apply(); return null; }
            int prevu = (int) (cible * Math.min(1.0, (now - t0) / (double) (t1 - t0))), retard = prevu - bu;
            if (retard < 50) return "récupération : à l'heure (encore " + cl(reste) + ")";
            long dr = prefs(c).getLong("dernierRappel", 0);
            if (prefs(c).getBoolean("enRetardRecup", false) && now - dr < Math.min(15, r.optInt("intervalle", 10)) * 60000L) return "récupération : déjà rappelé";
            prefs(c).edit().putBoolean("enRetardRecup", true).putLong("dernierRappel", now).apply();
            Calendar f = Calendar.getInstance(); f.setTimeInMillis(t1);
            String titre = "💧 Récupération : encore " + cl(Math.round(reste / 50f) * 50) + " d'ici " + String.format(Locale.ROOT, "%02d:%02d", f.get(Calendar.HOUR_OF_DAY), f.get(Calendar.MINUTE));
            String texte = String.format(Locale.FRANCE, "Après ta séance : %.2f L à boire, %.2f L déjà bus. Une touche pour noter ce que tu bois.", cible / 1000.0, bu / 1000.0);
            montrer(c, titre, texte, r.optInt("ml1", 250), r.optInt("ml2", 500), r.optBoolean("alarme", false) ? 1 : 0);
            return "rappel de récupération envoyé";
        } catch (Exception e) { return null; }
    }

    static void plusTard(Context c) { prefs(c).edit().putLong("dernierRappel", System.currentTimeMillis()).putBoolean("enRetard", true).apply(); annuler(c); }

    static void annuler(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(ID_NOTIF);
    }
}
