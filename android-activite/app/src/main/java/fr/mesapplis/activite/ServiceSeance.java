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
public class ServiceSeance extends Service implements LienH10.Ecoute, LienKickr.Ecoute, LienManettes.Ecoute {
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
    private LienManettes manettes;                      // 🎮 manettes Zwift (essai 16)
    private final java.util.Set<Integer> finDits = new java.util.HashSet<>();
    private long dernierCoaching = 0;
    private String aDire = null, rythme = "m500";
    private volatile boolean voixOn = true, prevOn = true;
    // 1.14 : home-trainer (activité « ht »)
    static final String ACT_HT = "ht";
    private LienKickr kickr;
    private volatile Velo velo;
    private static final Ftms.Mesure SANS_MESURE = new Ftms.Mesure();   // 1.13 : voix et prévision, coupables pendant la course
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
            Velo v = null;
            try { v = Velo.reprendre(Velo.fichierDe(dossierEnregistrements(dossier), e.debut), e.debut); } catch (Exception ignored) {}
            String nom = ecrireEnregistrement(dossier, e.debut, avecVelo(e.terminer(fin, "APK (séance clôturée après coup)", parcours), v));
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
        kickr = new LienKickr(this, this);
        manettes = new LienManettes(this, this);
        if (e != null) demarrerManettes();
        if (e != null && ACT_HT.equals(e.activite())) {
            try { velo = Velo.reprendre(Velo.fichierDe(dossierEnregistrements(dossier), e.debut), e.debut); } catch (Exception x) { velo = null; }
            if (velo == null) velo = Velo.nouveau(Velo.fichierDe(dossierEnregistrements(dossier), e.debut), e.debut);
            kickr.regler(velo.mode(), velo.consigne());
            kickr.demarrer(p.getString("kickrAdresse", ""));
        }
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
                demarrerManettes();
                if (ACT_HT.equals(act)) {
                    SharedPreferences p = getSharedPreferences("reglages", MODE_PRIVATE);
                    velo = Velo.nouveau(Velo.fichierDe(dossierEnregistrements(dossier), debut), debut);
                    velo.regler(Ftms.LIBRE, 0);
                    kickr.regler(Ftms.LIBRE, 0);
                    kickr.demarrer(p.getString("kickrAdresse", ""));
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
        try { if (kickr != null) kickr.arreter(); } catch (Exception ignored) {}
        try { if (manettes != null) manettes.arreter(); } catch (Exception ignored) {}
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

    /** Ajoute la séance de home-trainer (watts, cadence, vitesse à la seconde) à l'enregistrement. */
    static String avecVelo(String enregistrement, Velo v) {
        if (v == null || enregistrement == null || !enregistrement.endsWith("}")) return enregistrement;
        String j = v.resumeJson();
        try { new org.json.JSONObject(j); } catch (Exception e) { return enregistrement; }
        return enregistrement.substring(0, enregistrement.length() - 1) + ",\"velo\":" + j + "}";
    }

    /** Range le fantôme dans le résumé du parcours, pour que le bilan puisse être refait plus tard. */
    static String avecFantome(String parcours, Fantome f) {
        if (parcours == null || f == null || !parcours.endsWith("}")) return parcours;
        try { new org.json.JSONObject(f.json()); } catch (Exception e) { return parcours; }   // jamais de texte abîmé dans l'enregistrement
        return parcours.substring(0, parcours.length() - 1) + ",\"fantome\":" + f.json() + "}";
    }

    /** 🔧 Pourquoi le fantôme est (ou n'est pas) chargé : affiché dans le détail de la séance et dit par la voix. */
    private volatile String fantomeDiag = "";

    private void chargerFantome() {
        fantome = lireFantome(dossier);
        try {
            File ff = new File(dossier, FANTOME);
            if (fantome != null) fantomeDiag = "fantôme chargé : " + fantome.nom + ", " + fantome.dist + " m en " + fantome.temps() + " s";
            else if (!ff.exists()) fantomeDiag = "aucun fichier de fantôme (pas de fantôme choisi pour cette activité)";
            else if (ff.length() < 20) fantomeDiag = "fichier de fantôme vide (" + ff.length() + " octets) : pas de fantôme choisi, ou fantôme sans tracé GPS";
            else { byte[] b = new byte[(int) Math.min(160, ff.length())]; try (FileInputStream in = new FileInputStream(ff)) { in.read(b); } fantomeDiag = "fichier de fantôme illisible (" + ff.length() + " octets) : " + new String(b, StandardCharsets.UTF_8).replace("\"", "'"); }
        } catch (Exception e) { fantomeDiag = "erreur en lisant le fantôme : " + e; }
        annonceRang = -1;
        fantomeArriveDit = false;
        finDits.clear();
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

    static boolean seanceEnCours() { ServiceSeance s = actif; return s != null && s.enr != null; }

    /** 💧 Une fois par minute : si on est en retard sur l'eau de la séance, vibration (+ voix si choisi). */
    private void eauSeance(long debut) {
        try {
            String t = RappelEau.seanceSuivi(this, debut);
            if (t == null) return;
            android.os.Vibrator vb = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vb != null) vb.vibrate(android.os.VibrationEffect.createWaveform(new long[] { 0, 300, 200, 300, 200, 300 }, -1));
            if (RappelEau.modeRappelSeance(this) >= 2) { initVoix(); if (voixPrete && voix != null) voix.speak(t, TextToSpeech.QUEUE_ADD, null, "eau"); else aDire = t; }
        } catch (Exception ignored) {}
    }

    private void initVoix() {
        if (voix != null) return;
        try {
            voix = new TextToSpeech(this, etat -> {
                if (etat != TextToSpeech.SUCCESS || voix == null) return;
                try {
                    voix.setLanguage(Locale.FRANCE);
                    voix.setSpeechRate(1.1f);
                    voix.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                } catch (Exception ignored) {}
                voixPrete = true;
                if (aDire != null) { String x = aDire; aDire = null; try { voix.speak(x, TextToSpeech.QUEUE_ADD, null, "eau"); } catch (Exception ignored) {} }
            });
        } catch (Exception e) { voix = null; }
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
        annoncesFin(f, sec, d);
        long r = rang(sec, d);
        if (annonceRang < 0) { annonceRang = r; return; }   // départ ou reprise : pas d'annonce tout de suite
        if (r > annonceRang) {
            annonceRang = r;
            double[] pv = prevOn ? f.prevision(sec, d) : null;
            if (!"non".equals(rythme)) dire(("km1".equals(rythme) ? "Kilomètre " + r + ". " : "") + Fantome.phrase(f.ecartM(sec, d), f.ecartS(sec, d)) + (pv != null ? " " + Fantome.phrasePrev(pv[0], pv[1]) : ""));
        }
    }

    // ---------- 🎮 Manettes et 🏁 fin de fantôme (essai 16) ----------
    private org.json.JSONObject cfgManettes() { try { return new org.json.JSONObject(getSharedPreferences("reglages", MODE_PRIVATE).getString("manettes", "{}")); } catch (Exception e) { return new org.json.JSONObject(); } }

    private void demarrerManettes() { try { if (manettes != null && cfgManettes().optBoolean("on", false)) manettes.demarrer(); } catch (Exception ignored) {} }

    @Override public void etatManettesChange() { }

    /** 🎮 Manette reconnectée après sa mise en veille : double vibration (+ bip si choisi) → tu peux réappuyer. */
    @Override public void reconnectee() {
        if (enr == null) return;
        vibrer(new long[]{0, 60, 120, 60});
        if (cfgManettes().optBoolean("bip", false)) { try { if (son != null) son.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 160); } catch (Exception ignored) {} }
    }

