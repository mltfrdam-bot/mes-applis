/* Teste l'enregistreur de séance de la partie Android (Trames, SuiviImpacts, Enregistreur), hors du téléphone.
   node activite/tests/test-enregistreur.js
   On fabrique de fausses trames de ceinture H10 (FC + accéléromètre, formats brut et compressé), on les passe
   à la fois dans le Java de l'APK et dans le code d'origine de la v31, et on exige le même résultat. */
"use strict";
const assert = require("assert"), fs = require("fs"), os = require("os"), path = require("path"), { execFileSync, spawnSync } = require("child_process");
const racine = path.join(__dirname, "..", ".."), src = path.join(racine, "android-activite/app/src/main/java/fr/mesapplis/activite"), tmp = fs.mkdtempSync(path.join(os.tmpdir(), "enreg-"));
const env = Object.assign({}, process.env); delete env.JAVA_TOOL_OPTIONS;
execFileSync("javac", ["-encoding", "UTF-8", "-d", path.join(tmp, "classes"), ...["Trames", "SuiviImpacts", "Enregistreur", "Json", "Brut"].map(x => path.join(src, x + ".java")), path.join(racine, "android-activite/tests/EssaiEnregistreur.java")], { env, stdio: "inherit" });
const BRUT = path.join(tmp, "brut.bin"), Lecteur = require("../outils/lire-brut.js");
function java(lignes) { const sc = path.join(tmp, "scenario.txt"), sortie = path.join(tmp, "sortie.json"), journal = path.join(tmp, "journal.txt"); fs.writeFileSync(sc, lignes.join("\n") + "\n"); try { fs.unlinkSync(sortie); } catch (e) {} try { fs.unlinkSync(BRUT); } catch (e) {}
  const r = spawnSync("java", ["-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-cp", path.join(tmp, "classes"), "fr.mesapplis.activite.EssaiEnregistreur", sc, journal, sortie, BRUT], { env, encoding: "utf8" });
  assert.strictEqual((r.stdout || "").trim(), "OK", r.stderr); assert.ok(!fs.existsSync(journal), "le journal est effacé une fois l'enregistrement terminé"); return JSON.parse(fs.readFileSync(sortie, "utf8")); }
let n = 0; function test(nom, fn) { fn(); n++; console.log("✅ " + nom); }

/* ----- hasard reproductible ----- */
let graine = 12345; const alea = () => { graine = (graine * 1103515245 + 12345) & 0x7fffffff; return graine / 0x7fffffff; };
const hex = (o) => Buffer.from(o).toString("hex");

/* ----- fausses trames ----- */
function signal(nb, coups) { const s = []; let impact = 0, force = 0;
  for (let i = 0; i < nb; i++) { if (coups && alea() < 0.012) { impact = 3; force = 400 + alea() * 3200; } const bruit = () => Math.round((alea() - 0.5) * 60);
    const plus = impact > 0 ? force * (impact === 2 ? 1 : 0.5) : 0; if (impact > 0) impact--; s.push([bruit() + Math.round(plus * 0.3), bruit() - Math.round(plus * 0.2), 1000 + bruit() + Math.round(plus)]); } return s; }
function trameBrute(ech) { const b = Buffer.alloc(10 + ech.length * 6); b[0] = 2; b[9] = 1; ech.forEach((a, i) => a.forEach((v, k) => b.writeInt16LE(v, 10 + i * 6 + k * 2))); return b; }
function trameCompressee(ech) { const tete = Buffer.alloc(16); tete[0] = 2; tete[9] = 0x81; ech[0].forEach((v, k) => tete.writeInt16LE(v, 10 + k * 2)); const blocs = [tete]; let i = 1, ref = ech[0];
  while (i < ech.length) { const cnt = Math.min(ech.length - i, 1 + Math.floor(alea() * 12)), deltas = []; for (let k = 0; k < cnt; k++) { deltas.push(ech[i + k].map((v, a) => v - ref[a])); ref = ech[i + k]; }
    let nb = 1; const max = Math.max(...deltas.flat().map(v => v < 0 ? -v - 1 : v)); while ((1 << (nb - 1)) <= max) nb++; nb = Math.min(16, nb + Math.floor(alea() * 2));
    const bits = Buffer.alloc(Math.ceil(cnt * 3 * nb / 8)); let bit = 0; deltas.flat().forEach(v => { const u = v < 0 ? v + (1 << nb) : v; for (let k = 0; k < nb; k++) { if ((u >> k) & 1) bits[(bit + k) >> 3] |= 1 << ((bit + k) & 7); } bit += nb; });
    blocs.push(Buffer.from([nb, cnt]), bits); i += cnt; } return Buffer.concat(blocs); }

