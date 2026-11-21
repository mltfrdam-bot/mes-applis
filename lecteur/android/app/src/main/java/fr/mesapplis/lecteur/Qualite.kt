package fr.mesapplis.lecteur

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.FileInputStream

/**
 * 🎚 Qualité : repère les musiques faibles (moins de 120 kb/s) et prépare la liste « à améliorer » pour Termux.
 * YouTube ne donne jamais plus d'environ 130-160 kb/s : au-dessus du seuil, rien à gagner.
 */
object Qualite {
    const val SEUIL = 120
    const val DOSSIER = "Download/Lecteur/"
    const val FICHIER = "a-ameliorer.txt"
    private val lienYoutube = Regex("""(?:youtube\.com/watch\?v=|youtu\.be/|music\.youtube\.com/watch\?v=)([A-Za-z0-9_-]{11})""")

    fun faible(p: Piste): Boolean = !Format.estVideo(p.uri.toString()) && Doublons.kbps(p) in 1 until SEUIL

    /** Lien YouTube enregistré dans le fichier (yt-dlp le met dans les infos du fichier), ou null. */
    fun lien(ctx: Context, p: Piste): String? = try {
        ctx.contentResolver.openFileDescriptor(p.uri, "r")?.use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use { ch ->
                val taille = ch.size()
                fun lire(debut: Long, n: Int): String {
                    val b = java.nio.ByteBuffer.allocate(n.coerceAtMost((taille - debut).toInt().coerceAtLeast(0)))
                    ch.position(debut); ch.read(b)
                    return String(b.array(), 0, b.position(), Charsets.ISO_8859_1)
                }
                val id = lienYoutube.find(lire(0, 512 * 1024))?.groupValues?.get(1)
                    ?: lienYoutube.find(lire((taille - 256 * 1024).coerceAtLeast(0), 256 * 1024))?.groupValues?.get(1)
                id?.let { "https://www.youtube.com/watch?v=$it" }
            }
        }
    } catch (e: Exception) { null }

    /** Une ligne par titre : le lien, sinon une recherche YouTube « artiste titre ». */
    fun lignes(ctx: Context, pistes: List<Piste>): List<String> = pistes.map { p ->
        lien(ctx, p) ?: ("ytsearch1:" + listOf(p.artiste, p.titre).filter { it.isNotBlank() }.joinToString(" ").replace('\n', ' '))
    }

    fun texte(ctx: Context, pistes: List<Piste>): String {
        val l = lignes(ctx, pistes)
        return buildString {
            appendLine("# Lecteur — titres à améliorer (moins de $SEUIL kb/s) — ${Format.date(System.currentTimeMillis())}")
            pistes.forEachIndexed { i, p -> appendLine("# ${Doublons.kbps(p)} kb/s · ${p.dossier}${p.nom}"); appendLine(l[i]) }
        }
    }

    /** Écrit Download/Lecteur/a-ameliorer.txt (remplace l'ancien) ; Termux le lit avec la commande « ameliorer ». */
    fun enregistrer(ctx: Context, texte: String): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val cr = ctx.contentResolver
        try {
            cr.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.RELATIVE_PATH}=? AND ${MediaStore.Downloads.DISPLAY_NAME}=?", arrayOf(DOSSIER, FICHIER), null
            )?.use { c ->
                while (c.moveToNext()) cr.delete(android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0)), null, null)
            }
        } catch (e: Exception) { }
        return try {
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, FICHIER)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, DOSSIER)
            }
            val u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return false
            cr.openOutputStream(u)?.use { it.write(texte.toByteArray()) }
            true
        } catch (e: Exception) { false }
    }
}
