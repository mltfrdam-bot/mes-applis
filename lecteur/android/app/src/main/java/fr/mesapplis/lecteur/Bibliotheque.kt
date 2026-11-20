package fr.mesapplis.lecteur

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore

/** Lit la liste des fichiers audio dans Musique, Download et Movies (là où Termux enregistre). */
object Bibliotheque {

    val dossiersSuivis = listOf("Music/", "Download/", "Movies/")

    fun charger(ctx: Context): List<Piste> {
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
        )
        val selection: String
        if (q) {
            proj += MediaStore.Audio.Media.RELATIVE_PATH
            proj += MediaStore.Audio.Media.VOLUME_NAME
            selection = dossiersSuivis.joinToString(" OR ", "(", ")") { "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE '$it%'" }
        } else {
            proj += MediaStore.Audio.Media.DATA
            selection = dossiersSuivis.joinToString(" OR ", "(", ")") { "${MediaStore.Audio.Media.DATA} LIKE '%/$it%'" }
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
                val iChemin = c.getColumnIndexOrThrow(proj[7])
                val iVolume = if (q) c.getColumnIndexOrThrow(proj[8]) else -1
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
                    )
                }
            }
        } catch (e: SecurityException) {
            return emptyList()
        }
        return res
    }

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
