/* Tests du cœur (node activite/tests/test-noyau.js). Données inventées, de la même forme qu'une sauvegarde du Hub.
   Lancés à chaque fabrication de l'APK : si un test casse, l'APK n'est pas fabriquée. */
"use strict";
const assert = require("assert");
const N = require("../noyau.js");

const T0 = Date.UTC(2026, 10, 11, 11, 0, 0);   // 11/11/2026 15:00 à La Réunion
function hubFictif(plus) {
  const courses = [
    { id: T0 - 8 * 3600e3, debut: T0 - 8 * 3600e3, notes: [] },                                  // séance vide
    { id: T0, debut: T0, act: "muscu", close: true, notes: [
      { t: T0, fc: 120, pompes: { obj: 100, total: 100, temps: 980, series: [12, 12, 12, 12, 12, 12, 12, 16], reposMoy: 128, tl: [1, 2, 3], fcs: [[70, 115, 115], [80, 118, 118]], res: "100 pompes en 16:20, 8 séries",
        test: [{ tap: 12, polar: 13 }, { tap: 12, polar: 12 }] } },
      { t: T0 + 60e3, fc: 83, sensLibre: "Très facile" }] },
    { id: T0 + 4 * 3600e3, debut: T0 + 4 * 3600e3, act: "muscu", close: true, notes: [                 // tennis rangé par erreur dans « muscu »
      { t: T0 + 4 * 3600e3, h10rec: { debut: T0 + 3600e3, act: "tennis", res: { duree: 6, actif: 3, pics: 4, fcMoy: 129, fcMax: 180 }, l: [[0, 100, 5, 9, 0], [1, 101, 5, 9, 1], [2, 102, 5, 9, 1], [700, 120, 5, 9, 1], [701, 121, 5, 9, 1], [702, 122, 5, 9, 0]] } }] }
  ];
  if (plus) { courses[1].notes.push({ t: T0 + 120e3, note: "ajoutée plus tard" }); courses.push({ id: T0 + 24 * 3600e3, debut: T0 + 24 * 3600e3, act: "footing", notes: [{ t: T0 + 24 * 3600e3, km: 1 }] }); }
  return { format: "mon-hub-sauvegarde", version: 1, hub: "1.35", creeeLe: "2026-11-12T11:24:57.271Z",
    applis: [{ id: "f-autre", nom: "Autre appli", version: "1", code: "x" }, { id: "f-footing-test", nom: "Footing", version: 29, code: "y" }],
    donnees: { "f-autre": { localStorage: { "cle": "valeur" }, bases: [] },
      "f-footing-test": { localStorage: { "footing-courses": JSON.stringify(courses), "act-courante": "muscu", "pompes-opt": "{\"obj\":100,\"test\":true,\"repos\":90}", "acts-perso": plus ? "[\"Corde\",\"Rameur\"]" : "[\"Corde\"]", "pompes-journee": "null" }, bases: [] } } };
}
const ligne = (d, nom) => d.rows.find(r => r[0].startsWith(nom));
let n = 0; function test(nom, fn) { fn(); n++; console.log("✅ " + nom); }

test("le Journal est reconnu par sa clé footing-courses, pas par son identifiant", () => {
  const ex = N.extraireHub(hubFictif()); assert.strictEqual(ex.journaux.length, 1); assert.strictEqual(ex.journaux[0].nom, "Footing"); assert.strictEqual(ex.journaux[0].version, "29"); });

const base = N.baseVide();
test("1er import : tout est ajouté, avec les bons totaux", () => {
  const d = N.importer(base, hubFictif(), { version: "test" });
  assert.deepStrictEqual(ligne(d, "Séances"), ["Séances", "+3", "0"]);
  assert.deepStrictEqual(ligne(d, "Notes"), ["Notes", "+3", "0"]);
  assert.deepStrictEqual(ligne(d, "Pompes"), ["Pompes (1 séance)", "+100", "0"]);
  assert.deepStrictEqual(ligne(d, "Enregistrements H10").slice(1), ["+1", "0"]);
  assert.deepStrictEqual(ligne(d, "Séries de test").slice(1), ["+2", "0"]);
  assert.ok(d.obs.some(o => o.includes("séance vide")), "séance vide signalée");
  assert.ok(d.obs.some(o => o.includes("rangées dans Pompes")), "muscu → pompes signalé");
  assert.ok(d.obs.some(o => o.includes("affiché en Tennis")), "tennis mal rangé signalé");
  assert.ok(d.obs.some(o => o.includes("1 trou")), "trou H10 signalé");
  assert.strictEqual(base.imports.length, 1); });

test("les séances Muscu du Hub passent en Pompes, l'origine reste notée", () => {
  const c = base.courses.find(x => x.id === T0); assert.strictEqual(c.act, "pompes"); assert.strictEqual(c.actOrigine, "muscu"); assert.strictEqual(base.reglages["act-courante"], "pompes"); });

