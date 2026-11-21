package fr.mesapplis.lecteur

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 🌐 Traduction anglais → français, faite sur le téléphone (Google ML Kit, sans appli à installer).
 * Le pack de langue (environ 30 Mo) est téléchargé une seule fois ; ensuite tout marche sans internet.
 * Chaque texte n'est traduit qu'une fois : le résultat est gardé dans la base (table « traductions »).
 */
object Traduction {

    /** Traduction activée par l'utilisateur. */
    @Volatile var actif = false
    /** true : traduction sous le texte anglais ; false : à la place. */
    @Volatile var dessous = true
    /** Pack de langue téléchargé. */
    @Volatile var pret = false

    /** Texte original → traduction ("" = pas de l'anglais, rien à traduire). */
    private val memo = ConcurrentHashMap<String, String>()
    @Volatile private var charge = false
    private val file = Executors.newSingleThreadExecutor()
    private var traducteur: Translator? = null

    // ----- Réglages -----

    fun init(ctx: Context) {
        if (charge) return
        synchronized(this) {
            if (charge) return
            val b = Base.de(ctx)
            actif = b.lire("trad") == "1"
            dessous = b.lire("trad_mode") != "place"
            pret = b.lire("trad_pack") == "1"
            b.readableDatabase.rawQuery("SELECT texte, fr FROM traductions", null).use { c ->
                while (c.moveToNext()) memo[c.getString(0)] = c.getString(1) ?: ""
            }
            charge = true
        }
    }

    fun activer(ctx: Context, oui: Boolean) {
        actif = oui
        Base.de(ctx).ecrire("trad", if (oui) "1" else "0")
    }

    fun choisirDessous(ctx: Context, oui: Boolean) {
        dessous = oui
        Base.de(ctx).ecrire("trad_mode", if (oui) "dessous" else "place")
    }

    fun nombre(): Int = memo.values.count { it.isNotEmpty() }

    // ----- Affichage -----

    /** Traduction d'un texte, ou null (désactivé, pas de l'anglais, pas encore traduit). */
    fun fr(texte: String): String? = if (!actif || texte.isBlank()) null else memo[texte]?.takeIf { it.isNotEmpty() }

    /** Titre affiché sur une ligne : la traduction si « à la place ». */
    fun titre(texte: String): String = if (!dessous) fr(texte) ?: texte else texte

    /** Ligne du dessous (artiste…) : précédée de la traduction si « sous le texte ». */
    fun detail(titre: String, detail: String): String {
        val f = fr(titre) ?: return detail
        if (!dessous) return detail
        return if (detail.isEmpty()) "🌐 $f" else "🌐 $f · $detail"
    }

    // ----- Téléchargement du pack -----

    private fun client(): Translator = traducteur ?: Translation.getClient(
        TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.ENGLISH).setTargetLanguage(TranslateLanguage.FRENCH).build()
    ).also { traducteur = it }

    /** Télécharge le pack (une seule fois). Appelé hors du fil principal. Retourne null si tout va bien, sinon le message d'erreur. */
    fun telecharger(ctx: Context): String? = try {
        Tasks.await(client().downloadModelIfNeeded(DownloadConditions.Builder().build()), 15, TimeUnit.MINUTES)
        pret = true
        Base.de(ctx).ecrire("trad_pack", "1")
        null
    } catch (e: Exception) {
        "Le pack n'a pas pu être téléchargé. Vérifie internet (Wi-Fi conseillé), puis réessaie.\n(${e.cause?.message ?: e.message ?: "erreur"})"
    }

    // ----- Traduction -----

    private val langue by lazy {
        LanguageIdentification.getClient(LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.5f).build())
    }

    private fun estAnglais(texte: String): Boolean = try {
        Tasks.await(langue.identifyLanguage(texte), 10, TimeUnit.SECONDS) == "en"
    } catch (e: Exception) { false }

    private fun traduire(texte: String): String? = try {
        Tasks.await(client().translate(texte), 20, TimeUnit.SECONDS)
    } catch (e: Exception) { null }

    private fun garder(ctx: Context, lot: Map<String, String>) {
        if (lot.isEmpty()) return
        memo.putAll(lot)
        val db = Base.de(ctx).writableDatabase
        val t = System.currentTimeMillis()
        db.beginTransaction()
        try {
            for ((k, v) in lot) db.insertWithOnConflict("traductions", null, ContentValues().apply {
                put("texte", k); put("fr", v); put("quand", t)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Titres, albums et noms de dossiers de la bibliothèque (les noms d'artistes ne se traduisent pas). */
    fun traduireBibliotheque(ctx: Context, pistes: List<Piste>, fini: () -> Unit) {
        if (!actif || !pret) return
        val textes = LinkedHashSet<String>()
        for (p in pistes) { textes += p.titre; textes += p.album; textes += p.nomDossier }
        val a = textes.filter { it.isNotBlank() && !memo.containsKey(it) }
        if (a.isEmpty()) return
        file.execute {
            val lot = HashMap<String, String>()
            var nouveaux = 0
            for (t in a) {
                if (!actif) break
                val en = estAnglais(t)
                val f = if (en) traduire(t) else null
                if (en && f == null) continue // échec passager : on réessaiera
                lot[t] = f?.takeIf { !it.equals(t, true) } ?: ""
                if (lot[t]!!.isNotEmpty()) nouveaux++
                if (lot.size >= 40) { garder(ctx, lot); lot.clear(); if (nouveaux > 0) fini() ; nouveaux = 0 }
            }
            garder(ctx, lot)
            fini()
        }
    }

    /** Paroles : la langue est reconnue sur l'ensemble du texte, puis chaque ligne est traduite. */
    fun traduireParoles(ctx: Context, p: Paroles, fini: () -> Unit) {
        if (!actif || !pret) return
        val lignes = p.lignes.map { it.texte }.filter { it.isNotBlank() }.distinct()
        if (lignes.all { memo.containsKey(it) }) return
        file.execute {
            val anglais = estAnglais(lignes.take(40).joinToString("\n"))
            val lot = HashMap<String, String>()
            for (l in lignes) {
                if (memo.containsKey(l)) continue
                if (!anglais) { lot[l] = ""; continue }
                val f = traduire(l) ?: continue
                lot[l] = f.takeIf { !it.equals(l, true) } ?: ""
            }
            garder(ctx, lot)
            fini()
        }
    }
}
