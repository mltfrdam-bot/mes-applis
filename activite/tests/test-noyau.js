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
  const r = N.rapportReps(s, "1.5"); assert.ok(r.resume.includes("Squats : 18 répétitions") && r.resume.includes("il manque 2") && r.tout.includes("1 | 10 | 18 s | – | 20 kg | 😐 Moyen | 100/105/105"), r.tout);
  assert.strictEqual(N.repsSeance(N.repsNouveau(b, "muscu", ["Squats"], T0), T0 + 5000), null, "séance sans aucune répétition : rien à ranger");
  const copie = JSON.parse(JSON.stringify(st)); assert.strictEqual(N.stable(N.repsSeance(copie, t + 1000)), N.stable(s), "la séance écrite dans le téléphone puis relue donne le même résultat"); });

/* ----- supprimer une séance (corbeille) et 📋 copier le détail ----- */
test("supprimer une séance : elle part dans la corbeille, « Annuler » la remet à l'identique, un nouvel import ne la fait pas revenir", () => {
  const b = N.baseVide(); N.importer(b, hubFictif(), {}); N.ajouterEnregistrement(b, recFictif(T0 + 48 * 3600e3)); const avant = N.stable(b.courses), tt = N.totaux(b);
  const l = N.lignesHistorique(b), tennis = l.find(x => x.act === "tennis" && x.t === T0 + 3600e3), pompes = l.find(x => x.id === "s" + T0), neuf = l.find(x => x.t === T0 + 48 * 3600e3);
  assert.ok(N.supprimable(tennis) && N.supprimable(pompes)); assert.ok(!N.supprimable(l.find(x => x.id === "j3008")), "les lignes intégrées ne se suppriment pas"); assert.strictEqual(N.supprimerLigne(b, "carnet-0", 1), null);
  const e1 = N.supprimerLigne(b, pompes.id, 1000); assert.strictEqual(e1.notes.length, 2); assert.strictEqual(N.totaux(b).pompes, tt.pompes - 100); assert.strictEqual(N.totaux(b).lignes, tt.lignes - 1); assert.ok(!b.courses.some(c => c.id === T0), "séance vidée retirée");
  const e2 = N.supprimerLigne(b, tennis.id, 2000), e3 = N.supprimerLigne(b, neuf.id, 3000); assert.strictEqual(b.corbeille.length, 3); assert.strictEqual(N.lignesHistorique(b).filter(x => !x.source).length, 0);
  assert.strictEqual(N.supprimerLigne(b, pompes.id, 4000), null, "déjà supprimée");
  const d = N.importer(b, hubFictif(), {}); assert.strictEqual(N.lignesHistorique(b).filter(x => !x.source).length, 0, "le nouvel import ne remet pas ce qui est dans la corbeille"); assert.ok(d.obs.some(o => o.includes("corbeille")), d.obs.join(" / "));
  assert.deepStrictEqual(N.enregistrementsManquants(b, ["rec-" + (T0 + 48 * 3600e3) + ".json"]), [], "le fichier d'enregistrement d'une séance supprimée n'est pas réimporté");
  const s = JSON.parse(JSON.stringify(N.sauvegarde(b, "test"))); assert.strictEqual(s.corbeille.length, 3, "la corbeille est dans la sauvegarde");
  const b2 = N.baseVide(); N.importer(b2, s, {}); assert.strictEqual(b2.corbeille.length, 3); assert.strictEqual(N.lignesHistorique(b2).filter(x => !x.source).length, 0);
  const b3 = N.baseVide(); N.importer(b3, hubFictif(), {}); N.importer(b3, s, {}); assert.ok(N.lignesHistorique(b3).some(x => x.id === pompes.id), "une fusion ne supprime jamais une séance encore présente"); assert.strictEqual(b3.corbeille.length, 1, "seule la séance absente de l'appli arrive dans sa corbeille");
  assert.ok(N.restaurer(b, e2.t, e2.id)); assert.ok(N.restaurer(b, e1.t, e1.id)); assert.ok(N.restaurer(b, e3.t, e3.id)); assert.ok(!N.restaurer(b, e3.t, e3.id)); assert.strictEqual(b.corbeille.length, 0);
  assert.strictEqual(N.stable(b.courses), avant, "tout est revenu à l'identique"); assert.deepStrictEqual(N.totaux(b), tt); });

