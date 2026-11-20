package fr.mesapplis.lecteur

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import java.util.concurrent.Executors

/** Pochettes : celle du fichier, sinon une image du dossier, sinon une pochette générée (couleur + initiales). */
object Pochettes {
    private val memoire = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val travail = Executors.newFixedThreadPool(2)
    private val principal = Handler(Looper.getMainLooper())

    private val couleurs = intArrayOf(
        0xFF3D7BE0.toInt(), 0xFF2F9E8F.toInt(), 0xFFD9822B.toInt(), 0xFF8E5CD9.toInt(),
        0xFFD9534F.toInt(), 0xFF4A9D3F.toInt(), 0xFF2E86AB.toInt(), 0xFFB5517A.toInt(),
    )

    fun vider() = memoire.evictAll()

    fun charger(vue: ImageView, uri: Uri, empl: Emplacement?, titre: String, taille: Int) {
        val cle = "$uri@$taille"
        vue.tag = cle
        memoire.get(cle)?.let { vue.setImageBitmap(it); return }
        vue.setImageBitmap(generee(titre, taille))
        val ctx = vue.context.applicationContext
        travail.execute {
            val b = lire(ctx, uri, empl, taille) ?: generee(titre, taille)
            memoire.put(cle, b)
            principal.post { if (vue.tag == cle) vue.setImageBitmap(b) }
        }
    }

    /** Lecture directe (à appeler hors du fil principal). */
    fun lire(ctx: Context, uri: Uri, empl: Emplacement?, taille: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= 29) {
            try { return ctx.contentResolver.loadThumbnail(uri, Size(taille, taille), null) } catch (e: Exception) { }
        } else {
            try {
                val r = MediaMetadataRetriever()
                r.setDataSource(ctx, uri)
                val d = r.embeddedPicture
                r.release()
                if (d != null) decoder(d, taille)?.let { return it }
            } catch (e: Exception) { }
        }
        if (empl == null) return null
        val base = empl.nom.substringBeforeLast('.')
        val candidats = listOf(
            "$base.jpg", "$base.webp", "$base.png",
            "cover.jpg", "folder.jpg", "Cover.jpg", "Folder.jpg", "cover.png", "folder.png", "AlbumArt.jpg",
        )
        for (n in candidats) {
            val u = Acces.uriFichier(ctx, empl.volume, empl.dossier, n) ?: return null
            val octets = Acces.lireOctets(ctx, u) ?: continue
            decoder(octets, taille)?.let { return it }
        }
        return null
    }

    private fun decoder(d: ByteArray, taille: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(d, 0, d.size, o)
        var ech = 1
        while (o.outWidth / (ech * 2) >= taille && o.outHeight / (ech * 2) >= taille) ech *= 2
        return BitmapFactory.decodeByteArray(d, 0, d.size, BitmapFactory.Options().apply { inSampleSize = ech })
    }

    fun couleurDe(texte: String): Int = couleurs[Math.floorMod(texte.hashCode(), couleurs.size)]

    fun generee(titre: String, taille: Int): Bitmap {
        val cle = "gen:$titre@$taille"
        memoire.get(cle)?.let { return it }
        val b = Bitmap.createBitmap(taille, taille, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val fond = couleurDe(titre)
        c.drawColor(fond)
        val mots = titre.split(' ', '-', '_').filter { it.isNotBlank() && it[0].isLetterOrDigit() }
        val initiales = mots.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "♪" }
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = taille * 0.38f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val y = taille / 2f - (p.descent() + p.ascent()) / 2f
        c.drawText(initiales, taille / 2f, y, p)
        memoire.put(cle, b)
        return b
    }
}
