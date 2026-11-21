package fr.mesapplis.lecteur

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Aa : taille, police et couleurs des paroles (karaoké) et de leur traduction 🌐.
 * Les polices sont celles déjà présentes dans Android : rien à télécharger, APK pas plus lourde.
 */
object StyleParoles {

    val polices = listOf(
        "Normale" to "sans-serif", "Ronde" to "sans-serif-medium", "Fine" to "sans-serif-light", "Étroite" to "sans-serif-condensed",
        "Épaisse" to "sans-serif-black", "Livre" to "serif", "Machine" to "monospace", "Manuscrite" to "cursive", "BD" to "casual",
        "Majuscules" to "sans-serif-smallcaps",
    )
    val couleurs = listOf(
        "Noir" to 0xFF1A1C20.toInt(), "Bleu" to 0xFF1E63D6.toInt(), "Bleu doux" to 0xFF5B7DB8.toInt(), "Rouge" to 0xFFD62F2F.toInt(),
        "Rose" to 0xFFD63F8C.toInt(), "Orange" to 0xFFE07B00.toInt(), "Vert" to 0xFF1F9D55.toInt(), "Violet" to 0xFF7B3FD6.toInt(),
        "Gris" to 0xFF6B7280.toInt(),
    )

    var taille = 19
    var tailleTrad = 15
    var police = 0
    var couleur = 0
    var couleurTrad = 2
    private var charge = false

    fun charger(act: Activity) {
        if (charge) return
        val b = Base.de(act)
        taille = b.lire("p_taille")?.toIntOrNull() ?: 19
        tailleTrad = b.lire("p_taille_trad")?.toIntOrNull() ?: 15
        police = (b.lire("p_police")?.toIntOrNull() ?: 0).coerceIn(polices.indices)
        couleur = (b.lire("p_couleur")?.toIntOrNull() ?: 0).coerceIn(couleurs.indices)
        couleurTrad = (b.lire("p_couleur_trad")?.toIntOrNull() ?: 2).coerceIn(couleurs.indices)
        charge = true
    }

    private fun sauver(act: Activity) {
        val b = Base.de(act)
        b.ecrire("p_taille", taille.toString()); b.ecrire("p_taille_trad", tailleTrad.toString())
        b.ecrire("p_police", police.toString()); b.ecrire("p_couleur", couleur.toString()); b.ecrire("p_couleur_trad", couleurTrad.toString())
    }

    fun typeface(gras: Boolean = false): Typeface = Typeface.create(polices[police].second, if (gras) Typeface.BOLD else Typeface.NORMAL)
    fun couleurActive() = couleurs[couleur].second
    fun couleurTraduction() = couleurs[couleurTrad].second

    /** Panneau en bas de l'écran ; [changement] réaffiche les paroles à chaque appui (on voit le résultat tout de suite). */
    fun panneau(act: Activity, changement: () -> Unit) {
        charger(act)
        val d = act.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val texte = ContextCompat.getColor(act, R.color.texte)
        val gris = ContextCompat.getColor(act, R.color.gris)
        val accent = ContextCompat.getColor(act, R.color.accent)

        val vue = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(4)) }
        lateinit var reconstruire: () -> Unit

        fun titre(t: String) = vue.addView(TextView(act).apply {
            text = t; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(gris); setPadding(0, dp(12), 0, dp(6))
        })
        fun bouton(t: String, clic: () -> Unit) = TextView(act).apply {
            text = t; textSize = 18f; gravity = Gravity.CENTER; setTextColor(accent); typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0xFFEAF1FD.toInt()) }
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(44))
            setOnClickListener { clic(); sauver(act); changement(); reconstruire() }
        }
        fun reglageTaille(nom: String, valeur: Int, moins: () -> Unit, plus: () -> Unit) {
            val l = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            l.addView(TextView(act).apply { text = nom; textSize = 15f; setTextColor(texte) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            l.addView(bouton("A−", moins))
            l.addView(TextView(act).apply { text = "$valeur"; textSize = 16f; gravity = Gravity.CENTER; setTextColor(texte) }, LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.WRAP_CONTENT))
            l.addView(bouton("A+", plus))
            vue.addView(l, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }
        fun rangee(): LinearLayout {
            val r = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            vue.addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(r) })
            return r
        }
        fun pastilles(choisi: Int, choisir: (Int) -> Unit) {
            val r = rangee()
            couleurs.forEachIndexed { i, (nom, c) ->
                r.addView(View(act).apply {
                    contentDescription = nom
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL; setColor(c)
                        setStroke(dp(if (i == choisi) 4 else 1), if (i == choisi) accent else Color.WHITE)
                    }
                    setOnClickListener { choisir(i); sauver(act); changement(); reconstruire() }
                }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(10) })
            }
        }

        reconstruire = {
            vue.removeAllViews()
            titre("TAILLE")
            reglageTaille("Paroles", taille, { taille = (taille - 2).coerceAtLeast(12) }, { taille = (taille + 2).coerceAtMost(44) })
            reglageTaille("Traduction 🌐", tailleTrad, { tailleTrad = (tailleTrad - 2).coerceAtLeast(10) }, { tailleTrad = (tailleTrad + 2).coerceAtMost(40) })
            titre("POLICE")
            val r = rangee()
            polices.forEachIndexed { i, (nom, famille) ->
                r.addView(TextView(act).apply {
                    text = nom; textSize = 16f
                    typeface = Typeface.create(famille, Typeface.NORMAL)
                    setTextColor(if (i == police) Color.WHITE else texte)
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(18).toFloat(); setColor(if (i == police) accent else 0xFFF1F3F6.toInt())
                    }
                    setOnClickListener { police = i; sauver(act); changement(); reconstruire() }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
            }
            titre("COULEUR DE LA LIGNE CHANTÉE")
            pastilles(couleur) { couleur = it }
            titre("COULEUR DE LA TRADUCTION 🌐")
            pastilles(couleurTrad) { couleurTrad = it }
            vue.addView(TextView(act).apply {
                text = "Remettre comme au début"; textSize = 14f; setTextColor(accent); setPadding(0, dp(14), 0, dp(6))
                setOnClickListener { taille = 19; tailleTrad = 15; police = 0; couleur = 0; couleurTrad = 2; sauver(act); changement(); reconstruire() }
            })
        }
        reconstruire()

        val dlg = AlertDialog.Builder(act).setTitle("Aa  Paroles").setView(vue).setPositiveButton("OK", null).create()
        dlg.show()
        dlg.window?.apply {
            setGravity(Gravity.BOTTOM)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND) // on voit les paroles changer derrière
        }
    }
}
