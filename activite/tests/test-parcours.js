/* Teste le calcul du parcours GPS de la partie Android (Parcours.java), hors du téléphone.
   node activite/tests/test-parcours.js
   On fabrique de faux parcours dont on connaît la vraie distance (ligne droite, piste de 400 m, arrêt, saut du GPS,
   tunnel, appli tuée), avec les erreurs habituelles d'un GPS de téléphone, et on vérifie la distance calculée. */
"use strict";
const assert = require("assert"), fs = require("fs"), os = require("os"), path = require("path"), { execFileSync, spawnSync } = require("child_process");
const racine = path.join(__dirname, "..", ".."), src = path.join(racine, "android-activite/app/src/main/java/fr/mesapplis/activite"), tmp = fs.mkdtempSync(path.join(os.tmpdir(), "parcours-"));
const env = Object.assign({}, process.env); delete env.JAVA_TOOL_OPTIONS;
execFileSync("javac", ["-encoding", "UTF-8", "-d", path.join(tmp, "classes"), ...["Parcours", "Json"].map(x => path.join(src, x + ".java")), path.join(racine, "android-activite/tests/EssaiParcours.java")], { env, stdio: "inherit" });
const TRACE = path.join(tmp, "gps.txt");
function java(lignes, garder) { const sc = path.join(tmp, "scenario.txt"), sortie = path.join(tmp, "sortie.json"); fs.writeFileSync(sc, lignes.join("\n") + "\n"); try { fs.unlinkSync(sortie); } catch (e) {} if (!garder) try { fs.unlinkSync(TRACE); } catch (e) {}
  const r = spawnSync("java", ["-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-cp", path.join(tmp, "classes"), "fr.mesapplis.activite.EssaiParcours", sc, TRACE, sortie], { env, encoding: "utf8" });
  assert.strictEqual((r.stdout || "").trim(), "OK", r.stderr); return JSON.parse(fs.readFileSync(sortie, "utf8")); }
let n = 0; function test(nom, fn) { fn(); n++; console.log("✅ " + nom); }

/* ----- hasard reproductible ----- */
let graine = 2026; const alea = () => { graine = (graine * 1103515245 + 12345) & 0x7fffffff; return graine / 0x7fffffff; };
const gauss = () => { let s = 0; for (let i = 0; i < 6; i++) s += alea(); return (s - 3) * Math.SQRT2; };

/* ----- faux GPS -----
   chemin(t) rend la vraie position en mètres (x vers l'est, y vers le nord) ; le GPS y ajoute une erreur qui dérive
   lentement (comme en vrai : 3 m environ) plus un petit bruit, et donne la vitesse à 0,15 m/s près. */
const LAT0 = -20.98, LON0 = 55.70, M_LAT = 110574, M_LON = 111320 * Math.cos(LAT0 * Math.PI / 180);
function gps(chemin, duree, opt) { opt = opt || {}; const sigma = opt.sigma == null ? 3 : opt.sigma, L = []; let ex = 0, ey = 0;
  for (let s = 0; s <= duree; s++) { ex = 0.95 * ex + gauss() * sigma * 0.31; ey = 0.95 * ey + gauss() * sigma * 0.31; if (opt.trou && s > opt.trou[0] && s < opt.trou[1]) continue;
    const p = chemin(s), q = chemin(s + 1), v = Math.hypot(q.x - p.x, q.y - p.y); let x = p.x + ex + gauss() * 0.7, y = p.y + ey + gauss() * 0.7, prec = 3 + alea() * 4;
    if (opt.saut && opt.saut.includes(s)) { x += 400; y -= 250; } if (opt.mauvais && opt.mauvais.includes(s)) { x += 150; prec = 80; }
    L.push("P " + (s * 1000 + Math.round(alea() * 200)) + " " + (LAT0 + y / M_LAT).toFixed(6) + " " + (LON0 + x / M_LON).toFixed(6) + " " + prec.toFixed(1) + " 12.0 " + (opt.sansVitesse ? "-1.00" : Math.max(0, v + gauss() * 0.15).toFixed(2)) + " 14"); }
  return L; }
const droite = (v) => (t) => ({ x: v * t * 0.8, y: v * t * 0.6 });
/* piste de 400 m : deux lignes droites de 84,39 m et deux virages de 36,5 m de rayon */
function piste(v) { const D = 84.39, R = 36.5, V = Math.PI * R; return (t) => { let d = (v * t) % 400;
  if (d < D) return { x: d, y: 0 }; d -= D; if (d < V) { const a = d / R - Math.PI / 2; return { x: D + R * Math.cos(a), y: R + R * Math.sin(a) }; } d -= V;
  if (d < D) return { x: D - d, y: 2 * R }; d -= D; const a = Math.PI / 2 + d / R; return { x: R * Math.cos(a), y: R + R * Math.sin(a) }; }; }
