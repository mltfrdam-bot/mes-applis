package fr.mesapplis.lecteur

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Typeface
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

class MainActivity : Activity() {

    enum class Tri { AJOUT, TITRE, ARTISTE, ECOUTES, DUREE }

    private lateinit var liste: RecyclerView
    private lateinit var adPistes: PistesAdaptateur
    private lateinit var adGroupes: GroupesAdaptateur
    private lateinit var compteur: TextView
    private lateinit var bandeau: TextView
    private lateinit var barreTri: View
    private lateinit var vide: View
    private lateinit var texteVide: TextView
    private lateinit var btnVide: TextView
    private lateinit var onglets: List<TextView>
    private lateinit var boutonsTri: Map<Tri, TextView>
    private lateinit var recherche: Recherche
    private lateinit var connexion: Connexion
    private lateinit var mini: MiniLecteur

    private var toutes: List<Piste> = emptyList()
    private var onglet = 0
    private var tri = Tri.AJOUT
    private var triInverse = false
    private var analyse = ""
    private var nbDoublons = 0

    private val principal = Handler(Looper.getMainLooper())
    private val travail = Executors.newSingleThreadExecutor()
    private val chargement = Runnable { charger() }
    private val observateur = object : ContentObserver(principal) {
        override fun onChange(selfChange: Boolean) = rechargerBientot(800)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        liste = findViewById(R.id.liste)
        compteur = findViewById(R.id.compteur)
        bandeau = findViewById(R.id.bandeau)
        barreTri = findViewById(R.id.barreTri)
        vide = findViewById(R.id.vide)
        texteVide = findViewById(R.id.texteVide)
        btnVide = findViewById(R.id.btnVide)

        adPistes = PistesAdaptateur(object : PistesAdaptateur.Ecouteur {
            override fun clic(position: Int) {
                Actions.jouer(connexion.ctrl, adPistes.pistes, position)
                val p = adPistes.pistes.getOrNull(position)
                if (p != null && p.nouveau) { p.nouveau = false; adPistes.notifyItemChanged(position) }
            }
            override fun favori(position: Int) {
                val p = adPistes.pistes.getOrNull(position) ?: return
                Actions.basculerFavori(this@MainActivity, p)
                if (onglet == 1 && !p.favori) afficher() else adPistes.notifyItemChanged(position)
            }
            override fun menu(position: Int, ancre: View) {
                val p = adPistes.pistes.getOrNull(position) ?: return
                Actions.menu(this@MainActivity, ancre, p, connexion.ctrl) { }
            }
        })
        adGroupes = GroupesAdaptateur { ouvrirGroupe(it) }
        liste.layoutManager = LinearLayoutManager(this)
        liste.setItemViewCacheSize(30)

        connexion = Connexion(this) { mini.maj() }
        mini = MiniLecteur(this, connexion) { id ->
            adPistes.enCours = id
            adPistes.notifyDataSetChanged()
        }

        recherche = Recherche(this) { afficher() }

        onglets = listOf(R.id.ongletMusique, R.id.ongletFavoris, R.id.ongletPlaylists, R.id.ongletDossiers).map { findViewById(it) }
        onglets.forEachIndexed { i, v -> v.setOnClickListener { choisirOnglet(i) } }
        boutonsTri = mapOf(
            Tri.AJOUT to findViewById(R.id.triAjout), Tri.TITRE to findViewById(R.id.triTitre),
            Tri.ARTISTE to findViewById(R.id.triArtiste), Tri.ECOUTES to findViewById(R.id.triEcoutes),
            Tri.DUREE to findViewById(R.id.triDuree),
        )
        boutonsTri.forEach { (t, v) ->
            v.setOnClickListener {
                if (tri == t) triInverse = !triInverse else { tri = t; triInverse = false }
                Base.de(this).ecrire("tri", "${tri.name}:$triInverse")
                afficher()
                liste.scrollToPosition(0)
            }
        }
        val base = Base.de(this)
        base.lire("tri")?.split(':')?.let { morceaux ->
            tri = Tri.values().firstOrNull { it.name == morceaux.getOrNull(0) } ?: Tri.AJOUT
            triInverse = morceaux.getOrNull(1) == "true"
        }
        onglet = base.lire("onglet")?.toIntOrNull()?.coerceIn(0, 3) ?: 0

        findViewById<View>(R.id.btnAide).setOnClickListener { startActivity(Intent(this, AideActivity::class.java)) }
        findViewById<View>(R.id.btnDonnees).setOnClickListener { startActivity(Intent(this, DonneesActivity::class.java)) }
        findViewById<View>(R.id.btnActualiser).setOnClickListener { actualiser() }
        bandeau.setOnClickListener {
            val cible = if (reglagesManquants(this) == 0 && nbDoublons > 0) DoublonsActivity::class.java else AideActivity::class.java
            startActivity(Intent(this, cible))
        }

        choisirOnglet(onglet)
        if (aLesDroits(this)) charger() else demanderDroits()
    }