test("📋 copier le détail : minute par minute et heure exacte des trous (H10), série par série (pompes, Carnet)", () => {
  const b = N.baseVide(); N.ajouterEnregistrement(b, recFictif(T0)); const id = N.lignesHistorique(b).find(x => x.act === "tennis").id, t = N.detailLigne(b, id, "1.6");
  assert.ok(t.startsWith("DÉTAIL — Tennis — 11/11/2026 15:00 (APK 1.6)"), t.slice(0, 80)); assert.ok(t.includes("Trous (plus de 5 s sans mesure) : 1") && t.includes("- de 15:05:00 à 15:07:00 (2:00)"), t.slice(0, 600));
  assert.ok(t.includes("Petits manques (1 à 5 s) : 5 s en tout")); assert.ok(t.includes("Coupures de la ceinture : 1") && t.includes("- 15:05:00 pendant 2 min"));
  const lignes = t.split("\n"), i = lignes.indexOf("Minute | FC moy | FC max | Impacts | Mouvement moy (g) | Force max (g) | Secondes"); assert.ok(i > 0); assert.strictEqual(lignes.length - i - 1, 13, "15 minutes moins les 2 minutes du trou");
  assert.strictEqual(lignes[i + 1], "15:00 | 130 | 159 | 6 | 0,11 | 3,7 | 60"); assert.ok(lignes.some(x => x.startsWith("15:07 | ")) && !lignes.some(x => x.startsWith("15:05 | ") || x.startsWith("15:06 | ")));
  N.importer(b, hubFictif(), {}); const p = N.detailLigne(b, "s" + T0, "1.6"); assert.ok(p.includes("Pompes — série | répétitions | durée | repos avant |") && p.includes("Notes :") && p.includes("Sensation : Très facile"), p);
  const c = N.detailLigne(b, "carnet-0"); assert.ok(c.includes("(Carnet)") && c.split("\n").filter(x => /^\d+ \| /.test(x)).length === 4, c); assert.ok(c.includes("2 | 10 | 7 s | 1:06 |"), c);
  assert.strictEqual(N.detailLigne(b, "inconnu"), ""); });

/* ----- GPS (étape 4) : un enregistrement comme l'écrit le service Android ----- */
function courseGps(opt) { opt = opt || {}; const debut = Date.UTC(2026, 10, 14, 1, 30, 0), duree = 1650, premier = opt.sansPosition ? -1 : 8, v = 3650 / (duree - 8);   // 14/11/2026 05:30 à La Réunion, 27:30
  const dA = (sec) => premier < 0 ? 0 : Math.max(0, Math.min(3650, (sec - 8) * v)), prof = [], trace = [];
  for (let sec = 0; sec <= duree; sec += 5) { prof.push(Math.round(dA(sec))); if (sec < 8 || premier < 0) trace.push(0, 0); else trace.push(Math.round((-20.98 + dA(sec) * 0.6 / 110574) * 1e5), Math.round((55.70 + dA(sec) * 0.8 / 103900) * 1e5)); }
  const l = []; if (!opt.sansCeinture) for (let sec = 0; sec < duree; sec++) l.push([sec, 120 + Math.floor(sec / 60), 300, 900, sec % 2]);
  const fcs = l.map(x => x[1]);
  return { debut, act: "footing", res: { duree: l.length, actif: l.length, pics: l.reduce((a, x) => a + x[4], 0), fcMoy: l.length ? Math.round(fcs.reduce((a, x) => a + x, 0) / l.length) : null, fcMax: l.length ? Math.max(...fcs) : null }, l, coupures: 0, pics: [], deconnexions: [],
    fin: debut + duree * 1000, source: "APK 1.7", gps: { dist: premier < 0 ? 0 : 3650, distP: premier < 0 ? 0 : 3610, duree, premier, dernier: premier < 0 ? -1 : duree - 1, points: premier < 0 ? 40 : 1640, gardes: premier < 0 ? 0 : 1628, rejetes: premier < 0 ? 40 : 12, sauts: 0, precMoy: premier < 0 ? null : "4.2",
      reprises: 0, source: "sat", fichier: "gps-" + debut + ".txt", km: premier < 0 ? [] : [1, 2, 3].map(k => Math.round(8 + k * 1000 / v)), tours: premier < 0 || opt.sansTours ? [] : [[300, Math.round(dA(300)), 126], [900, Math.round(dA(900)), 137]], pas: 5, prof, trace } }; }