    /** Un bouton de manette : l'action choisie dans ⚙️ Réglages → 🎮 Manettes. */
    @Override public void bouton(String nom) {
        if (enr == null) return;
        org.json.JSONObject c = cfgManettes();
        String def = "Y".equals(nom) ? "boisson" : "Z".equals(nom) ? "fantome" : "A".equals(nom) ? "etat" : "B".equals(nom) ? "conseil" : "PLUS".equals(nom) ? "tour" : "rien";
        String action = c.optJSONObject("b") != null ? c.optJSONObject("b").optString(nom, def) : def;
        long now = System.currentTimeMillis();
        if (c.optBoolean("bip", false) && !"rien".equals(action)) { try { if (son != null) son.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 120); } catch (Exception ignored) {} }
        switch (action) {
            case "boisson": {
                int ml = c.optInt("ml", 150); String type = c.optString("gourde", "eau"); double su = c.optDouble("s", 0), sl = c.optDouble("sl", 0);
                try {
                    org.json.JSONObject o = new org.json.JSONObject().put("ts", now).put("ml", ml).put("type", type).put("note", "Pendant la séance (manette)");
                    if (!"eau".equals(type)) o.put("kcal", Math.round(ml * su * 4)).put("glucides", Math.round(ml * su * 10) / 10.0);
                    RappelEau.noter(this, o.toString());
                    org.json.JSONArray l = new org.json.JSONArray(getSharedPreferences("reglages", MODE_PRIVATE).getString("manetteEvts", "[]"));
                    l.put(new org.json.JSONObject().put("ts", now).put("k", type).put("q", ml).put("s", su).put("sl", sl));
                    getSharedPreferences("reglages", MODE_PRIVATE).edit().putString("manetteEvts", l.toString()).apply();
                } catch (Exception ignored) {}
                vibrer(new long[]{0, 70});
                break;
            }
            case "tour": tour(); break;
            case "fantome": parler(phraseFantome()); break;
            case "etat": parler(phraseEtat(c)); break;
            case "conseil": parler(phraseConseil(true)); break;
            default: break;
        }
    }

    /** Parole demandée par un bouton : dite même si les annonces automatiques sont coupées. */
    private void parler(String t) {
        if (t == null || t.isEmpty()) return;
        initVoix();
        try { if (voixPrete && voix != null) voix.speak(t, TextToSpeech.QUEUE_FLUSH, null, "manette"); else aDire = t; } catch (Exception ignored) {}
    }

    private static String km(double m) { return String.format(Locale.FRANCE, "%.1f", m / 1000.0).replace(",0", "") + (m >= 1950 ? " kilomètres" : " kilomètre"); }
    private static String allure(double sParKm) { long s = Math.round(sParKm); return (s / 60) + " minutes " + (s % 60 > 0 ? String.valueOf(s % 60) : ""); }
    private static String duree(long s) { return s < 60 ? s + " secondes" : (s / 60) + (s / 60 > 1 ? " minutes" : " minute") + (s % 60 > 0 ? " " + (s % 60) : ""); }

    private String phraseEtat(org.json.JSONObject c) {
        Enregistreur e = enr; if (e == null) return "";
        long sec = (System.currentTimeMillis() - e.debut) / 1000;
        StringBuilder t = new StringBuilder(duree(sec).replace(" secondes", " secondes") + ". ");
        Parcours g = gps;
        if (g != null && g.aPosition() && g.distance() > 50) t.append(km(g.distance())).append(", allure ").append(allure(sec / (g.distance() / 1000.0))).append(". ");
        int f = fcFraiche();
        if (f > 0) { int max = c.optInt("fcMax", 185); double p = f / (double) max; int z = p < 0.6 ? 1 : p < 0.7 ? 2 : p < 0.8 ? 3 : p < 0.9 ? 4 : 5; t.append("Cœur ").append(f).append(", zone ").append(z).append("."); }
        else t.append("Pas de cœur.");
        return t.toString();
    }

    private String phraseFantome() {
        Parcours g = gps; Fantome f = fantome;
        if (g == null || !g.aPosition()) return "Pas de GPS pour l'instant.";
        double sec = (System.currentTimeMillis() - g.debut) / 1000.0 - g.premier(), d = g.distance();
        String base = km(d) + ", allure " + allure(sec / Math.max(0.05, d / 1000.0)) + ".";
        if (f == null) return "Pas de fantôme : " + (fantomeDiag.startsWith("aucun") ? "aucun fantôme choisi." : fantomeDiag.startsWith("fichier de fantôme vide") ? "fantôme vide." : "fantôme illisible.") + " " + base;
        return Fantome.phrase(f.ecartM(sec, d), f.ecartS(sec, d)) + " " + base;
    }

    /** Conseil pour finir devant le fantôme. « demande » = bouton (toujours une réponse) ; sinon annonce automatique. */
    private String phraseConseil(boolean demande) {
        Parcours g = gps; Fantome f = fantome;
        if (g == null || !g.aPosition()) return demande ? "Pas de GPS pour l'instant." : null;
        if (f == null) return demande ? "Pas de fantôme." : null;
        double sec = (System.currentTimeMillis() - g.debut) / 1000.0 - g.premier(), d = g.distance(), reste = f.dist - d;
        if (reste <= 0 || f.fini(sec)) return demande ? "Le fantôme est arrivé. " + Fantome.phrase(f.ecartM(sec, d), f.ecartS(sec, d)) : null;
        double ecart = f.ecartS(sec, d), budget = f.temps() - sec;
        String debut = "Reste " + (reste >= 1000 ? km(reste) : (Math.round(reste / 10) * 10) + " mètres") + ", " + (Math.abs(ecart) < 1 ? "à égalité" : duree(Math.round(Math.abs(ecart))) + (ecart > 0 ? " d'avance" : " de retard")) + ". ";
        double moyActuelle = sec / Math.max(0.05, d / 1000.0);
        double[] pv = f.prevision(sec, d);
        double tenir = pv != null && pv[2] > 0 ? pv[2] : (budget > 0 ? budget / (reste / 1000.0) : -1);
        if (tenir <= 0 || tenir < moyActuelle * 0.8) return debut + "Le fantôme est loin, termine à ton rythme.";
        if (ecart >= 5) return debut + "Maintiens ton allure, autour de " + allure(Math.min(tenir, moyActuelle)) + ".";
        return debut + "Accélère : vise " + allure(tenir) + " au kilomètre.";
    }

    /** Annonces automatiques avant l'arrivée du fantôme : 1 = à 2 km et 1 km ; 2 = + 500 m et 200 m ; 3 = + toutes les 30 s dans le dernier km si en retard. */
    private void annoncesFin(Fantome f, double sec, double d) {
        int niveau = cfgManettes().optInt("finFantome", 1);
        if (niveau <= 0 || f.dist < 2500 || f.fini(sec)) return;
        double reste = f.dist - d;
        int[] seuils = niveau >= 2 ? new int[] { 2000, 1000, 500, 200 } : new int[] { 2000, 1000 };
        for (int s : seuils) {
            if (reste <= s && !finDits.contains(s)) { finDits.add(s); if (reste > s - 250) { String t = phraseConseil(false); if (t != null) parler(t); dernierCoaching = System.currentTimeMillis(); } return; }
        }
        if (niveau >= 3 && reste < 1000 && f.ecartS(sec, d) < 0 && System.currentTimeMillis() - dernierCoaching >= 30000) {
            dernierCoaching = System.currentTimeMillis(); String t = phraseConseil(false); if (t != null) parler(t);
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
            case "ht": return "Home-trainer";
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
        Velo v = velo;
        if (e != null && v != null) {
            Ftms.Mesure m = kickr.mesure();
            texte = (kickr.frais() ? "⚡ " + Math.max(0, m.puissance) + " W · " + (m.cadence >= 0 ? Math.round(m.cadence) + " tr/min · " : "") : "") + (f > 0 ? "❤️ " + f + " · " : "")
                    + texteKickr() + (v.mode() != Ftms.LIBRE ? " · " + texteMode(v.mode(), v.consigne()) : "");
        } else if (e != null && g != null) {
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
            Velo v = velo;
            if (v != null && e != null) { Ftms.Mesure m = kickr.frais() ? kickr.mesure() : SANS_MESURE; synchronized (m) { v.seconde((int) ((maintenant - e.debut) / 1000), m); } }
            if (maintenant - dernierTaux >= 2000) { accTaux = (int) Math.round(accCompte * 1000.0 / Math.max(1, maintenant - dernierTaux)); accCompte = 0; dernierTaux = maintenant; }
            if (++compteur % 5 == 0) majNotification(false);
            if (e != null && compteur % 60 == 0) eauSeance(e.debut);
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
        if (manettes != null) sb.append(",\"man\":").append(manettes.etatJson());
        sb.append(",\"fdiag\":").append(Json.texte(fantomeDiag));
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
        Velo v = velo;
        if (v != null) {
            Ftms.Mesure m = kickr.mesure();
            boolean ok = kickr.frais();
            synchronized (m) {
                sb.append(",\"ht\":{\"etat\":").append(kickr.etat()).append(",\"texte\":").append(Json.texte(texteKickr())).append(",\"nom\":").append(Json.texte(kickr.nom()))
                  .append(",\"message\":").append(Json.texte(kickr.message())).append(",\"ok\":").append(ok)
                  .append(",\"w\":").append(ok ? m.puissance : -1).append(",\"cad\":").append(ok && m.cadence >= 0 ? Math.round(m.cadence) : -1)
                  .append(",\"vit\":").append(ok && m.vitesse >= 0 ? String.format(Locale.ROOT, "%.1f", m.vitesse) : "-1")
                  .append(",\"dist\":").append(Math.round(v.distance())).append(",\"mode\":").append(v.mode()).append(",\"val\":").append(String.format(Locale.ROOT, "%.1f", v.consigne())).append('}');
            }
        }
        sb.append(",\"enr\":").append(e == null ? "null" : e.etatJson(maintenant)).append('}');
        return sb.toString();
    }

    // ---------- Home-trainer ----------

    /** Mode du home-trainer choisi sur l'écran : ERG (watts), pente (%), résistance (%), libre. */
    boolean reglerKickr(int mode, double valeur) {
        Velo v = velo;
        if (v == null) return false;
        v.regler(mode, valeur);
        h.post(() -> { kickr.regler(mode, valeur); majNotification(true); });
        return true;
    }

    private String texteKickr() {
        switch (kickr.etat()) {
            case LienKickr.CONNECTE: return kickr.frais() ? "home-trainer ok" : "home-trainer : pédale pour le réveiller";
            case LienKickr.RECHERCHE: return "recherche du home-trainer…";
            case LienKickr.CONNEXION: case LienKickr.PREPARATION: return "connexion au home-trainer…";
            case LienKickr.PERDU: return "⚠️ home-trainer perdu, reconnexion…";
            case LienKickr.BT_ETEINT: return "⚠️ Bluetooth éteint";
            case LienKickr.SANS_DROIT: return "⚠️ Bluetooth non autorisé";
            default: return "home-trainer arrêté";
        }
    }

    static String texteMode(int mode, double val) {
        switch (mode) {
            case Ftms.ERG: return "ERG " + Math.round(val) + " W";
            case Ftms.PENTE: return "pente " + String.format(Locale.FRANCE, "%.1f", val) + " %";
            case Ftms.RESISTANCE: return "résistance " + Math.round(val) + " %";
            default: return "libre";
        }
    }

    @Override public void mesure(Ftms.Mesure m) { }

    @Override public void etatKickrChange() { h.post(() -> majNotification(false)); }

    @Override
    public void kickrTrouve(String adresse, String nom) {
        getSharedPreferences("reglages", MODE_PRIVATE).edit().putString("kickrAdresse", adresse).putString("kickrNom", nom).apply();
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
        Velo v = velo;
        String nom = ecrireEnregistrement(dossier, e.debut, avecVelo(e.terminer(fin, "APK " + version, g == null ? null : avecFantome(g.resumeJson(fin), fantome)), v));
        if (nom.isEmpty()) return "";
        velo = null;
        if (v != null) { try { kickr.arreter(); } catch (Exception ignored) {} }
        fantome = null;
        try { manettes.arreter(); } catch (Exception ignored) {}
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
