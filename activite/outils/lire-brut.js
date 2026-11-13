/* Lit un fichier de mesures brutes de la ceinture (brut-<début>.bin, écrit par Brut.java).
   En ligne de commande : node activite/outils/lire-brut.js brut-123.bin [sortie.csv]
   En module : require("./lire-brut.js").lire(octets) → { debut, hz, blocs: [{ ms, mesures: [[x, y, z], …] }], mesures, abimes }
   Un bloc abîmé (appli tuée en pleine écriture) est sauté : on reprend au bloc suivant. */
"use strict";
function lire(buf) { if (buf.length < 16 || buf.toString("latin1", 0, 4) !== "JAB1") throw new Error("Ce n'est pas un fichier de mesures brutes.");
  const r = { debut: Number(buf.readBigInt64LE(4)), hz: buf.readUInt16LE(12), blocs: [], mesures: 0, abimes: 0 }; let p = 16, avant = -1;
  while (p + 8 <= buf.length) { const n = buf[p] === 0xAB && buf[p + 1] === 0xCD ? buf.readUInt16LE(p + 6) : -1, ms = n >= 0 ? buf.readUInt32LE(p + 2) : 0;
    if (n < 1 || n > 2000 || p + 8 + n * 6 > buf.length || ms < avant) { if (p + 8 + Math.max(0, n) * 6 > buf.length && n >= 1 && n <= 2000) { r.abimes++; break; } p++; continue; }   // pas un bloc valable ici : on cherche le prochain repère AB CD
    const m = []; for (let i = 0; i < n; i++) m.push([buf.readInt16LE(p + 8 + i * 6), buf.readInt16LE(p + 10 + i * 6), buf.readInt16LE(p + 12 + i * 6)]);
    r.blocs.push({ ms, mesures: m }); r.mesures += n; avant = ms; p += 8 + n * 6; }
  return r; }
/* chaque mesure avec son heure : la dernière mesure d'un bloc est à l'heure du bloc, les autres 20 ms plus tôt chacune (comme dans l'appli) */
function aPlat(r) { const l = []; r.blocs.forEach(b => { const n = b.mesures.length; b.mesures.forEach((a, i) => l.push([b.ms - (n - 1 - i) * (1000 / r.hz), a[0], a[1], a[2], Math.round(Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]))])); }); return l; }
module.exports = { lire, aPlat };
if (require.main === module) { const fs = require("fs"); const r = lire(fs.readFileSync(process.argv[2])), l = aPlat(r), duree = l.length ? (l[l.length - 1][0] - l[0][0]) / 1000 : 0;
  console.log("Début : " + new Date(r.debut).toLocaleString("fr-FR", { timeZone: "Indian/Reunion" }) + " (La Réunion)"); console.log(r.blocs.length + " blocs, " + r.mesures + " mesures sur " + Math.round(duree) + " s (" + (duree ? (r.mesures / duree).toFixed(1) : "–") + " par seconde)" + (r.abimes ? ", " + r.abimes + " bloc abîmé sauté" : ""));
  if (l.length) console.log("Force max : " + (Math.max(...l.map(x => x[4])) / 1000).toFixed(2) + " g");
  if (process.argv[3]) { fs.writeFileSync(process.argv[3], "ms;x;y;z;force\n" + l.map(x => x.join(";")).join("\n") + "\n"); console.log("Écrit : " + process.argv[3]); } }
