package fr.mesapplis.lecteur

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

/** Connexion d'un écran au service de lecture. */
class Connexion(private val act: Activity, private val pret: (MediaController) -> Unit) {
    private var futur: ListenableFuture<MediaController>? = null
    var ctrl: MediaController? = null
        private set
    private val ecouteurs = ArrayList<Player.Listener>()

    fun ecouter(l: Player.Listener) { ecouteurs += l; ctrl?.addListener(l) }

    fun demarrer() {
        val f = MediaController.Builder(act, SessionToken(act, ComponentName(act, LectureService::class.java))).buildAsync()
        futur = f
        f.addListener({
            val c = (try { f.get() } catch (e: Exception) { null }) ?: return@addListener
            ctrl = c
            ecouteurs.forEach { c.addListener(it) }
            pret(c)
        }, ContextCompat.getMainExecutor(act))
    }

    fun arreter() {
        futur?.let { MediaController.releaseFuture(it) }
        futur = null
        ctrl = null
    }
}

/** Mini lecteur en bas des listes. */
class MiniLecteur(private val act: Activity, private val connexion: Connexion, private val surChangement: (String?) -> Unit = {}) {
    private val mini: View = act.findViewById(R.id.mini)
    private val barre: ProgressBar = act.findViewById(R.id.miniBarre)
    private val pochette: ImageView = act.findViewById(R.id.miniPochette)
    private val titre: TextView = act.findViewById(R.id.miniTitre)
    private val artiste: TextView = act.findViewById(R.id.miniArtiste)
    private val lecture: ImageView = act.findViewById(R.id.miniLecture)
    private var affiche: String? = null
    private val principal = Handler(Looper.getMainLooper())
    private val tic = object : Runnable {
        override fun run() { majBarre(); principal.postDelayed(this, 500) }
    }

    init {
        pochette.clipToOutline = true
        act.findViewById<View>(R.id.miniZone).setOnClickListener {
            val video = Format.estVideo(connexion.ctrl?.currentMediaItem?.mediaId)
            act.startActivity(Intent(act, if (video) VideoActivity::class.java else LectureActivity::class.java))
            act.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
        lecture.setOnClickListener { connexion.ctrl?.let { Actions.lectureOuPause(it) } }
        act.findViewById<View>(R.id.miniSuivant).setOnClickListener { connexion.ctrl?.seekToNext() }
        connexion.ecouter(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = maj()
        })
    }

    fun demarrer() { principal.post(tic) }
    fun arreter() { principal.removeCallbacks(tic) }

    fun maj() {
        val c = connexion.ctrl ?: return
        val item = c.currentMediaItem
        if (item == null) { mini.visibility = View.GONE; return }
        mini.visibility = View.VISIBLE
        val md = c.mediaMetadata
        val t = md.title?.toString() ?: ""
        titre.text = Traduction.titre(t)
        artiste.text = Traduction.detail(t, md.artist?.toString() ?: "")
        lecture.setImageResource(if (c.playWhenReady) R.drawable.ic_pause else R.drawable.ic_lecture)
        if (affiche != item.mediaId) {
            affiche = item.mediaId
            Pochettes.charger(pochette, Uri.parse(item.mediaId), Emplacement.de(item), t, 144)
            surChangement(item.mediaId)
        }
        majBarre()
    }

    private fun majBarre() {
        val c = connexion.ctrl ?: return
        val d = c.duration
        barre.progress = if (d == C.TIME_UNSET || d <= 0) 0 else (c.currentPosition * 1000 / d).toInt()
    }
}

/** Barre de recherche instantanée (filtre pendant la frappe). */
class Recherche(act: Activity, private val surTexte: (String) -> Unit) {
    val champ: EditText = act.findViewById(R.id.recherche)
    private val effacer: View = act.findViewById(R.id.effacerRecherche)
    var texte = ""
        private set

    init {
        champ.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                texte = Texte.simplifier(s?.toString()?.trim() ?: "")
                effacer.visibility = if (texte.isEmpty()) View.GONE else View.VISIBLE
                surTexte(texte)
            }
        })
        effacer.setOnClickListener {
            champ.setText("")
            (act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(champ.windowToken, 0)
        }
    }

    /** Tous les mots tapés doivent se trouver dans le texte. */
    fun correspond(cible: String): Boolean = texte.isEmpty() || texte.split(' ').all { it.isEmpty() || cible.contains(it) }
}

/** Actions sur un titre, communes à tous les écrans. */
object Actions {

