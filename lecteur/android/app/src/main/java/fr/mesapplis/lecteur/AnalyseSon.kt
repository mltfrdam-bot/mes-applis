package fr.mesapplis.lecteur

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Analyse du son joué par le lecteur lui-même (pas de micro, pas d'autorisation) :
 * 32 bandes de fréquences (basses à gauche, aigus à droite), de 0 à 1, pour l'économiseur « Ambiance ».
 * Ne calcule rien tant que l'économiseur n'est pas affiché (batterie).
 */
object AnalyseSon : TeeAudioProcessor.AudioBufferSink {
    const val NB = 32
    private const val N = 1024

    @Volatile var actif = false
    @Volatile var bandes = FloatArray(NB)
    @Volatile var basses = 0f
    @Volatile var niveau = 0f
    /** Moment de la dernière analyse (pour tout faire retomber doucement en pause). */
    @Volatile var maj = 0L

    private var frequence = 44100
    private var canaux = 2
    private var encodage = C.ENCODING_PCM_16BIT
    private val mono = FloatArray(N)
    private var rempli = 0
    private val re = FloatArray(N)
    private val im = FloatArray(N)
    private val fenetre = FloatArray(N) { (0.5 - 0.5 * cos(2 * Math.PI * it / (N - 1))).toFloat() }
    private var limites = IntArray(NB + 1)
    private var maximum = 1f

    init { calculerLimites() }

    private fun calculerLimites() {
        // Bandes réparties « à l'oreille » (logarithmiques) de 40 Hz à 16 kHz
        val hzParCase = frequence.toFloat() / N
        limites = IntArray(NB + 1) { i ->
            val hz = 40.0 * (16000.0 / 40.0).pow(i.toDouble() / NB)
            (hz / hzParCase).toInt().coerceIn(1, N / 2)
        }
        for (i in 1..NB) if (limites[i] <= limites[i - 1]) limites[i] = (limites[i - 1] + 1).coerceAtMost(N / 2)
    }

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        frequence = if (sampleRateHz > 0) sampleRateHz else 44100
        canaux = channelCount.coerceAtLeast(1)
        encodage = encoding
        rempli = 0
        calculerLimites()
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (!actif) return
        val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        when (encodage) {
            C.ENCODING_PCM_16BIT -> {
                while (b.remaining() >= 2 * canaux) {
                    var s = 0f
                    for (c in 0 until canaux) s += b.short / 32768f
                    ajouter(s / canaux)
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                while (b.remaining() >= 4 * canaux) {
                    var s = 0f
                    for (c in 0 until canaux) s += b.float
                    ajouter(s / canaux)
                }
            }
            else -> return
        }
    }

    private fun ajouter(v: Float) {
        mono[rempli++] = v
        if (rempli == N) { analyser(); rempli = 0 }
    }

    private fun analyser() {
        var somme = 0f
        for (i in 0 until N) { re[i] = mono[i] * fenetre[i]; im[i] = 0f; somme += mono[i] * mono[i] }
        fft()
        val brut = FloatArray(NB)
        var plusGrand = 0f
        for (k in 0 until NB) {
            var m = 0f
            for (j in limites[k] until maxOf(limites[k + 1], limites[k] + 1)) m = maxOf(m, hypot(re[j], im[j]))
            // Les aigus sont naturellement plus faibles : on les remonte un peu
            val v = ln(1f + m * (1f + k * 0.12f))
            brut[k] = v
            plusGrand = maxOf(plusGrand, v)
        }
        // Gain automatique : s'adapte au volume de chaque morceau
        maximum = maxOf(plusGrand, maximum * 0.997f, 0.5f)
        val sortie = FloatArray(NB) { (brut[it] / maximum).coerceIn(0f, 1f) }
        bandes = sortie
        basses = (sortie[0] + sortie[1] + sortie[2] + sortie[3]) / 4f
        niveau = sqrt(somme / N).coerceIn(0f, 1f)
        maj = SystemClock.uptimeMillis()
    }

    /** Transformée de Fourier rapide (en place, N = 1024). */
    private fun fft() {
        var j = 0
        for (i in 1 until N) {
            var bit = N shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var longueur = 2
        while (longueur <= N) {
            val angle = -2 * Math.PI / longueur
            val wr = cos(angle).toFloat(); val wi = sin(angle).toFloat()
            var i = 0
            while (i < N) {
                var cr = 1f; var ci = 0f
                for (k in 0 until longueur / 2) {
                    val a = i + k; val b = a + longueur / 2
                    val tr = re[b] * cr - im[b] * ci
                    val ti = re[b] * ci + im[b] * cr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr; cr = ncr
                }
                i += longueur
            }
            longueur = longueur shl 1
        }
    }
}
