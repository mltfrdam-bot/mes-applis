package fr.mesapplis.relais;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Relance le relais tout seul après un redémarrage du téléphone ou une mise à jour de l'appli. */
public class Demarrage extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (!Reglages.auto(c)) return;
        try { RelaisService.demarrer(c); } catch (Exception ignored) {}
    }
}
