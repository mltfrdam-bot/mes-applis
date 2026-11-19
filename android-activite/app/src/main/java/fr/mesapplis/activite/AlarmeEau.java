package fr.mesapplis.activite;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * ⏰ Écran d'alarme de l'eau : s'affiche par-dessus l'écran de verrouillage (pas besoin de déverrouiller).
 * Une seule touche sur une quantité = la boisson est notée ET l'alarme s'arrête.
 * Le son vient de la notification : il s'arrête quand la notification est retirée (RappelEau.annuler).
 */
public class AlarmeEau extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        float d = getResources().getDisplayMetrics().density;
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        l.setPadding((int) (16 * d), (int) (20 * d), (int) (16 * d), (int) (20 * d));
        l.setBackgroundColor(Color.WHITE);

        TextView t = new TextView(this);
        t.setText(getIntent().getStringExtra("titre") == null ? "💧 En retard d'eau" : getIntent().getStringExtra("titre"));
        t.setTextSize(24); t.setTypeface(Typeface.DEFAULT_BOLD); t.setTextColor(0xFF1F6FB2); t.setGravity(Gravity.CENTER);
        l.addView(t);
        TextView s = new TextView(this);
        s.setText((getIntent().getStringExtra("texte") == null ? "" : getIntent().getStringExtra("texte") + "\n") + "Touche ce que tu bois : c'est noté et l'alarme s'arrête.");
        s.setTextSize(16); s.setTextColor(0xFF1b1f23); s.setGravity(Gravity.CENTER);
        s.setPadding(0, (int) (8 * d), 0, (int) (12 * d));
        l.addView(s);

        GridLayout g = new GridLayout(this);
        g.setColumnCount(3);
        for (int q : new int[] { 100, 150, 200, 250, 330, 500 }) g.addView(bouton("+ " + q / 10 + " cl", d, 96, 64, v -> boire(q)));
        l.addView(g);

        Button autre = bouton("Autre quantité…", d, 200, 54, v -> { RappelEau.annuler(this); startActivity(new android.content.Intent(this, SaisieEau.class)); finish(); });
        l.addView(autre);
        Button tard = bouton("Plus tard", d, 140, 46, v -> { RappelEau.plusTard(this); finish(); });
        tard.setTextSize(14);
        l.addView(tard);

        setContentView(l, new ViewGroup.LayoutParams((int) (340 * d), ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void boire(int ml) { RappelEau.boire(this, ml, true); finish(); }

    private Button bouton(String texte, float d, int w, int h, android.view.View.OnClickListener clic) {
        Button b = new Button(this);
        b.setText(texte); b.setAllCaps(false); b.setTextSize(18); b.setTextColor(0xFF1F6FB2); b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable fond = new GradientDrawable();
        fond.setColor(0xFFE3F0FB); fond.setCornerRadius(14 * d); fond.setStroke((int) d, 0xFF1F6FB2);
        b.setBackground(fond);
        GridLayout.LayoutParams p = new GridLayout.LayoutParams();
        p.width = (int) (w * d); p.height = (int) (h * d); p.setMargins((int) (4 * d), (int) (4 * d), (int) (4 * d), (int) (4 * d));
        p.setGravity(Gravity.CENTER_HORIZONTAL);
        b.setLayoutParams(p);
        b.setOnClickListener(clic);
        return b;
    }
}
