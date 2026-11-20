# mes-applis
## Applis Android (APK)
- `android/` : coquille d'appli Android qui embarque une appli web (dossier `assets/www`), sans internet.
- `.github/workflows/apk.yml` : fabrique et signe l'APK, puis le publie dans **Releases**.
- `signature/mes-applis.keystore` : clé de signature, protégée par un mot de passe gardé hors de GitHub (secret `CLE_MOT_DE_PASSE`).

## Relais Appels (téléphone ↔ tablette)
- `relais/android/` : appli du **téléphone**. Elle passe, décroche et raccroche les appels sur ordre de la tablette, par le wifi ou le point d'accès (réseau local, sans internet). Elle sert aussi la page de la tablette (`assets/tablette.html`) : sur la tablette, ouvrir dans Chrome l'adresse affichée par l'appli.
- `.github/workflows/relais.yml` : fabrique et signe `RelaisAppels.apk`, puis le publie dans **Releases** (tag `relais-vN`).

## Miroir (version 1, page web)
- `miroir/` : afficher et piloter le téléphone depuis la tablette par câble USB, dans Chrome. Adresse : https://mltfrdam-bot.github.io/mes-applis/miroir/
- `miroir-src/` : le code source (refabriquer avec `npm run build`).

## Miroir version 2 (sans fil)
- `miroir-android/` : appli Android **Miroir** pour la tablette (page Miroir + relais Wi-Fi vers le téléphone).
- `.github/workflows/miroir.yml` : fabrique et signe `Miroir.apk`, publié dans **Releases** (tag `miroir`, toujours la dernière version).
- Lien direct : https://github.com/mltfrdam-bot/mes-applis/releases/download/miroir/Miroir.apk

## Plans DWG (Lecteur DWG chantier)
- `dwg/` : appli Android **Plans DWG** pour ouvrir les plans DWG/DXF sur le téléphone et mesurer (voir `dwg/LISEZMOI.md`).
- `.github/workflows/dwg.yml` : fabrique et signe `PlansDWG.apk`, publié dans **Releases** (tag `plans-dwg`, toujours la dernière version).
- Lien direct : https://github.com/mltfrdam-bot/mes-applis/releases/download/plans-dwg/PlansDWG.apk

## Lecteur (musique et vidéo)
- `lecteur/android/` : appli Android native **Lecteur** (Kotlin, Media3) qui lit la musique téléchargée avec Termux, paroles en karaoké. Cahier des charges : `docs/cahier-des-charges-lecteur.md`.
- `.github/workflows/lecteur.yml` : fabrique et signe `Lecteur.apk`, publié dans **Releases** (tag `lecteur`, toujours la dernière version).
- Lien direct : https://github.com/mltfrdam-bot/mes-applis/releases/download/lecteur/Lecteur.apk
