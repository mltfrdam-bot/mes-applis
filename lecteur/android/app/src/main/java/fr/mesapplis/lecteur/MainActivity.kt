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
    private lateinit var btnTraduction: TextView

    private var toutes: List<Piste> = emptyList()
    private var videos: List<Piste> = emptyList()
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
                if (p != null && Format.estVideo(p.uri.toString())) startActivity(Intent(this@MainActivity, VideoActivity::class.java))
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

        // L'ordre des numéros ne change pas (réglage gardé) : Vidéos est le n° 4, affiché en 2e
        onglets = listOf(R.id.ongletMusique, R.id.ongletFavoris, R.id.ongletPlaylists, R.id.ongletDossiers, R.id.ongletVideos).map { findViewById(it) }
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
        onglet = base.lire("onglet")?.toIntOrNull()?.coerceIn(0, 4) ?: 0

        findViewById<View>(R.id.btnAide).setOnClickListener { startActivity(Intent(this, AideActivity::class.java)) }
        findViewById<View>(R.id.btnDonnees).setOnClickListener { startActivity(Intent(this, DonneesActivity::class.java)) }
        findViewById<View>(R.id.btnActualiser).setOnClickListener { actualiser() }
        btnTraduction = findViewById(R.id.btnTraduction)
        btnTraduction.setOnClickListener { basculerTraduction() }
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
        contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observateur)
    }

    override fun onResume() {
        super.onResume()
        majBandeau()
        majBoutonTraduction()
        if (toutes.isNotEmpty()) afficher()
        // Retour des réglages Android après avoir autorisé les vidéos
        if (videos.isEmpty() && videosOk(this) && Base.de(this).lire("video_demande") == "1") charger()
    }

    override fun onStop() {
        mini.arreter()
        contentResolver.unregisterContentObserver(observateur)
        connexion.arreter()
        super.onStop()
    }

    // ----- Droits -----

    private fun demanderDroits() = requestPermissions(droitsNecessaires(), 1)

    /** Vidéos : droit à part (groupe « Photos et vidéos » d'Android). Refusé pour de bon : on ouvre les réglages de l'appli. */
    private fun demanderVideos() {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (Base.de(this).lire("video_demande") == "1" && !shouldShowRequestPermissionRationale(p)) {
            Toast.makeText(this, "Autorisations → Photos et vidéos → « Toujours tout autoriser »", Toast.LENGTH_LONG).show()
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
            return
        }
        Base.de(this).ecrire("video_demande", "1")
        requestPermissions(arrayOf(p), 2)
    }

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
            Traduction.init(ctx)
            principal.post { majBoutonTraduction() }
            val l = Bibliotheque.charger(ctx)
            val v = Bibliotheque.chargerVideos(ctx)
            val base = Base.de(ctx)
            val manquants = base.completer(ctx, l, calculer = false) + base.completer(ctx, v, calculer = false)
            Bibliotheque.derniere = l
            Bibliotheque.videos = v
            principal.post { toutes = l; videos = v; afficher() }
            if (manquants > 0) {
                base.completer(ctx, v, calculer = true)
                base.completer(ctx, l, calculer = true) { n, total ->
                    principal.post { analyse = "Analyse des fichiers : $n / $total"; majCompteur() }
                }
                principal.post { analyse = ""; afficher() }
            }
            traduireBibliotheque(l + v)
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
        val pistesOnglet = onglet <= 1 || onglet == 4
        barreTri.visibility = if (pistesOnglet) View.VISIBLE else View.GONE
        boutonsTri.forEach { (t, v) ->
            val actif = t == tri
            v.text = v.text.toString().trimEnd(' ', '▼', '▲') + if (actif) (if (triInverse) " ▲" else " ▼") else ""
            v.setTextColor(ContextCompat.getColor(this, if (actif) R.color.accent else R.color.gris))
            v.typeface = if (actif) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val nbAffiches: Int
        when (onglet) {
            0, 1, 4 -> {
                val base = when (onglet) { 1 -> (toutes + videos).filter { it.favori }; 4 -> videos; else -> toutes }
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
                if (recherche.texte.isEmpty()) g += Groupe("importer", "📥 Importer un pack", "Depuis une clé USB : chansons, paroles et playlist", null, R.drawable.ic_dossier)
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
            onglet == 4 && !videosOk(this) -> {
                texteVide.text = "Le Lecteur n'a pas encore le droit de voir tes vidéos.\n\nDans la fenêtre d'Android, choisis « Toujours tout autoriser » (et non « Sélectionner »)."
                btnVide.text = "Autoriser les vidéos"
                btnVide.setOnClickListener { demanderVideos() }
            }
            onglet == 4 -> {
                texteVide.text = "Aucune vidéo trouvée dans Movies, Download ou Music.\n\nAvec Termux, enregistre tes vidéos dans ~/storage/shared/Movies."
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
            g.cle == "importer" -> choisirPack()
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

    // ----- 📥 Import d'un pack (clé USB) -----

    private var packEnAttente: android.net.Uri? = null

    private fun choisirPack() {
        AlertDialog.Builder(this)
            .setTitle("📥 Importer un pack")
            .setMessage("Branche la clé USB, touche « Choisir le pack », ouvre la clé puis le dossier du pack (ex. « Céline Dion ») et touche « Utiliser ce dossier ».")
            .setPositiveButton("Choisir le pack") { _, _ ->
                try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), IMPORT_PACK) } catch (e: Exception) { }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun importerPack(arbre: android.net.Uri) {
        val (fenetre, avance) = Pack.attente(this, "📥 Import du pack…")
        val ctx = applicationContext
        travail.execute {
            if (Bibliotheque.derniere.isNotEmpty()) Base.de(ctx).completer(ctx, Bibliotheque.derniere, calculer = true)
            val r = Pack.importer(ctx, arbre, avance)
            principal.post {
                fenetre.dismiss()
                if (r.musiqueRefusee) {
                    packEnAttente = arbre
                    AlertDialog.Builder(this)
                        .setTitle("Autoriser le dossier Musique")
                        .setMessage("Pour ranger les chansons du pack, l'appli doit pouvoir écrire dans Musique (une seule fois). Dans la fenêtre qui s'ouvre, reste sur « Music », touche « Utiliser ce dossier », puis « Autoriser ».")
                        .setPositiveButton("Continuer") { _, _ -> startActivityForResult(Acces.intentChoix(), DROIT_MUSIQUE) }
                        .setNegativeButton("Annuler", null)
                        .show()
                    return@post
                }
                Pack.montrerRapport(this, "Import du pack", r)
                rechargerBientot(1500)
            }
        }
    }

    @Deprecated("Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            IMPORT_PACK -> data?.data?.let { importerPack(it) }
            DROIT_MUSIQUE -> {
                Acces.garder(this, data)
                val p = packEnAttente
                packEnAttente = null
                if (Acces.arbreMusique(this) == null) {
                    Toast.makeText(this, "Choisis bien le dossier « Music » (sans entrer dans un sous-dossier).", Toast.LENGTH_LONG).show()
                } else if (p != null) importerPack(p)
            }
        }
    }

    // ----- 🌐 Traduction -----

    private fun traduireBibliotheque(l: List<Piste>) {
        Traduction.traduireBibliotheque(applicationContext, l) { principal.post { afficher(); mini.maj() } }
    }

    private fun majBoutonTraduction() {
        btnTraduction.alpha = if (Traduction.actif && Traduction.pret) 1f else 0.35f
    }

    /** Un appui : active ou désactive. La première fois, propose de télécharger le pack de langue. */
    private fun basculerTraduction() {
        if (!Traduction.pret) { proposerPack(); return }
        Traduction.activer(this, !Traduction.actif)
        majBoutonTraduction()
        Toast.makeText(this, if (Traduction.actif) "🌐 Traduction activée" else "Traduction désactivée", Toast.LENGTH_SHORT).show()
        afficher(); mini.maj()
        if (Traduction.actif) traduireBibliotheque(toutes + videos)
    }

    private fun proposerPack() {
        AlertDialog.Builder(this)
            .setTitle("🌐 Traduction anglais → français")
            .setMessage(
                "Pour traduire les titres et les paroles en anglais, l'appli doit télécharger une seule fois le pack de langue " +
                    "(environ 30 Mo, Wi-Fi conseillé).\n\nEnsuite, la traduction marche sans internet. " +
                    "Tu pourras choisir dans 📊 Données : traduction sous le texte anglais, ou à la place."
            )
            .setPositiveButton("Télécharger") { _, _ -> telechargerPack() }
            .setNegativeButton("Plus tard", null)
            .show()
    }

    private fun telechargerPack() {
        val attente = AlertDialog.Builder(this)
            .setTitle("🌐 Téléchargement du pack…")
            .setMessage("Environ 30 Mo. Ça peut prendre une à deux minutes. Tu peux continuer à écouter ta musique.")
            .setView(android.widget.ProgressBar(this).apply { isIndeterminate = true; setPadding(0, 30, 0, 30) })
            .setCancelable(false)
            .show()
        val ctx = applicationContext
        Thread {
            val erreur = Traduction.telecharger(ctx)
            principal.post {
                attente.dismiss()
                if (erreur != null) {
                    AlertDialog.Builder(this).setTitle("❌ Pack non téléchargé").setMessage(erreur).setPositiveButton("OK", null).show()
                    return@post
                }
                Traduction.activer(this, true)
                majBoutonTraduction()
                Toast.makeText(this, "✅ Pack installé : traduction activée. Les titres se traduisent petit à petit.", Toast.LENGTH_LONG).show()
                traduireBibliotheque(toutes + videos)
            }
        }.start()
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
        const val IMPORT_PACK = 51
        const val DROIT_MUSIQUE = 52

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

        fun videosOk(ctx: Context): Boolean = ctx.checkSelfPermission(
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED

        fun notificationsOk(ctx: Context): Boolean =
            Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        fun batterieOk(ctx: Context): Boolean =
            (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)

        /** Nombre de réglages de première utilisation pas encore faits. */
        fun reglagesManquants(ctx: Context): Int =
            listOf(aLesDroits(ctx), notificationsOk(ctx), batterieOk(ctx), Acces.aUnAcces(ctx)).count { !it }
    }
}
