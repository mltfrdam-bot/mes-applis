package fr.mesapplis.lecteur

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/** Résultat d'un export ou d'un import de pack. [musiqueRefusee] : il faut d'abord autoriser l'écriture dans Musique. */
class RapportPack(val lignes: List<String>, val musiqueRefusee: Boolean = false)

/**
 * 📦 Pack de playlist : un dossier (clé USB…) avec les chansons, leurs paroles et l'ordre de la liste.
 * Un simple dossier : il se lit aussi sur un autoradio, une télé ou un PC.
 * 📥 Import : les chansons vont dans Music/<nom>/, la playlist est recréée, rien n'est copié deux fois.
 */
object Pack {
    const val FORMAT = "lecteur-pack"
    const val FICHIER_PACK = "pack-lecteur.json"
    const val FICHIER_M3U = "playlist.m3u"
    private val audio = setOf("mp3", "m4a", "aac", "opus", "ogg", "flac", "wav", "webm", "mp4", "mkv")
    private val paroles = listOf("lrc", "fr.lrc", "en.lrc", "srt", "fr.srt", "en.srt", "vtt", "fr.vtt", "en.vtt", "txt")

    private class Enfant(val id: String, val nom: String, val mime: String, val taille: Long)

    fun nomSain(s: String): String = s.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifEmpty { "Playlist" }

