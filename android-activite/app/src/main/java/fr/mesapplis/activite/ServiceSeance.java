package fr.mesapplis.activite;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationRequest;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.speech.tts.TextToSpeech;
import android.os.Vibrator;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Le service permanent de séance : c'est lui qui tient la ceinture H10 et qui enregistre, même écran éteint
 * et téléphone dans le sac. Android le garde en vie grâce à sa notification permanente.
 *
 * - Il tourne dès qu'on connecte la ceinture ; pendant un enregistrement, il empêche le téléphone de s'endormir.
 * - Chaque seconde est écrite tout de suite dans « seance-en-cours.txt ». Si l'appli est tuée, Android relance
 *   le service (ou l'appli le relance à son ouverture) et l'enregistrement reprend à partir de ce journal.
 * - À la fin, l'enregistrement complet est écrit dans « enregistrements/rec-<début>.json » ; la page l'ajoute à l'Historique.
 * - Étape 4 : pour une course, il écoute aussi le GPS (un point par seconde), écran éteint. Le calcul est dans Parcours ;
 *   tous les points sont gardés dans « enregistrements/gps-<début>.txt ».
 * - Fantôme de course (version 1.10) : la page écrit la course de référence dans « fantome-en-cours.json » ; le service compare
 *   chaque seconde et annonce l'écart à la voix, écran éteint. Le calcul est dans Fantome.
 */
public class ServiceSeance extends Service implements LienH10.Ecoute {
    static final String ACTION_CONNECTER = "fr.mesapplis.activite.CONNECTER";
    static final String ACTION_DEMARRER = "fr.mesapplis.activite.DEMARRER";
    static final String ACTION_REPRENDRE = "fr.mesapplis.activite.REPRENDRE";
    static final String ACTION_TOUR = "fr.mesapplis.activite.TOUR";
    static final String ACTION_VOIX = "fr.mesapplis.activite.VOIX";
    static final String EXTRA_ACT = "act";
    static final String EXTRA_GPS = "gps";
    static final String FANTOME = "fantome-en-cours.json";
    /** États du GPS pour l'écran. */
    static final int GPS_ARRET = 0, GPS_RECHERCHE = 1, GPS_OK = 2, GPS_ETEINT = 3, GPS_SANS_DROIT = 4, GPS_LIMITE = 5;
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
    private volatile Parcours gps;                      // parcours GPS de la séance en cours, s'il y en a un
    private LocationManager positions;
    private boolean gpsEcoute = false, gpsLimite = false, gpsKm = true;
    private int typePremierPlan = -1;
    private long gpsEssai = 0;
    private volatile Fantome fantome;                   // la course contre laquelle on court, s'il y en a une
    private TextToSpeech voix;
    private boolean voixPrete = false, fantomeArriveDit = false;
    private String aDire = null, rythme = "m500";
    private volatile boolean voixOn = true, prevOn = true;   // 1.13 : voix et prévision, coupables pendant la course
    private long annonceRang = -1;
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
    static boolean droitGps(Context c) { return c.checkSelfPermission("android.permission.ACCESS_FINE_LOCATION") == PackageManager.PERMISSION_GRANTED; }
    static File dossierEnregistrements(File dossier) { return new File(dossier, "enregistrements"); }
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
            long fin = e.finConnue();
            String parcours = null;
            try {
                Parcours p = Parcours.relire(Parcours.fichierDe(dossierEnregistrements(dossier), e.debut));
                if (p != null) { fin = Math.max(fin, p.finConnue()); parcours = avecFantome(p.resumeJson(fin), lireFantome(dossier)); }
            } catch (Exception ignored) {}
            String nom = ecrireEnregistrement(dossier, e.debut, e.terminer(fin, "APK (séance clôturée après coup)", parcours));
            if (!nom.isEmpty()) { journal.delete(); new File(dossier, FANTOME).delete(); }
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
        suivi.regler(p.getInt("impSeuil", 2500), p.getInt("impRearme", 1800), p.getBoolean("impBip", true), p.getInt("impEcart", 400));
        lien = new LienH10(this, this);
        try { son = new ToneGenerator(AudioManager.STREAM_MUSIC, 90); } catch (Exception e) { son = null; }
        cloturerVieuxJournal(this);
        try { enr = Enregistreur.reprendre(new File(dossier, JOURNAL), System.currentTimeMillis()); } catch (Exception e) { enr = null; }
        positions = (LocationManager) getSystemService(LOCATION_SERVICE);
        gpsKm = p.getBoolean("gpsKm", true);
        Enregistreur e = enr;
        if (e != null) { try { gps = Parcours.reprendre(Parcours.fichierDe(dossierEnregistrements(dossier), e.debut), System.currentTimeMillis()); } catch (Exception x) { gps = null; } }
        rythme = p.getString("fantomeRythme", "m500");
        voixOn = p.getBoolean("voixOn", true); prevOn = p.getBoolean("prevOn", true);
        if (gps != null) chargerFantome();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int id) {
        String action = intent == null ? null : intent.getAction();
        boolean avecGps = ACTION_DEMARRER.equals(action) && enr == null && intent.getBooleanExtra(EXTRA_GPS, false);
        // bouton « Tour » de la notification : le service tourne déjà, il n'y a rien d'autre à faire
        if (ACTION_TOUR.equals(action) && premierPlan && enr != null) { tour(); return START_STICKY; }
        if (ACTION_VOIX.equals(action) && premierPlan) { basculerVoix(); return START_STICKY; }
        if (!mettreAuPremierPlan(avecGps || gps != null)) { stopSelf(); return START_NOT_STICKY; }
        if (ACTION_DEMARRER.equals(action) && enr == null) {
            try {
                long debut = System.currentTimeMillis();
                String act = intent.getStringExtra(EXTRA_ACT);
                enr = Enregistreur.nouveau(new File(dossier, JOURNAL), debut, act);
                if (avecGps) {
                    SharedPreferences p = getSharedPreferences("reglages", MODE_PRIVATE);
                    gpsKm = p.getBoolean("gpsKm", true);
                    try { gps = Parcours.nouveau(Parcours.fichierDe(dossierEnregistrements(dossier), debut), debut, "velo".equals(act) ? 28 : 12, p.getString("gpsSource", "sat")); } catch (Exception x) { gps = null; }
                    rythme = p.getString("fantomeRythme", "m500");
                    voixOn = p.getBoolean("voixOn", true); prevOn = p.getBoolean("prevOn", true);
                    if (gps != null) chargerFantome();
                }
            } catch (Exception e) {
                enr = null;
            }
        }
        if (enr == null && !ACTION_CONNECTER.equals(action) && !ACTION_DEMARRER.equals(action)) {
            // relancé par Android sans rien à reprendre : on s'arrête
            if (lien.etat() == LienH10.ARRET) { arreter(); return START_NOT_STICKY; }
        }
        Enregistreur e = enr;
        if (e != null && brut == null && LienH10.droit(this)) brut = Brut.ouvrir(new File(new File(dossier, "enregistrements"), "brut-" + e.debut + ".bin"), e.debut);
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
        ecouterGps(false);
        Parcours g = gps;
        gps = null;
        if (g != null) g.fermer();                       // le fichier du tracé reste : la séance pourra reprendre
        try { if (voix != null) voix.shutdown(); } catch (Exception ignored) {}
        voix = null;
        relacher();
        try { if (son != null) son.release(); } catch (Exception ignored) {}
        if (actif == this) actif = null;
        super.onDestroy();
    }

    // ---------- Premier plan, veille, notification ----------

    /**
     * Android exige de dire pourquoi le service tourne : « appareil connecté » (ceinture) et, pour une course, « position ».
     * Le type « position » n'est accepté que si l'appli est à l'écran à ce moment-là (ou si la position est autorisée « toujours ») :
     * après une relance par Android en arrière-plan, on continue sans lui (GPS limité) et on le redemande au retour de l'écran.
     */
    private boolean mettreAuPremierPlan(boolean avecGps) {
        int base = LienH10.droit(this) ? ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE : 0;
        boolean veutGps = avecGps && droitGps(this);
        if (veutGps && poser(base | ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)) { gpsLimite = false; return true; }
        if (base != 0 && poser(base)) { gpsLimite = veutGps; return true; }
        return false;                                    // par ex. autorisation Bluetooth retirée
    }

    private boolean poser(int type) {
        try {
            startForeground(ID_NOTIF, notification(), type);
            premierPlan = true;
            typePremierPlan = type;
            return true;
        } catch (Exception e) {
            return false;
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
        boolean avecGps = gps != null;
        int voulu = (LienH10.droit(this) ? ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE : 0) | (avecGps && droitGps(this) ? ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION : 0);
        if (premierPlan && voulu != typePremierPlan && (!gpsLimite || MainActivity.visible)) mettreAuPremierPlan(avecGps);
        ecouterGps(avecGps);
        majNotification(true);
    }

    // ---------- GPS ----------

    private final LocationListener ecouteGps = new LocationListener() {
        @Override public void onLocationChanged(Location l) { position(l); }
        @Override public void onProviderEnabled(String f) { h.post(() -> majNotification(false)); }
        @Override public void onProviderDisabled(String f) { h.post(() -> majNotification(false)); }
        @Override public void onStatusChanged(String f, int etat, Bundle b) { }
    };

    private void ecouterGps(boolean oui) {
        if (positions == null) return;
        if (!oui) {
            if (gpsEcoute) { try { positions.removeUpdates(ecouteGps); } catch (Exception ignored) {} gpsEcoute = false; }
            return;
        }
        if (gpsEcoute || !droitGps(this)) return;
        gpsEssai = System.currentTimeMillis();
        try {
            boolean melange = "mix".equals(getSharedPreferences("reglages", MODE_PRIVATE).getString("gpsSource", "sat")) && positions.hasProvider(LocationManager.FUSED_PROVIDER);
            LocationRequest r = new LocationRequest.Builder(1000L).setQuality(LocationRequest.QUALITY_HIGH_ACCURACY).setMinUpdateIntervalMillis(500L).build();
            positions.requestLocationUpdates(melange ? LocationManager.FUSED_PROVIDER : LocationManager.GPS_PROVIDER, r, getMainExecutor(), ecouteGps);
            gpsEcoute = true;
        } catch (Exception e) {
            gpsEcoute = false;                           // GPS absent ou refusé : on réessaiera
        }
    }

    private void position(Location l) {
        Parcours p = gps;
        if (p == null || enr == null || l == null) return;
        long maintenant = System.currentTimeMillis();
        int satellites = 0;
        try { Bundle x = l.getExtras(); if (x != null) satellites = x.getInt("satellites", 0); } catch (Exception ignored) {}
        double vitesse = l.hasSpeed() && !(l.hasSpeedAccuracy() && l.getSpeedAccuracyMetersPerSecond() > 2) ? l.getSpeed() : -1;
        int r = p.point(maintenant - p.debut, l.getLatitude(), l.getLongitude(), l.hasAccuracy() ? l.getAccuracy() : 0, l.hasAltitude() ? l.getAltitude() : 0, vitesse, satellites);
        if ((r & Parcours.PREMIER) != 0) {
            vibrer(new long[]{0, 150, 120, 150});
            if (fantome != null && !"non".equals(rythme)) dire("GPS prêt. Le fantôme part avec toi.");
            h.post(() -> majNotification(false));
        }
        if ((r & Parcours.KM) != 0 && gpsKm) vibrer(new long[]{0, 500, 200, 500});
    }

    private void vibrer(long[] motif) {
        try {
            Vibrator vb = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vb != null) vb.vibrate(VibrationEffect.createWaveform(motif, -1));
        } catch (Exception ignored) {}
    }

    private boolean gpsAllume() {
        try { return positions != null && positions.isLocationEnabled() && positions.isProviderEnabled(LocationManager.GPS_PROVIDER); } catch (Exception e) { return false; }
    }

    private int gpsEtat(long maintenant) {
        Parcours p = gps;
        if (p == null) return GPS_ARRET;
        if (!droitGps(this)) return GPS_SANS_DROIT;
        if (!gpsAllume()) return GPS_ETEINT;
        if (p.frais(maintenant)) return GPS_OK;
        return gpsLimite ? GPS_LIMITE : GPS_RECHERCHE;
    }

    private static String texteGps(int etat) {
        switch (etat) {
            case GPS_OK: return "GPS prêt";
            case GPS_RECHERCHE: return "recherche des satellites…";
            case GPS_ETEINT: return "⚠️ position éteinte sur le téléphone";
            case GPS_SANS_DROIT: return "⚠️ position non autorisée";
            case GPS_LIMITE: return "⚠️ GPS en pause : ouvre l'appli";
            default: return "";
        }
    }

    static String kmTexte(double metres) { return String.format(Locale.FRANCE, "%.2f km", metres / 1000.0); }
    static String allureTexte(double metresParSeconde) {
        if (!(metresParSeconde > 0.4)) return "–";
        int s = (int) Math.round(1000.0 / metresParSeconde);
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60) + " /km";
    }

    /** Tour marqué à la main (bouton de l'écran ou de la notification). */
    boolean tour() {
        Parcours p = gps;
        if (p == null || enr == null) return false;
        p.tour(System.currentTimeMillis() - p.debut, fcFraiche());
        vibrer(new long[]{0, 80});
        h.post(() -> majNotification(true));
        return true;
    }

    /** L'écran de l'appli vient de revenir : si le GPS avait été limité (service relancé en arrière-plan), on le remet en route. */
    void ecranRevenu() { if (gps != null && (gpsLimite || !gpsEcoute)) h.post(this::ajuster); }

    void reglerGps(boolean km) { gpsKm = km; }

    // ---------- Fantôme de course ----------

    static Fantome lireFantome(File dossier) {
        try {
            File f = new File(dossier, FANTOME);
            if (!f.exists() || f.length() < 20) return null;
            byte[] b = new byte[(int) Math.min(f.length(), 2000000)];
            try (FileInputStream in = new FileInputStream(f)) { int lu = 0; while (lu < b.length) { int n = in.read(b, lu, b.length - lu); if (n < 0) break; lu += n; } }
            return Fantome.lire(new String(b, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    /** Range le fantôme dans le résumé du parcours, pour que le bilan puisse être refait plus tard. */
    static String avecFantome(String parcours, Fantome f) {
        if (parcours == null || f == null || !parcours.endsWith("}")) return parcours;
        try { new org.json.JSONObject(f.json()); } catch (Exception e) { return parcours; }   // jamais de texte abîmé dans l'enregistrement
        return parcours.substring(0, parcours.length() - 1) + ",\"fantome\":" + f.json() + "}";
    }

    private void chargerFantome() {
        fantome = lireFantome(dossier);
        annonceRang = -1;
        fantomeArriveDit = false;
        if (fantome != null && voix == null) {
            try {
                voix = new TextToSpeech(this, etat -> {
                    if (etat != TextToSpeech.SUCCESS || voix == null) return;
                    try {
                        voix.setLanguage(Locale.FRANCE);
                        voix.setSpeechRate(1.1f);
                        // comme un guidage : passe par-dessus la musique, aussi dans les écouteurs
                        voix.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                    } catch (Exception ignored) {}
                    voixPrete = true;
                    if (aDire != null) { String t = aDire; aDire = null; dire(t); }
                });
            } catch (Exception e) { voix = null; }
        }
    }

    /** Bouton 🔊 / 🔇 (écran 2 et notification) : coupe ou remet toutes les annonces. */
    boolean basculerVoix() {
        voixOn = !voixOn;
        getSharedPreferences("reglages", MODE_PRIVATE).edit().putBoolean("voixOn", voixOn).apply();
        if (!voixOn) { aDire = null; try { if (voix != null) voix.stop(); } catch (Exception ignored) {} }
        majNotification(true);
        return voixOn;
    }

    /** Bouton 🧮 : montre ou cache la prévision (le calcul continue pour le bilan). */
    boolean basculerPrev() {
        prevOn = !prevOn;
        getSharedPreferences("reglages", MODE_PRIVATE).edit().putBoolean("prevOn", prevOn).apply();
        return prevOn;
    }

    private void dire(String texte) {
        if (!voixOn) return;
        try {
            if (voixPrete && voix != null) voix.speak(texte, TextToSpeech.QUEUE_ADD, null, "fantome");
            else aDire = texte;
        } catch (Exception ignored) {}
    }

    /** Numéro de l'annonce où on en est, selon le rythme choisi : la voix parle quand il augmente. */
    private long rang(double sec, double metres) {
        switch (rythme) {
            case "km1": return (long) (metres / 1000);
            case "min1": return (long) (sec / 60);
            case "min2": return (long) (sec / 120);
            default: return (long) (metres / 500);
        }
    }

    /** Une fois par seconde pendant une course avec fantôme. */
    private void suivreFantome(long maintenant) {
        Parcours g = gps;
        Fantome f = fantome;
        if (g == null || f == null || enr == null || !g.aPosition()) return;
        double sec = (maintenant - g.debut) / 1000.0 - g.premier(), d = g.distance();
        f.noter(sec, d);
        if (f.fini(sec) && !fantomeArriveDit) {
            fantomeArriveDit = true;
            if (annonceRang >= 0 && !"non".equals(rythme)) dire("Le fantôme est arrivé. " + Fantome.phrase(f.ecartM(sec, d), f.ecartS(sec, d)));
            annonceRang = rang(sec, d);
            return;
        }
        long r = rang(sec, d);
        if (annonceRang < 0) { annonceRang = r; return; }   // départ ou reprise : pas d'annonce tout de suite
        if (r > annonceRang) {
            annonceRang = r;
            double[] pv = prevOn ? f.prevision(sec, d) : null;
            if (!"non".equals(rythme)) dire(("km1".equals(rythme) ? "Kilomètre " + r + ". " : "") + Fantome.phrase(f.ecartM(sec, d), f.ecartS(sec, d)) + (pv != null ? " " + Fantome.phrasePrev(pv[0], pv[1]) : ""));
        }
    }

    void reglerFantome(String r) { rythme = r == null ? "m500" : r; annonceRang = -1; }

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
        Parcours g = gps;
        long maintenant = System.currentTimeMillis();
        String titre = e != null ? "● " + nomAct(e.activite()) + " — enregistrement" : "Ceinture H10";
        String texte;
        if (e != null && g != null) {
            int etat = gpsEtat(maintenant);
            texte = (g.aPosition() ? kmTexte(g.distance()) + " · " + allureTexte(g.allure(maintenant)) + " · " : "") + (f > 0 ? "❤️ " + f + " · " : "") + (etat == GPS_OK ? (g.nbTours() > 0 ? "tour " + (g.nbTours() + 1) : "GPS ok") : texteGps(etat));
        } else {
            texte = (f > 0 ? "❤️ " + f + " · " : "") + (e != null ? "⚡ " + e.impacts() + " impacts · " : "") + texteLien();
        }
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
        if (e != null && g != null) {
            PendingIntent pt = PendingIntent.getService(this, 1, new Intent(this, ServiceSeance.class).setAction(ACTION_TOUR), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.addAction(new Notification.Action.Builder(null, "🚩 Tour", pt).build());
            if (fantome != null) {
                PendingIntent pv = PendingIntent.getService(this, 2, new Intent(this, ServiceSeance.class).setAction(ACTION_VOIX), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                b.addAction(new Notification.Action.Builder(null, voixOn ? "🔇 Couper la voix" : "🔊 Remettre la voix", pv).build());
            }
        }
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
            Parcours g = gps;
            if (g != null) {
                g.tic(maintenant);
                if (!gpsEcoute && maintenant - gpsEssai > 10000) ecouterGps(true);   // autorisation donnée ou GPS rallumé entre-temps
                suivreFantome(maintenant);
            }
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
        Parcours g = gps;
        int etatGps = gpsEtat(maintenant);
        sb.append(",\"gps\":");
        if (g == null) sb.append("null");
        else {
            Fantome f = fantome;
            sb.append("{\"etat\":").append(etatGps).append(",\"texte\":").append(Json.texte(texteGps(etatGps))).append(",\"p\":").append(g.etatJson(maintenant))
              .append(",\"voix\":").append(voixOn).append(",\"prevOn\":").append(prevOn);
            if (f == null || !g.aPosition()) sb.append(",\"f\":null}");
            else { double sec = (maintenant - g.debut) / 1000.0 - g.premier(), d = g.distance(), td = g.tourDist();
                sb.append(",\"f\":").append(f.etatJson(sec, d, f.prevision(sec, d), td > 0 ? 0 : -1, td)).append('}'); }
        }
        sb.append(",\"enr\":").append(e == null ? "null" : e.etatJson(maintenant)).append('}');
        return sb.toString();
    }

    static String etatHorsService(Context c) {
        return "{\"service\":false,\"aReprendre\":" + aReprendre(c) + ",\"h10\":{\"etat\":0,\"texte\":\"H10 arrêtée\",\"nom\":"
                + Json.texte(c.getSharedPreferences("reglages", MODE_PRIVATE).getString("h10Nom", "")) + ",\"fc\":0,\"depuis\":0},\"enr\":null}";
    }

    void regler(int seuil, int rearme, boolean bip, int ecart) {
        suivi.regler(seuil, rearme, bip, ecart);
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
        long fin = System.currentTimeMillis();
        Parcours g = gps;
        String nom = ecrireEnregistrement(dossier, e.debut, e.terminer(fin, "APK " + version, g == null ? null : avecFantome(g.resumeJson(fin), fantome)));
        if (nom.isEmpty()) return "";
        fantome = null;
        new File(dossier, FANTOME).delete();
        enr = null;
        e.fermerJournal(true);
        gps = null;
        if (g != null) g.fermer();
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
