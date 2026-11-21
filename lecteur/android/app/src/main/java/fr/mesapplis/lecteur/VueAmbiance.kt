package fr.mesapplis.lecteur

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Dessin de l'économiseur « Ambiance » : réagit à AnalyseSon, couleurs reprises de la pochette. */
class VueAmbiance(ctx: Context) : View(ctx) {

    enum class Style(val nom: String) { ONDES("Ondes"), BARRES("Barres"), PARTICULES("Particules"), VINYLE("Vinyle") }

    var style = Style.ONDES
    var anime = true

    private var c1 = 0xFF6A8DD6.toInt()
    private var c2 = 0xFFE58FB3.toInt()
    private var pochette: Bitmap? = null
    private var shaderPochette: BitmapShader? = null

    private val bandes = FloatArray(AnalyseSon.NB)
    private var basses = 0f
    private var niveau = 0f
    private var temps = 0f
    private var dernier = 0L
    private var angle = 0f

    private val fond = Paint()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chemin = Path()
    private val matrice = Matrix()

    private class Particule(var x: Float, var y: Float, var r: Float, var v: Float, var a: Int, var bande: Int)
    private val particules = ArrayList<Particule>()

    fun couleurs(a: Int, b: Int, image: Bitmap?) {
        c1 = a; c2 = b
        pochette = image
        shaderPochette = image?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        majFond()
        invalidate()
    }