test("GPS : une course devient une ligne avec distance et allure ; kilomètres et tours calculés", () => {
  const b = N.baseVide(), R = courseGps(); assert.ok(N.ajouterEnregistrement(b, R)); const x = N.lignesHistorique(b).find(l => l.t === R.debut);
  assert.strictEqual(x.act, "footing"); assert.strictEqual(x.duree, 1650); assert.strictEqual(x.dist, 3650); assert.strictEqual(x.resultat, "3,65 km · 7:30 /km · ❤️ 133 / 147 · 825 impacts"); assert.strictEqual(x.alerte, "");
  assert.strictEqual(N.totaux(b).dist, 3650); assert.ok(x.detail[0].startsWith("05:57 — 🛰 3,65 km en 27:22 (7:30 /km) — 📡 H10 : 27 min"), x.detail[0]);
  assert.ok(x.detail.some(d => d.startsWith("Par km : 7:30 · 7:30 · 7:30 · reste 650 m en 4:52")), x.detail.join(" / ")); assert.ok(x.detail.some(d => d.startsWith("Mes tours : 1) 0,65 km en 4:52 · 2) 1,33 km en 10:00 · 3) 1,67 km en 12:30")), x.detail.join(" / "));
  const g = N.gpsBilan(R); assert.strictEqual(g.kms.length, 4); assert.deepStrictEqual(g.kms.map(k => k.t), [450, 450, 450, 292]); assert.strictEqual(g.kms[3].partiel, true); assert.strictEqual(g.kms[0].fcMoy, 123); assert.strictEqual(g.kms[0].impacts, 225);
  assert.deepStrictEqual(g.tours.map(t => [t.n, t.debut, t.t, t.d]), [[1, 8, 292, 649], [2, 300, 600, 1334], [3, 900, 750, 1667]]); assert.strictEqual(g.tours[2].fin, true); assert.strictEqual(g.tours[0].fcAppui, 126); assert.strictEqual(g.attente, 8); assert.strictEqual(g.temps, 1642);
  assert.ok(Math.abs(N.gpsDistA(R.gps, 908) - 2000.7) < 3 && N.gpsDistA(R.gps, 0) === 0 && N.gpsDistA(R.gps, 5000) === 3650);
  const svg = N.traceSvg(R.gps); assert.ok(svg.startsWith("<svg") && svg.includes("<polyline") && (svg.match(/<circle/g) || []).length === 4 && (svg.match(/<path/g) || []).length === 2, svg.slice(0, 200)); });

test("GPS : le détail à copier donne les tours, le km par km et la distance minute par minute", () => {
  const b = N.baseVide(), R = courseGps(); N.ajouterEnregistrement(b, R); const d = N.detailLigne(b, N.lignesHistorique(b).find(l => l.t === R.debut).id, "1.7"), L = d.split("\n");
  assert.strictEqual(L[0], "DÉTAIL — Course — 14/11/2026 05:30 (APK 1.7)"); assert.ok(L.includes("GPS : 3,65 km en 27:22 · allure moyenne 7:30 /km · attente du GPS au départ 8 s"), d);
  assert.ok(L.some(x => x.startsWith("Qualité du GPS : précision moyenne 4,2 m · 1 640 points, 12 écartés, 0 saut · satellites seuls · distance calculée par la vitesse du GPS (calcul de la version 1.7) · par les positions seules : 3,61 km")), d);
  assert.ok(L.includes("Tour | Départ | Temps | Distance | Allure | FC moy | FC max | Impacts") && L.includes("1 | 05:30:08 | 4:52 | 0,65 km | 7:30 /km | 122 | 124 | 146") && L.includes("3 (fin) | 05:45:00 | 12:30 | 1,67 km | 7:30 /km | 141 | 147 | 375"), d);
  assert.ok(L.includes("Km | Départ | Temps | Allure | FC moy | FC max | Impacts") && L.includes("1 | 05:30:08 | 7:30 | 7:30 /km | 123 | 127 | 225") && L.includes("reste 650 m | 05:52:38 | 4:52 | 7:29 /km | 145 | 147 | 146"), d);
  assert.ok(L.includes("Minute | Distance (m) | Allure | FC moy | FC max | Impacts | Mouvement moy (g) | Force max (g) | Secondes") && L.includes("05:31 | 133 | 7:31 /km | 121 | 121 | 30 | 0,30 | 1,9 | 60"), d);
  assert.ok(L.includes("05:30 | 116 | 8:37 /km | 120 | 120 | 30 | 0,30 | 1,9 | 60"), "la première minute compte l'attente du GPS"); assert.strictEqual(L.filter(x => /^0\d:\d\d \| /.test(x)).length, 28);
  const r = N.rapportEnregistrement(R, null, "1.7"); assert.ok(r.resume.startsWith("ENREGISTREMENT GPS + H10 — Course — 14/11/2026 05:30 (APK 1.7)\nGPS : 3,65 km en 27:22"), r.resume); assert.ok(r.tout.includes("Km par km :") && r.tout.includes("Mes tours (bouton Tour) :")); assert.strictEqual(r.bilan.gps.dist, 3650); });

