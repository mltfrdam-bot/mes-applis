package fr.mesapplis.activite;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.UUID;

/**
 * Le lien Bluetooth avec le home-trainer (Wahoo KICKR Core ou tout home-trainer « FTMS »), tenu par le service de séance.
 * Même fonctionnement que la ceinture (LienH10) : recherche, adresse retenue, reconnexion sans fin, commandes l'une après l'autre.
 * Une fois connecté : mesures (watts, cadence, vitesse), prise de contrôle, puis le mode choisi (ERG, pente, résistance).
 * Après une reconnexion, le dernier mode est renvoyé tout seul.
 */
@SuppressLint("MissingPermission")
final class LienKickr {
    interface Ecoute {
        void mesure(Ftms.Mesure m);
        void etatKickrChange();
        void kickrTrouve(String adresse, String nom);
    }

    static final int ARRET = 0, RECHERCHE = 1, CONNEXION = 2, PREPARATION = 3, CONNECTE = 4, PERDU = 5, BT_ETEINT = 6, SANS_DROIT = 7;

    private static final UUID S_FTMS = UUID.fromString("00001826-0000-1000-8000-00805f9b34fb");
    private static final UUID C_DONNEES = UUID.fromString("00002ad2-0000-1000-8000-00805f9b34fb");
    private static final UUID C_CONTROLE = UUID.fromString("00002ad9-0000-1000-8000-00805f9b34fb");
    private static final UUID C_PLAGE = UUID.fromString("00002ad6-0000-1000-8000-00805f9b34fb");
    private static final UUID D_NOTIF = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Context ctx;
    private final Ecoute ecoute;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Ftms.Mesure mesure = new Ftms.Mesure();

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic controle;
    private BluetoothLeScanner scanner;
    private boolean rechercheEnCours = false, voulu = false, dejaConnecte = false;
    private String adresse = null;
    private int generation = 0, echecsDirects = 0;

    private volatile int etat = ARRET;
    private volatile String nom = "";
    private volatile String message = "";
    private volatile long derniereMesure = 0;
    private int min = 0, max = 1000;                     // plage de résistance (dixièmes)
    private int mode = Ftms.LIBRE;
    private double valeur = 0;

    private final ArrayDeque<Runnable> file = new ArrayDeque<>();
    private boolean occupe = false;
    private final Runnable delaiCommande = this::suite;
    private final Runnable garde = this::gardeConnexion;
    private final Runnable finRecherche = this::arreterRecherche;
    private final Runnable nouvelEssai = this::essayer;

    LienKickr(Context ctx, Ecoute ecoute) { this.ctx = ctx.getApplicationContext(); this.ecoute = ecoute; }

    int etat() { return etat; }
    String nom() { return nom; }
    String message() { return message; }
    /** Des mesures récentes (moins de 3 s) ? */
    boolean frais() { return etat == CONNECTE && System.currentTimeMillis() - derniereMesure < 3000; }
    Ftms.Mesure mesure() { return mesure; }

    private void passer(int e) { etat = e; ecoute.etatKickrChange(); }

    // ---------- Commandes du service (fil principal) ----------

    void demarrer(String adresseConnue) {
        if (adresseConnue != null && !adresseConnue.isEmpty() && adresse == null) adresse = adresseConnue;
        if (voulu) return;
        voulu = true;
        echecsDirects = 0;
        essayer();
    }

    void arreter() {
        voulu = false;
        h.removeCallbacksAndMessages(null);
        arreterRecherche();
        fermer();
        passer(ARRET);
    }

    /** Change le mode (ERG, pente, résistance, libre). Envoyé tout de suite si le home-trainer est là, sinon à la connexion. */
    void regler(int mode, double valeur) {
        this.mode = mode;
        this.valeur = valeur;
        if (etat != CONNECTE || gatt == null || controle == null) return;
        envoyerMode();
    }

    private void envoyerMode() {
        final byte[] c = Ftms.commande(mode, valeur, min, max);
        if (c == null) return;
        final BluetoothGatt g = gatt;
        final BluetoothGattCharacteristic cp = controle;
        final int gen = generation;
        commande(() -> { if (gen != generation || !ecrire(g, cp, c)) suite(); });
    }

    // ---------- Recherche et connexion ----------

