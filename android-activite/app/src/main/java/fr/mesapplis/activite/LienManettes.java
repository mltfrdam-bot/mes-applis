package fr.mesapplis.activite;

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

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 🎮 Manettes Zwift Click V2 (essai 16) : jusqu'à 2 manettes pendant la séance.
 * Protocole vérifié le 22/11/2026 avec la page essais/manettes-zwift.html :
 *   service 0xFC82 (ou 00000001-19ca-4651-86e5-fa29dcdd09d1), caractéristiques …0002 (notifications), …0004 (indications), …0003 (écriture) ;
 *   on écrit « RideOn », puis chaque changement de boutons arrive en message 0x23 : champ 1 (protobuf) = carte des boutons, bit à 0 = appuyé.
 * La manette de gauche répète les appuis de celle de droite au même instant : un même bouton n'est compté qu'une fois en 300 ms.
 */
final class LienManettes {
    interface Ecoute { void bouton(String nom); void etatManettesChange(); default void reconnectee() { } }

    private static final UUID S_FC82 = UUID.fromString("0000fc82-0000-1000-8000-00805f9b34fb");
    private static final UUID S_ZWIFT = UUID.fromString("00000001-19ca-4651-86e5-fa29dcdd09d1");
    private static final UUID C_ASYNC = UUID.fromString("00000002-19ca-4651-86e5-fa29dcdd09d1");
    private static final UUID C_ECRIRE = UUID.fromString("00000003-19ca-4651-86e5-fa29dcdd09d1");
    private static final UUID C_SYNC = UUID.fromString("00000004-19ca-4651-86e5-fa29dcdd09d1");
    private static final UUID D_NOTIF = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final int ZWIFT = 0x094A;
    static final int MAX = 2;

    /** Bits de la carte des boutons → nom court envoyé à la séance. */
    private static final int[] BITS = { 0x1, 0x2, 0x4, 0x8, 0x10, 0x20, 0x40, 0x80, 0x100, 0x200, 0x1000, 0x2000 };
    private static final String[] NOMS = { "GAUCHE", "HAUT", "DROITE", "BAS", "A", "B", "Y", "Z", "MOINS", "MOINS", "PLUS", "PLUS" };

    private final Context ctx;
    private final Ecoute ecoute;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final List<Manette> manettes = new ArrayList<>();
    private final Map<String, Long> dernierAppui = new HashMap<>();
    private BluetoothLeScanner scanner;
    private boolean voulu = false, rechercheEnCours = false, sansFiltre = false;
    private final java.util.LinkedHashSet<String> vus = new java.util.LinkedHashSet<>();   // diagnostic : appareils Bluetooth vus
    private String derniere = "";
    private final Runnable relancer = this::chercher;
    private final Runnable finRecherche = this::arreterRecherche;

    private final class Manette {
        final String adresse; String nom = "Manette"; BluetoothGatt gatt; boolean prete = false, perdue = false; int batterie = -1; int carte = 0xFFFFFFFF;
        final ArrayDeque<Runnable> file = new ArrayDeque<>(); boolean occupe = false;
        Manette(String a) { adresse = a; }
    }

    LienManettes(Context c, Ecoute e) { ctx = c.getApplicationContext(); ecoute = e; }

    synchronized int pretes() { int n = 0; for (Manette m : manettes) if (m.prete) n++; return n; }

    synchronized String etatJson() {
        StringBuilder sb = new StringBuilder("{\"voulu\":").append(voulu).append(",\"droit\":").append(LienH10.droit(ctx)).append(",\"cherche\":").append(rechercheEnCours)
                .append(",\"dernier\":").append(Json.texte(derniere));
        synchronized (vus) { sb.append(",\"vus\":").append(Json.texte(String.join(", ", vus))); }
        sb.append(",\"l\":[");
        for (int i = 0; i < manettes.size(); i++) { Manette m = manettes.get(i); if (i > 0) sb.append(',');
            sb.append("{\"nom\":").append(Json.texte(m.nom)).append(",\"prete\":").append(m.prete).append(",\"batt\":").append(m.batterie).append('}'); }
        return sb.append("]}").toString();
    }