test("GPS : course sans ceinture, course sans tours, GPS resté muet, anciens enregistrements inchangés", () => {
  const b = N.baseVide(), S = courseGps({ sansCeinture: true, sansTours: true }); N.ajouterEnregistrement(b, S); const x = N.lignesHistorique(b)[0];
  assert.strictEqual(x.resultat, "3,65 km · 7:30 /km"); assert.strictEqual(x.duree, 1650); assert.strictEqual(x.alerte, ""); assert.ok(x.detail[0].endsWith("🛰 3,65 km en 27:22 (7:30 /km)"), x.detail[0]); assert.ok(!x.detail.some(d => d.startsWith("Mes tours")));
  const d = N.detailLigne(b, x.id, "1.7"); assert.ok(d.includes("Sans ceinture : pas de cardio ni de mouvement.") && !d.includes("Trous") && !d.includes("Mes tours") && d.includes("05:31 | 133 | 7:31 /km | – | – | 0 | – | – | 0"), d);
  const rs = N.rapportEnregistrement(S, null, "1.7"); assert.ok(rs.resume.startsWith("ENREGISTREMENT GPS — Course") && rs.resume.includes("Durée : 27:30 · sans ceinture"), rs.resume); assert.strictEqual(rs.bilan.sansCeinture, true);
  const b2 = N.baseVide(), M = courseGps({ sansPosition: true }); N.ajouterEnregistrement(b2, M); const y = N.lignesHistorique(b2)[0];
  assert.ok(y.resultat.startsWith("GPS : aucune position · ❤️ 133 / 147"), y.resultat); assert.strictEqual(y.dist, 0); assert.strictEqual(N.traceSvg(M.gps), ""); assert.ok(N.detailLigne(b2, y.id).includes("GPS : aucune position reçue pendant la séance"));
  assert.ok(N.detailLigne(b2, y.id).includes("Minute | FC moy | FC max"), "sans position, le tableau reste celui de la ceinture");
  const b3 = N.baseVide(); N.importer(b3, hubFictif(), {}); const t = N.lignesHistorique(b3).find(l => l.act === "tennis"); assert.ok(t.resultat.startsWith("H10 · 4 impacts"), t.resultat); assert.strictEqual(t.dist, 0); assert.strictEqual(N.gpsBilan(t.ref.n.h10rec), null); });

