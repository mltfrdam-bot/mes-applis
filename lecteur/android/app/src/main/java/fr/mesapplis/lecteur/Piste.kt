package fr.mesapplis.lecteur

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import java.text.Normalizer

/** Un fichier audio trouvé sur le téléphone. */
class Piste(
    val id: Long,
    val uri: Uri,
    val titre: String,
    val artiste: String,
    val album: String,
    val duree: Long,
    val ajoute: Long,
    /** Volume Android (« external_primary » pour la mémoire interne). */
    val volume: String,
    /** Dossier relatif, ex. « Music/Mon album/ » (finit toujours par « / »). */
    val dossier: String,
    /** Nom du fichier, ex. « Chanson.mp3 ». */
    val nom: String,
    val taille: Long = 0,
    val modifie: Long = 0,
) {
    /** Identifiant basé sur le contenu : un fichier renommé ou déplacé garde ses écoutes, favoris et playlists. */
    var empreinte: String? = null
    var nouveau = false
    var favori = false
    var ecoutes = 0

    /** Chemin (dossier + nom). */
    val cle: String get() = dossier + nom

    /** Texte de recherche sans accents ni majuscules. */
    val recherche: String by lazy { Texte.simplifier("$titre $artiste $album $dossier") }

    val nomDossier: String get() = dossier.trimEnd('/').substringAfterLast('/')

    fun versMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(uri.toString())
        .setUri(uri)
        .setMediaMetadata(metadonnees(titre, artiste, album, volume, dossier, nom, empreinte))
        .build()

    companion object {
        fun metadonnees(titre: String, artiste: String, album: String, volume: String, dossier: String, nom: String, empreinte: String?): MediaMetadata {
            val extras = Bundle().apply {
                putString("vol", volume)
                putString("dossier", dossier)
                putString("nom", nom)
                if (empreinte != null) putString("emp", empreinte)
            }
            return MediaMetadata.Builder()
                .setTitle(titre)
                .setArtist(artiste.ifEmpty { null })
                .setAlbumTitle(album.ifEmpty { null })
                .setExtras(extras)
                .build()
        }
    }
}

/** Où se trouve le fichier d'un MediaItem (pour les paroles, les pochettes de dossier et les écoutes). */
class Emplacement(val volume: String, val dossier: String, val nom: String, val empreinte: String? = null) {
    val cle: String get() = dossier + nom

    companion object {
        fun de(item: MediaItem?): Emplacement? {
            val e = item?.mediaMetadata?.extras ?: return null
            val nom = e.getString("nom") ?: return null
            return Emplacement(e.getString("vol") ?: "", e.getString("dossier") ?: "", nom, e.getString("emp"))
        }
    }
}

object Texte {
    private val accents = Regex("\\p{Mn}+")
    fun simplifier(s: String): String = accents.replace(Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD), "")
}

object Format {
    fun duree(ms: Long): String {
        if (ms <= 0) return "–"
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%d:%02d", m, sec)
    }

    /** Durée longue lisible, ex. « 3 h 12 min ». */
    fun dureeLongue(ms: Long): String {
        val min = ms / 60000
        return if (min >= 60) "${min / 60} h ${String.format("%02d", min % 60)} min" else "$min min"
    }

    private val fmtDate = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.FRANCE)
    fun date(ms: Long): String = if (ms <= 0) "–" else fmtDate.format(java.util.Date(ms))
}
