package fr.mesapplis.lecteur

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

enum class Mode { NORMAL, CHOIX, EDITION }

/** Liste de titres, en tableau : pochette · titre/artiste · ★ · durée/écoutes · ⋮ */
class PistesAdaptateur(private val ecouteur: Ecouteur) : RecyclerView.Adapter<PistesAdaptateur.Ligne>() {

    interface Ecouteur {
        fun clic(position: Int)
        fun favori(position: Int) {}
        fun menu(position: Int, ancre: View) {}
        fun retirer(position: Int) {}
        fun saisir(h: RecyclerView.ViewHolder) {}
    }

    var pistes: MutableList<Piste> = ArrayList()
    var mode = Mode.NORMAL
    var enCours: String? = null
    val choisis = LinkedHashSet<Long>()

    init { setHasStableIds(true) }

    @SuppressLint("ClickableViewAccessibility")
    inner class Ligne(v: View) : RecyclerView.ViewHolder(v) {
        val pochette: ImageView = v.findViewById(R.id.pochette)
        val titre: TextView = v.findViewById(R.id.titre)
        val artiste: TextView = v.findViewById(R.id.artiste)
        val nouveau: TextView = v.findViewById(R.id.nouveau)
        val duree: TextView = v.findViewById(R.id.duree)
        val ecoutes: TextView = v.findViewById(R.id.ecoutes)
        val etoile: ImageView = v.findViewById(R.id.etoile)
        val menu: ImageView = v.findViewById(R.id.menu)
        val retirer: ImageView = v.findViewById(R.id.retirer)
        val poignee: ImageView = v.findViewById(R.id.poignee)
        val caseChoix: CheckBox = v.findViewById(R.id.caseChoix)

        init {
            pochette.clipToOutline = true
            v.setOnClickListener { pos()?.let { ecouteur.clic(it) } }
            etoile.setOnClickListener { pos()?.let { ecouteur.favori(it) } }
            menu.setOnClickListener { pos()?.let { ecouteur.menu(it, menu) } }
            retirer.setOnClickListener { pos()?.let { ecouteur.retirer(it) } }
            poignee.setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) ecouteur.saisir(this)
                false
            }
        }

        private fun pos(): Int? = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Ligne =
        Ligne(LayoutInflater.from(parent.context).inflate(R.layout.item_piste, parent, false))

    override fun getItemCount() = pistes.size

    override fun getItemId(position: Int) = pistes[position].id

    override fun onBindViewHolder(h: Ligne, position: Int) {
        val p = pistes[position]
        val ctx = h.itemView.context
        val absente = p.id < 0
        h.titre.text = p.titre
        h.artiste.text = if (absente) "Fichier absent du téléphone" else
            listOf(p.artiste, p.album).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { p.nomDossier }
        h.duree.text = if (absente) "" else Format.duree(p.duree)
        h.ecoutes.text = if (p.ecoutes > 0) "${p.ecoutes}×" else ""
        h.nouveau.visibility = if (p.nouveau) View.VISIBLE else View.GONE
        val joue = !absente && p.uri.toString() == enCours
        h.itemView.isActivated = joue
        h.titre.setTextColor(ContextCompat.getColor(ctx, when {
            absente -> R.color.gris_clair
            joue -> R.color.accent
            else -> R.color.texte
        }))
        h.titre.paint.isFakeBoldText = joue
        h.etoile.setImageResource(if (p.favori) R.drawable.ic_etoile else R.drawable.ic_etoile_vide)
        h.etoile.visibility = if (mode == Mode.NORMAL && !absente) View.VISIBLE else View.GONE
        h.menu.visibility = if (mode == Mode.NORMAL && !absente) View.VISIBLE else View.GONE
        h.retirer.visibility = if (mode == Mode.EDITION) View.VISIBLE else View.GONE
        h.poignee.visibility = if (mode == Mode.EDITION) View.VISIBLE else View.GONE
        h.caseChoix.visibility = if (mode == Mode.CHOIX) View.VISIBLE else View.GONE
        h.caseChoix.isChecked = choisis.contains(p.id)
        if (absente) {
            h.pochette.tag = null
            h.pochette.setImageBitmap(Pochettes.generee(p.titre, 132))
            h.pochette.alpha = 0.4f
        } else {
            h.pochette.alpha = 1f
            Pochettes.charger(h.pochette, p.uri, Emplacement(p.volume, p.dossier, p.nom), p.titre, 132)
        }
    }

    fun deplacer(de: Int, vers: Int) {
        val p = pistes.removeAt(de)
        pistes.add(vers, p)
        notifyItemMoved(de, vers)
    }
}

/** Un groupe : dossier ou playlist. */
class Groupe(
    val cle: String,
    val titre: String,
    val detail: String,
    val pochette: Piste?,
    val icone: Int = 0,
    val recherche: String = Texte.simplifier(titre),
)

class GroupesAdaptateur(private val clic: (Groupe) -> Unit) : RecyclerView.Adapter<GroupesAdaptateur.Ligne>() {
    var groupes: List<Groupe> = emptyList()

    inner class Ligne(v: View) : RecyclerView.ViewHolder(v) {
        val pochette: ImageView = v.findViewById(R.id.pochette)
        val titre: TextView = v.findViewById(R.id.titre)
        val detail: TextView = v.findViewById(R.id.artiste)

        init {
            pochette.clipToOutline = true
            v.setOnClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) clic(groupes[p])
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Ligne =
        Ligne(LayoutInflater.from(parent.context).inflate(R.layout.item_groupe, parent, false))

    override fun getItemCount() = groupes.size

    override fun onBindViewHolder(h: Ligne, position: Int) {
        val g = groupes[position]
        h.titre.text = g.titre
        h.detail.text = g.detail
        val p = g.pochette
        if (g.icone != 0) {
            h.pochette.tag = null
            h.pochette.scaleType = ImageView.ScaleType.CENTER
            h.pochette.setImageResource(g.icone)
        } else if (p != null) {
            h.pochette.scaleType = ImageView.ScaleType.CENTER_CROP
            Pochettes.charger(h.pochette, p.uri, Emplacement(p.volume, p.dossier, p.nom), g.titre, 144)
        } else {
            h.pochette.tag = null
            h.pochette.scaleType = ImageView.ScaleType.CENTER_CROP
            h.pochette.setImageBitmap(Pochettes.generee(g.titre, 144))
        }
    }

    companion object {
        /** Dossiers (albums / playlists rangés par Termux). */
        fun dossiers(pistes: List<Piste>): List<Groupe> = pistes.groupBy { it.dossier }.map { (d, l) ->
            val parent = d.trimEnd('/').substringBeforeLast('/', "")
            Groupe(
                cle = d,
                titre = d.trimEnd('/').substringAfterLast('/'),
                detail = "${l.size} titre${if (l.size > 1) "s" else ""} · ${Format.dureeLongue(l.sumOf { it.duree })}" + (if (parent.isNotEmpty()) " · $parent" else ""),
                pochette = l.minByOrNull { it.nom },
                recherche = Texte.simplifier(d),
            )
        }.sortedBy { it.titre.lowercase() }

    }
}
