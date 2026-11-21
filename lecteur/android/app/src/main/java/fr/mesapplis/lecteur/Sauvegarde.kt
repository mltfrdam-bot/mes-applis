package fr.mesapplis.lecteur

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Une ligne du descriptif de fusion : rubrique, ce qui s'est passé, et si quelque chose a été ajouté. */
class LigneFusion(val rubrique: String, val texte: String, val ajout: Boolean)

/**
 * Sauvegarde et fusion, comme dans les autres applis de Frédéric :
 * rien n'est jamais écrasé, et le descriptif liste tout ce qui a été ajouté.
 */
object Sauvegarde {
    const val FORMAT = "lecteur-sauvegarde"
    const val DOSSIER = "Download/Lecteur/"
    const val DOSSIER_AUTO = "Download/Lecteur/Auto/"
    private const val GARDEES = 7

    // ----- Export -----

    fun exporter(ctx: Context): JSONObject {
        val db = Base.de(ctx).readableDatabase
        val racine = JSONObject()
        racine.put("format", FORMAT)
        racine.put("version", 1)
        racine.put("cree_le", System.currentTimeMillis())
        racine.put("appareil", "${Build.MANUFACTURER} ${Build.MODEL}")

        val morceaux = JSONArray()
        db.rawQuery("SELECT empreinte, titre, artiste, album, chemin, duree, vu_le, ecoutes_base, favori, favori_le, position FROM morceaux", null).use { c ->
            while (c.moveToNext()) morceaux.put(JSONObject().apply {
                put("e", c.getString(0)); put("titre", c.getString(1) ?: ""); put("artiste", c.getString(2) ?: "")
                put("album", c.getString(3) ?: ""); put("chemin", c.getString(4) ?: ""); put("duree", c.getLong(5))
                put("vu_le", c.getLong(6)); put("ecoutes_base", c.getLong(7)); put("favori", c.getInt(8))
                put("favori_le", c.getLong(9)); put("position", c.getLong(10))
            })
        }
        racine.put("morceaux", morceaux)

        val ecoutes = JSONObject()
        db.rawQuery("SELECT empreinte, quand FROM ecoutes ORDER BY empreinte, quand", null).use { c ->
            while (c.moveToNext()) {
                val e = c.getString(0)
                val t = ecoutes.optJSONArray(e) ?: JSONArray().also { ecoutes.put(e, it) }
                t.put(c.getLong(1))
            }
        }
        racine.put("ecoutes", ecoutes)

        val playlists = JSONArray()
        db.rawQuery("SELECT id, nom, cree_le, modifie_le, supprimee_le FROM playlists", null).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val pistes = JSONArray()
                db.rawQuery("SELECT empreinte, ordre, ajoute_le, titre, artiste FROM playlist_pistes WHERE playlist=? ORDER BY ordre", arrayOf(id)).use { p ->
                    while (p.moveToNext()) pistes.put(JSONObject().apply {
                        put("e", p.getString(0)); put("ordre", p.getDouble(1)); put("ajoute_le", p.getLong(2))
                        put("titre", p.getString(3) ?: ""); put("artiste", p.getString(4) ?: "")
                    })
                }
                playlists.put(JSONObject().apply {
                    put("id", id); put("nom", c.getString(1) ?: ""); put("cree_le", c.getLong(2))
                    put("modifie_le", c.getLong(3)); put("supprimee_le", c.getLong(4)); put("pistes", pistes)
                })
            }
        }
        racine.put("playlists", playlists)

        val remplacements = JSONArray()
        db.rawQuery("SELECT ancienne, nouvelle, quand FROM remplacements", null).use { c ->
            while (c.moveToNext()) remplacements.put(JSONObject().apply { put("a", c.getString(0)); put("n", c.getString(1)); put("quand", c.getLong(2)) })
        }
        racine.put("remplacements", remplacements)

        val reglages = JSONObject()
        db.rawQuery("SELECT cle, valeur FROM etat", null).use { c -> while (c.moveToNext()) reglages.put(c.getString(0), c.getString(1)) }
        racine.put("reglages", reglages)
        return racine
    }

    private fun horodatage() = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.FRANCE).format(Date())

    /** Écrit un fichier dans Téléchargements (visible dans « Mes fichiers »). */
    fun ecrire(ctx: Context, texte: String, dossier: String, nom: String): Uri? {
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, nom)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, dossier)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val cr = ctx.contentResolver
            val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return null
            return try {
                cr.openOutputStream(uri)?.use { it.write(texte.toByteArray(Charsets.UTF_8)) }
                cr.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                uri
            } catch (e: Exception) {
                cr.delete(uri, null, null)
                null
            }
        }
        val dir = java.io.File(ctx.getExternalFilesDir(null), dossier.removePrefix("Download/"))
        dir.mkdirs()
        val f = java.io.File(dir, nom)
        f.writeText(texte)
        return Uri.fromFile(f)
    }

    /** 💾 Sauvegarde manuelle : retourne l'adresse du fichier et son nom. */
    fun sauvegarder(ctx: Context, prefixe: String = "lecteur-sauvegarde"): Pair<Uri?, String> {
        val nom = "$prefixe-${horodatage()}.json"
        return ecrire(ctx, exporter(ctx).toString(1), DOSSIER, nom) to nom
    }

    /** Sauvegarde automatique, une fois par jour, les 7 dernières gardées. */
    fun autoSiBesoin(ctx: Context) {
        val base = Base.de(ctx)
        val derniere = base.lire("auto_le")?.toLongOrNull() ?: 0L
        val maintenant = System.currentTimeMillis()
        if (maintenant - derniere < 20L * 3600 * 1000) return
        val vide = base.readableDatabase.rawQuery("SELECT COUNT(*) FROM morceaux", null).use { it.moveToFirst() && it.getLong(0) == 0L }
        if (vide) return
        val uri = ecrire(ctx, exporter(ctx).toString(), DOSSIER_AUTO, "lecteur-auto-${horodatage()}.json") ?: return
        base.ecrire("auto_le", maintenant.toString())
        base.ecrire("auto_fichier", uri.toString())
        if (Build.VERSION.SDK_INT >= 29) try {
            val anciens = ArrayList<Uri>()
            ctx.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.RELATIVE_PATH}=? AND ${MediaStore.Downloads.DISPLAY_NAME} LIKE 'lecteur-auto-%'",
                arrayOf(DOSSIER_AUTO), "${MediaStore.Downloads.DATE_ADDED} DESC"
            )?.use { c ->
                var i = 0
                while (c.moveToNext()) {
                    if (i++ >= GARDEES) anciens += android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0))
                }
            }
            anciens.forEach { ctx.contentResolver.delete(it, null, null) }
        } catch (e: Exception) { }
    }

    // ----- Fusion -----

    fun lire(ctx: Context, uri: Uri): JSONObject? = try {
        ctx.contentResolver.openInputStream(uri)?.use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
    } catch (e: Exception) { null }

    /** Ajoute ce qui manque, sans jamais rien écraser. Retourne le descriptif. */
    fun fusionner(ctx: Context, s: JSONObject): List<LigneFusion> {
        val lignes = ArrayList<LigneFusion>()
        if (s.optString("format") != FORMAT) {
            lignes += LigneFusion("Fichier", "Ce n'est pas une sauvegarde du Lecteur : rien n'a été modifié.", false)
            return lignes
        }
        lignes += LigneFusion("Sauvegarde", "du ${Format.date(s.optLong("cree_le"))} · ${s.optString("appareil")}", false)

        // Filet de sécurité : l'état actuel est sauvegardé avant la fusion
        val (_, avant) = sauvegarder(ctx, "lecteur-avant-fusion")
        lignes += LigneFusion("Sécurité", "État d'avant la fusion gardé dans $DOSSIER$avant", false)

        val db = Base.de(ctx).writableDatabase
        db.beginTransaction()
        try {
            fusionRemplacements(db, s.optJSONArray("remplacements") ?: JSONArray(), lignes)
            // Les données d'un doublon supprimé vont directement sur la version gardée
            val rempl = Base.de(ctx).remplacements(db)
            val m: (String) -> String = { rempl[it] ?: it }
            fusionMorceaux(db, s.optJSONArray("morceaux") ?: JSONArray(), lignes, m)
            fusionEcoutes(db, s.optJSONObject("ecoutes") ?: JSONObject(), lignes, m)
            fusionPlaylists(db, s.optJSONArray("playlists") ?: JSONArray(), lignes, m)
            fusionReglages(db, s.optJSONObject("reglages") ?: JSONObject(), lignes)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        lignes += LigneFusion("Total", Base.de(ctx).totaux(), false)
        return lignes
    }

    private fun fusionRemplacements(db: SQLiteDatabase, t: JSONArray, lignes: MutableList<LigneFusion>) {
        var n = 0
        for (i in 0 until t.length()) {
            val o = t.getJSONObject(i)
            val a = o.optString("a"); val nv = o.optString("n")
            if (a.isEmpty() || nv.isEmpty() || a == nv) continue
            val r = db.insertWithOnConflict("remplacements", null, ContentValues().apply {
                put("ancienne", a); put("nouvelle", nv); put("quand", o.optLong("quand"))
            }, SQLiteDatabase.CONFLICT_IGNORE)
            if (r != -1L) n++
        }
        if (n > 0) lignes += LigneFusion("Doublons", "+$n doublon(s) supprimé(s) ailleurs : leurs données vont sur la version gardée", true)
    }

    private fun fusionMorceaux(db: SQLiteDatabase, t: JSONArray, lignes: MutableList<LigneFusion>, m: (String) -> String) {
        var ajoutes = 0; var favoris = 0; var completes = 0; var positions = 0; var presents = 0
        for (i in 0 until t.length()) {
            val o = t.getJSONObject(i)
            val e = m(o.optString("e"))
            if (e.isEmpty()) continue
            val actuel = db.rawQuery("SELECT favori, ecoutes_base, position FROM morceaux WHERE empreinte=?", arrayOf(e)).use {
                if (it.moveToFirst()) longArrayOf(it.getLong(0), it.getLong(1), it.getLong(2)) else null
            }
            if (actuel == null) {
                db.insert("morceaux", null, ContentValues().apply {
                    put("empreinte", e); put("titre", o.optString("titre")); put("artiste", o.optString("artiste"))
                    put("album", o.optString("album")); put("chemin", o.optString("chemin")); put("duree", o.optLong("duree"))
                    put("vu_le", o.optLong("vu_le")); put("ecoutes_base", o.optLong("ecoutes_base")); put("favori", o.optInt("favori"))
                    put("favori_le", o.optLong("favori_le")); put("position", o.optLong("position"))
                })
                ajoutes++
                if (o.optInt("favori") == 1) favoris++
                continue
            }
            presents++
            val maj = ContentValues()
            if (o.optInt("favori") == 1 && actuel[0] == 0L) { maj.put("favori", 1); maj.put("favori_le", o.optLong("favori_le")); favoris++ }
            if (o.optLong("ecoutes_base") > actuel[1]) { maj.put("ecoutes_base", o.optLong("ecoutes_base")); completes++ }
            if (actuel[2] == 0L && o.optLong("position") > 0) { maj.put("position", o.optLong("position")); positions++ }
            if (maj.size() > 0) db.update("morceaux", maj, "empreinte=?", arrayOf(e))
        }
        lignes += LigneFusion("Morceaux", listOfNotNull(
            "+$ajoutes ajouté(s)".takeIf { ajoutes > 0 }, "$presents déjà présent(s)".takeIf { presents > 0 },
            "$completes compteur(s) complété(s)".takeIf { completes > 0 }, "$positions reprise(s) ajoutée(s)".takeIf { positions > 0 },
        ).joinToString(" · ").ifEmpty { "Rien à ajouter" }, ajoutes + completes + positions > 0)
        lignes += LigneFusion("Favoris", if (favoris > 0) "+$favoris favori(s) ajouté(s)" else "Rien à ajouter", favoris > 0)
    }

    private fun fusionEcoutes(db: SQLiteDatabase, o: JSONObject, lignes: MutableList<LigneFusion>, m: (String) -> String) {
        var ajoutees = 0; var presentes = 0
        val cles = o.keys()
        while (cles.hasNext()) {
            val brute = cles.next()
            val t = o.optJSONArray(brute) ?: continue
            val e = m(brute)
            for (i in 0 until t.length()) {
                val r = db.insertWithOnConflict("ecoutes", null, ContentValues().apply { put("empreinte", e); put("quand", t.getLong(i)) }, SQLiteDatabase.CONFLICT_IGNORE)
                if (r == -1L) presentes++ else ajoutees++
            }
        }
        lignes += LigneFusion("Écoutes", listOfNotNull(
            "+$ajoutees ajoutée(s)".takeIf { ajoutees > 0 }, "$presentes déjà présente(s)".takeIf { presentes > 0 },
        ).joinToString(" · ").ifEmpty { "Rien à ajouter" }, ajoutees > 0)
    }

    private fun fusionPlaylists(db: SQLiteDatabase, t: JSONArray, lignes: MutableList<LigneFusion>, m: (String) -> String) {
        if (t.length() == 0) { lignes += LigneFusion("Playlists", "Aucune dans la sauvegarde", false); return }
        for (i in 0 until t.length()) {
            val o = t.getJSONObject(i)
            val id = o.optString("id")
            if (id.isEmpty()) continue
            val nom = o.optString("nom")
            val pistes = o.optJSONArray("pistes") ?: JSONArray()
            val actuelle = db.rawQuery("SELECT nom, supprimee_le FROM playlists WHERE id=?", arrayOf(id)).use {
                if (it.moveToFirst()) (it.getString(0) ?: "") to it.getLong(1) else null
            }
            if (actuelle == null) {
                db.insert("playlists", null, ContentValues().apply {
                    put("id", id); put("nom", nom); put("cree_le", o.optLong("cree_le")); put("modifie_le", o.optLong("modifie_le"))
                    put("supprimee_le", o.optLong("supprimee_le"))
                })
                val n = ajouterPistes(db, id, pistes, m)
                lignes += LigneFusion("📃 $nom", "Playlist ajoutée ($n titres)" + (if (o.optLong("supprimee_le") > 0) ", dans la corbeille" else ""), true)
                continue
            }
            val n = ajouterPistes(db, id, pistes, m)
            val details = ArrayList<String>()
            if (n > 0) details += "+$n titre(s) ajouté(s) à la fin"
            if (actuelle.first != nom) details += "nom différent (« $nom ») : nom actuel gardé"
            if (actuelle.second > 0 && o.optLong("supprimee_le") == 0L) details += "dans la corbeille ici : gardée dans la corbeille"
            lignes += LigneFusion("📃 ${actuelle.first}", details.joinToString(" · ").ifEmpty { "Déjà présente, identique" }, n > 0)
        }
    }

    private fun ajouterPistes(db: SQLiteDatabase, id: String, pistes: JSONArray, m: (String) -> String): Int {
        var ordre = db.rawQuery("SELECT COALESCE(MAX(ordre), 0) FROM playlist_pistes WHERE playlist=?", arrayOf(id)).use { if (it.moveToFirst()) it.getDouble(0) else 0.0 }
        var n = 0
        for (k in 0 until pistes.length()) {
            val p = pistes.getJSONObject(k)
            val e = m(p.optString("e"))
            if (e.isEmpty()) continue
            val existe = db.rawQuery("SELECT 1 FROM playlist_pistes WHERE playlist=? AND empreinte=?", arrayOf(id, e)).use { it.moveToFirst() }
            if (existe) continue
            ordre += 1
            db.insert("playlist_pistes", null, ContentValues().apply {
                put("playlist", id); put("empreinte", e); put("ordre", ordre); put("ajoute_le", p.optLong("ajoute_le"))
                put("titre", p.optString("titre")); put("artiste", p.optString("artiste"))
            })
            n++
        }
        return n
    }

    private fun fusionReglages(db: SQLiteDatabase, o: JSONObject, lignes: MutableList<LigneFusion>) {
        var ajoutes = 0; var gardes = 0
        val cles = o.keys()
        while (cles.hasNext()) {
            val k = cles.next()
            if (k.startsWith("premier_scan") || k.startsWith("auto_")) continue
            val r = db.insertWithOnConflict("etat", null, ContentValues().apply { put("cle", k); put("valeur", o.optString(k)) }, SQLiteDatabase.CONFLICT_IGNORE)
            if (r == -1L) gardes++ else ajoutes++
        }
        lignes += LigneFusion("Réglages", listOfNotNull(
            "+$ajoutes ajouté(s)".takeIf { ajoutes > 0 }, "$gardes déjà réglé(s) : valeur actuelle gardée".takeIf { gardes > 0 },
        ).joinToString(" · ").ifEmpty { "Rien à ajouter" }, ajoutes > 0)
    }

    fun texteDescriptif(lignes: List<LigneFusion>): String = buildString {
        appendLine("Fusion Lecteur — ${Format.date(System.currentTimeMillis())}")
        lignes.forEach { appendLine("${if (it.ajout) "✅" else "•"} ${it.rubrique} : ${it.texte}") }
    }
}