    private fun mime(nom: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(nom.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    private fun enfants(ctx: Context, arbre: Uri, idParent: String): List<Enfant> {
        val res = ArrayList<Enfant>()
        try {
            ctx.contentResolver.query(
                DocumentsContract.buildChildDocumentsUriUsingTree(arbre, idParent),
                arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE), null, null, null
            )?.use { c -> while (c.moveToNext()) res += Enfant(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getLong(3)) }
        } catch (e: Exception) { }
        return res
    }

    /** Trouve ou crée le sous-dossier [nom] ; retourne son identifiant. */
    private fun dossier(ctx: Context, arbre: Uri, idParent: String, nom: String): String? {
        enfants(ctx, arbre, idParent).firstOrNull { it.mime == Document.MIME_TYPE_DIR && it.nom.equals(nom, true) }?.let { return it.id }
        val u = DocumentsContract.createDocument(ctx.contentResolver, DocumentsContract.buildDocumentUriUsingTree(arbre, idParent), Document.MIME_TYPE_DIR, nom)
            ?: return null
        return DocumentsContract.getDocumentId(u)
    }

    private fun copier(ctx: Context, source: Uri, arbre: Uri, idDossier: String, nom: String): Boolean = try {
        val cible = DocumentsContract.createDocument(ctx.contentResolver, DocumentsContract.buildDocumentUriUsingTree(arbre, idDossier), mime(nom), nom)
        if (cible == null) false else {
            ctx.contentResolver.openInputStream(source)!!.use { e -> ctx.contentResolver.openOutputStream(cible)!!.use { s -> e.copyTo(s, 256 * 1024) } }
            true
        }
    } catch (e: Exception) { false }

    private fun ecrireTexte(ctx: Context, arbre: Uri, idDossier: String, nom: String, texte: String) {
        enfants(ctx, arbre, idDossier).filter { it.nom == nom }.forEach {
            try { DocumentsContract.deleteDocument(ctx.contentResolver, DocumentsContract.buildDocumentUriUsingTree(arbre, it.id)) } catch (e: Exception) { }
        }
        try {
            val u = DocumentsContract.createDocument(ctx.contentResolver, DocumentsContract.buildDocumentUriUsingTree(arbre, idDossier), "application/octet-stream", nom) ?: return
            ctx.contentResolver.openOutputStream(u)?.use { it.write(texte.toByteArray(Charsets.UTF_8)) }
        } catch (e: Exception) { }
    }

    private fun mo(octets: Long) = String.format("%.0f Mo", octets / 1048576.0)

    // ----- 📦 Export -----

    fun exporter(ctx: Context, nomPlaylist: String, pistes: List<Piste>, arbre: Uri, progression: (Int, Int, String) -> Unit): RapportPack {
        val lignes = ArrayList<String>()
        val nom = nomSain(nomPlaylist)
        val racine = DocumentsContract.getTreeDocumentId(arbre)
        val idDossier = dossier(ctx, arbre, racine, nom)
            ?: return RapportPack(listOf("❌ Impossible de créer le dossier « $nom » à cet endroit. La clé est-elle bien branchée et non protégée ?"))
        val deja = enfants(ctx, arbre, idDossier).associate { it.nom to it.taille }
        val presentes = pistes.filter { it.id >= 0 }
        val absentes = pistes.size - presentes.size
        val utilises = HashSet<String>()
        val liste = JSONArray()
        val m3u = StringBuilder("#EXTM3U\n")
        var copies = 0; var dejaLa = 0; var erreurs = 0; var nbParoles = 0; var octets = 0L
        presentes.forEachIndexed { i, p ->
            progression(i, presentes.size, p.titre)
            var fichier = p.nom
            if (!utilises.add(fichier.lowercase())) { fichier = "${i + 1} - ${p.nom}"; utilises += fichier.lowercase() }
            val base = fichier.substringBeforeLast('.')
            when {
                deja[fichier] == p.taille -> dejaLa++
                copier(ctx, p.uri, arbre, idDossier, fichier) -> { copies++; octets += p.taille }
                else -> { erreurs++; lignes += "❌ Non copié : $fichier (clé pleine ou débranchée ?)" }
            }
            val fichiersParoles = JSONArray()
            if (Acces.aUnAcces(ctx)) for (ext in paroles) {
                val src = Acces.uriFichier(ctx, p.volume, p.dossier, p.nom.substringBeforeLast('.') + "." + ext) ?: break
                val octetsParoles = Acces.lireOctets(ctx, src) ?: continue
                val nomParoles = "$base.$ext"
                fichiersParoles.put(nomParoles)
                if (deja[nomParoles] == octetsParoles.size.toLong()) continue
                ecrireTexte(ctx, arbre, idDossier, nomParoles, String(octetsParoles, Charsets.UTF_8))
                nbParoles++
            }
            liste.put(JSONObject().apply {
                put("fichier", fichier); put("titre", p.titre); put("artiste", p.artiste); put("album", p.album)
                put("duree", p.duree); put("e", p.empreinte ?: ""); put("paroles", fichiersParoles)
            })
            m3u.append("#EXTINF:${p.duree / 1000},${if (p.artiste.isNotEmpty()) p.artiste + " - " else ""}${p.titre}\n$fichier\n")
        }
        progression(presentes.size, presentes.size, "Liste de lecture…")
        ecrireTexte(ctx, arbre, idDossier, FICHIER_M3U, m3u.toString())
        ecrireTexte(ctx, arbre, idDossier, FICHIER_PACK, JSONObject().apply {
            put("format", FORMAT); put("version", 1); put("nom", nomPlaylist); put("cree_le", System.currentTimeMillis()); put("pistes", liste)
        }.toString(1))

        lignes.add(0, "📦 Pack « $nomPlaylist » → dossier « $nom »")
        lignes.add(1, "✅ $copies chanson(s) copiée(s) (${mo(octets)})" +
            (if (dejaLa > 0) " · $dejaLa déjà sur la clé" else "") + (if (nbParoles > 0) " · $nbParoles fichier(s) de paroles" else ""))
        if (absentes > 0) lignes += "• $absentes titre(s) absent(s) du téléphone : non copié(s)"
        if (!Acces.aUnAcces(ctx)) lignes += "• Paroles non copiées : autorise d'abord le dossier Musique (❓ Aide)"
        lignes += "• Liste dans l'ordre : $FICHIER_M3U (lisible par la plupart des lecteurs) et $FICHIER_PACK (pour le Lecteur)"
        if (erreurs == 0) lignes += "Tu peux débrancher la clé."
        return RapportPack(lignes)
    }

    // ----- 📥 Import -----

    private class Entree(val fichier: String, val titre: String, val artiste: String)

    fun importer(ctx: Context, arbre: Uri, progression: (Int, Int, String) -> Unit): RapportPack {
        val musique = Acces.arbreMusique(ctx) ?: return RapportPack(emptyList(), musiqueRefusee = true)
        val racine = DocumentsContract.getTreeDocumentId(arbre)
        val contenu = enfants(ctx, arbre, racine)
        val parNom = contenu.associateBy { it.nom }
        val nomDossierPack = racine.substringAfterLast('/').substringAfterLast(':').ifEmpty { "Pack" }

        // Nom et ordre : fichier du Lecteur, sinon la liste .m3u, sinon les fichiers audio du dossier
        var nomPlaylist = nomDossierPack
        val entrees = ArrayList<Entree>()
        val json = parNom[FICHIER_PACK]?.let { Acces.lireTexte(ctx, DocumentsContract.buildDocumentUriUsingTree(arbre, it.id)) }
        if (json != null) try {
            val o = JSONObject(json)
            nomPlaylist = o.optString("nom").ifEmpty { nomPlaylist }
            val t = o.optJSONArray("pistes") ?: JSONArray()
            for (i in 0 until t.length()) t.getJSONObject(i).let { entrees += Entree(it.optString("fichier"), it.optString("titre"), it.optString("artiste")) }
        } catch (e: Exception) { }
        if (entrees.isEmpty()) {
            val m3u = contenu.firstOrNull { it.nom.endsWith(".m3u", true) || it.nom.endsWith(".m3u8", true) }
                ?.let { Acces.lireTexte(ctx, DocumentsContract.buildDocumentUriUsingTree(arbre, it.id)) }
            m3u?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() && !it.startsWith("#") }?.forEach {
                val f = it.substringAfterLast('/').substringAfterLast('\\')
                entrees += Entree(f, f.substringBeforeLast('.'), "")
            }
        }
        if (entrees.isEmpty()) contenu.filter { it.nom.substringAfterLast('.', "").lowercase() in audio }.sortedBy { it.nom.lowercase() }
            .forEach { entrees += Entree(it.nom, it.nom.substringBeforeLast('.'), "") }
        if (entrees.isEmpty()) return RapportPack(listOf("❌ Aucune chanson trouvée dans ce dossier. Choisis le dossier du pack (celui qui contient les chansons)."))

        val nom = nomSain(nomPlaylist)
        val idMusique = DocumentsContract.getTreeDocumentId(musique).let { if (it.endsWith(":")) it + "Music" else it }
        val idDest = dossier(ctx, musique, idMusique, nom)
            ?: return RapportPack(listOf("❌ Impossible de créer Music/$nom sur le téléphone."))
        val dejaDest = enfants(ctx, musique, idDest).associate { it.nom to it.taille }
        val parEmp = Bibliotheque.parEmpreinte()

        val lignes = ArrayList<String>()
        val pourPlaylist = ArrayList<Triple<String, String, String>>()
        val aScanner = ArrayList<String>()
        val chemin = Environment.getExternalStorageDirectory().absolutePath + "/Music/$nom/"
        var copies = 0; var connues = 0; var manquantes = 0; var nbParoles = 0
        entrees.forEachIndexed { i, en ->
            progression(i, entrees.size, en.titre)
            val src = parNom[en.fichier]
            if (src == null) { manquantes++; lignes += "❌ Absent du pack : ${en.fichier}"; return@forEachIndexed }
            val uriSrc = DocumentsContract.buildDocumentUriUsingTree(arbre, src.id)
            val e = Bibliotheque.empreinte(ctx, uriSrc, src.taille) ?: "chemin:Music/$nom/${en.fichier}"
            val existante = parEmp[e]
            when {
                existante != null -> { connues++; lignes += "• Déjà sur le téléphone : ${existante.titre} (${existante.dossier}) — pas recopiée" }
                dejaDest[en.fichier] == src.taille -> connues++
                copier(ctx, uriSrc, musique, idDest, en.fichier) -> { copies++; aScanner += chemin + en.fichier }
                else -> { lignes += "❌ Non copié : ${en.fichier} (place insuffisante ?)"; return@forEachIndexed }
            }
            pourPlaylist += Triple(e, en.titre.ifEmpty { en.fichier.substringBeforeLast('.') }, en.artiste)
            if (existante == null) {
                val base = en.fichier.substringBeforeLast('.')
                for (ext in paroles) {
                    val p = parNom["$base.$ext"] ?: continue
                    if (dejaDest["$base.$ext"] == p.taille) continue
                    if (copier(ctx, DocumentsContract.buildDocumentUriUsingTree(arbre, p.id), musique, idDest, "$base.$ext")) nbParoles++
                }
            }
        }
        progression(entrees.size, entrees.size, "Playlist…")
        val base = Base.de(ctx)
        val existante = base.playlists().firstOrNull { it.nom.equals(nomPlaylist, true) }
        val id = existante?.id ?: base.creerPlaylist(nomPlaylist)
        val ajoutees = base.ajouterEmpreintes(id, pourPlaylist)
        if (aScanner.isNotEmpty()) MediaScannerConnection.scanFile(ctx.applicationContext, aScanner.toTypedArray(), null, null)
        LecteurParoles.vider()

        lignes.add(0, "📥 Pack « $nomPlaylist » → Music/$nom/")
        lignes.add(1, "✅ $copies chanson(s) copiée(s)" + (if (connues > 0) " · $connues déjà présente(s)" else "") +
            (if (nbParoles > 0) " · $nbParoles fichier(s) de paroles" else "") + (if (manquantes > 0) " · $manquantes manquante(s)" else ""))
        lignes.add(2, if (existante == null) "✅ Playlist « $nomPlaylist » créée ($ajoutees titres)"
            else "✅ Playlist « ${existante.nom} » existante : +$ajoutees titre(s) ajouté(s) à la fin")
        return RapportPack(lignes)
    }

    // ----- Fenêtres communes -----

    /** Fenêtre d'attente avec barre de progression ; retourne la fenêtre et la fonction de mise à jour. */
    fun attente(act: Activity, titre: String): Pair<AlertDialog, (Int, Int, String) -> Unit> {
        val pad = (20 * act.resources.displayMetrics.density).toInt()
        val texte = TextView(act).apply { textSize = 14f }
        val barre = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val vue = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(barre); addView(texte)
        }
        val d = AlertDialog.Builder(act).setTitle(titre).setView(vue).setCancelable(false).show()
        return d to { n, total, quoi ->
            act.runOnUiThread {
                barre.progress = if (total > 0) n * 100 / total else 0
                texte.text = "$n / $total · $quoi"
            }
        }
    }

    fun montrerRapport(act: Activity, titre: String, r: RapportPack) {
        val t = r.lignes.joinToString("\n")
        AlertDialog.Builder(act)
            .setTitle(titre)
            .setMessage(t)
            .setPositiveButton("OK", null)
            .setNeutralButton("📋 Copier") { _, _ ->
                (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Lecteur", "$titre — ${Format.date(System.currentTimeMillis())}\n$t"))
                Toast.makeText(act, "📋 Copié : colle-le dans la conversation", Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
