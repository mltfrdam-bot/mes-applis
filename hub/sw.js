// Mode hors réseau de Mon Hub.
// - Le hub s'ouvre TOUT DE SUITE avec la version enregistrée dans le téléphone, même sans internet.
// - S'il y a du réseau, la dernière version est récupérée en arrière-plan pour l'ouverture suivante.
// - catalogue.json, version.json et modules/ : on essaie d'abord internet (pour voir les nouveautés), sinon la copie gardée.
// - Les applis rangées et leurs données sont dans la mémoire du téléphone : ce fichier n'y touche jamais.
const CACHE = 'mon-hub-v10';
const FICHIERS = ['./', './index.html', './manifest.json', './icone-192.png', './icone-512.png', './serveur-google/MonHub-serveur.gs'];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(FICHIERS)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', e => {
  e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k.startsWith('mon-hub-') && k !== CACHE).map(k => caches.delete(k))))
    .then(() => self.clients.claim()));
});

function enLigne(req, delai) {
  const ctrl = new AbortController();
  const t = setTimeout(() => ctrl.abort(), delai || 8000);
  return fetch(req, { cache: 'no-store', signal: ctrl.signal }).then(rep => {
    clearTimeout(t);
    if (rep && rep.ok) { const copie = rep.clone(); caches.open(CACHE).then(c => c.put(req, copie)); }
    return rep;
  }).catch(err => { clearTimeout(t); throw err; });
}

self.addEventListener('fetch', e => {
  const req = e.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== self.location.origin) return;
  const dynamique = /catalogue\.json$|version\.json$|\/modules\//.test(url.pathname);
  if (dynamique) {
    // Internet d'abord, copie gardée si pas de réseau.
    e.respondWith(enLigne(req, 7000).catch(() => caches.match(req, { ignoreSearch: true }).then(c => c || new Response('', { status: 504 }))));
    return;
  }
  e.respondWith(
    caches.match(req, { ignoreSearch: true }).then(copie => {
      const maj = enLigne(req);
      if (copie) { e.waitUntil(maj.catch(() => {})); return copie; }
      return maj.catch(() => caches.match('./index.html'));
    })
  );
});

// Bouton « Mettre à jour maintenant » : on recharge tout le hub depuis internet, puis on prévient la page.
self.addEventListener('message', e => {
  if (!e.data || e.data.type !== 'rafraichir') return;
  e.waitUntil(caches.open(CACHE).then(c => Promise.all(FICHIERS.map(f =>
    fetch(f, { cache: 'no-store' }).then(r => r.ok ? c.put(f, r) : null).catch(() => null))))
    .then(() => { if (e.ports && e.ports[0]) e.ports[0].postMessage('ok'); }));
});
