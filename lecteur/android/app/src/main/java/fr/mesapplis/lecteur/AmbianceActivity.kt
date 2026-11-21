package fr.mesapplis.lecteur

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.palette.graphics.Palette
import java.util.concurrent.Executors

/**
 * ✨ Économiseur d'écran « Ambiance » : animation plein écran au rythme de la musique, paroles par-dessus.
 * Toucher l'écran : afficher les réglages (style, paroles, lancement automatique, pause, fermer).
 */
class AmbianceActivity : Activity() {

    private lateinit var vue: VueAmbiance
    private lateinit var titre: TextView
    private lateinit var ligne: TextView
    private lateinit var barre: LinearLayout
    private lateinit var btnStyle: TextView
    private lateinit var btnParoles: TextView
    private lateinit var btnAuto: TextView
    private lateinit var btnLecture: TextView
    private lateinit var connexion: Connexion

    private val principal = Handler(Looper.getMainLooper())
    private val travail = Executors.newSingleThreadExecutor()
    private var afficheId: String? = null
    private var paroles: Paroles? = null
    private var parolesOui = true
    private var decalage = 0

    private val cacher = Runnable { barre.visibility = View.GONE; pleinEcran() }
    private val tic = object : Runnable {
        override fun run() { majParoles(); principal.postDelayed(this, 200) }
    }
    // Protection de l'écran : le texte se déplace un peu chaque minute
    private val bouger = object : Runnable {
        override fun run() {
            decalage = (decalage + 1) % 5
            val d = ((decalage - 2) * 6 * resources.displayMetrics.density)
            titre.translationX = d; ligne.translationX = -d; ligne.translationY = d / 2
            principal.postDelayed(this, 60_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val base = Base.de(this)
        StyleParoles.charger(this)
        parolesOui = base.lire("amb_paroles") != "0"
        val racine = FrameLayout(this)
        vue = VueAmbiance(this).apply {
            style = VueAmbiance.Style.values().getOrElse(base.lire("amb_style")?.toIntOrNull() ?: 0) { VueAmbiance.Style.ONDES }
        }
        racine.addView(vue, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val d = resources.displayMetrics.density
        titre = TextView(this).apply {
            textSize = 15f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(8f, 0f, 1f, 0x99000000.toInt())
            setPadding((22 * d).toInt(), (34 * d).toInt(), (22 * d).toInt(), 0)
        }
        racine.addView(titre, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        ligne = TextView(this).apply {
            gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            setShadowLayer(10f, 0f, 2f, 0xAA000000.toInt())
            setPadding((22 * d).toInt(), 0, (22 * d).toInt(), 0)
        }
        racine.addView(ligne, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = (110 * d).toInt() })

        barre = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding((8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
            background = GradientDrawable().apply { cornerRadius = 24 * d; setColor(0xF2FFFFFF.toInt()) }
            visibility = View.GONE
        }
        fun bouton(t: String, clic: () -> Unit) = TextView(this).apply {
            text = t; textSize = 15f; gravity = Gravity.CENTER
            setTextColor(getColor(R.color.texte))
            setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
            setOnClickListener { clic(); montrerBarre() }
        }
        btnStyle = bouton("") { styleSuivant() }
        btnParoles = bouton("") { parolesOui = !parolesOui; base.ecrire("amb_paroles", if (parolesOui) "1" else "0"); majBoutons(); majParoles() }
        btnAuto = bouton("") { autoSuivant() }
        btnLecture = bouton("⏯") { connexion.ctrl?.let { Actions.lectureOuPause(it) } }
        barre.addView(btnStyle); barre.addView(btnParoles); barre.addView(btnAuto); barre.addView(btnLecture)
        barre.addView(bouton("✕") { finish() })
        racine.addView(barre, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = (28 * d).toInt() })

        racine.setOnClickListener { if (barre.visibility == View.VISIBLE) cacher.run() else montrerBarre() }
        setContentView(racine)
        majBoutons()

        connexion = Connexion(this) { majTout() }
        connexion.ecouter(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = majTout()
        })
        pleinEcran()
    }

    private fun montrerBarre() {
        barre.visibility = View.VISIBLE
        principal.removeCallbacks(cacher)
        principal.postDelayed(cacher, 4000)
    }

    private fun majBoutons() {
        btnStyle.text = "◀ ${vue.style.nom} ▶"
        btnParoles.text = if (parolesOui) "🎤 Paroles" else "🎤 Sans"
        btnParoles.alpha = if (parolesOui) 1f else 0.5f
        val auto = Base.de(this).lire("amb_auto")?.toIntOrNull() ?: 0
        btnAuto.text = if (auto == 0) "⏱ Auto : non" else "⏱ Auto : $auto min"
    }

    private fun styleSuivant() {
        val s = VueAmbiance.Style.values()
        vue.style = s[(vue.style.ordinal + 1) % s.size]
        Base.de(this).ecrire("amb_style", vue.style.ordinal.toString())
        majBoutons()
    }

    /** Lancement automatique depuis l'écran de lecture : non, 2, 5 ou 10 minutes sans toucher l'écran. */
    private fun autoSuivant() {
        val choix = listOf(0, 2, 5, 10)
        val actuel = Base.de(this).lire("amb_auto")?.toIntOrNull() ?: 0
        val n = choix[(choix.indexOf(actuel).coerceAtLeast(0) + 1) % choix.size]
        Base.de(this).ecrire("amb_auto", n.toString())
        majBoutons()
    }

    private fun majTout() {
        val c = connexion.ctrl ?: return
        val item = c.currentMediaItem ?: return
        val t = c.mediaMetadata.title?.toString() ?: ""
        val a = c.mediaMetadata.artist?.toString() ?: ""
        titre.text = Traduction.titre(t) + if (a.isNotEmpty()) "\n$a" else ""
        if (afficheId != item.mediaId) {
            afficheId = item.mediaId
            chargerMorceau(item)
        }
    }

    private fun chargerMorceau(item: MediaItem) {
        paroles = null
        ligne.text = ""
        val empl = Emplacement.de(item)
        val ctx = applicationContext
        val id = item.mediaId
        travail.execute {
            val b = Pochettes.lire(ctx, Uri.parse(id), empl, 512)
            var c1 = 0xFF6A8DD6.toInt(); var c2 = 0xFFE58FB3.toInt()
            if (b != null) {
                val pal = Palette.from(b).generate()
                c1 = pal.getVibrantColor(pal.getDominantColor(c1))
                c2 = pal.getLightVibrantColor(pal.getMutedColor(c2))
            }
            val p = if (empl != null && Acces.aUnAcces(ctx)) LecteurParoles.chercher(ctx, empl) else null
            principal.post {
                if (afficheId != id) return@post
                vue.couleurs(c1, c2, b)
                paroles = p
            }
            if (p != null) Traduction.traduireParoles(ctx, p) { }
        }
    }

    private fun majParoles() {
        val p = paroles
        val c = connexion.ctrl
        if (!parolesOui || p == null || c == null || !p.synchro) { ligne.text = ""; return }
        val l = p.lignes.getOrNull(p.indexA(c.currentPosition))?.texte ?: ""
        val f = Traduction.fr(l)
        ligne.typeface = StyleParoles.typeface(gras = true)
        ligne.textSize = (StyleParoles.taille + 6).toFloat()
        val texte = when {
            f == null -> l
            !Traduction.dessous -> f
            else -> null
        }
        if (texte != null) { ligne.text = texte; return }
        val s = android.text.SpannableString("$l\n$f")
        s.setSpan(android.text.style.AbsoluteSizeSpan(StyleParoles.tailleTrad + 4, true), l.length + 1, s.length, 0)
        s.setSpan(android.text.style.StyleSpan(Typeface.ITALIC), l.length + 1, s.length, 0)
        ligne.text = s
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

    override fun onStart() {
        super.onStart()
        connexion.demarrer()
    }

    override fun onResume() {
        super.onResume()
        AnalyseSon.actif = true
        vue.anime = true
        vue.invalidate()
        principal.post(tic)
        principal.postDelayed(bouger, 60_000)
    }

    override fun onPause() {
        AnalyseSon.actif = false
        vue.anime = false
        principal.removeCallbacks(tic)
        principal.removeCallbacks(bouger)
        super.onPause()
    }

    override fun onStop() {
        connexion.arreter()
        super.onStop()
    }
}