    private void essayer() {
        h.removeCallbacks(nouvelEssai);
        if (!voulu) return;
        if (!LienH10.droit(ctx)) { passer(SANS_DROIT); h.postDelayed(nouvelEssai, 5000); return; }
        BluetoothAdapter a = LienH10.adaptateur(ctx);
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
            ScanFilter filtre = new ScanFilter.Builder().setServiceUuid(new ParcelUuid(S_FTMS)).build();
            ScanSettings reglages = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            scanner.startScan(Collections.singletonList(filtre), reglages, recherche);
            rechercheEnCours = true;
            passer(dejaConnecte ? PERDU : RECHERCHE);
            h.removeCallbacks(finRecherche);
            h.postDelayed(finRecherche, 30000);
        } catch (Exception e) {
            h.postDelayed(nouvelEssai, 5000);
        }
    }

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
            if (adresse != null && dejaConnecte && !adresse.equals(d.getAddress())) return;
            final String nomTrouve = n == null ? "home-trainer" : n;
            h.post(() -> {
                if (!rechercheEnCours || !voulu) return;
                rechercheEnCours = false;
                h.removeCallbacks(finRecherche);
                try { scanner.stopScan(recherche); } catch (Exception ignored) {}
                adresse = d.getAddress();
                nom = nomTrouve;
                ecoute.kickrTrouve(adresse, nomTrouve);
                connecter(d, true);
            });
        }

        @Override
        public void onScanFailed(int code) {
            h.post(() -> { rechercheEnCours = false; h.removeCallbacks(finRecherche); if (voulu) h.postDelayed(nouvelEssai, 5000); });
        }
    };

    private void connecter(BluetoothDevice d, boolean direct) {
        fermer();
        generation++;
        if (nom.isEmpty()) { try { String n = d.getName(); if (n != null) nom = n; } catch (Exception ignored) {} }
        passer(dejaConnecte ? PERDU : CONNEXION);
        try {
            gatt = d.connectGatt(ctx, !direct, new Rappel(generation), BluetoothDevice.TRANSPORT_LE);
        } catch (Exception e) {
            gatt = null;
        }
        h.removeCallbacks(garde);
        h.postDelayed(garde, direct ? 35000 : 120000);
    }

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
        controle = null;
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

    // ---------- Ce que le home-trainer nous dit ----------

    private final class Rappel extends BluetoothGattCallback {
        private final int gen;
        Rappel(int gen) { this.gen = gen; }

        @Override
        public void onConnectionStateChange(final BluetoothGatt g, final int statut, final int nouvelEtat) {
            h.post(() -> {
                if (gen != generation) { try { g.close(); } catch (Exception ignored) {} return; }
                if (nouvelEtat == BluetoothProfile.STATE_CONNECTED && statut == BluetoothGatt.GATT_SUCCESS) {
                    passer(dejaConnecte ? PERDU : PREPARATION);
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
                BluetoothGattService s = g.getService(S_FTMS);
                final BluetoothGattCharacteristic donnees = s == null ? null : s.getCharacteristic(C_DONNEES);
                final BluetoothGattCharacteristic cp = s == null ? null : s.getCharacteristic(C_CONTROLE);
                final BluetoothGattCharacteristic plage = s == null ? null : s.getCharacteristic(C_PLAGE);
                if (statut != BluetoothGatt.GATT_SUCCESS || donnees == null) { message = "Cet appareil ne parle pas le langage des home-trainers (FTMS)."; perdu(); return; }
                etat = PREPARATION;
                controle = cp;
                message = cp == null ? "Le home-trainer ne se laisse pas piloter : mesures seulement." : "";
                commande(() -> { if (!abonner(g, donnees, false)) suite(); });
                if (plage != null) commande(() -> { if (!g.readCharacteristic(plage)) suite(); });
                if (cp != null) {
                    commande(() -> { if (!abonner(g, cp, true)) suite(); });
                    commande(() -> { if (!ecrire(g, cp, Ftms.prendreLaMain())) suite(); });
                    commande(() -> { if (!ecrire(g, cp, Ftms.demarrer())) suite(); });
                    final byte[] c = Ftms.commande(mode, valeur, min, max);
                    if (c != null) commande(() -> { byte[] c2 = Ftms.commande(mode, valeur, min, max); if (c2 == null || !ecrire(g, cp, c2)) suite(); });
                }
            });
        }

        private void fait() { h.post(() -> { if (gen == generation) suite(); }); }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int statut) { fait(); }
        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int statut) { fait(); }

        /** Android 13 et plus. */
        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] valeur, int statut) { lu(c, valeur, statut); }

        /** Avant Android 13. */
        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int statut) { if (Build.VERSION.SDK_INT < 33) lu(c, c.getValue(), statut); }

        private void lu(BluetoothGattCharacteristic c, byte[] v, int statut) {
            if (statut == BluetoothGatt.GATT_SUCCESS && C_PLAGE.equals(c.getUuid())) { int[] p = Ftms.plage(v); if (p != null) { min = p[0]; max = p[1]; } }
            fait();
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] valeur) { recu(c.getUuid(), valeur); }

        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) { if (Build.VERSION.SDK_INT < 33) recu(c.getUuid(), c.getValue()); }

        private void recu(UUID id, byte[] v) {
            if (gen != generation || v == null) return;
            if (C_DONNEES.equals(id)) {
                synchronized (mesure) { if (Ftms.lire(v, mesure)) derniereMesure = System.currentTimeMillis(); }
                ecoute.mesure(mesure);
            } else if (C_CONTROLE.equals(id)) {
                String r = Ftms.reponse(v);
                if (r != null) { message = r; h.post(ecoute::etatKickrChange); }
            }
        }
    }

    private void perdu() {
        fermer();
        if (!voulu) { passer(ARRET); return; }
        passer(dejaConnecte ? PERDU : CONNEXION);
        h.removeCallbacks(nouvelEssai);
        h.postDelayed(nouvelEssai, 1000);
    }
}