test("version 1.9 : distance par les positions, réglage des impacts par activité, écrit dans la séance", () => {
  const b = N.baseVide(), R = courseGps(); delete R.gps.distP; R.gps.distV = 3520; R.gps.methode = "positions"; R.gps.tremble = "0.10"; R.reglage = { seuil: 1800, rearme: 1480, ecart: 250 }; N.ajouterEnregistrement(b, R);
  const g = N.gpsBilan(R); assert.strictEqual(g.methode, "positions"); assert.deepStrictEqual(g.autre, { nom: "par la vitesse du GPS", d: 3520 });
  const d = N.detailLigne(b, N.lignesHistorique(b)[0].id, "1.9"); assert.ok(d.includes("distance calculée par les positions · par la vitesse du GPS : 3,52 km"), d); assert.ok(d.includes("825 impacts (réglage 1,8 g · 0,25 s)"), d);
  assert.ok(N.rapportEnregistrement(R, N.impLire(b), "1.9").resume.includes("Impacts : 825 (réglage 1,8 g · 0,25 s)"));
  b.reglages["impact-reglage"] = JSON.stringify({ seuil: 1838, rearme: 1503, bip: true });
  assert.deepStrictEqual(N.impPour(b, "footing"), { seuil: 1800, rearme: 1480, ecart: 250, bip: false, auto: true }); assert.deepStrictEqual(N.impPour(b, "marche"), { seuil: 1250, rearme: 1150, ecart: 300, bip: false, auto: true });
  assert.deepStrictEqual(N.impPour(b, "tennis"), { seuil: 1838, rearme: 1503, ecart: 400, bip: true, auto: false }); assert.deepStrictEqual(N.impPour(b, "velo"), N.impPour(b, "tennis"));
  const vieux = courseGps(); assert.strictEqual(N.impTexte(vieux, N.impLire(b)), "1,8 g"); assert.strictEqual(N.impTexte(vieux, null), ""); assert.strictEqual(N.gpsBilan(vieux).methode, "vitesse"); });

