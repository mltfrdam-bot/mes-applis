package fr.mesapplis.lecteur

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var liste: RecyclerView
    private lateinit var adaptateur: Adaptateur
    private lateinit var compteur: TextView
    private lateinit var bandeau: TextView
    private lateinit var vide: View
    private lateinit var texteVide: TextView
    private lateinit var btnVide: TextView
    private lateinit var mini: View
    private lateinit var miniBarre: ProgressBar
    private lateinit var miniPochette: ImageView
    private lateinit var miniTitre: TextView
    private lateinit var miniArtiste: TextView
    private lateinit var miniLecture: ImageView

    private var futur: ListenableFuture<MediaController>? = null
    private var ctrl: MediaController? = null
    private var pistes: List<Piste> = emptyList()
    private var miniAffiche: String? = null

    private val principal = Handler(Looper.getMainLooper())
    private val travail = Executors.newSingleThreadExecutor()
    private val chargement = Runnable { charger() }
    private val tic = object : Runnable {
        override fun run() {
            majBarre()
            principal.postDelayed(this, 500)
        }
    }
    private val observateur = object : ContentObserver(principal) {
        override fun onChange(selfChange: Boolean) = rechargerBientot(800)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        liste = findViewById(R.id.liste)
        compteur = findViewById(R.id.compteur)
        bandeau = findViewById(R.id.bandeau)
        vide = findViewById(R.id.vide)
        texteVide = findViewById(R.id.texteVide)
        btnVide = findViewById(R.id.btnVide)
        mini = findViewById(R.id.mini)
        miniBarre = findViewById(R.id.miniBarre)
        miniPochette = findViewById(R.id.miniPochette)
        miniTitre = findViewById(R.id.miniTitre)
        miniArtiste = findViewById(R.id.miniArtiste)
        miniLecture = findViewById(R.id.miniLecture)
        miniPochette.clipToOutline = true

        adaptateur = Adaptateur { jouer(it) }
        liste.layoutManager = LinearLayoutManager(this)
        liste.setHasFixedSize(true)
        liste.setItemViewCacheSize(30)
        liste.adapter = adaptateur

        findViewById<View>(R.id.btnAide).setOnClickListener { startActivity(Intent(this, AideActivity::class.java)) }
        findViewById<View>(R.id.btnActualiser).setOnClickListener { actualiser() }
        bandeau.setOnClickListener { startActivity(Intent(this, AideActivity::class.java)) }
        findViewById<View>(R.id.miniZone).setOnClickListener { ouvrirLecture() }
        miniLecture.setOnClickListener { ctrl?.let { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() } } }
        findViewById<View>(R.id.miniSuivant).setOnClickListener { ctrl?.seekToNext() }

        if (aLesDroits(this)) charger() else demanderDroits()
    }

    override fun onStart() {
        super.onStart()
        val jeton = SessionToken(this, ComponentName(this, LectureService::class.java))
        val f = MediaController.Builder(this, jeton).buildAsync()
        futur = f
        f.addListener({
            val c = (try { f.get() } catch (e: Exception) { null }) ?: return@addListener
            ctrl = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = majMini()
            })
            majMini()
        }, ContextCompat.getMainExecutor(this))
        contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observateur)
        principal.post(tic)
    }

    override fun onResume() {
        super.onResume()
        majBandeau()
    }

    override fun onStop() {
        principal.removeCallbacks(tic)
        contentResolver.unregisterContentObserver(observateur)
        futur?.let { MediaController.releaseFuture(it) }
        futur = null
        ctrl = null
        super.onStop()
    }

    // ----- Droits -----

    private fun demanderDroits() {
        requestPermissions(droitsNecessaires(), 1)
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
        if (!aLesDroits(this)) { afficher(emptyList()); return }
        val ctx = applicationContext
        travail.execute {
            val l = Bibliotheque.charger(ctx)
            Base.de(ctx).marquerNouveaux(l)
            val triee = Bibliotheque.trier(l)
            principal.post { afficher(triee) }
        }
    }

    private fun afficher(l: List<Piste>) {
        pistes = l
        adaptateur.pistes = l
        adaptateur.notifyDataSetChanged()
        val nouveaux = l.count { it.nouveau }
        compteur.text = when {
            l.isEmpty() -> ""
            nouveaux > 0 -> "${l.size} titres · $nouveaux nouveau${if (nouveaux > 1) "x" else ""}"
            else -> "${l.size} titres"
        }
        if (l.isEmpty()) {
            vide.visibility = View.VISIBLE
            if (!aLesDroits(this)) {
                texteVide.text = "Pour afficher ta musique, l'appli doit pouvoir lire les fichiers audio du téléphone."
                btnVide.text = "Autoriser l'accès"
                btnVide.setOnClickListener { demanderDroits() }
            } else {
                texteVide.text = "Aucun fichier audio trouvé dans Musique, Download ou Movies.\n\nRegarde l'aide pour enregistrer tes téléchargements Termux au bon endroit."
                btnVide.text = "❓ Ouvrir l'aide"
                btnVide.setOnClickListener { startActivity(Intent(this, AideActivity::class.java)) }
            }
        } else {
            vide.visibility = View.GONE
        }
    }

    /** Demande à Android de chercher les fichiers arrivés récemment (si un fichier n'apparaît pas tout seul). */
    private fun actualiser() {
        Toast.makeText(this, "Recherche des nouveaux fichiers…", Toast.LENGTH_SHORT).show()
        val racine = Environment.getExternalStorageDirectory()
        val chemins = Bibliotheque.dossiersSuivis.map { java.io.File(racine, it.trimEnd('/')).absolutePath }.toTypedArray()
        MediaScannerConnection.scanFile(applicationContext, chemins, null) { _, _ -> rechargerBientot(300) }
        rechargerBientot(1500)
    }

    private fun jouer(position: Int) {
        val c = ctrl ?: return
        c.setMediaItems(pistes.map { it.versMediaItem() }, position, 0L)
        c.prepare()
        c.play()
        val p = pistes.getOrNull(position)
        if (p != null && p.nouveau) {
            p.nouveau = false
            adaptateur.notifyItemChanged(position)
        }
    }

    private fun ouvrirLecture() {
        startActivity(Intent(this, LectureActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    // ----- Mini lecteur -----

    private fun majMini() {
        val c = ctrl ?: return
        val item = c.currentMediaItem
        if (item == null) { mini.visibility = View.GONE; return }
        mini.visibility = View.VISIBLE
        val md = c.mediaMetadata
        val titre = md.title?.toString() ?: ""
        miniTitre.text = titre
        miniArtiste.text = md.artist?.toString() ?: ""
        miniLecture.setImageResource(if (c.playWhenReady) R.drawable.ic_pause else R.drawable.ic_lecture)
        if (miniAffiche != item.mediaId) {
            miniAffiche = item.mediaId
            Pochettes.charger(miniPochette, android.net.Uri.parse(item.mediaId), Emplacement.de(item), titre, 144)
            adaptateur.enCours = item.mediaId
            adaptateur.notifyDataSetChanged()
        }
        majBarre()
    }

    private fun majBarre() {
        val c = ctrl ?: return
        val d = c.duration
        miniBarre.progress = if (d == C.TIME_UNSET || d <= 0) 0 else (c.currentPosition * 1000 / d).toInt()
    }

    private fun majBandeau() {
        val n = reglagesManquants(this)
        if (n == 0) {
            bandeau.visibility = View.GONE
        } else {
            bandeau.visibility = View.VISIBLE
            bandeau.text = "⚙️ Première utilisation : $n réglage${if (n > 1) "s" else ""} à faire (une seule fois). Touche ici."
        }
    }

    // ----- Liste -----

    private class Adaptateur(val surClic: (Int) -> Unit) : RecyclerView.Adapter<Adaptateur.Ligne>() {
        var pistes: List<Piste> = emptyList()
        var enCours: String? = null

        init { setHasStableIds(true) }

        inner class Ligne(v: View) : RecyclerView.ViewHolder(v) {
            val pochette: ImageView = v.findViewById(R.id.pochette)
            val titre: TextView = v.findViewById(R.id.titre)
            val artiste: TextView = v.findViewById(R.id.artiste)
            val nouveau: TextView = v.findViewById(R.id.nouveau)
            val duree: TextView = v.findViewById(R.id.duree)

            init {
                pochette.clipToOutline = true
                v.setOnClickListener {
                    val p = bindingAdapterPosition
                    if (p != RecyclerView.NO_POSITION) surClic(p)
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Ligne =
            Ligne(LayoutInflater.from(parent.context).inflate(R.layout.item_piste, parent, false))

        override fun getItemCount() = pistes.size

        override fun getItemId(position: Int) = pistes[position].id

        override fun onBindViewHolder(h: Ligne, position: Int) {
            val p = pistes[position]
            h.titre.text = p.titre
            h.artiste.text = listOf(p.artiste, p.album).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { p.dossier.trimEnd('/') }
            h.duree.text = Format.duree(p.duree)
            h.nouveau.visibility = if (p.nouveau) View.VISIBLE else View.GONE
            val joue = p.uri.toString() == enCours
            h.itemView.isActivated = joue
            h.titre.setTextColor(ContextCompat.getColor(h.itemView.context, if (joue) R.color.accent else R.color.texte))
            h.titre.paint.isFakeBoldText = joue
            Pochettes.charger(h.pochette, p.uri, Emplacement(p.volume, p.dossier, p.nom), p.titre, 132)
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
