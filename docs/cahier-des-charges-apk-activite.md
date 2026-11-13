# Cahier des charges — APK Journal d'activité

12/11/2026 · Maillot Frédéric · Version de travail : https://claude.ai/artifact/Y5wA4XkBfFeR9t4tiVtgxH

## 1. Contexte et mission

Ta mission : transformer l'appli web « Journal d'activité » en une vraie APK Android, fiable écran éteint, sans rien perdre de l'existant ni des données.

**L'utilisateur.** Frédéric, 45 ans, La Réunion (UTC+4), chef d'équipe VRD. Il ne lit pas l'anglais : toute l'interface, les messages, l'aide et tes réponses sont en français simple. Téléphone : Samsung Galaxy S23 Ultra (One UI, Android récent). Capteur : ceinture cardio Polar H10. Montre : COROS. Pratique : tennis (match et entraînement), course, vélo, pompes (séances de 100 et journées jusqu'à ~1000), renforcement.

**L'existant.** Appli web dans son « Hub » (PWA dans Chrome), dépôt GitHub `mltfrdam-bot/mes-applis`, fichier `footing/index.html` (~141 Ko, version v31). L'APK la remplace pour le sport ; le Hub continue pour ses autres applis.

**Règles de travail (non négociables).**

- Aucune donnée perdue, jamais. Toute migration passe par sauvegarde + fusion, avec un descriptif de ce qui a été ajouté.
- Gain de temps d'abord : un appui simple plutôt qu'un appui long, le moins d'écrans possible.
- Mode clair uniquement, style classique, données en tableaux façon Excel plutôt qu'en cartes.
- Pas d'appli tierce à installer en plus (pas de service cloud tiers).
- Avant de coder une idée nouvelle : la présenter en 2-3 lignes et attendre son accord. Ce qui est écrit ici est déjà validé.
- Livrer par petites versions testables, chacune avec ce qui a changé en 3-5 lignes.
- Tout rapport destiné à Claude a un bouton « 📋 Copier » (texte collé dans la conversation), et le Drive en option.

## 2. Ce qui existe déjà (à reprendre sans rien perdre)

Tout ce qui suit fonctionne en v31 et doit se retrouver dans l'APK, en mieux. Lis `footing/index.html` en entier avant de coder : c'est la référence du comportement attendu.

| Domaine | Ce qui existe en v31 |
| --- | --- |
| Activités | Course (footing), tennis, pompes, activités perso ; ⚙️ masquer/afficher ; toucher le nom pour changer d'activité |
| Séances | Plein écran, 🔒 verrouillage tactile, écran maintenu allumé (Wake Lock), son silencieux en boucle pour rester vivant, bouton Retour du téléphone qui ferme l'écran ouvert |
| Pompes | Compteur au toucher, bouton +1, pas d'arrêt à l'objectif, alerte « record battu sur une série », note de difficulté après chaque série, repos cible (90 s par défaut) avec signal jaune/vert, bips et « On reprend », bilan pendant le repos (règle de fatigue 30 %) |
| Fantôme pompes | Journée fantôme qui suit l'horloge relative à la 1re pompe ; reprise entre séances ; fantôme choisi dans l'📜 Historique ; graphique série contre série ; journée de référence du 30/08/2025 (889 pompes, 4 séances) |
| Polar H10 | FC dans chaque note ; flux accéléromètre PMD 50 Hz ; test TOI (toucher) contre H10 (mouvement) dans un grand cadre ; détection des pompes avec niveaux de sensibilité 1 à 10 ; rejeu hors ligne des données pour trouver le meilleur niveau |
| Tennis | Enregistrement à la seconde (FC + impacts) ; calibration des impacts avec réglage ± en direct ; journal de chaque essai et rapport |
| 📊 Bilans | Récaps façon COROS comparant ce qui est comparable ; chaque repos affiché et coloré par rapport à 1 min 30 |
| Rapports | 📋 Copier le résumé, 📋 Copier tout, 💾 Fichier |