    override fun onStart() {
        super.onStart()
        connexion.demarrer()
        mini.demarrer()
        contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observateur)
    }

    override fun onResume() {
        super.onResume()
        majBandeau()
        if (toutes.isNotEmpty()) afficher()
    }

    override fun onStop() {
        mini.arreter()
        contentResolver.unregisterContentObserver(observateur)
        connexion.arreter()
        super.onStop()
    }

    // ----- Droits -----

    private fun demanderDroits() = requestPermissions(droitsNecessaires(), 1)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        majBandeau()
        charger()
    }

    // ----- Bibliothèque -----

    private fun rechargerBientot(delai: Long) {
        principal.removeCallbacks(chargement)
        principal.postDelayed(chargement, delai)
    }

    private fun charger() {
        if (!aLesDroits(this)) { toutes = emptyList(); afficher(); return }
        val ctx = applicationContext
        travail.execute {
            val l = Bibliotheque.charger(ctx)
            val base = Base.de(ctx)
            val manquants = base.completer(ctx, l, calculer = false)
            Bibliotheque.derniere = l
            principal.post { toutes = l; afficher() }
            if (manquants > 0) {
                base.completer(ctx, l, calculer = true) { n, total ->
                    principal.post { analyse = "Analyse des fichiers : $n / $total"; majCompteur() }
                }
                principal.post { analyse = ""; afficher() }
            }
            // Doublons : proposés automatiquement dans le bandeau
            val d = Doublons.chercher(ctx, l).size
            principal.post { nbDoublons = d; majBandeau() }
            Sauvegarde.autoSiBesoin(ctx)
        }
    }

    private fun choisirOnglet(i: Int) {
        onglet = i
        onglets.forEachIndexed { k, v ->
            v.isSelected = k == i
            v.setTextColor(ContextCompat.getColor(this, if (k == i) R.color.accent else R.color.gris))
        }
        Base.de(this).ecrire("onglet", i.toString())
        afficher()
        liste.scrollToPosition(0)
    }

    private fun trier(l: List<Piste>): List<Piste> {
        val c: Comparator<Piste> = when (tri) {
            Tri.AJOUT -> compareByDescending<Piste> { it.nouveau }.thenByDescending { it.ajoute }
            Tri.TITRE -> compareBy { it.titre.lowercase() }
            Tri.ARTISTE -> compareBy<Piste> { it.artiste.ifEmpty { "￿" }.lowercase() }.thenBy { it.album.lowercase() }.thenBy { it.nom }
            Tri.ECOUTES -> compareByDescending<Piste> { it.ecoutes }.thenBy { it.titre.lowercase() }
            Tri.DUREE -> compareByDescending { it.duree }
        }
        val r = l.sortedWith(c)
        return if (triInverse) r.reversed() else r
    }

    private fun afficher() {
        val pistesOnglet = onglet <= 1
        barreTri.visibility = if (pistesOnglet) View.VISIBLE else View.GONE
        boutonsTri.forEach { (t, v) ->
            val actif = t == tri
            v.text = v.text.toString().trimEnd(' ', '▼', '▲') + if (actif) (if (triInverse) " ▲" else " ▼") else ""
            v.setTextColor(ContextCompat.getColor(this, if (actif) R.color.accent else R.color.gris))
            v.typeface = if (actif) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val nbAffiches: Int
        when (onglet) {
            0, 1 -> {
                val base = if (onglet == 1) toutes.filter { it.favori } else toutes
                val l = trier(base.filter { recherche.correspond(it.recherche) })
                adPistes.pistes = l.toMutableList()
                if (liste.adapter !== adPistes) liste.adapter = adPistes
                adPistes.notifyDataSetChanged()
                nbAffiches = l.size
            }
            2 -> {
                val bd = Base.de(this)
                val tout = bd.playlists(avecSupprimees = true)
                val actives = tout.filter { it.supprimeeLe == 0L && recherche.correspond(Texte.simplifier(it.nom)) }
                val parEmp = Bibliotheque.parEmpreinte()
                val g = ArrayList<Groupe>()
                if (recherche.texte.isEmpty()) g += Groupe("+", "➕ Nouvelle playlist", "Créer une liste de titres", null, R.drawable.ic_ajout_playlist)
                for (p in actives) {
                    val premier = bd.elements(p.id).firstNotNullOfOrNull { parEmp[it.empreinte] }
                    g += Groupe(p.id, p.nom, "${p.nombre} titre${if (p.nombre > 1) "s" else ""}", premier)
                }
                val corbeille = tout.count { it.supprimeeLe > 0 }
                if (corbeille > 0 && recherche.texte.isEmpty()) g += Groupe("corbeille", "🗑 Corbeille ($corbeille)", "Playlists supprimées : toucher pour en restaurer une", null, R.drawable.ic_fermer)
                adGroupes.groupes = g
                if (liste.adapter !== adGroupes) liste.adapter = adGroupes
                adGroupes.notifyDataSetChanged()
                nbAffiches = actives.size
            }
            else -> {
                val g = GroupesAdaptateur.dossiers(toutes).filter { recherche.correspond(it.recherche) }
                adGroupes.groupes = g
                if (liste.adapter !== adGroupes) liste.adapter = adGroupes
                adGroupes.notifyDataSetChanged()
                nbAffiches = g.size
            }
        }
        majCompteur()
        majVide(nbAffiches)
    }

    private fun majCompteur() {
        val nouveaux = toutes.count { it.nouveau }
        compteur.text = when {
            analyse.isNotEmpty() -> analyse
            toutes.isEmpty() -> ""
            nouveaux > 0 -> "${toutes.size} titres · $nouveaux nouveau${if (nouveaux > 1) "x" else ""}"
            else -> "${toutes.size} titres"
        }
    }

    private fun majVide(n: Int) {
        if (n > 0 || onglet == 2) { vide.visibility = View.GONE; return }
        vide.visibility = View.VISIBLE
        btnVide.visibility = View.VISIBLE
        when {
            !aLesDroits(this) -> {
                texteVide.text = "Pour afficher ta musique, l'appli doit pouvoir lire les fichiers audio du téléphone."
                btnVide.text = "Autoriser l'accès"
                btnVide.setOnClickListener { demanderDroits() }
            }
            recherche.texte.isNotEmpty() -> {
                texteVide.text = "Rien ne correspond à « ${recherche.champ.text} »."
                btnVide.visibility = View.GONE
            }
            onglet == 1 -> {
                texteVide.text = "Aucun favori pour l'instant.\n\nTouche l'étoile ☆ d'un titre pour l'ajouter ici."
                btnVide.visibility = View.GONE
            }
            else -> {
                texteVide.text = "Aucun fichier audio trouvé dans Musique, Download ou Movies.\n\nRegarde l'aide pour enregistrer tes téléchargements Termux au bon endroit."
                btnVide.text = "❓ Ouvrir l'aide"
                btnVide.setOnClickListener { startActivity(Intent(this, AideActivity::class.java)) }
            }
        }
    }

    private fun ouvrirGroupe(g: Groupe) {
        when {
            onglet == 3 -> startActivity(Intent(this, ListeActivity::class.java).putExtra("type", "dossier").putExtra("cle", g.cle))
            g.cle == "+" -> Actions.nouvellePlaylist(this) { id ->
                startActivity(Intent(this, ListeActivity::class.java).putExtra("type", "playlist").putExtra("cle", id))
            }
            g.cle == "corbeille" -> corbeille()
            else -> startActivity(Intent(this, ListeActivity::class.java).putExtra("type", "playlist").putExtra("cle", g.cle))
        }
    }

    private fun corbeille() {
        val bd = Base.de(this)
        val sup = bd.playlists(avecSupprimees = true).filter { it.supprimeeLe > 0 }
        AlertDialog.Builder(this)
            .setTitle("Restaurer une playlist")
            .setItems(sup.map { "${it.nom}  (${it.nombre}) — supprimée le ${Format.date(it.supprimeeLe)}" }.toTypedArray()) { _, i ->
                bd.supprimerPlaylist(sup[i].id, false)
                Toast.makeText(this, "✅ « ${sup[i].nom} » restaurée", Toast.LENGTH_SHORT).show()
                afficher()
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    /** Demande à Android de chercher les fichiers arrivés récemment (si un fichier n'apparaît pas tout seul). */
    private fun actualiser() {
        Toast.makeText(this, "Recherche des nouveaux fichiers…", Toast.LENGTH_SHORT).show()
        val racine = Environment.getExternalStorageDirectory()
        val chemins = Bibliotheque.dossiersSuivis.map { java.io.File(racine, it.trimEnd('/')).absolutePath }.toTypedArray()
        MediaScannerConnection.scanFile(applicationContext, chemins, null) { _, _ -> rechargerBientot(300) }
        rechargerBientot(1500)
    }

    private fun majBandeau() {
        val n = reglagesManquants(this)
        if (n == 0 && nbDoublons > 0) {
            bandeau.visibility = View.VISIBLE
            bandeau.text = "🔁 $nbDoublons morceau${if (nbDoublons > 1) "x" else ""} en double : touche ici pour choisir quoi supprimer."
        } else if (n == 0) {
            bandeau.visibility = View.GONE
        } else {
            bandeau.visibility = View.VISIBLE
            bandeau.text = "⚙️ Première utilisation : $n réglage${if (n > 1) "s" else ""} à faire (une seule fois). Touche ici."
        }
    }

    companion object {
        fun droitsNecessaires(): Array<String> =
            if (Build.VERSION.SDK_INT >= 33) arrayOf(
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.POST_NOTIFICATIONS
            ) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

        fun aLesDroits(ctx: Context): Boolean {
            val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
            return ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        }

        fun notificationsOk(ctx: Context): Boolean =
            Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        fun batterieOk(ctx: Context): Boolean =
            (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)

        /** Nombre de réglages de première utilisation pas encore faits. */
        fun reglagesManquants(ctx: Context): Int =
            listOf(aLesDroits(ctx), notificationsOk(ctx), batterieOk(ctx), Acces.aUnAcces(ctx)).count { !it }
    }
}
