package fr.mesapplis.lecteur

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

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
) {
    var nouveau = false

    /** Clé stable dans la base (dossier + nom). */
    val cle: String get() = dossier + nom

    fun versMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(uri.toString())
        .setUri(uri)
        .setMediaMetadata(metadonnees(titre, artiste, album, volume, dossier, nom))
        .build()

    companion object {
        fun metadonnees(titre: String, artiste: String, album: String, volume: String, dossier: String, nom: String): MediaMetadata {
            val extras = Bundle().apply {
                putString("vol", volume)
                putString("dossier", dossier)
                putString("nom", nom)
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

/** Où se trouve le fichier d'un MediaItem (pour les paroles et les pochettes de dossier). */
class Emplacement(val volume: String, val dossier: String, val nom: String) {
    val cle: String get() = dossier + nom

    companion object {
        fun de(item: MediaItem?): Emplacement? {
            val e = item?.mediaMetadata?.extras ?: return null
            val nom = e.getString("nom") ?: return null
            return Emplacement(e.getString("vol") ?: "", e.getString("dossier") ?: "", nom)
        }
    }
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
}
