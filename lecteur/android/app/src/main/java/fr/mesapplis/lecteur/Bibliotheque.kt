package fr.mesapplis.lecteur

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore

/** Lit la liste des fichiers audio dans Musique, Download et Movies (là où Termux enregistre). */
object Bibliotheque {

    /** Dossiers de base, toujours suivis. */
    val dossiersDeBase = listOf("Music/", "Download/", "Movies/")
    /** Dossiers suivis : ceux de base + ceux ajoutés dans 📊 Données (« 📁 Dossiers suivis »). */
    @Volatile var dossiersSuivis: List<String> = dossiersDeBase
    /** Mis à true quand la liste des dossiers change : l'écran principal recharge. */
    @Volatile var aRecharger = false

    fun dossiersAjoutes(ctx: Context): List<String> =
        (Base.de(ctx).lire("dossiers_plus") ?: "").split('\n').filter { it.isNotEmpty() }

    fun majDossiers(ctx: Context) {
        dossiersSuivis = (dossiersDeBase + dossiersAjoutes(ctx)).distinct()
    }

    /** Ajoute un dossier (chemin relatif, ex. « Documents/Karaoké/ ») ; retourne le message à afficher. */
    fun ajouterDossier(ctx: Context, relatif: String): String {
        val r = if (relatif.isEmpty()) "" else relatif.trimEnd('/') + "/"
        val actuels = dossiersDeBase + dossiersAjoutes(ctx)
        actuels.firstOrNull { r.startsWith(it, true) }?.let { return "Déjà suivi : « ${it.ifEmpty { "tout le téléphone" }} » le contient." }
        val gardes = dossiersAjoutes(ctx).filter { !it.startsWith(r, true) } + r
        Base.de(ctx).ecrire("dossiers_plus", gardes.joinToString("\n"))
        majDossiers(ctx); aRecharger = true
        return "📁 « ${r.ifEmpty { "Tout le téléphone" }} » ajouté : ses musiques et vidéos arrivent dans le Lecteur."
    }

    fun retirerDossier(ctx: Context, relatif: String) {
        Base.de(ctx).ecrire("dossiers_plus", dossiersAjoutes(ctx).filter { it != relatif }.joinToString("\n"))
        majDossiers(ctx); aRecharger = true
    }

    /** Condition SQL « dans un des dossiers suivis » (les apostrophes sont protégées). */
    fun selection(colonne: String, ancienne: Boolean = false): String =
        dossiersSuivis.joinToString(" OR ", "(", ")") {
            val d = it.replace("'", "''")
            if (ancienne) "$colonne LIKE '%/$d%'" else "$colonne LIKE '$d%'"
        }

