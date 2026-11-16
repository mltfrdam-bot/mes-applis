// Miroir : fonctionne aussi sans internet (réseau d'abord, sinon copie gardée).
const CACHE = "miroir-v2.1";
const FICHIERS = ["./", "index.html", "app.js", "scrcpy-server.jar", "manifest.json", "icone.svg", "icone-192.png", "icone-512.png", "LICENCES.txt"];
self.addEventListener("install", (e) => { e.waitUntil(caches.open(CACHE).then((c) => c.addAll(FICHIERS))); self.skipWaiting(); });
self.addEventListener("activate", (e) => { e.waitUntil(caches.keys().then((k) => Promise.all(k.filter((n) => n !== CACHE).map((n) => caches.delete(n))))); self.clients.claim(); });
self.addEventListener("fetch", (e) => {
  if (e.request.method !== "GET") return;
  e.respondWith(fetch(e.request).then((r) => { const copie = r.clone(); caches.open(CACHE).then((c) => c.put(e.request, copie)); return r; }).catch(() => caches.match(e.request, { ignoreSearch: true })));
});
