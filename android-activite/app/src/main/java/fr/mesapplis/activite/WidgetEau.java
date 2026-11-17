package fr.mesapplis.activite;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import org.json.JSONObject;

/** 💧 Widget de l'écran d'accueil (essai n° 4) : total du jour et boutons + 10 / + 15 / + 20 cl / + (quantité au choix). */
public class WidgetEau extends AppWidgetProvider {
    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) { for (int id : ids) m.updateAppWidget(id, vue(c)); }

    static RemoteViews vue(Context c) {
        JSONObject r = RappelEau.reglages(c);
        int q1 = r.optInt("q1", 100), q2 = r.optInt("q2", 150), q3 = r.optInt("q3", 200);
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_eau);
        v.setTextViewText(R.id.weTotal, "💧 " + RappelEau.resume(c).replace(" · ", "\n"));
        v.setTextViewText(R.id.weB1, "+" + RappelEau.cl(q1).replace(" cl", ""));
        v.setTextViewText(R.id.weB2, "+" + RappelEau.cl(q2).replace(" cl", ""));
        v.setTextViewText(R.id.weB3, "+" + RappelEau.cl(q3).replace(" cl", ""));
        v.setOnClickPendingIntent(R.id.weB1, RappelEau.boissonDiscrete(c, q1, 81));
        v.setOnClickPendingIntent(R.id.weB2, RappelEau.boissonDiscrete(c, q2, 82));
        v.setOnClickPendingIntent(R.id.weB3, RappelEau.boissonDiscrete(c, q3, 83));
        v.setOnClickPendingIntent(R.id.weAutre, PendingIntent.getActivity(c, 84, new Intent(c, SaisieEau.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        v.setOnClickPendingIntent(R.id.weTotal, PendingIntent.getActivity(c, 85, new Intent(c, MainActivity.class).putExtra("page", "carnet").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        return v;
    }

    static void majTous(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, WidgetEau.class));
        if (ids.length > 0) m.updateAppWidget(ids, vue(c));
    }
}
