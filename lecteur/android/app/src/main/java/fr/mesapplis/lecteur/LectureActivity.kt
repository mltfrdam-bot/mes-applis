package fr.mesapplis.lecteur

import android.app.Activity
import android.app.AlertDialog
import android.widget.Toast
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.TransitionDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.palette.graphics.Palette
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executors

/** Écran « Lecture en cours » : grande pochette, couleurs reprises de la pochette, paroles en karaoké. */
class LectureActivity : Activity() {

    private lateinit var racine: View
    private lateinit var pochette: ImageView
    private lateinit var titre: TextView
    private lateinit var artiste: TextView
    private lateinit var zoneParoles: ScrollView
    private lateinit var lignesParoles: LinearLayout
    private lateinit var barre: SeekBar
    private lateinit var tempsActuel: TextView
    private lateinit var tempsTotal: TextView
    private lateinit var btnLecture: ImageView
    private lateinit var btnAleatoire: ImageView
    private lateinit var btnRepetition: ImageView
    private lateinit var btnFavori: ImageView
    private lateinit var icoMinuterie: ImageView
    private lateinit var texteMinuterie: TextView
    private var favoriAffiche: String? = null

    private var futur: ListenableFuture<MediaController>? = null
    private var ctrl: MediaController? = null
    private var affiche: String? = null
    private var paroles: Paroles? = null
    private var ligneActive = -1
    private var dernierToucher = 0L
    private var glisse = false
    private var fondActuel: Drawable? = null

    private val principal = Handler(Looper.getMainLooper())
    private val travail = Executors.newSingleThreadExecutor()
    private val tic = object : Runnable {
        override fun run() {
            majProgression()
            principal.postDelayed(this, 200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lecture)
        racine = findViewById(R.id.racine)
        pochette = findViewById(R.id.pochette)
        titre = findViewById(R.id.titre)
        artiste = findViewById(R.id.artiste)
        zoneParoles = findViewById(R.id.zoneParoles)
        lignesParoles = findViewById(R.id.lignesParoles)
        barre = findViewById(R.id.barre)
        tempsActuel = findViewById(R.id.tempsActuel)
        tempsTotal = findViewById(R.id.tempsTotal)
        btnLecture = findViewById(R.id.btnLecture)
        btnAleatoire = findViewById(R.id.btnAleatoire)
        btnRepetition = findViewById(R.id.btnRepetition)
        btnFavori = findViewById(R.id.btnFavori)
        icoMinuterie = findViewById(R.id.icoMinuterie)
        texteMinuterie = findViewById(R.id.texteMinuterie)
        btnFavori.setOnClickListener { pisteEnCours()?.let { p -> Actions.basculerFavori(this, p); majFavori(p.favori) } }
        findViewById<View>(R.id.btnPlaylist).setOnClickListener { pisteEnCours()?.let { p -> Actions.choisirPlaylist(this, listOf(p)) } }
        findViewById<View>(R.id.btnFile).setOnClickListener {
            startActivity(Intent(this, ListeActivity::class.java).putExtra("type", "file"))
        }
        findViewById<View>(R.id.btnMinuterie).setOnClickListener { choisirMinuterie() }

        val rayon = 18 * resources.displayMetrics.density
        pochette.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, rayon)
        }
        pochette.clipToOutline = true