**Données actuelles (clés localStorage de l'appli dans le Hub).** `footing-courses` (historique principal), `act-courante`, `acts-perso`, `acts-masquees`, `pompes-journee`, `pompes-jour-opt`, `pompes-opt`, `pompes-encours`, `pompes-det-niveau`, `pompes-det-log`, `h10-rec`, `impact-reglage`, `impact-essais`, `fant-opt`, `frac-opt`.

**Détails techniques H10 déjà validés.** Service PMD `FB005C80-02E7-F387-1CAD-8ACD2D8DF0C8` (point de contrôle `…81`, données `…82`). Démarrage ACC 50 Hz, 16 bits, 8 G : `02 02 00 01 32 00 01 01 10 00 02 01 08 00` ; arrêt : `03 02`. Trames de type 0, 1 et delta compressées à gérer.

## 3. Objectifs principaux de l'APK

Quatre choses que l'appli web ne sait pas faire de façon fiable, et qui justifient l'APK.

1. **GPS fiable écran éteint, téléphone en poche.**
   - Service de premier plan (foreground service, type location) avec notification permanente.
   - Points GPS filtrés : sauts et dérives écartés, distance et allure lissées.
   - Allure, distance, durée, FC ; tours manuels et automatiques (au km).
   - Le parcours est écrit au fur et à mesure : appli tuée = rien de perdu, reprise propre.
2. **Polar H10 sans coupure.**
   - Connexion Bluetooth tenue dans un service de premier plan, 1 h 30 de tennis sans trou.
   - Reconnexion automatique si le signal saute, avec la durée de la coupure notée.
   - FC et accéléromètre enregistrés à la seconde, même écran éteint.
   - Un seul vrai problème constaté en v22 : 4 trous de 8 à 12 min au tennis car l'appli était tuée. C'est le test d'acceptation.
3. **Fantôme vocal, téléphone en poche.**
   - Course : courir contre une sortie passée (la sienne ou une sortie COROS importée). La voix annonce l'avance ou le retard (« 50 mètres d'avance », « 12 secondes de retard ») à un rythme réglable.
   - Pompes : le fantôme existant, avec annonces vocales (série, repos, « on reprend »).
   - Voix française d'Android (synthèse vocale), qui passe aussi dans les écouteurs Bluetooth.
4. **Commandes sur l'écran verrouillé.** Notification de séance avec Pause, Tour, Fin, et pour les pompes +1 et Série finie. Temps et FC visibles sans déverrouiller.

**À tenir sur Samsung.** One UI tue les applis en arrière-plan. L'appli doit guider une seule fois l'utilisateur pour la sortir de l'optimisation de batterie (écran explicatif + bouton vers le bon réglage), et vérifier au début de chaque séance que c'est toujours bon.

## 4. Activités personnalisables et ceinture H10

Frédéric doit pouvoir créer lui-même une activité en moins d'une minute, sans mise à jour de l'appli.

**Créer une activité = remplir une fiche courte :** nom, icône, type de mesure, et les options ci-dessous. Exemples visés : corde à sauter, vélo d'appartement, gainage, squats, élastiques, rameur.

