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
    private String raison = "arrêt par Android (batterie ?)";
    private final Runnable finAuto = () -> { raison = "fin du délai sans appui"; stopSelf(); };

    /** Durée sans appui avant l'arrêt automatique : 3 h, 8 h, ou 0 = jusqu'à ce que je l'arrête (réglage dans 📝). */
    private long delai() { int hh = getSharedPreferences("reglages", MODE_PRIVATE).getInt("teleHeures", 8); return hh <= 0 ? 0 : hh * 3600 * 1000L; }
    private void relancerDelai() { h.removeCallbacks(finAuto); long d = delai(); if (d > 0) h.postDelayed(finAuto, d); }
    private ToneGenerator bip;

    static volatile String raisonExterne = null;
    static void basculer(Context c) {
        if (active) { raisonExterne = "arrêt par toi (widget ou appli)"; c.stopService(new Intent(c, ServiceTelecommande.class)); }
        else try { c.startForegroundService(new Intent(c, ServiceTelecommande.class)); } catch (Exception ignored) {}
    }

    static void arreter(Context c) { if (active) { raisonExterne = "séance lancée ou essai des manettes (la manette passe à la séance)"; c.stopService(new Intent(c, ServiceTelecommande.class)); } }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        if (i != null && ACTION_ARRET.equals(i.getAction())) { raison = "arrêt par toi (notification)"; stopSelf(); return START_NOT_STICKY; }
        try { startForeground(ID, notif("Réveille la manette. 🔵 Y note Claude · 🟢 A note 4B BTP · 🟣 B appel"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE); }
        catch (Exception e) { stopSelf(); return START_NOT_STICKY; }
        active = true;
        Bug.note(this, "Télécommande", "allumée");
        WidgetTelecommande.majTous(this);
        if (lien == null) { lien = new LienManettes(this, this); lien.demarrer(); }
        relancerDelai();
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        active = false;
        if (raisonExterne != null) { raison = raisonExterne; raisonExterne = null; }
        Bug.note(this, "Télécommande", "arrêtée : " + raison);
        h.removeCallbacksAndMessages(null);
        try { if (lien != null) lien.arreter(); } catch (Exception ignored) {}
        try { if (bip != null) bip.release(); } catch (Exception ignored) {}
        try { if (voix != null) voix.shutdown(); } catch (Exception ignored) {}
        WidgetTelecommande.majTous(this);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void etatManettesChange() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && lien != null) nm.notify(ID, notif(lien.pretes() > 0 ? "✅ Prête · 🔵 Y note Claude · 🟢 A note 4B BTP · 🟣 B appel" : "🔎 Réveille la manette d'un appui…"));
    }

    @Override public void reconnectee() { vibrer(new long[]{0, 60, 120, 60}); }

    @Override public void bouton(String nom) {
        Bug.note(this, "Télécommande", "bouton " + nom);
        DicteeRepas dr = DicteeRepas.actif;   // 🎙️ dictée du carnet ouverte : Y (ou la lettre qui l'a lancée) l'ANNULE (essai 36)
        if (dr != null && dr.enCours()) {
            if (nom.equals(dr.lettre) || "Y".equals(nom)) { vibrer(new long[]{0, 60, 100, 60}); dr.runOnUiThread(dr::appuiExterne); }
            return;
        }
        NoteVocale n = NoteVocale.actif;
        if (n != null) {   // une note est en cours : la MÊME lettre que celle qui l'a commencée l'ANNULE (essai 36 ; on valide en disant « terminé »)
            if (nom.equals(n.lettre)) { vibrer(new long[]{0, 60, 100, 60}); n.runOnUiThread(n::annuler); }
            return;
        }
        if ("B".equals(nom)) { boutonAppel(); return; }
        if (!"Y".equals(nom) && !"A".equals(nom)) return;
        relancerDelai();
        vibrer(new long[]{0, 80});
        try { if (bip == null) bip = new ToneGenerator(AudioManager.STREAM_MUSIC, 90); bip.startTone(ToneGenerator.TONE_PROP_BEEP, 120); } catch (Exception ignored) {}
        ouvrirNote("A".equals(nom) ? "btp" : "claude", nom);
    }

    // ---------- 📞 B = appel (essai 26) : 1er appui → « Appeler X ? appuie sur B pour confirmer » ; 2e appui (10 s) → appel ; pendant l'appel → raccrocher ----------
    private long confirmerJusqua = 0;
    private android.speech.tts.TextToSpeech voix; private boolean voixPrete = false; private String aDire = null;

    private void parler(String t) {
        if (voix == null) { aDire = t; voix = new android.speech.tts.TextToSpeech(this, e -> { if (e == android.speech.tts.TextToSpeech.SUCCESS && voix != null) { try { voix.setLanguage(java.util.Locale.FRANCE); } catch (Exception ignored) {} voixPrete = true; if (aDire != null) { voix.speak(aDire, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "tel"); aDire = null; } } }); return; }
        if (voixPrete) voix.speak(t, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "tel"); else aDire = t;
    }

    private void boutonAppel() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        boolean enAppel = am != null && (am.getMode() == AudioManager.MODE_IN_CALL || am.getMode() == AudioManager.MODE_IN_COMMUNICATION);
        if (enAppel) {
            vibrer(new long[]{0, 60, 100, 60});
            try { android.telecom.TelecomManager tm = (android.telecom.TelecomManager) getSystemService(TELECOM_SERVICE); boolean ok = tm != null && tm.endCall(); Bug.note(this, "Téléphone", ok ? "appel raccroché" : "raccrocher : refusé par Android"); }
            catch (SecurityException e) { Bug.note(this, "Téléphone", "raccrocher : autorisation « gérer les appels » manquante"); parler("Je n'ai pas l'autorisation de raccrocher."); }
            catch (Exception e) { Bug.note(this, "Téléphone", "raccrocher : erreur " + e); }
            return;
        }
        String nom = "", num = "";
        try { org.json.JSONObject c = new org.json.JSONObject(getSharedPreferences("reglages", MODE_PRIVATE).getString("teleContact", "{}")); nom = c.optString("nom"); num = c.optString("num"); } catch (Exception ignored) {}
        if (num.isEmpty()) { vibrer(new long[]{0, 200}); parler("Aucun contact choisi pour le bouton B. Choisis-le dans l'appli."); Bug.note(this, "Téléphone", "B : aucun contact choisi"); return; }
        long now = System.currentTimeMillis();
        if (now > confirmerJusqua) {
            confirmerJusqua = now + 10000;
            vibrer(new long[]{0, 80});
            parler("Appeler " + nom + " ? Appuie sur B pour confirmer.");
            Bug.note(this, "Téléphone", "B : confirmation demandée pour " + nom);
            return;
        }
        confirmerJusqua = 0;
        vibrer(new long[]{0, 80, 100, 80});
        if (checkSelfPermission("android.permission.CALL_PHONE") != android.content.pm.PackageManager.PERMISSION_GRANTED) { parler("Je n'ai pas l'autorisation d'appeler. Autorise-la dans l'appli."); Bug.note(this, "Téléphone", "appel : autorisation manquante"); return; }
        try {
            android.telecom.TelecomManager tm = (android.telecom.TelecomManager) getSystemService(TELECOM_SERVICE);
            tm.placeCall(android.net.Uri.fromParts("tel", num, null), new android.os.Bundle());
            Bug.note(this, "Téléphone", "appel lancé vers " + nom);
        } catch (Exception e) { Bug.note(this, "Téléphone", "appel : erreur " + e); parler("L'appel n'a pas pu partir."); }
    }

    /** 🟣 (essai 25, remplacé par l'appel) : l'assistant d'Android (Gemini) en écoute. */
    private void ouvrirGemini() {
        Intent[] essais = { new Intent(Intent.ACTION_VOICE_COMMAND), new Intent("android.intent.action.ASSIST"), new Intent(android.speech.RecognizerIntent.ACTION_VOICE_SEARCH_HANDS_FREE) };
        for (Intent i : essais) {
            try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); Bug.note(this, "Télécommande", "Gemini ouvert (" + i.getAction() + ")"); return; }
            catch (Exception e) { Bug.note(this, "Télécommande", "Gemini : " + i.getAction() + " refusé : " + e.getClass().getSimpleName()); }
        }
    }

    /** L'écran de dictée s'ouvre par une notification « plein écran » (seule façon autorisée par Android depuis l'arrière-plan). */
    private void ouvrirNote(String cat, String lettre) { ouvrirNote(this, cat, lettre, ""); }

    /** Ouvre l'écran de dictée depuis un service (télécommande ou séance) : notification « plein écran » + ouverture directe si permise. */
    static void ouvrirNote(Service sv, String cat, String lettre, String ctx) {
        NotificationManager nm = (NotificationManager) sv.getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel c = new NotificationChannel(CANAL_NOTE, "Télécommande : note vocale", NotificationManager.IMPORTANCE_HIGH);
        c.setSound(null, null); c.enableVibration(false); c.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(c);
        Intent ecran = new Intent(sv, NoteVocale.class).putExtra("cat", cat).putExtra("lettre", lettre).putExtra("ctx", ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pi = PendingIntent.getActivity(sv, 2203, ecran, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(sv, CANAL_NOTE).setSmallIcon(R.drawable.ic_seance).setContentTitle("🎙️ Note vocale").setContentText("Touche pour dicter ta note")
                .setCategory(Notification.CATEGORY_CALL).setVisibility(Notification.VISIBILITY_PUBLIC).setFullScreenIntent(pi, true).setContentIntent(pi).setAutoCancel(true).setTimeoutAfter(60000).build();
        nm.notify(ID_NOTE, n);
        try { sv.startActivity(ecran); Bug.note(sv, "Note vocale", "écran demandé (" + cat + ", par-dessus les applis " + (android.provider.Settings.canDrawOverlays(sv) ? "autorisé" : "NON autorisé") + ")"); } catch (Exception e) { Bug.note(sv, "Note vocale", "ouverture directe refusée : " + e); }
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
