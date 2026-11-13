package fr.mesapplis.activite;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Le service permanent de séance : c'est lui qui tient la ceinture H10 et qui enregistre, même écran éteint
 * et téléphone dans le sac. Android le garde en vie grâce à sa notification permanente.
 *
 * - Il tourne dès qu'on connecte la ceinture ; pendant un enregistrement, il empêche le téléphone de s'endormir.
 * - Chaque seconde est écrite tout de suite dans « seance-en-cours.txt ». Si l'appli est tuée, Android relance
 *   le service (ou l'appli le relance à son ouverture) et l'enregistrement reprend à partir de ce journal.
 * - À la fin, l'enregistrement complet est écrit dans « enregistrements/rec-<début>.json » ; la page l'ajoute à l'Historique.
 */
public class ServiceSeance extends Service implements LienH10.Ecoute {
    static final String ACTION_CONNECTER = "fr.mesapplis.activite.CONNECTER";
    static final String ACTION_DEMARRER = "fr.mesapplis.activite.DEMARRER";
    static final String ACTION_REPRENDRE = "fr.mesapplis.activite.REPRENDRE";
    static final String EXTRA_ACT = "act";
    static final String JOURNAL = "seance-en-cours.txt";
    private static final String CANAL = "seance";
    private static final int ID_NOTIF = 1;
    private static final long ARRET_SI_INUTILE = 10 * 60 * 1000;
    private static final long VIEUX_JOURNAL = 3 * 60 * 60 * 1000;

    /** Le service en marche, s'il y en a un (même processus que l'écran). */
    static volatile ServiceSeance actif;

    private final SuiviImpacts suivi = new SuiviImpacts();
    private final Handler h = new Handler(Looper.getMainLooper());
    private LienH10 lien;
    private volatile Enregistreur enr;
    private volatile Brut brut;                         // mesures brutes de la séance en cours (un fichier par séance)
    private File dossier;
    private PowerManager.WakeLock verrou;
    private ToneGenerator son;
    private NotificationManager notifs;

    private volatile int fc = 0;
    private volatile long fcT = 0;
    private volatile int accCompte = 0, accTaux = 0;
    private long dernierBip = 0, dernierTaux = 0, inutileDepuis = 0;
    private String derniereNotif = "";
    private boolean premierPlan = false;
    private String version = "";

    static File dossierDonnees(Context c) { return new File(c.getFilesDir(), "donnees"); }
    static boolean aReprendre(Context c) { File f = new File(dossierDonnees(c), JOURNAL); return f.exists() && f.length() > 0; }

    /** Écrit un enregistrement terminé dans « enregistrements/rec-<début>.json ». Renvoie le nom du fichier, ou "" en cas d'échec. */
    static String ecrireEnregistrement(File dossier, long debut, String json) {
        String nom = "rec-" + debut + ".json";
        try {
            File dir = new File(dossier, "enregistrements");
            if (!dir.exists()) dir.mkdirs();
            File tmp = new File(dir, nom + ".tmp"), fin = new File(dir, nom);
            try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(json.getBytes(StandardCharsets.UTF_8)); o.getFD().sync(); }
            if (fin.exists()) fin.delete();
            return tmp.renameTo(fin) ? nom : "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Une séance jamais terminée et arrêtée depuis plus de 3 heures (appli tuée, téléphone éteint…) n'est pas reprise :
     * elle est rangée telle quelle dans les enregistrements, avec sa vraie heure de fin. Rien n'est perdu.
     */
    static void cloturerVieuxJournal(Context c) {
        try {
            File dossier = dossierDonnees(c), journal = new File(dossier, JOURNAL);
            if (!journal.exists() || journal.length() == 0) return;
            if (System.currentTimeMillis() - journal.lastModified() < VIEUX_JOURNAL) return;
            Enregistreur e = Enregistreur.relire(journal);
            if (e == null) { journal.renameTo(new File(dossier, "seance-illisible-" + System.currentTimeMillis() + ".txt")); return; }
            String nom = ecrireEnregistrement(dossier, e.debut, e.terminer(e.finConnue(), "APK (séance clôturée après coup)"));
            if (!nom.isEmpty()) journal.delete();
        } catch (Exception ignored) {}
    }

    @Override
    public void onCreate() {
        super.onCreate();
        actif = this;
        dossier = dossierDonnees(this);
        if (!dossier.exists()) dossier.mkdirs();
        notifs = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (notifs != null) {
            NotificationChannel canal = new NotificationChannel(CANAL, "Séance en cours", NotificationManager.IMPORTANCE_LOW);
            canal.setDescription("Affiche la séance et la ceinture H10 pendant que l'appli travaille écran éteint.");
            canal.setShowBadge(false);
            canal.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            notifs.createNotificationChannel(canal);
        }
        try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) {}
        SharedPreferences p = getSharedPreferences("reglages", MODE_PRIVATE);
        suivi.regler(p.getInt("impSeuil", 2500), p.getInt("impRearme", 1800), p.getBoolean("impBip", true));
        lien = new LienH10(this, this);
        try { son = new ToneGenerator(AudioManager.STREAM_MUSIC, 90); } catch (Exception e) { son = null; }
        cloturerVieuxJournal(this);
        try { enr = Enregistreur.reprendre(new File(dossier, JOURNAL), System.currentTimeMillis()); } catch (Exception e) { enr = null; }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int id) {
        if (!mettreAuPremierPlan()) { stopSelf(); return START_NOT_STICKY; }
        String action = intent == null ? null : intent.getAction();
        if (ACTION_DEMARRER.equals(action) && enr == null) {
            try {
                enr = Enregistreur.nouveau(new File(dossier, JOURNAL), System.currentTimeMillis(), intent.getStringExtra(EXTRA_ACT));
            } catch (Exception e) {
                enr = null;
            }
        }
        if (enr == null && !ACTION_CONNECTER.equals(action) && !ACTION_DEMARRER.equals(action)) {
            // relancé par Android sans rien à reprendre : on s'arrête
            if (lien.etat() == LienH10.ARRET) { arreter(); return START_NOT_STICKY; }
        }
        Enregistreur e = enr;
        if (e != null && brut == null) brut = Brut.ouvrir(new File(new File(dossier, "enregistrements"), "brut-" + e.debut + ".bin"), e.debut);
        lien.demarrer(getSharedPreferences("reglages", MODE_PRIVATE).getString("h10Adresse", ""));
        ajuster();
        h.removeCallbacks(tic);
        h.postDelayed(tic, 1000);
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    /** L'appli est balayée de la liste des applis récentes : on continue si une séance est en cours. */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (enr == null) arreter();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        h.removeCallbacksAndMessages(null);
        try { lien.arreter(); } catch (Exception ignored) {}
        Enregistreur e = enr;
        if (e != null) e.fermerJournal(false);          // le journal reste sur le téléphone : la séance pourra reprendre
        enr = null;
        Brut b = brut;
        brut = null;
        if (b != null) b.fermer();
        relacher();
        try { if (son != null) son.release(); } catch (Exception ignored) {}
        if (actif == this) actif = null;
        super.onDestroy();
    }

    // ---------- Premier plan, veille, notification ----------

    private boolean mettreAuPremierPlan() {
        try {
            Notification n = notification();
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID_NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            else startForeground(ID_NOTIF, n);
            premierPlan = true;
            return true;
        } catch (Exception e) {
            return false;                                // par ex. autorisation Bluetooth retirée
        }
    }

    /** Capteur de mouvement et blocage de la veille : seulement quand il y a une raison. */
    private void ajuster() {
        boolean enregistre = enr != null;
        lien.accVouloir(enregistre || suivi.essaiEnCours());
        if (enregistre) {
            if (verrou == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null) { verrou = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "journal:seance"); verrou.setReferenceCounted(false); }
            }
            try { if (verrou != null && !verrou.isHeld()) verrou.acquire(6 * 60 * 60 * 1000L); } catch (Exception ignored) {}
        } else {
            relacher();
        }
        majNotification(true);
    }

    private void relacher() { try { if (verrou != null && verrou.isHeld()) verrou.release(); } catch (Exception ignored) {} }

    private static String nomAct(String a) {
        if (a == null) return "Séance";
        switch (a) {
            case "footing": return "Course";
            case "tennis": return "Tennis";
            case "pompes": return "Pompes";
            case "muscu": return "Muscu";
            case "velo": return "Vélo";
            case "marche": return "Marche";
            default: return a;
        }
    }

    private String texteLien() {
        switch (lien.etat()) {
            case LienH10.CONNECTE: return "H10 connectée";
            case LienH10.RECHERCHE: return "recherche de la H10…";
            case LienH10.CONNEXION: case LienH10.PREPARATION: return "connexion à la H10…";
            case LienH10.PERDUE: return "⚠️ H10 perdue — reconnexion…";
            case LienH10.BT_ETEINT: return "⚠️ Bluetooth éteint";
            case LienH10.SANS_DROIT: return "⚠️ Bluetooth non autorisé";
            default: return "H10 arrêtée";
        }
    }

    private int fcFraiche() { return fc > 0 && System.currentTimeMillis() - fcT < 6000 && lien.etat() == LienH10.CONNECTE ? fc : 0; }

    private Notification notification() {
        Enregistreur e = enr;
        int f = fcFraiche();
        String titre = e != null ? "● " + nomAct(e.activite()) + " — enregistrement" : "Ceinture H10";
        String texte = (f > 0 ? "❤️ " + f + " · " : "") + (e != null ? "⚡ " + e.impacts() + " impacts · " : "") + texteLien();
        derniereNotif = titre + "|" + texte;
        Intent ouvrir = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, ouvrir, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CANAL)
                .setSmallIcon(R.drawable.ic_seance)
                .setContentTitle(titre)
                .setContentText(texte)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(e != null)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_SERVICE);
        if (e != null) b.setUsesChronometer(true).setWhen(e.debut);
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }

    private void majNotification(boolean forcer) {
        if (!premierPlan || notifs == null) return;
        try {
            String avant = derniereNotif;
            Notification n = notification();
            if (forcer || !derniereNotif.equals(avant)) notifs.notify(ID_NOTIF, n);
        } catch (Exception ignored) {}
    }

    /** Une fois par seconde. */
    private final Runnable tic = new Runnable() {
        private int compteur = 0;
        @Override
        public void run() {
            long maintenant = System.currentTimeMillis();
            Enregistreur e = enr;
            if (e != null) e.tic(maintenant);
            if (maintenant - dernierTaux >= 2000) { accTaux = (int) Math.round(accCompte * 1000.0 / Math.max(1, maintenant - dernierTaux)); accCompte = 0; dernierTaux = maintenant; }
            if (++compteur % 5 == 0) majNotification(false);
            // ceinture connectée pour rien (pas de séance, pas de réglage, appli fermée) : on s'arrête au bout de 10 minutes
            if (e == null && !suivi.essaiEnCours() && !MainActivity.visible) {
                if (inutileDepuis == 0) inutileDepuis = maintenant;
                else if (maintenant - inutileDepuis > ARRET_SI_INUTILE) { arreter(); return; }
            } else {
                inutileDepuis = 0;
            }
            h.postDelayed(this, 1000);
        }
    };

    // ---------- Ce que la ceinture envoie (LienH10.Ecoute) ----------

    @Override
    public void fc(int bpm) {
        long maintenant = System.currentTimeMillis();
        fc = bpm; fcT = maintenant;
        Enregistreur e = enr;
        if (e != null) e.fc(bpm, maintenant);
    }

    @Override
    public void trame(byte[] donnees, long t) {
        int[] v = Trames.acc(donnees);
        if (v == null) return;
        int n = v.length / 3;
        long horloge = System.currentTimeMillis();
        Enregistreur e = enr;
        Brut b = brut;
        if (b != null && e != null) b.trame(v, horloge);
        boolean biper = false;
        for (int i = 0; i < n; i++) {
            double x = v[3 * i], y = v[3 * i + 1], z = v[3 * i + 2];
            double m = Math.sqrt(x * x + y * y + z * z);
            long ti = t - (long) (n - 1 - i) * 20;
            if (e != null) { if (e.mesure(m, ti, horloge, suivi)) biper = true; }
            else if (suivi.mesure(m, ti) == SuiviImpacts.PIC_COMPTE && suivi.bip) biper = true;
        }
        accCompte += n;
        if (biper) biper(horloge);
    }

    private void biper(long maintenant) {
        if (maintenant - dernierBip < 300) return;
        dernierBip = maintenant;
        try { if (son != null) son.startTone(ToneGenerator.TONE_PROP_BEEP, 90); } catch (Exception ignored) {}
        try {
            Vibrator vb = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vb != null) vb.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Exception ignored) {}
    }

    @Override
    public void etatChange() {
        Enregistreur e = enr;
        int etat = lien.etat();
        if (e != null && etat != LienH10.PREPARATION && etat != LienH10.CONNEXION) e.lien(etat == LienH10.CONNECTE, System.currentTimeMillis());
        if (etat != LienH10.CONNECTE) fc = 0;
        h.post(() -> majNotification(false));
    }

    @Override
    public void adresseTrouvee(String adresse, String nom) {
        getSharedPreferences("reglages", MODE_PRIVATE).edit().putString("h10Adresse", adresse).putString("h10Nom", nom).apply();
    }

    // ---------- Ce que l'écran demande (appelé par le pont de MainActivity) ----------

    /** État complet pour l'écran, une fois par seconde. */
    String etatJson() {
        long maintenant = System.currentTimeMillis();
        Enregistreur e = enr;
        String nom = lien.nom();
        if (nom == null || nom.isEmpty()) nom = getSharedPreferences("reglages", MODE_PRIVATE).getString("h10Nom", "");
        StringBuilder sb = new StringBuilder(400);
        sb.append("{\"service\":true,\"h10\":{\"etat\":").append(lien.etat()).append(",\"texte\":").append(Json.texte(texteLien())).append(",\"nom\":").append(Json.texte(nom))
          .append(",\"fc\":").append(fcFraiche()).append(",\"depuis\":").append(Math.max(0, (maintenant - lien.depuis()) / 1000)).append('}');
        sb.append(",\"acc\":{\"present\":").append(lien.pmdPresent()).append(",\"actif\":").append(lien.accActif()).append(",\"taux\":").append(accTaux)
          .append(",\"erreur\":").append(Json.texte(lien.accErreur())).append('}');
        sb.append(",\"imp\":{\"seuil\":").append(suivi.seuil).append(",\"rearme\":").append(suivi.rearme).append(",\"bip\":").append(suivi.bip).append('}');
        sb.append(",\"dernier\":");
        if (suivi.dernierM > 0) sb.append("{\"m\":").append(Math.round(suivi.dernierM)).append(",\"compte\":").append(suivi.dernierM > suivi.seuil).append('}'); else sb.append("null");
        sb.append(",\"essai\":").append(suivi.essaiEnCours());
        sb.append(",\"enr\":").append(e == null ? "null" : e.etatJson(maintenant)).append('}');
        return sb.toString();
    }

    static String etatHorsService(Context c) {
        return "{\"service\":false,\"aReprendre\":" + aReprendre(c) + ",\"h10\":{\"etat\":0,\"texte\":\"H10 arrêtée\",\"nom\":"
                + Json.texte(c.getSharedPreferences("reglages", MODE_PRIVATE).getString("h10Nom", "")) + ",\"fc\":0,\"depuis\":0},\"enr\":null}";
    }

    void regler(int seuil, int rearme, boolean bip) {
        suivi.regler(seuil, rearme, bip);
    }

    void activite(String a) { Enregistreur e = enr; if (e != null) { e.activite(a); h.post(() -> majNotification(true)); } }

    void essai(boolean marche) {
        if (marche) suivi.essaiDemarrer(); else suivi.essaiArreter();
        h.post(this::ajuster);
    }

    String essaiJson() { return suivi.essaiJson(); }

    /**
     * Fin de l'enregistrement. Renvoie le nom du fichier écrit (« rec-<début>.json »), ou "" si l'écriture a échoué
     * (dans ce cas rien n'est perdu : l'enregistrement continue et le journal reste).
     */
    synchronized String terminer() {
        Enregistreur e = enr;
        if (e == null) return "";
        String nom = ecrireEnregistrement(dossier, e.debut, e.terminer(System.currentTimeMillis(), "APK " + version));
        if (nom.isEmpty()) return "";
        enr = null;
        e.fermerJournal(true);
        Brut b = brut;
        brut = null;
        if (b != null) b.fermer();
        h.post(this::ajuster);
        return nom;
    }

    /** Déconnecte la ceinture et arrête le service. Refusé pendant un enregistrement. */
    boolean arreterSiLibre() {
        if (enr != null) return false;
        h.post(this::arreter);
        return true;
    }

    private void arreter() {
        h.removeCallbacksAndMessages(null);
        try { lien.arreter(); } catch (Exception ignored) {}
        relacher();
        premierPlan = false;
        try { stopForeground(true); } catch (Exception ignored) {}
        stopSelf();
    }
}