    fun charger(ctx: Context): List<Piste> {
        majDossiers(ctx)
        val res = ArrayList<Piste>()
        val q = Build.VERSION.SDK_INT >= 29
        val proj = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED,
        )
        val selection: String
        if (q) {
            proj += MediaStore.Audio.Media.RELATIVE_PATH
            proj += MediaStore.Audio.Media.VOLUME_NAME
            selection = selection(MediaStore.Audio.Media.RELATIVE_PATH)
        } else {
            proj += MediaStore.Audio.Media.DATA
            selection = selection(MediaStore.Audio.Media.DATA, ancienne = true)
        }
        val racine = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/') + "/"
        try {
            ctx.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj.toTypedArray(), selection, null, null
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val iTitre = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val iArtiste = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val iDuree = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val iAjoute = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val iNom = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val iTaille = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val iModifie = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                val iChemin = c.getColumnIndexOrThrow(proj[9])
                val iVolume = if (q) c.getColumnIndexOrThrow(proj[10]) else -1
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val nom = c.getString(iNom) ?: continue
                    var dossier: String
                    if (q) {
                        dossier = c.getString(iChemin) ?: ""
                    } else {
                        val data = c.getString(iChemin) ?: ""
                        dossier = data.removePrefix(racine).substringBeforeLast('/', "") + "/"
                    }
                    if (!dossier.endsWith("/")) dossier += "/"
                    val volume = if (iVolume >= 0) (c.getString(iVolume) ?: "external_primary") else "external_primary"
                    val brut = c.getString(iTitre)?.takeIf { it.isNotBlank() } ?: nom.substringBeforeLast('.')
                    var artiste = c.getString(iArtiste)?.takeIf { it != MediaStore.UNKNOWN_STRING } ?: ""
                    var titre = nettoyerTitre(brut)
                    if (artiste.isBlank() && titre.contains(" - ")) {
                        artiste = titre.substringBefore(" - ").trim()
                        titre = titre.substringAfter(" - ").trim()
                    }
                    var album = c.getString(iAlbum)?.takeIf { it != MediaStore.UNKNOWN_STRING } ?: ""
                    if (album.equals("Music", true) || album.equals("Download", true) || album.equals("Movies", true)) album = ""
                    res += Piste(
                        id = id,
                        uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                        titre = titre,
                        artiste = artiste.trim(),
                        album = album.trim(),
                        duree = c.getLong(iDuree),
                        ajoute = c.getLong(iAjoute),
                        volume = volume,
                        dossier = dossier,
                        nom = nom,
                        taille = c.getLong(iTaille),
                        modifie = c.getLong(iModifie),
                    )
                }
            }
        } catch (e: SecurityException) {
            return emptyList()
        }
        return res
    }

    /** Vidéos de Movies, Download et Music (onglet Vidéos). */
    fun chargerVideos(ctx: Context): List<Piste> {
        val res = ArrayList<Piste>()
        if (Build.VERSION.SDK_INT < 29) return res
        val proj = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.TITLE, MediaStore.Video.Media.DURATION, MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.RELATIVE_PATH, MediaStore.Video.Media.VOLUME_NAME,
        )
        val sel = selection(MediaStore.Video.Media.RELATIVE_PATH)
        try {
            ctx.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, sel, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val nom = c.getString(4) ?: continue
                    var dossier = c.getString(7) ?: ""
                    if (!dossier.endsWith("/")) dossier += "/"
                    var titre = nettoyerTitre(c.getString(1)?.takeIf { it.isNotBlank() } ?: nom.substringBeforeLast('.'))
                    var artiste = ""
                    if (titre.contains(" - ")) { artiste = titre.substringBefore(" - ").trim(); titre = titre.substringAfter(" - ").trim() }
                    res += Piste(
                        id = id, uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                        titre = titre, artiste = artiste, album = "", duree = c.getLong(2), ajoute = c.getLong(3),
                        volume = c.getString(8) ?: "external_primary", dossier = dossier, nom = nom,
                        taille = c.getLong(5), modifie = c.getLong(6),
                    )
                }
            }
        } catch (e: Exception) { }
        return res
    }

    @Volatile var videos: List<Piste> = emptyList()

    /** Dernière liste chargée (partagée entre les écrans). */
    @Volatile var derniere: List<Piste> = emptyList()

    fun parEmpreinte(): Map<String, Piste> = (videos + derniere).mapNotNull { p -> p.empreinte?.let { it to p } }.toMap()

    /**
     * Empreinte du contenu : taille + somme de contrôle de 64 Ko pris au milieu du fichier (là où est le son,
     * pas les tags). Un fichier renommé ou déplacé garde la même empreinte.
     */
    fun empreinte(ctx: Context, uri: android.net.Uri, taille: Long): String? = try {
        ctx.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            java.io.FileInputStream(pfd.fileDescriptor).use { f ->
                val canal = f.channel
                val t = if (taille > 0) taille else canal.size()
                val debut = if (t > 131072) t / 2 - 32768 else 0L
                canal.position(debut)
                val tampon = java.nio.ByteBuffer.allocate(65536)
                while (tampon.hasRemaining() && canal.read(tampon) > 0) { }
                val crc = java.util.zip.CRC32()
                crc.update(tampon.array(), 0, tampon.position())
                "$t-" + java.lang.Long.toHexString(crc.value)
            }
        }
    } catch (e: Exception) { null }

    /** Range : les nouveaux en tête, puis du plus récent au plus ancien. */
    fun trier(liste: List<Piste>): List<Piste> =
        liste.sortedWith(compareByDescending<Piste> { it.nouveau }.thenByDescending { it.ajoute }.thenBy { it.titre.lowercase() })

    private val idYoutube = Regex("""\s*[\[(]([A-Za-z0-9_-]{11})[\])]""")
    private val finYoutube = Regex("""\s+-\s+YouTube$""", RegexOption.IGNORE_CASE)

    /** Retire l'identifiant YouTube (ex. « [dQw4w9WgXcQ] ») ajouté par yt-dlp. */
    fun nettoyerTitre(brut: String): String {
        var s = idYoutube.replace(brut) { m -> if (ressembleId(m.groupValues[1])) "" else m.value }
        s = finYoutube.replace(s, "")
        s = s.replace('_', ' ').replace(Regex("""\s{2,}"""), " ").trim()
        return s.ifEmpty { brut.trim() }
    }

    private fun ressembleId(x: String): Boolean {
        val chiffre = x.any { it.isDigit() }
        val symbole = x.any { it == '-' || it == '_' }
        val majInterne = x.drop(1).any { it.isUpperCase() } && x.any { it.isLowerCase() }
        return chiffre || symbole || majInterne
    }
}
