package fr.mesapplis.lecteur

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Analyse d'un morceau pour le mix automatique (faite une seule fois, gardée dans la base) :
 * tempo (BPM), position des temps, rythme régulier ou non, début et fin de la musique (sans les blancs).
 */
class Analyse(val bpm: Float, val phaseMs: Long, val regulier: Boolean, val debutMs: Long, val finMs: Long) {
    val periodeMs: Double get() = 60_000.0 / bpm
    /** Premier temps (battement) à partir de [t] ms dans le morceau. */
    fun tempsApres(t: Long): Long {
        val p = periodeMs
        val k = kotlin.math.ceil((t - phaseMs) / p)
        return (phaseMs + k * p).toLong()
    }
}

object AnalyseMix {
    const val VERSION = 1
    private val memo = ConcurrentHashMap<String, Analyse>()
    private val enCours = ConcurrentHashMap.newKeySet<String>()
    private val echecs = ConcurrentHashMap.newKeySet<String>()
    private val file = Executors.newSingleThreadExecutor()

    fun connue(e: String?): Analyse? = e?.let { memo[it] }

    /** Charge depuis la base, ou analyse en arrière-plan (1 à 3 s par morceau). */
    fun preparer(ctx: Context, e: String?, uri: Uri) {
        if (e == null || memo.containsKey(e) || e in echecs || !enCours.add(e)) return
        file.execute {
            try {
                val a = lire(ctx, e) ?: analyser(ctx, uri)?.also { ecrire(ctx, e, it) }
                if (a != null) memo[e] = a else echecs += e
            } catch (t: Throwable) { } finally { enCours.remove(e) }
        }
    }

    private fun lire(ctx: Context, e: String): Analyse? =
        Base.de(ctx).readableDatabase.rawQuery("SELECT bpm, phase, regulier, debut, fin FROM analyses WHERE empreinte=? AND version=?", arrayOf(e, VERSION.toString())).use {
            if (it.moveToFirst()) Analyse(it.getFloat(0), it.getLong(1), it.getInt(2) == 1, it.getLong(3), it.getLong(4)) else null
        }

