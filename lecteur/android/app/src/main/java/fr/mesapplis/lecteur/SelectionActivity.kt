package fr.mesapplis.lecteur

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

/**
 * ☑ Choisir des titres pour les supprimer du téléphone (corbeille d'Android, 30 jours),
 * ou, dans une playlist, pour les retirer de la playlist (le fichier reste).
 * Ouvert aussi par ⋮ → « 🗑 Supprimer du téléphone » pour un seul titre (demande directe).
 */
class SelectionActivity : Activity() {

    companion object {
        const val CORBEILLE = 61
        /** Titres proposés (passés par l'écran qui ouvre celui-ci, même appli). */
        var aProposer: List<Piste> = emptyList()
        /** Quelque chose a changé (suppression, retrait) : les listes se rechargent au retour. */
        @Volatile var modifie = false

        /** Ouvre l'écran de choix. [playlist] : id de la playlist, pour proposer « Retirer de la playlist ». */
        fun ouvrir(act: Activity, titre: String, pistes: List<Piste>, playlist: String? = null) {
            aProposer = pistes
            act.startActivity(Intent(act, SelectionActivity::class.java).putExtra("titre", titre).putExtra("playlist", playlist))
        }

        /** ⋮ → Supprimer du téléphone : un seul titre, Android confirme directement. */
        fun supprimerUn(act: Activity, p: Piste) {
            if (p.id < 0) { Toast.makeText(act, "Ce fichier n'est plus sur le téléphone", Toast.LENGTH_SHORT).show(); return }
            aProposer = listOf(p)
            act.startActivity(Intent(act, SelectionActivity::class.java).putExtra("direct", true))
        }
    }

    private lateinit var adaptateur: PistesAdaptateur
    private lateinit var btnSupprimer: TextView
    private lateinit var btnRetirer: TextView
    private lateinit var btnTout: TextView
    private val travail = Executors.newSingleThreadExecutor()
    private var pistes: List<Piste> = emptyList()
    private var enAttente: List<Piste> = emptyList()
    private var playlist: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pistes = aProposer
        playlist = intent.getStringExtra("playlist")
        if (intent.getBooleanExtra("direct", false)) {
            // Pas d'écran : la fenêtre d'Android s'ouvre tout de suite
            window.setBackgroundDrawableResource(android.R.color.transparent)
            demander(pistes)
            return
        }
        construire()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun couleur(id: Int) = ContextCompat.getColor(this, id)

