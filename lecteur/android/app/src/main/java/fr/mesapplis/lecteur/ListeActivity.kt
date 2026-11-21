package fr.mesapplis.lecteur

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.Player
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

/**
 * Détail d'un groupe de titres :
 *  - dossier  : un album / une playlist rangée par Termux
 *  - playlist : une playlist de l'appli (ajouter, retirer, ranger)
 *  - choix    : cocher des titres à ajouter à une playlist
 *  - file     : la file d'attente en cours
 */
class ListeActivity : Activity() {

    private lateinit var type: String
    private lateinit var cle: String
    private lateinit var liste: RecyclerView
    private lateinit var adaptateur: PistesAdaptateur
    private lateinit var titre: TextView
    private lateinit var sousTitre: TextView
    private lateinit var texteVide: TextView
    private lateinit var btnValider: TextView
    private lateinit var btnModifier: TextView
    private lateinit var recherche: Recherche
    private lateinit var connexion: Connexion
    private lateinit var mini: MiniLecteur
    private lateinit var poignees: ItemTouchHelper

    private var source: List<Piste> = emptyList()
    private var departGlisse = -1
    private var glisseEnCours = false
    private val principal = Handler(Looper.getMainLooper())
    private val travail = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_liste)
        type = intent.getStringExtra("type") ?: "dossier"
        cle = intent.getStringExtra("cle") ?: ""
        liste = findViewById(R.id.liste)
        titre = findViewById(R.id.titreListe)
        sousTitre = findViewById(R.id.sousTitre)
        texteVide = findViewById(R.id.texteVide)
        btnValider = findViewById(R.id.btnValider)
        btnModifier = findViewById(R.id.btnModifier)

        findViewById<View>(R.id.btnRetour).setOnClickListener { finish() }
        adaptateur = PistesAdaptateur(Ecoute())
        liste.layoutManager = LinearLayoutManager(this)
        liste.adapter = adaptateur
        poignees = ItemTouchHelper(Glisser())
        poignees.attachToRecyclerView(liste)

        connexion = Connexion(this) {
            mini.maj()
            if (type == "file") charger()
        }
        mini = MiniLecteur(this, connexion) { id ->
            adaptateur.enCours = id
            adaptateur.notifyDataSetChanged()
        }
        connexion.ecouter(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (type == "file" && !glisseEnCours && events.contains(Player.EVENT_TIMELINE_CHANGED)) charger()
            }
        })
        recherche = Recherche(this) { filtrer() }

        findViewById<View>(R.id.btnToutLire).setOnClickListener { Actions.jouer(connexion.ctrl, adaptateur.pistes, 0) }
        findViewById<View>(R.id.btnAleatoire).setOnClickListener { Actions.jouer(connexion.ctrl, adaptateur.pistes, 0, aleatoire = true) }
        findViewById<View>(R.id.btnAjouter).setOnClickListener {
            startActivity(Intent(this, ListeActivity::class.java).putExtra("type", "choix").putExtra("cle", cle))
        }
        btnModifier.setOnClickListener { basculerModification() }
        btnValider.setOnClickListener { validerChoix() }
        findViewById<View>(R.id.btnMenuListe).setOnClickListener { menuPlaylist(it) }
        findViewById<View>(R.id.btnPack).setOnClickListener { proposerPack() }
        findViewById<View>(R.id.btnChoisir).setOnClickListener {
            if (adaptateur.pistes.isEmpty()) return@setOnClickListener
            SelectionActivity.ouvrir(this, if (type == "playlist") "Playlist" else "Dossier", adaptateur.pistes.toList(), if (type == "playlist") cle else null)
        }

        when (type) {
            "playlist" -> {
                findViewById<View>(R.id.btnAjouter).visibility = View.VISIBLE
                btnModifier.visibility = View.VISIBLE
                findViewById<View>(R.id.btnPack).visibility = View.VISIBLE
                findViewById<View>(R.id.btnChoisir).visibility = View.VISIBLE
                findViewById<View>(R.id.btnMenuListe).visibility = View.VISIBLE
            }
            "dossier" -> findViewById<View>(R.id.btnChoisir).visibility = View.VISIBLE
            "choix" -> {
                findViewById<View>(R.id.zoneActions).visibility = View.GONE
                btnValider.visibility = View.VISIBLE
                adaptateur.mode = Mode.CHOIX
                majValider()
            }
            "file" -> {
                findViewById<View>(R.id.zoneActions).visibility = View.GONE
                recherche.champ.visibility = View.GONE
                (recherche.champ.parent as? View)?.visibility = View.GONE
                adaptateur.mode = Mode.EDITION
            }
        }
    }

    override fun onStart() {
        super.onStart()
        connexion.demarrer()
        mini.demarrer()
    }

    override fun onResume() {
        super.onResume()
        if (type != "file") charger()
    }

    override fun onStop() {
        mini.arreter()
        connexion.arreter()
        super.onStop()
    }

    // ----- Chargement -----

    private fun charger() {
        if (type != "file" && Bibliotheque.derniere.isEmpty() && MainActivity.aLesDroits(this)) {
            // L'appli a été relancée directement sur cet écran : on relit la bibliothèque d'abord
            val ctx = applicationContext
            travail.execute {
                val l = Bibliotheque.charger(ctx)
                Base.de(ctx).completer(ctx, l, calculer = true)
                Bibliotheque.derniere = l
                principal.post { if (l.isNotEmpty()) charger() }
            }
            return
        }
        val base = Base.de(this)
        when (type) {
            "dossier" -> {
                val l = Bibliotheque.derniere.filter { it.dossier == cle }
                val numerotes = l.count { it.nom.firstOrNull()?.isDigit() == true } > l.size / 2
                source = if (numerotes) l.sortedBy { it.nom.lowercase() } else l.sortedWith(compareBy<Piste> { it.ajoute }.thenBy { it.nom })
                titre.text = cle.trimEnd('/').substringAfterLast('/')
                sousTitre.text = resume(source) + " · " + cle.trimEnd('/')
            }
            "playlist" -> {
                val p = base.playlist(cle)
                if (p == null) { finish(); return }
                val parEmp = Bibliotheque.parEmpreinte()
                source = base.elements(cle).map { parEmp[it.empreinte] ?: Actions.absente(it) }
                titre.text = p.nom
                sousTitre.text = resume(source) + (if (p.supprimeeLe > 0) " · dans la corbeille" else "")
            }
            "choix" -> {
                val p = base.playlist(cle)
                val deja = base.elements(cle).map { it.empreinte }.toHashSet()
                source = Bibliotheque.derniere.filter { it.empreinte == null || it.empreinte !in deja }.sortedBy { it.titre.lowercase() }
                titre.text = "Ajouter à « ${p?.nom ?: ""} »"
                sousTitre.text = "Coche les titres, puis touche « Ajouter »"
            }
            "file" -> {
                val c = connexion.ctrl ?: return
                source = (0 until c.mediaItemCount).map { Actions.depuisItem(c.getMediaItemAt(it), it) }
                titre.text = "File d'attente"
                sousTitre.text = "${source.size} titre${if (source.size > 1) "s" else ""} · glisse ≡ pour ranger, ✕ pour retirer"
            }
        }
        filtrer()
    }

    private fun resume(l: List<Piste>): String =
        "${l.size} titre${if (l.size > 1) "s" else ""} · ${Format.dureeLongue(l.sumOf { it.duree })}"

    private fun filtrer() {
        val l = if (adaptateur.mode == Mode.EDITION) source else source.filter { recherche.correspond(it.recherche) }
        adaptateur.pistes = l.toMutableList()
        adaptateur.notifyDataSetChanged()
        texteVide.visibility = if (l.isEmpty()) View.VISIBLE else View.GONE
        texteVide.text = when {
            source.isEmpty() && type == "playlist" -> "Playlist vide.\n\nTouche « ➕ Ajouter des titres »."
            source.isEmpty() -> "Aucun titre."
            else -> "Rien ne correspond à « ${recherche.champ.text} »."
        }
    }

    // ----- Playlist -----

    private fun basculerModification() {
        if (adaptateur.mode == Mode.EDITION) {
            adaptateur.mode = Mode.NORMAL
            btnModifier.text = "✏️ Modifier"
        } else {
            recherche.champ.setText("")
            adaptateur.mode = Mode.EDITION
            btnModifier.text = "✅ Terminé"
        }
        filtrer()
    }

    // ----- 📦 Pack pour clé USB -----

    private fun proposerPack() {
        val p = Base.de(this).playlist(cle) ?: return
        val presentes = source.filter { it.id >= 0 }
        if (presentes.isEmpty()) { Toast.makeText(this, "Aucune chanson de cette playlist n'est sur le téléphone", Toast.LENGTH_SHORT).show(); return }
        val taille = presentes.sumOf { it.taille }
        AlertDialog.Builder(this)
            .setTitle("📦 Pack « ${p.nom} »")
            .setMessage(
                "${presentes.size} chanson(s) avec leurs paroles, environ ${String.format("%.0f", taille / 1048576.0)} Mo.\n\n" +
                    "« Playlist + Lecteur » ajoute aussi l'appli à jour (environ ${String.format("%.0f", java.io.File(applicationInfo.sourceDir).length() / 1048576.0)} Mo) et un mode d'emploi, pour l'installer sur un autre téléphone.\n\n" +
                    "Branche la clé USB, choisis le type de pack, puis la clé, et touche « Utiliser ce dossier ». " +
                    "Un dossier « ${Pack.nomSain(p.nom)} » y sera créé (les chansons déjà dessus ne sont pas recopiées)."
            )
            .setPositiveButton("Playlist + Lecteur") { _, _ -> choisirCle(true) }
            .setNeutralButton("Playlist seule") { _, _ -> choisirCle(false) }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private var packAvecLecteur = false

    private fun choisirCle(avecLecteur: Boolean) {
        packAvecLecteur = avecLecteur
        try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PACK) }
        catch (e: Exception) { Toast.makeText(this, "Sélecteur de dossier introuvable", Toast.LENGTH_SHORT).show() }
    }

    @Deprecated("Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val arbre = data?.data
        if (requestCode != PACK || resultCode != RESULT_OK || arbre == null) return
        val p = Base.de(this).playlist(cle) ?: return
        val pistes = source
        val (fenetre, avance) = Pack.attente(this, "📦 Copie sur la clé…")
        val ctx = applicationContext
        Thread {
            val r = Pack.exporter(ctx, p.nom, pistes, arbre, avance, packAvecLecteur)
            principal.post { fenetre.dismiss(); Pack.montrerRapport(this, "Pack « ${p.nom} »", r) }
        }.start()
    }

    private fun menuPlaylist(ancre: View) {
        val base = Base.de(this)
        val p = base.playlist(cle) ?: return
        val m = PopupMenu(this, ancre)
        m.menu.add(0, 1, 0, "✏️ Renommer")
        m.menu.add(0, 2, 1, "➕ Tout ajouter à la file d'attente")
        m.menu.add(0, 3, 2, if (p.supprimeeLe > 0) "♻️ Restaurer" else "🗑 Supprimer")
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> Actions.demanderTexte(this, "Renommer la playlist", p.nom, "Renommer") { nom -> base.renommerPlaylist(cle, nom); charger() }
                2 -> source.filter { s -> s.id >= 0 }.forEach { s -> connexion.ctrl?.addMediaItem(s.versMediaItem()) }
                    .also { Toast.makeText(this, "➕ Ajouté à la file d'attente", Toast.LENGTH_SHORT).show() }
                3 -> if (p.supprimeeLe > 0) { base.supprimerPlaylist(cle, false); charger() } else {
                    AlertDialog.Builder(this)
                        .setTitle("Supprimer « ${p.nom} » ?")
                        .setMessage("Elle va dans la corbeille (onglet Playlists) : tu pourras la restaurer.")
                        .setPositiveButton("Supprimer") { _, _ -> base.supprimerPlaylist(cle, true); finish() }
                        .setNegativeButton("Annuler", null)
                        .show()
                }
            }
            true
        }
        m.show()
    }

    private fun majValider() {
        val n = adaptateur.choisis.size
        btnValider.text = if (n == 0) "Coche des titres" else "Ajouter $n titre${if (n > 1) "s" else ""}"
        btnValider.alpha = if (n == 0) 0.5f else 1f
    }

    private fun validerChoix() {
        if (adaptateur.choisis.isEmpty()) return
        val parId = source.associateBy { it.id }
        val choisies = adaptateur.choisis.mapNotNull { parId[it] }
        choisies.forEach { Actions.empreinte(this, it) }
        val n = Base.de(this).ajouter(cle, choisies)
        Toast.makeText(this, "✅ $n titre${if (n > 1) "s" else ""} ajouté${if (n > 1) "s" else ""}", Toast.LENGTH_SHORT).show()
        finish()
    }

    // ----- Clics -----

    private inner class Ecoute : PistesAdaptateur.Ecouteur {
        override fun clic(position: Int) {
            val p = adaptateur.pistes.getOrNull(position) ?: return
            when {
                type == "choix" -> {
                    if (!adaptateur.choisis.add(p.id)) adaptateur.choisis.remove(p.id)
                    adaptateur.notifyItemChanged(position)
                    majValider()
                }
                type == "file" -> connexion.ctrl?.let { it.seekTo(p.id.toInt(), 0L); if (!it.isPlaying) it.play() }
                p.id < 0 -> Toast.makeText(this@ListeActivity, "Ce fichier n'est plus sur le téléphone", Toast.LENGTH_SHORT).show()
                adaptateur.mode == Mode.NORMAL -> {
                    Actions.jouer(connexion.ctrl, adaptateur.pistes, position)
                    if (Format.estVideo(p.uri.toString())) startActivity(android.content.Intent(this@ListeActivity, VideoActivity::class.java))
                }
            }
        }

        override fun favori(position: Int) {
            val p = adaptateur.pistes.getOrNull(position) ?: return
            Actions.basculerFavori(this@ListeActivity, p)
            adaptateur.notifyItemChanged(position)
        }

        override fun menu(position: Int, ancre: View) {
            val p = adaptateur.pistes.getOrNull(position) ?: return
            Actions.menu(this@ListeActivity, ancre, p, connexion.ctrl) { if (type == "playlist") charger() }
        }

        override fun retirer(position: Int) {
            val p = adaptateur.pistes.getOrNull(position) ?: return
            if (type == "file") {
                connexion.ctrl?.removeMediaItem(p.id.toInt())
                return
            }
            val e = p.empreinte ?: return
            Base.de(this@ListeActivity).retirer(cle, e)
            adaptateur.pistes.removeAt(position)
            adaptateur.notifyItemRemoved(position)
            source = source.filter { it.empreinte != e }
            sousTitre.text = resume(source)
            Toast.makeText(this@ListeActivity, "Retiré de la playlist (le fichier reste sur le téléphone)", Toast.LENGTH_SHORT).show()
        }

        override fun saisir(h: RecyclerView.ViewHolder) = poignees.startDrag(h)
    }

    /** Ranger en glissant la poignée ≡. */
    private inner class Glisser : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
        override fun isLongPressDragEnabled() = false

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                departGlisse = viewHolder.bindingAdapterPosition
                glisseEnCours = true
            }
        }

        override fun onMove(rv: RecyclerView, a: RecyclerView.ViewHolder, b: RecyclerView.ViewHolder): Boolean {
            adaptateur.deplacer(a.bindingAdapterPosition, b.bindingAdapterPosition)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

        override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(rv, viewHolder)
            val arrivee = viewHolder.bindingAdapterPosition
            glisseEnCours = false
            if (departGlisse < 0 || arrivee < 0 || departGlisse == arrivee) { departGlisse = -1; return }
            when (type) {
                "playlist" -> {
                    Base.de(this@ListeActivity).reordonner(cle, adaptateur.pistes.mapNotNull { it.empreinte })
                    source = adaptateur.pistes.toList()
                }
                "file" -> connexion.ctrl?.moveMediaItem(departGlisse, arrivee)
            }
            departGlisse = -1
        }
    }

    companion object {
        const val PACK = 41
    }
}
