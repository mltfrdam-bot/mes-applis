// Mode hors réseau du compteur de squats.
// Ouverture : on affiche TOUT DE SUITE la version enregistrée sur la tablette (aucune attente du réseau).
// En même temps, s'il y a internet, on va chercher la dernière version sur GitHub : elle sera utilisée à l'ouverture suivante.
// Si internet est coupé, lent ou « Wi-Fi sans internet », l'appli s'ouvre quand même, sans délai.
// Les données (séances) restent dans la tablette et ne sont jamais touchées ici.
const CACHE = 'squats-v2';
const FICHIERS = ['./', './index.html', './manifest.json', './icone-192.png', './icone-512.png'];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(FICHIERS)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', e => {
  e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== CACHE).map(k => caches.delete(k))))
    .then(() => self.clients.claim()));
});

// Va chercher la version en ligne, avec une limite de 8 secondes pour ne jamais rester bloqué.
function enLigne(req) {
  const ctrl = new AbortController();
  const t = setTimeout(() => ctrl.abort(), 8000);
  return fetch(req, { cache: 'no-store', signal: ctrl.signal }).then(rep => {
    clearTimeout(t);
    if (rep && rep.ok) { const copie = rep.clone(); caches.open(CACHE).then(c => c.put(req, copie)); }
    return rep;
  }).catch(err => { clearTimeout(t); throw err; });
}

self.addEventListener('fetch', e => {
  if (e.request.method !== 'GET') return;
  e.respondWith(
    caches.match(e.request, { ignoreSearch: true }).then(copie => {
      const maj = enLigne(e.request);
      if (copie) { e.waitUntil(maj.catch(() => {})); return copie; }   // version de la tablette, tout de suite
      return maj.catch(() => caches.match('./index.html'));              // première fois : il faut le réseau
    })
  );
});
