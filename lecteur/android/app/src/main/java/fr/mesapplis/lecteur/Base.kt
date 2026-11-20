package fr.mesapplis.lecteur

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * La seule base de données de l'appli.
 *  - etat   : réglages et reprise de lecture (file d'attente, position)
 *  - pistes : ce qui est connu de chaque fichier (première fois vu, nombre d'écoutes)
 */
class Base private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "lecteur.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE etat (cle TEXT PRIMARY KEY, valeur TEXT)")
        db.execSQL(
            "CREATE TABLE pistes (cle TEXT PRIMARY KEY, vu_le INTEGER NOT NULL DEFAULT 0, " +
                "ecoutes INTEGER NOT NULL DEFAULT 0, derniere_ecoute INTEGER NOT NULL DEFAULT 0)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, ancienne: Int, nouvelle: Int) {
        // Les prochaines versions ajouteront des tables ici, sans jamais rien effacer.
    }

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

    /** Repère les fichiers jamais vus : ils sont marqués « Nouveau » tant qu'ils n'ont pas été écoutés (7 jours max). */
    fun marquerNouveaux(pistes: List<Piste>) {
        if (pistes.isEmpty()) return
        val db = writableDatabase
        val connus = HashMap<String, LongArray>()
        db.rawQuery("SELECT cle, vu_le, ecoutes FROM pistes", null).use { c ->
            while (c.moveToNext()) connus[c.getString(0)] = longArrayOf(c.getLong(1), c.getLong(2))
        }
        // Tout premier lancement : ce qui est déjà là n'est pas « nouveau »
        val premier = lire("premier_scan") == null
        val maintenant = System.currentTimeMillis()
        val limite = maintenant - 7L * 24 * 3600 * 1000
        db.beginTransaction()
        try {
            for (p in pistes) {
                val k = connus[p.cle]
                if (k == null) {
                    val v = ContentValues().apply { put("cle", p.cle); put("vu_le", if (premier) 0L else maintenant) }
                    db.insertWithOnConflict("pistes", null, v, SQLiteDatabase.CONFLICT_IGNORE)
                    p.nouveau = !premier
                } else {
                    p.nouveau = k[0] > limite && k[1] == 0L
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (premier) ecrire("premier_scan", maintenant.toString())
    }

    fun compterEcoute(cle: String) {
        val db = writableDatabase
        db.execSQL("INSERT OR IGNORE INTO pistes(cle, vu_le) VALUES(?, 0)", arrayOf(cle))
        db.execSQL(
            "UPDATE pistes SET ecoutes = ecoutes + 1, derniere_ecoute = ? WHERE cle = ?",
            arrayOf<Any>(System.currentTimeMillis(), cle)
        )
    }

    fun nombrePistesConnues(): Long =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM pistes", null).use { if (it.moveToFirst()) it.getLong(0) else 0 }

    companion object {
        @Volatile private var instance: Base? = null
        fun de(ctx: Context): Base = instance ?: synchronized(this) {
            instance ?: Base(ctx.applicationContext).also { instance = it }
        }
    }
}
