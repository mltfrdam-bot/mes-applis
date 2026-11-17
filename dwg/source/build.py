import base64,gzip,sys
lib=open('build/lib.js').read()
assert '</script' not in lib.lower()
wasm=gzip.compress(open('build/reader.wasm','rb').read(),9)
html=open('app.html',encoding='utf-8').read()
html=html.replace('/*__LIBJS__*/',lib,1).replace('"__WASM__"','"'+base64.b64encode(wasm).decode()+'"',1)
out='build/Lecteur_DWG_chantier.html'
open(out,'w',encoding='utf-8').write(html)
print(out, round(len(html.encode())/1e6,2),'Mo')