/* ----- le code d'origine de la v31 (accTrame, picSuivre, recEch, recFermer, recResume), avec l'horloge donnée ----- */
function v31() { let IMP = { seuil: 2500, rearme: 1800, bip: true }, REC = null, recSec = null, pic = null, derPic = null, fc = null, fcT = 0, maintenant = 0, bips = 0, derBipT = 0;
  const fcFraiche = () => fc && maintenant - fcT < 6000 ? fc : null;
  function recFermer(x) { REC.l.push([x.s, fcFraiche() || 0, Math.round(x.som / Math.max(1, x.n)), Math.round(x.max), x.pics]); }
  function recEch(m, t) { const sec = Math.floor((maintenant - REC.debut) / 1000);
    if (!recSec || recSec.s !== sec) { if (recSec) recFermer(recSec); recSec = { s: sec, n: 0, som: 0, max: 0, pics: 0, enPic: false }; }
    const dv = Math.abs(m - 1000); recSec.n++; recSec.som += dv; if (dv > recSec.max) recSec.max = dv;
    if (m > IMP.seuil && !recSec.enPic && (REC.derPic == null || t - REC.derPic > 400)) { recSec.pics++; REC.derPic = t; recSec.enPic = true; } else if (m < IMP.rearme) recSec.enPic = false; }
  function picSuivre(m, t) { if (!pic) { if (m > 1250) pic = { m, t }; return; } if (m > pic.m) pic.m = m; if (m < 1150) { if (pic.m > 1400 && (!derPic || t - derPic.t > 400)) { derPic = { m: pic.m, t };
        (REC.pics = REC.pics || []).push([Math.floor((maintenant - REC.debut) / 1000), Math.round(pic.m), pic.m > IMP.seuil ? 1 : 0, IMP.seuil]);
        if (pic.m > IMP.seuil && IMP.bip !== false) bips++; } pic = null; } }
  function accTrame(dv, now) { if (dv.getUint8(0) !== 2) return; const ft = dv.getUint8(9); let o = 10; const out = [];
    if (ft === 1) { while (o + 6 <= dv.byteLength) { out.push([dv.getInt16(o, true), dv.getInt16(o + 2, true), dv.getInt16(o + 4, true)]); o += 6; } }
    else if (ft === 0) { while (o + 3 <= dv.byteLength) { out.push([dv.getInt8(o), dv.getInt8(o + 1), dv.getInt8(o + 2)]); o += 3; } }
    else if (ft & 0x80) { let ref = [dv.getInt16(o, true), dv.getInt16(o + 2, true), dv.getInt16(o + 4, true)]; o += 6; out.push(ref.slice());
      while (o + 2 <= dv.byteLength) { const nb = dv.getUint8(o), cnt = dv.getUint8(o + 1); o += 2; let bit = 0;
        const lire = () => { let v = 0; for (let k = 0; k < nb; k++) { const by = dv.getUint8(o + ((bit + k) >> 3)); v |= ((by >> ((bit + k) & 7)) & 1) << k; } bit += nb; return v & (1 << (nb - 1)) ? v - (1 << nb) : v; };
        for (let i = 0; i < cnt; i++) { ref = [ref[0] + lire(), ref[1] + lire(), ref[2] + lire()]; out.push(ref.slice()); } o += Math.ceil(cnt * 3 * nb / 8); } }
    const nn = out.length; out.forEach((a, i) => { const m = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]), t = now - (nn - 1 - i) * 20; picSuivre(m, t); recEch(m, t); }); }
  function recResume(R) { const l = R.l, act = l.filter(x => x[2] > 120).length, fcs = l.map(x => x[1]).filter(Boolean), pics = l.reduce((a, x) => a + x[4], 0);
    return { duree: l.length, actif: act, pics, fcMoy: fcs.length ? Math.round(fcs.reduce((a, b) => a + b, 0) / fcs.length) : null, fcMax: fcs.length ? Math.max(...fcs) : null }; }
  return { regler: (s, r, b) => { IMP = { seuil: s, rearme: r, bip: b }; }, demarrer: (debut, act) => { REC = { debut, act, l: [], pics: [] }; recSec = null; },
    fc: (h, v) => { maintenant = h; fc = v.getUint8(0) & 1 ? v.getUint16(1, true) : v.getUint8(1); fcT = h; }, trame: (h, t, b) => { maintenant = h; accTrame(new DataView(b.buffer, b.byteOffset, b.length), t); },
    tuee: () => { recSec = null; pic = null; derPic = null; REC.derPic = null; REC.coupures = (REC.coupures || 0) + 1; },
    finir: (h) => { maintenant = h; if (recSec) recFermer(recSec); return { debut: REC.debut, act: REC.act, res: recResume(REC), l: REC.l, coupures: REC.coupures || 0, pics: REC.pics, bips }; } }; }

