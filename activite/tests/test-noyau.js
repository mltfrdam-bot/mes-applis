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

/* ----- étape 3 : compteur de répétitions, charge, objectif, enchaînement d'exercices ----- */
function serie(st, depart, nb, pas, fc) { let t = depart; for (let i = 0; i < nb; i++) { N.repsAppui(st, t, fc); t += pas; } return t - pas; }

test("pompes : séries au toucher, +1, −1, repos, difficulté, objectif dépassé, record → même forme de note que la v31", () => {
  const b = N.baseVide(); assert.strictEqual(N.recordSerie(b, "Pompes"), 45, "record de départ = plus longue série du Carnet et du 30/08/2025"); N.exoRegler(b, "Pompes", { obj: 30, charge: 5, repos: 90 });
  assert.deepStrictEqual(JSON.parse(b.reglages["pompes-opt"]), { obj: 30, repos: 90, charge: 5 });
  const st = N.repsNouveau(b, "pompes", ["Pompes"], T0), B = N.repsCourant(st); assert.strictEqual(B.phase, "attente");
  let t = serie(st, T0 + 5000, 12, 900, 110); assert.strictEqual(N.repsAppui(st, t + 100, 110).length, 0, "double appui trop rapide ignoré"); assert.strictEqual(N.repsTotal(B), 12);
  assert.ok(N.repsMoins(st, t + 500)); assert.ok(N.repsPlus(st, t + 1000)); assert.strictEqual(N.repsTotal(B), 12); N.repsFc(st, 131);
  assert.ok(N.repsFinSerie(st, t + 2000, 128)); assert.strictEqual(B.phase, "repos"); assert.ok(B.attendDiff); N.repsDiff(st, 1); assert.ok(!N.repsFinSerie(st, t + 2100, 128), "pas de série vide");
  assert.strictEqual(N.repsRepos(st, t + 2000 + 70000).signal, null); assert.strictEqual(N.repsRepos(st, t + 2000 + 81000).signal, "bientot"); assert.strictEqual(N.repsRepos(st, t + 2000 + 85000).signal, null); assert.strictEqual(N.repsRepos(st, t + 2000 + 90500).signal, "reprendre"); assert.strictEqual(N.repsRepos(st, t + 2000 + 95000).signal, null);
  assert.ok(N.repsPlus(st, t + 3000), "+1 au repos : ajouté à la série finie"); assert.strictEqual(B.series[0].tl.length, 13);
  N.repsCharge(st, 7.3); assert.strictEqual(B.charge, 7.5);
  assert.strictEqual(B.record, 45); B.record = 16;   // pour le test : on fait comme si le record était de 16
  const d2 = t + 2000 + 95000; let ev = []; let u = d2; for (let i = 0; i < 20; i++) { ev = ev.concat(N.repsAppui(st, u, 120)); u += 1000; }
  assert.deepStrictEqual(ev, [["record", 17], ["objectif", 30]], "record annoncé une fois, objectif annoncé une fois, et on continue après"); assert.strictEqual(N.repsTotal(B), 33);
  const s = N.repsSeance(st, u), p = s.notes[0].pompes; assert.strictEqual(s.act, "pompes"); assert.strictEqual(s.id, T0); assert.deepStrictEqual(p.series, [13, 20]); assert.strictEqual(p.total, 33); assert.strictEqual(p.obj, 30); assert.deepStrictEqual(p.charges, [5, 7.5]);
  assert.deepStrictEqual(p.diffs, [1, null]); assert.strictEqual(p.tl.length, 33); assert.deepStrictEqual(p.tl, p.tl.slice().sort((x, y) => x - y)); assert.strictEqual(p.fcs[0][1], 131); assert.strictEqual(p.manuels, 2); assert.ok(p.reposMoy >= 95 && p.reposMoy <= 98, String(p.reposMoy));
  assert.ok(p.res.startsWith("33 pompes en ") && p.res.includes("2 séries") && p.res.includes("charge 5 kg à 7,5 kg"), p.res); assert.strictEqual(s.notes[0].fc, 131);
  N.fusionner(b, { courses: [s], reglages: {} }); const l = N.lignesHistorique(b).find(x => x.id === "s" + T0); assert.strictEqual(l.act, "pompes"); assert.strictEqual(l.pompes, 33); assert.ok(l.resultat.includes("33 · 2 séries") && l.resultat.includes("+5 kg à 7,5 kg"), l.resultat);
  assert.strictEqual(N.recordSerie(b, "Pompes"), 45); assert.ok(N.rapportReps(s, "1.5").resume.includes("Pompes : 33 pompes") && N.rapportReps(s).resume.includes("objectif 30 atteint")); });