        findViewById<View>(R.id.btnFermer).setOnClickListener { finish() }
        StyleParoles.charger(this)
        findViewById<View>(R.id.btnStyle).setOnClickListener { StyleParoles.panneau(this) { paroles?.let { afficherParoles(it) } } }
        btnLecture.setOnClickListener {
            ctrl?.let { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() } }
        }
        findViewById<View>(R.id.btnPrecedent).setOnClickListener { ctrl?.seekToPrevious() }
        findViewById<View>(R.id.btnSuivant).setOnClickListener { ctrl?.seekToNext() }
        btnAleatoire.setOnClickListener { ctrl?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
        btnRepetition.setOnClickListener {
            ctrl?.let {
                it.repeatMode = when (it.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
            }
        }
        barre.max = 1000
        barre.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, parUtilisateur: Boolean) {
                if (parUtilisateur) tempsActuel.text = Format.duree(dureeTotale() * p / 1000)
            }
            override fun onStartTrackingTouch(s: SeekBar) { glisse = true }
            override fun onStopTrackingTouch(s: SeekBar) {
                glisse = false
                ctrl?.seekTo(dureeTotale() * s.progress / 1000)
            }
        })
        // Si on fait défiler les paroles à la main, le défilement automatique attend 4 secondes
        zoneParoles.setOnTouchListener { _, _ -> dernierToucher = System.currentTimeMillis(); false }
    }

    override fun onResume() {
        super.onResume()
        val ctx = applicationContext
        travail.execute { Traduction.init(ctx); principal.post { majTout() } }
    }

    override fun onStart() {
        super.onStart()
        val f = MediaController.Builder(this, SessionToken(this, ComponentName(this, LectureService::class.java))).buildAsync()
        futur = f
        f.addListener({
            val c = (try { f.get() } catch (e: Exception) { null }) ?: return@addListener
            ctrl = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = majTout()
            })
            majTout()
            principal.post(tic)
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        principal.removeCallbacks(tic)
        futur?.let { MediaController.releaseFuture(it) }
        futur = null
        ctrl = null
        super.onStop()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun dureeTotale(): Long {
        val d = ctrl?.duration ?: 0L
        return if (d == C.TIME_UNSET || d < 0) 0L else d
    }

    private fun majTout() {
        val c = ctrl ?: return
        val item = c.currentMediaItem
        if (item == null) { finish(); return }
        val md = c.mediaMetadata
        val t = md.title?.toString() ?: ""
        titre.text = Traduction.titre(t)
        artiste.text = Traduction.detail(t, listOfNotNull(md.artist, md.albumTitle?.toString()?.let { Traduction.titre(it) }).joinToString(" · "))
        btnLecture.setImageResource(if (c.playWhenReady) R.drawable.ic_pause else R.drawable.ic_lecture)
        val accent = ContextCompat.getColor(this, R.color.accent)
        val gris = ContextCompat.getColor(this, R.color.gris_clair)
        btnAleatoire.setColorFilter(if (c.shuffleModeEnabled) accent else gris)
        btnRepetition.setImageResource(if (c.repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_repetition_une else R.drawable.ic_repetition)
        btnRepetition.setColorFilter(if (c.repeatMode == Player.REPEAT_MODE_OFF) gris else accent)
        if (affiche != item.mediaId) {
            affiche = item.mediaId
            val emp = Emplacement.de(item)?.empreinte
            majFavori(emp != null && Base.de(this).estFavori(emp))
            chargerPochette(item, md.title?.toString() ?: "")
            chargerParoles(item)
        }
        majProgression()
    }

    private fun majProgression() {
        val c = ctrl ?: return
        val pos = c.currentPosition
        val d = dureeTotale()
        if (!glisse) {
            barre.progress = if (d > 0) (pos * 1000 / d).toInt() else 0
            tempsActuel.text = Format.duree(pos)
        }
        tempsTotal.text = Format.duree(d)
        texteMinuterie.text = Minuterie.texte()
        icoMinuterie.setColorFilter(ContextCompat.getColor(this, if (Minuterie.active()) R.color.accent else R.color.texte))
        val p = paroles ?: return
        if (!p.synchro) return
        val i = p.indexA(pos)
        if (i != ligneActive) surligner(i)
    }

    // ----- Pochette et couleurs -----

    private fun chargerPochette(item: MediaItem, nomTitre: String) {
        val id = item.mediaId
        val empl = Emplacement.de(item)
        val ctx = applicationContext
        travail.execute {
            val b = Pochettes.lire(ctx, Uri.parse(id), empl, 900) ?: Pochettes.generee(nomTitre, 600)
            val fond = degrade(b)
            principal.post {
                if (affiche != id) return@post
                pochette.setImageBitmap(b)
                changerFond(fond)
            }
        }
    }

    /** Dégradé clair qui reprend les 2 couleurs principales de la pochette. */
    private fun degrade(b: Bitmap): GradientDrawable {
        val pal = Palette.from(b).maximumColorCount(16).generate()
        val c1 = pal.getVibrantColor(pal.getDominantColor(Color.LTGRAY))
        val c2 = pal.getMutedColor(pal.getLightVibrantColor(c1))
        val haut = ColorUtils.blendARGB(c1, Color.WHITE, 0.72f)
        val bas = ColorUtils.blendARGB(c2, Color.WHITE, 0.88f)
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(haut, bas, Color.parseColor("#F6F7F9")))
    }

    private fun changerFond(nouveau: Drawable) {
        val ancien = fondActuel
        if (ancien == null) {
            racine.background = nouveau
        } else {
            val t = TransitionDrawable(arrayOf(ancien, nouveau))
            racine.background = t
            t.startTransition(600)
        }
        fondActuel = nouveau
    }

    // ----- Paroles (karaoké) -----

    private fun chargerParoles(item: MediaItem) {
        paroles = null
        ligneActive = -1
        lignesParoles.removeAllViews()
        zoneParoles.scrollTo(0, 0)
        val empl = Emplacement.de(item)
        if (empl == null) { messageParoles(""); return }
        if (!Acces.aUnAcces(this)) {
            messageParoles("🎤 Pour voir les paroles, autorise une fois le dossier Musique.\nTouche ici.") {
                startActivityForResult(Acces.intentChoix(), DEMANDE_DOSSIER)
            }
            return
        }
        val id = item.mediaId
        val ctx = applicationContext
        travail.execute {
            val p = LecteurParoles.chercher(ctx, empl)
            principal.post { if (affiche == id) afficherParoles(p) }
            // 🌐 Paroles en anglais : traduites une fois, puis réaffichées
            if (p != null) Traduction.traduireParoles(ctx, p) {
                principal.post { if (affiche == id && paroles === p) { afficherParoles(p); majTout() } }
            }
        }
    }

    private fun messageParoles(texte: String, action: (() -> Unit)? = null) {
        taillePochette(grande = true)
        lignesParoles.removeAllViews()
        if (texte.isEmpty()) return
        val tv = TextView(this).apply {
            text = texte
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.gris))
            setPadding(0, dp(18), 0, dp(18))
            if (action != null) {
                setTextColor(ContextCompat.getColor(context, R.color.accent))
                setOnClickListener { action() }
            }
        }
        lignesParoles.addView(tv)
    }

    private fun afficherParoles(p: Paroles?) {
        if (p == null || p.lignes.isEmpty()) {
            messageParoles("Pas de paroles pour ce titre")
            return
        }
        paroles = p
        ligneActive = -1
        taillePochette(grande = false)
        lignesParoles.removeAllViews()
        val gris = ContextCompat.getColor(this, R.color.gris)
        for (l in p.lignes) {
            val tv = TextView(this).apply {
                text = texteLigne(l.texte)
                gravity = Gravity.CENTER
                textSize = StyleParoles.taille.toFloat()
                typeface = StyleParoles.typeface()
                setTextColor(if (p.synchro) gris else StyleParoles.couleurActive())
                setPadding(0, dp(7), 0, dp(7))
                setLineSpacing(0f, 1.1f)
                if (p.synchro) {
                    // Toucher une ligne : la chanson va à ce moment
                    setOnClickListener { ctrl?.seekTo(l.t); dernierToucher = 0L }
                }
            }
            lignesParoles.addView(tv)
        }
        majProgression()
    }

    /** Ligne de paroles avec sa traduction 🌐 (en dessous, plus petite et en gris), ou à la place. */
    private fun texteLigne(t: String): CharSequence {
        if (t.isEmpty()) return "♪"
        val f = Traduction.fr(t) ?: return t
        if (!Traduction.dessous) return f
        val s = android.text.SpannableString("$t\n$f")
        val debut = t.length + 1
        s.setSpan(android.text.style.AbsoluteSizeSpan(StyleParoles.tailleTrad, true), debut, s.length, 0)
        s.setSpan(android.text.style.ForegroundColorSpan(StyleParoles.couleurTraduction()), debut, s.length, 0)
        s.setSpan(android.text.style.StyleSpan(Typeface.ITALIC), debut, s.length, 0)
        return s
    }

    private fun surligner(i: Int) {
        val gris = ContextCompat.getColor(this, R.color.gris)
        val fort = StyleParoles.couleurActive()
        (lignesParoles.getChildAt(ligneActive) as? TextView)?.apply {
            setTextColor(gris)
            typeface = StyleParoles.typeface()
            animate().scaleX(1f).scaleY(1f).setDuration(200).start()
        }
        ligneActive = i
        val tv = lignesParoles.getChildAt(i) as? TextView ?: return
        tv.setTextColor(fort)
        tv.typeface = StyleParoles.typeface(gras = true)
        tv.animate().scaleX(1.08f).scaleY(1.08f).setDuration(200).start()
        if (System.currentTimeMillis() - dernierToucher > 4000) {
            val y = lignesParoles.top + tv.top + tv.height / 2 - zoneParoles.height / 2
            zoneParoles.smoothScrollTo(0, maxOf(0, y))
        }
    }

    private fun taillePochette(grande: Boolean) {
        val m = resources.displayMetrics
        val cote = if (grande) minOf(m.widthPixels * 0.82f, m.heightPixels * 0.42f) else minOf(m.widthPixels * 0.5f, m.heightPixels * 0.24f)
        val lp = pochette.layoutParams
        if (lp.width != cote.toInt()) {
            lp.width = cote.toInt()
            lp.height = cote.toInt()
            pochette.layoutParams = lp
        }
    }

    @Deprecated("Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == DEMANDE_DOSSIER && resultCode == RESULT_OK && Acces.garder(this, data)) {
            val item = ctrl?.currentMediaItem ?: return
            chargerPochette(item, titre.text.toString())
            chargerParoles(item)
        }
    }

    private fun majFavori(f: Boolean) {
        btnFavori.setImageResource(if (f) R.drawable.ic_etoile else R.drawable.ic_etoile_vide)
    }

    /** Le titre en cours, retrouvé dans la bibliothèque (ou reconstruit). */
    private fun pisteEnCours(): Piste? {
        val item = ctrl?.currentMediaItem ?: return null
        val p = Bibliotheque.derniere.firstOrNull { it.uri.toString() == item.mediaId } ?: Actions.depuisItem(item, 0)
        if (p.empreinte == null) Emplacement.de(item)?.empreinte?.let { p.empreinte = it }
        p.favori = p.empreinte?.let { Base.de(this).estFavori(it) } ?: false
        return p
    }

    private fun choisirMinuterie() {
        val choix = arrayOf("15 minutes", "30 minutes", "45 minutes", "1 heure", "1 h 30", "À la fin du titre", "Annuler la minuterie")
        val minutes = intArrayOf(15, 30, 45, 60, 90)
        AlertDialog.Builder(this)
            .setTitle("⏾ Arrêter la musique dans…")
            .setItems(choix) { _, i ->
                when {
                    i < minutes.size -> Minuterie.programmer(minutes[i])
                    i == 5 -> Minuterie.programmerFinDuTitre()
                    else -> Minuterie.annuler()
                }
                majProgression()
                if (i < 6) Toast.makeText(this, "⏾ Minuterie : ${choix[i]}", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val DEMANDE_DOSSIER = 7
    }
}
