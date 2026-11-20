package fr.mesapplis.lecteur

import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player

/** Minuterie de sommeil : la musique baisse doucement puis s'arrête. */
object Minuterie {
    var joueur: Player? = null
    /** Heure de fin (0 = pas de minuterie). */
    var fin = 0L
        private set
    /** Arrêt à la fin du titre en cours. */
    var finDuTitre = false
        private set

    private val principal = Handler(Looper.getMainLooper())
    private const val FONDU = 15_000L

    private val tic = object : Runnable {
        override fun run() {
            val j = joueur ?: return
            val reste = fin - System.currentTimeMillis()
            if (fin == 0L) return
            if (reste <= 0) {
                j.pause()
                j.volume = 1f
                fin = 0L
                return
            }
            j.volume = if (reste < FONDU) (reste.toFloat() / FONDU).coerceIn(0.05f, 1f) else 1f
            principal.postDelayed(this, 500)
        }
    }

    fun programmer(minutes: Int) {
        annuler()
        fin = System.currentTimeMillis() + minutes * 60_000L
        principal.post(tic)
    }

    fun programmerFinDuTitre() {
        annuler()
        finDuTitre = true
    }

    /** Appelé au changement de titre. */
    fun finDeTitre() {
        if (!finDuTitre) return
        finDuTitre = false
        joueur?.pause()
    }

    fun annuler() {
        principal.removeCallbacks(tic)
        fin = 0L
        finDuTitre = false
        joueur?.volume = 1f
    }

    fun active() = fin > 0 || finDuTitre

    fun texte(): String = when {
        finDuTitre -> "fin du titre"
        fin > 0 -> Format.duree(fin - System.currentTimeMillis())
        else -> ""
    }
}
