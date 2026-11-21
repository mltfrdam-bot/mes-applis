package fr.mesapplis.lecteur

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

class Playlist(val id: String, val nom: String, val nombre: Int, val supprimeeLe: Long, val modifieLe: Long)

class ElementPlaylist(val empreinte: String, val titre: String, val artiste: String)

class LigneStat(val colonnes: List<String>)

/**
 * La seule base de données de l'appli.
 *  - etat            : réglages et reprise de lecture (file d'attente, position)
 *  - fichiers        : chemin → empreinte du contenu (pour ne pas la recalculer)
 *  - morceaux        : tout ce qui est connu d'un morceau, par empreinte (favori, écoutes, position…)
 *  - ecoutes         : chaque écoute avec sa date (statistiques par semaine, fusion exacte)
 *  - playlists, playlist_pistes
 *  - remplacements   : doublon supprimé → version gardée (ses données y ont été reportées)
 *  - traductions     : texte anglais → traduction française (🌐), "" si ce n'est pas de l'anglais
 *  - pistes          : ancienne table de la version 1 (vidée au fur et à mesure de la reprise des données)
 */
class Base private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "lecteur.db", null, 4) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE etat (cle TEXT PRIMARY KEY, valeur TEXT)")
        db.execSQL(
            "CREATE TABLE pistes (cle TEXT PRIMARY KEY, vu_le INTEGER NOT NULL DEFAULT 0, " +
                "ecoutes INTEGER NOT NULL DEFAULT 0, derniere_ecoute INTEGER NOT NULL DEFAULT 0)"
        )
        creerV2(db)
        creerV3(db)
        creerV4(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, ancienne: Int, nouvelle: Int) {
        // On ajoute, on n'efface jamais rien.
        if (ancienne < 2) creerV2(db)
        if (ancienne < 3) creerV3(db)
        if (ancienne < 4) creerV4(db)
    }

    private fun creerV4(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS traductions (texte TEXT PRIMARY KEY, fr TEXT NOT NULL, quand INTEGER NOT NULL DEFAULT 0)")
    }

    private fun creerV3(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS remplacements (ancienne TEXT PRIMARY KEY, nouvelle TEXT NOT NULL, quand INTEGER NOT NULL DEFAULT 0)")
    }

    private fun creerV2(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS fichiers (chemin TEXT PRIMARY KEY, taille INTEGER, modifie INTEGER, empreinte TEXT)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS morceaux (empreinte TEXT PRIMARY KEY, titre TEXT, artiste TEXT, album TEXT, chemin TEXT, " +
                "duree INTEGER NOT NULL DEFAULT 0, vu_le INTEGER NOT NULL DEFAULT 0, ecoutes_base INTEGER NOT NULL DEFAULT 0, " +
                "favori INTEGER NOT NULL DEFAULT 0, favori_le INTEGER NOT NULL DEFAULT 0, position INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS ecoutes (empreinte TEXT NOT NULL, quand INTEGER NOT NULL, PRIMARY KEY (empreinte, quand))")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS playlists (id TEXT PRIMARY KEY, nom TEXT, cree_le INTEGER, modifie_le INTEGER, " +
                "supprimee_le INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS playlist_pistes (playlist TEXT NOT NULL, empreinte TEXT NOT NULL, ordre REAL, " +
                "ajoute_le INTEGER, titre TEXT, artiste TEXT, PRIMARY KEY (playlist, empreinte))"
        )
    }

    // ----- Réglages et état -----

    fun lire(cle: String): String? =
        readableDatabase.rawQuery("SELECT valeur FROM etat WHERE cle=?", arrayOf(cle)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    fun ecrire(cle: String, valeur: String?) {
        if (valeur == null) {
            writableDatabase.delete("etat", "cle=?", arrayOf(cle))
            return
        }
        val v = ContentValues().apply { put("cle", cle); put("valeur", valeur) }
        writableDatabase.insertWithOnConflict("etat", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ----- Bibliothèque -----

    /**
     * Complète chaque piste : empreinte (calculée seulement si [calculer]), favori, écoutes, « Nouveau ».
     * Retourne le nombre de fichiers dont l'empreinte reste à calculer.
     */
    fun completer(ctx: Context, pistes: List<Piste>, calculer: Boolean, progression: ((Int, Int) -> Unit)? = null): Int {
        val db = writableDatabase
        val cache = HashMap<String, Array<Any>>()
        db.rawQuery("SELECT chemin, taille, modifie, empreinte FROM fichiers", null).use { c ->
            while (c.moveToNext()) cache[c.getString(0)] = arrayOf(c.getLong(1), c.getLong(2), c.getString(3) ?: "")
        }
        val manquants = ArrayList<Piste>()
        for (p in pistes) {
            val k = cache[p.cle]
            if (k != null && k[0] == p.taille && k[1] == p.modifie && (k[2] as String).isNotEmpty()) p.empreinte = k[2] as String
            else manquants += p
        }
        if (calculer && manquants.isNotEmpty()) {
            var n = 0
            for (lot in manquants.chunked(50)) {
                val calcules = lot.map { it to (Bibliotheque.empreinte(ctx, it.uri, it.taille) ?: "chemin:${it.cle}") }
                db.beginTransaction()
                try {
                    for ((p, e) in calcules) {
                        p.empreinte = e
                        db.insertWithOnConflict("fichiers", null, ContentValues().apply {
                            put("chemin", p.cle); put("taille", p.taille); put("modifie", p.modifie); put("empreinte", e)
                        }, SQLiteDatabase.CONFLICT_REPLACE)
                    }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
                n += lot.size
                progression?.invoke(n, manquants.size)
            }
        }

        // Morceaux connus
        val connus = HashMap<String, LongArray>()
        val chemins = HashMap<String, String>()
        db.rawQuery("SELECT empreinte, vu_le, ecoutes_base, favori, chemin FROM morceaux", null).use { c ->
            while (c.moveToNext()) {
                connus[c.getString(0)] = longArrayOf(c.getLong(1), c.getLong(2), c.getLong(3))
                chemins[c.getString(0)] = c.getString(4) ?: ""
            }
        }
        // Données de la version 1 (par chemin), reprises une seule fois
        val anciens = HashMap<String, LongArray>()
        db.rawQuery("SELECT cle, vu_le, ecoutes FROM pistes", null).use { c ->
            while (c.moveToNext()) anciens[c.getString(0)] = longArrayOf(c.getLong(1), c.getLong(2))
        }
        val premier = connus.isEmpty() && lire("premier_scan_v2") == null && anciens.isEmpty()
        val maintenant = System.currentTimeMillis()
        db.beginTransaction()
        try {
            for (p in pistes) {
                val e = p.empreinte ?: continue
                if (!connus.containsKey(e)) {
                    val ancien = anciens[p.cle]
                    val vu = when {
                        ancien != null -> ancien[0]
                        premier -> 0L
                        else -> maintenant
                    }
                    val base = ancien?.get(1) ?: 0L
                    db.insertWithOnConflict("morceaux", null, ContentValues().apply {
                        put("empreinte", e); put("titre", p.titre); put("artiste", p.artiste); put("album", p.album)
                        put("chemin", p.cle); put("duree", p.duree); put("vu_le", vu); put("ecoutes_base", base)
                    }, SQLiteDatabase.CONFLICT_IGNORE)
                    if (ancien != null) db.delete("pistes", "cle=?", arrayOf(p.cle))
                    connus[e] = longArrayOf(vu, base, 0)
                    chemins[e] = p.cle
                } else if (chemins[e] != p.cle) {
                    // Fichier renommé ou déplacé : on garde tout, on met juste à jour le chemin
                    db.update("morceaux", ContentValues().apply {
                        put("chemin", p.cle); put("titre", p.titre); put("artiste", p.artiste); put("album", p.album); put("duree", p.duree)
                    }, "empreinte=?", arrayOf(e))
                    chemins[e] = p.cle
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (premier && pistes.isNotEmpty()) ecrire("premier_scan_v2", maintenant.toString())

        val nb = HashMap<String, Long>()
        db.rawQuery("SELECT empreinte, COUNT(*) FROM ecoutes GROUP BY empreinte", null).use { c ->
            while (c.moveToNext()) nb[c.getString(0)] = c.getLong(1)
        }
        val limite = maintenant - 7L * 24 * 3600 * 1000
        for (p in pistes) {
            val e = p.empreinte
            if (e == null) { p.nouveau = false; continue }
            val k = connus[e] ?: continue
            p.favori = k[2] == 1L
            p.ecoutes = (k[1] + (nb[e] ?: 0L)).toInt()
            p.nouveau = k[0] > limite && p.ecoutes == 0
        }
        return if (calculer) 0 else manquants.size
    }

    private fun assurerMorceau(db: SQLiteDatabase, e: String, titre: String, artiste: String) {
        db.insertWithOnConflict("morceaux", null, ContentValues().apply {
            put("empreinte", e); put("titre", titre); put("artiste", artiste); put("vu_le", 0L)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun compterEcoute(e: String, titre: String, artiste: String) {
        val db = writableDatabase
        assurerMorceau(db, e, titre, artiste)
        db.insertWithOnConflict("ecoutes", null, ContentValues().apply {
            put("empreinte", e); put("quand", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    /** Favori en un appui : retourne le nouvel état. */
    fun basculerFavori(e: String, titre: String, artiste: String): Boolean {
        val db = writableDatabase
        assurerMorceau(db, e, titre, artiste)
        val actuel = db.rawQuery("SELECT favori FROM morceaux WHERE empreinte=?", arrayOf(e)).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        val nouveau = if (actuel == 1) 0 else 1
        db.update("morceaux", ContentValues().apply { put("favori", nouveau); put("favori_le", System.currentTimeMillis()) }, "empreinte=?", arrayOf(e))
        return nouveau == 1
    }

    fun estFavori(e: String): Boolean =
        readableDatabase.rawQuery("SELECT favori FROM morceaux WHERE empreinte=?", arrayOf(e)).use { it.moveToFirst() && it.getInt(0) == 1 }

    fun position(e: String): Long =
        readableDatabase.rawQuery("SELECT position FROM morceaux WHERE empreinte=?", arrayOf(e)).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    fun sauverPosition(e: String, ms: Long) {
        writableDatabase.update("morceaux", ContentValues().apply { put("position", ms) }, "empreinte=?", arrayOf(e))
    }

    // ----- Doublons -----

    /** Table « ancienne empreinte → empreinte gardée », chaînes résolues. */
    fun remplacements(db: SQLiteDatabase = readableDatabase): Map<String, String> {
        val brut = HashMap<String, String>()
        db.rawQuery("SELECT ancienne, nouvelle FROM remplacements", null).use { c -> while (c.moveToNext()) brut[c.getString(0)] = c.getString(1) }
        val res = HashMap<String, String>()
        for (a in brut.keys) {
            var n = brut[a]!!
            var garde = 0
            while (brut.containsKey(n) && garde++ < 20) n = brut[n]!!
            if (n != a) res[a] = n
        }
        return res
    }

    /** Oublie la base (empreinte → données) d'un fichier supprimé du téléphone. */
    fun oublierChemin(chemin: String) {
        writableDatabase.delete("fichiers", "chemin=?", arrayOf(chemin))
    }

    /**
     * Doublon supprimé : tout ce qu'il avait (écoutes, favori, reprise, playlists) passe sur la version gardée.
     * Rien n'est perdu ; le lien est gardé pour que les anciennes sauvegardes fusionnent au bon endroit.
     */
    fun reporter(ancienne: String, nouvelle: String, titre: String, artiste: String): Report {
        val r = Report()
        if (ancienne == nouvelle) return r
        val db = writableDatabase
        db.beginTransaction()
        try {
            assurerMorceau(db, nouvelle, titre, artiste)
            val vieux = db.rawQuery("SELECT favori, favori_le, ecoutes_base, position FROM morceaux WHERE empreinte=?", arrayOf(ancienne)).use {
                if (it.moveToFirst()) longArrayOf(it.getLong(0), it.getLong(1), it.getLong(2), it.getLong(3)) else null
            }
            r.ecoutes = db.rawQuery("SELECT COUNT(*) FROM ecoutes WHERE empreinte=?", arrayOf(ancienne)).use { if (it.moveToFirst()) it.getInt(0) else 0 }
            db.execSQL("INSERT OR IGNORE INTO ecoutes (empreinte, quand) SELECT ?, quand FROM ecoutes WHERE empreinte=?", arrayOf(nouvelle, ancienne))
            db.delete("ecoutes", "empreinte=?", arrayOf(ancienne))
            if (vieux != null) {
                r.ecoutes += vieux[2].toInt()
                db.execSQL("UPDATE morceaux SET ecoutes_base = ecoutes_base + ? WHERE empreinte=?", arrayOf<Any>(vieux[2], nouvelle))
                if (vieux[0] == 1L) {
                    val deja = db.rawQuery("SELECT favori FROM morceaux WHERE empreinte=?", arrayOf(nouvelle)).use { it.moveToFirst() && it.getInt(0) == 1 }
                    if (!deja) {
                        db.update("morceaux", ContentValues().apply { put("favori", 1); put("favori_le", vieux[1]) }, "empreinte=?", arrayOf(nouvelle))
                        r.favori = true
                    }
                }
                if (vieux[3] > 0) db.execSQL("UPDATE morceaux SET position=? WHERE empreinte=? AND position=0", arrayOf<Any>(vieux[3], nouvelle))
                db.delete("morceaux", "empreinte=?", arrayOf(ancienne))
            }
            val listes = ArrayList<String>()
            db.rawQuery("SELECT playlist FROM playlist_pistes WHERE empreinte=?", arrayOf(ancienne)).use { c -> while (c.moveToNext()) listes += c.getString(0) }
            for (pl in listes) {
                val dejaLa = db.rawQuery("SELECT 1 FROM playlist_pistes WHERE playlist=? AND empreinte=?", arrayOf(pl, nouvelle)).use { it.moveToFirst() }
                if (dejaLa) db.delete("playlist_pistes", "playlist=? AND empreinte=?", arrayOf(pl, ancienne))
                else db.update("playlist_pistes", ContentValues().apply { put("empreinte", nouvelle) }, "playlist=? AND empreinte=?", arrayOf(pl, ancienne))
                r.playlists++
            }
            db.insertWithOnConflict("remplacements", null, ContentValues().apply {
                put("ancienne", ancienne); put("nouvelle", nouvelle); put("quand", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.update("remplacements", ContentValues().apply { put("nouvelle", nouvelle) }, "nouvelle=?", arrayOf(ancienne))
            db.delete("remplacements", "ancienne=nouvelle", null)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return r
    }

    class Report { var ecoutes = 0; var favori = false; var playlists = 0 }

    // ----- Playlists -----

    fun playlists(avecSupprimees: Boolean = false): List<Playlist> {
        val res = ArrayList<Playlist>()
        readableDatabase.rawQuery(
            "SELECT p.id, p.nom, (SELECT COUNT(*) FROM playlist_pistes x WHERE x.playlist = p.id), p.supprimee_le, p.modifie_le " +
                "FROM playlists p " + (if (avecSupprimees) "" else "WHERE p.supprimee_le = 0 ") + "ORDER BY p.nom COLLATE NOCASE", null
        ).use { c -> while (c.moveToNext()) res += Playlist(c.getString(0), c.getString(1) ?: "", c.getInt(2), c.getLong(3), c.getLong(4)) }
        return res
    }

    fun playlist(id: String): Playlist? = playlists(true).firstOrNull { it.id == id }

    fun creerPlaylist(nom: String): String {
        val id = UUID.randomUUID().toString()
        val t = System.currentTimeMillis()
        writableDatabase.insert("playlists", null, ContentValues().apply {
            put("id", id); put("nom", nom); put("cree_le", t); put("modifie_le", t)
        })
        return id
    }

    fun renommerPlaylist(id: String, nom: String) {
        writableDatabase.update("playlists", ContentValues().apply { put("nom", nom); put("modifie_le", System.currentTimeMillis()) }, "id=?", arrayOf(id))
    }

    /** Suppression douce : la playlist va dans la corbeille, rien n'est effacé. */
    fun supprimerPlaylist(id: String, supprimer: Boolean) {
        writableDatabase.update("playlists", ContentValues().apply {
            put("supprimee_le", if (supprimer) System.currentTimeMillis() else 0L); put("modifie_le", System.currentTimeMillis())
        }, "id=?", arrayOf(id))
    }

    fun elements(id: String): List<ElementPlaylist> {
        val res = ArrayList<ElementPlaylist>()
        readableDatabase.rawQuery("SELECT empreinte, titre, artiste FROM playlist_pistes WHERE playlist=? ORDER BY ordre", arrayOf(id)).use { c ->
            while (c.moveToNext()) res += ElementPlaylist(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "")
        }
        return res
    }

    /** Ajoute à la fin ; retourne le nombre réellement ajouté (les doublons sont ignorés). */
    fun ajouter(id: String, pistes: List<Piste>): Int {
        val db = writableDatabase
        var ordre = db.rawQuery("SELECT COALESCE(MAX(ordre), 0) FROM playlist_pistes WHERE playlist=?", arrayOf(id)).use { if (it.moveToFirst()) it.getDouble(0) else 0.0 }
        var n = 0
        val t = System.currentTimeMillis()
        db.beginTransaction()
        try {
            for (p in pistes) {
                val e = p.empreinte ?: continue
                ordre += 1
                val r = db.insertWithOnConflict("playlist_pistes", null, ContentValues().apply {
                    put("playlist", id); put("empreinte", e); put("ordre", ordre); put("ajoute_le", t); put("titre", p.titre); put("artiste", p.artiste)
                }, SQLiteDatabase.CONFLICT_IGNORE)
                if (r != -1L) n++
            }
            db.update("playlists", ContentValues().apply { put("modifie_le", t) }, "id=?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return n
    }

    fun retirer(id: String, e: String) {
        writableDatabase.delete("playlist_pistes", "playlist=? AND empreinte=?", arrayOf(id, e))
        writableDatabase.update("playlists", ContentValues().apply { put("modifie_le", System.currentTimeMillis()) }, "id=?", arrayOf(id))
    }

    fun reordonner(id: String, empreintes: List<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            empreintes.forEachIndexed { i, e ->
                db.update("playlist_pistes", ContentValues().apply { put("ordre", (i + 1).toDouble()) }, "playlist=? AND empreinte=?", arrayOf(id, e))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    // ----- Statistiques -----

    private val sousTotal = "SELECT m.empreinte AS e, m.titre AS titre, m.artiste AS artiste, " +
        "m.ecoutes_base + (SELECT COUNT(*) FROM ecoutes x WHERE x.empreinte = m.empreinte) AS n, " +
        "(SELECT MAX(quand) FROM ecoutes x WHERE x.empreinte = m.empreinte) AS der FROM morceaux m"

    fun statsTitres(limite: Int): List<LigneStat> {
        val res = ArrayList<LigneStat>()
        readableDatabase.rawQuery("SELECT titre, artiste, n, der FROM ($sousTotal) WHERE n > 0 ORDER BY n DESC, der DESC LIMIT $limite", null).use { c ->
            while (c.moveToNext()) res += LigneStat(listOf(c.getString(0) ?: "", c.getString(1) ?: "", c.getInt(2).toString(), Format.date(c.getLong(3)).take(10)))
        }
        return res
    }

    fun statsArtistes(limite: Int): List<LigneStat> {
        val res = ArrayList<LigneStat>()
        readableDatabase.rawQuery(
            "SELECT CASE WHEN artiste IS NULL OR artiste = '' THEN '(inconnu)' ELSE artiste END AS a, SUM(n), COUNT(*) " +
                "FROM ($sousTotal) WHERE n > 0 GROUP BY a ORDER BY 2 DESC LIMIT $limite", null
        ).use { c -> while (c.moveToNext()) res += LigneStat(listOf(c.getString(0), c.getInt(1).toString(), c.getInt(2).toString())) }
        return res
    }

    fun statsSemaines(limite: Int): List<LigneStat> {
        val res = ArrayList<LigneStat>()
        readableDatabase.rawQuery(
            "SELECT strftime('%Y-%W', e.quand / 1000, 'unixepoch', 'localtime') AS s, MIN(e.quand), COUNT(*), SUM(COALESCE(m.duree, 0)), COUNT(DISTINCT e.empreinte) " +
                "FROM ecoutes e LEFT JOIN morceaux m ON m.empreinte = e.empreinte GROUP BY s ORDER BY s DESC LIMIT $limite", null
        ).use { c ->
            while (c.moveToNext()) {
                val cal = java.util.Calendar.getInstance(java.util.Locale.FRANCE).apply {
                    timeInMillis = c.getLong(1)
                    firstDayOfWeek = java.util.Calendar.MONDAY
                    set(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.MONDAY)
                }
                val lundi = String.format("%02d/%02d/%d", cal.get(java.util.Calendar.DAY_OF_MONTH), cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.YEAR))
                res += LigneStat(listOf("Sem. du $lundi", c.getInt(2).toString(), Format.dureeLongue(c.getLong(3)), c.getInt(4).toString()))
            }
        }
        return res
    }

    fun totaux(): String {
        val db = readableDatabase
        fun n(sql: String) = db.rawQuery(sql, null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
        return "${n("SELECT COUNT(*) FROM morceaux")} morceaux connus · ${n("SELECT COUNT(*) FROM morceaux WHERE favori = 1")} favoris · " +
            "${n("SELECT COUNT(*) FROM playlists WHERE supprimee_le = 0")} playlists · " +
            "${n("SELECT COUNT(*) FROM ecoutes") + n("SELECT COALESCE(SUM(ecoutes_base), 0) FROM morceaux")} écoutes"
    }

    companion object {
        @Volatile private var instance: Base? = null
        fun de(ctx: Context): Base = instance ?: synchronized(this) {
            instance ?: Base(ctx.applicationContext).also { instance = it }
        }
    }
}