const T0 = Date.UTC(2026, 10, 13, 12, 0, 0);
function scenario(minutes, options) { options = options || {}; const L = ["S 2500 1800 1", "N " + T0 + " tennis"], ref = v31(); ref.regler(2500, 1800, true); ref.demarrer(T0, "tennis");
  let h = T0 + 137, t = 5000137, fcv = 110; const fin = T0 + minutes * 60000; let k = 0;
  while (h < fin) { k++;
    if (k % 2 === 0) { fcv = Math.max(60, Math.min(190, fcv + Math.round((alea() - 0.5) * 6))); const b = fcv > 150 && alea() < 0.5 ? Buffer.from([0x11, fcv & 255, fcv >> 8, 0x20, 0x03]) : Buffer.from([0x10, fcv, 0x20, 0x03]); L.push("H " + h + " " + hex(b)); ref.fc(h, new DataView(b.buffer, b.byteOffset, b.length)); }
    const ech = signal(36, true), b = alea() < 0.3 ? trameBrute(ech) : trameCompressee(ech); L.push("F " + h + " " + t + " " + hex(b)); ref.trame(h, t, b); if (options.garder) options.garder.push({ ms: h - T0, mesures: ech.map(a => a.map(v => v + 0)) });
    if (options.reglage && k === 300) { L.push("S 1800 1480 1"); ref.regler(1800, 1480, true); }
    if (options.tuee && k === options.tuee) { L.push("K " + (h + 5)); ref.tuee(); }
    const pas = 720 + Math.round((alea() - 0.5) * 40); h += pas; t += pas; }
  L.push("E " + h); return { lignes: L, attendu: ref.finir(h), fin: h }; }

test("15 min de tennis : mêmes secondes, mêmes impacts, même résumé que la v31", () => {
  const s = scenario(15, { reglage: true }), r = java(s.lignes);
  assert.ok(s.attendu.l.length > 890 && s.attendu.res.pics > 20 && s.attendu.pics.length > 40, "le scénario contient bien des impacts");
  assert.deepStrictEqual(r.rec.l, s.attendu.l); assert.deepStrictEqual(r.rec.pics, s.attendu.pics); assert.deepStrictEqual(r.rec.res, s.attendu.res);
  assert.strictEqual(r.bips, s.attendu.bips); assert.strictEqual(r.rec.debut, T0); assert.strictEqual(r.rec.act, "tennis"); assert.strictEqual(r.rec.coupures, 0); assert.deepStrictEqual(r.rec.deconnexions, []);
  console.log("   " + r.rec.l.length + " secondes, " + r.rec.res.pics + " impacts, " + r.rec.pics.length + " mouvements nets, FC moy " + r.rec.res.fcMoy + " / max " + r.rec.res.fcMax); });

