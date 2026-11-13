package fr.mesapplis.activite;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.UUID;

/**
 * Le lien Bluetooth avec la ceinture Polar H10, tenu par le service de séance (donc aussi écran éteint).
 *
 * - 1re fois : recherche de la ceinture (nom qui commence par « Polar »), puis son adresse est retenue.
 * - Connexion : fréquence cardiaque, puis capteur de mouvement (flux PMD, accéléromètre 50 Hz) quand il est demandé.
 * - Signal perdu : reconnexion toute seule, sans fin, tant que le service la veut
 *   (2 essais directs, puis une recherche, et on recommence).
 * Toutes les commandes Bluetooth partent du fil principal, l'une après l'autre (file d'attente).
 */
@SuppressLint("MissingPermission")
final class LienH10 {
    interface Ecoute {
        void fc(int bpm);
        void trame(byte[] donnees, long t);
        void etatChange();
        void adresseTrouvee(String adresse, String nom);
    }

    static final int ARRET = 0, RECHERCHE = 1, CONNEXION = 2, PREPARATION = 3, CONNECTE = 4, PERDUE = 5, BT_ETEINT = 6, SANS_DROIT = 7;

    private static final UUID S_FC = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb");
    private static final UUID C_FC = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb");
    private static final UUID D_NOTIF = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final UUID S_PMD = UUID.fromString("fb005c80-02e7-f387-1cad-8acd2d8df0c8");
    private static final UUID C_PMD_CP = UUID.fromString("fb005c81-02e7-f387-1cad-8acd2d8df0c8");
    private static final UUID C_PMD_D = UUID.fromString("fb005c82-02e7-f387-1cad-8acd2d8df0c8");
    /** Démarrage de l'accéléromètre : 50 Hz, 16 bits, 8 g (validé dans la v31). */
    private static final byte[] ACC_MARCHE = {0x02, 0x02, 0x00, 0x01, 0x32, 0x00, 0x01, 0x01, 0x10, 0x00, 0x02, 0x01, 0x08, 0x00};
    private static final byte[] ACC_ARRET = {0x03, 0x02};

    private final Context ctx;
    private final Ecoute ecoute;
    private final Handler h = new Handler(Looper.getMainLooper());

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic pmdCp;
    private BluetoothLeScanner scanner;
    private boolean rechercheEnCours = false;
    private boolean voulu = false;
    private boolean accVoulu = false;
    private boolean dejaConnecte = false;
    private String adresse = null;
    private int generation = 0;
    private int echecsDirects = 0;

    private volatile int etat = ARRET;
    private volatile String nom = "";
    private volatile boolean accActif = false;
    private volatile boolean pmdPresent = false;
    private volatile String accErreur = "";
    private volatile long depuis = 0;           // début de l'état en cours (horloge du téléphone)

    private final ArrayDeque<Runnable> file = new ArrayDeque<>();
    private boolean occupe = false;
    private final Runnable delaiCommande = this::suite;
    private final Runnable garde = this::gardeConnexion;
    private final Runnable finRecherche = this::arreterRecherche;
    private final Runnable nouvelEssai = this::essayer;

    LienH10(Context ctx, Ecoute ecoute) { this.ctx = ctx.getApplicationContext(); this.ecoute = ecoute; }

    int etat() { return etat; }
    String nom() { return nom; }
    boolean accActif() { return accActif; }
    boolean pmdPresent() { return pmdPresent; }
    String accErreur() { return accErreur; }
    long depuis() { return depuis; }

    static boolean droit(Context c) {
        if (Build.VERSION.SDK_INT < 31) return true;
        return c.checkSelfPermission("android.permission.BLUETOOTH_CONNECT") == PackageManager.PERMISSION_GRANTED
                && c.checkSelfPermission("android.permission.BLUETOOTH_SCAN") == PackageManager.PERMISSION_GRANTED;
    }