    private fun ecrire(ctx: Context, e: String, a: Analyse) {
        Base.de(ctx).writableDatabase.insertWithOnConflict("analyses", null, ContentValues().apply {
            put("empreinte", e); put("bpm", a.bpm); put("phase", a.phaseMs); put("regulier", if (a.regulier) 1 else 0)
            put("debut", a.debutMs); put("fin", a.finMs); put("version", VERSION)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ----- Décodage -----

    private const val CIBLE = 11025
    private const val N = 512
    private const val PAS = 128

    fun analyser(ctx: Context, uri: Uri): Analyse? {
        val ex = MediaExtractor()
        try { ex.setDataSource(ctx, uri, null) } catch (e: Exception) { ex.release(); return null }
        var piste = -1
        for (i in 0 until ex.trackCount) if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { piste = i; break }
        if (piste < 0) { ex.release(); return null }
        ex.selectTrack(piste)
        val fmt = ex.getTrackFormat(piste)
        val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        var freq = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var canaux = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var flottant = false
        val codec = try { MediaCodec.createDecoderByType(mime) } catch (e: Exception) { ex.release(); return null }
        val mono = FloatArrayList()
        try {
            codec.configure(fmt, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var finEntree = false
            var facteur = maxOf(1, freq / CIBLE)
            var somme = 0f; var compte = 0
            while (true) {
                if (!finEntree) {
                    val i = codec.dequeueInputBuffer(5000)
                    if (i >= 0) {
                        val b = codec.getInputBuffer(i)!!
                        val n = ex.readSampleData(b, 0)
                        if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); finEntree = true }
                        else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 5000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    freq = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    canaux = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    flottant = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    facteur = maxOf(1, freq / CIBLE)
                } else if (o >= 0) {
                    val b = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                    b.position(info.offset); b.limit(info.offset + info.size)
                    if (flottant) {
                        val fb = b.asFloatBuffer()
                        while (fb.remaining() >= canaux) {
                            var s = 0f; for (c in 0 until canaux) s += fb.get()
                            somme += s / canaux; if (++compte == facteur) { mono.ajouter(somme / facteur); somme = 0f; compte = 0 }
                        }
                    } else {
                        val sb = b.asShortBuffer()
                        while (sb.remaining() >= canaux) {
                            var s = 0f; for (c in 0 until canaux) s += sb.get() / 32768f
                            somme += s / canaux; if (++compte == facteur) { mono.ajouter(somme / facteur); somme = 0f; compte = 0 }
                        }
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    if (mono.taille > CIBLE * 60 * 15) break // 15 min maximum
                }
            }
        } catch (e: Exception) {
            if (mono.taille < CIBLE * 10) return null
        } finally {
            try { codec.stop() } catch (e: Exception) { }
            codec.release(); ex.release()
        }
        val frequence = freq.toFloat() / maxOf(1, freq / CIBLE)
        return calculer(mono.tableau(), frequence)
    }

    // ----- Calcul -----

    private fun calculer(x: FloatArray, fs: Float): Analyse? {
        val nb = (x.size - N) / PAS
        if (nb < 200) return null
        val fr = fs / PAS // trames par seconde
        val fen = FloatArray(N) { (0.5 - 0.5 * cos(2 * Math.PI * it / (N - 1))).toFloat() }
        val re = FloatArray(N); val im = FloatArray(N)
        val avant = FloatArray(N / 2)
        val flux = FloatArray(nb)
        val energie = FloatArray(nb)
        for (t in 0 until nb) {
            var e = 0f
            for (i in 0 until N) { val v = x[t * PAS + i]; re[i] = v * fen[i]; im[i] = 0f; e += v * v }
            energie[t] = sqrt(e / N)
            fft(re, im)
            var f = 0f
            for (k in 1 until N / 2) {
                val m = ln(1f + 100f * hypot(re[k], im[k]))
                val d = m - avant[k]; if (d > 0) f += d
                avant[k] = m
            }
            flux[t] = f
        }
        // Enveloppe des attaques, centrée
        val moy = flux.average().toFloat()
        val env = FloatArray(nb) { maxOf(0f, flux[it] - moy) }

        // Début et fin de la musique (sans les blancs)
        val maxE = energie.maxOrNull() ?: return null
        val seuil = maxE * 0.06f
        val debut = energie.indexOfFirst { it > seuil }.coerceAtLeast(0)
        val fin = energie.indexOfLast { it > seuil }.coerceAtLeast(debut)

        // Tempo : autocorrélation (60 à 190 BPM), préférence autour de 120
        val lagMin = (60 * fr / 190).toInt(); val lagMax = (60 * fr / 60).toInt()
        var meilleur = 0.0; var lag0 = lagMin
        for (lag in lagMin..lagMax) {
            var s = 0.0
            for (t in debut until minOf(fin, nb - lag)) s += env[t] * env[t + lag]
            val bpm = 60 * fr / lag
            val poids = kotlin.math.exp(-0.5 * (ln(bpm / 120.0) / 0.5).let { it * it })
            if (s * poids > meilleur) { meilleur = s * poids; lag0 = lag }
        }
        // Affinage : peigne sur tout le morceau, période fractionnaire
        var meilleurP = lag0.toDouble(); var meilleurePhase = 0.0; var score = -1.0
        var p = lag0 - 1.0
        while (p <= lag0 + 1.0) {
            var ph = 0.0
            while (ph < p) {
                var s = 0.0; var n = 0
                var pos = debut + ph
                while (pos < fin) { s += interp(env, pos); n++; pos += p }
                if (n > 0 && s / n > score) { score = s / n; meilleurP = p; meilleurePhase = debut + ph }
                ph += 1.0
            }
            p += 0.02
        }
        val moyEnv = env.sliceArray(debut..fin).average()
        val regulier = moyEnv > 0 && score / moyEnv > 1.6
        var bpm = (60 * fr / meilleurP).toFloat()
        if (bpm < 80) bpm *= 2
        if (bpm > 165) bpm /= 2
        val msParTrame = 1000.0 / fr
        return Analyse(bpm, (meilleurePhase * msParTrame).toLong(), regulier, (debut * msParTrame).toLong(), (fin * msParTrame).toLong())
    }

    private fun interp(a: FloatArray, pos: Double): Double {
        val i = pos.toInt(); if (i + 1 >= a.size) return 0.0
        val f = pos - i
        return a[i] * (1 - f) + a[i + 1] * f
    }

    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var l = 2
        while (l <= n) {
            val ang = -2 * Math.PI / l
            val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f; var ci = 0f
                for (k in 0 until l / 2) {
                    val a = i + k; val b = a + l / 2
                    val tr = re[b] * cr - im[b] * ci; val ti = re[b] * ci + im[b] * cr
                    re[b] = re[a] - tr; im[b] = im[a] - ti; re[a] += tr; im[a] += ti
                    val n2 = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = n2
                }
                i += l
            }
            l = l shl 1
        }
    }

    /** Tableau de nombres qui grandit sans tout recopier à chaque ajout. */
    private class FloatArrayList {
        private var t = FloatArray(1 shl 16)
        var taille = 0; private set
        fun ajouter(v: Float) { if (taille == t.size) t = t.copyOf(t.size * 2); t[taille++] = v }
        fun tableau(): FloatArray = t.copyOf(taille)
    }
}