test("mesures brutes : le fichier de la séance redonne exactement chaque mesure, même après une appli tuée ou un fichier abîmé", () => {
  graine = 4242; const garde = [], s = scenario(3, { tuee: 100, garder: garde }); java(s.lignes); const octets = fs.readFileSync(BRUT), r = Lecteur.lire(octets);
  assert.strictEqual(r.debut, T0); assert.strictEqual(r.hz, 50); assert.strictEqual(r.blocs.length, garde.length); assert.deepStrictEqual(r.blocs, garde); assert.strictEqual(octets.length, 16 + garde.length * (8 + 36 * 6));
  const abime = Buffer.concat([octets.subarray(0, 16 + 10 * 224), octets.subarray(16 + 10 * 224 + 57)]), r2 = Lecteur.lire(abime); assert.strictEqual(r2.blocs.length, garde.length - 1, "un bloc abîmé est sauté, les autres restent lisibles");
  const coupe = Lecteur.lire(octets.subarray(0, octets.length - 30)); assert.strictEqual(coupe.blocs.length, garde.length - 1); assert.strictEqual(Lecteur.aPlat(r).length, garde.length * 36);
  console.log("   " + r.mesures + " mesures, " + Math.round(octets.length / 1024) + " Ko pour 3 min (≈ " + (octets.length / 180 * 5400 / 1e6).toFixed(1) + " Mo pour 1 h 30)"); });

test("appli tuée 2 fois en pleine séance : reprise depuis le journal, aucune seconde écrite n'est perdue", () => {
  graine = 777; const s = scenario(6, { tuee: 150 }); const i = s.lignes.findIndex((x, k) => k > 300 && x.startsWith("F ")); s.lignes.splice(i, 0, "K " + (Number(s.lignes[i].split(" ")[1]) - 1));
  const r = java(s.lignes); assert.strictEqual(r.rec.coupures, 2); assert.ok(r.rec.l.length >= s.attendu.l.length - 2, r.rec.l.length + " / " + s.attendu.l.length);
  const sec = r.rec.l.map(x => x[0]); assert.deepStrictEqual(sec, sec.slice().sort((a, b) => a - b)); assert.strictEqual(new Set(sec).size, sec.length, "aucune seconde en double"); });

test("ceinture perdue puis retrouvée : la coupure est notée avec sa durée, et laisse un trou dans les secondes", () => {
  const L = ["S 2500 1800 0", "N " + T0 + " Corde à sauter"], fcb = (v) => hex(Buffer.from([0x10, v]));
  for (let s = 0; s < 400; s++) { const h = T0 + s * 1000 + 500, coupe = s >= 100 && s < 220; if (s === 100) L.push("X " + h + " 0"); if (s === 220) L.push("X " + h + " 1");
    if (!coupe) L.push("H " + h + " " + fcb(100 + (s % 20))); L.push("T " + (h + 400)); if (s === 150 || s === 399) L.push("Q " + (h + 450)); }
  L.push("X " + (T0 + 400500) + " 0", "E " + (T0 + 430500));
  const r = java(L); assert.deepStrictEqual(r.rec.deconnexions, [[100, 120], [400, 30]]); assert.strictEqual(r.rec.act, "Corde à sauter");
  const sec = r.rec.l.map(x => x[0]); assert.ok(!sec.some(x => x > 101 && x < 219), "pas de seconde inventée pendant la coupure"); assert.ok(sec.includes(50) && sec.includes(300));
  assert.ok(r.rec.l.every(x => x[2] === 0 && x[3] === 0 && x[4] === 0 && x[1] >= 100), "FC seule, sans mouvement"); assert.ok(r.rec.res.duree >= 275 && r.rec.res.duree <= 282, String(r.rec.res.duree));
  assert.strictEqual(r.etats[0].deconnexions, 1); assert.ok(r.etats[0].perdu >= 48 && r.etats[0].perdu <= 52, "pendant la coupure, le temps perdu augmente"); assert.strictEqual(r.etats[1].deconnexions, 1); assert.strictEqual(r.etats[1].fcMax, 119); });

