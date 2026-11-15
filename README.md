# mes-applis
## Applis Android (APK)
- `android/` : coquille d'appli Android qui embarque une appli web (dossier `assets/www`), sans internet.
- `.github/workflows/apk.yml` : fabrique et signe l'APK, puis le publie dans **Releases**.
- `signature/mes-applis.keystore` : clé de signature, protégée par un mot de passe gardé hors de GitHub (secret `CLE_MOT_DE_PASSE`).

## Relais Appels (téléphone ↔ tablette)
- `relais/android/` : appli du **téléphone**. Elle passe, décroche et raccroche les appels sur ordre de la tablette, par le wifi ou le point d'accès (réseau local, sans internet). Elle sert aussi la page de la tablette (`assets/tablette.html`) : sur la tablette, ouvrir dans Chrome l'adresse affichée par l'appli.
- `.github/workflows/relais.yml` : fabrique et signe `RelaisAppels.apk`, puis le publie dans **Releases** (tag `relais-vN`).
