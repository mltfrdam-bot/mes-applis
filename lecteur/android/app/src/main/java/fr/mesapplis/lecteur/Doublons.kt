package fr.mesapplis.lecteur

import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore

/** Un groupe de fichiers qui sont le même morceau. [garder] = la meilleure version, proposée par défaut. */
class GroupeDoublons(val pistes: List<Piste>, val identiques: Boolean, val garder: Piste) {
    /** Signature stable (chemins triés) : sert à retenir « Pas un doublon ». */
    val signature: String = pistes.map { it.cle }.sorted().joinToString("|")
    val raison: String get() = if (identiques) "Fichiers identiques" else "Même titre, même durée"
}

/** Fichier mis à la corbeille d'Android (restaurable pendant 30 jours). */
class FichierCorbeille(val uri: Uri, val nom: String, val dossier: String, val expire: Long)

object Doublons {

    /** Écart de durée toléré entre deux versions du même morceau. */
    private const val ECART_MS = 3000L

    private val bruit = Regex(
        """\s*[\[(][^\])]*(official|officiel|video|vidéo|audio|lyrics?|paroles|clip|visuali[sz]er|hd|hq|4k|remaster(ed)?)[^\])]*[\])]""",
        RegexOption.IGNORE_CASE
    )
    private val nonLettres = Regex("""[^a-z0-9]+""")

    fun cleTitre(p: Piste): String {
        val t = Texte.simplifier(bruit.replace(p.titre, " "))
        val a = Texte.simplifier(p.artiste)
        val tt = nonLettres.replace(t, " ").trim()
        if (tt.length < 2) return ""
        return tt + "|" + nonLettres.replace(a, " ").trim()
    }

    /** Débit en kbit/s (taille ÷ durée) : plus il est haut, meilleure est la qualité. */
    fun kbps(p: Piste): Int = if (p.duree > 0 && p.taille > 0) (p.taille * 8 / p.duree).toInt() else 0

    /** Choix de la version à garder : meilleure qualité, puis rangée dans Music, puis la plus écoutée, puis la plus ancienne. */
    private fun meilleure(l: List<Piste>): Piste {
        val max = l.maxOf { kbps(it) }
        val bonnes = l.filter { kbps(it) >= max * 0.9 }
        return bonnes.sortedWith(
            compareByDescending<Piste> { it.dossier.startsWith("Music/") }
                .thenByDescending { it.ecoutes }
                .thenByDescending { it.favori }
                .thenBy { it.ajoute }
        ).first()
    }

    fun ignores(ctx: Context): MutableSet<String> =
        (Base.de(ctx).lire("doublons_ignores") ?: "").split('\n').filter { it.isNotEmpty() }.toMutableSet()

    fun ignorer(ctx: Context, g: GroupeDoublons) {
        val s = ignores(ctx)
        s += g.signature
        Base.de(ctx).ecrire("doublons_ignores", s.joinToString("\n"))
    }

    /** Cherche les doublons (rapide : aucune lecture de fichier, les empreintes sont déjà connues). */
    fun chercher(ctx: Context, pistes: List<Piste>): List<GroupeDoublons> {
        val n = pistes.size
        val parent = IntArray(n) { it }
        fun racine(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        fun unir(a: Int, b: Int) { val ra = racine(a); val rb = racine(b); if (ra != rb) parent[rb] = ra }

        // 1. Même contenu
        val parEmp = HashMap<String, Int>()
        for (i in 0 until n) {
            val e = pistes[i].empreinte ?: continue
            if (e.startsWith("chemin:")) continue
            val j = parEmp.putIfAbsent(e, i)
            if (j != null) unir(j, i)
        }
        // 2. Même titre + même artiste + durée proche
        val parTitre = HashMap<String, MutableList<Int>>()
        for (i in 0 until n) {
            val k = cleTitre(pistes[i])
            if (k.isNotEmpty() && pistes[i].duree > 0) parTitre.getOrPut(k) { ArrayList() } += i
        }
        for (l in parTitre.values) {
            if (l.size < 2) continue
            val tries = l.sortedBy { pistes[it].duree }
            for (k in 1 until tries.size) {
                if (pistes[tries[k]].duree - pistes[tries[k - 1]].duree <= ECART_MS) unir(tries[k - 1], tries[k])
            }
        }

        val groupes = HashMap<Int, MutableList<Piste>>()
        for (i in 0 until n) groupes.getOrPut(racine(i)) { ArrayList() } += pistes[i]
        val ign = ignores(ctx)
        return groupes.values.filter { it.size > 1 }.map { g ->
            val emp = g.map { it.empreinte }
            GroupeDoublons(g.sortedByDescending { kbps(it) }, emp.all { it != null && it == emp[0] }, meilleure(g))
        }.filter { it.signature !in ign }.sortedBy { it.garder.titre.lowercase() }
    }

    // ----- Corbeille d'Android -----

    fun corbeillePossible() = Build.VERSION.SDK_INT >= 30

    /** Demande à Android de mettre ces fichiers à la corbeille (mettre = true) ou de les restaurer (false). */
    fun demande(ctx: Context, uris: List<Uri>, mettre: Boolean): IntentSender? =
        if (Build.VERSION.SDK_INT >= 30 && uris.isNotEmpty())
            MediaStore.createTrashRequest(ctx.contentResolver, uris, mettre).intentSender
        else null

    /** Fichiers audio de Musique, Download, Movies qui sont dans la corbeille. */
    fun corbeille(ctx: Context): List<FichierCorbeille> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        val res = ArrayList<FichierCorbeille>()
        val sel = Bibliotheque.dossiersSuivis.joinToString(" OR ", "(", ")") { "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE '$it%'" }
        val args = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, sel)
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns.DATE_EXPIRES} DESC")
        }
        try {
            ctx.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.RELATIVE_PATH, MediaStore.MediaColumns.DATE_EXPIRES),
                args, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val nom = (c.getString(1) ?: "").removePrefix(".trashed-").replace(Regex("""^\d+-"""), "")
                    res += FichierCorbeille(
                        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(0)),
                        nom, c.getString(2) ?: "", c.getLong(3) * 1000
                    )
                }
            }
        } catch (e: Exception) { }
        return res
    }
}
