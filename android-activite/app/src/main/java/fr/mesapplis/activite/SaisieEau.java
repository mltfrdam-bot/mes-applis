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

/** 💧 Petite fenêtre « Combien as-tu bu ? » (widget « + » et notification permanente), sans ouvrir l'appli. */
public class SaisieEau extends Activity {
    private int ml = 250;
    private TextView valeur;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("");
        setShowWhenLocked(true); // utilisable depuis l'écran de verrouillage
        float d = getResources().getDisplayMetrics().density;
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding((int) (14 * d), (int) (14 * d), (int) (14 * d), (int) (14 * d));
        l.setBackgroundColor(Color.WHITE);

        TextView t = new TextView(this);
        t.setText("💧 Combien as-tu bu ?");
        t.setTextSize(20); t.setTypeface(Typeface.DEFAULT_BOLD); t.setTextColor(0xFF1b1f23);
        l.addView(t);

        valeur = new TextView(this);
        valeur.setTextSize(40); valeur.setTypeface(Typeface.DEFAULT_BOLD); valeur.setTextColor(0xFF1F6FB2); valeur.setGravity(Gravity.CENTER);
        valeur.setPadding(0, (int) (8 * d), 0, (int) (8 * d));
        l.addView(valeur);

        GridLayout g = new GridLayout(this);
        g.setColumnCount(4);
        g.setUseDefaultMargins(false);
        int[] choix = { 50, 100, 150, 200, 250, 330, 500, 750 };
        for (int q : choix) g.addView(bouton(q / 10 + " cl", d, v -> { ml = q; maj(); }));
        l.addView(g);

        LinearLayout pm = new LinearLayout(this);
        pm.setGravity(Gravity.CENTER);
        pm.addView(bouton("− 5 cl", d, v -> { ml = Math.max(10, ml - 50); maj(); }));
        pm.addView(bouton("+ 5 cl", d, v -> { ml = Math.min(3000, ml + 50); maj(); }));
        l.addView(pm);

        LinearLayout bas = new LinearLayout(this);
        bas.setGravity(Gravity.END);
        Button annuler = bouton("Annuler", d, v -> finish());
        Button ok = bouton("✔ Noter", d, v -> { RappelEau.boire(this, ml, true); finish(); });
        ok.setTextColor(Color.WHITE);
        ((GradientDrawable) ok.getBackground()).setColor(0xFFB5451B);
        for (Button x : new Button[] { annuler, ok }) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams((int) (120 * d), (int) (52 * d));
            p.setMargins((int) (4 * d), (int) (8 * d), (int) (4 * d), 0);
            x.setLayoutParams(p);
        }
        bas.addView(annuler); bas.addView(ok);
        l.addView(bas);

        setContentView(l, new ViewGroup.LayoutParams((int) (372 * d), ViewGroup.LayoutParams.WRAP_CONTENT));
        maj();
    }

    private void maj() { valeur.setText(RappelEau.cl(ml)); }

    private Button bouton(String texte, float d, android.view.View.OnClickListener clic) {
        Button b = new Button(this);
        b.setText(texte); b.setAllCaps(false); b.setTextSize(16); b.setTextColor(0xFF1F6FB2);
        GradientDrawable fond = new GradientDrawable();
        fond.setColor(0xFFE3F0FB); fond.setCornerRadius(12 * d); fond.setStroke((int) d, 0xFF1F6FB2);
        b.setBackground(fond);
        GridLayout.LayoutParams p = new GridLayout.LayoutParams();
        p.width = (int) (80 * d); p.height = (int) (50 * d); p.setMargins((int) (3 * d), (int) (3 * d), (int) (3 * d), (int) (3 * d));
        b.setLayoutParams(p);
        b.setOnClickListener(clic);
        return b;
    }
}