    private fun construire() {
        val racine = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(couleur(R.color.fond)) }
        val haut = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(6), dp(12), dp(6)) }
        haut.addView(TextView(this).apply {
            text = "←"; textSize = 24f; gravity = Gravity.CENTER; setTextColor(couleur(R.color.texte))
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        haut.addView(TextView(this).apply {
            text = "☑ " + (intent.getStringExtra("titre") ?: "Choisir"); textSize = 19f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(couleur(R.color.texte)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btnTout = TextView(this).apply {
            text = "Tout cocher"; textSize = 15f; setTextColor(couleur(R.color.accent)); setPadding(dp(10), dp(10), dp(4), dp(10))
            setOnClickListener {
                if (adaptateur.choisis.size == pistes.size) adaptateur.choisis.clear() else pistes.forEach { adaptateur.choisis += it.id }
                adaptateur.notifyDataSetChanged(); maj()
            }
        }
        haut.addView(btnTout)
        racine.addView(haut)
        racine.addView(TextView(this).apply {
            text = "Touche les titres à cocher."; textSize = 13f; setTextColor(couleur(R.color.gris)); setPadding(dp(16), 0, dp(16), dp(6))
        })

        adaptateur = PistesAdaptateur(object : PistesAdaptateur.Ecouteur {
            override fun clic(position: Int) {
                val p = adaptateur.pistes.getOrNull(position) ?: return
                if (!adaptateur.choisis.add(p.id)) adaptateur.choisis.remove(p.id)
                adaptateur.notifyItemChanged(position); maj()
            }
        })
        adaptateur.mode = Mode.CHOIX
        adaptateur.pistes = pistes.toMutableList()
        val liste = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@SelectionActivity); adapter = adaptateur }
        racine.addView(liste, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bas = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(10), dp(12), dp(14))
            setBackgroundColor(couleur(R.color.carte))
        }
        btnRetirer = bouton(false) { retirerDeLaPlaylist() }
        btnSupprimer = bouton(true) { demander(choisis().filter { it.id >= 0 }) }
        if (playlist != null) {
            bas.addView(btnRetirer, LinearLayout.LayoutParams(0, dp(52), 1f))
            bas.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        }
        bas.addView(btnSupprimer, LinearLayout.LayoutParams(0, dp(52), 1f))
        racine.addView(bas)
        setContentView(racine)
        maj()
    }

    private fun bouton(plein: Boolean, clic: () -> Unit) = TextView(this).apply {
        textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setBackgroundResource(if (plein) R.drawable.bg_bouton else R.drawable.bg_bouton_clair)
        setTextColor(if (plein) 0xFFFFFFFF.toInt() else couleur(R.color.accent))
        setOnClickListener { clic() }
    }

    private fun choisis(): List<Piste> = pistes.filter { adaptateur.choisis.contains(it.id) }

    private fun maj() {
        val c = choisis()
        val n = c.count { it.id >= 0 }
        btnSupprimer.text = "🗑 Supprimer du téléphone ($n)"
        btnSupprimer.alpha = if (n > 0) 1f else 0.4f
        btnRetirer.text = "Retirer de la playlist (${c.size})"
        btnRetirer.alpha = if (c.isNotEmpty()) 1f else 0.4f
        btnTout.text = if (c.size == pistes.size && pistes.isNotEmpty()) "Tout décocher" else "Tout cocher"
    }

    // ----- Retirer de la playlist (le fichier reste) -----

    private fun retirerDeLaPlaylist() {
        val pl = playlist ?: return
        val c = choisis()
        if (c.isEmpty()) return
        val base = Base.de(this)
        c.forEach { p -> p.empreinte?.let { base.retirer(pl, it) } }
        modifie = true
        Toast.makeText(this, "${c.size} titre(s) retiré(s) de la playlist (les fichiers restent sur le téléphone)", Toast.LENGTH_LONG).show()
        finish()
    }

    // ----- Supprimer du téléphone (corbeille d'Android) -----

    private fun demander(l: List<Piste>) {
        if (l.isEmpty()) { if (pistes.size <= 1) finish(); return }
        if (!Doublons.corbeillePossible()) {
            Toast.makeText(this, "Cette version d'Android ne permet pas la corbeille : suppression impossible ici.", Toast.LENGTH_LONG).show()
            finish(); return
        }
        val d = Doublons.demande(this, l.map { it.uri }, true) ?: run { finish(); return }
        enAttente = l
        try { startIntentSenderForResult(d, CORBEILLE, null, 0, 0, 0) } catch (e: Exception) {
            Toast.makeText(this, "Android a refusé la demande : rien n'a été supprimé.", Toast.LENGTH_LONG).show(); finish()
        }
    }

    @Deprecated("Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != CORBEILLE) return
        val l = enAttente
        enAttente = emptyList()
        if (resultCode != RESULT_OK) {
            Toast.makeText(this, "Annulé : rien n'a été supprimé.", Toast.LENGTH_SHORT).show()
            if (intent.getBooleanExtra("direct", false)) finish()
            return
        }
        val ctx = applicationContext
        travail.execute {
            val base = Base.de(ctx)
            // Les écoutes et favoris restent dans la base : ils reviennent si le fichier est restauré
            l.forEach { base.oublierChemin(it.cle) }
            val cles = l.map { it.cle }.toSet()
            Bibliotheque.derniere = Bibliotheque.derniere.filter { it.cle !in cles }
            Bibliotheque.videos = Bibliotheque.videos.filter { it.cle !in cles }
            modifie = true
            runOnUiThread { rapport(l) }
        }
    }

    private fun rapport(l: List<Piste>) {
        val lignes = ArrayList<String>()
        lignes += "🗑 ${l.size} fichier(s) mis à la corbeille d'Android (restaurables 30 jours : 📊 Données → ♻️ Corbeille)"
        l.forEach { lignes += "• ${it.dossier}${it.nom}" }
        lignes += "Écoutes et favoris gardés : ils reviennent si tu restaures."
        val t = lignes.joinToString("\n")
        AlertDialog.Builder(this)
            .setTitle("Suppression")
            .setMessage(t)
            .setPositiveButton("OK") { _, _ -> finish() }
            .setNeutralButton("📋 Copier") { _, _ ->
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Lecteur", "Suppression Lecteur — ${Format.date(System.currentTimeMillis())}\n$t"))
                Toast.makeText(this, "📋 Copié : colle-le dans la conversation", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setOnCancelListener { finish() }
            .show()
    }
}
