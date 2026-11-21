package fr.mesapplis.lecteur

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * 🔁 Doublons : propose de supprimer les fichiers en double, du lecteur ET du téléphone.
 * Les fichiers passent par la corbeille d'Android (30 jours, restaurables ici) ;
 * écoutes, favoris, reprise et playlists sont reportés sur la version gardée.
 */
class DoublonsActivity : Activity() {

    private lateinit var contenu: LinearLayout
    private val travail = Executors.newSingleThreadExecutor()

    private var groupes: List<GroupeDoublons> = emptyList()
    private var corbeille: List<FichierCorbeille> = emptyList()
    /** Chemin → true si le fichier est gardé. */
    private val garder = HashMap<String, Boolean>()
    /** En attente de la réponse d'Android : (supprimé, gardé). */
    private var plan: List<Pair<Piste, Piste>> = emptyList()
    private var rapport: List<String> = emptyList()
    private val seulementCorbeille by lazy { intent.getBooleanExtra("corbeille", false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val defil = ScrollView(this).apply { setBackgroundColor(couleur(R.color.fond)) }
        contenu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(32))
        }
        defil.addView(contenu)
        setContentView(defil)
        charger()
    }

    private fun charger() {
        entete()
        texte("Recherche des doublons…", gris = true)
        val ctx = applicationContext
        travail.execute {
            val l = Bibliotheque.charger(ctx)
            Base.de(ctx).completer(ctx, l, calculer = true)
            Bibliotheque.derniere = l
            val g = Doublons.chercher(ctx, l)
            val c = Doublons.corbeille(ctx)
            runOnUiThread {
                groupes = g
                corbeille = c
                garder.clear()
                for (gr in g) for (p in gr.pistes) garder[p.cle] = p === gr.garder
                construire()
            }
        }
    }

    private fun entete() {
        contenu.removeAllViews()
        val haut = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        haut.addView(TextView(this).apply {
            text = "←"; textSize = 24f; gravity = Gravity.CENTER
            setTextColor(couleur(R.color.texte))
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setOnClickListener { finish() }
        })
        haut.addView(TextView(this).apply {
            text = if (seulementCorbeille) "♻️ Corbeille" else "🔁 Doublons"; textSize = 22f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(couleur(R.color.texte))
        })
        contenu.addView(haut)
    }

    private fun construire() {
        entete()

        if (rapport.isNotEmpty()) {
            sousTitre("Ce qui a été fait")
            contenu.addView(tableau(listOf("Détail"), rapport.map { listOf(it) }, floatArrayOf(1f)) { })
            val t = "Doublons Lecteur — ${Format.date(System.currentTimeMillis())}\n" + rapport.joinToString("\n")
            contenu.addView(bouton("📋 Copier le détail", true) { copier(t) }, marge(10))
        }

        if (seulementCorbeille) {
            if (corbeille.isEmpty()) texte("✅ La corbeille est vide.")
        } else if (!Doublons.corbeillePossible()) {
            texte("Cette version d'Android ne permet pas la corbeille : la suppression n'est pas proposée.", gris = true)
        }

        if (seulementCorbeille) {
        } else if (groupes.isEmpty()) {
            texte("✅ Aucun doublon trouvé.")
        } else {
            val aSupprimer = groupes.sumOf { g -> g.pistes.count { garder[it.cle] == false } }
            texte("${groupes.size} morceau(x) en double. Coche ✅ ce que tu gardes : touche une ligne pour changer. La meilleure qualité est cochée d'office.")
            texte("Les fichiers 🗑 vont dans la corbeille d'Android (30 jours, restaurables en bas de cet écran). Leurs écoutes, favoris et playlists passent sur la version gardée.", gris = true)
            if (Doublons.corbeillePossible() && aSupprimer > 0) {
                contenu.addView(bouton("🗑 Mettre $aSupprimer fichier(s) à la corbeille", true) { supprimer() }, marge(10))
            }
            groupes.forEachIndexed { i, g -> afficherGroupe(i, g) }
        }

        if (corbeille.isNotEmpty()) {
            sousTitre("♻️ Corbeille (${corbeille.size})")
            texte("Touche une ligne pour la restaurer. Android efface définitivement à la date indiquée.", gris = true)
            contenu.addView(
                tableau(listOf("Fichier", "Effacé le"), corbeille.map { listOf(it.nom + "\n" + it.dossier, Format.date(it.expire).take(10)) }, floatArrayOf(2.4f, 1f)) { i ->
                    restaurer(listOf(corbeille[i]))
                }
            )
            contenu.addView(bouton("♻️ Tout restaurer", false) { restaurer(corbeille) }, marge(10))
        }
    }

    private fun afficherGroupe(i: Int, g: GroupeDoublons) {
        val haut = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        haut.addView(TextView(this).apply {
            text = "${i + 1}. ${g.garder.titre}" + (if (g.garder.artiste.isNotEmpty()) " — ${g.garder.artiste}" else "") + "\n" + g.raison
            textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(couleur(R.color.texte))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        haut.addView(TextView(this).apply {
            text = "Pas un doublon"; textSize = 13f
            setTextColor(couleur(R.color.accent))
            setPadding(dp(10), dp(10), dp(4), dp(10))
            setOnClickListener {
                Doublons.ignorer(this@DoublonsActivity, g)
                groupes = groupes - g
                Toast.makeText(this@DoublonsActivity, "Ce groupe ne sera plus proposé", Toast.LENGTH_SHORT).show()
                construire()
            }
        })
        contenu.addView(haut, marge(18))
        val lignes = g.pistes.map { p ->
            val ext = p.nom.substringAfterLast('.', "").uppercase()
            listOf(
                if (garder[p.cle] == true) "✅ Garder" else "🗑",
                p.nom + "\n" + p.dossier,
                "${Doublons.kbps(p)} kb/s\n$ext",
                Format.duree(p.duree),
                p.ecoutes.toString() + if (p.favori) " ★" else "",
            )
        }
        contenu.addView(tableau(listOf("", "Fichier", "Qualité", "Durée", "Écoutes"), lignes, floatArrayOf(1f, 2.4f, 1.1f, 0.9f, 0.9f)) { k ->
            val p = g.pistes[k]
            val nouveau = garder[p.cle] != true
            if (!nouveau && g.pistes.count { garder[it.cle] == true } <= 1) {
                Toast.makeText(this, "Il faut garder au moins une version", Toast.LENGTH_SHORT).show()
                return@tableau
            }
            garder[p.cle] = nouveau
            construire()
        }, marge(6))
    }

    // ----- Suppression -----

    private fun supprimer() {
        val p = ArrayList<Pair<Piste, Piste>>()
        for (g in groupes) {
            val gardes = g.pistes.filter { garder[it.cle] == true }
            val cible = if (g.garder in gardes) g.garder else gardes.firstOrNull() ?: continue
            for (s in g.pistes) if (garder[s.cle] == false) p += s to cible
        }
        if (p.isEmpty()) return
        val demande = Doublons.demande(this, p.map { it.first.uri }, true) ?: return
        plan = p
        try {
            startIntentSenderForResult(demande, CORBEILLE, null, 0, 0, 0)
        } catch (e: Exception) {
            Toast.makeText(this, "Android a refusé la demande : rien n'a été supprimé.", Toast.LENGTH_LONG).show()
        }
    }

    private fun restaurer(l: List<FichierCorbeille>) {
        val demande = Doublons.demande(this, l.map { it.uri }, false) ?: return
        try { startIntentSenderForResult(demande, RESTAURER, null, 0, 0, 0) } catch (e: Exception) { }
    }

    @Deprecated("Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RESTAURER) {
            if (resultCode == RESULT_OK) {
                Toast.makeText(this, "♻️ Restauré", Toast.LENGTH_SHORT).show()
                rapport = emptyList()
                charger()
            }
            return
        }
        if (requestCode != CORBEILLE) return
        val p = plan
        plan = emptyList()
        if (resultCode != RESULT_OK) {
            Toast.makeText(this, "Annulé : rien n'a été supprimé.", Toast.LENGTH_SHORT).show()
            return
        }
        val ctx = applicationContext
        travail.execute {
            val base = Base.de(ctx)
            val lignes = ArrayList<String>()
            var ecoutes = 0; var favoris = 0; var playlists = 0
            for ((s, g) in p) {
                base.oublierChemin(s.cle)
                val es = s.empreinte; val eg = g.empreinte
                val details = ArrayList<String>()
                if (es != null && eg != null && es != eg) {
                    val r = base.reporter(es, eg, g.titre, g.artiste)
                    if (r.ecoutes > 0) details += "${r.ecoutes} écoute(s)"
                    if (r.favori) details += "favori"
                    if (r.playlists > 0) details += "${r.playlists} playlist(s)"
                    ecoutes += r.ecoutes; if (r.favori) favoris++; playlists += r.playlists
                } else if (es != null && es == eg) {
                    details += "fichier identique : ses données étaient déjà communes"
                }
                lignes += "🗑 ${s.dossier}${s.nom}  →  ✅ gardé : ${g.dossier}${g.nom}" +
                    (if (details.isNotEmpty()) "\n   reporté : " + details.joinToString(", ") else "")
            }
            lignes.add(0, "${p.size} fichier(s) mis à la corbeille d'Android (restaurables 30 jours) · reporté : $ecoutes écoute(s), $favoris favori(s), $playlists playlist(s)")
            runOnUiThread {
                rapport = lignes
                Toast.makeText(this, "✅ ${p.size} doublon(s) supprimé(s)", Toast.LENGTH_SHORT).show()
                charger()
            }
        }
    }

    // ----- Affichage -----

    /** Tableau façon Excel ; [clic] reçoit le numéro de la ligne touchée. */
    private fun tableau(entetes: List<String>, lignes: List<List<String>>, poids: FloatArray, clic: (Int) -> Unit): View {
        val t = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(couleur(R.color.carte)); setStroke(dp(1), 0xFFD5D9DF.toInt()); cornerRadius = dp(6).toFloat() }
            setPadding(dp(1), dp(1), dp(1), dp(1))
        }
        fun ligne(cols: List<String>, entete: Boolean, index: Int) {
            val supprime = !entete && cols.firstOrNull() == "🗑"
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(
                    when {
                        entete -> 0xFFEEF0F3.toInt()
                        supprime -> 0xFFFDF0F0.toInt()
                        index % 2 == 1 -> 0xFFF8F9FB.toInt()
                        else -> couleur(R.color.carte)
                    }
                )
                if (!entete) setOnClickListener { clic(index) }
            }
            cols.forEachIndexed { i, c ->
                if (i > 0) r.addView(View(this).apply { setBackgroundColor(0xFFE3E6EA.toInt()) }, LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT))
                r.addView(TextView(this).apply {
                    text = c; textSize = 13f
                    setTextColor(couleur(if (entete) R.color.gris else R.color.texte))
                    if (entete) typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(6), dp(8), dp(6), dp(8))
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, poids.getOrElse(i) { 1f }))
            }
            t.addView(r)
            t.addView(View(this).apply { setBackgroundColor(0xFFE3E6EA.toInt()) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
        }
        ligne(entetes, true, -1)
        lignes.forEachIndexed { i, l -> ligne(l, false, i) }
        return t
    }

    private fun sousTitre(t: String) {
        contenu.addView(TextView(this).apply {
            text = t; textSize = 17f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(couleur(R.color.texte))
        }, marge(20))
    }

    private fun texte(t: String, gris: Boolean = false) {
        contenu.addView(TextView(this).apply {
            text = t; textSize = if (gris) 13f else 15f
            setTextColor(couleur(if (gris) R.color.gris else R.color.texte))
            setLineSpacing(0f, 1.1f)
        }, marge(8))
    }

    private fun bouton(t: String, plein: Boolean, clic: () -> Unit) = TextView(this).apply {
        text = t; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        minHeight = dp(48)
        setPadding(dp(12), 0, dp(12), 0)
        setBackgroundResource(if (plein) R.drawable.bg_bouton else R.drawable.bg_bouton_clair)
        setTextColor(if (plein) 0xFFFFFFFF.toInt() else couleur(R.color.accent))
        setOnClickListener { clic() }
    }

    private fun copier(t: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Lecteur", t))
        Toast.makeText(this, "📋 Copié : colle-le dans la conversation", Toast.LENGTH_SHORT).show()
    }

    private fun couleur(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun marge(haut: Int) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(haut) }

    companion object {
        const val CORBEILLE = 31
        const val RESTAURER = 32
    }
}