    void demarrer() { if (voulu) return; voulu = true; chercher(); h.postDelayed(entretien, 20000); }

    private final Runnable entretien = new Runnable() { @Override public void run() {
        if (!voulu) return;
        synchronized (LienManettes.this) { for (Manette m : manettes) if (m.prete && m.gatt != null) { final BluetoothGatt g = m.gatt; commande(m, () -> { if (!lireBatterie(g)) suite(m); }); } }
        h.postDelayed(this, 20000);
    } };

    private static final UUID S_BATT = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb"), C_BATT = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    private static boolean lireBatterie(BluetoothGatt g) { try { BluetoothGattService s = g.getService(S_BATT); BluetoothGattCharacteristic c = s == null ? null : s.getCharacteristic(C_BATT); return c != null && g.readCharacteristic(c); } catch (Exception e) { return false; } }

    void arreter() {
        voulu = false;
        h.removeCallbacksAndMessages(null);
        arreterRecherche();
        synchronized (this) { for (Manette m : manettes) fermer(m); manettes.clear(); }
        ecoute.etatManettesChange();
    }

    // ---------- Recherche ----------
    private void chercher() {
        h.removeCallbacks(relancer);
        if (!voulu) return;
        synchronized (this) { if (manettes.size() >= MAX) return; }
        if (!LienH10.droit(ctx)) { h.postDelayed(relancer, 5000); ecoute.etatManettesChange(); return; }
        BluetoothAdapter a = LienH10.adaptateur(ctx);
        if (a == null || !a.isEnabled()) { h.postDelayed(relancer, 10000); return; }
        try {
            scanner = a.getBluetoothLeScanner();
            if (scanner == null) { h.postDelayed(relancer, 10000); return; }
            // essai 17 : recherche SANS filtre (avec filtre, les manettes n'apparaissaient pas), tri fait dans onScanResult.
            // Écran éteint, Android met en pause les recherches sans filtre : on alterne donc avec une recherche filtrée.
            sansFiltre = !sansFiltre;
            ScanSettings reg = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            if (sansFiltre) scanner.startScan(null, reg, recherche);
            else scanner.startScan(Arrays.asList(new ScanFilter.Builder().setDeviceName("Zwift Click").build(), new ScanFilter.Builder().setServiceUuid(new ParcelUuid(S_FC82)).build(),
                    new ScanFilter.Builder().setManufacturerData(ZWIFT, new byte[] { 0 }, new byte[] { 0 }).build()), reg, recherche);
            rechercheEnCours = true;
            h.removeCallbacks(finRecherche);
            h.postDelayed(finRecherche, 20000);
        } catch (Exception e) { h.postDelayed(relancer, 10000); }
    }

    private void arreterRecherche() {
        h.removeCallbacks(finRecherche);
        boolean etait = rechercheEnCours;
        rechercheEnCours = false;
        try { if (scanner != null && etait) scanner.stopScan(recherche); } catch (Exception ignored) {}
        if (voulu) { synchronized (this) { if (manettes.size() < MAX) h.postDelayed(relancer, 3000); } }
    }