test("enchaînement (tuile Muscu) : exercices à la suite, exercice ajouté en route, un seul passage dans l'Historique", () => {
  const b = N.baseVide(); assert.deepStrictEqual(N.exosLire(b).map(x => x.nom), ["Pompes", "Squats", "Développé couché", "Biceps barre"]);
  assert.strictEqual(N.exoAjouter(b, "  Tractions ").nom, "Tractions"); assert.strictEqual(N.exoAjouter(b, "tractions").nom, "Tractions"); assert.strictEqual(N.exosLire(b).length, 5); N.exoRegler(b, "Squats", { obj: 20, charge: 20 });
  N.programmeEcrire(b, ["Squats", "Inconnu", "Pompes"]); assert.deepStrictEqual(N.programmeLire(b), ["Squats", "Pompes"]);
  const st = N.repsNouveau(b, "muscu", N.programmeLire(b), T0); let t = serie(st, T0 + 1000, 10, 2000, 100); N.repsFinSerie(st, t + 1000, 105); N.repsDiff(st, 2); t = serie(st, t + 60000, 8, 2000, 108);
  assert.ok(N.repsSuivant(st, t + 500, 110, b)); assert.strictEqual(N.repsCourant(st).exo, "Pompes"); assert.strictEqual(N.repsCourant(st).t0, null); t = serie(st, t + 30000, 15, 900, 120);
  assert.ok(N.repsSuivant(st, t + 500, 125, b, "Tractions"), "exercice ajouté en route"); assert.strictEqual(N.repsCourant(st).exo, "Tractions"); t = serie(st, t + 40000, 6, 2500, 130);
  assert.ok(!N.repsSuivant(st, t + 500, 130, b), "plus d'exercice : la séance est finie");
  const s = N.repsSeance(st, t + 1000); assert.strictEqual(s.act, "muscu"); assert.deepStrictEqual(s.notes.map(n => n.reps ? n.reps.exo : "Pompes"), ["Squats", "Pompes", "Tractions"]); assert.deepStrictEqual(s.notes[0].reps.series, [10, 8]); assert.deepStrictEqual(s.notes[0].reps.charges, [20, 20]);
  assert.ok(s.notes[0].t < s.notes[1].t && s.notes[1].t < s.notes[2].t); assert.strictEqual(s.notes[2].reps.charges, undefined);
  N.fusionner(b, { courses: [s], reglages: {} }); const l = N.lignesHistorique(b).filter(x => x.act === "muscu"); assert.strictEqual(l.length, 1); assert.strictEqual(l[0].pompes, 15);
  assert.strictEqual(l[0].resultat, "Squats 18 (20 kg) · Pompes 15 · Tractions 6 · ❤️ max 130"); assert.strictEqual(l[0].detail.length, 3); assert.strictEqual(N.recordSerie(b, "Squats"), 10); assert.strictEqual(N.recordSerie(b, "Tractions"), 6);
  const r = N.rapportReps(s, "1.5"); assert.ok(r.resume.includes("Squats : 18 répétitions") && r.resume.includes("il manque 2") && r.tout.includes("1 | 10 | 20 kg | 😐 Moyen | 100/105/105"), r.tout);
  assert.strictEqual(N.repsSeance(N.repsNouveau(b, "muscu", ["Squats"], T0), T0 + 5000), null, "séance sans aucune répétition : rien à ranger");
  const copie = JSON.parse(JSON.stringify(st)); assert.strictEqual(N.stable(N.repsSeance(copie, t + 1000)), N.stable(s), "la séance écrite dans le téléphone puis relue donne le même résultat"); });

console.log("\n" + n + " tests réussis.");