    private fun majFond() {
        if (width == 0) return
        // Fond clair et coloré (pas de mode sombre) : les deux couleurs de la pochette, éclaircies
        fond.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), eclaircir(c1, 0.55f), eclaircir(c2, 0.55f), Shader.TileMode.CLAMP)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        majFond()
        particules.clear()
        repeat(70) { particules += nouvelle(w, h, Random.nextFloat() * h) }
    }

    private fun nouvelle(w: Int, h: Int, y: Float) = Particule(
        Random.nextFloat() * w, y, 4f + Random.nextFloat() * 14f, 0.3f + Random.nextFloat() * 1.2f, 90 + Random.nextInt(120), Random.nextInt(AnalyseSon.NB)
    )

    private fun suivreSon(dt: Float) {
        val frais = SystemClock.uptimeMillis() - AnalyseSon.maj < 300
        val cible = if (frais) AnalyseSon.bandes else null
        for (i in bandes.indices) {
            val t = cible?.get(i) ?: 0f
            // Monte vite, redescend doucement
            bandes[i] += (t - bandes[i]) * (if (t > bandes[i]) 0.6f else 0.12f)
        }
        val tb = if (frais) AnalyseSon.basses else 0f
        basses += (tb - basses) * (if (tb > basses) 0.5f else 0.1f)
        val tn = if (frais) AnalyseSon.niveau else 0f
        niveau += (tn - niveau) * 0.2f
        temps += dt * (0.6f + basses * 2f)
    }

    override fun onDraw(canvas: Canvas) {
        val maintenant = SystemClock.uptimeMillis()
        val dt = if (dernier == 0L) 0.016f else ((maintenant - dernier) / 1000f).coerceAtMost(0.05f)
        dernier = maintenant
        suivreSon(dt)
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, fond)
        when (style) {
            Style.ONDES -> ondes(canvas, w, h)
            Style.BARRES -> barres(canvas, w, h)
            Style.PARTICULES -> particules(canvas, w, h, dt)
            Style.VINYLE -> vinyle(canvas, w, h, dt)
        }
        if (anime) postInvalidateOnAnimation()
    }

    private fun ondes(c: Canvas, w: Float, h: Float) {
        p.style = Paint.Style.FILL
        val couches = listOf(Triple(c1, 0, 0.35f), Triple(c2, 10, 0.5f), Triple(Color.WHITE, 22, 0.65f))
        couches.forEachIndexed { n, (couleur, bande, base) ->
            val force = (bandes[bande] + bandes[bande + 3]) / 2f
            val amp = h * (0.03f + force * 0.16f)
            chemin.reset()
            chemin.moveTo(0f, h)
            var x = 0f
            while (x <= w + 8f) {
                val y = h * base + sin(x / w * (2.5f + n) * 2 * PI.toFloat() + temps * (1.2f + n * 0.5f)) * amp +
                    sin(x / w * 7f + temps * 2.3f) * amp * 0.25f
                chemin.lineTo(x, y)
                x += 8f
            }
            chemin.lineTo(w, h); chemin.close()
            p.color = couleur
            p.alpha = 120 + n * 30
            c.drawPath(chemin, p)
        }
    }

    private fun barres(c: Canvas, w: Float, h: Float) {
        val n = AnalyseSon.NB
        val pas = w / n
        val largeur = pas * 0.66f
        val bas = h * 0.78f
        p.style = Paint.Style.FILL
        for (i in 0 until n) {
            val hauteur = (h * 0.05f + bandes[i] * h * 0.55f)
            val x = i * pas + (pas - largeur) / 2
            p.shader = LinearGradient(0f, bas - hauteur, 0f, bas, Color.WHITE, c1, Shader.TileMode.CLAMP)
            c.drawRoundRect(x, bas - hauteur, x + largeur, bas, largeur / 2, largeur / 2, p)
            // Reflet
            p.shader = null
            p.color = c2; p.alpha = 70
            c.drawRoundRect(x, bas + 6f, x + largeur, bas + 6f + hauteur * 0.25f, largeur / 2, largeur / 2, p)
            p.alpha = 255
        }
        p.shader = null
    }

    private fun particules(c: Canvas, w: Float, h: Float, dt: Float) {
        p.style = Paint.Style.FILL
        // Halo central qui pulse avec les basses
        val rayon = min(w, h) * (0.18f + basses * 0.22f)
        p.shader = RadialGradient(w / 2, h / 2, rayon.coerceAtLeast(1f), intArrayOf(Color.WHITE, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        p.alpha = 200
        c.drawCircle(w / 2, h / 2, rayon, p)
        p.shader = null
        for (q in particules) {
            val force = bandes[q.bande]
            q.y -= (q.v + force * 4f + niveau * 6f) * 60f * dt
            q.x += sin(temps + q.r) * 0.6f
            if (q.y < -30f) { val r = nouvelle(width, height, h + 20f); q.x = r.x; q.y = r.y; q.r = r.r; q.v = r.v; q.bande = r.bande }
            p.color = if (q.bande % 2 == 0) c1 else Color.WHITE
            p.alpha = (q.a * (0.5f + force * 0.5f)).toInt().coerceIn(40, 255)
            c.drawCircle(q.x, q.y, q.r * (0.7f + force * 0.9f), p)
        }
    }

    private fun vinyle(c: Canvas, w: Float, h: Float, dt: Float) {
        val cx = w / 2; val cy = h * 0.45f
        val r = min(w, h) * 0.34f * (1f + basses * 0.06f)
        angle = (angle + dt * 33.3f / 60f * 360f) % 360f
        // Couronne de bandes autour du disque
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = r * 0.035f
        for (i in 0 until 64) {
            val v = bandes[if (i < 32) i else 63 - i]
            val a = (i / 64f) * 2 * PI.toFloat() - PI.toFloat() / 2
            val r1 = r * 1.08f; val r2 = r1 + r * (0.05f + v * 0.35f)
            p.color = if (i % 2 == 0) Color.WHITE else c1
            c.drawLine(cx + cos(a) * r1, cy + sin(a) * r1, cx + cos(a) * r2, cy + sin(a) * r2, p)
        }
        // Disque noir avec sillons
        p.style = Paint.Style.FILL
        p.color = 0xFF16171A.toInt()
        c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.2f
        p.color = 0x33FFFFFF
        var s = r * 0.42f
        while (s < r * 0.97f) { c.drawCircle(cx, cy, s, p); s += r * 0.045f }
        // Pochette au centre, qui tourne
        p.style = Paint.Style.FILL
        val rc = r * 0.4f
        val sh = shaderPochette
        val b = pochette
        if (sh != null && b != null) {
            val e = (2 * rc) / min(b.width, b.height)
            matrice.reset()
            matrice.postTranslate(-b.width / 2f, -b.height / 2f)
            matrice.postScale(e, e)
            matrice.postRotate(angle)
            matrice.postTranslate(cx, cy)
            sh.setLocalMatrix(matrice)
            p.shader = sh
            c.drawCircle(cx, cy, rc, p)
            p.shader = null
        } else {
            p.color = c1
            c.drawCircle(cx, cy, rc, p)
        }
        p.color = Color.WHITE
        c.drawCircle(cx, cy, r * 0.03f, p)
        // Reflet qui tourne
        p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.05f; p.color = 0x22FFFFFF
        c.drawArc(cx - r * 0.8f, cy - r * 0.8f, cx + r * 0.8f, cy + r * 0.8f, angle * 0.2f - 30f, 40f, false, p)
        p.style = Paint.Style.FILL
    }

    companion object {
        fun eclaircir(c: Int, f: Float): Int {
            val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
            return Color.rgb((r + (255 - r) * f).toInt(), (g + (255 - g) * f).toInt(), (b + (255 - b) * f).toInt())
        }
    }
}