const ecart = (mesure, vrai) => Math.abs(mesure - vrai) / vrai * 100;
const pc = (x) => x.toFixed(1).replace(".", ",") + " %";

test("ligne droite de 3,03 km à 3 m/s : distance à moins de 2 %, 3 kilomètres annoncés", () => {
  const r = java(["N 1000 12 sat", ...gps(droite(3), 1010), "F 1011000"]), g = r.resume;
  assert.ok(ecart(g.dist, 3030) < 2, "distance " + g.dist); assert.ok(ecart(g.distP, 3030) < 2, "distance par les positions " + g.distP);
  assert.strictEqual(r.km, 3); assert.strictEqual(g.km.length, 3); g.km.forEach((s, i) => assert.ok(Math.abs(s - (i + 1) * 333.3) < 8, "km " + (i + 1) + " à " + s + " s"));
  assert.strictEqual(r.premiers, 1); assert.strictEqual(g.premier, 0); assert.strictEqual(g.points, 1011); assert.strictEqual(g.rejetes, 0); assert.strictEqual(g.duree, 1011);
  assert.strictEqual(g.pas, 5); assert.strictEqual(g.prof.length, 203); assert.strictEqual(g.trace.length, 406); assert.ok(Math.abs(g.prof[100] - 1500) < 30, "profil à 500 s : " + g.prof[100]);
  assert.ok(g.prof.every((d, i) => i === 0 || d >= g.prof[i - 1]), "la distance ne recule jamais"); assert.ok(Math.abs(g.trace[2] / 1e5 - LAT0) < 0.001 && Math.abs(g.trace[3] / 1e5 - LON0) < 0.001);
  console.log("   distance " + g.dist + " m (écart " + pc(ecart(g.dist, 3030)) + "), par les positions seules " + g.distP + " m"); });

test("5 tours de piste (2 km) à 3,5 m/s : distance à moins de 3 % malgré les virages", () => {
  const r = java(["N 1000 12 sat", ...gps(piste(3.5), Math.round(2000 / 3.5)), "F " + (Math.round(2000 / 3.5) * 1000 + 1000)]), g = r.resume;
  assert.ok(ecart(g.dist, 2000) < 3, "distance " + g.dist); assert.strictEqual(g.km.length, g.dist >= 2000 ? 2 : 1);
  console.log("   distance " + g.dist + " m (écart " + pc(ecart(g.dist, 2000)) + "), par les positions seules " + g.distP + " m"); });

test("marche lente (1,2 m/s) et GPS moins précis : distance à moins de 4 %", () => {
  const g = java(["N 1000 12 sat", ...gps(piste(1.2), 1000, { sigma: 5 }), "F 1001000"]).resume;
  assert.ok(ecart(g.dist, 1200) < 4, "distance " + g.dist); console.log("   distance " + g.dist + " m (écart " + pc(ecart(g.dist, 1200)) + ")"); });

test("5 minutes à l'arrêt (feu rouge, lacets) : presque rien n'est compté", () => {
  const stop = (t) => t < 100 ? { x: 3 * t, y: 0 } : t < 400 ? { x: 300, y: 0 } : { x: 300 + 3 * (t - 400), y: 0 };
  const g = java(["N 1000 12 sat", ...gps(stop, 500), "F 501000"]).resume;
  assert.ok(Math.abs(g.dist - 600) < 15, "distance " + g.dist + " pour 600 m vrais"); console.log("   distance " + g.dist + " m pour 600 m vrais"); });

test("sauts du GPS et points très imprécis : écartés, la distance ne bouge pas", () => {
  const g = java(["N 1000 12 sat", ...gps(droite(3), 600, { saut: [200, 201, 350], mauvais: [100, 101, 102, 450] }), "F 601000"]).resume;
  assert.ok(ecart(g.dist, 1800) < 2, "distance " + g.dist); assert.strictEqual(g.rejetes, 7); assert.strictEqual(g.sauts, 0); assert.strictEqual(g.points, 601); });

test("tunnel de 60 s sans GPS : la partie manquante est comptée en ligne droite", () => {
  const g = java(["N 1000 12 sat", ...gps(droite(3), 600, { trou: [200, 260] }), "F 601000"]).resume;
  assert.ok(ecart(g.dist, 1800) < 2, "distance " + g.dist); });

test("GPS qui se trompe d'endroit au départ puis se corrige : on repart de la bonne position sans compter le saut", () => {
  const L = gps(droite(3), 300).map((l, i) => { if (i >= 20) return l; const x = l.split(" "); x[2] = (Number(x[2]) + 0.02).toFixed(6); return x.join(" "); });   // 20 premières secondes : 2 km trop au nord
  const g = java(["N 1000 12 sat", ...L, "F 301000"]).resume;
  assert.strictEqual(g.sauts, 1); assert.ok(g.dist > 780 && g.dist < 900, "distance " + g.dist + " pour 900 m vrais (10 s d'attente non comptées)"); });

