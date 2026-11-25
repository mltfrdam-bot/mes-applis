package fr.mesapplis.activite;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * 🎮 Télécommande (essai 22) : lancée par le widget, elle garde UNE manette connectée hors séance.
 * Bouton 🔵 Y = 🎙️ note vocale « à regarder plus tard » : un écran s'ouvre (même téléphone verrouillé), écoute, écrit la note.
 * S'arrête toute seule après 3 h, ou quand une séance démarre (la séance reprend la manette).
 */
public class ServiceTelecommande extends Service implements LienManettes.Ecoute {
    static final String CANAL = "telecommande", CANAL_NOTE = "telecommande-note";
    static final int ID = 2201, ID_NOTE = 2202;
    static final String ACTION_ARRET = "fr.mesapplis.activite.TELECOMMANDE_ARRET";
    static volatile boolean active = false;

    private LienManettes lien;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable finAuto = this::stopSelf;
    private ToneGenerator bip;

    static void basculer(Context c) {
        if (active) c.stopService(new Intent(c, ServiceTelecommande.class));
        else try { c.startForegroundService(new Intent(c, ServiceTelecommande.class)); } catch (Exception ignored) {}
    }

    static void arreter(Context c) { if (active) c.stopService(new Intent(c, ServiceTelecommande.class)); }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        if (i != null && ACTION_ARRET.equals(i.getAction())) { stopSelf(); return START_NOT_STICKY; }
        try { startForeground(ID, notif("Réveille la manette. 🔵 Y note Claude · 🟢 A note 4B BTP · 🟣 B Gemini"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE); }
        catch (Exception e) { stopSelf(); return START_NOT_STICKY; }
        active = true;
        Bug.note(this, "Télécommande", "allumée");
        WidgetTelecommande.majTous(this);
        if (lien == null) { lien = new LienManettes(this, this); lien.demarrer(); }
        h.removeCallbacks(finAuto);
        h.postDelayed(finAuto, 3 * 3600 * 1000L);
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        active = false;
        Bug.note(this, "Télécommande", "arrêtée");
        h.removeCallbacksAndMessages(null);
        try { if (lien != null) lien.arreter(); } catch (Exception ignored) {}
        try { if (bip != null) bip.release(); } catch (Exception ignored) {}
        WidgetTelecommande.majTous(this);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void etatManettesChange() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && lien != null) nm.notify(ID, notif(lien.pretes() > 0 ? "✅ Prête · 🔵 Y note Claude · 🟢 A note 4B BTP · 🟣 B Gemini" : "🔎 Réveille la manette d'un appui…"));
    }

    @Override public void reconnectee() { vibrer(new long[]{0, 60, 120, 60}); }

    @Override public void bouton(String nom) {
        Bug.note(this, "Télécommande", "bouton " + nom);
        NoteVocale n = NoteVocale.actif;
        if (n != null) {   // une note est en cours : on la termine avec la MÊME lettre que celle qui l'a commencée
            if (nom.equals(n.lettre)) { vibrer(new long[]{0, 60, 100, 60}); n.runOnUiThread(n::terminer); }
            return;
        }
        if ("B".equals(nom)) { vibrer(new long[]{0, 80}); ouvrirGemini(); return; }
        if (!"Y".equals(nom) && !"A".equals(nom)) return;
        h.removeCallbacks(finAuto);
        h.postDelayed(finAuto, 3 * 3600 * 1000L);
        vibrer(new long[]{0, 80});
        try { if (bip == null) bip = new ToneGenerator(AudioManager.STREAM_MUSIC, 90); bip.startTone(ToneGenerator.TONE_PROP_BEEP, 120); } catch (Exception ignored) {}
        ouvrirNote("A".equals(nom) ? "btp" : "claude", nom);
    }

    /** 🟣 B : l'assistant d'Android (Gemini) en écoute. */
    private void ouvrirGemini() {
        Intent[] essais = { new Intent(Intent.ACTION_VOICE_COMMAND), new Intent("android.intent.action.ASSIST"), new Intent(android.speech.RecognizerIntent.ACTION_VOICE_SEARCH_HANDS_FREE) };
        for (Intent i : essais) {
            try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); Bug.note(this, "Télécommande", "Gemini ouvert (" + i.getAction() + ")"); return; }
            catch (Exception e) { Bug.note(this, "Télécommande", "Gemini : " + i.getAction() + " refusé : " + e.getClass().getSimpleName()); }
        }
    }

    /** L'écran de dictée s'ouvre par une notification « plein écran » (seule façon autorisée par Android depuis l'arrière-plan). */
    private void ouvrirNote(String cat, String lettre) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel c = new NotificationChannel(CANAL_NOTE, "Télécommande : note vocale", NotificationManager.IMPORTANCE_HIGH);
        c.setSound(null, null); c.enableVibration(false); c.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(c);
        Intent ecran = new Intent(this, NoteVocale.class).putExtra("cat", cat).putExtra("lettre", lettre).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 2203, ecran, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CANAL_NOTE).setSmallIcon(R.drawable.ic_seance).setContentTitle("🎙️ Note vocale").setContentText("Touche pour dicter ta note")
                .setCategory(Notification.CATEGORY_CALL).setVisibility(Notification.VISIBILITY_PUBLIC).setFullScreenIntent(pi, true).setContentIntent(pi).setAutoCancel(true).setTimeoutAfter(60000).build();
        nm.notify(ID_NOTE, n);
        try { startActivity(ecran); Bug.note(this, "Télécommande", "écran de note : ouverture demandée (par-dessus les applis " + (android.provider.Settings.canDrawOverlays(this) ? "autorisé" : "NON autorisé") + ", notification plein écran " + (android.os.Build.VERSION.SDK_INT >= 34 ? (nm.canUseFullScreenIntent() ? "autorisée" : "NON autorisée") : "ok") + ")"); } catch (Exception e) { Bug.note(this, "Télécommande", "ouverture directe refusée : " + e); }   // écran allumé et appli visible : ouverture directe
    }

    private void vibrer(long[] m) { try { Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE); if (v != null) v.vibrate(VibrationEffect.createWaveform(m, -1)); } catch (Exception ignored) {} }

    private Notification notif(String texte) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) { NotificationChannel c = new NotificationChannel(CANAL, "Télécommande (manette)", NotificationManager.IMPORTANCE_LOW); nm.createNotificationChannel(c); }
        PendingIntent arret = PendingIntent.getService(this, 2204, new Intent(this, ServiceTelecommande.class).setAction(ACTION_ARRET), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CANAL).setSmallIcon(R.drawable.ic_seance).setContentTitle("🎮 Télécommande active").setContentText(texte).setOngoing(true)
                .addAction(new Notification.Action.Builder((android.graphics.drawable.Icon) null, "⏹ Arrêter", arret).build()).build();
    }
}
