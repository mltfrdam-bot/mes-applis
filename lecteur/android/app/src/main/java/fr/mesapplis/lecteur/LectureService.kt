package fr.mesapplis.lecteur

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.common.audio.AudioProcessor
import android.content.Context
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Service de lecture : continue écran éteint, commandes dans la notification, l'écran verrouillé,
 * le casque Bluetooth et l'autoradio. Garde la file d'attente et la position dans la base.
 */
class LectureService : MediaSessionService() {

    private var session: MediaSession? = null
    private lateinit var joueur: ExoPlayer
    private val principal = Handler(Looper.getMainLooper())
    private val ecriture = Executors.newSingleThreadExecutor()
    private var ecouteComptee = false
    // Reprise par fichier (morceaux longs : cours, livres audio…)
    private var empEnCours: String? = null
    private var posEnCours = 0L
    private var dureeEnCours = 0L

    private val sauvegardeReguliere = object : Runnable {
        override fun run() {
            sauverPosition()
            if (joueur.isPlaying) principal.postDelayed(this, 5000)
        }
    }
    private val suiviPosition = object : Runnable {
        override fun run() {
            if (joueur.mediaItemCount > 0) {
                posEnCours = joueur.currentPosition
                val d = joueur.duration
                if (d > 0) dureeEnCours = d
            }
            principal.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Le son passe aussi par AnalyseSon (économiseur « Ambiance ») : aucune autorisation micro nécessaire
        val rendus = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf<AudioProcessor>(TeeAudioProcessor(AnalyseSon)))
                    .build()
        }
        joueur = ExoPlayer.Builder(this, rendus)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true
            )
            .setHandleAudioBecomingNoisy(true) // casque débranché ou Bluetooth coupé : pause
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        joueurActif = joueur
        val ouvrir = PendingIntent.getActivity(
            this, 0,
            Intent(this, LectureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaSession.Builder(this, joueur)
            .setCallback(Rappels())
            .setSessionActivity(ouvrir)
            .build()
        joueur.addListener(Ecouteur())
        Minuterie.joueur = joueur
        principal.post(suiviPosition)
        // Reprise exacte, même après redémarrage du téléphone
        restaurer()?.let {
            joueur.setMediaItems(it.mediaItems, it.startIndex, it.startPositionMs)
            joueur.prepare()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Appli balayée : la musique continue si elle joue, sinon on s'arrête proprement
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0 || p.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        joueurActif = null
        principal.removeCallbacks(sauvegardeReguliere)
        principal.removeCallbacks(suiviPosition)
        garderPositionFichier()
        Minuterie.annuler()
        Minuterie.joueur = null
        sauverPosition()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private inner class Ecouteur : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) sauverFile()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            garderPositionFichier()
            ecouteComptee = false
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) Minuterie.finDeTitre()
            sauverPosition()
            reprendreFichier(mediaItem)
            if (joueur.isPlaying) compterEcoute()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            sauverPosition()
            principal.removeCallbacks(sauvegardeReguliere)
            if (isPlaying) {
                if (!ecouteComptee) compterEcoute()
                principal.postDelayed(sauvegardeReguliere, 5000)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // Fichier supprimé ou illisible : on passe au suivant sans s'arrêter
            if (joueur.hasNextMediaItem()) {
                joueur.seekToNextMediaItem()
                joueur.prepare()
            }
        }
    }

    private fun compterEcoute() {
        val item = joueur.currentMediaItem ?: return
        val e = Emplacement.de(item) ?: return
        ecouteComptee = true
        val titre = item.mediaMetadata.title?.toString() ?: ""
        val artiste = item.mediaMetadata.artist?.toString() ?: ""
        val ctx = applicationContext
        ecriture.execute {
            val emp = e.empreinte ?: Bibliotheque.empreinte(ctx, Uri.parse(item.mediaId), 0) ?: return@execute
            Base.de(ctx).compterEcoute(emp, titre, artiste)
        }
    }

    /** Morceau de plus de 10 min quitté en cours de route : on garde l'endroit. */
    private fun garderPositionFichier() {
        val e = empEnCours ?: return
        val d = dureeEnCours
        if (d < LONG && !videoEnCours) return
        val pos = if (posEnCours < 30_000 || posEnCours > d - 30_000) 0L else posEnCours
        val base = Base.de(this)
        ecriture.execute { base.sauverPosition(e, pos) }
    }

    private var videoEnCours = false

    private fun reprendreFichier(item: MediaItem?) {
        val e = Emplacement.de(item)?.empreinte
        videoEnCours = Format.estVideo(item?.mediaId)
        empEnCours = e
        posEnCours = 0L
        dureeEnCours = 0L
        if (e == null) return
        val pos = Base.de(this).position(e)
        if (pos > 0 && joueur.currentPosition < 1000) joueur.seekTo(pos)
    }

    // ----- Sauvegarde de la file d'attente et de la position -----

    private fun sauverFile() {
        val tab = JSONArray()
        for (i in 0 until joueur.mediaItemCount) {
            val it = joueur.getMediaItemAt(i)
            val md = it.mediaMetadata
            val ex = md.extras
            tab.put(JSONObject().apply {
                put("u", it.mediaId)
                put("t", md.title?.toString() ?: "")
                put("a", md.artist?.toString() ?: "")
                put("l", md.albumTitle?.toString() ?: "")
                put("v", ex?.getString("vol") ?: "")
                put("d", ex?.getString("dossier") ?: "")
                put("n", ex?.getString("nom") ?: "")
                put("e", ex?.getString("emp") ?: "")
            })
        }
        val texte = tab.toString()
        val base = Base.de(this)
        ecriture.execute { base.ecrire("file", texte) }
        sauverPosition()
    }

    private fun sauverPosition() {
        if (joueur.mediaItemCount == 0) return
        val valeur = "${joueur.currentMediaItemIndex}:${joueur.currentPosition}"
        val base = Base.de(this)
        ecriture.execute { base.ecrire("position", valeur) }
    }

    private fun restaurer(): MediaSession.MediaItemsWithStartPosition? {
        val base = Base.de(this)
        val texte = base.lire("file") ?: return null
        return try {
            val tab = JSONArray(texte)
            val items = ArrayList<MediaItem>(tab.length())
            for (i in 0 until tab.length()) {
                val o = tab.getJSONObject(i)
                val u = o.getString("u")
                items += MediaItem.Builder()
                    .setMediaId(u)
                    .setUri(Uri.parse(u))
                    .setMediaMetadata(
                        Piste.metadonnees(
                            o.optString("t"), o.optString("a"), o.optString("l"), o.optString("v"), o.optString("d"), o.optString("n"),
                            o.optString("e").ifEmpty { null }
                        )
                    )
                    .build()
            }
            if (items.isEmpty()) return null
            val pos = (base.lire("position") ?: "0:0").split(':')
            val index = (pos.getOrNull(0)?.toIntOrNull() ?: 0).coerceIn(0, items.size - 1)
            val ms = pos.getOrNull(1)?.toLongOrNull() ?: 0L
            MediaSession.MediaItemsWithStartPosition(items, index, ms)
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val LONG = 10 * 60 * 1000L
        /** Le lecteur, pour l'écran vidéo (même appli, même processus). */
        @Volatile var joueurActif: ExoPlayer? = null
    }

    private inner class Rappels : MediaSession.Callback {
        // Les éléments envoyés par l'écran de l'appli arrivent sans leur adresse : on la remet
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.map { it.buildUpon().setUri(Uri.parse(it.mediaId)).build() }.toMutableList())

        // Bouton Lecture du casque ou de l'autoradio alors que l'appli était fermée
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val r = restaurer() ?: return Futures.immediateFailedFuture(UnsupportedOperationException("Rien à reprendre"))
            return Futures.immediateFuture(r)
        }
    }
}