    private final ScanCallback recherche = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult r) {
            final BluetoothDevice d = r.getDevice();
            android.bluetooth.le.ScanRecord rec = r.getScanRecord();
            String n = rec != null ? rec.getDeviceName() : null;
            boolean zwift = (n != null && n.toLowerCase(java.util.Locale.ROOT).contains("zwift"))
                    || (rec != null && rec.getManufacturerSpecificData(ZWIFT) != null)
                    || (rec != null && rec.getServiceUuids() != null && rec.getServiceUuids().contains(new ParcelUuid(S_FC82)));
            if (n != null && !n.isEmpty()) synchronized (vus) { vus.add(n + (zwift ? " ✅" : "")); while (vus.size() > 12) vus.remove(vus.iterator().next()); }
            if (!zwift) return;
            final String nom = n == null ? "Zwift Click" : n;
            h.post(() -> {
                if (!voulu) return;
                synchronized (LienManettes.this) {
                    for (Manette m : manettes) if (m.adresse.equals(d.getAddress())) return;
                    if (manettes.size() >= MAX) return;
                    Manette m = new Manette(d.getAddress());
                    m.nom = nom + " n°" + (manettes.size() + 1);
                    manettes.add(m);
                    connecter(m, d);
                    if (manettes.size() >= MAX) { rechercheEnCours = false; h.removeCallbacks(finRecherche); try { scanner.stopScan(recherche); } catch (Exception ignored) {} }
                }
                ecoute.etatManettesChange();
            });
        }
        @Override public void onScanFailed(int code) { h.post(() -> { rechercheEnCours = false; if (voulu) h.postDelayed(relancer, 10000); }); }
    };

    // ---------- Connexion ----------
    private void connecter(Manette m, BluetoothDevice d) {
        try { m.gatt = d.connectGatt(ctx, false, new Rappel(m), BluetoothDevice.TRANSPORT_LE); } catch (Exception e) { m.gatt = null; }
    }

    private void fermer(Manette m) {
        m.prete = false; m.file.clear(); m.occupe = false;
        if (m.gatt != null) { try { m.gatt.disconnect(); } catch (Exception ignored) {} try { m.gatt.close(); } catch (Exception ignored) {} m.gatt = null; }
    }

    /** Coupure : on se reconnecte tout seul (connexion « automatique » d'Android, sans limite de temps). */
    private void perdue(Manette m) {
        if (!voulu) return;
        BluetoothGatt g = m.gatt;
        m.prete = false; m.file.clear(); m.occupe = false;
        try { if (g != null) g.close(); } catch (Exception ignored) {}
        m.gatt = null;
        m.perdue = true;
        // La manette s'endort après ~60 s sans appui (comportement normal des Zwift Click) : on reste en connexion directe,
        // relancée en boucle, pour qu'elle soit reprise dès qu'un appui la réveille.
        h.postDelayed(() -> {
            if (!voulu || m.gatt != null) return;
            BluetoothAdapter a = LienH10.adaptateur(ctx);
            if (a == null) return;
            try { m.gatt = a.getRemoteDevice(m.adresse).connectGatt(ctx, false, new Rappel(m), BluetoothDevice.TRANSPORT_LE); } catch (Exception ignored) {}
        }, 300);
        ecoute.etatManettesChange();
    }

    private void commande(Manette m, Runnable r) { m.file.add(r); if (!m.occupe) suite(m); }
    private void suite(Manette m) {
        Runnable r = m.file.poll();
        if (r == null) { m.occupe = false; return; }
        m.occupe = true;
        try { r.run(); } catch (Exception e) { suite(m); }
    }

    private final class Rappel extends BluetoothGattCallback {
        final Manette m;
        Rappel(Manette m) { this.m = m; }

        @Override public void onConnectionStateChange(BluetoothGatt g, int statut, int etat) {
            h.post(() -> {
                if (etat == BluetoothProfile.STATE_CONNECTED) { m.gatt = g; try { g.discoverServices(); } catch (Exception ignored) {} }
                else if (etat == BluetoothProfile.STATE_DISCONNECTED) perdue(m);
            });
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int statut) {
            h.post(() -> {
                BluetoothGattService s = g.getService(S_FC82);
                if (s == null) s = g.getService(S_ZWIFT);
                if (s == null) { ecoute.etatManettesChange(); return; }   // manette verrouillée par Zwift
                final BluetoothGattCharacteristic a = s.getCharacteristic(C_ASYNC), sy = s.getCharacteristic(C_SYNC), rx = s.getCharacteristic(C_ECRIRE);
                if (a != null) commande(m, () -> { if (!abonner(g, a, indique(a))) suite(m); });
                if (sy != null) commande(m, () -> { if (!abonner(g, sy, indique(sy))) suite(m); });
                if (rx != null) commande(m, () -> { if (!ecrire(g, rx, "RideOn".getBytes(StandardCharsets.US_ASCII))) suite(m); });
                commande(m, () -> { m.prete = true; boolean retour = m.perdue; m.perdue = false; ecoute.etatManettesChange(); if (retour) h.post(ecoute::reconnectee); suite(m); });
                ecoute.etatManettesChange();
            });
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int statut) { h.post(() -> suite(m)); }
        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int statut) { h.post(() -> suite(m)); }
        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int statut) { try { byte[] v = c.getValue(); if (v != null && v.length > 0) m.batterie = v[0] & 0xFF; } catch (Exception ignored) {} h.post(() -> suite(m)); }
        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] v, int statut) { if (v != null && v.length > 0) m.batterie = v[0] & 0xFF; h.post(() -> suite(m)); }

        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) { recu(m, c.getValue()); }
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] v) { recu(m, v); }
    }

    // ---------- Messages ----------
    private void recu(Manette m, byte[] b) {
        if (b == null || b.length == 0) return;
        if ((b[0] & 0xFF) == 0x19 && b.length > 2) { long[] v = varint(b, 2); if (v[0] <= 100) { m.batterie = (int) v[0]; } return; }
        if ((b[0] & 0xFF) != 0x23) return;
        long carte = carteBoutons(b);
        if (carte < 0) return;
        int avant = m.carte, apres = (int) carte;
        m.carte = apres;
        long now = System.currentTimeMillis();
        for (int i = 0; i < BITS.length; i++) {
            boolean appuye = (apres & BITS[i]) == 0, etait = (avant & BITS[i]) == 0;
            if (!appuye || etait) continue;
            String nom = NOMS[i];
            synchronized (dernierAppui) {
                Long t = dernierAppui.get(nom);
                if (t != null && now - t < 300) continue;     // même appui répété par l'autre manette
                dernierAppui.put(nom, now);
            }
            derniere = nom + " (" + m.nom + ")";
            h.post(() -> ecoute.bouton(nom));
        }
    }

    private static long[] varint(byte[] b, int i) {
        long v = 0; int f = 0, x;
        do { if (i >= b.length) break; x = b[i++] & 0xFF; v |= (long) (x & 0x7F) << f; f += 7; } while ((x & 0x80) != 0 && f < 63);
        return new long[] { v, i };
    }

    /** Champ 1 du message protobuf (la carte des boutons), -1 s'il manque. */
    static long carteBoutons(byte[] b) {
        int i = 1;
        while (i < b.length) {
            long[] k = varint(b, i); i = (int) k[1];
            int champ = (int) (k[0] >>> 3), type = (int) (k[0] & 7);
            if (type == 0) { long[] v = varint(b, i); i = (int) v[1]; if (champ == 1) return v[0]; }
            else if (type == 2) { long[] l = varint(b, i); i = (int) (l[1] + l[0]); }
            else if (type == 5) i += 4; else if (type == 1) i += 8; else break;
        }
        return -1;
    }

    /** Comme Web Bluetooth : indications si la caractéristique ne sait faire que ça, sinon notifications. */
    private static boolean indique(BluetoothGattCharacteristic c) { int p = c.getProperties(); return (p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0 && (p & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0; }

    @SuppressWarnings("deprecation")
    private static boolean ecrire(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] valeur) {
        int type = (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0 ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        if (Build.VERSION.SDK_INT >= 33) return g.writeCharacteristic(c, valeur, type) == 0;
        c.setWriteType(type);
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
}