    static BluetoothAdapter adaptateur(Context c) {
        BluetoothManager bm = (BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE);
        return bm == null ? null : bm.getAdapter();
    }

    private void passer(int e) {
        if (etat != e) { etat = e; depuis = System.currentTimeMillis(); }
        ecoute.etatChange();
    }

    // ---------- Commandes du service (à appeler depuis le fil principal) ----------

    void demarrer(String adresseConnue) {
        if (adresseConnue != null && !adresseConnue.isEmpty() && adresse == null) adresse = adresseConnue;
        if (voulu) return;
        voulu = true;
        echecsDirects = 0;
        essayer();
    }

    void arreter() {
        voulu = false;
        accVoulu = false;
        h.removeCallbacksAndMessages(null);
        arreterRecherche();
        try {
            if (gatt != null && accActif && pmdCp != null) ecrire(gatt, pmdCp, ACC_ARRET);
        } catch (Exception ignored) {}
        fermer();
        accActif = false;
        passer(ARRET);
    }

    /** Le capteur de mouvement n'est allumé que pendant une séance ou un réglage (économie de pile de la ceinture). */
    void accVouloir(boolean oui) {
        if (accVoulu == oui) return;
        accVoulu = oui;
        if (etat != CONNECTE || gatt == null || pmdCp == null) return;
        final BluetoothGatt g = gatt;
        final int gen = generation;
        if (oui) commande(() -> { if (gen == generation) { accErreur = ""; if (!ecrire(g, pmdCp, ACC_MARCHE)) suite(); } else suite(); });
        else commande(() -> { accActif = false; if (gen != generation || !ecrire(g, pmdCp, ACC_ARRET)) suite(); });
    }

    // ---------- Recherche et connexion ----------

    private void essayer() {
        h.removeCallbacks(nouvelEssai);
        if (!voulu) return;
        if (!droit(ctx)) { passer(SANS_DROIT); h.postDelayed(nouvelEssai, 5000); return; }
        BluetoothAdapter a = adaptateur(ctx);
        if (a == null || !a.isEnabled()) { passer(BT_ETEINT); h.postDelayed(nouvelEssai, 5000); return; }
        if (adresse != null && echecsDirects < 2) {
            try { connecter(a.getRemoteDevice(adresse), true); return; } catch (Exception e) { echecsDirects = 2; }
        }
        chercher(a);
    }

    private void chercher(BluetoothAdapter a) {
        try {
            scanner = a.getBluetoothLeScanner();
            if (scanner == null) { passer(BT_ETEINT); h.postDelayed(nouvelEssai, 5000); return; }
            ScanFilter filtre = new ScanFilter.Builder().setServiceUuid(new ParcelUuid(S_FC)).build();
            ScanSettings reglages = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            scanner.startScan(Collections.singletonList(filtre), reglages, recherche);
            rechercheEnCours = true;
            passer(dejaConnecte ? PERDUE : RECHERCHE);
            h.removeCallbacks(finRecherche);
            h.postDelayed(finRecherche, 30000);
        } catch (Exception e) {
            h.postDelayed(nouvelEssai, 5000);
        }
    }

    /** Fin d'une recherche sans résultat : petite pause, puis on recommence (essais directs d'abord). */
    private void arreterRecherche() {
        h.removeCallbacks(finRecherche);
        boolean etait = rechercheEnCours;
        rechercheEnCours = false;
        try { if (scanner != null && etait) scanner.stopScan(recherche); } catch (Exception ignored) {}
        if (etait && voulu && gatt == null) { echecsDirects = 0; h.postDelayed(nouvelEssai, 3000); }
    }

