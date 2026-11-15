package fr.mesapplis.relais;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Écran de réglage du relais, sur le téléphone. À n'utiliser qu'une fois au départ. */
public class MainActivity extends Activity {
    private LinearLayout page;

    private static final String[][] AUTORISATIONS = {
            {Manifest.permission.CALL_PHONE, "Passer des appels"},
            {Manifest.permission.ANSWER_PHONE_CALLS, "Décrocher et raccrocher"},
            {Manifest.permission.READ_PHONE_STATE, "Savoir quand ça sonne"},
            {Manifest.permission.READ_CONTACTS, "Lire les contacts"},
            {Manifest.permission.READ_CALL_LOG, "Journal d'appels (historique, numéro qui appelle)"},
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.WHITE);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int m = dp(18);
        page.setPadding(m, m, m, m * 2);
        sv.addView(page);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        afficher();
    }

    private void afficher() {
        page.removeAllViews();
        titre("📞 Relais Appels", 28);
        texte("Ta tablette passe les appels avec ce téléphone, même verrouillé.", 16, Color.DKGRAY);

        // 1. Autorisations
        section("1. Autorisations");
        boolean toutes = true;
        for (String[] a : AUTORISATIONS) {
            boolean ok = checkSelfPermission(a[0]) == PackageManager.PERMISSION_GRANTED;
            toutes &= ok;
            texte((ok ? "✅ " : "❌ ") + a[1], 17, Color.BLACK);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            boolean ok = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
            toutes &= ok;
            texte((ok ? "✅ " : "❌ ") + "Notification « relais actif »", 17, Color.BLACK);
        }
        if (!toutes) {
            bouton("Donner les autorisations", v -> demanderAutorisations());
            texte("Si « Journal d'appels » reste bloqué : Paramètres > Applications > Relais Appels > ⋮ (en haut à droite) > « Autoriser les paramètres restreints », puis reviens ici.", 14, Color.GRAY);
        }

        // 2. Batterie
        section("2. Batterie");
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean batterieOk = pm.isIgnoringBatteryOptimizations(getPackageName());
        texte((batterieOk ? "✅ " : "❌ ") + "Jamais mis en veille", 17, Color.BLACK);
        if (!batterieOk) bouton("Autoriser (aucune restriction)", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
            } catch (Exception e) { ouvrirInfosAppli(); }
        });
        boolean dessus = Settings.canDrawOverlays(this);
        texte((dessus ? "✅ " : "⚪ ") + "Afficher par-dessus (secours si un appel ne part pas)", 17, Color.BLACK);
        if (!dessus) bouton("Autoriser l'affichage par-dessus", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            } catch (Exception e) { ouvrirInfosAppli(); }
        });

        // 3. Relais
        section("3. Relais");
        if (RelaisService.actif) {
            texte("🟢 Relais en marche", 20, Color.rgb(0, 120, 0));
            bouton("Arrêter le relais", v -> {
                Reglages.auto(this, false);
                stopService(new Intent(this, RelaisService.class));
                RelaisService.actif = false;
                afficher();
            });
        } else {
            texte("🔴 Relais arrêté", 20, Color.rgb(180, 0, 0));
            bouton("▶ Démarrer le relais", v -> {
                Reglages.auto(this, true);
                RelaisService.demarrer(this);
                page.postDelayed(this::afficher, 600);
            });
        }

        // 4. Tablette
        section("4. Sur la tablette");
        texte("Ouvre Chrome sur la tablette et tape l'adresse :", 16, Color.DKGRAY);
        List<String[]> ad = Reglages.adresses();
        if (ad.isEmpty()) {
            texte("Aucun réseau : connecte le téléphone au wifi, ou allume son point d'accès et connecte la tablette dessus.", 16, Color.rgb(180, 0, 0));
        }
        for (String[] a : ad) {
            texte(a[0], 14, Color.GRAY);
            TextView t = texte(a[1], 24, Color.rgb(0, 70, 160));
            t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            t.setTextIsSelectable(true);
        }
        texte("Puis tape ce code (une seule fois) :", 16, Color.DKGRAY);
        TextView code = texte(espacer(Reglages.code(this)), 40, Color.BLACK);
        code.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        code.setGravity(Gravity.CENTER_HORIZONTAL);
        bouton("Actualiser", v -> afficher());
        bouton("Changer le code", v -> { Reglages.nouveauCode(this); afficher(); });
    }

    private void demanderAutorisations() {
        List<String> l = new ArrayList<>();
        for (String[] a : AUTORISATIONS)
            if (checkSelfPermission(a[0]) != PackageManager.PERMISSION_GRANTED) l.add(a[0]);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            l.add(Manifest.permission.POST_NOTIFICATIONS);
        if (l.isEmpty()) return;
        requestPermissions(l.toArray(new String[0]), 7);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        afficher();
        // Si Android ne montre plus la fenêtre (refus répétés), on ouvre la page de l'appli
        for (int i = 0; i < r.length; i++) {
            if (r[i] != PackageManager.PERMISSION_GRANTED && !shouldShowRequestPermissionRationale(p[i])) {
                ouvrirInfosAppli();
                return;
            }
        }
    }

    private void ouvrirInfosAppli() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) {}
    }

    private static String espacer(String c) {
        return c.length() == 6 ? c.substring(0, 3) + " " + c.substring(3) : c;
    }

    // ---------- Petits outils d'affichage ----------

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void titre(String t, int taille) {
        TextView v = texte(t, taille, Color.BLACK);
        v.setTypeface(Typeface.DEFAULT_BOLD);
    }

    private void section(String t) {
        TextView v = texte(t, 20, Color.BLACK);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        ((LinearLayout.LayoutParams) v.getLayoutParams()).topMargin = dp(22);
    }

    private TextView texte(String t, int taille, int couleur) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, taille);
        v.setTextColor(couleur);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        page.addView(v, lp);
        return v;
    }

    private void bouton(String t, android.view.View.OnClickListener ecoute) {
        Button bt = new Button(this);
        bt.setText(t);
        bt.setAllCaps(false);
        bt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        bt.setOnClickListener(ecoute);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
        lp.topMargin = dp(8);
        page.addView(bt, lp);
    }
}
