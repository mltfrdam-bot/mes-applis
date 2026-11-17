package fr.mesapplis.activite;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

import org.json.JSONObject;

/**
 * 💧 Notification permanente de l'eau (essai n° 4, au choix dans ⚙️) : total du jour + boutons + 10 / + 15 / + 20 cl ;
 * la toucher ouvre « Combien as-tu bu ? ». C'est un service de premier plan très léger (aucun capteur) :
 * Android garde ainsi l'appli active, elle se rouvre sans recharger.
 */
public class ServiceEau extends Service {
    static final String CANAL = "eau-permanente-2"; // nouveau canal : visible sur l'écran de verrouillage
    static final int ID = 2101;

    static boolean voulu(Context c) { return RappelEau.prefs(c).getBoolean("permanente", false); }

    static void choisir(Context c, boolean oui) {
        RappelEau.prefs(c).edit().putBoolean("permanente", oui).apply();
        if (oui) lancerSiVoulu(c); else c.stopService(new Intent(c, ServiceEau.class));
    }

    static void lancerSiVoulu(Context c) {
        if (!voulu(c)) return;
        try { c.startForegroundService(new Intent(c, ServiceEau.class)); } catch (Exception ignored) {}
    }

    static void maj(Context c) {
        if (!voulu(c)) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(ID, fabriquer(c));
    }

    static Notification fabriquer(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            try { nm.deleteNotificationChannel("eau-permanente"); } catch (Exception e) { }
            NotificationChannel canal = new NotificationChannel(CANAL, "Eau du jour (permanente)", NotificationManager.IMPORTANCE_DEFAULT);
            canal.setSound(null, null);
            canal.enableVibration(false);
            canal.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            canal.setDescription("Total d'eau du jour et boutons pour noter ce que tu bois.");
            canal.setShowBadge(false);
            nm.createNotificationChannel(canal);
        }
        JSONObject r = RappelEau.reglages(c);
        int q1 = r.optInt("q1", 100), q2 = r.optInt("q2", 150), q3 = r.optInt("q3", 200);
        PendingIntent autre = PendingIntent.getActivity(c, 90, new Intent(c, SaisieEau.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        android.widget.RemoteViews v = new android.widget.RemoteViews(c.getPackageName(), R.layout.notif_eau);
        v.setTextViewText(R.id.neTotal, "💧 " + RappelEau.resume(c).replace(" · ", "\n"));
        v.setTextViewText(R.id.neB1, "+" + q1 / 10);
        v.setTextViewText(R.id.neB2, "+" + q2 / 10);
        v.setTextViewText(R.id.neB3, "+" + q3 / 10);
        v.setOnClickPendingIntent(R.id.neB1, RappelEau.boissonDiscrete(c, q1, 91));
        v.setOnClickPendingIntent(R.id.neB2, RappelEau.boissonDiscrete(c, q2, 92));
        v.setOnClickPendingIntent(R.id.neB3, RappelEau.boissonDiscrete(c, q3, 93));
        v.setOnClickPendingIntent(R.id.neAutre, autre);
        v.setOnClickPendingIntent(R.id.neTotal, PendingIntent.getActivity(c, 94, new Intent(c, MainActivity.class).putExtra("page", "carnet").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        return new Notification.Builder(c, CANAL)
                .setCustomContentView(v)
                .setCustomBigContentView(v)
                .setStyle(new Notification.DecoratedCustomViewStyle())
                .setSmallIcon(R.drawable.ic_seance)
                .setContentTitle("💧 " + RappelEau.resume(c))
                .setContentText("Touche ici pour une autre quantité")
                .setContentIntent(autre)
                .setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .build();
    }

    @Override
    public int onStartCommand(Intent i, int flags, int id) {
        if (!voulu(this)) { stopSelf(); return START_NOT_STICKY; }
        try { startForeground(ID, fabriquer(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE); } catch (Exception e) { stopSelf(); return START_NOT_STICKY; }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent i) { return null; }
}