    private final ScanCallback recherche = new ScanCallback() {
        @Override
        public void onScanResult(int type, ScanResult r) {
            final BluetoothDevice d = r.getDevice();
            String n = r.getScanRecord() != null ? r.getScanRecord().getDeviceName() : null;
            if (n == null) { try { n = d.getName(); } catch (Exception ignored) {} }
            if (n == null || !n.startsWith("Polar")) return;
            if (adresse != null && dejaConnecte && !adresse.equals(d.getAddress())) return;      // on ne change pas de ceinture en pleine séance
            final String nomTrouve = n;
            h.post(() -> {
                if (!rechercheEnCours || !voulu) return;
                rechercheEnCours = false;
                h.removeCallbacks(finRecherche);
                try { scanner.stopScan(recherche); } catch (Exception ignored) {}
                adresse = d.getAddress();
                nom = nomTrouve;
                ecoute.adresseTrouvee(adresse, nomTrouve);
                connecter(d, true);
            });
        }

        @Override
        public void onScanFailed(int code) {
            h.post(() -> { rechercheEnCours = false; h.removeCallbacks(finRecherche); if (voulu) h.postDelayed(nouvelEssai, 5000); });
        }
    };

    private BluetoothDevice appareil;

    private void connecter(BluetoothDevice d, boolean direct) {
        fermer();
        generation++;
        appareil = d;
        passer(dejaConnecte ? PERDUE : CONNEXION);
        try {
            gatt = d.connectGatt(ctx, !direct, new Rappel(generation), BluetoothDevice.TRANSPORT_LE);
        } catch (Exception e) {
            gatt = null;
        }
        h.removeCallbacks(garde);
        h.postDelayed(garde, direct ? 35000 : 120000);
    }

    /** La connexion n'a pas abouti dans le délai : on repart de zéro. */
    private void gardeConnexion() {
        if (!voulu || etat == CONNECTE) return;
        echecsDirects++;
        fermer();
        essayer();
    }

    private void fermer() {
        h.removeCallbacks(garde);
        h.removeCallbacks(delaiCommande);
        file.clear();
        occupe = false;
        pmdCp = null;
        if (gatt != null) {
            try { gatt.disconnect(); } catch (Exception ignored) {}
            try { gatt.close(); } catch (Exception ignored) {}
            gatt = null;
        }
        generation++;
    }

    // ---------- File d'attente des commandes ----------

    private void commande(Runnable r) { file.add(r); if (!occupe) suite(); }

    private void suite() {
        h.removeCallbacks(delaiCommande);
        Runnable r = file.poll();
        if (r == null) { occupe = false; finDeFile(); return; }
        occupe = true;
        h.postDelayed(delaiCommande, 4000);
        try { r.run(); } catch (Exception e) { suite(); }
    }

    private void finDeFile() {
        if (etat == PREPARATION) {
            dejaConnecte = true;
            echecsDirects = 0;
            h.removeCallbacks(garde);
            passer(CONNECTE);
        }
    }