test("fantôme de course : choisir une course, fabriquer le fantôme, bilan à la fin et à chaque km", () => {
  const b = N.baseVide(), ref = courseGps({ sansTours: true }); N.ajouterEnregistrement(b, ref); assert.strictEqual(N.fantPour(b, "footing"), null); assert.strictEqual(N.fantLire(b).rythme, "m500");
  N.fantChoisir(b, "footing", ref.debut); assert.strictEqual(N.fantPour(b, "footing").debut, ref.debut); assert.strictEqual(N.fantPour(b, "marche"), null); assert.deepStrictEqual(JSON.parse(b.reglages["fantome-opt"]), { refs: { footing: ref.debut }, rythme: "m500" });
  assert.strictEqual(N.fantRythme(b).rythme, "km1"); assert.strictEqual(N.rythmeTxt("km1"), "à chaque km"); N.fantRythme(b); N.fantRythme(b); N.fantRythme(b); assert.strictEqual(N.fantRythme(b).rythme, "m500");
  const F = N.fantFabriquer(ref); assert.deepStrictEqual([F.ref, F.nom, F.premier, F.duree, F.dist, F.pas, F.recalcule, F.prof.length], [ref.debut, "Course du 14/11/2026 05:30", 8, 1650, 3650, 5, false, 331]);
  const recalc = Object.assign({}, ref.gps, { dist: 3780, prof: ref.gps.prof.map(d => Math.round(d * 3780 / 3650)) }), F2 = N.fantFabriquer(ref, recalc); assert.strictEqual(F2.dist, 3780); assert.strictEqual(F2.recalcule, true);
  assert.strictEqual(N.fantFabriquer(courseGps({ sansPosition: true })), null); assert.strictEqual(N.fantTotal(F), 3650); assert.strictEqual(N.fantTemps(F), 1642); assert.ok(Math.abs(N.fantDistA(F, 821) - 1825) < 3); assert.ok(Math.abs(N.fantTempsA(F, 1825) - 821) < 2); assert.strictEqual(N.fantTempsA(F, 9999), 1642); assert.strictEqual(N.fantDistA(F, 5000), 3650);
  /* une nouvelle course, 5 % plus rapide que le fantôme, sur la même distance */
  const moi = courseGps({ sansTours: true }); moi.debut += 86400000; moi.fin = moi.debut + 1572000; const v = 3650 / 1564; moi.gps.duree = 1572; moi.gps.km = [1, 2, 3].map(k => Math.round(8 + k * 1000 / v)); moi.gps.prof = []; for (let sec = 0; sec <= 1572; sec += 5) moi.gps.prof.push(Math.round(Math.max(0, Math.min(3650, (sec - 8) * v)))); moi.l = moi.l.slice(0, 1572); moi.gps.fantome = F;
  const fb = N.fantBilan(moi); assert.strictEqual(fb.complet, true); assert.ok(Math.abs(fb.s - 78) <= 2, "78 s d'avance : " + fb.s); assert.ok(Math.abs(fb.m - 173) <= 5, "173 m d'avance à l'arrivée : " + fb.m); assert.strictEqual(fb.kms.length, 3); assert.ok(Math.abs(fb.kms[0].ecart - 21) <= 2 && Math.abs(fb.kms[2].ecart - 64) <= 2, JSON.stringify(fb.kms));
  assert.ok(/^Sur la distance du fantôme \(3,65 km\) : 17\d m d'avance \(1:1\d\)\.$/.test(fb.texte), fb.texte); N.ajouterEnregistrement(b, moi); const d = N.detailLigne(b, N.lignesHistorique(b).find(l => l.t === moi.debut).id, "1.10");
  assert.ok(d.includes("👻 Fantôme : Course du 14/11/2026 05:30 — 3,65 km en 27:22 (distance d'origine, tracé complet absent)") && d.includes("Km | Moi | Fantôme | Écart") && /1 \| 7:0\d \| 7:30 \| \+2\d s d'avance/.test(d), d);
  assert.ok(N.rapportEnregistrement(moi, null, "1.10").resume.includes("👻 Fantôme (Course du 14/11/2026 05:30) : Sur la distance du fantôme"));
  /* arrêt avant la fin du fantôme, en retard */
  const court = courseGps({ sansTours: true }); court.gps.duree = 608; court.gps.dist = 1200; court.gps.km = [508]; court.gps.prof = []; for (let sec = 0; sec <= 608; sec += 5) court.gps.prof.push(Math.max(0, (sec - 8) * 2)); court.gps.fantome = F;
  const fc = N.fantBilan(court); assert.strictEqual(fc.complet, false); assert.ok(Math.abs(fc.m + 134) <= 3 && Math.abs(fc.s + 60) <= 2, fc.m + " m, " + fc.s + " s"); assert.ok(fc.texte.startsWith("À ton arrêt (1,20 km sur les 3,65 km du fantôme) : 13"), fc.texte); assert.strictEqual(N.fantBilan(ref), null);
  assert.strictEqual(N.ecartTxt(3, 1), "à égalité"); assert.strictEqual(N.ecartTxt(-52, -12), "52 m de retard (12 s)");
  /* la course de référence supprimée : plus de fantôme */
  N.supprimerLigne(b, N.lignesHistorique(b).find(l => l.t === ref.debut).id, Date.now()); assert.strictEqual(N.fantPour(b, "footing"), null); });

test("fantôme des pompes : refaire une séance passée, série par série (cible, repos, annonces, bilan)", () => {
  const b = N.baseVide(), T0 = Date.UTC(2026, 10, 10, 2, 0, 0); let t = T0;
  const serie = (st, n) => { for (let i = 0; i < n; i++) { t += 1500; N.repsAppui(st, t, 120); } t += 500; N.repsFinSerie(st, t, 130); N.repsDiff(st, 2); }, repos = (sec) => { t += sec * 1000 - 2000; };
  /* la séance de référence : 12, 10, 8 pompes, repos d'environ 1 min puis 1 min 30 */
  const ref = N.repsNouveau(b, "pompes", ["Pompes"], t); assert.strictEqual(ref.blocs[0].fant, null); serie(ref, 12); repos(60); serie(ref, 10); repos(90); serie(ref, 8); N.fusionner(b, { courses: [N.repsSeance(ref, t)], reglages: {} });
  const ligne = N.lignesHistorique(b).find(l => l.t === T0); assert.deepStrictEqual(N.fantRepsDe(ligne).map(x => x.exo), ["Pompes"]); assert.strictEqual(N.fantRepsPour(b, "Pompes"), null);
  N.fantRepsChoisir(b, "Pompes", ligne.id); const f = N.fantRepsPour(b, "Pompes"); assert.strictEqual(f.nom, "Pompes du 10/11/2026 06:00"); assert.deepStrictEqual(f.series, [12, 10, 8]); assert.deepStrictEqual(f.repos, [60, 90, null]);
  assert.deepStrictEqual(JSON.parse(b.reglages["fantome-opt"]).reps, { Pompes: { id: ligne.id } }); assert.strictEqual(N.fantRepsPour(b, "Squats"), null);
  /* nouvelle séance contre ce fantôme */
  t = T0 + 86400000; const st = N.repsNouveau(b, "pompes", ["Pompes"], t), bl = st.blocs[0]; assert.deepStrictEqual(bl.fant.series, [12, 10, 8]);
  let e = N.fantRepsEtat(bl); assert.deepStrictEqual([e.serie, e.cible, e.ecart, e.moi, e.lui, e.series, e.fini, e.reposCible], [1, 12, 0, 0, 30, 3, false, null]); assert.strictEqual(N.fantRepsAnnonce(bl), "");
  for (let i = 0; i < 5; i++) { t += 1500; N.repsAppui(st, t, 120); } e = N.fantRepsEtat(bl); assert.deepStrictEqual([e.serie, e.cible, e.enCours, e.moi, e.ecart], [1, 12, 5, 5, 0]);
  for (let i = 0; i < 8; i++) { t += 1500; N.repsAppui(st, t, 120); } t += 500; N.repsFinSerie(st, t, 130); assert.strictEqual(N.fantRepsAnnonce(bl), "13. 1 de plus que le fantôme. Au total, 1 d'avance.");
  e = N.fantRepsEtat(bl); assert.deepStrictEqual([e.serie, e.cible, e.ecart, e.reposCible], [2, 10, 1, 60]); let r = N.repsRepos(st, t + 20000); assert.deepStrictEqual([r.cible, r.fantome, Math.round(r.reste)], [60, true, 40], "le repos cible est celui du fantôme, pas les 90 s du réglage");
  assert.strictEqual(N.repsRepos(st, t + 51000).signal, "bientot"); assert.strictEqual(N.repsRepos(st, t + 60500).signal, "reprendre"); N.repsDiff(st, 1); repos(62);
  serie(st, 9); assert.strictEqual(N.fantRepsAnnonce(bl), "9. 1 de moins que le fantôme. Au total, égalité."); assert.strictEqual(N.repsRepos(st, t + 1000).cible, 90); repos(90);
  serie(st, 8); assert.strictEqual(N.fantRepsAnnonce(bl), "8. Comme le fantôme. Au total, égalité. Le fantôme a fini."); e = N.fantRepsEtat(bl); assert.deepStrictEqual([e.cible, e.fini, e.reposCible], [null, true, null]);
  r = N.repsRepos(st, t + 1000); assert.deepStrictEqual([r.cible, r.fantome], [90, false], "après la dernière série du fantôme : le repos du réglage"); repos(60);
  serie(st, 5); assert.strictEqual(N.fantRepsAnnonce(bl), "5. Série en plus du fantôme. Au total, 5 d'avance.");
  const s2 = N.repsSeance(st, t), d = s2.notes[0].pompes; assert.deepStrictEqual(d.fantome, { nom: "Pompes du 10/11/2026 06:00", series: [12, 10, 8], repos: [60, 90, null] }); assert.deepStrictEqual(d.series, [13, 9, 8, 5]);
  const fb = N.fantRepsBilan(d); assert.strictEqual(fb.texte, "35 contre 30 : 5 de plus que le fantôme (4 séries contre 3)"); assert.deepStrictEqual(fb.lignes.map(l => [l.moi, l.lui, l.cumul]), [[13, 12, 1], [9, 10, 0], [8, 8, 0], [5, null, 5]]);
  const rap = N.rapportReps(s2, "1.11"); assert.ok(rap.resume.includes("👻 Pompes contre le fantôme (Pompes du 10/11/2026 06:00) : 35 contre 30 : 5 de plus que le fantôme"), rap.resume);
  assert.ok(rap.tout.includes("Série | Moi | Fantôme | Écart | Écart total") && rap.tout.includes("1 | 13 | 12 | +1 | +1") && rap.tout.includes("2 | 9 | 10 | −1 | =") && rap.tout.includes("4 | 5 | – | – | +5"), rap.tout);
  N.fusionner(b, { courses: [s2], reglages: {} }); assert.ok(N.detailLigne(b, N.lignesHistorique(b).find(l => l.t === s2.debut).id, "1.11").includes("👻 Fantôme : Pompes du 10/11/2026 06:00"));
  assert.strictEqual(N.fantRepsBilan(N.repsSeance(ref, t).notes[0].pompes), null, "une séance sans fantôme n'a pas de bilan fantôme");
  /* moins bien que le fantôme, et arrêt avant la fin */
  const st3 = N.repsNouveau(b, "pompes", ["Pompes"], t + 1e6); t += 1e6; serie(st3, 10); assert.strictEqual(N.fantRepsAnnonce(st3.blocs[0]), "10. 2 de moins que le fantôme. Au total, 2 de retard.");
  assert.strictEqual(N.fantRepsBilan(N.repsSeance(st3, t).notes[0].pompes).texte, "10 contre 30 : 20 de moins que le fantôme (1 série contre 3)");
  /* une séance du Carnet et la journée du 30/08/2025 peuvent servir de fantôme ; un exercice de Muscu aussi */
  const carnet = N.lignesHistorique(b).find(l => l.id === "carnet-0"); N.fantRepsChoisir(b, "Pompes", "carnet-0"); const fc = N.fantRepsPour(b, "Pompes"); assert.ok(fc.series.length >= 2 && fc.series.reduce((a, x) => a + x, 0) === carnet.pompes && fc.nom.startsWith("Pompes du "), JSON.stringify(fc));
  N.fantRepsChoisir(b, "Pompes", "j3008"); const fj = N.fantRepsPour(b, "Pompes"); assert.strictEqual(fj.series.reduce((a, x) => a + x, 0), 889); assert.ok(fj.repos.every(x => x == null || x <= 600), "les longues pauses de la journée ne deviennent pas un repos cible");
  const m = N.repsNouveau(b, "muscu", ["Squats", "Biceps barre"], T0 + 3e8); t = T0 + 3e8; serie(m, 15); repos(45); serie(m, 12); N.repsSuivant(m, t, 120, b, null); serie(m, 10); N.fusionner(b, { courses: [N.repsSeance(m, t)], reglages: {} });
  const lm = N.lignesHistorique(b).find(l => l.t === T0 + 3e8); assert.deepStrictEqual(N.fantRepsDe(lm).map(x => x.exo), ["Squats", "Biceps barre"]); N.fantRepsChoisir(b, "Squats", lm.id);
  assert.deepStrictEqual(N.fantRepsPour(b, "Squats").series, [15, 12]); assert.deepStrictEqual(N.fantRepsPour(b, "Squats").repos, [45, null]); const m2 = N.repsNouveau(b, "muscu", ["Squats", "Biceps barre"], t + 1e6); assert.deepStrictEqual([!!m2.blocs[0].fant, !!m2.blocs[1].fant], [true, false]);
  /* retirer, ou supprimer la séance d'origine : plus de fantôme */
  N.fantRepsChoisir(b, "Pompes", ligne.id); assert.ok(N.fantRepsPour(b, "Pompes")); N.supprimerLigne(b, ligne.id, Date.now()); assert.strictEqual(N.fantRepsPour(b, "Pompes"), null); N.fantRepsChoisir(b, "Squats", null); assert.strictEqual(N.fantRepsPour(b, "Squats"), null);
  assert.strictEqual(N.signe(3), "+3"); assert.strictEqual(N.signe(-2), "−2"); assert.strictEqual(N.signe(0), "="); });

test("GPS : réglages (activités avec GPS, source, vibration au km) gardés dans la base", () => {
  const b = N.baseVide(); assert.deepStrictEqual(N.gpsLire(b), { acts: ["footing", "marche", "velo"], source: "sat", km: true }); assert.ok(N.gpsPour(b, "footing") && !N.gpsPour(b, "tennis"));
  N.gpsBasculer(b, "tennis"); N.gpsBasculer(b, "velo"); assert.deepStrictEqual(N.gpsLire(b).acts, ["footing", "marche", "tennis"]); const o = N.gpsLire(b); o.source = "mix"; o.km = false; N.gpsEcrire(b, o);
  assert.deepStrictEqual(JSON.parse(b.reglages["gps-opt"]), { acts: ["footing", "marche", "tennis"], source: "mix", km: false }); b.reglages["gps-opt"] = "abîmé"; assert.deepStrictEqual(N.gpsLire(b).acts, ["footing", "marche", "velo"]);
  assert.strictEqual(N.kmTxt(3652), "3,65 km"); assert.strictEqual(N.allureTxt(1000, 452), "7:32 /km"); assert.strictEqual(N.allureTxt(5, 60), "–"); assert.strictEqual(N.allureVit(3.2), "5:13 /km"); assert.strictEqual(N.allureVit(0.1), "–"); assert.strictEqual(N.allureVit(null), "–"); });

console.log("\n" + n + " tests réussis.");
