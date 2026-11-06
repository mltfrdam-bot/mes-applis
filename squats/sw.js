// Mode hors réseau du compteur de squats.
// Avec réseau : on prend toujours la dernière version sur GitHub (mise à jour automatique).
// Sans réseau : on ouvre la dernière version enregistrée sur le téléphone.
// Les données (séances) restent dans le téléphone et ne sont jamais touchées ici.
const CACHE = 'squats-v1';
const FICHIERS = ['./', './index.html', './manifest.json', './icone-192.png', './icone-512.png'];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(FICHIERS)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', e => {
  e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== CACHE).map(k => caches.delete(k))))
    .then(() => self.clients.claim()));
});
self.addEventListener('fetch', e => {
  if (e.request.method !== 'GET') return;
  e.respondWith(
    fetch(e.request, {cache: 'no-store'}).then(rep => {
      const copie = rep.clone();
      caches.open(CACHE).then(c => c.put(e.request, copie));
      return rep;
    }).catch(() => caches.match(e.request, {ignoreSearch: true}).then(r => r || caches.match('./index.html')))
  );
});
