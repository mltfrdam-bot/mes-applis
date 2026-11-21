# Cahier des charges — APK Lecteur Musique & Vidéo

20/11/2026 · Maillot Frédéric · Dépôt GitHub `mltfrdam-bot/mes-applis`, fichier `docs/cahier-des-charges-lecteur.md`

## 1. Contexte et mission

Ta mission : créer une APK Android de lecteur musique et vidéo, **légère, ultra réactive et belle**. Elle lit les fichiers téléchargés avec Termux, et elle a trois modes d'affichage : Chevet, Fond d'écran animé et Conduite.

**L'utilisateur.** Frédéric, 45 ans, La Réunion (UTC+4), chef d'équipe VRD. Il ne lit pas l'anglais : toute l'interface, les messages, l'aide et tes réponses sont en français simple. Téléphone : Samsung Galaxy S23 Ultra (One UI, Android récent, écran OLED). Il télécharge sa musique et ses vidéos avec Termux (yt-dlp).

**Règles de travail (non négociables).**

- Aucune donnée perdue, jamais (playlists, favoris, compteurs d'écoute, reprises). Toute migration passe par sauvegarde + fusion, avec le descriptif de ce qui a été ajouté.
- Gain de temps d'abord : un appui simple plutôt qu'un appui long, le moins d'écrans possible.
- Mode clair par défaut, style classique. Les listes de gestion (bibliothèque, statistiques) sont en tableaux façon Excel. Le noir n'est autorisé qu'en mode Conduite, selon le réglage de l'utilisateur (voir section 5).
- Pas d'appli tierce à installer en plus, pas de service cloud tiers, pas de publicité, pas de compte.
- Avant de coder une idée nouvelle : la présenter en 2-3 lignes et attendre son accord. Ce qui est écrit ici est déjà validé.
- Livrer par petites versions testables, chacune avec ce qui a changé en 3-5 lignes.
- Tout rapport destiné à Claude a un bouton « 📋 Copier » (texte collé dans la conversation), et le Drive en option.

## 2. Les fichiers : lecture de ce que Termux télécharge

Première règle : tout fichier audio ou vidéo déposé par Termux dans le stockage partagé apparaît dans l'appli sans rien faire.

**Où Termux doit enregistrer.** Le dossier interne de Termux (`~/`) est invisible pour les autres applis. Les téléchargements doivent aller dans le stockage partagé :

| Type | Dossier Termux | Dossier vu par Android |
| --- | --- | --- |
| Musique | `~/storage/shared/Music` | `Musique` |
| Vidéos | `~/storage/shared/Movies` | `Movies` |
| Divers | `~/storage/shared/Download` | `Download` |

L'aide intégrée explique ça en une page, avec la commande `termux-setup-storage` à lancer une seule fois. Elle donne aussi un exemple de commande yt-dlp qui enregistre directement au bon endroit.

**Lecture des fichiers.**

- Formats audio : mp3, m4a/aac, opus, ogg, flac, wav. Formats vidéo : mp4, webm, mkv.
- Lecture via le MediaStore d'Android (permissions `READ_MEDIA_AUDIO` et `READ_MEDIA_VIDEO`), avec choix possible d'autres dossiers.
- Détection automatique des nouveaux fichiers (observation du MediaStore) : un fichier qui vient d'arriver apparaît en tête, marqué « Nouveau ».
- Pochette : celle intégrée au fichier, sinon une image du dossier, sinon une pochette générée (couleur + initiales).
- Titre/artiste : lus dans les tags. Sinon, déduits du nom du fichier en retirant les identifiants YouTube (ex. `[dQw4w9WgXcQ]`).
- **Rangement de Frédéric** : `Music/<album ou playlist>/`, avec un fichier de paroles à côté de chaque chanson.
- **Paroles en karaoké** (validé le 20/11/2026) : fichier de même nom que la chanson (`.lrc`, aussi `.srt`, `.vtt`, `.txt`). La ligne en cours est surlignée et centrée ; toucher une ligne amène la chanson à ce moment. Android cache ces fichiers aux autres applis : Frédéric autorise une seule fois le dossier Musique (sélecteur de dossier Android).

## 3. Lecteur de base (le cœur)

**Musique.**

- Lecture en arrière-plan écran éteint : service de premier plan et MediaSession. Commandes dans la notification, sur l'écran verrouillé, sur les écouteurs Bluetooth et sur l'autoradio.
- File d'attente, aléatoire, répétition (une piste / toute la liste), minuterie de sommeil.
- Reprise exacte là où on s'était arrêté, même après redémarrage du téléphone.
- Enchaînement sans blanc entre les pistes (gapless).
- Pause automatique quand le casque est débranché ou le Bluetooth coupé.

**Vidéo.**

- Lecture plein écran, gestes simples : balayer à gauche/droite pour reculer/avancer de 10 s, balayer en haut/bas pour la luminosité et le volume.
- Petite fenêtre flottante (Picture-in-Picture) quand on quitte l'appli.
- Option « Écouter seulement » : la vidéo continue en son seul, écran éteint (pratique pour les vidéos de musique ou les cours).
- Reprise de chaque vidéo là où elle avait été arrêtée.

**Bibliothèque.** Onglets Musique · Vidéos · Playlists · Dossiers. Recherche instantanée pendant la frappe. Tri par titre, artiste, date d'ajout ou nombre d'écoutes. Favoris en un appui.

## 4. Mode Chevet / Bureau (écran de veille intelligent)

L'écran reste allumé à faible luminosité quand le téléphone charge ou est posé.

- **Affichage épuré** : heure en très grand, titre en cours, barre de progression très fine, 3 gros boutons (Précédent, Lecture/Pause, Suivant) utilisables sans regarder.
- **Fond réactif** : dégradé lent qui reprend les 2 couleurs principales de la pochette en cours (extraction des couleurs, transition douce au changement de piste).
- **Gestes** : balayer pour changer de piste, double-tap pour pause.
- **Lancement** : manuel en un appui, ou automatique au branchement du chargeur (réglable).
- **Protection anti-brûlure OLED** : l'heure et les textes se déplacent de quelques pixels chaque minute ; luminosité réduite la nuit.

## 5. Mode Conduite / Focus (contrôle à l'aveugle)

Pour la voiture, le sport ou le chantier : téléphone visible, zéro distraction.

- Contraste maximal, texte très gros, 3 zones de toucher géantes.
- **Couleurs** (décidé le 20/11/2026) : réglables par l'utilisateur, au choix : clair toujours, noir toujours, ou automatique (clair le jour, noir pur la nuit).
- **Automatique** : se lance dès la connexion d'un appareil Bluetooth choisi (autoradio, enceinte) ou au branchement du chargeur. Se ferme à la déconnexion.
- Écran maintenu allumé seulement tant que le mode est actif.

## 6. Fond d'écran animé « Ambiance » (Live Wallpaper)

Le fond d'écran d'accueil du téléphone réagit à la musique, sans ouvrir l'appli.

- Styles au choix : ondes fluides, vinyle qui tourne, particules lentes qui pulsent au rythme du son.
- Se fige doucement quand la musique est en pause.
- Couleurs automatiques selon la pochette, et clair/sombre selon l'heure.
- Activation en un appui (écran de choix du fond d'écran d'Android).
- **Batterie** : animation en pause dès que l'écran d'accueil n'est pas visible. Images par seconde limitées (30 maximum).

## 7. Musique des autres applis (option, plus tard)

Les modes Chevet et Conduite et le fond d'écran animé peuvent aussi afficher et piloter la musique jouée par Spotify, Deezer ou YouTube Music. Techniquement, l'appli lit les sessions média actives grâce à `NotificationListenerService`.

- Désactivé par défaut. Une autorisation à donner une seule fois, expliquée dans l'aide.
- Rien n'est enregistré ni envoyé : l'appli lit seulement le titre, la pochette et l'état lecture/pause.

## 8. Léger, réactif, beau : objectifs chiffrés

| Critère | Objectif |
| --- | --- |
| Ouverture de l'appli | Moins de 0,5 s jusqu'à la bibliothèque affichée |
| Lancer une piste | Le son démarre en moins de 0,2 s après l'appui |
| Bibliothèque de 5 000 fichiers | Défilement fluide, recherche instantanée |
| Taille de l'APK | Le plus petit possible (objectif : moins de 15 Mo) |
| Batterie | Lecture écran éteint : pas plus qu'un lecteur classique |

**Beau.** Animations courtes et fluides, typographie lisible, pochettes en grand, couleurs reprises de la pochette. Lisible en plein soleil.

## 9. Données : sauvegarde, fusion, statistiques

Une seule base de données (Room/SQLite) : playlists, favoris, nombre d'écoutes, dernière position de chaque fichier, réglages.

- **Identifiant de chaque fichier** basé sur son contenu (pas seulement son chemin) : un fichier déplacé ou renommé garde ses écoutes et ses favoris.
- **Sauvegarde** en un appui vers un fichier (dossier Téléchargements) et, en option, vers Google Drive par le menu Partager d'Android.
- **Fusion** de deux sauvegardes : rien n'est jamais écrasé, le descriptif liste ce qui a été ajouté. Même comportement que dans ses autres applis.
- Sauvegarde automatique locale quotidienne, les 7 dernières gardées.
- **Statistiques** en tableau : écoutes par piste, par artiste, par semaine. Rapport 📋 Copier pour l'envoyer à Claude.
- Pas de données à récupérer d'un ancien lecteur (confirmé le 20/11/2026).
- Fichiers de sauvegarde : `Download/Lecteur/` (manuelles et « avant fusion »), `Download/Lecteur/Auto/` (automatiques). Format JSON `lecteur-sauvegarde` version 1.

## 10. Aide intégrée et hors ligne

- Onglet ❓ Aide, une fiche par fonction, phrases courtes, pas d'anglais.
- Page « Première utilisation » : autoriser l'accès aux fichiers, configurer Termux (`termux-setup-storage` + bon dossier), sortir l'appli de l'optimisation de batterie Samsung, autoriser le dossier Musique (paroles), activer le fond d'écran animé.
- L'appli fonctionne à 100 % sans internet, sauf le téléchargement unique du pack de traduction (section 13.1).

## 11. Technique et atelier APK

**Atelier.** Projet natif dans `lecteur/android/` (Kotlin, Media3, SQLite, écrans Android classiques sans WebView). Le fichier `.github/workflows/lecteur.yml` fabrique, signe (clé `signature/mes-applis.keystore`, secret `CLE_MOT_DE_PASSE`) et publie `Lecteur.apk` dans les Releases, tag `lecteur` (toujours la dernière version). appId `fr.mesapplis.lecteur`. Les autres APK ne sont pas touchées.

Lien direct : https://github.com/mltfrdam-bot/mes-applis/releases/download/lecteur/Lecteur.apk

**Permissions minimales** : accès musique et vidéo, notifications, service de premier plan (lecture média), Bluetooth (détection de connexion pour le mode Conduite), optionnellement l'accès aux notifications (section 7). **Internet** (décidé le 21/11/2026, choix A) : autorisé uniquement pour le téléchargement unique du pack de traduction anglais → français ; aucune autre fonction ne s'en sert.

## 12. Étapes, tests et critères de réussite

Livrer dans cet ordre, une APK testable à chaque étape.

1. **Socle** : l'APK s'installe, trouve les fichiers Termux, lit la musique en arrière-plan avec la notification et l'écran verrouillé. Paroles en karaoké. Aide « Première utilisation ». — *Livré le 20/11/2026 (version 1.1).*
2. **Bibliothèque complète** : recherche, playlists, favoris, reprise, sauvegarde + fusion. — *Livré le 20/11/2026 (version 1.2) : onglets Musique · ★ Favoris · Playlists · Dossiers, tri en un appui (ajout, titre, artiste, écoutes, durée), menu ⋮ (lire ensuite, file d'attente, playlist, dossier), file d'attente rangeable, minuterie avec fondu, reprise par fichier pour les morceaux de plus de 10 min, corbeille des playlists, sauvegarde de sécurité automatique avant chaque fusion, écran 📊 Données (sauvegarde, fusion, statistiques en tableaux).*
3. **Vidéo** : plein écran, gestes, fenêtre flottante, « Écouter seulement ». Livrée avec l'économiseur « Ambiance » (13.6). — *Livré le 21/11/2026 (version 1.7) : onglet Vidéos ; balayer ±10 s ou double appui, luminosité à gauche, volume à droite ; ⧉ ou bouton Accueil = fenêtre flottante ; 🎧 Écouter seulement ; ← met en pause ; écran éteint = le son continue ; sous-titres .srt/.vtt du même nom avec traduction 🌐 ; reprise de chaque vidéo (plus d'1 min) ; l'image suit la rotation de l'appareil (pas de paysage forcé, 1.7.2) ; écran noir pendant la vidéo (seule exception au mode clair).*
4. **Mode Chevet** puis **Mode Conduite**.
5. **Fond d'écran animé**.
6. **Musique des autres applis** (option).

Les fonctions de la section 13 (doublons, traduction) s'intercalent après l'étape 2 : doublons d'abord (*livré le 21/11/2026, version 1.3*), traduction ensuite (*livrée le 21/11/2026, version 1.4*), puis le pack de playlist (13.4, *livré le 21/11/2026, version 1.5*). La voix traduite des podcasts (13.3) vient après l'étape 6.

**Tests que Frédéric fera.**

- [ ] Télécharger un titre avec Termux, ouvrir l'appli : il apparaît en tête sans rien faire.
- [ ] 1 h de musique écran éteint en poche : aucune coupure, commandes Bluetooth OK.
- [ ] Ouvrir l'appli : bibliothèque affichée instantanément.
- [ ] Paroles : la ligne en cours suit la chanson.
- [ ] Brancher le chargeur la nuit : le mode Chevet se lance, l'écran ne marque pas.
- [ ] Monter en voiture : le mode Conduite se lance à la connexion Bluetooth.
- [ ] Sauvegarde, réinstallation, restauration : tout est revenu (playlists, écoutes, positions).
- [ ] Doublons : un même titre téléchargé deux fois est proposé à la suppression ; après validation, il disparaît de l'appli et du téléphone, et se restaure depuis la corbeille.
- [ ] Traduction : après le téléchargement du pack, un titre et des paroles en anglais s'affichent en français, mode avion activé.

## 13. Fonctions validées le 21/11/2026

### 13.1 Traduction anglais → français (activable/désactivable) — *livré le 21/11/2026 (version 1.4)*

- **Textes traduits** : titres, noms d'albums et de dossiers, paroles (karaoké) ; sous-titres des vidéos à l'étape Vidéo. Les noms d'artistes restent tels quels (noms propres).
- Bouton 🌐 en haut de l'écran principal : un appui active ou désactive (grisé = désactivé). Choix d'affichage dans 📊 Données : sous le texte anglais (🌐 en gris dans les listes, ligne bleue en italique sous chaque ligne de paroles), ou à la place. Langue reconnue automatiquement (ML Kit Language ID).
- Traduction faite sur le téléphone (traducteur intégré Google ML Kit, pas d'appli à installer), puis gardée dans la base : un texte n'est traduit qu'une fois, l'affichage reste instantané.
- **Pack de langue (choix A, validé le 21/11/2026)** : environ 30 Mo, téléchargé une seule fois avec internet à la première activation de 🌐 (message clair avant, barre de progression). Ensuite, tout fonctionne sans internet. Pas de barre de progression possible (ML Kit ne donne pas l'avancement) : une fenêtre d'attente s'affiche.
- **Taille** : le traducteur contient un gros code natif. APK réduite aux téléphones 64 bits (tous les téléphones récents) : environ 20 Mo au lieu de 1,4 Mo. Objectif de 15 Mo dépassé, à cause de cette fonction.

### 13.2 Détection et suppression des doublons — *livré le 21/11/2026 (version 1.3)*

- Comparaison des fichiers : contenu identique (empreinte), ou même titre et même artiste (sans « Official video », « Lyrics »…) avec moins de 3 s d'écart de durée.
- Liste en tableaux : la meilleure version (débit le plus haut, puis rangée dans Music, puis la plus écoutée) est cochée ✅ ; toucher une ligne change le choix (au moins une version gardée). « Pas un doublon » retire le groupe pour toujours.
- Proposition automatique par le bandeau 🔁 de l'écran principal, et bouton « 🔁 Chercher les doublons » dans 📊 Données.
- À la validation : Android affiche sa fenêtre de confirmation (un seul appui pour tout le lot), le fichier disparaît du lecteur et du téléphone.
- **Sécurité** : corbeille d'Android (30 jours), restaurable depuis l'écran Doublons (une ligne ou tout). Écoutes, favori, reprise et playlists du doublon sont reportés sur la version gardée ; le lien est gardé (table `remplacements`, exportée dans la sauvegarde) pour que les anciennes sauvegardes fusionnent sur la version gardée.
- Les fichiers de paroles restent en place (rien n'est perdu si on restaure).
- Rapport de ce qui a été supprimé et reporté, avec bouton 📋 Copier.

### 13.4 Partager une playlist en pack — *livré le 21/11/2026 (version 1.5)*

- But : donner une playlist (ex. « Céline Dion », « Karaoké ») à quelqu'un (ex. sa mère), sans le reste de la bibliothèque.
- Bouton « 📦 Pack clé USB » dans une playlist : choisir la destination (clé USB branchée au téléphone, ou dossier du téléphone) avec le sélecteur Android. L'appli y crée un dossier au nom de la playlist : les chansons copiées (originaux jamais touchés), leurs paroles (.lrc), et la liste dans l'ordre (`.m3u` + fichier pack du Lecteur). Barre de progression, place nécessaire affichée avant (environ 5 Mo par titre).
- Un dossier simple plutôt qu'un fichier compressé : il se lit aussi sur un autoradio, une télé ou un PC.
- Moyen prévu par Frédéric : **clé USB**. Le menu Partager d'Android (Quick Share, Drive) reste possible.
- « 📥 Importer un pack » (onglet Playlists) : choisir le dossier sur la clé ; les chansons sont copiées dans `Music/<nom de la playlist>/` avec leurs paroles, la playlist est recréée. Chansons déjà présentes (même empreinte) : pas recopiées, simplement ajoutées à la playlist. Descriptif de ce qui a été ajouté avec 📋 Copier. Le dossier Musique doit être autorisé en écriture : l'appli le demande une fois si besoin (le dossier Musique est désormais autorisé en lecture + écriture).
- Re-exporter sur la même clé : les chansons déjà présentes (même nom, même taille) ne sont pas recopiées ; la liste est mise à jour.
- Sans le Lecteur, le destinataire lit quand même les chansons avec n'importe quel lecteur (sans karaoké).

### 13.5 Aa : taille, police et couleurs des paroles — *livré le 21/11/2026 (version 1.6)*

- Bouton **Aa** en haut de l'écran de lecture : panneau en bas, les paroles changent derrière en direct.
- Taille des paroles (A− / A+, de 12 à 44) et de la traduction 🌐 (de 10 à 40), séparément.
- Police au choix parmi celles d'Android (aucun téléchargement) : Normale, Ronde, Fine, Étroite, Épaisse, Livre, Machine, Manuscrite, BD, Majuscules (selon le téléphone, certaines peuvent se ressembler).
- Couleur de la ligne chantée et couleur de la traduction (9 pastilles). « Remettre comme au début ».

### 13.6 Économiseur d'écran musical « Ambiance » — *livré le 21/11/2026 (version 1.7)*

- Animation plein écran qui bouge au rythme de la musique, aux couleurs de la pochette. Styles : ondes, barres, particules, vinyle. Paroles en karaoké par-dessus (au choix).
- Le son est analysé dans le lecteur lui-même : pas de micro, pas d'autorisation, pas d'appli tierce.
- Lancement : bouton ✨ sur l'écran de lecture, ou automatique après 2, 5 ou 10 minutes sans toucher l'écran de lecture (réglable dans l'économiseur, « non » par défaut).
- Fond clair et coloré (couleurs de la pochette éclaircies), écran maintenu allumé, textes déplacés chaque minute (protection OLED). Toucher l'écran : barre de réglages (style, paroles, auto, pause, fermer).
- Analyse : 32 bandes de fréquences (TeeAudioProcessor de Media3), calculées seulement quand l'économiseur est affiché.
- Les mêmes animations serviront au mode Chevet et au fond d'écran animé (étapes 4 et 5).

### 13.7 Suppression simple et dossiers suivis — *livré le 21/11/2026 (version 1.8)*

- **Un titre** : ⋮ → « 🗑 Supprimer du téléphone » (confirmation d'Android directe).
- **Plusieurs** : « ☑ Choisir » dans la barre de tri (Musique, Vidéos, Favoris) et en haut d'une playlist ou d'un dossier ; cocher, « Tout cocher », puis « 🗑 Supprimer du téléphone (N) ». Dans une playlist : « Retirer de la playlist (N) » (le fichier reste).
- Corbeille d'Android 30 jours ; écoutes et favoris gardés dans la base (ils reviennent si on restaure) ; rapport avec 📋 Copier. Écran « ♻️ Corbeille » dans 📊 Données (musiques et vidéos), restauration ligne par ligne ou tout.
- **📁 Dossiers suivis** (📊 Données) : Music, Download, Movies de base + « ➕ Ajouter un dossier » (n'importe quel dossier, ou tout le téléphone), « ✕ Retirer ». Le dossier ajouté est aussi autorisé pour les paroles et sous-titres. Les doublons sont cherchés dans tous les dossiers suivis.

### 13.3 Voix traduite pour les podcasts (plus tard, à confirmer)

- Idée : écouter un podcast anglais en français. L'appli écrit ce qui est dit, le traduit, puis le lit avec une voix française d'Android, la voix d'origine baissée en fond.
- Limites : ce sera une voix de synthèse, pas la voix de la personne. En temps réel, le décalage sera de quelques secondes. Proposition : préparer la traduction à l'avance (téléphone en charge), puis l'écouter parfaitement synchronisée.
- À présenter de nouveau en détail avant de coder.

**Idées à proposer (ne rien coder sans son accord).** Pistes possibles : égaliseur simple, réglage de vitesse de lecture pour les cours, lecture des sous-titres (.srt) des vidéos, widget écran d'accueil, réveil en musique. Ajoute les tiennes, en 2-3 lignes chacune.