    fun lectureOuPause(c: MediaController) {
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun jouer(c: MediaController?, pistes: List<Piste>, index: Int, aleatoire: Boolean = false) {
        if (c == null || pistes.isEmpty()) return
        val jouables = pistes.filter { it.id >= 0 }
        if (jouables.isEmpty()) return
        val depart = jouables.indexOf(pistes.getOrNull(index)).coerceAtLeast(0)
        c.shuffleModeEnabled = aleatoire
        c.setMediaItems(jouables.map { it.versMediaItem() }, if (aleatoire) (jouables.indices).random() else depart, 0L)
        c.prepare()
        c.play()
    }

    fun empreinte(ctx: Context, p: Piste): String? {
        p.empreinte?.let { return it }
        if (p.id < 0) return null
        val e = Bibliotheque.empreinte(ctx, p.uri, p.taille) ?: "chemin:${p.cle}"
        p.empreinte = e
        return e
    }

    fun basculerFavori(ctx: Context, p: Piste): Boolean {
        val e = empreinte(ctx, p) ?: return p.favori
        p.favori = Base.de(ctx).basculerFavori(e, p.titre, p.artiste)
        Toast.makeText(ctx, if (p.favori) "★ Ajouté aux favoris" else "Retiré des favoris", Toast.LENGTH_SHORT).show()
        return p.favori
    }

    fun menu(act: Activity, ancre: View, p: Piste, c: MediaController?, surChangement: () -> Unit) {
        val m = PopupMenu(act, ancre)
        m.menu.add(0, 1, 0, "▶ Lire ensuite")
        m.menu.add(0, 2, 1, "➕ Ajouter à la file d'attente")
        m.menu.add(0, 3, 2, "📃 Ajouter à une playlist…")
        m.menu.add(0, 4, 3, "📁 Ouvrir le dossier « ${p.nomDossier} »")
        if (p.id >= 0) m.menu.add(0, 5, 4, "🗑 Supprimer du téléphone")
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> ajouterFile(act, c, p, ensuite = true)
                2 -> ajouterFile(act, c, p, ensuite = false)
                3 -> choisirPlaylist(act, listOf(p), surChangement)
                4 -> act.startActivity(Intent(act, ListeActivity::class.java).putExtra("type", "dossier").putExtra("cle", p.dossier))
                5 -> SelectionActivity.supprimerUn(act, p)
            }
            true
        }
        m.show()
    }

    fun ajouterFile(ctx: Context, c: MediaController?, p: Piste, ensuite: Boolean) {
        if (c == null || p.id < 0) return
        val item = p.versMediaItem()
        if (c.mediaItemCount == 0) {
            c.setMediaItem(item); c.prepare(); c.play()
        } else if (ensuite) {
            c.addMediaItem(c.currentMediaItemIndex + 1, item)
        } else {
            c.addMediaItem(item)
        }
        Toast.makeText(ctx, if (ensuite) "▶ « ${p.titre} » sera joué ensuite" else "➕ Ajouté à la file d'attente", Toast.LENGTH_SHORT).show()
    }

    /** Choisir une playlist (ou en créer une) pour y ajouter des titres. */
    fun choisirPlaylist(act: Activity, pistes: List<Piste>, fini: () -> Unit = {}) {
        val base = Base.de(act)
        val listes = base.playlists()
        val noms = arrayOf("➕ Nouvelle playlist…") + listes.map { "${it.nom}  (${it.nombre})" }
        AlertDialog.Builder(act)
            .setTitle("Ajouter à une playlist")
            .setItems(noms) { _, i ->
                if (i == 0) nouvellePlaylist(act) { id -> ajouterA(act, id, pistes); fini() }
                else { ajouterA(act, listes[i - 1].id, pistes); fini() }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun ajouterA(ctx: Context, id: String, pistes: List<Piste>) {
        pistes.forEach { empreinte(ctx, it) }
        val n = Base.de(ctx).ajouter(id, pistes)
        val nom = Base.de(ctx).playlist(id)?.nom ?: ""
        Toast.makeText(ctx, if (n == 0) "Déjà dans « $nom »" else "✅ $n titre${if (n > 1) "s" else ""} ajouté${if (n > 1) "s" else ""} à « $nom »", Toast.LENGTH_SHORT).show()
    }

    fun nouvellePlaylist(act: Activity, suite: (String) -> Unit) {
        demanderTexte(act, "Nouvelle playlist", "", "Créer") { nom ->
            suite(Base.de(act).creerPlaylist(nom))
        }
    }

    fun demanderTexte(act: Activity, titre: String, initial: String, ok: String, suite: (String) -> Unit) {
        val champ = EditText(act).apply {
            setText(initial)
            setSelection(initial.length)
            hint = "Nom"
            setSingleLine()
        }
        val cadre = FrameLayout(act).apply {
            val m = (20 * act.resources.displayMetrics.density).toInt()
            setPadding(m, m / 2, m, 0)
            addView(champ)
        }
        val d = AlertDialog.Builder(act)
            .setTitle(titre)
            .setView(cadre)
            .setPositiveButton(ok) { _, _ ->
                val t = champ.text.toString().trim()
                if (t.isNotEmpty()) suite(t)
            }
            .setNegativeButton("Annuler", null)
            .create()
        d.setOnShowListener {
            champ.requestFocus()
            (act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(champ, InputMethodManager.SHOW_IMPLICIT)
        }
        d.show()
    }

    /** Piste à partir d'un élément de la file d'attente. */
    fun depuisItem(item: MediaItem, index: Int): Piste {
        val md = item.mediaMetadata
        val e = Emplacement.de(item)
        return Piste(
            id = index.toLong(), uri = Uri.parse(item.mediaId), titre = md.title?.toString() ?: "",
            artiste = md.artist?.toString() ?: "", album = md.albumTitle?.toString() ?: "", duree = 0, ajoute = 0,
            volume = e?.volume ?: "", dossier = e?.dossier ?: "", nom = e?.nom ?: "",
        ).also { it.empreinte = e?.empreinte }
    }

    /** Titre d'une playlist dont le fichier n'est plus sur le téléphone. */
    fun absente(el: ElementPlaylist): Piste = Piste(
        id = -1L - (el.empreinte.hashCode().toLong() and 0x7fffffffL), uri = Uri.EMPTY, titre = el.titre, artiste = el.artiste,
        album = "", duree = 0, ajoute = 0, volume = "", dossier = "", nom = "",
    ).also { it.empreinte = el.empreinte }
}