| Type de mesure | Ce que l'activité utilise | Exemples |
| --- | --- | --- |
| Durée + cardio | Chrono, FC, zones, temps dans chaque zone, récupération (FC qui redescend en 1 min après l'effort) | Gainage, vélo d'appartement |
| Répétitions | Compteur (section 5), séries, repos, FC par série | Pompes, squats, élastiques |
| Enregistrement à la seconde | FC + mouvement + événements (impacts) | Tennis, entraînement libre |
| Distance GPS | GPS + FC + fantôme | Course, marche, vélo |

**Options communes à chaque activité :** repos cible et signal de reprise, objectif (nombre, durée ou distance), fantôme (choisi dans l'historique de la même activité), annonces vocales oui/non, note de difficulté après chaque série oui/non.

**Cardio partout.** Dès que la H10 est connectée, toute activité affiche la FC en grand, la garde dans l'historique, et l'affiche dans les 📊 Bilans. Zones de FC réglables une fois pour toutes (par défaut : calculées à partir de la FC max qu'il saisit).

## 5. Comptage des répétitions et calibration

Chaque exercice à répétitions propose trois modes, au choix, mémorisés par exercice.

| Mode | Fonctionnement | Usage |
| --- | --- | --- |
| Au toucher | Il tape l'écran (grande zone, + bouton +1) | Comme aujourd'hui, la référence |
| Au mouvement | La H10 compte seule | Mains libres, plus tard |
| Les deux | Toucher et H10 en parallèle, grand cadre TOI / H10, écart par série | Apprendre et régler (priorité actuelle) |

Pour l'instant, il ne veut pas passer au comptage sans toucher : le mode « Les deux » sert à comprendre la H10 pendant ses vraies séances de 100 pompes. Le comptage officiel reste celui au toucher tant qu'il ne choisit pas l'inverse.

**Axe du mouvement, choisi par exercice.**

- Haut-bas : pompes, squats, sauts, corde.
- Avant-arrière : rameur, élastique tiré vers soi, balancements.
- Côté-côté : déplacements latéraux, rotations.
- Automatique : l'appli trouve l'axe qui bouge le plus pendant la calibration et le propose.

La ceinture est portée sur la poitrine, à l'envers parfois : la calibration doit rendre le comptage indépendant du sens de port.

**Calibration guidée, par exercice.**

1. « Fais 10 répétitions » : il les fait en tapant l'écran à chaque répétition.
2. L'appli compare ses touchers au signal de la H10 et choisit axe, seuil, filtre et écart minimal entre deux répétitions.
3. Résultat affiché en clair (« 10/10 trouvées, niveau 6 »), avec un réglage ± de sensibilité en direct.
4. Chaque essai est gardé dans un journal ; un rapport 📋 Copier résume tous les essais.

Reprendre la logique v31 (niveaux 1 à 10, rejeu hors ligne pour trouver le meilleur niveau, proposition « Appliquer le niveau X » en fin de séance). Biais connu à corriger : +1 répétition par série environ, et des séries d'échauffement mal comptées (6 au lieu de 12).

## 6. Design, ergonomie et aide

La v31 a beaucoup grandi par ajouts successifs : l'APK doit tout réorganiser pour que l'essentiel soit à un appui, et le reste rangé.

**Organisation proposée (à valider avec lui sur maquette avant de coder).**

- Accueil = les activités en grosses tuiles + un bouton Démarrer. Rien d'autre au premier coup d'œil.
- 4 onglets en bas : Activités · Historique · 📊 Bilans · ❓ Aide. Les réglages derrière ⚙️.
- Écran de séance : un gros chiffre principal (répétitions, allure ou temps), la FC, le fantôme ; options avancées repliées.
- Historique et Bilans en tableaux façon Excel, triables.
- Tout lisible en plein soleil et en sueur : grosses zones de toucher, contrastes forts, mode clair uniquement.

**Onglet ❓ Aide, intégré et hors ligne.**

- Une fiche par écran et par fonction : démarrer une séance, le fantôme, la H10, la calibration, les Bilans, la sauvegarde.
- Phrases courtes, exemples concrets, pas de jargon ni d'anglais.
- Un petit ❓ sur chaque écran ouvre directement la bonne fiche.
- Une page « Première utilisation » : connecter la H10, autoriser le GPS, sortir l'appli de l'optimisation de batterie Samsung, importer ses données.

## 7. Données : transfert, sauvegarde, COROS, rapports

Première règle : l'historique existant (dont la journée du 30/08/2025 et toutes les séances H10) arrive entier dans l'APK, vérifié chiffres à l'appui.

**Transfert depuis le Hub.**

- Le Hub exporte une sauvegarde JSON au format `mon-hub-sauvegarde` (version 1) : `donnees[<id appli>].localStorage` contient les clés listées en section 2. Voir `fabriquerSauvegarde()` dans `hub/index.html`.
- L'APK importe ce fichier (ou seulement la partie Journal), puis affiche le descriptif : nombre de séances par activité, dates de la plus ancienne et de la plus récente, totaux de pompes, nombre d'enregistrements H10.
- Import rejouable sans doublon (fusion par identifiant), pour pouvoir réimporter si besoin.

**Sauvegarde et fusion dans l'APK.**

- Sauvegarde en un appui vers un fichier (dossier Téléchargements) et, en option, vers Google Drive par le menu Partager d'Android (pas d'appli tierce).
- Fusion de deux sauvegardes : rien n'est jamais écrasé, le descriptif liste ce qui a été ajouté. Même comportement que dans ses autres applis.
- Sauvegarde automatique locale quotidienne, les 7 dernières gardées.

**Données COROS.** COROS n'offre pas d'accès direct pour une appli personnelle. Prévoir l'import de fichiers d'activité (.fit, et .gpx/.tcx si simple) exportés depuis l'appli COROS, puis partagés vers l'APK (« Ouvrir avec » / Partager). Une sortie COROS importée peut servir de fantôme de course, et ses tours de fantôme de pompes.

**Rapports copiables.** Chaque rapport (séance, Bilans, calibration, tests H10) a trois boutons : 📋 Copier le résumé (court, pour coller à Claude), 📋 Copier tout, 💾 Fichier. Texte brut lisible, en français, avec dates et heures de La Réunion.

**Pas de doublon.** En v31, une même séance de pompes est parfois enregistrée deux fois à quelques secondes d'écart (ex. 13/11/2026 05:06:04 et 05:06:10, mêmes chiffres). L'APK enregistre chaque séance une seule fois (identifiant unique), et l'import nettoie les doublons existants en les listant dans le descriptif. Une séance courte reste une vraie séance (ex. 12 pompes faites réellement) : ne jamais la supprimer automatiquement.

## 8. Hors ligne, technique et atelier APK

L'appli fonctionne à 100 % sans internet pour tout l'usage quotidien.

| Geste | Sans internet |
| --- | --- |
| Séances, GPS, H10, fantôme, comptage, voix, Bilans, aide | Tout fonctionne |
| Fond de carte de course | Optionnel ; le tracé et les chiffres marchent sans carte |
| Sauvegarde vers Drive | Part dès que le réseau revient |
| Fichier COROS | Récupéré avec réseau, importé hors ligne |

**Atelier APK existant (à réutiliser).**

- Projet Android dans `android/` : coquille qui embarque une appli web (`assets/www`), pont JavaScript `AndroidApp` (voix, vibration, fichiers) dans `MainActivity.java`.
- `.github/workflows/apk.yml` fabrique et signe l'APK, puis la publie dans les Releases GitHub. Clé : `signature/mes-applis.keystore`, mot de passe dans le secret `CLE_MOT_DE_PASSE`. Exemple livré : Compteur de squats.
- Ajouter une cible « Journal d'activité » (appId propre, ex. `fr.mesapplis.activite`) sans casser la fabrication des squats.

**Choix technique libre, avec deux contraintes.** Tu peux garder la coquille WebView + services natifs (GPS, H10, voix) reliés par le pont, ou passer en natif (Kotlin). Choisis ce qui rend le GPS et la H10 les plus fiables écran éteint ; explique ton choix en 5 lignes avant de te lancer. Permissions minimales : localisation (dont arrière-plan pendant une séance), Bluetooth, notifications, service de premier plan, vibration ; pas d'accès internet en dehors du partage de fichiers.

## 9. Étapes, tests et critères de réussite

Livrer dans cet ordre, une APK testable à chaque étape. Fable 5.2 est inclus jusqu'au 16/11/2026 : les étapes 1 à 3 sont la priorité.

1. **Socle** : l'APK s'installe, importe la sauvegarde du Hub, affiche l'historique complet avec le descriptif de l'import ; aide « Première utilisation » (batterie Samsung).
2. **H10 sans coupure** : connexion, reconnexion, FC et accéléromètre à la seconde écran éteint ; enregistrement tennis.
3. **Pompes** : tout l'existant v31 (toucher, +1, repos, fantôme, test TOI/H10, niveaux), plus la notification écran verrouillé et la voix.
4. **GPS et fantôme de course** vocal.
5. **Activités personnalisables**, calibration par axe, 📊 Bilans, ❓ Aide complète, import COROS.

**Tests que Frédéric fera sur le terrain.**

- [ ] Tennis 1 h 30, téléphone dans le sac, écran éteint : 0 trou dans l'enregistrement H10.
- [ ] Footing 45 min téléphone en poche : tracé complet, distance proche de la COROS (écart < 3 %).
- [ ] Séance de 100 pompes en mode « Les deux » : écart TOI/H10 de 1 pompe ou moins par série après calibration.
- [ ] Import de sa sauvegarde : mêmes totaux qu'avant (dont 889 pompes le 30/08/2025).
- [ ] Appli tuée en pleine séance : relance et reprise sans perte.

**Idées à proposer (ne rien coder sans son accord).** Exemples de pistes : résumé vocal en fin de séance ; alerte si la FC reste trop haute au repos ; détection automatique des séries ; carte des zones de FC par séance ; widget « séance du jour ». Ajoute les tiennes, en 2-3 lignes chacune, avant l'étape 5.

**Pour démarrer.** Clone `mltfrdam-bot/mes-applis`, lis `footing/index.html`, `hub/index.html` (sauvegarde) et `android/`, puis réponds avec : ton choix technique (5 lignes), ton plan des étapes 1 à 3, et les questions qui te bloquent.
