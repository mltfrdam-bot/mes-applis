package fr.mesapplis.lecteur

import android.content.Context
import android.media.audiofx.Equalizer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Enchaînement entre deux morceaux : fondu enchaîné, ou mix automatique calé sur le rythme.
 * Un second lecteur (B) joue le début du morceau suivant pendant la fin du morceau en cours (A),
 * puis le lecteur principal reprend la main exactement là où B en est (la notification ne change pas).
 *
 * Mix en 3 phases : préparer (attendre un temps fort, caler le tempo) → annoncer (B en fond, sans basses)
 * → enchaîner (les basses passent sur B, A s'efface).
 */
class Enchaineur(private val ctx: Context, private val a: ExoPlayer) {

    companion object {
        const val AUCUN = 0
        const val FONDU = 1
        const val MIX = 2
        /** Réglages possibles, dans l'ordre du bouton ⇄ : (mode, secondes de fondu). */
        val choix = listOf(AUCUN to 0, FONDU to 3, FONDU to 6, FONDU to 10, MIX to 0)
        private const val LATENCE_MS = 70L
    }

    private val h = Handler(Looper.getMainLooper())
    private var b: ExoPlayer? = null
    private var eqA: Equalizer? = null
    private var eqB: Equalizer? = null

    /** Une transition est en cours (B joue). */
    var enCours = false; private set
    /** Le lecteur principal est en train de reprendre la main (ne pas traiter comme un changement par l'utilisateur). */
    var bascule = false; private set

    var mode = AUCUN; private set
    var secondes = 6; private set

    // Plan de la transition (temps réel, en ms depuis SystemClock.uptimeMillis)
    private var tAnnonce = 0L   // B démarre (doucement, sans basses en mix)
    private var tEnchaine = 0L  // les basses passent sur B, A commence à baisser
    private var tFin = 0L       // A est silencieux : le lecteur principal reprend
    private var volAnnonce = 0f
    private var vitesseB = 1f
    private var indexSuivant = C.INDEX_UNSET
    private var basseCoupeeA = false

    fun lireReglage() {
        val b = Base.de(ctx)
        mode = b.lire("ench_mode")?.toIntOrNull() ?: AUCUN
        secondes = b.lire("ench_sec")?.toIntOrNull() ?: 6
    }

    /** Bouton ⇄ : passe au réglage suivant et retourne son nom. */
    fun reglageSuivant(): String {
        val i = choix.indexOfFirst { it.first == mode && (mode != FONDU || it.second == secondes) }
        val (m, s) = choix[(i + 1) % choix.size]
        Base.de(ctx).ecrire("ench_mode", m.toString())
        if (s > 0) Base.de(ctx).ecrire("ench_sec", s.toString())
        lireReglage()
        return nom()
    }

    fun nom(): String = when (mode) {
        FONDU -> "⇄ Fondu $secondes s"
        MIX -> "⇄ Mix auto"
        else -> "⇄ Sans fondu"
    }

    private val tic = object : Runnable {
        override fun run() {
            if (enCours) avancer() else surveiller()
            h.postDelayed(this, if (enCours) 30 else 250)
        }
    }

    fun demarrer() { lireReglage(); h.post(tic) }

    fun arreter() { h.removeCallbacks(tic); annuler() }

    // ----- Surveillance : faut-il lancer une transition ? -----

    private fun suivant(): MediaItem? {
        val i = a.nextMediaItemIndex
        if (i == C.INDEX_UNSET || a.repeatMode == Player.REPEAT_MODE_ONE) return null
        return a.getMediaItemAt(i)
    }

    private fun compatible(cur: MediaItem?, nxt: MediaItem?): Boolean {
        if (cur == null || nxt == null) return false
        if (Format.estVideo(cur.mediaId) || Format.estVideo(nxt.mediaId)) return false
        // Deux pistes d'un même album dans le même dossier : enchaînement d'origine respecté
        val alA = cur.mediaMetadata.albumTitle?.toString() ?: ""
        val alB = nxt.mediaMetadata.albumTitle?.toString() ?: ""
        val dA = Emplacement.de(cur)?.dossier; val dB = Emplacement.de(nxt)?.dossier
        if (alA.isNotEmpty() && alA == alB && dA == dB && mode != MIX) return false
        return true
    }

    /** Prépare l'analyse du morceau en cours et du suivant (mix). */
    fun preparerAnalyses() {
        if (mode != MIX) return
        a.currentMediaItem?.let { AnalyseMix.preparer(ctx, Emplacement.de(it)?.empreinte, android.net.Uri.parse(it.mediaId)) }
        suivant()?.let { AnalyseMix.preparer(ctx, Emplacement.de(it)?.empreinte, android.net.Uri.parse(it.mediaId)) }
    }

    private fun surveiller() {
        if (mode == MIX && a.isPlaying) preparerAnalyses()
        if (mode == AUCUN || !a.isPlaying || a.playbackParameters.speed != 1f && !vitesseEnRetour) return
        val cur = a.currentMediaItem; val nxt = suivant() ?: return
        if (!compatible(cur, nxt)) return
        val dur = a.duration; if (dur == C.TIME_UNSET || dur < 30_000) return
        val pos = a.currentPosition
        if (mode == FONDU) {
            val l = secondes * 1000L
            if (dur - pos <= l + 300) lancerFondu(nxt, maxOf(500L, dur - pos - 300))
        } else {
            val anaA = AnalyseMix.connue(Emplacement.de(cur!!)?.empreinte)
            val finMusique = minOf(dur - 400, anaA?.finMs ?: dur)
            val l = dureeMix(anaA)
            if (finMusique - pos <= l + 1500) lancerMix(nxt, rapide = false)
        }
    }

    private fun dureeMix(anaA: Analyse?): Long = if (anaA != null && anaA.regulier) (anaA.periodeMs * 16).toLong() else 8000L

    // ----- Lancement -----

    private fun creerB(nxt: MediaItem, debutMs: Long): ExoPlayer {
        val p = ExoPlayer.Builder(ctx)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
            .build()
        p.setMediaItem(nxt, debutMs)
        p.volume = 0f
        p.prepare()
        return p
    }

    private fun lancerFondu(nxt: MediaItem, longueur: Long) {
        val anaB = AnalyseMix.connue(Emplacement.de(nxt)?.empreinte)
        b = creerB(nxt, anaB?.debutMs?.coerceAtMost(15_000) ?: 0L)
        indexSuivant = a.nextMediaItemIndex
        val t = SystemClock.uptimeMillis()
        tAnnonce = t; tEnchaine = t; tFin = t + longueur
        volAnnonce = 0f; vitesseB = 1f
        enCours = true
        b?.play()
    }

    /** Mix : [rapide] = bouton « Mixer maintenant » (démarre au prochain temps fort, pas en fin de morceau). */
    private fun lancerMix(nxt: MediaItem, rapide: Boolean) {
        val anaA = AnalyseMix.connue(Emplacement.de(a.currentMediaItem ?: return)?.empreinte)
        val anaB = AnalyseMix.connue(Emplacement.de(nxt)?.empreinte)
        val pos = a.currentPosition
        val calage = anaA != null && anaB != null && anaA.regulier && anaB.regulier
        // Vitesse du morceau suivant pour caler les tempos (±8 % au plus, voix inchangée)
        vitesseB = 1f
        if (calage) {
            var r = anaA!!.bpm / anaB!!.bpm
            if (r > 1.5f) r /= 2 else if (r < 0.67f) r *= 2
            if (abs(r - 1f) <= 0.08f) vitesseB = r
        }
        val calageOk = calage && vitesseB != 1f || calage && abs(anaA!!.bpm / anaB!!.bpm - 1f) < 0.005f
        // Début de la transition : sur un temps fort du morceau en cours (toutes les 4 mesures si possible)
        val dur = a.duration
        val l = dureeMix(anaA)
        val departA: Long = if (anaA != null && anaA.regulier) {
            val mesure = anaA.periodeMs * 4
            val cible = if (rapide) pos + 600 else minOf(dur - 400, anaA.finMs) - l
            val k = kotlin.math.ceil((maxOf(pos + 600, cible) - anaA.phaseMs) / mesure)
            (anaA.phaseMs + k * mesure).toLong()
        } else pos + 600
        // Début du morceau suivant : sa première note, calée sur un de ses temps
        val debutB = when {
            anaB == null -> 0L
            calageOk -> anaB.tempsApres(anaB.debutMs.coerceAtMost(20_000))
            else -> anaB.debutMs.coerceAtMost(15_000)
        }
        b = creerB(nxt, debutB)
        b?.playbackParameters = PlaybackParameters(vitesseB)
        indexSuivant = a.nextMediaItemIndex
        val maintenant = SystemClock.uptimeMillis()
        val attente = ((departA - pos) / a.playbackParameters.speed).toLong().coerceAtLeast(0)
        val moitie = if (anaA != null && anaA.regulier) (anaA.periodeMs * 8).toLong() else l / 2
        tAnnonce = maintenant + attente - LATENCE_MS
        tEnchaine = tAnnonce + moitie
        tFin = tEnchaine + moitie
        // Ne jamais dépasser la fin réelle du morceau en cours
        val reste = ((dur - 300 - pos) / a.playbackParameters.speed).toLong()
        if (tFin - maintenant > reste) { tFin = maintenant + reste; tEnchaine = minOf(tEnchaine, tFin - 1500); tAnnonce = minOf(tAnnonce, tEnchaine - 1500) }
        volAnnonce = 0.45f
        enCours = true
        eqB = egaliseur(b!!.audioSessionId)
        couperBasses(eqB, true)
    }

    /** Bouton ⏭🎚 : mixer maintenant vers le morceau suivant ; pendant une transition, l'accélère. */
    fun mixerMaintenant(): String? {
        if (enCours) {
            val t = SystemClock.uptimeMillis()
            if (t < tAnnonce) tAnnonce = t
            if (t < tEnchaine) tEnchaine = t
            tFin = minOf(tFin, t + 2500)
            return "⏩ Enchaînement rapide"
        }
        val nxt = suivant() ?: return "Pas de morceau suivant"
        if (Format.estVideo(a.currentMediaItem?.mediaId) || Format.estVideo(nxt.mediaId)) return "Pas de mix avec une vidéo"
        if (!a.isPlaying) { a.seekToNextMediaItem(); return null }
        AnalyseMix.preparer(ctx, Emplacement.de(a.currentMediaItem!!)?.empreinte, android.net.Uri.parse(a.currentMediaItem!!.mediaId))
        AnalyseMix.preparer(ctx, Emplacement.de(nxt)?.empreinte, android.net.Uri.parse(nxt.mediaId))
        lancerMix(nxt, rapide = true)
        return "🎚 Mix en préparation…"
    }

    // ----- Déroulement -----

    private fun courbe(x: Float) = sin(x.coerceIn(0f, 1f) * PI / 2).toFloat() // fondu « puissance égale »

    private fun avancer() {
        val bb = b ?: return annuler()
        if (!a.playWhenReady) { annuler(); return } // pause par l'utilisateur
        val t = SystemClock.uptimeMillis()
        if (t >= tAnnonce && !bb.isPlaying && bb.playbackState == Player.STATE_READY) bb.play()
        when {
            t < tAnnonce -> { bb.volume = 0f }
            t < tEnchaine -> {
                bb.volume = volAnnonce * courbe((t - tAnnonce).toFloat() / maxOf(1L, tEnchaine - tAnnonce))
            }
            t < tFin -> {
                if (!basseCoupeeA && volAnnonce > 0f) {
                    // Les basses passent sur le nouveau morceau
                    eqA = egaliseur(a.audioSessionId); couperBasses(eqA, true); couperBasses(eqB, false); basseCoupeeA = true
                }
                val x = (t - tEnchaine).toFloat() / maxOf(1L, tFin - tEnchaine)
                bb.volume = volAnnonce + (1f - volAnnonce) * courbe(x)
                a.volume = cos(x.coerceIn(0f, 1f) * PI / 2).toFloat()
            }
            else -> basculer()
        }
    }

    /** Le lecteur principal reprend la main sur le morceau suivant, là où B en est. */
    private fun basculer() {
        val bb = b ?: return annuler()
        if (bascule) return
        bascule = true
        val posB = bb.currentPosition + (LATENCE_MS * bb.playbackParameters.speed).toLong()
        a.volume = 0f
        a.playbackParameters = PlaybackParameters(vitesseB)
        a.seekTo(indexSuivant, posB)
        a.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) return
                a.removeListener(this)
                // Court relais pour éviter tout trou
                val debut = SystemClock.uptimeMillis()
                h.post(object : Runnable {
                    override fun run() {
                        val x = ((SystemClock.uptimeMillis() - debut) / 150f).coerceIn(0f, 1f)
                        a.volume = x; b?.volume = 1f - x
                        if (x < 1f) h.postDelayed(this, 15) else { liberer(); retourVitesse() }
                    }
                })
            }
        })
        enCours = false
        // Sécurité : si le lecteur principal ne signale rien, on termine quand même le relais
        h.postDelayed({ if (bascule) liberer() }, 2500)
    }

    private var vitesseEnRetour = false

    /** Après un mix calé, la vitesse revient doucement à la normale (8 s). */
    private fun retourVitesse() {
        val v0 = a.playbackParameters.speed
        if (abs(v0 - 1f) < 0.001f) return
        vitesseEnRetour = true
        val debut = SystemClock.uptimeMillis()
        h.post(object : Runnable {
            override fun run() {
                val x = ((SystemClock.uptimeMillis() - debut) / 8000f).coerceIn(0f, 1f)
                a.playbackParameters = PlaybackParameters(v0 + (1f - v0) * x)
                if (x < 1f) h.postDelayed(this, 200) else vitesseEnRetour = false
            }
        })
    }

    private fun liberer() {
        b?.release(); b = null
        couperBasses(eqA, false)
        try { eqA?.release() } catch (e: Exception) { }
        try { eqB?.release() } catch (e: Exception) { }
        eqA = null; eqB = null
        basseCoupeeA = false
        bascule = false
        a.volume = 1f
    }

    /** Annule une transition (pause, saut, morceau changé par l'utilisateur) : tout revient à la normale. */
    fun annuler() {
        if (!enCours && b == null) return
        enCours = false
        liberer()
        if (abs(a.playbackParameters.speed - 1f) > 0.001f && !vitesseEnRetour) a.playbackParameters = PlaybackParameters(1f)
    }

    // ----- Basses (égaliseur d'Android) -----

    private fun egaliseur(session: Int): Equalizer? = try {
        Equalizer(0, session).apply { enabled = true }
    } catch (e: Throwable) { null }

    private fun couperBasses(eq: Equalizer?, oui: Boolean) {
        eq ?: return
        try {
            val min = eq.bandLevelRange[0]
            for (i in 0 until eq.numberOfBands) {
                val centre = eq.getCenterFreq(i.toShort()) / 1000 // Hz
                eq.setBandLevel(i.toShort(), if (oui && centre < 300) min else 0)
            }
        } catch (e: Throwable) { }
    }
}
