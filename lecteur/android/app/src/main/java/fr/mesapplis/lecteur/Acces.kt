package fr.mesapplis.lecteur

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Accès aux fichiers qui ne sont pas de la musique (paroles .lrc, images de dossier).
 * Android les cache aux autres applis : Frédéric autorise une seule fois le dossier Musique.
 */
object Acces {
    @Volatile private var memo: List<Uri>? = null

    fun dossiers(ctx: Context): List<Uri> = memo ?: ctx.contentResolver.persistedUriPermissions
        .filter { it.isReadPermission }.map { it.uri }.also { memo = it }

    fun aUnAcces(ctx: Context) = dossiers(ctx).isNotEmpty()

    /** Noms lisibles des dossiers autorisés, ex. « Music ». */
    fun nomsDossiers(ctx: Context): List<String> = dossiers(ctx).map {
        try { DocumentsContract.getTreeDocumentId(it).substringAfter(':').ifEmpty { "Tout le stockage" } } catch (e: Exception) { it.toString() }
    }

    private fun idDocument(volume: String, dossier: String, nom: String): String {
        val v = if (volume.isEmpty() || volume == "external" || volume == "external_primary") "primary" else volume.uppercase()
        return "$v:$dossier$nom"
    }

    /** Adresse d'un fichier voisin (même dossier), ou null si le dossier n'est pas autorisé. */
    fun uriFichier(ctx: Context, volume: String, dossier: String, nom: String): Uri? {
        val id = idDocument(volume, dossier, nom)
        for (arbre in dossiers(ctx)) {
            val racine = try { DocumentsContract.getTreeDocumentId(arbre) } catch (e: Exception) { null } ?: continue
            if (id.startsWith("$racine/") || (racine.endsWith(":") && id.startsWith(racine))) {
                return DocumentsContract.buildDocumentUriUsingTree(arbre, id)
            }
        }
        return null
    }

    fun lireTexte(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (e: Exception) { null }

    fun lireOctets(ctx: Context, uri: Uri): ByteArray? = try {
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (e: Exception) { null }

    /** Ouvre le sélecteur Android directement sur le dossier Musique. */
    fun intentChoix(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        putExtra(
            DocumentsContract.EXTRA_INITIAL_URI,
            DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Music")
        )
    }

    fun garder(ctx: Context, data: Intent?): Boolean {
        val u = data?.data ?: return false
        return try {
            ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            memo = null
            Pochettes.vider()
            LecteurParoles.vider()
            true
        } catch (e: Exception) { false }
    }
}
