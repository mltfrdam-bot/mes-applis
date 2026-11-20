package fr.mesapplis.lecteur

import android.content.Context

/** Une ligne de paroles ; t = moment en millisecondes (-1 si les paroles ne sont pas synchronisées). */
class Ligne(val t: Long, val texte: String)

class Paroles(val lignes: List<Ligne>, val synchro: Boolean, val fichier: String) {
    /** Ligne à afficher en surbrillance au moment donné. */
    fun indexA(ms: Long): Int {
        if (!synchro || lignes.isEmpty() || ms < lignes[0].t) return -1
        var bas = 0
        var haut = lignes.size - 1
        while (bas < haut) {
            val milieu = (bas + haut + 1) / 2
            if (lignes[milieu].t <= ms) bas = milieu else haut = milieu - 1
        }
        return bas
    }
}

/** Trouve et lit le fichier de paroles posé à côté de la chanson (même nom) : .lrc, .srt, .vtt ou .txt. */
object LecteurParoles {
    private val memo = HashMap<String, Paroles?>()

    @Synchronized fun vider() = memo.clear()

    fun chercher(ctx: Context, e: Emplacement): Paroles? {
        synchronized(this) { if (memo.containsKey(e.cle)) return memo[e.cle] }
        val base = e.nom.substringBeforeLast('.')
        val extensions = listOf("lrc", "fr.lrc", "en.lrc", "srt", "fr.srt", "en.srt", "vtt", "fr.vtt", "en.vtt", "txt")
        var trouve: Paroles? = null
        for (ext in extensions) {
            val u = Acces.uriFichier(ctx, e.volume, e.dossier, "$base.$ext") ?: return null
            val texte = Acces.lireTexte(ctx, u) ?: continue
            trouve = analyser(texte, ext.substringAfterLast('.'), "$base.$ext")
            if (trouve.lignes.isNotEmpty()) break
        }
        synchronized(this) { memo[e.cle] = trouve }
        return trouve
    }

    fun analyser(brut: String, ext: String, fichier: String): Paroles {
        val t = brut.removePrefix("﻿").replace("\r", "")
        return when (ext) {
            "lrc" -> lrc(t, fichier)
            "srt", "vtt" -> sousTitres(t, fichier)
            else -> Paroles(t.lines().map { Ligne(-1, it.trim()) }, false, fichier)
        }
    }

    private val tagTemps = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val tagMot = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")
    private val tagDecalage = Regex("""^\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)

    private fun lrc(t: String, fichier: String): Paroles {
        var decalage = 0L
        val res = ArrayList<Ligne>()
        val sansTemps = ArrayList<String>()
        for (brute in t.lines()) {
            val ligne = brute.trim()
            val d = tagDecalage.find(ligne)
            if (d != null) { decalage = d.groupValues[1].toLongOrNull() ?: 0L; continue }
            val tags = ArrayList<MatchResult>()
            var i = 0
            while (true) {
                val m = tagTemps.find(ligne, i)
                if (m == null || m.range.first != i) break
                tags += m
                i = m.range.last + 1
            }
            if (tags.isEmpty()) {
                if (ligne.isNotEmpty() && !ligne.startsWith("[")) sansTemps += ligne
                continue
            }
            val texte = tagMot.replace(ligne.substring(i), "").trim()
            for (m in tags) {
                val mn = m.groupValues[1].toLong()
                val s = m.groupValues[2].toLong()
                val frac = (m.groupValues[3] + "000").take(3).toLong()
                res += Ligne(maxOf(0L, mn * 60000 + s * 1000 + frac - decalage), texte)
            }
        }
        if (res.isEmpty()) return Paroles(sansTemps.map { Ligne(-1, it) }, false, fichier)
        res.sortBy { it.t }
        return Paroles(res, true, fichier)
    }

    private val tempsSt = Regex("""(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{3})\s*-->""")
    private val balise = Regex("<[^>]*>")

    private fun sousTitres(t: String, fichier: String): Paroles {
        val res = ArrayList<Ligne>()
        for (bloc in t.split(Regex("""\n\s*\n"""))) {
            val lignes = bloc.lines()
            val iT = lignes.indexOfFirst { tempsSt.containsMatchIn(it) }
            if (iT < 0) continue
            val m = tempsSt.find(lignes[iT]) ?: continue
            val h = m.groupValues[1].ifEmpty { "0" }.toLong()
            val ms = h * 3600000 + m.groupValues[2].toLong() * 60000 + m.groupValues[3].toLong() * 1000 + m.groupValues[4].toLong()
            val textes = lignes.drop(iT + 1).map { nettoyer(it) }.filter { it.isNotEmpty() }
            // Sous-titres automatiques de YouTube : chaque ligne revient deux fois, on ne garde que la nouvelle
            for (x in textes) if (res.isEmpty() || res.last().texte != x) res += Ligne(ms, x)
        }
        return Paroles(res, res.isNotEmpty(), fichier)
    }

    private fun nettoyer(s: String): String = balise.replace(s, "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&#39;", "'").replace("&quot;", "\"")
        .replace("&nbsp;", " ").trim()
}
