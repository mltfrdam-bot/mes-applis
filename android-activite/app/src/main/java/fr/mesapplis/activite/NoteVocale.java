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

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        try { ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(ServiceTelecommande.ID_NOTE); } catch (Exception ignored) {}
        float d = getResources().getDisplayMetrics().density;
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL); l.setGravity(Gravity.CENTER); l.setBackgroundColor(Color.WHITE);
        l.setPadding((int) (20 * d), (int) (24 * d), (int) (20 * d), (int) (24 * d));
        TextView t = new TextView(this); t.setText("🎙️ Note à regarder plus tard"); t.setTextSize(24); t.setTypeface(Typeface.DEFAULT_BOLD); t.setTextColor(0xFF1F6FB2); t.setGravity(Gravity.CENTER);
        etat = new TextView(this); etat.setTextSize(20); etat.setTextColor(0xFF1b1f23); etat.setGravity(Gravity.CENTER); etat.setPadding(0, (int) (16 * d), 0, (int) (16 * d));
        Button fermer = new Button(this); fermer.setText("Fermer"); fermer.setAllCaps(false); fermer.setTextSize(18); fermer.setOnClickListener(v -> finish());
        l.addView(t); l.addView(etat); l.addView(fermer);
        setContentView(l);
        voix = new TextToSpeech(this, s -> { try { voix.setLanguage(Locale.FRANCE); } catch (Exception ignored) {} });
        if (checkSelfPermission("android.permission.RECORD_AUDIO") != PackageManager.PERMISSION_GRANTED) {
            etat.setText("Autorise le micro, puis réappuie sur 🔵 Y.");
            requestPermissions(new String[]{"android.permission.RECORD_AUDIO"}, 1);
            return;
        }
        ecouter();
    }

    @Override public void onRequestPermissionsResult(int code, String[] p, int[] r) { if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) ecouter(); }

    private void ecouter() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { etat.setText("La reconnaissance vocale n'est pas disponible sur cet appareil (Google)."); return; }
        etat.setText("Je t'écoute… parle maintenant.");
        try { if (reco != null) reco.destroy(); } catch (Exception ignored) {}
        reco = SpeechRecognizer.createSpeechRecognizer(this);
        reco.setRecognitionListener(new RecognitionListener() {
            @Override public void onResults(Bundle r) {
                ArrayList<String> l = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String txt = l != null && !l.isEmpty() ? l.get(0).trim() : "";
                if (txt.isEmpty()) { rate(); return; }
                garder(txt);
                etat.setText("✅ Noté :\n« " + txt + " »");
                dire("Noté : " + txt);
                h.postDelayed(NoteVocale.this::finish, 3500);
            }
            @Override public void onError(int e) { rate(); }
            @Override public void onReadyForSpeech(Bundle p) { }
            @Override public void onBeginningOfSpeech() { etat.setText("J'écoute…"); }
            @Override public void onRmsChanged(float v) { }
            @Override public void onBufferReceived(byte[] b) { }
            @Override public void onEndOfSpeech() { etat.setText("J'écris…"); }
            @Override public void onPartialResults(Bundle b) { }
            @Override public void onEvent(int t, Bundle b) { }
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L);
        try { reco.startListening(i); } catch (Exception e) { rate(); }
    }

    private void rate() {
        if (++essais <= 1) { etat.setText("Je n'ai pas compris, recommence…"); dire("Je n'ai pas compris, recommence."); h.postDelayed(this::ecouter, 1800); return; }
        etat.setText("Pas compris. Réappuie sur 🔵 Y pour recommencer.");
        dire("Pas compris.");
        h.postDelayed(this::finish, 3000);
    }

    private void dire(String t) { try { if (voix != null) voix.speak(t, TextToSpeech.QUEUE_FLUSH, null, "note"); } catch (Exception ignored) {} }

    private void garder(String txt) {
        try {
            android.content.SharedPreferences p = getSharedPreferences("reglages", MODE_PRIVATE);
            JSONArray l = new JSONArray(p.getString("notesVocales", "[]"));
            l.put(new JSONObject().put("ts", System.currentTimeMillis()).put("t", txt));
            p.edit().putString("notesVocales", l.toString()).apply();
        } catch (Exception ignored) {}
    }

    @Override protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        try { if (reco != null) reco.destroy(); } catch (Exception ignored) {}
        h.postDelayed(() -> { }, 0);
        try { if (voix != null) { TextToSpeech v = voix; new Handler(Looper.getMainLooper()).postDelayed(v::shutdown, 4000); } } catch (Exception ignored) {}
        super.onDestroy();
    }
}