    @SuppressWarnings("deprecation")
    private static boolean ecrire(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] valeur) {
        if (Build.VERSION.SDK_INT >= 33) return g.writeCharacteristic(c, valeur, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0;
        c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        c.setValue(valeur);
        return g.writeCharacteristic(c);
    }

    @SuppressWarnings("deprecation")
    private static boolean abonner(BluetoothGatt g, BluetoothGattCharacteristic c, boolean indication) {
        if (!g.setCharacteristicNotification(c, true)) return false;
        BluetoothGattDescriptor d = c.getDescriptor(D_NOTIF);
        if (d == null) return false;
        byte[] v = indication ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
        if (Build.VERSION.SDK_INT >= 33) return g.writeDescriptor(d, v) == 0;
        d.setValue(v);
        return g.writeDescriptor(d);
    }

    // ---------- Ce que la ceinture nous dit ----------

    private final class Rappel extends BluetoothGattCallback {
        private final int gen;
        Rappel(int gen) { this.gen = gen; }

        @Override
        public void onConnectionStateChange(final BluetoothGatt g, final int statut, final int nouvelEtat) {
            h.post(() -> {
                if (gen != generation) { try { g.close(); } catch (Exception ignored) {} return; }
                if (nouvelEtat == BluetoothProfile.STATE_CONNECTED && statut == BluetoothGatt.GATT_SUCCESS) {
                    passer(dejaConnecte ? PERDUE : PREPARATION);
                    h.postDelayed(() -> { if (gen == generation) { try { if (!g.discoverServices()) perdu(); } catch (Exception e) { perdu(); } } }, 300);
                } else if (nouvelEtat == BluetoothProfile.STATE_DISCONNECTED) {
                    if (etat != CONNECTE && etat != PREPARATION) echecsDirects++;
                    perdu();
                }
            });
        }

        @Override
        public void onServicesDiscovered(final BluetoothGatt g, final int statut) {
            h.post(() -> {
                if (gen != generation) return;
                BluetoothGattService sFc = g.getService(S_FC);
                final BluetoothGattCharacteristic cFc = sFc == null ? null : sFc.getCharacteristic(C_FC);
                if (statut != BluetoothGatt.GATT_SUCCESS || cFc == null) { perdu(); return; }
                etat = PREPARATION;
                BluetoothGattService sPmd = g.getService(S_PMD);
                final BluetoothGattCharacteristic cp = sPmd == null ? null : sPmd.getCharacteristic(C_PMD_CP);
                final BluetoothGattCharacteristic cd = sPmd == null ? null : sPmd.getCharacteristic(C_PMD_D);
                pmdPresent = cp != null && cd != null;
                pmdCp = pmdPresent ? cp : null;
                accActif = false;
                commande(() -> { if (!g.requestMtu(232)) suite(); });
                commande(() -> { if (!abonner(g, cFc, false)) suite(); });
                if (pmdPresent) {
                    commande(() -> { if (!abonner(g, cp, true)) suite(); });
                    commande(() -> { if (!abonner(g, cd, false)) suite(); });
                    if (accVoulu) commande(() -> { accErreur = ""; if (!ecrire(g, cp, ACC_MARCHE)) suite(); });
                }
            });
        }

        private void fait() { h.post(() -> { if (gen == generation) suite(); }); }

        @Override public void onMtuChanged(BluetoothGatt g, int mtu, int statut) { fait(); }
        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int statut) { fait(); }
        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int statut) { fait(); }

        /** Android 13 et plus. */
        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] valeur) {
            recu(c.getUuid(), valeur);
        }

        /** Avant Android 13. */
        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (Build.VERSION.SDK_INT < 33) recu(c.getUuid(), c.getValue());
        }

        private void recu(UUID id, byte[] v) {
            if (gen != generation || v == null) return;
            if (C_FC.equals(id)) {
                int bpm = Trames.fc(v);
                if (bpm > 0) ecoute.fc(bpm);
            } else if (C_PMD_D.equals(id)) {
                if (!accActif && accVoulu) accActif = true;
                ecoute.trame(v, SystemClock.elapsedRealtime());
            } else if (C_PMD_CP.equals(id)) {
                // réponse de la ceinture à une commande : F0, commande, type de mesure, code d'erreur
                if (v.length >= 4 && (v[0] & 0xFF) == 0xF0) {
                    int code = v[3] & 0xFF;
                    if ((v[1] & 0xFF) == 0x02) {
                        if (code == 0 || code == 6) { accActif = true; accErreur = ""; }
                        else { accActif = false; accErreur = "la ceinture refuse le flux (code " + code + ")"; }
                        h.post(ecoute::etatChange);
                    } else if ((v[1] & 0xFF) == 0x03) {
                        accActif = false;
                    }
                }
            }
        }
    }

    /** Lien perdu (ou connexion ratée) : on ferme proprement et on réessaie une seconde plus tard. */
    private void perdu() {
        accActif = false;
        fermer();
        if (!voulu) { passer(ARRET); return; }
        passer(dejaConnecte ? PERDUE : CONNEXION);
        h.removeCallbacks(nouvelEssai);
        h.postDelayed(nouvelEssai, 1000);
    }
}