test("trame au format 0 (petites valeurs) et trames inconnues ou coupées : pas de plantage", () => {
  const f0 = Buffer.alloc(10 + 9); f0[0] = 2; f0[9] = 0; [1, -2, 3, 4, 5, -6, 7, 8, 9].forEach((v, i) => f0.writeInt8(v, 10 + i));
  const coupee = trameCompressee(signal(36, false)).subarray(0, 40), autre = Buffer.from([1, 0, 0, 0, 0, 0, 0, 0, 0, 1, 5, 5]);
  const L = ["S 2500 1800 1", "N " + T0 + " tennis", "F " + (T0 + 100) + " 1000 " + hex(f0), "F " + (T0 + 300) + " 1200 " + hex(coupee), "F " + (T0 + 500) + " 1400 " + hex(autre), "F " + (T0 + 600) + " 1500 02", "E " + (T0 + 2000)];
  const r = java(L); assert.strictEqual(r.rec.l.length, 1); assert.strictEqual(r.rec.l[0][0], 0); });

test("séance jamais terminée (appli tuée pour de bon) : clôturée depuis le journal, à l'heure de sa dernière seconde", () => {
  const L = ["S 2500 1800 0", "N " + T0 + " tennis"], fcb = (v) => hex(Buffer.from([0x10, v])); for (let s = 0; s < 120; s++) { const h = T0 + s * 1000 + 500; L.push("H " + h + " " + fcb(120), "T " + (h + 400)); } L.push("V");
  const r = java(L); assert.strictEqual(r.rec.coupures, 0); assert.ok(r.rec.l.length >= 118 && r.rec.l.length <= 120); const der = r.rec.l[r.rec.l.length - 1][0]; assert.strictEqual(r.rec.fin, T0 + (der + 1) * 1000); assert.strictEqual(r.rec.res.fcMoy, 120); });

/* ----- version 1.9 : le temps minimum entre deux impacts dépend de l'activité ----- */
function course(reglage, pasParMinute, minutes) { const L = ["S " + reglage, "N " + T0 + " footing"], periode = 60000 / pasParMinute; let h = T0 + 137, t = 5000137, i = 0, pas = 0, prochain = 300; const fin = T0 + minutes * 60000;
  while (h < fin) { const ech = []; for (let k = 0; k < 36; k++, i++) { const ms = i * 20; let z = 400 + Math.round((alea() - 0.5) * 80);                 // entre deux pas : le corps est en l'air, force faible
      if (ms >= prochain) { pas++; prochain += periode + (alea() - 0.5) * 30; z = 2700 + Math.round(alea() * 600); } else if (ms >= prochain - periode + 20 && ms < prochain - periode + 60) z = 1900; ech.push([30, -20, z]); }
    L.push("F " + h + " " + t + " " + hex(trameBrute(ech))); h += 720; t += 720; }
  L.push("E " + h); return { lignes: L, pas }; }
test("course à 165 pas par minute : avec 0,4 s (règle du tennis) un pas sur deux est refusé, avec 0,25 s tous les pas sont comptés", () => {
  const a = course("1800 1480 0 250", 165, 5), ra = java(a.lignes).rec; assert.ok(Math.abs(ra.res.pics - a.pas) <= 2, ra.res.pics + " impacts pour " + a.pas + " pas"); assert.deepStrictEqual(ra.reglage, { seuil: 1800, rearme: 1480, ecart: 250 });
  const b = course("1800 1480 0", 165, 5), rb = java(b.lignes).rec; assert.ok(rb.res.pics < b.pas * 0.75 && rb.res.pics > b.pas * 0.45, rb.res.pics + " impacts pour " + b.pas + " pas"); assert.deepStrictEqual(rb.reglage, { seuil: 1800, rearme: 1480, ecart: 400 });
  const c = course("1800 1480 0 250", 200, 2), rc = java(c.lignes).rec; assert.ok(Math.abs(rc.res.pics - c.pas) <= 2, "sprint à 200 pas par minute : " + rc.res.pics + " pour " + c.pas);
  console.log("   165 pas/min pendant 5 min : " + a.pas + " pas → " + ra.res.pics + " impacts avec 0,25 s, " + rb.res.pics + " avec 0,4 s"); });

fs.rmSync(tmp, { recursive: true, force: true });
console.log("\n" + n + " tests réussis.");
