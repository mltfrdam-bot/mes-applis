package fr.mesapplis.activite;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Essai 32 : écran simple (sans page web) qui montre la dernière erreur de l'appli, avec 📋 Copier pour Claude. */
public class Plantage extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        File f = Appli.fichier(this);
        String txt = "";
        try { txt = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); } catch (Exception ignored) {}
        try { File vu = new File(getFilesDir(), "plantage-vu.txt"); vu.delete(); f.renameTo(vu); } catch (Exception ignored) {}
        final String rapport = txt;
        float d = getResources().getDisplayMetrics().density;
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setBackgroundColor(Color.WHITE);
        l.setPadding((int) (16 * d), (int) (24 * d), (int) (16 * d), (int) (16 * d));
        TextView titre = new TextView(this); titre.setTextSize(20); titre.setTextColor(0xFFB00020);
        titre.setText("🐞 L'appli a planté la dernière fois.\nAppuie sur 📋 Copier et colle le texte dans la conversation avec Claude.");
        Button copier = new Button(this); copier.setAllCaps(false); copier.setTextSize(20); copier.setText("📋 Copier l'erreur");
        copier.setOnClickListener(v -> { ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Plantage", rapport)); Toast.makeText(this, "📋 Copié : colle-le à Claude.", Toast.LENGTH_LONG).show(); });
        Button ouvrir = new Button(this); ouvrir.setAllCaps(false); ouvrir.setTextSize(18); ouvrir.setText("▶ Ouvrir le Journal quand même");
        ouvrir.setOnClickListener(v -> { startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); finish(); });
        TextView corps = new TextView(this); corps.setTextSize(12); corps.setTextColor(0xFF1b1f23); corps.setTextIsSelectable(true); corps.setText(rapport.isEmpty() ? "(texte introuvable)" : rapport);
        ScrollView sv = new ScrollView(this); sv.addView(corps);
        l.addView(titre); l.addView(copier); l.addView(ouvrir);
        l.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(l);
    }
}
