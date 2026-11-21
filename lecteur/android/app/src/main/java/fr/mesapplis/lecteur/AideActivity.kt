package fr.mesapplis.lecteur

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/** ❓ Aide : réglages de première utilisation, Termux, et rapport à copier pour Claude. */
class AideActivity : Activity() {

    private lateinit var contenu: LinearLayout
    private val travail = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val defil = ScrollView(this).apply { setBackgroundColor(ContextCompat.getColor(context, R.color.fond)) }
        contenu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(32))
        }
        defil.addView(contenu)
        setContentView(defil)
    }

    override fun onResume() {
        super.onResume()
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
            text = "❓ Aide"; textSize = 22f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(couleur(R.color.texte))
        })
        contenu.addView(haut)

        titre("Première utilisation")
        texte("4 réglages, une seule fois. Touche chaque bouton : l'appli t'emmène au bon endroit.")

        val droits = MainActivity.aLesDroits(this)
        carte(
            "1. Accès à la musique et aux vidéos", droits,
            "Pour trouver les fichiers téléchargés avec Termux.",
            if (droits) null else "Autoriser" to { requestPermissions(MainActivity.droitsNecessaires(), 1) }
        )
        val notif = MainActivity.notificationsOk(this)
        carte(
            "2. Notifications", notif,
            "Pour garder les commandes (lecture, suivant…) dans la notification et sur l'écran verrouillé.",
            if (notif) null else "Autoriser" to { requestPermissions(MainActivity.droitsNecessaires(), 1) }
        )
        val batterie = MainActivity.batterieOk(this)
        carte(
            "3. Batterie Samsung", batterie,
            "Sinon Samsung peut couper la musique écran éteint. Choisis « Autoriser ». " +
                "Si rien ne s'ouvre : Paramètres → Applications → Lecteur → Batterie → Non restreinte.",
            if (batterie) null else "Ne pas restreindre" to { demanderBatterie() }
        )
        val dossier = Acces.aUnAcces(this)
        carte(
            "4. Paroles et images de dossier", dossier,
            if (dossier) "Dossier autorisé : " + Acces.nomsDossiers(this).joinToString(", ") + ". Tu peux en ajouter un autre (ex. Download)."
            else "Android cache les fichiers .lrc aux autres applis. Dans la fenêtre qui s'ouvre, reste sur « Music » et touche « Utiliser ce dossier », puis « Autoriser ».",
            (if (dossier) "Ajouter un dossier" else "Choisir le dossier Musique") to {
                startActivityForResult(Acces.intentChoix(), LectureActivity.DEMANDE_DOSSIER)
            }
        )

        titre("Termux : enregistrer au bon endroit")
        texte(
            "Le dossier interne de Termux est invisible pour les autres applis. Tes téléchargements doivent aller dans :\n" +
                "• Musique → ~/storage/shared/Music\n• Vidéos → ~/storage/shared/Movies\n• Divers → ~/storage/shared/Download"
        )
        texte("Une seule fois, dans Termux, pour ouvrir l'accès au stockage :")
        commande("termux-setup-storage")
        texte("Exemple : télécharger une musique (ou toute une playlist) rangée par album, avec pochette et titre :")
        commande("yt-dlp -x --audio-format mp3 --embed-thumbnail --embed-metadata -o \"~/storage/shared/Music/%(playlist_title,album|Divers)s/%(title)s.%(ext)s\" LIEN")
        texte("Paroles : pose le fichier .lrc à côté de la chanson, avec le même nom (ex. Chanson.mp3 et Chanson.lrc). Les .srt et .vtt marchent aussi.")
        texte("Un fichier n'apparaît pas ? Touche 🔄 en haut de la liste.")

        titre("🌐 Traduction")
        texte(
            "Touche 🌐 en haut de l'écran principal pour traduire en français les titres, albums, dossiers et paroles en anglais. " +
                "La première fois, l'appli télécharge le pack de langue (environ 30 Mo, une seule fois, Wi-Fi conseillé) ; ensuite tout marche sans internet.\n" +
                "• Un appui sur 🌐 : activer ou désactiver.\n" +
                "• 📊 Données : traduction sous le texte anglais, ou à la place.\n" +
                "• Chaque texte n'est traduit qu'une fois, puis gardé : l'affichage reste instantané."
        )

        titre("🎬 Vidéos")
        texte(
            "Onglet Vidéos : les vidéos de Movies, Download et Music. Pendant la lecture :\n" +
                "• Toucher : afficher ou cacher les commandes.\n" +
                "• Balayer à gauche / à droite, ou double appui : reculer / avancer de 10 s.\n" +
                "• Balayer en haut / en bas : luminosité à gauche, volume à droite.\n" +
                "• Bouton Accueil ou ⧉ : petite fenêtre flottante.\n" +
                "• 🎧 Écouter seulement : le son continue écran éteint.\n" +
                "• 💬 Sous-titres : fichier .srt ou .vtt du même nom à côté de la vidéo (dossier Movies à autoriser ci-dessus avec « Ajouter un dossier »). Traduits si 🌐 est activé."
        )

        titre("✨ Économiseur Ambiance")
        texte(
            "Sur l'écran de lecture, touche ✨ : animation plein écran au rythme de la musique, aux couleurs de la pochette, avec les paroles.\n" +
                "• Toucher l'écran : changer de style (ondes, barres, particules, vinyle), paroles oui/non, lancement automatique (2, 5 ou 10 min sans toucher l'écran de lecture), pause, fermer.\n" +
                "• Le son est analysé par le lecteur lui-même : pas de micro, pas d'autorisation."
        )

        titre("📦 Pack de playlist (clé USB)")
        texte(
            "Pour donner une playlist à quelqu'un sans le reste de ta musique :\n" +
                "• Ouvre la playlist, touche « 📦 Pack clé USB », branche la clé et choisis-la. Un dossier au nom de la playlist y est créé, avec les chansons, les paroles et l'ordre.\n" +
                "• Chez l'autre personne (avec le Lecteur) : onglet Playlists → « 📥 Importer un pack », puis choisir le dossier du pack sur la clé. Les chansons vont dans Music, la playlist est recréée.\n" +
                "• Sans le Lecteur : les chansons du dossier se lisent avec n'importe quel lecteur, un autoradio ou un PC."
        )

        titre("🗑 Supprimer des titres")
        texte(
            "• Un titre : menu ⋮ de la ligne → « 🗑 Supprimer du téléphone ».\n" +
                "• Plusieurs : « ☑ Choisir » (barre de tri, ou en haut d'une playlist ou d'un dossier), coche les titres, puis « 🗑 Supprimer du téléphone ». Dans une playlist, « Retirer de la playlist » garde le fichier.\n" +
                "• Les fichiers vont dans la corbeille d'Android : 30 jours pour les restaurer (📊 Données → ♻️ Corbeille). Écoutes et favoris reviennent avec eux."
        )

        titre("📁 Autres dossiers")
        texte("Tes musiques ou vidéos sont ailleurs que dans Music, Download ou Movies ? 📊 Données → 📁 Dossiers suivis → « ➕ Ajouter un dossier ». Les doublons sont cherchés dans tous les dossiers suivis.")

        titre("⇄ Fondu et mix automatique")
        texte(
            "Sur l'écran de lecture, le bouton ⇄ change l'enchaînement à chaque appui : sans fondu → fondu 3 s → 6 s → 10 s → mix auto.\n" +
                "• Fondu : la fin du morceau baisse pendant que le suivant monte (pas entre deux pistes d'un même album).\n" +
                "• Mix auto : chaque morceau est analysé une fois (tempo, temps forts, blancs). L'appli coupe les blancs, cale le tempo du suivant (±8 %, voix inchangée), l'annonce en fond sans basses, puis enchaîne sur un temps fort.\n" +
                "• ⏭🎚 Mixer maintenant : écourte le morceau en cours et enchaîne en mix. Rappuie pour enchaîner tout de suite.\n" +
                "• Une pause ou un saut pendant l'enchaînement l'annule proprement."
        )

        titre("Doublons")
        texte(
            "Quand un même morceau est présent deux fois (même fichier, ou même titre et même durée), un bandeau 🔁 le signale. " +
                "Tu peux aussi chercher toi-même : 📊 Données → 🔁 Chercher les doublons.\n" +
                "• La meilleure qualité est cochée ✅ d'office ; touche une ligne pour changer.\n" +
                "• Les fichiers 🗑 vont dans la corbeille d'Android : 30 jours pour les restaurer (en bas de l'écran Doublons).\n" +
                "• Écoutes, favoris, reprise et playlists passent sur la version gardée.\n" +
                "• Les fichiers de paroles (.lrc) restent en place : rien n'est perdu si tu restaures."
        )

        titre("Rapport pour Claude")
        texte("Copie ce rapport et colle-le dans la conversation si quelque chose ne marche pas.")
        val ligne = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ligne.addView(bouton("📋 Copier", true) { rapport { copier(it) } }, poids())
        ligne.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        ligne.addView(bouton("Partager (Drive…)", false) { rapport { partager(it) } }, poids())
        contenu.addView(ligne, marge(10))
    }

    // ----- Actions -----

    @SuppressLint("BatteryLife")
    private fun demanderBatterie() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    @Deprecated("Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == LectureActivity.DEMANDE_DOSSIER && resultCode == RESULT_OK) {
            Toast.makeText(this, if (Acces.garder(this, data)) "✅ Dossier autorisé" else "Le dossier n'a pas pu être autorisé", Toast.LENGTH_SHORT).show()
            construire()
        }
    }

    private fun rapport(suite: (String) -> Unit) {
        val ctx = applicationContext
        travail.execute {
            val pistes = Bibliotheque.charger(ctx)
            val parDossier = pistes.groupingBy { it.dossier.substringBefore('/') }.eachCount()
            val albums = pistes.map { it.dossier }.distinct().size
            val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
            val t = buildString {
                appendLine("Rapport Lecteur — version $version")
                appendLine("Téléphone : ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
                appendLine("Accès musique : ${oui(MainActivity.aLesDroits(ctx))} · Notifications : ${oui(MainActivity.notificationsOk(ctx))} · Batterie non restreinte : ${oui(MainActivity.batterieOk(ctx))}")
                appendLine("Dossiers autorisés (paroles) : ${Acces.nomsDossiers(ctx).joinToString(", ").ifEmpty { "aucun" }}")
                appendLine("Fichiers audio trouvés : ${pistes.size} dans $albums dossier(s)")
                parDossier.forEach { (d, n) -> appendLine("  • $d : $n") }
                appendLine("Base : ${Base.de(ctx).totaux()}")
            }
            runOnUiThread { suite(t) }
        }
    }

    private fun oui(b: Boolean) = if (b) "oui" else "NON"

    private fun copier(t: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Rapport", t))
        Toast.makeText(this, "📋 Copié : colle-le dans la conversation", Toast.LENGTH_SHORT).show()
    }

    private fun partager(t: String) {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Rapport Lecteur")
            putExtra(Intent.EXTRA_TEXT, t)
        }
        startActivity(Intent.createChooser(i, "Envoyer le rapport"))
    }

    // ----- Petits morceaux d'affichage -----

    private fun titre(t: String) {
        contenu.addView(TextView(this).apply {
            text = t; textSize = 18f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(couleur(R.color.texte))
        }, marge(22))
    }

    private fun texte(t: String) {
        contenu.addView(TextView(this).apply {
            text = t; textSize = 15f
            setTextColor(couleur(R.color.texte))
            setLineSpacing(0f, 1.15f)
        }, marge(8))
    }

    private fun commande(c: String) {
        val l = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFFEEF0F3.toInt())
            setPadding(dp(12), dp(8), dp(8), dp(8))
        }
        l.addView(TextView(this).apply {
            text = c; textSize = 13f; typeface = Typeface.MONOSPACE
            setTextColor(couleur(R.color.texte))
            setTextIsSelectable(true)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        l.addView(bouton("📋", false) {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Commande", c))
            Toast.makeText(this, "📋 Commande copiée", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(dp(52), dp(44)).apply { marginStart = dp(8) })
        contenu.addView(l, marge(6))
    }

    private fun carte(nom: String, fait: Boolean, explication: String, action: Pair<String, () -> Unit>?) {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(if (fait) 0xFFE8F6EE.toInt() else 0xFFFFF4D6.toInt())
                setStroke(dp(1), if (fait) 0xFF9FD8B5.toInt() else 0xFFF2C94C.toInt())
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        c.addView(TextView(this).apply {
            text = (if (fait) "✅ " else "⬜ ") + nom; textSize = 16f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(couleur(R.color.texte))
        })
        c.addView(TextView(this).apply {
            text = explication; textSize = 14f
            setTextColor(couleur(R.color.gris))
            setPadding(0, dp(4), 0, 0)
        })
        if (action != null) c.addView(bouton(action.first, !fait, action.second), marge(10))
        contenu.addView(c, marge(10))
    }

    private fun bouton(t: String, plein: Boolean, clic: () -> Unit) = TextView(this).apply {
        text = t; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        minHeight = dp(46)
        setPadding(dp(12), 0, dp(12), 0)
        setBackgroundResource(if (plein) R.drawable.bg_bouton else R.drawable.bg_bouton_clair)
        setTextColor(if (plein) 0xFFFFFFFF.toInt() else couleur(R.color.accent))
        setOnClickListener { clic() }
    }

    private fun couleur(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun poids() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    private fun marge(haut: Int) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(haut) }
}