test("départ : le GPS n'est « prêt » qu'avec une position précise (15 m), ou après 30 s d'attente", () => {
  const flou = (n) => (l, i) => { if (i >= n) return l; const x = l.split(" "); x[4] = "25.0"; return x.join(" "); };
  const a = java(["N 1000 12 sat", ...gps(droite(3), 120).map(flou(12)), "F 121000"]); assert.strictEqual(a.resume.premier, 12); assert.strictEqual(a.premiers, 1); assert.strictEqual(a.resume.rejetes, 12); assert.ok(ecart(a.resume.dist, 324) < 4, "distance " + a.resume.dist);
  const b = java(["N 1000 12 sat", ...gps(droite(3), 120).map(flou(200)), "F 121000"]).resume; assert.ok(b.premier === 30 || b.premier === 31, "premier " + b.premier); assert.strictEqual(b.rejetes, b.premier); });

test("tours marqués à la main : heure, distance et cardio de chaque tour", () => {
  const L = gps(droite(4), 500), avec = []; L.forEach(l => { const t = Number(l.split(" ")[1]); avec.push(l); if (t >= 100000 && t < 101000) avec.push("T 100500 141"); if (t >= 250000 && t < 251000) avec.push("T 250400 150"); if (t >= 300000 && t < 301000) avec.push("E 300400"); });
  const r = java(["N 1000 12 sat", ...avec, "F 501000"]), g = r.resume, e = r.etats[0];
  assert.strictEqual(g.tours.length, 2); assert.deepStrictEqual([g.tours[0][0], g.tours[0][2], g.tours[1][0], g.tours[1][2]], [101, 141, 250, 150]);
  assert.ok(Math.abs(g.tours[0][1] - 400) < 12 && Math.abs(g.tours[1][1] - 1000) < 20, JSON.stringify(g.tours));
  assert.strictEqual(e.tours, 2); assert.ok(Math.abs(e.tourT - 50) <= 1 && Math.abs(e.tourD - 200) < 15, JSON.stringify(e)); assert.ok(Math.abs(e.vit - 4) < 0.4, "vitesse du moment " + e.vit); assert.strictEqual(e.km, 1); assert.ok(Math.abs(e.dernierKm - 250) < 5); });

test("appli tuée en pleine course : à la reprise, même distance, mêmes tours, rien de perdu", () => {
  const L = gps(piste(3.2), 900), sans = java(["N 1000 12 sat", ...L.slice(0, 300), "T 250000 140", ...L.slice(300), "F 901000"]).resume;
  const avec = java(["N 1000 12 sat", ...L.slice(0, 300), "T 250000 140", ...L.slice(300, 500), "K 505000", ...L.slice(505, 700), "K 705000", ...L.slice(705), "F 901000"]).resume;
  assert.strictEqual(avec.reprises, 2); assert.strictEqual(avec.points, 901 - 10); assert.ok(Math.abs(avec.dist - sans.dist) < 12, avec.dist + " / " + sans.dist); assert.deepStrictEqual(avec.tours, sans.tours); assert.strictEqual(avec.km.length, sans.km.length);
  /* relire le fichier seul redonne exactement le même résultat (c'est ce qui permettra de recalculer un parcours plus tard) */
  const relu = java(["K 0", "F 901000"], true).resume; assert.strictEqual(relu.dist, avec.dist); assert.deepStrictEqual(relu.prof, avec.prof); assert.deepStrictEqual(relu.trace, avec.trace); assert.strictEqual(relu.reprises, 3);
  const txt = fs.readFileSync(TRACE, "utf8").split("\n"); assert.ok(txt[0].startsWith("G 1000 12.0 sat")); assert.strictEqual(txt.filter(l => l.startsWith("P ")).length, 891); assert.strictEqual(txt.filter(l => l.startsWith("R ")).length, 3); });

test("position mélangée, sans vitesse donnée par le GPS : la distance reste à moins de 4 %", () => {
  const g = java(["N 1000 12 mix", ...gps(droite(3), 600, { sansVitesse: true }), "F 601000"]).resume;
  assert.ok(ecart(g.dist, 1800) < 4, "distance " + g.dist); assert.strictEqual(g.source, "mix"); console.log("   distance " + g.dist + " m (écart " + pc(ecart(g.dist, 1800)) + ")"); });

test("pas de GPS du tout : un résumé vide, sans erreur", () => {
  const g = java(["N 1000 12 sat", "F 60000"]).resume; assert.strictEqual(g.dist, 0); assert.strictEqual(g.premier, -1); assert.strictEqual(g.points, 0); assert.strictEqual(g.prof.length, 13); assert.ok(g.trace.every(v => v === 0)); assert.strictEqual(g.precMoy, null); });

console.log("\n" + n + " tests réussis.");
