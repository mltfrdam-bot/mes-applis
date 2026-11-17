#!/bin/sh
# Refabrique dwg/www/index.html à partir de source/app.html (à lancer depuis dwg/source).
set -e
npm install --no-audit --no-fund
npx esbuild lib-entry.js --bundle --format=iife --platform=browser --external:module --minify --outfile=build/lib.js
python3 - <<'PY'
# Lecteur DWG : mémoire de départ réduite de 1 Go à 64 Mo (elle grandit seule si besoin) pour les téléphones
src='node_modules/@mlightcad/libredwg-web/wasm/libredwg-web.wasm'
b=bytearray(open(src,'rb').read())
k=b.find(bytes([0x05,0x08,0x01,0x01,0x80,0x80,0x01,0x80,0x80,0x04])); assert k>0
pages=1024
b[k+4:k+7]=bytes([0x80|(pages&0x7f),0x80|((pages>>7)&0x7f),(pages>>14)&0x7f])
open('build/reader.wasm','wb').write(b)
PY
python3 build.py
cp build/Lecteur_DWG_chantier.html ../www/index.html
echo "OK : dwg/www/index.html mis à jour"
