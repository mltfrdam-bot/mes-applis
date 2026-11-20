package fr.mesapplis.lecteur

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/** 📊 Données : sauvegarde, fusion, statistiques d'écoute en tableaux. */
class DonneesActivity : Activity() {

    private lateinit var contenu: LinearLayout
    private val travail = Executors.newSingleThreadExecutor()
    private var resultat: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val defil = ScrollView(this).apply { setBackgroundColor(couleur(R.color.fond)) }
        contenu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(32))
        }
        defil.addView(contenu)
        setContentView(defil)
        construire()
    }

    private fun construire() {
        contenu.removeAllViews()
        val haut = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        haut.addView(TextView(this).apply {
            text = "←"; textSize = 24f; gravity = Gravity.CENTER
            setTextColor(couleur(R.color.texte))
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setOnClickListener { finish() }
        })
        haut.addView(TextView(this).apply {
            text = "📊 Données"; textSize = 22f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(couleur(R.color.texte))
        })
        contenu.addView(haut)

        val base = Base.de(this)
        titre("Sauvegarde")
        texte(base.totaux())
        val auto = base.lire("auto_le")?.toLongOrNull() ?: 0L
        texte("Sauvegarde automatique chaque jour dans ${Sauvegarde.DOSSIER_AUTO} (les 7 dernières sont gardées). Dernière : ${Format.date(auto)}.", gris = true)
        val ligne = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ligne.addView(bouton("💾 Sauvegarder", true) { sauvegarder() }, poids())
        ligne.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        ligne.addView(bouton("📥 Fusionner", false) { choisirFichier() }, poids())
        contenu.addView(ligne, marge(10))
        texte("Fusionner ajoute ce qui manque (morceaux, favoris, écoutes, playlists) sans jamais rien écraser, puis affiche le détail.", gris = true)

        val zone = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        contenu.addView(zone, marge(4))
        resultat = zone

        titre("Statistiques")
        val stats = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        contenu.addView(stats)
        stats.addView(petitTexte("Calcul…"))
        travail.execute {
            val titres = base.statsTitres(30)
            val artistes = base.statsArtistes(20)
            val semaines = base.statsSemaines(12)
            runOnUiThread { afficherStats(stats, titres, artistes, semaines) }
        }
    }

    private fun afficherStats(zone: LinearLayout, titres: List<LigneStat>, artistes: List<LigneStat>, semaines: List<LigneStat>) {
        zone.removeAllViews()
        if (titres.isEmpty() && semaines.isEmpty()) {
            zone.addView(petitTexte("Pas encore d'écoute enregistrée. Écoute quelques titres, puis reviens ici."))
            return
        }
        zone.addView(sousTitre("Par semaine"))
        zone.addView(tableau(listOf("Semaine", "Écoutes", "Durée", "Titres"), semaines, floatArrayOf(1.6f, 0.9f, 1.1f, 0.8f)))
        zone.addView(sousTitre("Titres les plus écoutés"))
        zone.addView(tableau(listOf("Titre", "Artiste", "Écoutes", "Dernière"), titres, floatArrayOf(2f, 1.3f, 0.9f, 1.1f)))
        zone.addView(sousTitre("Par artiste"))
        zone.addView(tableau(listOf("Artiste", "Écoutes", "Titres"), artistes, floatArrayOf(2.2f, 0.9f, 0.8f)))

        val texteRapport = buildString {
            appendLine("Statistiques Lecteur — ${Format.date(System.currentTimeMillis())}")
            appendLine(Base.de(this@DonneesActivity).totaux())
            appendLine("\nPar semaine (semaine | écoutes | durée | titres différents)")
            semaines.forEach { appendLine(it.colonnes.joinToString(" | ")) }
            appendLine("\nTitres les plus écoutés (titre | artiste | écoutes | dernière)")
            titres.forEach { appendLine(it.colonnes.joinToString(" | ")) }
            appendLine("\nPar artiste (artiste | écoutes | titres)")
            artistes.forEach { appendLine(it.colonnes.joinToString(" | ")) }
        }
        val ligne = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ligne.addView(bouton("📋 Copier", true) { copier(texteRapport) }, poids())
        ligne.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        ligne.addView(bouton("Partager (Drive…)", false) { partagerTexte(texteRapport) }, poids())
        zone.addView(ligne, marge(12))
    }

    // ----- Sauvegarde / fusion -----

    private fun sauvegarder() {
        val ctx = applicationContext
        travail.execute {
            val (uri, nom) = Sauvegarde.sauvegarder(ctx)
            runOnUiThread {
                val z = resultat as LinearLayout
                z.removeAllViews()
                if (uri == null) {
                    z.addView(carte("❌ La sauvegarde n'a pas pu être écrite.", false))
                    return@runOnUiThread
                }
                z.addView(carte("✅ Sauvegardé dans ${Sauvegarde.DOSSIER}$nom", true))
                z.addView(bouton("Envoyer vers le Drive (Partager…)", false) { partagerFichier(uri) }, marge(8))
            }
        }
    }

    private fun choisirFichier() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream"))
        }
        startActivityForResult(i, FICHIER)
    }

    @Deprecated("Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != FICHIER || resultCode != RESULT_OK || uri == null) return
        val ctx = applicationContext
        Toast.makeText(this, "Fusion en cours…", Toast.LENGTH_SHORT).show()
        travail.execute {
            val json = Sauvegarde.lire(ctx, uri)
            val lignes = if (json == null) listOf(LigneFusion("Fichier", "Fichier illisible : rien n'a été modifié.", false))
            else Sauvegarde.fusionner(ctx, json)
            // La bibliothèque relira favoris et écoutes
            Bibliotheque.derniere.takeIf { it.isNotEmpty() }?.let { Base.de(ctx).completer(ctx, it, calculer = false) }
            runOnUiThread { afficherFusion(lignes) }
        }
    }

    private fun afficherFusion(lignes: List<LigneFusion>) {
        construire()
        val z = resultat as LinearLayout
        z.addView(sousTitre("Détail de la fusion"))
        z.addView(tableau(listOf("", "Ce qui a été fait"), lignes.map { LigneStat(listOf((if (it.ajout) "✅ " else "• ") + it.rubrique, it.texte)) }, floatArrayOf(1f, 2.2f)))
        val t = Sauvegarde.texteDescriptif(lignes)
        z.addView(bouton("📋 Copier le détail", true) { copier(t) }, marge(10))
    }

    private fun partagerFichier(uri: Uri) {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(i, "Envoyer la sauvegarde"))
    }

    private fun partagerTexte(t: String) {
        val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, t) }
        startActivity(Intent.createChooser(i, "Envoyer"))
    }

    private fun copier(t: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Lecteur", t))
        Toast.makeText(this, "📋 Copié : colle-le dans la conversation", Toast.LENGTH_SHORT).show()
    }

    // ----- Affichage -----

    /** Tableau façon Excel : en-tête gris, lignes alternées, traits fins. */
    private fun tableau(entetes: List<String>, lignes: List<LigneStat>, poids: FloatArray): View {
        val t = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(couleur(R.color.carte)); setStroke(dp(1), 0xFFD5D9DF.toInt()); cornerRadius = dp(6).toFloat() }
            setPadding(dp(1), dp(1), dp(1), dp(1))
        }
        fun ligne(cols: List<String>, entete: Boolean, impaire: Boolean) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(if (entete) 0xFFEEF0F3.toInt() else if (impaire) 0xFFF8F9FB.toInt() else couleur(R.color.carte))
            }
            cols.forEachIndexed { i, c ->
                if (i > 0) r.addView(View(this).apply { setBackgroundColor(0xFFE3E6EA.toInt()) }, LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT))
                r.addView(TextView(this).apply {
                    text = c; textSize = 13f
                    setTextColor(couleur(if (entete) R.color.gris else R.color.texte))
                    if (entete) typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                    if (i > 0 && c.firstOrNull()?.isDigit() == true) gravity = Gravity.END
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, poids.getOrElse(i) { 1f }))
            }
            t.addView(r)
            t.addView(View(this).apply { setBackgroundColor(0xFFE3E6EA.toInt()) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
        }
        ligne(entetes, true, false)
        lignes.forEachIndexed { i, l -> ligne(l.colonnes, false, i % 2 == 1) }
        if (lignes.isEmpty()) ligne(listOf("Aucune donnée"), false, false)
        return t
    }

    private fun titre(t: String) {
        contenu.addView(TextView(this).apply {
            text = t; textSize = 18f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(couleur(R.color.texte))
        }, marge(20))
    }

    private fun sousTitre(t: String) = TextView(this).apply {
        text = t; textSize = 15f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(couleur(R.color.texte))
        setPadding(0, dp(14), 0, dp(6))
    }

    private fun petitTexte(t: String) = TextView(this).apply {
        text = t; textSize = 14f
        setTextColor(couleur(R.color.gris))
    }

    private fun texte(t: String, gris: Boolean = false): TextView {
        val v = TextView(this).apply {
            text = t; textSize = if (gris) 13f else 15f
            setTextColor(couleur(if (gris) R.color.gris else R.color.texte))
            setLineSpacing(0f, 1.1f)
        }
        contenu.addView(v, marge(6))
        return v
    }

    private fun carte(t: String, ok: Boolean) = TextView(this).apply {
        text = t; textSize = 14f
        setTextColor(couleur(R.color.texte))
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(if (ok) 0xFFE8F6EE.toInt() else 0xFFFDECEC.toInt())
            setStroke(dp(1), if (ok) 0xFF9FD8B5.toInt() else 0xFFE8A3A3.toInt())
        }
    }

    private fun bouton(t: String, plein: Boolean, clic: () -> Unit) = TextView(this).apply {
        text = t; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        minHeight = dp(48)
        setPadding(dp(12), 0, dp(12), 0)
        setBackgroundResource(if (plein) R.drawable.bg_bouton else R.drawable.bg_bouton_clair)
        setTextColor(if (plein) 0xFFFFFFFF.toInt() else couleur(R.color.accent))
        setOnClickListener { clic() }
    }

    private fun couleur(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun poids() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    private fun marge(haut: Int) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(haut) }

    companion object {
        const val FICHIER = 21
    }
}
