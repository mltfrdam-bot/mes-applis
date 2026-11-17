# Plans DWG (Lecteur DWG chantier)

Appli Android autonome pour ouvrir des plans DWG/DXF sur le téléphone, mesurer (distance, surface, accrochage), et plus tard tracer, créer des calques et exporter des points pour la TS11.

- `www/index.html` : la page complète (un seul fichier, marche sans internet). C'est elle qui va dans l'APK.
- `source/` : le code source (`app.html`) et l'outil pour refabriquer la page (`construire.sh`).
- `android/` : la coquille Android (WebView), avec « Ouvrir avec… » pour les DWG/DXF et « ⋮ → Mettre à jour l'appli ».
- Fabrication et signature : `.github/workflows/dwg.yml` → Releases, tag `plans-dwg` (toujours la dernière version).

Lien direct : https://github.com/mltfrdam-bot/mes-applis/releases/download/plans-dwg/PlansDWG.apk
