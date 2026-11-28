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
 * Essai 36 (27/11) : il finit par « terminé » (noté sans relecture) ou « relire » (relu, puis noté 3 s après sauf « effacer ») ;
 * « effacer » vide tout et le micro reste ouvert ; un appui (🎙️, Y ou ❌) annule entièrement.
 * Seuls ses favoris sont cherchés (nom ou mots-codes) ; sans quantité dite, on prend la quantité par défaut du favori.
 * Chaque dictée est gardée dans un journal (« dictees ») pour le 📋 rapport du carnet.
 */
public class DicteeRepas extends Activity {
    static volatile DicteeRepas actif;

    private SpeechRecognizer reco;
    private TextToSpeech voix;
    private boolean voixPrete = false;
    private String aDire = null, aDireId = "info";
    private TextView titre, etat, compte;
    private Button bValider, bAnnuler;
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean horsLigne = true;
    private android.media.ToneGenerator bip;

    // étapes : 0 écoute, 1 relecture / attente de validation, 2 fini
    private int etape = 0;
    String lettre = "";        // lettre de la télécommande si la dictée vient d'une note (« alimentation … »)
    private String texteDonne = null;
    boolean enCours() { return etape < 2; }
    void appuiExterne() { appui(); }
    private String entendu = "", partiel = "";
    private long ts;
    private Dictee.Resultat res;
    private int reste = 5;
    private boolean compteLance = false;
    private boolean fenetre = false, parle = false, direct = false;   // essai 36 : fenêtre de 3 s après la relecture
    private String fin = "";
    private int erreurs = 0;
    private final Runnable arretCode = () -> { try { if (reco != null) reco.stopListening(); } catch (Exception ignored) {} };

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
        titre = new TextView(this); titre.setText("🎙️ Dictée du carnet et de la santé"); titre.setTextSize(24); titre.setTypeface(Typeface.DEFAULT_BOLD); titre.setTextColor(0xFF1F6FB2); titre.setGravity(Gravity.CENTER);
        etat = new TextView(this); etat.setTextSize(20); etat.setTextColor(0xFF1b1f23); etat.setGravity(Gravity.CENTER); etat.setPadding(0, (int) (14 * d), 0, (int) (10 * d));
        compte = new TextView(this); compte.setTextSize(18); compte.setTextColor(0xFF2E7D32); compte.setGravity(Gravity.CENTER); compte.setTypeface(Typeface.DEFAULT_BOLD);
        bValider = new Button(this); bValider.setAllCaps(false); bValider.setTextSize(20); bValider.setText("✅ Noter tout de suite"); bValider.setOnClickListener(v -> { if (etape == 0) finEcoute(true); else valider(); });
        bAnnuler = new Button(this); bAnnuler.setAllCaps(false); bAnnuler.setTextSize(22); bAnnuler.setText("❌ Annuler"); bAnnuler.setOnClickListener(v -> annuler("annulée"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (74 * d));
        l.addView(titre); l.addView(etat); l.addView(compte); l.addView(bAnnuler, lp); l.addView(bValider);
        setContentView(l);
        bValider.setVisibility(android.view.View.GONE);
        voix = new TextToSpeech(this, s -> { try { voix.setLanguage(Locale.FRANCE); } catch (Exception ignored) {} voixPrete = true; if (aDire != null) { String t = aDire; aDire = null; dire(t, aDireId); } });
        voix.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) { if ("relecture".equals(id)) h.post(DicteeRepas.this::lancerCompte); }
            @Override public void onError(String id) { if ("relecture".equals(id)) h.post(DicteeRepas.this::lancerCompte); }
        });
        Bug.note(this, "Dictée repas", "écran ouvert");
        texteDonne = getIntent() != null ? getIntent().getStringExtra("texte") : null;
        String lt = getIntent() != null ? getIntent().getStringExtra("lettre") : null; lettre = lt == null ? "" : lt;
        String fi = getIntent() != null ? getIntent().getStringExtra("fin") : null; fin = fi == null ? "" : fi;
        if (texteDonne != null) {   // essai 33 : la phrase vient d'une note 🔵 Y « alimentation … » : pas besoin d'écouter
            Bug.note(this, "Dictée repas", "phrase reçue d'une note : « " + texteDonne + " »");
            entendu = texteDonne.trim();
            bValider.setVisibility(android.view.View.VISIBLE);
            copiee = "copier".equals(fin);   // essai 38 : déjà copié par la note
            finEcoute("termine".equals(fin) || copiee);   // essai 36 : « terminé » = noté sans relecture
            return;
        }
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

    /** Un appui (🎙️, Y ou la lettre de la note) à n'importe quel moment = la dictée est ANNULÉE entièrement (essai 36). */
    private void appui() { if (etape < 2) annuler("annulée"); }

    // ---------------- Écoute ----------------
    private void ecouter() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { etat.setText("La reconnaissance vocale n'est pas disponible sur cet appareil."); return; }
        afficherEcoute(false);
        bValider.setVisibility(android.view.View.VISIBLE); bValider.setText("✅ Noter (sans relecture)");
        sonner(1);
        h.postDelayed(() -> finEcoute(false), 180000);   // sécurité : 3 min → relecture puis noté
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
                Bug.note(DicteeRepas.this, "Dictée repas", "entendu : « " + t + " »");
                h.removeCallbacks(arretCode);
                if (etape == 1 && fenetre) { traiterFenetre(t); return; }
                if (etape != 0) return;
                partiel = ""; erreurs = 0;
                int c = CodesVoix.code(t);
                if (c == CodesVoix.EFFACER) { recommencer(); return; }
                String sc = CodesVoix.sans(t);
                if (!sc.isEmpty()) entendu = (entendu + " " + sc).trim();
                if (c == CodesVoix.TERMINE) { Bug.note(DicteeRepas.this, "Dictée repas", "« terminé »"); finEcoute(true); return; }
                if (c == CodesVoix.COPIER) { copier(entendu); finEcoute(true); return; }   // essai 38
                if (c == CodesVoix.LIRE) { Bug.note(DicteeRepas.this, "Dictée repas", "« relire »"); finEcoute(false); return; }
                afficherEcoute(false);
                h.postDelayed(DicteeRepas.this::demarrer, 120);   // pas de mot-code : on continue d'écouter
            }
            @Override public void onError(int e) {
                Bug.note(DicteeRepas.this, "Dictée repas", "ERREUR " + Bug.erreurVoix(e));
                if ((e == 12 || e == 13) && horsLigne) { horsLigne = false; h.postDelayed(() -> { nouveauReco(); demarrer(); }, 300); return; }
                if (etape == 1 && fenetre) { parle = false; if (e != 6 && e != 7) h.postDelayed(DicteeRepas.this::demarrer, 300); return; }   // la fenêtre de 3 s continue
                if (etape != 0) return;
                if (e == 6 || e == 7) { h.postDelayed(DicteeRepas.this::demarrer, 120); return; }   // silence : il réfléchit, on écoute encore
                if (++erreurs > 5) { finEcoute(false); return; }
                h.postDelayed(DicteeRepas.this::demarrer, 600);
            }
            @Override public void onReadyForSpeech(Bundle p) { }
            @Override public void onBeginningOfSpeech() { if (etape == 1 && fenetre) { parle = true; compte.setText("🎙️ Je t'écoute…"); } }
            @Override public void onRmsChanged(float v) { }
            @Override public void onBufferReceived(byte[] b) { }
            @Override public void onEndOfSpeech() { }
            @Override public void onPartialResults(Bundle b) {
                ArrayList<String> l = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION); String p = l != null && !l.isEmpty() ? l.get(0) : "";
                h.removeCallbacks(arretCode);   // essai 36 : mot-code à la fin + ~1 s sans rien de nouveau → on l'applique
                if (CodesVoix.code(p) != CodesVoix.AUCUN) h.postDelayed(arretCode, 1000);
                if (etape == 1 && fenetre) { if (!p.isEmpty()) compte.setText("🎙️ « " + p + " »"); return; }
                partiel = p;
                if (etape == 0) afficherEcoute(false);
            }
            @Override public void onEvent(int t, Bundle b) { }
        });
    }

    private void demarrer() {
        if (reco == null || !(etape == 0 || (etape == 1 && fenetre))) return;
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, horsLigne)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName())
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L);
        try { reco.startListening(i); } catch (Exception e) { Bug.note(this, "Dictée repas", "ERREUR au démarrage : " + e); if (etape == 0) finEcoute(false); }
    }

    private void afficherEcoute(boolean efface) {
        String t = (entendu + " " + partiel).trim();
        etat.setText((efface ? "🗑 Effacé : redis ta note.\n" : "🎙️ Je t'écoute…\nEx. : « eau 20 cl, baguette 125 g »\n")
                + "Finis par « terminé » (ou « relire »).\n« effacer » = tout recommencer.\n\n" + (t.isEmpty() ? "" : "« " + t + " »"));
        bAnnuler.setText(lettre.isEmpty() ? "❌ Annuler (ou réappuie sur 🎙️)" : "❌ Annuler (ou 🔵 " + lettre + ")");
    }

    /** 📋 « copier » (essai 38) : la phrase dite va dans le presse-papier, et elle est notée comme avec « terminé ». */
    private boolean copiee = false;
    private void copier(String t) {
        try {
            android.content.ClipboardManager cb = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cb.setPrimaryClip(android.content.ClipData.newPlainText("Dictée", t));
            copiee = true;
            Bug.note(this, "Dictée repas", "« copier » : " + t.length() + " lettres copiées");
        } catch (Exception e) { Bug.note(this, "Dictée repas", "copier : erreur " + e); }
    }

    /** « effacer » : tout est vidé, le micro reste ouvert (essai 36). */
    private void recommencer() {
        Bug.note(this, "Dictée repas", "« effacer » : on recommence");
        if (!entendu.isEmpty()) noterJournal("effacée");
        try { if (voix != null) voix.stop(); } catch (Exception ignored) {}
        etape = 0; fenetre = false; parle = false; compteLance = false; res = null; entendu = ""; partiel = ""; erreurs = 0;
        h.removeCallbacksAndMessages(null);
        compte.setText("");
        bValider.setVisibility(android.view.View.VISIBLE); bValider.setText("✅ Noter (sans relecture)");
        afficherEcoute(true);
        sonner(1);
        h.postDelayed(() -> finEcoute(false), 180000);
        h.postDelayed(() -> { nouveauReco(); demarrer(); }, 250);
    }

    /** Ce qui est dit pendant les 3 s après la relecture. */
    private void traiterFenetre(String t) {
        fenetre = false; parle = false; compte.setText("");
        if (t.isEmpty()) { valider(); return; }
        int c = CodesVoix.code(t);
        if (c == CodesVoix.EFFACER) { recommencer(); return; }
        etape = 0; compteLance = false; res = null;   // il complète sa note : on reprend l'écoute
        String r = CodesVoix.sans(t);
        if (!r.isEmpty()) entendu = (entendu + " " + r).trim();
        if (c == CodesVoix.TERMINE) { finEcoute(true); return; }
        if (c == CodesVoix.COPIER) { copier(entendu); finEcoute(true); return; }
        if (c == CodesVoix.LIRE) { finEcoute(false); return; }
        h.removeCallbacksAndMessages(null);
        bValider.setText("✅ Noter (sans relecture)");
        afficherEcoute(false);
        h.postDelayed(() -> finEcoute(false), 180000);
        h.postDelayed(this::demarrer, 120);
    }

    /** Fin de l'écoute : on comprend la phrase ; « terminé » (direct) = noté tout de suite, sinon relecture puis 3 s (essai 36). */
    private void finEcoute(boolean direct) {
        if (etape != 0) return;
        etape = 1; this.direct = direct;
        h.removeCallbacksAndMessages(null);
        if (entendu.isEmpty() && !partiel.isEmpty()) entendu = partiel.trim();
        try { if (reco != null) reco.cancel(); } catch (Exception ignored) {}
        if (entendu.isEmpty()) { etat.setText("Je n'ai rien entendu."); dire("Je n'ai rien entendu."); noterJournal("rien entendu"); fermerDans(2500); return; }
        JSONObject cfg = favoris(this);
        try { res = Dictee.analyser(entendu, liste(cfg.optJSONArray("favs"))); }
        catch (Throwable e) { Bug.note(this, "Dictée repas", "ERREUR compréhension : " + e); res = new Dictee.Resultat(); res.incompris.add("erreur de l'appli : rien n'est noté"); }
        if (res.items.isEmpty() && res.sante.isEmpty()) {
            etat.setText("« " + entendu + " »\n\n❓ Je n'ai rien compris à noter." + (res.incompris.isEmpty() ? "" : "\n" + String.join("\n", res.incompris)));
            dire((res.incompris.size() == 1 && res.incompris.get(0).contains(" : ") ? res.incompris.get(0).replace(" : ", ", ") + ". " : "Je n'ai pas compris. ") + "Rien n'est noté." + (copiee ? " Mais c'est copié." : ""));
            noterJournal("rien compris"); fermerDans(4500); return;
        }
        Calendar k = Calendar.getInstance(); k.setTimeInMillis(ts);
        if (res.heure != null) { k.set(Calendar.HOUR_OF_DAY, Integer.parseInt(res.heure.substring(0, 2))); k.set(Calendar.MINUTE, Integer.parseInt(res.heure.substring(3))); }   // essai 33 : heure dite
        int min = k.get(Calendar.HOUR_OF_DAY) * 60 + k.get(Calendar.MINUTE);
        boolean deja = cfg.optBoolean("deja", false) && Dictee.aujourdhui(k).equals(cfg.optString("date"));
        String repas = Dictee.repas(min, !deja);
        StringBuilder ecrit = new StringBuilder(), oral = new StringBuilder();
        for (Dictee.Sante x : res.sante) { ecrit.append(x.ecrit).append("\n"); oral.append(x.oral).append(". "); }
        for (Dictee.Item it : res.items) {
            ecrit.append("• ").append(it.nom).append(" ").append(Dictee.qte(it, false)).append(it.defaut ? " (par défaut)" : "").append("\n");
            oral.append(it.nom).append(" ").append(Dictee.qte(it, true)).append(", ");
        }
        String heure = String.format(Locale.FRANCE, "%d h %02d", k.get(Calendar.HOUR_OF_DAY), k.get(Calendar.MINUTE));
        if (!res.items.isEmpty()) oral.append(repas).append(", ");
        if (res.heure != null || !res.items.isEmpty()) oral.append(heure.replace(" h 00", " heures")).append(". ");
        if (!res.incompris.isEmpty()) {
            ecrit.append("\n❓ Pas compris :\n");
            int n = 0;
            for (String x : res.incompris) {
                boolean phrase = x.contains(" : ");
                ecrit.append(phrase ? "• " + x : "• « " + x + " »").append("\n");
                if (n++ < 3) oral.append(phrase ? x.replace(" : ", ", ") + ". " : "Je n'ai pas compris « " + x + " ». ");
            }
        }
        if (direct) { valider(); return; }   // « terminé » : pas de relecture
        oral.append("Dis effacer pour recommencer.");
        etat.setText("« " + entendu + " »\n\n" + ecrit.toString().trim() + "\n\n🕒 " + heure + (res.items.isEmpty() ? "" : " · " + repas));
        bValider.setVisibility(android.view.View.VISIBLE); bValider.setText("✅ Noter tout de suite");
        bAnnuler.setText(lettre.isEmpty() ? "❌ Annuler (ou réappuie sur 🎙️)" : "❌ Annuler (ou 🔵 " + lettre + ")");
        compte.setText("");
        dire(oral.toString(), "relecture");
        h.postDelayed(this::lancerCompte, 20000);   // si la voix ne répond pas
    }

    /** Après la relecture : micro rouvert ; rien dit en 3 s = noté ; « effacer » = recommencer (essai 36). */
    private void lancerCompte() {
        if (etape != 1 || compteLance) return;
        compteLance = true; fenetre = true; parle = false; reste = 3;
        sonner(1);
        h.postDelayed(() -> { if (etape == 1 && fenetre) { nouveauReco(); demarrer(); } }, 250);
        h.postDelayed(this::tic, 400);
    }

    private void tic() {
        if (etape != 1 || !fenetre) return;
        if (parle) { h.postDelayed(this::tic, 300); return; }   // il parle : on attend ce qu'il dit
        if (reste <= 0) { compte.setText(""); valider(); return; }
        compte.setText("⏱ Noté dans " + reste + " s\n« effacer » = recommencer · " + (lettre.isEmpty() ? "🎙️" : "🔵 " + lettre) + " = annuler");
        reste--;
        h.postDelayed(this::tic, 1000);
    }

    private void valider() {
        if (etape != 1 || res == null) return;
        etape = 2; fenetre = false;
        h.removeCallbacksAndMessages(null);
        try { if (reco != null) reco.cancel(); } catch (Exception ignored) {}
        Calendar k = Calendar.getInstance(); k.setTimeInMillis(ts);
        String hm = res.heure != null ? res.heure : String.format(Locale.ROOT, "%02d:%02d", k.get(Calendar.HOUR_OF_DAY), k.get(Calendar.MINUTE));
        int n = 0;
        for (Dictee.Item it : res.items) {
            try {
                JSONObject o = new JSONObject().put("ts", ts).put("vts", ts).put("note", "Noté à la voix");
                if (res.heure != null) o.put("heure", res.heure);
                if (it.eau) o.put("type", "eau").put("ml", (int) Math.round(it.q)); else o.put("type", "fav").put("fid", it.id).put("nom", it.nom).put("q", it.q);
                if (RappelEau.noter(this, o.toString())) n++;
            } catch (Exception e) { Bug.note(this, "Dictée repas", "ERREUR en notant " + it.nom + " : " + e); }
        }
        for (Dictee.Sante x : res.sante) {   // 🩺 essai 34 : même chemin que 🩺 Santé du Journal (le carnet range)
            try {
                JSONObject o = new JSONObject().put("ts", ts).put("vts", ts).put("type", x.type).put("heure", hm);
                if (x.type.equals("tension")) { o.put("sys", (int) x.sys).put("dia", (int) x.dia).put("note", "Noté à la voix"); if (x.pouls > 0) o.put("pouls", (int) x.pouls); }
                else if (x.type.equals("poids")) o.put("kg", x.kg).put("note", "Noté à la voix");
                else o.put("id", "").put("zone", x.zone).put("niveau", (int) x.niveau).put("note", x.note).put("fini", x.fini);
                if (RappelEau.noter(this, o.toString())) n++;
            } catch (Exception e) { Bug.note(this, "Dictée repas", "ERREUR en notant " + x.type + " : " + e); }
        }
        noterJournal("notée");
        sonner(2);
        compte.setText("");
        etat.setText("✅ Noté dans le carnet : " + n + " élément" + (n > 1 ? "s" : "") + "." + (copiee ? "\n📋 Copié." : ""));
        bValider.setVisibility(android.view.View.GONE); bAnnuler.setVisibility(android.view.View.GONE);
        if (direct && !res.incompris.isEmpty()) {   // noté sans relecture : on dit quand même ce qui n'a pas été compris
            String x = res.incompris.get(0);
            etat.setText(etat.getText() + "\n\n❓ Pas compris :\n• " + String.join("\n• ", res.incompris));
            dire((copiee ? "Noté et copié. " : "Noté. ") + (x.contains(" : ") ? x.replace(" : ", ", ") : "Pas compris : " + x) + ".");
        } else dire(copiee ? "Noté et copié." : "C'est noté.");
        MainActivity.carnetRanger();
        fermerDans(1800);
    }

    private void annuler(String statut) {
        if (etape == 2) { finish(); return; }
        int e = etape; etape = 2;
        h.removeCallbacksAndMessages(null);
        try { if (reco != null) reco.cancel(); } catch (Exception ignored) {}
        try { if (voix != null) voix.stop(); } catch (Exception ignored) {}
        etat.setText("❌ Dictée annulée."); compte.setText("");
        if (e == 1 || !entendu.isEmpty()) noterJournal(statut);
        dire("Annulé.");
        fermerDans(1200);
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
                for (Dictee.Sante x : res.sante) its.put(new JSONObject().put("sante", x.type).put("nom", x.ecrit).put("txt", x.ecrit));
                for (String s : res.incompris) inc.put(s);
            }
            o.put("items", its).put("incompris", inc);
            if (res != null && res.heure != null) o.put("heure", res.heure);
            if (texteDonne != null) o.put("source", "note " + lettre);
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
        if (!voixPrete) { aDire = t; aDireId = id; if ("relecture".equals(id)) h.postDelayed(this::lancerCompte, 20000); return; }   // essai 36 : la relecture garde son nom (fenêtre ouverte à la fin de la voix)
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