test("Historique : tennis rangé en Tennis à sa vraie heure, séance vide cachée, données intégrées présentes", () => {
  const l = N.lignesHistorique(base);
  const tennis = l.find(x => x.act === "tennis"); assert.ok(tennis); assert.strictEqual(tennis.t, T0 + 3600e3); assert.ok(tennis.alerte.includes("1 trou"));
  const pompes = l.find(x => x.id === "s" + T0); assert.strictEqual(pompes.act, "pompes"); assert.strictEqual(pompes.pompes, 100); assert.strictEqual(pompes.detail.length, 2);
  assert.strictEqual(l.filter(x => x.id.startsWith("s")).length, 1, "une seule ligne de séance (la vide est cachée)");
  assert.strictEqual(l.find(x => x.id === "j3008").pompes, 889, "journée du 30/08/2025 = 889 pompes");
  assert.strictEqual(l.filter(x => x.source === "Carnet").length, 6);
  assert.strictEqual(N.totaux(base).pompes, 100 + 889 + 438); });

test("2e import du même fichier : rien en double", () => {
  const avant = N.stable(base.courses), d = N.importer(base, hubFictif(), {});
  assert.ok(d.rien); assert.deepStrictEqual(ligne(d, "Séances"), ["Séances", "0", "3"]); assert.deepStrictEqual(ligne(d, "Pompes"), ["Pompes (1 séance)", "0", "100"]);
  assert.strictEqual(N.stable(base.courses), avant); });

test("import d'une sauvegarde plus récente : seul ce qui manque est ajouté", () => {
  const d = N.importer(base, hubFictif(true), {});
  assert.deepStrictEqual(ligne(d, "Séances"), ["Séances", "+1", "3"]); assert.deepStrictEqual(ligne(d, "Notes"), ["Notes", "+2", "3"]);
  assert.deepStrictEqual(JSON.parse(base.reglages["acts-perso"]), ["Corde", "Rameur"]);
  const c = base.courses.find(x => x.id === T0); assert.strictEqual(c.notes.length, 3); assert.ok(c.notes[0].t <= c.notes[1].t && c.notes[1].t <= c.notes[2].t); });

test("une valeur différente dans le fichier n'écrase jamais celle de l'appli", () => {
  const h = hubFictif(); const cs = JSON.parse(h.donnees["f-footing-test"].localStorage["footing-courses"]); cs[1].notes[1].sensLibre = "Dur"; h.donnees["f-footing-test"].localStorage["footing-courses"] = JSON.stringify(cs);
  h.donnees["f-footing-test"].localStorage["pompes-opt"] = "{\"obj\":50}";
  const d = N.importer(base, h, {}); assert.ok(d.obs.some(o => o.includes("note différente"))); assert.ok(d.obs.some(o => o.includes("réglage différent")));
  assert.strictEqual(base.courses.find(x => x.id === T0).notes.find(x => x.sensLibre).sensLibre, "Très facile"); assert.ok(base.reglages["pompes-opt"].includes("100")); });

test("sauvegarde de l'appli → appli vide : tout revient à l'identique", () => {
  const s = JSON.parse(JSON.stringify(N.sauvegarde(base, "test"))), b2 = N.baseVide(); N.importer(b2, s, {});
  assert.strictEqual(N.stable(b2.courses), N.stable(base.courses)); assert.strictEqual(N.stable(b2.reglages), N.stable(base.reglages)); assert.deepStrictEqual(N.totaux(b2), N.totaux(base)); });

test("un fichier inconnu est refusé sans rien changer", () => {
  const avant = N.stable(base); assert.throws(() => N.importer(base, { format: "autre" }, {})); assert.throws(() => N.importer(base, { format: "mon-hub-sauvegarde", applis: [], donnees: {} }, {})); assert.strictEqual(N.stable(base), avant); });

test("dates à l'heure de La Réunion", () => { assert.strictEqual(N.dateHeure(T0), "11/11/2026 15:00"); assert.strictEqual(N.horodatage(T0), "2026-11-11_15h00"); });

/* ----- étape 2 : enregistrements H10 faits par l'APK, réglage des impacts ----- */
function recFictif(debut) { const l = []; for (let s = 0; s < 900; s++) { if (s >= 300 && s < 420) continue; l.push([s, 100 + (s % 60), s % 3 ? 150 : 40, 900 + (s % 7) * 300, s % 10 === 0 ? 1 : 0]); }
  return { debut, act: "tennis", res: { duree: l.length, actif: l.filter(x => x[2] > 120).length, pics: l.reduce((a, x) => a + x[4], 0), fcMoy: 130, fcMax: 159 }, l, coupures: 1, pics: [[5, 2700, 1, 2500], [9, 1600, 0, 2500]], deconnexions: [[300, 120]], fin: debut + 905000, source: "APK test" }; }

