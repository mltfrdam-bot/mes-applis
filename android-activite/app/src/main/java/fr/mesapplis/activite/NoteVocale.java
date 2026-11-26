package fr.mesapplis.activite;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;

/**
 * 🎙️ Note vocale « à regarder plus tard » (essai 22) : s'ouvre par-dessus l'écran de verrouillage, écoute, écrit la note,
 * la relit à voix haute, puis se ferme toute seule. Les notes attendent dans « notes » puis la page les range dans le Journal.
 */
public class NoteVocale extends Activity {
    private SpeechRecognizer reco;
    private TextToSpeech voix;
    private TextView etat;
    private final Handler h = new Handler(Looper.getMainLooper());
    private int essais = 0;
    private boolean horsLigne = true;   // on essaie d'abord sans internet, puis avec si la langue n'est pas téléchargée

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setShowWhenLocked(true);
        if (getIntent() != null) { String c = getIntent().getStringExtra("cat"); cat = "btp".equals(c) || "seance".equals(c) ? c : "claude"; String l = getIntent().getStringExtra("lettre"); lettre = l == null || l.isEmpty() ? "Y" : l; String x = getIntent().getStringExtra("ctx"); ctx = x == null ? "" : x; }
        setTurnScreenOn(true);
        try { ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(ServiceTelecommande.ID_NOTE); } catch (Exception ignored) {}
        float d = getResources().getDisplayMetrics().density;
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL); l.setGravity(Gravity.CENTER); l.setBackgroundColor(Color.WHITE);
        l.setPadding((int) (20 * d), (int) (24 * d), (int) (20 * d), (int) (24 * d));
        TextView t = new TextView(this); t.setText("btp".equals(cat) ? "🏗️ Note pour 4B BTP" : "seance".equals(cat) ? "🏃 Note de séance" : "🤖 Note pour Claude"); t.setTextSize(24); t.setTypeface(Typeface.DEFAULT_BOLD); t.setTextColor(0xFF1F6FB2); t.setGravity(Gravity.CENTER);
        etat = new TextView(this); etat.setTextSize(20); etat.setTextColor(0xFF1b1f23); etat.setGravity(Gravity.CENTER); etat.setPadding(0, (int) (16 * d), 0, (int) (16 * d));
        Button fermer = new Button(this); fermer.setText("✅ Terminer"); fermer.setAllCaps(false); fermer.setTextSize(20); fermer.setOnClickListener(v -> { if (actif == this) terminer(); else finish(); });
        l.addView(t); l.addView(etat); l.addView(fermer);
        setContentView(l);
        voix = new TextToSpeech(this, s -> { try { voix.setLanguage(Locale.FRANCE); } catch (Exception ignored) {} });
        Bug.note(this, "Note vocale", "écran ouvert (Android " + android.os.Build.VERSION.SDK_INT + ", " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + ")");
        if (checkSelfPermission("android.permission.RECORD_AUDIO") != PackageManager.PERMISSION_GRANTED) {
            Bug.note(this, "Note vocale", "micro non autorisé : demande envoyée");
            etat.setText("Autorise le micro, puis réappuie sur 🔵 Y.");
            requestPermissions(new String[]{"android.permission.RECORD_AUDIO"}, 1);
            return;
        }
        ecouter();
    }

    @Override public void onRequestPermissionsResult(int code, String[] p, int[] r) { boolean ok = r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED; Bug.note(this, "Note vocale", "micro " + (ok ? "autorisé" : "REFUSÉ")); if (ok) ecouter(); }

    // ---------- Dictée continue (essai 24) : on écoute par morceaux et on relance tout seul après chaque pause ;
    // c'est TOI qui termines : 🔵 Y à nouveau, ou le bouton « ✅ Terminer ». Arrêt automatique après 5 minutes. ----------
    static volatile NoteVocale actif;
    String cat = "claude", lettre = "Y", ctx = "";
    private final StringBuilder texte = new StringBuilder();
    private String partiel = "";
    private boolean finir = false, gardee = false;
    private int erreursSuivies = 0;
    private android.media.ToneGenerator bip;

    private void ecouter() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { Bug.note(this, "Note vocale", "ERREUR : aucune reconnaissance vocale sur l'appareil"); etat.setText("La reconnaissance vocale n'est pas disponible sur cet appareil (appli Google manquante ?)."); return; }
        actif = this;
        sonner(1);
        h.postDelayed(() -> muet(true), 300);   // après NOTRE bip : on coupe les bips de Google à chaque reprise d'écoute
        h.postDelayed(this::terminer, 5 * 60000L);
        nouveauReco();
        demarrer();
    }

    private void nouveauReco() {
        try { if (reco != null) reco.destroy(); } catch (Exception ignored) {}
        reco = SpeechRecognizer.createSpeechRecognizer(this);
        reco.setRecognitionListener(new RecognitionListener() {
            @Override public void onResults(Bundle r) {
                ArrayList<String> l = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String txt = l != null && !l.isEmpty() ? l.get(0).trim() : "";
                Bug.note(NoteVocale.this, "Note vocale", "morceau : " + (txt.isEmpty() ? "VIDE" : "« " + txt + " »"));
                if (!txt.isEmpty()) { if (texte.length() > 0) texte.append(' '); texte.append(txt); }
                partiel = ""; erreursSuivies = 0; afficher();
                if (finir) garderEtFermer(); else h.postDelayed(NoteVocale.this::demarrer, 120);
            }
            @Override public void onError(int e) {
                Bug.note(NoteVocale.this, "Note vocale", "ERREUR " + Bug.erreurVoix(e));
                if ((e == 12 || e == 13) && horsLigne) { horsLigne = false; h.postDelayed(() -> { nouveauReco(); demarrer(); }, 300); return; }   // français pas téléchargé : on passe par internet
                if (finir) { garderEtFermer(); return; }
                if (e == 6 || e == 7) { erreursSuivies = 0; h.postDelayed(NoteVocale.this::demarrer, 120); return; }   // silence = tu réfléchis : on continue d'écouter
                if (++erreursSuivies > 5) { rateErr = Bug.erreurVoix(e); garderEtFermer(); return; }
                if (e == 8) { try { reco.cancel(); } catch (Exception ignored) {} }
                h.postDelayed(NoteVocale.this::demarrer, 600);
            }
            @Override public void onReadyForSpeech(Bundle p) { }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float v) { }
            @Override public void onBufferReceived(byte[] b) { }
            @Override public void onEndOfSpeech() { }
            @Override public void onPartialResults(Bundle b) { ArrayList<String> l = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION); partiel = l != null && !l.isEmpty() ? l.get(0) : ""; afficher(); }
            @Override public void onEvent(int t, Bundle b) { }
        });
    }

    private void demarrer() {
        if (finir || reco == null) return;
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, horsLigne)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName())
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L);
        try { reco.startListening(i); } catch (Exception e) { Bug.note(this, "Note vocale", "ERREUR au démarrage : " + e); if (++erreursSuivies > 5) garderEtFermer(); else h.postDelayed(this::demarrer, 800); }
    }

    private String nomLettre() { switch (lettre) { case "A": return "🟢 A"; case "B": return "🟣 B"; case "Z": return "🔴 Z"; case "PLUS": return "➕"; case "MOINS": return "➖"; case "ECRAN": return "le bouton"; default: return "🔵 Y"; } }

    private void afficher() {
        String t = (texte + (partiel.isEmpty() ? "" : (texte.length() > 0 ? " " : "") + partiel)).trim();
        etat.setText(finir ? "J'écris…" : "🎙️ Je t'écoute… prends ton temps.\nPour finir : " + nomLettre() + " ou « ✅ Terminer ».\n\n" + (t.isEmpty() ? "" : "« " + t + " »"));
    }

    /** 🔵 Y appuyé à nouveau, ou bouton « Terminer » : on récupère le dernier morceau puis on garde la note. */
    void terminer() {
        if (finir) return;
        finir = true; afficher();
        Bug.note(this, "Note vocale", "fin demandée");
        try { if (reco != null) reco.stopListening(); } catch (Exception ignored) {}
        h.postDelayed(this::garderEtFermer, 2500);   // si la reconnaissance ne répond plus
    }

    private void garderEtFermer() {
        if (gardee) return;
        gardee = true; finir = true; actif = null;
        h.removeCallbacksAndMessages(null);
        String t = (texte + (partiel.isEmpty() ? "" : (texte.length() > 0 ? " " : "") + partiel)).trim();
        try { if (reco != null) reco.cancel(); } catch (Exception ignored) {}
        muet(false);
        sonner(2);
        if (t.isEmpty()) { etat.setText("Rien n'a été noté." + (rateErr.isEmpty() ? "" : "\n(" + rateErr + ")")); dire("Rien noté."); h.postDelayed(this::finish, 2500); return; }
        String repas = "claude".equals(cat) ? debutAlimentation(t) : null;
        if (repas != null) {   // 🎙️ (essai 33) note qui commence par « alimentation » : elle part dans la dictée du carnet (relecture + 2e appui en 5 s)
            Bug.note(this, "Note vocale", "« alimentation » entendu : dictée du carnet");
            try { startActivity(new Intent(this, DicteeRepas.class).putExtra("texte", repas).putExtra("lettre", lettre).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception e) { Bug.note(this, "Note vocale", "ERREUR ouverture de la dictée : " + e); garder(t); }
            h.postDelayed(this::finish, 300);
            return;
        }
        String sansEnvoi = "claude".equals(cat) ? finEnvoi(t) : null;
        if (sansEnvoi != null) {   // 🎙️ (essai 31) note finie par « envoyer à Claude » : on garde la note PUIS on ouvre l'appli Claude
            final String txt = sansEnvoi.isEmpty() ? t : sansEnvoi;
            final long ts = garder(txt);
            Bug.note(this, "Note vocale", "« envoyer à Claude » entendu : ouverture de l'appli Claude");
            etat.setText("✅ Noté :\n« " + txt + " »\n\n🤖 J'ouvre Claude…");
            dire("Noté. J'ouvre Claude.");
            h.postDelayed(() -> envoyerAClaude(txt, ts), 1500);
            return;
        }
        garder(t);
        etat.setText("✅ Noté :\n« " + t + " »");
        dire("Noté.");
        h.postDelayed(this::finish, 3000);
    }

    /** Note qui commence par « alimentation » : renvoie la suite (ce qu'il a mangé ou bu), ou null. */
    static String debutAlimentation(String t) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?iu)^[\\s,.]*(?:alimentation|alimentations|aliments?)\\b[\\s,.:;!-]*").matcher(t);
        return m.find() ? t.substring(m.end()).trim() : null;
    }

    /** Fin « envoyer à Claude » (et ses variantes mal entendues) : renvoie le texte sans ces mots, ou null si la note ne finit pas ainsi. */
    static String finEnvoi(String t) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?iu)[\\s,.;!-]*(?:et\\s+)?(?:envoie|envoies|envoyer|envoyé|envoyée|envoyez|envoi|envoye)\\s+(?:(?:à|a|au|pour|chez)\\s+)?(?:claude|clode|clôde|glaude|cloud|clod|claud|klaude|claudes)[\\s.!]*$").matcher(t);
        return m.find() ? t.substring(0, m.start()).trim() : null;
    }

    /** Ouvre l'appli Claude (menu Partager d'Android) avec la note déjà écrite ; il reste à appuyer sur Envoyer. */
    private void envoyerAClaude(String txt, long ts) {
        java.util.Calendar k = java.util.Calendar.getInstance(); k.setTimeInMillis(ts > 0 ? ts : System.currentTimeMillis());
        String[] jours = { "dim.", "lun.", "mar.", "mer.", "jeu.", "ven.", "sam." };
        String corps = String.format(Locale.FRANCE, "🤖 NOTE POUR CLAUDE — %s %02d/%02d, %02d:%02d 🎙️\n%s", jours[k.get(java.util.Calendar.DAY_OF_WEEK) - 1], k.get(java.util.Calendar.DAY_OF_MONTH), k.get(java.util.Calendar.MONTH) + 1, k.get(java.util.Calendar.HOUR_OF_DAY), k.get(java.util.Calendar.MINUTE), txt);
        Runnable lancer = () -> {
            Intent s = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, corps).setPackage("com.anthropic.claude").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            boolean ok = false;
            try { startActivity(s); ok = true; Bug.note(this, "Note vocale", "appli Claude ouverte"); }
            catch (Exception e) {
                Bug.note(this, "Note vocale", "appli Claude introuvable (" + e.getClass().getSimpleName() + ") : menu Partager");
                try { startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, corps), "Envoyer la note à…").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); ok = true; } catch (Exception e2) { Bug.note(this, "Note vocale", "ERREUR partage : " + e2); }
            }
            if (ok) marquerEnvoyee(ts); else { etat.setText("✅ Noté, mais je n'ai pas pu ouvrir Claude.\nLa note attend dans « Pour Claude »."); dire("Je n'ai pas pu ouvrir Claude. La note est gardée."); }
            h.postDelayed(this::finish, ok ? 500 : 3500);
        };
        android.app.KeyguardManager km = (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (km != null && km.isKeyguardLocked()) {
            etat.setText("🔓 Déverrouille la tablette pour ouvrir Claude.\n(La note est déjà gardée.)");
            km.requestDismissKeyguard(this, new android.app.KeyguardManager.KeyguardDismissCallback() {
                @Override public void onDismissSucceeded() { lancer.run(); }
                @Override public void onDismissCancelled() { Bug.note(NoteVocale.this, "Note vocale", "déverrouillage annulé : note gardée sans envoi"); h.postDelayed(NoteVocale.this::finish, 1500); }
                @Override public void onDismissError() { Bug.note(NoteVocale.this, "Note vocale", "déverrouillage impossible : note gardée sans envoi"); h.postDelayed(NoteVocale.this::finish, 1500); }
            });
        } else lancer.run();
    }

    /** La note envoyée passera en « ✅ traitée » quand le Journal la rangera. */
    private void marquerEnvoyee(long ts) {
        try {
            android.content.SharedPreferences p = getSharedPreferences("reglages", MODE_PRIVATE);
            JSONArray l = new JSONArray(p.getString("notesVocales", "[]"));
            for (int i = 0; i < l.length(); i++) { JSONObject o = l.getJSONObject(i); if (o.optLong("ts") == ts) o.put("envoyee", true); }
            p.edit().putString("notesVocales", l.toString()).apply();
        } catch (Exception ignored) {}
    }

    /** 🔇 (essai 27) Coupe les sons du système pendant la dictée (les bips de la reconnaissance de Google), puis les remet. */
    private final java.util.List<Integer> coupes = new java.util.ArrayList<>();
    private void muet(boolean oui) {
        android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return;
        if (oui) {
            for (int f : new int[] { android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.STREAM_SYSTEM, android.media.AudioManager.STREAM_NOTIFICATION }) {
                try { if (!am.isStreamMute(f)) { am.adjustStreamVolume(f, android.media.AudioManager.ADJUST_MUTE, 0); coupes.add(f); } } catch (Exception e) { Bug.note(this, "Note vocale", "son " + f + " pas coupé : " + e.getClass().getSimpleName()); }
            }
            Bug.note(this, "Note vocale", "sons système coupés pendant la dictée (" + coupes.size() + ")");
        } else {
            for (int f : coupes) { try { am.adjustStreamVolume(f, android.media.AudioManager.ADJUST_UNMUTE, 0); } catch (Exception ignored) {} }
            coupes.clear();
        }
    }

    private void sonner(int n) {
        try { if (bip == null) bip = new android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 90); bip.startTone(n == 1 ? android.media.ToneGenerator.TONE_PROP_BEEP : android.media.ToneGenerator.TONE_PROP_ACK, n == 1 ? 150 : 250); } catch (Exception ignored) {}
    }

    private String rateErr = "";

    private void dire(String t) { try { if (voix != null) voix.speak(t, TextToSpeech.QUEUE_FLUSH, null, "note"); } catch (Exception ignored) {} }

    private long garder(String txt) {
        long ts = System.currentTimeMillis();
        try {
            android.content.SharedPreferences p = getSharedPreferences("reglages", MODE_PRIVATE);
            JSONArray l = new JSONArray(p.getString("notesVocales", "[]"));
            l.put(new JSONObject().put("ts", ts).put("t", txt).put("cat", cat).put("ctx", ctx));
            p.edit().putString("notesVocales", l.toString()).apply();
            Bug.note(this, "Note vocale", "note gardée (" + l.length() + " en attente de rangement)");
        } catch (Exception ignored) {}
        return ts;
    }

    @Override protected void onDestroy() {
        muet(false);
        if (actif == this) { actif = null; String t = (texte + " " + partiel).trim(); if (!gardee && !t.isEmpty()) { gardee = true; garder(t); } }
        try { if (bip != null) bip.release(); } catch (Exception ignored) {}
        h.removeCallbacksAndMessages(null);
        try { if (reco != null) reco.destroy(); } catch (Exception ignored) {}
        h.postDelayed(() -> { }, 0);
        try { if (voix != null) { TextToSpeech v = voix; new Handler(Looper.getMainLooper()).postDelayed(v::shutdown, 4000); } } catch (Exception ignored) {}
        super.onDestroy();
    }
}
