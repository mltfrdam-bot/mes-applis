# mes-applis
## Applis Android (APK)
- `android/` : coquille d'appli Android qui embarque une appli web (dossier `assets/www`), sans internet.
- `.github/workflows/apk.yml` : fabrique et signe l'APK, puis le publie dans **Releases**.
- `signature/mes-applis.keystore` : clé de signature, protégée par un mot de passe gardé hors de GitHub (secret `CLE_MOT_DE_PASSE`).
