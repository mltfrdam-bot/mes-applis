package fr.mesapplis.activite;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/** 🎮 Widget « Télécommande » : un bouton qui allume / arrête la télécommande (manette Zwift hors séance). */
public class WidgetTelecommande extends AppWidgetProvider {
    static final String ACTION = "fr.mesapplis.activite.TELECOMMANDE_BASCULER";

    @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) { majTous(c); }

    @Override public void onReceive(Context c, Intent i) {
        if (ACTION.equals(i.getAction())) { ServiceTelecommande.basculer(c); return; }
        super.onReceive(c, i);
    }

    static void majTous(Context c) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            int[] ids = m.getAppWidgetIds(new ComponentName(c, WidgetTelecommande.class));
            if (ids == null || ids.length == 0) return;
            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_telecommande);
            v.setTextViewText(R.id.wtBouton, ServiceTelecommande.active ? "🎮 Télécommande : ON\n🔵 Y = 🎙️ note · toucher pour arrêter" : "🎮 Télécommande : OFF\ntoucher pour allumer");
            v.setOnClickPendingIntent(R.id.wtBouton, PendingIntent.getBroadcast(c, 2205, new Intent(c, WidgetTelecommande.class).setAction(ACTION), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            m.updateAppWidget(ids, v);
        } catch (Exception ignored) {}
    }
}
