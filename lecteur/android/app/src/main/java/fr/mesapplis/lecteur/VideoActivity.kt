package fr.mesapplis.lecteur

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * 🎬 Vidéo plein écran.
 *  - Toucher : afficher / cacher les commandes.
 *  - Balayer à gauche / à droite : reculer / avancer de 10 s (double appui aussi).
 *  - Balayer en haut / en bas : luminosité (moitié gauche), volume (moitié droite).
 *  - Quitter avec le bouton Accueil : petite fenêtre flottante. 🎧 « Écouter seulement » : le son continue écran éteint.
 *  - Sous-titres (.srt / .vtt à côté de la vidéo) avec leur traduction 🌐.
 */
class VideoActivity : Activity() {

    private lateinit var racine: FrameLayout
    private lateinit var surface: SurfaceView
    private lateinit var commandes: LinearLayout
    private lateinit var haut: LinearLayout
    private lateinit var titre: TextView
    private lateinit var barre: SeekBar
    private lateinit var temps: TextView
    private lateinit var btnLecture: TextView
    private lateinit var btnSousTitres: TextView
    private lateinit var sousTitre: TextView
    private lateinit var info: TextView
    private lateinit var connexion: Connexion

    private val principal = Handler(Looper.getMainLooper())
    private val travail = Executors.newSingleThreadExecutor()
    private var glisse = false
    private var ecouterSeulement = false
    private var largeurVideo = 16
    private var hauteurVideo = 9
    private var afficheId: String? = null
    private var paroles: Paroles? = null
    private var sousTitresOui = true

    private val cacher = Runnable { montrerCommandes(false) }
    private val cacherInfo = Runnable { info.visibility = View.GONE }
    private val tic = object : Runnable {
        override fun run() { majProgression(); principal.postDelayed(this, 250) }
    }

    private val ecouteur = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = majTout()
        override fun onVideoSizeChanged(videoSize: VideoSize) = adapterTaille(videoSize)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // L'image suit la rotation de l'appareil (portrait ou paysage, au choix de l'utilisateur)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        sousTitresOui = Base.de(this).lire("video_st") != "0"
        construire()
        connexion = Connexion(this) { c ->
            c.addListener(ecouteur)
            attacher()
            majTout()
        }
        pleinEcran()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun bouton(t: String, clic: () -> Unit) = TextView(this).apply {
        text = t; textSize = 20f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setOnClickListener { clic(); relancerMinuteur() }
    }