test("enregistrement de l'APK ajouté à la base : une séance, une ligne Tennis, jamais en double", () => {
  const b = N.baseVide(), R = recFictif(T0);
  assert.deepStrictEqual(N.enregistrementsManquants(b, ["rec-" + T0 + ".json", "autre.txt", "rec-abc.json"]), ["rec-" + T0 + ".json"]);
  assert.strictEqual(N.ajouterEnregistrement(b, R), true); assert.strictEqual(N.ajouterEnregistrement(b, JSON.parse(JSON.stringify(R))), false); assert.strictEqual(b.courses.length, 1);
  assert.deepStrictEqual(N.enregistrementsManquants(b, ["rec-" + T0 + ".json"]), []);
  const l = N.lignesHistorique(b).find(x => x.act === "tennis"); assert.strictEqual(l.t, T0); assert.strictEqual(l.duree, 780); assert.ok(l.alerte.includes("1 trou")); assert.ok(l.detail.some(x => x.includes("ceinture perdue 2 min")));
  assert.throws(() => N.ajouterEnregistrement(b, { debut: 1 })); });

test("rapport d'un enregistrement : durée, temps perdu, coupures, tranches de 10 minutes", () => {
  const r = N.rapportEnregistrement(recFictif(T0), { seuil: 2500 }, "1.3"); assert.strictEqual(r.bilan.ecoule, 905); assert.strictEqual(r.bilan.enregistre, 780); assert.strictEqual(r.bilan.perdu, 125);
  assert.strictEqual(r.bilan.trous.length, 1); assert.strictEqual(r.bilan.tempsDeconnecte, 120); assert.strictEqual(r.bilan.mouvementsComptes, 1);
  assert.ok(r.resume.includes("Tennis — 11/11/2026 15:00 (APK 1.3)")); assert.ok(r.resume.includes("perdu 2 min")); assert.ok(r.resume.includes("Coupures de la ceinture : 1 (2 min en tout)")); assert.ok(r.resume.includes("réglage 2,5 g"));
  assert.ok(r.tout.includes("15:00 | 480 |") && r.tout.includes("15:10 | 300 |"), r.tout); assert.ok(r.tout.includes("- 15:05:00 pendant 2 min")); });

test("réglage des impacts : ± 0,1 g entre 1,2 et 4 g, journal, essai « N coups » comme dans la v31", () => {
  const b = N.baseVide(); assert.deepStrictEqual(N.impLire(b), { seuil: 2500, rearme: 1800, bip: true });
  assert.deepStrictEqual(N.impRegler(b, -100, "enreg", 2700), { seuil: 2400, rearme: 1840, bip: true }); for (let i = 0; i < 30; i++) N.impRegler(b, +100); assert.strictEqual(N.impLire(b).seuil, 4000);
  for (let i = 0; i < 40; i++) N.impRegler(b, -100); assert.strictEqual(N.impLire(b).seuil, 1200); assert.strictEqual(N.impBip(b, false).bip, false);
  const mes = []; [3000, 2600, 2900, 1700, 3100, 1500].forEach((m, i) => { const t = 1000 + i * 1500; mes.push([t, 1300], [t + 20, m], [t + 40, 1400]); });
  assert.deepStrictEqual(N.calPics(mes, 600).map(x => x[1]), [3000, 2600, 2900, 1700, 3100, 1500]);
  const d = N.essaiEnDirect(mes, { seuil: 2500 }); assert.strictEqual(d.comptes, 4); assert.strictEqual(d.mouvements, 6);
  const r = N.impEssai(b, mes, 4); assert.strictEqual(r.imp.seuil, 2150); assert.strictEqual(r.imp.rearme, 1690); assert.strictEqual(r.imp.bip, false); assert.ok(r.texte.includes("2,15 g")); assert.strictEqual(N.impLire(b).seuil, 2150);
  assert.ok(N.impEssai(b, mes, 9).texte.includes("Je n'ai vu que 6 mouvements")); assert.strictEqual(N.impLire(b).seuil, 1300);
  const rap = N.rapportReglages(b); assert.ok(rap.includes("2 essais")); assert.ok(rap.includes("essai 4 coups | 3,1 · 3,0 · 2,9 · 2,6 | 1,7 | 2,1") || rap.includes("essai 4 coups | 3,1 · 3,0 · 2,9 · 2,6 | 1,7 | 2,2"), rap);
  assert.strictEqual(N.chrono(3725), "1:02:05"); assert.strictEqual(N.chrono(65), "1:05"); });

console.log("\n" + n + " tests réussis.");
