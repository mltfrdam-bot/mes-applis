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
- L'appli fonctionne à 100 % sans internet.

## 11. Technique et atelier APK

**Atelier.** Projet natif dans `lecteur/android/` (Kotlin, Media3, SQLite, écrans Android classiques sans WebView). Le fichier `.github/workflows/lecteur.yml` fabrique, signe (clé `signature/mes-applis.keystore`, secret `CLE_MOT_DE_PASSE`) et publie `Lecteur.apk` dans les Releases, tag `lecteur` (toujours la dernière version). appId `fr.mesapplis.lecteur`. Les autres APK ne sont pas touchées.

Lien direct : https://github.com/mltfrdam-bot/mes-applis/releases/download/lecteur/Lecteur.apk

**Permissions minimales** : accès musique et vidéo, notifications, service de premier plan (lecture média), Bluetooth (détection de connexion pour le mode Conduite), optionnellement l'accès aux notifications (section 7). Pas d'accès internet (retiré explicitement du manifeste).

## 12. Étapes, tests et critères de réussite

Livrer dans cet ordre, une APK testable à chaque étape.

1. **Socle** : l'APK s'installe, trouve les fichiers Termux, lit la musique en arrière-plan avec la notification et l'écran verrouillé. Paroles en karaoké. Aide « Première utilisation ». — *Livré le 20/11/2026 (version 1.1).*
2. **Bibliothèque complète** : recherche, playlists, favoris, reprise, sauvegarde + fusion. — *Livré le 20/11/2026 (version 1.2) : onglets Musique · ★ Favoris · Playlists · Dossiers, tri en un appui (ajout, titre, artiste, écoutes, durée), menu ⋮ (lire ensuite, file d'attente, playlist, dossier), file d'attente rangeable, minuterie avec fondu, reprise par fichier pour les morceaux de plus de 10 min, corbeille des playlists, sauvegarde de sécurité automatique avant chaque fusion, écran 📊 Données (sauvegarde, fusion, statistiques en tableaux).*
3. **Vidéo** : plein écran, gestes, fenêtre flottante, « Écouter seulement ».
4. **Mode Chevet** puis **Mode Conduite**.
5. **Fond d'écran animé**.
6. **Musique des autres applis** (option).

**Tests que Frédéric fera.**

- [ ] Télécharger un titre avec Termux, ouvrir l'appli : il apparaît en tête sans rien faire.
- [ ] 1 h de musique écran éteint en poche : aucune coupure, commandes Bluetooth OK.
- [ ] Ouvrir l'appli : bibliothèque affichée instantanément.
- [ ] Paroles : la ligne en cours suit la chanson.
- [ ] Brancher le chargeur la nuit : le mode Chevet se lance, l'écran ne marque pas.
- [ ] Monter en voiture : le mode Conduite se lance à la connexion Bluetooth.
- [ ] Sauvegarde, réinstallation, restauration : tout est revenu (playlists, écoutes, positions).

**Idées à proposer (ne rien coder sans son accord).** Pistes possibles : égaliseur simple, réglage de vitesse de lecture pour les cours, lecture des sous-titres (.srt) des vidéos, widget écran d'accueil, réveil en musique. Ajoute les tiennes, en 2-3 lignes chacune.