    private fun construire() {
        racine = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surface = SurfaceView(this)
        racine.addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))

        sousTitre = TextView(this).apply {
            textSize = 18f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
            setPadding(dp(24), dp(4), dp(24), dp(4))
        }
        racine.addView(sousTitre, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = dp(36) })

        info = TextView(this).apply {
            textSize = 22f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0xAA000000.toInt()) }
            visibility = View.GONE
        }
        racine.addView(info, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        // En haut : retour, titre
        haut = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xAA000000.toInt(), 0x00000000))
            setPadding(dp(4), dp(8), dp(12), dp(18))
        }
        haut.addView(bouton("←") { fermer() })
        titre = TextView(this).apply { textSize = 16f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; maxLines = 2 }
        haut.addView(titre, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        racine.addView(haut, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // En bas : barre de temps et boutons
        commandes = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xCC000000.toInt(), 0x00000000))
            setPadding(dp(12), dp(24), dp(12), dp(10))
        }
        val ligneTemps = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        barre = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, parUtilisateur: Boolean) {
                    if (parUtilisateur) temps.text = "${Format.duree(p * duree() / 1000)} / ${Format.duree(duree())}"
                }
                override fun onStartTrackingTouch(s: SeekBar) { glisse = true; principal.removeCallbacks(cacher) }
                override fun onStopTrackingTouch(s: SeekBar) {
                    glisse = false
                    connexion.ctrl?.seekTo(s.progress * duree() / 1000)
                    relancerMinuteur()
                }
            })
        }
        ligneTemps.addView(barre, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        temps = TextView(this).apply { textSize = 13f; setTextColor(Color.WHITE) }
        ligneTemps.addView(temps)
        commandes.addView(ligneTemps)

        val ligneBoutons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        ligneBoutons.addView(bouton("🎧") { ecouter() })
        ligneBoutons.addView(bouton("⏮") { connexion.ctrl?.seekToPrevious() })
        ligneBoutons.addView(bouton("−10") { decaler(-10_000) })
        btnLecture = bouton("⏸") { connexion.ctrl?.let { Actions.lectureOuPause(it) } }.apply { textSize = 30f }
        ligneBoutons.addView(btnLecture)
        ligneBoutons.addView(bouton("+10") { decaler(10_000) })
        ligneBoutons.addView(bouton("⏭") { connexion.ctrl?.seekToNext() })
        btnSousTitres = bouton("💬") { basculerSousTitres() }
        ligneBoutons.addView(btnSousTitres)
        if (Build.VERSION.SDK_INT >= 26) ligneBoutons.addView(bouton("⧉") { fenetreFlottante() })
        commandes.addView(ligneBoutons)
        racine.addView(commandes, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        val gestes = GestureDetector(this, Gestes())
        racine.setOnTouchListener { _, e ->
            gestes.onTouchEvent(e)
            if (e.action == MotionEvent.ACTION_UP || e.action == MotionEvent.ACTION_CANCEL) finGeste()
            true
        }
        setContentView(racine)
    }

    // ----- Lecteur -----

    private fun attacher() {
        LectureService.joueurActif?.setVideoSurfaceView(surface)
        LectureService.joueurActif?.videoSize?.let { adapterTaille(it) }
    }

    private fun detacher() {
        LectureService.joueurActif?.clearVideoSurfaceView(surface)
    }

    private fun duree(): Long {
        val d = connexion.ctrl?.duration ?: 0L
        return if (d == C.TIME_UNSET || d < 0) 0L else d
    }

    private fun majTout() {
        val c = connexion.ctrl ?: return
        val item = c.currentMediaItem ?: run { finish(); return }
        if (!Format.estVideo(item.mediaId)) {
            // La file est passée à une musique : retour à l'écran de lecture
            startActivity(android.content.Intent(this, LectureActivity::class.java)); finish(); return
        }
        val t = c.mediaMetadata.title?.toString() ?: ""
        titre.text = Traduction.titre(t) + (Traduction.fr(t)?.takeIf { Traduction.dessous }?.let { "\n🌐 $it" } ?: "")
        btnLecture.text = if (c.playWhenReady) "⏸" else "▶"
        if (afficheId != item.mediaId) {
            afficheId = item.mediaId
            chargerSousTitres(item)
        }
        majProgression()
    }

    private fun majProgression() {
        val c = connexion.ctrl ?: return
        val pos = c.currentPosition
        val d = duree()
        if (!glisse) {
            barre.progress = if (d > 0) (pos * 1000 / d).toInt() else 0
            temps.text = "${Format.duree(pos)} / ${Format.duree(d)}"
        }
        val p = paroles
        if (p != null && sousTitresOui) {
            val i = p.indexA(pos)
            val l = p.lignes.getOrNull(i)?.texte ?: ""
            val f = Traduction.fr(l)
            sousTitre.text = when {
                f == null -> l
                Traduction.dessous -> "$l\n$f"
                else -> f
            }
        } else sousTitre.text = ""
    }

    private fun chargerSousTitres(item: androidx.media3.common.MediaItem) {
        paroles = null
        btnSousTitres.alpha = 0.4f
        val empl = Emplacement.de(item) ?: return
        val ctx = applicationContext
        val id = item.mediaId
        travail.execute {
            val p = LecteurParoles.chercher(ctx, empl)
            principal.post {
                if (afficheId != id) return@post
                paroles = p
                btnSousTitres.alpha = if (p != null && sousTitresOui) 1f else 0.4f
            }
            if (p != null) Traduction.traduireParoles(ctx, p) { }
        }
    }

    private fun basculerSousTitres() {
        if (paroles == null) {
            Toast.makeText(this, "Pas de sous-titres pour cette vidéo (fichier .srt ou .vtt du même nom, dossier autorisé dans ❓ Aide)", Toast.LENGTH_LONG).show()
            return
        }
        sousTitresOui = !sousTitresOui
        Base.de(this).ecrire("video_st", if (sousTitresOui) "1" else "0")
        btnSousTitres.alpha = if (sousTitresOui) 1f else 0.4f
        majProgression()
    }

    private fun decaler(ms: Long) {
        val c = connexion.ctrl ?: return
        c.seekTo((c.currentPosition + ms).coerceIn(0L, duree().coerceAtLeast(0L)))
        montrerInfo(if (ms > 0) "+${ms / 1000} s ⏩" else "⏪ ${ms / 1000} s")
    }

    /** 🎧 Le son continue sans l'image (écran éteint possible). */
    private fun ecouter() {
        ecouterSeulement = true
        Toast.makeText(this, "🎧 Le son continue : commandes dans la notification", Toast.LENGTH_SHORT).show()
        finish()
    }

    /** ← : on arrête la vidéo (pause) et on revient à la liste. */
    private fun fermer() {
        connexion.ctrl?.pause()
        finish()
    }

    @Deprecated("Activity")
    override fun onBackPressed() = fermer()

    // ----- Fenêtre flottante -----

    private fun parametresPip(): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < 26) return null
        val r = (largeurVideo.toFloat() / hauteurVideo).coerceIn(0.42f, 2.38f)
        return PictureInPictureParams.Builder().setAspectRatio(Rational((r * 1000).toInt(), 1000)).build()
    }

    private fun fenetreFlottante() {
        if (Build.VERSION.SDK_INT < 26) return
        try { enterPictureInPictureMode(parametresPip()!!) } catch (e: Exception) {
            Toast.makeText(this, "Fenêtre flottante désactivée pour le Lecteur dans les réglages Android", Toast.LENGTH_LONG).show()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (connexion.ctrl?.isPlaying == true && !ecouterSeulement && Build.VERSION.SDK_INT >= 26) {
            try { enterPictureInPictureMode(parametresPip()!!) } catch (e: Exception) { }
        }
    }

    override fun onPictureInPictureModeChanged(enPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(enPip, newConfig)
        haut.visibility = if (enPip) View.GONE else View.VISIBLE
        commandes.visibility = if (enPip) View.GONE else View.VISIBLE
        sousTitre.textSize = if (enPip) 11f else 18f
    }

    private fun enPip() = Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode

    // ----- Taille de l'image -----

    private fun adapterTaille(v: VideoSize) {
        if (v.width <= 0 || v.height <= 0) return
        largeurVideo = (v.width * v.pixelWidthHeightRatio).toInt().coerceAtLeast(1)
        hauteurVideo = v.height
        racine.post { placerImage() }
        if (Build.VERSION.SDK_INT >= 26) try { setPictureInPictureParams(parametresPip()!!) } catch (e: Exception) { }
    }

    private fun placerImage() {
        val lw = racine.width; val lh = racine.height
        if (lw == 0 || lh == 0) return
        val ratio = largeurVideo.toFloat() / hauteurVideo
        val (w, h) = if (lw.toFloat() / lh > ratio) (lh * ratio).toInt() to lh else lw to (lw / ratio).toInt()
        surface.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        racine.post { placerImage() }
        pleinEcran()
    }

    // ----- Commandes et gestes -----

    private fun montrerCommandes(oui: Boolean) {
        if (enPip()) return
        val v = if (oui) View.VISIBLE else View.GONE
        haut.visibility = v
        commandes.visibility = v
        if (oui) relancerMinuteur() else pleinEcran()
    }

    private fun relancerMinuteur() {
        principal.removeCallbacks(cacher)
        if (connexion.ctrl?.isPlaying == true) principal.postDelayed(cacher, 3500)
    }

    private fun montrerInfo(t: String) {
        info.text = t
        info.visibility = View.VISIBLE
        principal.removeCallbacks(cacherInfo)
        principal.postDelayed(cacherInfo, 900)
    }

    private var axe = 0 // 0 = pas encore décidé, 1 = horizontal, 2 = vertical
    private var cumulX = 0f
    private var luminositeDepart = 0.5f
    private var volumeDepart = 0
    private var gauche = true

    private fun finGeste() {
        if (axe == 1 && abs(cumulX) > dp(60)) decaler(if (cumulX > 0) 10_000 else -10_000)
        axe = 0; cumulX = 0f
    }

    private inner class Gestes : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            axe = 0; cumulX = 0f
            gauche = e.x < racine.width / 2
            val l = window.attributes.screenBrightness
            luminositeDepart = if (l < 0) 0.5f else l
            volumeDepart = (getSystemService(Context.AUDIO_SERVICE) as AudioManager).getStreamVolume(AudioManager.STREAM_MUSIC)
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            montrerCommandes(commandes.visibility != View.VISIBLE)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            decaler(if (e.x < racine.width / 2) -10_000 else 10_000)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            val debut = e1 ?: return false
            val totalX = e2.x - debut.x
            val totalY = e2.y - debut.y
            if (axe == 0 && (abs(totalX) > dp(16) || abs(totalY) > dp(16))) axe = if (abs(totalX) > abs(totalY)) 1 else 2
            when (axe) {
                1 -> {
                    cumulX = totalX
                    if (abs(totalX) > dp(60)) montrerInfo(if (totalX > 0) "+10 s ⏩" else "⏪ −10 s")
                }
                2 -> {
                    val part = -totalY / (racine.height * 0.7f)
                    if (gauche) {
                        val l = (luminositeDepart + part).coerceIn(0.02f, 1f)
                        window.attributes = window.attributes.apply { screenBrightness = l }
                        montrerInfo("☀ ${(l * 100).toInt()} %")
                    } else {
                        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val v = (volumeDepart + (part * max).toInt()).coerceIn(0, max)
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                        montrerInfo("🔊 $v / $max")
                    }
                }
            }
            return true
        }
    }

    @Suppress("DEPRECATION")
    private fun pleinEcran() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    // ----- Cycle de vie -----

    override fun onStart() {
        super.onStart()
        connexion.demarrer()
        if (LectureService.joueurActif != null) attacher()
        principal.post(tic)
        relancerMinuteur()
    }

    override fun onStop() {
        principal.removeCallbacks(tic)
        principal.removeCallbacks(cacher)
        // Écran éteint ou appli quittée : l'image s'arrête, le son continue
        detacher()
        connexion.ctrl?.removeListener(ecouteur)
        connexion.arreter()
        super.onStop()
    }
}
