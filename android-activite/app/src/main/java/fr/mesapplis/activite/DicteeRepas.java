package fr.mesapplis.activite;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.speech.tts.UtteranceProgressListener;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * 🎙️ Dictée de l'alimentation (essai 31, validée par Frédéric le 26/11/2026).
 * 1er appui (🎙️ du carnet, de la notification d'eau ou du widget) : bip → il dit « eau 20 cl, lait de vache 20 cl, baguette 125 g ».
 * La voix relit ce qu'elle a compris ; un 2e appui dans les 5 secondes valide, sinon rien n'est noté.
 * Seuls ses favoris sont cherchés (nom ou mots-codes) ; sans quantité dite, on prend la quantité par défaut du favori.
 * Chaque dictée est gardée dans un journal (« dictees ») pour le 📋 rapport du carnet.
 */
public class DicteeRepas extends Activity {
    static volatile DicteeRepas actif;

    private SpeechRecognizer reco;
    private TextToSpeech voix;
    private boolean voixPrete = false;
    private String aDire = null;
    private TextView titre, etat, compte;
    private Button bValider, bAnnuler;
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean horsLigne = true;
    private android.media.ToneGenerator bip;

    // étapes : 0 écoute, 1 relecture / attente de validation, 2 fini
    private int etape = 0;
    private String entendu = "", partiel = "";
    private long ts;
    private Dictee.Resultat res;
    private int reste = 5;
    private boolean compteLance = false;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        actif = this;
        ts = System.currentTimeMillis();
        float d = getResources().getDisplayMetrics().density;
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL); l.setGravity(Gravity.CENTER); l.setBackgroundColor(Color.WHITE);
        l.setPadding((int) (20 * d), (int) (22 * d), (int) (20 * d), (int) (22 * d));
        titre = new TextView(this); titre.setText("🎙️ Dictée du carnet"); titre.setTextSize(24); titre.setTypeface(Typeface.DEFAULT_BOLD); titre.setTextColor(0xFF1F6FB2); titre.setGravity(Gravity.CENTER);
        etat = new TextView(this); etat.setTextSize(20); etat.setTextColor(0xFF1b1f23); etat.setGravity(Gravity.CENTER); etat.setPadding(0, (int) (14 * d), 0, (int) (10 * d));
        compte = new TextView(this); compte.setTextSize(18); compte.setTextColor(0xFF2E7D32); compte.setGravity(Gravity.CENTER); compte.setTypeface(Typeface.DEFAULT_BOLD);
        bValider = new Button(this); bValider.setAllCaps(false); bValider.setTextSize(22); bValider.setText("✅ Valider"); bValider.setOnClickListener(v -> appui());
        bAnnuler = new Button(this); bAnnuler.setAllCaps(false); bAnnuler.setTextSize(18); bAnnuler.setText("❌ Annuler"); bAnnuler.setOnClickListener(v -> annuler("annulée"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (74 * d));
        l.addView(titre); l.addView(etat); l.addView(compte); l.addView(bValider, lp); l.addView(bAnnuler);
        setContentView(l);
        bValider.setVisibility(android.view.View.GONE);
        voix = new TextToSpeech(this, s -> { try { voix.setLanguage(Locale.FRANCE); } catch (Exception ignored) {} voixPrete = true; if (aDire != null) { String t = aDire; aDire = null; dire(t); } });
        voix.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) { if ("relecture".equals(id)) h.post(DicteeRepas.this::lancerCompte); }
            @Override public void onError(String id) { if ("relecture".equals(id)) h.post(DicteeRepas.this::lancerCompte); }
        });
        Bug.note(this, "Dictée repas", "écran ouvert");
        if (checkSelfPermission("android.permission.RECORD_AUDIO") != PackageManager.PERMISSION_GRANTED) {
            etat.setText("Autorise le micro, puis réappuie sur 🎙️.");
            requestPermissions(new String[]{"android.permission.RECORD_AUDIO"}, 1);
            return;
        }
        ecouter();
    }

    @Override public void onRequestPermissionsResult(int code, String[] p, int[] r) { if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) ecouter(); else etat.setText("Sans le micro, la dictée ne peut pas marcher."); }

    /** Nouvel appui sur 🎙️ (notification, widget, carnet) pendant que la fenêtre est ouverte. */
    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); appui(); }

    /** Un appui : pendant l'écoute = « j'ai fini de parler » ; pendant la relecture = valider. */
    private void appui() {
        if (etape == 0) { try { if (reco != null) reco.stopListening(); } catch (Exception ignored) {} h.postDelayed(this::finEcoute, 1500); return; }
        if (etape == 1) valider();
    }

    // ---------------- Écoute ----------------
    private void ecouter() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { etat.setText("La reconnaissance vocale n'est pas disponible sur cet appareil."); return; }
        etat.setText("🎙️ Je t'écoute…\nEx. : « eau 20 cl, baguette 125 g »\n\n(Réappuie sur 🎙️ quand tu as fini, ou attends.)");
        bValider.setVisibility(android.view.View.VISIBLE); bValider.setText("⏹ J'ai fini de parler");
        sonner(1);
        h.postDelayed(this::finEcoute, 25000);   // sécurité
        nouveauReco();
        h.postDelayed(this::demarrer, 250);
    }

    private void nouveauReco() {
        try { if (reco != null) reco.destroy(); } catch (Exception ignored) {}
        reco = SpeechRecognizer.createSpeechRecognizer(this);
        reco.setRecognitionListener(new RecognitionListener() {
            @Override public void onResults(Bundle r) {
                ArrayList<String> l = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String t = l != null && !l.isEmpty() ? l.get(0).trim() : "";
                if (!t.isEmpty()) entendu = (entendu + " " + t).trim();
                partiel = "";
                Bug.note(DicteeRepas.this, "Dictée repas", "entendu : « " + t + " »");
                finEcoute();
            }
            @Override public void onError(int e) {
                Bug.note(DicteeRepas.this, "Dictée repas", "ERREUR " + Bug.erreurVoix(e));
                if ((e == 12 || e == 13) && horsLigne) { horsLigne = false; h.postDelayed(() -> { nouveauReco(); demarrer(); }, 300); return; }
                finEcoute();
            }
            @Override public void onReadyForSpeech(Bundle p) { }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float v) { }
            @Override public void onBufferReceived(byte[] b) { }
            @Override public void onEndOfSpeech() { }
            @Override public void onPartialResults(Bundle b) { ArrayList<String> l = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION); partiel = l != null && !l.isEmpty() ? l.get(0) : ""; if (etape == 0 && !partiel.isEmpty()) etat.setText("🎙️ « " + partiel + " »"); }
            @Override public void onEvent(int t, Bundle b) { }
        });
    }

    private void demarrer() {
        if (etape != 0 || reco == null) return;
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, horsLigne)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName())
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L);
        try { reco.startListening(i); } catch (Exception e) { Bug.note(this, "Dictée repas", "ERREUR au démarrage : " + e); finEcoute(); }
    }

    /** Fin de l'écoute : on comprend la phrase, on relit, puis on attend le 2e appui. */
    private void finEcoute() {
        if (etape != 0) return;
        etape = 1;
        h.removeCallbacksAndMessages(null);
        if (entendu.isEmpty() && !partiel.isEmpty()) entendu = partiel.trim();
        try { if (reco != null) reco.cancel(); } catch (Exception ignored) {}
        if (entendu.isEmpty()) { etat.setText("Je n'ai rien entendu."); dire("Je n'ai rien entendu."); noterJournal("rien entendu"); fermerDans(2500); return; }
        JSONObject cfg = favoris(this);
        res = Dictee.comprendre(entendu, liste(cfg.optJSONArray("favs")));
        if (res.items.isEmpty()) {
            etat.setText("« " + entendu + " »\n\n❓ Je n'ai reconnu aucun de tes favoris.");
            dire("Je n'ai pas compris. Rien n'est noté.");
            noterJournal("rien compris"); fermerDans(4000); return;
        }
        Calendar k = Calendar.getInstance(); k.setTimeInMillis(ts);
        int min = k.get(Calendar.HOUR_OF_DAY) * 60 + k.get(Calendar.MINUTE);
        boolean deja = cfg.optBoolean("deja", false) && Dictee.aujourdhui(k).equals(cfg.optString("date"));
        String repas = Dictee.repas(min, !deja);
        StringBuilder ecrit = new StringBuilder(), oral = new StringBuilder();
        for (Dictee.Item it : res.items) {
            ecrit.append("• ").append(it.nom).append(" ").append(Dictee.qte(it, false)).append(it.defaut ? " (par défaut)" : "").append("\n");
            oral.append(it.nom).append(" ").append(Dictee.qte(it, true)).append(", ");
        }
        String heure = String.format(Locale.FRANCE, "%d h %02d", k.get(Calendar.HOUR_OF_DAY), k.get(Calendar.MINUTE));
        oral.append(heure.replace(" h 00", " heures")).append(", ").append(repas).append(". ");
        if (!res.incompris.isEmpty()) {
            ecrit.append("\n❓ Pas compris : « ").append(String.join(" », « ", res.incompris)).append(" »\n");
            oral.append(res.incompris.size() == 1 ? "Je n'ai pas compris « " + res.incompris.get(0) + " ». " : "Il y a " + res.incompris.size() + " passages que je n'ai pas compris. ");
        }
        oral.append(res.items.size() > 1 ? "Appuie pour valider les " + res.items.size() + "." : "Appuie pour valider.");
        etat.setText("« " + entendu + " »\n\n" + ecrit.toString().trim() + "\n\n🕒 " + heure + " · " + repas);
        bValider.setText("✅ Valider (réappuie sur 🎙️)");
        compte.setText("");
        dire(oral.toString(), "relecture");
        h.postDelayed(this::lancerCompte, 15000);   // si la voix ne répond pas
    }

    private void lancerCompte() {
        if (etape != 1 || compteLance) return;
        compteLance = true; reste = 5; tic();
    }

    private void tic() {
        if (etape != 1) return;
        if (reste <= 0) { compte.setText(""); etat.append("\n\n⏱ Pas validé : rien n'est noté."); dire("Pas validé."); noterJournal("pas validée (5 s)"); etape = 2; fermerDans(2500); return; }
        compte.setText("⏱ " + reste + " s pour valider");
        reste--;
        h.postDelayed(this::tic, 1000);
    }

    private void valider() {
        if (etape != 1 || res == null) return;
        etape = 2;
        h.removeCallbacksAndMessages(null);
        int n = 0;
        for (Dictee.Item it : res.items) {
            try {
                JSONObject o = new JSONObject().put("ts", ts).put("vts", ts).put("note", "Noté à la voix");
                if (it.eau) o.put("type", "eau").put("ml", (int) Math.round(it.q)); else o.put("type", "fav").put("fid", it.id).put("nom", it.nom).put("q", it.q);
                if (RappelEau.noter(this, o.toString())) n++;
            } catch (Exception e) { Bug.note(this, "Dictée repas", "ERREUR en notant " + it.nom + " : " + e); }
        }
        noterJournal("validée");
        sonner(2);
        compte.setText("");
        etat.setText("✅ Noté dans le carnet :\n" + n + " prise" + (n > 1 ? "s" : "") + ".");
        bValider.setVisibility(android.view.View.GONE); bAnnuler.setVisibility(android.view.View.GONE);
        dire("C'est noté.");
        MainActivity.carnetRanger();
        fermerDans(1800);
    }

    private void annuler(String statut) {
        if (etape == 2) { finish(); return; }
        int e = etape; etape = 2;
        h.removeCallbacksAndMessages(null);
        try { if (reco != null) reco.cancel(); } catch (Exception ignored) {}
        if (e == 1 || !entendu.isEmpty()) noterJournal(statut);
        dire("Annulé.");
        fermerDans(900);
    }

    private void fermerDans(long ms) { etape = 2; h.postDelayed(this::finish, ms); }

    // ---------------- Journal des dictées (pour le rapport) ----------------
    private void noterJournal(String statut) {
        try {
            SharedPreferences p = RappelEau.prefs(this);
            JSONArray l = new JSONArray(p.getString("dictees", "[]"));
            JSONObject o = new JSONObject().put("ts", ts).put("entendu", entendu).put("statut", statut);
            JSONArray its = new JSONArray(), inc = new JSONArray();
            if (res != null) {
                for (Dictee.Item it : res.items) its.put(new JSONObject().put("fid", it.id).put("nom", it.nom).put("q", it.q).put("txt", it.nom + " " + Dictee.qte(it, false)).put("dit", it.dit).put("defaut", it.defaut));
                for (String s : res.incompris) inc.put(s);
            }
            o.put("items", its).put("incompris", inc);
            l.put(o);
            JSONArray garde = new JSONArray();
            for (int i = Math.max(0, l.length() - 300); i < l.length(); i++) garde.put(l.get(i));
            p.edit().putString("dictees", garde.toString()).apply();
            Bug.note(this, "Dictée repas", "dictée " + statut + " (" + (res == null ? 0 : res.items.size()) + " article(s))");
        } catch (Exception ignored) {}
    }

    // ---------------- Voix ----------------
    private void dire(String t) { dire(t, "info"); }
    private void dire(String t, String id) {
        if (!voixPrete) { aDire = t; if ("relecture".equals(id)) h.postDelayed(this::lancerCompte, 9000); return; }
        try { voix.speak(t, TextToSpeech.QUEUE_FLUSH, null, id); } catch (Exception e) { if ("relecture".equals(id)) lancerCompte(); }
    }

    private void sonner(int n) {
        try { if (bip == null) bip = new android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 90); bip.startTone(n == 1 ? android.media.ToneGenerator.TONE_PROP_BEEP : android.media.ToneGenerator.TONE_PROP_ACK, n == 1 ? 150 : 250); } catch (Exception ignored) {}
    }

    @Override protected void onDestroy() {
        if (actif == this) actif = null;
        h.removeCallbacksAndMessages(null);
        try { if (reco != null) reco.destroy(); } catch (Exception ignored) {}
        try { if (bip != null) bip.release(); } catch (Exception ignored) {}
        try { if (voix != null) { TextToSpeech v = voix; new Handler(Looper.getMainLooper()).postDelayed(v::shutdown, 3000); } } catch (Exception ignored) {}
        super.onDestroy();
    }

    /** 🎙️ de la notification d'eau ou du widget : 1er appui = ouvrir et écouter ; 2e appui (fenêtre déjà ouverte) = valider. */
    static android.app.PendingIntent ouvrir(Context c, int code) {
        return android.app.PendingIntent.getActivity(c, code, new Intent(c, DicteeRepas.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
    }

    static JSONObject favoris(Context c) {
        try { return new JSONObject(RappelEau.prefs(c).getString("favorisVoix", "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    static List<Dictee.Fav> liste(JSONArray a) {
        List<Dictee.Fav> l = new ArrayList<>();
        if (a == null) return l;
        for (int i = 0; i < a.length(); i++) { JSONObject f = a.optJSONObject(i); if (f == null) continue; l.add(new Dictee.Fav(f.optString("id"), f.optString("nom"), f.optString("u", "portion"), f.optDouble("q", 1), f.optString("codes", ""), f.optBoolean("eau"))); }
        return l;
    }
}
