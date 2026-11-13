/* Journal d'activité (APK) — le cœur, sans écran : données, import du Hub, fusion, descriptif, lignes de l'historique.
   Chargé par index.html, et testé seul par tests/test-noyau.js (node).
   Règle n° 1 : aucune donnée perdue. Une fusion n'écrase jamais rien, et dit tout ce qu'elle a ajouté. */
(function (g) {
"use strict";
const Integre = (typeof module !== "undefined" && module.exports) ? require("./donnees-integrees.js") : g.DonneesIntegrees;

const FORMAT = "journal-activite";
const CLE_COURSES = "footing-courses";
/* clés du Journal dans le Hub (v31) */
const CLES_HUB = ["footing-courses", "act-courante", "acts-perso", "acts-masquees", "pompes-journee", "pompes-jour-opt", "pompes-opt", "pompes-encours",
  "pompes-det-niveau", "pompes-det-log", "h10-rec", "impact-reglage", "impact-essais", "fant-opt", "frac-opt"];
const CLES_LISTES = ["acts-perso", "acts-masquees", "pompes-det-log", "impact-essais"];   // listes : on complète
const CLES_EN_COURS = ["pompes-journee", "pompes-encours", "h10-rec"];                    // séance non terminée dans le Hub

/* ===== Activités ===== */
const ACTS = [["footing", "🏃", "Course"], ["tennis", "🎾", "Tennis"], ["pompes", "💪", "Pompes"], ["muscu", "🏋️", "Muscu"], ["velo", "🚴", "Vélo"], ["marche", "🚶", "Marche"]];
function actInfo(a) { const x = ACTS.find(y => y[0] === a); if (x) return { id: a, icone: x[1], nom: x[2] };
  if (a === "chantier") return { id: a, icone: "🏗️", nom: "Chantier" }; return { id: a, icone: "✳️", nom: String(a || "Activité") }; }
function actNom(a) { const i = actInfo(a); return i.icone + " " + i.nom; }
function lireListe(v) { try { const l = JSON.parse(v || "[]"); return Array.isArray(l) ? l : []; } catch (e) { return []; } }
function toutesActs(base) { const l = ACTS.map(x => x[0]); lireListe(base.reglages["acts-perso"]).forEach(a => { if (typeof a === "string" && a && !l.includes(a)) l.push(a); }); return l; }
function actsVisibles(base) { const m = lireListe(base.reglages["acts-masquees"]); return toutesActs(base).filter(a => !m.includes(a)); }

/* ===== Dates et durées : toujours à l'heure de La Réunion ===== */
const TZ = "Indian/Reunion";
const f = (o) => new Intl.DateTimeFormat("fr-FR", Object.assign({ timeZone: TZ }, o));
const F_JOUR = f({ weekday: "short", day: "2-digit", month: "2-digit" }), F_JOURAN = f({ weekday: "short", day: "2-digit", month: "2-digit", year: "2-digit" });
const F_DATE = f({ day: "2-digit", month: "2-digit", year: "numeric" }), F_H = f({ hour: "2-digit", minute: "2-digit", hourCycle: "h23" }), F_HS = f({ hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23" });
const F_AN = f({ year: "numeric" });
const annee = (t) => F_AN.format(new Date(t));
function jourCourt(t, ref) { return annee(t) === annee(ref == null ? Date.now() : ref) ? F_JOUR.format(new Date(t)) : F_JOURAN.format(new Date(t)); }
const dateFr = (t) => F_DATE.format(new Date(t));
const heure = (t) => F_H.format(new Date(t));
const heureS = (t) => F_HS.format(new Date(t));
const dateHeure = (t) => dateFr(t) + " " + heure(t);
function horodatage(t) { const p = {}; f({ year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hourCycle: "h23" }).formatToParts(new Date(t)).forEach(x => p[x.type] = x.value);
  return p.year + "-" + p.month + "-" + p.day + "_" + p.hour + "h" + p.minute; }
const reunion = (an, mois, jour, h, min) => Date.UTC(an, mois - 1, jour, h - 4, min);   // La Réunion = UTC+4, sans heure d'été
const gm = (s) => { s = Math.round(s); return Math.floor(s / 60) + ":" + String(s % 60).padStart(2, "0"); };
const hm = (s) => { s = Math.max(0, Math.round(s)); const h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60); return h ? h + " h " + String(m).padStart(2, "0") : m + " min"; };
const s1 = (ms) => (ms / 1000).toFixed(1).replace(".", ",");
const mmss = (ms) => { const s = ms / 1000, m = Math.floor(s / 60); return m + ":" + (s % 60).toFixed(1).padStart(4, "0").replace(".", ","); };
const v1 = (x) => x.toFixed(1).replace(".", ",");
const nb = (n) => String(n).replace(/\B(?=(\d{3})+(?!\d))/g, " ");
const pl = (n, mot, plur) => n + " " + (n > 1 ? (plur || mot + "s") : mot);

/* ===== Libellé d'une note (repris de la v31) ===== */
const NOMS5 = { 1: "Très facile", 2: "Facile", 3: "Normal", 4: "J'accélère", 5: "À fond" };
const DIFF = ["😌 Très facile", "🙂 Facile", "😐 Moyen", "😤 Difficile", "🥵 Très difficile"];
function libelle(x) {
  if (x.frac) { const q = x.frac; return "⏱ Répét. " + q.rep + " — " + (q.mode === "dist" ? q.cible + " m en " + mmss(q.temps) : s1(q.temps) + " s, " + Math.round(q.dist) + " m") +
    (q.dur ? " — dur à " + s1(q.dur.t) + " s / " + Math.round(q.dur.d) + " m" : " — bonnes sensations") +
    (q.acc ? " — accélère à " + s1(q.acc.t) + " s / " + Math.round(q.acc.d) + " m" + (q.dur ? "" : " (double appui)") : q.dur ? " — n'a pas pu réaccélérer" : "") +
    (q.rpe ? " — ressenti " + q.rpe : "") + (q.fcMax ? " — FC moy " + q.fcMoy + " / max " + q.fcMax : ""); }
  if (x.tr) return "🧭 " + x.tr + (x.sens ? " — " + x.sens : "") + (x.dom ? " — " + x.dom : "") + (x.zone ? " (" + x.zone + ")" : "");
  if (x.boire) return "💧 Boisson : " + x.boire; if (x.manger) return "🍎 Manger : " + x.manger; if (x.arret) return "⏸ Arrêt : " + x.arret;
  if (x.douleur) return "⚠️ Douleur : " + x.douleur; if (x.sensLibre) return "Sensation : " + x.sensLibre;
  if (x.h10rec) { const R = x.h10rec, r = R.res || {}, g = gpsBilan(R), avecH10 = (R.l || []).length > 0;
    return (g ? "🛰 " + (g.ok ? kmTxt(g.dist) + " en " + chrono(g.temps) + " (" + g.allure + ")" : "GPS : aucune position reçue") + (avecH10 ? " — " : "") : "") +
      (avecH10 || !g ? "📡 H10 : " + hm(r.duree || 0) + ", actif " + hm(r.actif || 0) + ", " + (r.pics || 0) + " impacts" + (r.fcMoy ? ", ❤️ moy " + r.fcMoy + " / max " + r.fcMax : "") : ""); }
  if (x.pjour) return "📅 Journée pompes : " + x.pjour.res;
  if (x.reps) return "🏋️ " + x.reps.exo + " : " + x.reps.res + " (séries " + (x.reps.series || []).join("-") + (x.reps.reposMoy ? ", repos moyen " + gm(x.reps.reposMoy) : "") + ")";
  if (x.pompes && x.pompes.diffs) return "👻 Pompes : " + x.pompes.res + " — " + (x.pompes.series || []).map((n, i) => n + (x.pompes.diffs[i] != null && DIFF[x.pompes.diffs[i]] ? DIFF[x.pompes.diffs[i]].split(" ")[0] : "")).join(" ");
  if (x.pompes) return "👻 Pompes : " + x.pompes.res + " (séries " + (x.pompes.series || []).join("-") + (x.pompes.reposMoy ? ", repos moyen " + gm(x.pompes.reposMoy) : "") + ")";
  if (x.ghost) return "👻 Contre « " + x.ghost.contre + " » — " + x.ghost.res;
  if (x.fracDebut) return "⏱ Début fractionné : " + x.fracDebut; if (x.fracFin) return "⏱ Fin fractionné";
  if (x.n) return NOMS5[x.n] || String(x.n); if (x.km) return "📍 Km " + x.km; if (x.rep) return "📍 Repère " + x.rep;
  return (x.voix ? "🎤 " : "📝 ") + (x.note || ""); }

/* ===== Base de l'appli ===== */
function baseVide() { return { format: FORMAT, version: 1, courses: [], reglages: {}, imports: [], corbeille: [] }; }
function baseValide(b) { return !!b && typeof b === "object" && b.format === FORMAT && Array.isArray(b.courses) && !!b.reglages && typeof b.reglages === "object"; }
function normaliser(b) { if (!Array.isArray(b.imports)) b.imports = []; if (!Array.isArray(b.corbeille)) b.corbeille = []; b.courses.forEach(c => { if (!Array.isArray(c.notes)) c.notes = []; }); return b; }

/* écriture stable d'une valeur (clés triées) : sert à savoir si deux éléments sont identiques */
function stable(v) { if (v === null || typeof v !== "object") return JSON.stringify(v === undefined ? null : v);
  if (Array.isArray(v)) return "[" + v.map(stable).join(",") + "]";
  return "{" + Object.keys(v).sort().filter(k => v[k] !== undefined).map(k => JSON.stringify(k) + ":" + stable(v[k])).join(",") + "}"; }
const TYPES = ["pompes", "reps", "pjour", "h10rec", "ghost", "frac", "tr", "boire", "manger", "arret", "douleur", "sensLibre", "fracDebut", "fracFin", "n", "km", "rep", "note"];
function typeNote(n) { return TYPES.find(k => n[k] != null) || "autre"; }
const idSeance = (c) => String(c.id != null ? c.id : c.debut);

/* ===== Lecture d'une sauvegarde du Hub =====
   Le Journal n'a pas d'identifiant fixe dans le Hub : on le reconnaît à sa clé « footing-courses ». */
function extraireHub(sv) { const journaux = []; const don = sv.donnees || {};
  Object.keys(don).forEach(id => { const ls = (don[id] || {}).localStorage || {}; if (typeof ls[CLE_COURSES] !== "string") return;
    const a = (sv.applis || []).find(x => x.id === id) || {}; journaux.push({ id, nom: a.nom || id, version: a.version != null ? String(a.version) : "", localStorage: ls }); });
  return { type: "hub", creeeLe: sv.creeeLe || "", hub: sv.hub || "", journaux }; }

/* Transforme les données d'un Journal du Hub en « paquet » à fusionner.
   Décision de Frédéric (12/11/2026) : la tuile « Muscu » du Hub, c'était les pompes → ces séances passent en « Pompes »
   (l'activité d'origine reste notée dans actOrigine). */
function paquetDepuisHub(j) { const ls = j.localStorage || {}; let courses;
  try { courses = JSON.parse(ls[CLE_COURSES] || "[]"); } catch (e) { throw new Error("L'historique du Journal est illisible dans ce fichier."); }
  if (!Array.isArray(courses)) throw new Error("L'historique du Journal est illisible dans ce fichier.");
  let muscu = 0;
  courses.forEach(c => { if (c.id == null) c.id = c.debut; if (!Array.isArray(c.notes)) c.notes = [];
    if (c.act === "muscu") { c.act = "pompes"; c.actOrigine = "muscu"; muscu++; }
    c.notes.forEach(n => { if (n.h10rec && n.h10rec.act === "muscu") { n.h10rec.act = "pompes"; n.h10rec.actOrigine = "muscu"; } }); });
  const reglages = {}; Object.keys(ls).forEach(k => { if (k !== CLE_COURSES && typeof ls[k] === "string") reglages[k] = ls[k]; });
  if (reglages["act-courante"] === "muscu") reglages["act-courante"] = "pompes";
  return { courses, reglages, muscu }; }

/* ===== Fusion : ajoute ce qui manque, n'écrase jamais =====
   Séances reconnues par leur identifiant, notes par leur heure + leur type. Rejouable : un 2e import n'ajoute rien. */
function fusionner(base, paquet) {
  const b = { seances: { aj: 0, pres: 0 }, notes: { aj: 0, pres: 0, diff: 0 }, reglages: { aj: 0, pres: 0, completes: 0, diff: 0 }, seancesAj: [], notesAj: [], cles: [] };
  const index = new Map(); base.courses.forEach(c => index.set(idSeance(c), c));
  const jetees = clesCorbeille(base); b.corbeille = 0;   // ce que Frédéric a supprimé n'est jamais remis par une fusion
  (paquet.courses || []).forEach(c => { const cur = index.get(idSeance(c));
    if (!cur) { const copie = JSON.parse(JSON.stringify(c)); if (!Array.isArray(copie.notes)) copie.notes = [];
      const avant = copie.notes.length; copie.notes = copie.notes.filter(n => !jetees.has(cleCorbeille(idSeance(copie), n))); b.corbeille += avant - copie.notes.length; if (avant && !copie.notes.length) return;
      base.courses.push(copie); index.set(idSeance(copie), copie);
      b.seances.aj++; b.seancesAj.push(copie); copie.notes.forEach(n => { b.notes.aj++; b.notesAj.push({ c: copie, n }); }); return; }
    b.seances.pres++; let ajout = false;
    (c.notes || []).forEach(n => { if (jetees.has(cleCorbeille(idSeance(cur), n))) { b.corbeille++; return; } const memes = cur.notes.filter(x => x.t === n.t && typeNote(x) === typeNote(n));
      if (!memes.length) { const copie = JSON.parse(JSON.stringify(n)); cur.notes.push(copie); b.notes.aj++; b.notesAj.push({ c: cur, n: copie }); ajout = true; }
      else if (memes.some(x => stable(x) === stable(n))) b.notes.pres++;
      else { b.notes.pres++; b.notes.diff++; } });
    if (ajout) cur.notes.sort((x, y) => x.t - y.t);
    ["close", "actOrigine"].forEach(k => { if (cur[k] == null && c[k] != null) cur[k] = c[k]; }); });
  base.courses.sort((x, y) => (x.debut || 0) - (y.debut || 0));
  Object.keys(paquet.reglages || {}).forEach(k => { const v = paquet.reglages[k], cur = base.reglages[k];
    if (cur == null) { base.reglages[k] = v; b.reglages.aj++; b.cles.push([k, "ajouté"]); return; }
    if (cur === v) { b.reglages.pres++; b.cles.push([k, "déjà là"]); return; }
    if (CLES_LISTES.includes(k)) { let a, e; try { a = JSON.parse(cur); e = JSON.parse(v); } catch (x) { a = null; }
      if (Array.isArray(a) && Array.isArray(e)) { const vus = new Set(a.map(stable)); let n = 0; e.forEach(x => { if (!vus.has(stable(x))) { a.push(x); vus.add(stable(x)); n++; } });
        if (n) { base.reglages[k] = JSON.stringify(a); b.reglages.completes++; b.cles.push([k, "complété (+" + n + ")"]); } else { b.reglages.pres++; b.cles.push([k, "déjà là"]); } return; } }
    b.reglages.diff++; b.cles.push([k, "différent : valeur actuelle gardée"]); });
  return b; }

/* ===== Inventaire d'un lot de notes ===== */
function trousH10(R) { const l = R.l || [], t = []; let prec = 0; l.forEach(x => { if (x[0] - prec > 60) t.push({ a: prec, duree: x[0] - prec }); prec = x[0]; }); return t; }
function testsDe(n) { let k = 0; const compte = (x) => { if (x && x.polar != null) k++; };
  if (n.pompes && n.pompes.test) n.pompes.test.forEach(compte); if (n.pjour && n.pjour.seances) n.pjour.seances.forEach(z => (z.test || []).forEach(compte)); return k; }
function inventaire(seances, notes) { const r = { seances: seances.length, vides: seances.filter(c => !(c.notes || []).length), notes: notes.length, pompesN: 0, pompes: 0, journeesN: 0, journees: 0, h10: 0, h10Sec: 0, tests: 0, fantomes: 0, fracs: 0 };
  notes.forEach(({ n }) => {
    if (n.pompes) { r.pompesN++; r.pompes += n.pompes.total != null ? n.pompes.total : (n.pompes.tl || []).length; }
    if (n.pjour) { r.journeesN++; r.journees += n.pjour.total != null ? n.pjour.total : (n.pjour.tl || []).length; }
    if (n.h10rec) { r.h10++; r.h10Sec += (n.h10rec.l || []).length; }
    r.tests += testsDe(n); if (n.ghost) r.fantomes++; if (n.frac) r.fracs++; });
  return r; }
const paires = (courses) => [].concat(...courses.map(c => (c.notes || []).map(n => ({ c, n }))));

/* ===== Lignes de l'Historique =====
   Une ligne par séance. Un enregistrement H10 a sa propre ligne, rangée dans SON activité et à SON heure de début
   (dans la v31, il pouvait se retrouver dans la séance d'une autre activité). Les séances vides ne sont pas affichées. */
function seriesDe(tl) { const l = []; let c = null; (tl || []).forEach((t, i) => { if (!c || t - tl[i - 1] > 5) { c = []; l.push(c); } c.push(t); }); return l.map(x => x.length); }
function detailNote(n) { return heure(n.t) + " — " + libelle(n) + (n.fc ? " ❤️" + n.fc : ""); }
function ligneH10(c, n) { const R = n.h10rec, r = R.res || {}, tr = trousH10(R), total = tr.reduce((a, x) => a + x.duree, 0), g = gpsBilan(R), avecH10 = (R.l || []).length > 0;
  const res = g ? (g.ok ? [kmTxt(g.dist), g.allure] : ["GPS : aucune position"]) : ["H10", pl(r.pics || 0, "impact")]; if (r.fcMoy) res.push("❤️ " + r.fcMoy + " / " + r.fcMax); if (g && avecH10) res.push(pl(r.pics || 0, "impact"));
  const alerte = tr.length ? "⚠️ " + pl(tr.length, "trou") + " (" + Math.round(total / 60) + " min)" : (R.coupe ? "⚠️ enregistrement coupé" : "");
  const det = [detailNote(n)]; tr.forEach(x => det.push("⚠️ trou de " + v1(x.duree / 60) + " min à " + heure((R.debut || n.t) + x.a * 1000)));
  (R.deconnexions || []).forEach(x => det.push("ceinture perdue " + dureeCourte(x[1]) + " à " + heure((R.debut || n.t) + x[0] * 1000)));
  if (R.coupures) det.push(pl(R.coupures, "reprise") + " après fermeture de l'appli");
  if (g && g.ok) { if (g.kms.length) det.push("Par km : " + g.kms.map(k => (k.partiel ? "reste " + k.d + " m en " : "") + chrono(k.t)).join(" · ")); if (g.tours.length) det.push("Mes tours : " + g.tours.map(k => k.n + ") " + kmTxt(k.d) + " en " + chrono(k.t)).join(" · ")); }
  return { id: "h" + idSeance(c) + "-" + n.t, t: R.debut || n.t, act: R.act || c.act || "footing", duree: g ? Math.max(g.duree, r.duree || 0) : (r.duree || (R.l || []).length), pompes: 0, dist: g && g.ok ? g.dist : 0, resultat: res.join(" · "), alerte, source: "", detail: det, trous: tr, ref: { c, n } }; }
function ligneSeance(c, notes) { const act = c.act || "footing", t = c.debut || notes[0].t; let duree = Math.max(0, (notes[notes.length - 1].t - t) / 1000), pompes = 0, res;
  const P = notes.filter(n => n.pompes), J = notes.filter(n => n.pjour), G = notes.filter(n => n.ghost), X = notes.filter(n => n.reps);
  if (X.length) { /* séance de plusieurs exercices (enchaînement) : un résumé par exercice, dans l'ordre */
    const E = notes.filter(n => n.reps || n.pompes).map(n => n.reps || Object.assign({ exo: "Pompes" }, n.pompes)); pompes = P.reduce((a, n) => a + (n.pompes.total || 0), 0);
    const fin = notes[notes.length - 1].t, fcs = [].concat(...E.map(e => (e.fcs || []).map(x => x[1]).filter(Boolean)));
    return { id: "s" + idSeance(c), t, act, duree: Math.max(0, (fin - t) / 1000), pompes, resultat: E.map(e => e.exo + " " + nb(e.total) + chargeTxt(e, " (", ")")).join(" · ") + (fcs.length ? " · ❤️ max " + Math.max(...fcs) : ""), alerte: "", source: "", detail: notes.map(detailNote), ref: { c, notes } }; }
  if (J.length) { const j = J[0].pjour; pompes = J.reduce((a, n) => a + (n.pjour.total || 0), 0) + P.reduce((a, n) => a + (n.pompes.total || 0), 0);
    duree = (j.tl && j.tl.length) ? j.tl[j.tl.length - 1] : duree; res = nb(pompes) + " · " + pl((j.seances || j.offs || []).length, "séance") + " (journée)"; }
  else if (P.length) { pompes = P.reduce((a, n) => a + (n.pompes.total || 0), 0); duree = P.reduce((a, n) => a + (n.pompes.temps || 0), 0);
    const series = P.reduce((a, n) => a + (n.pompes.series || []).length, 0), fcs = [].concat(...P.map(n => (n.pompes.fcs || []).map(x => x[1]).filter(Boolean)));
    const m = [nb(pompes), pl(series, "série")]; if (P.length > 1) m.push(P.length + " séances"); else { if (P[0].pompes.reposMoy) m.push("repos " + gm(P[0].pompes.reposMoy)); const ch = chargeTxt(P[0].pompes, "+", ""); if (ch) m.push(ch); }
    if (fcs.length) m.push("❤️ max " + Math.max(...fcs)); res = m.join(" · "); }
  else if (G.length) { duree = G.reduce((a, n) => a + (n.ghost.temps || 0), 0); res = "👻 " + G[0].ghost.res; }
  else res = pl(notes.length, "note") + " · " + libelle(notes[0]);
  return { id: "s" + idSeance(c), t, act, duree, pompes, resultat: res, alerte: "", source: "", detail: notes.map(detailNote), ref: { c, notes } }; }
function lignesHistorique(base) { const l = [];
  base.courses.forEach(c => { const reste = []; (c.notes || []).forEach(n => { if (n.h10rec) l.push(ligneH10(c, n)); else reste.push(n); }); if (reste.length) l.push(ligneSeance(c, reste)); });
  Integre.CARNET_POMPES.forEach((x, i) => { const m = x.nom.match(/(\d\d)\/(\d\d) (\d\d):(\d\d)/); const sr = seriesDe(x.tl);
    l.push({ id: "carnet-" + i, t: m ? reunion(2026, Number(m[2]), Number(m[1]), Number(m[3]), Number(m[4])) : 0, act: "pompes", duree: x.tl[x.tl.length - 1], pompes: x.tl.length,
      resultat: nb(x.tl.length) + " · " + pl(sr.length, "série"), alerte: "", source: "Carnet", detail: ["Séance reprise du Carnet d'entraînement — séries " + sr.join("-")], ref: { tl: x.tl } }); });
  const J = Integre.JOUR_3008; l.push({ id: "j3008", t: reunion(2025, 8, 30, 6, 20), act: "pompes", duree: J.tl[J.tl.length - 1], pompes: J.tl.length, journee: true,
    resultat: nb(J.tl.length) + " · " + pl(J.offs.length, "séance"), alerte: "", source: "montre COROS",
    detail: ["Journée relevée sur la montre COROS — séances de " + J.cut.map((v, i) => v - (J.cut[i - 1] || 0)).join(" + ") + " pompes"], ref: { tl: J.tl } });
  return l; }
function totaux(base, lignes) { const l = lignes || lignesHistorique(base); return { lignes: l.length, pompes: l.reduce((a, x) => a + (x.pompes || 0), 0), dist: l.reduce((a, x) => a + (x.dist || 0), 0) }; }
function derniereParAct(lignes) { const d = {}; lignes.forEach(x => { if (!d[x.act] || x.t > d[x.act]) d[x.act] = x.t; }); return d; }

/* ===== Descriptif d'une fusion (écran + rapports à copier) ===== */
function descriptif(base, paquet, bilan, meta) { const tout = inventaire(paquet.courses || [], paires(paquet.courses || [])), aj = inventaire(bilan.seancesAj, bilan.notesAj);
  const plus = (n) => n ? "+" + nb(n) : "0", rows = [];
  rows.push(["Séances", plus(bilan.seances.aj), nb(bilan.seances.pres)]);
  rows.push(["Notes", plus(bilan.notes.aj), nb(bilan.notes.pres)]);
  if (tout.pompesN) rows.push(["Pompes (" + pl(tout.pompesN, "séance") + ")", plus(aj.pompes), nb(tout.pompes - aj.pompes)]);
  if (tout.journeesN) rows.push(["Pompes en journée (" + pl(tout.journeesN, "journée") + ")", plus(aj.journees), nb(tout.journees - aj.journees)]);
  if (tout.h10) rows.push(["Enregistrements H10 (" + hm(tout.h10Sec) + ")", plus(aj.h10), nb(tout.h10 - aj.h10)]);
  if (tout.tests) rows.push(["Séries de test TOI / H10", plus(aj.tests), nb(tout.tests - aj.tests)]);
  if (tout.fantomes) rows.push(["Courses contre le fantôme", plus(aj.fantomes), nb(tout.fantomes - aj.fantomes)]);
  if (tout.fracs) rows.push(["Répétitions de fractionné", plus(aj.fracs), nb(tout.fracs - aj.fracs)]);
  rows.push(["Réglages", plus(bilan.reglages.aj + bilan.reglages.completes), nb(bilan.reglages.pres + bilan.reglages.diff)]);
  const infos = [], debuts = (paquet.courses || []).map(c => c.debut).filter(Boolean);
  if (debuts.length) { infos.push(["Plus ancienne séance du fichier", dateHeure(Math.min(...debuts))]); infos.push(["Plus récente séance du fichier", dateHeure(Math.max(...debuts))]); }
  const par = {}; lignesHistorique({ courses: paquet.courses || [], reglages: {} }).filter(x => !x.source).forEach(x => par[x.act] = (par[x.act] || 0) + 1);
  if (Object.keys(par).length) infos.push(["Lignes par activité", Object.keys(par).map(a => actInfo(a).nom + " " + par[a]).join(" · ")]);
  const lg = lignesHistorique(base), tt = totaux(base, lg);
  infos.push(["Dans l'appli maintenant", pl(tt.lignes, "ligne") + " · " + nb(tt.pompes) + " pompes"]);
  const integre = [["Journée du 30/08/2025 (montre)", nb(Integre.JOUR_3008.tl.length) + " pompes"], [pl(Integre.CARNET_POMPES.length, "séance") + " du Carnet", nb(Integre.CARNET_POMPES.reduce((a, x) => a + x.tl.length, 0)) + " pompes"]];
  const obs = [];
  if (tout.vides.length) obs.push(pl(tout.vides.length, "séance vide", "séances vides") + " (" + tout.vides.map(c => dateHeure(c.debut)).join(", ") + ") : gardée" + (tout.vides.length > 1 ? "s" : "") + ", mais pas affichée" + (tout.vides.length > 1 ? "s" : "") + ".");
  if (paquet.muscu) obs.push(pl(paquet.muscu, "séance") + " « Muscu » du Hub rangée" + (paquet.muscu > 1 ? "s" : "") + " dans Pompes.");
  paires(paquet.courses || []).forEach(({ c, n }) => { if (!n.h10rec) return; const R = n.h10rec, a = R.act || c.act || "footing";
    if (a !== (c.act || "footing")) obs.push("L'enregistrement H10 du " + dateHeure(R.debut || n.t) + " était rangé dans « " + actInfo(c.actOrigine || c.act || "footing").nom + " » : affiché en " + actInfo(a).nom + ".");
    const tr = trousH10(R); if (tr.length) obs.push(actInfo(a).nom + " du " + dateHeure(R.debut || n.t) + " : " + pl(tr.length, "trou") + " dans l'enregistrement H10 (" + tr.map(x => v1(x.duree / 60)).join(" · ") + " min)."); });
  CLES_EN_COURS.forEach(k => { const v = (paquet.reglages || {})[k]; if (v && v !== "null") obs.push("Une séance n'était pas terminée dans le Hub (" + k + ") : gardée telle quelle."); });
  if (bilan.corbeille) obs.push(pl(bilan.corbeille, "élément du fichier que tu avais supprimé : laissé", "éléments du fichier que tu avais supprimés : laissés") + " dans la corbeille.");
  if (bilan.notes.diff) obs.push(pl(bilan.notes.diff, "note différente", "notes différentes") + " entre le fichier et l'appli : la valeur de l'appli est gardée.");
  if (bilan.reglages.diff) obs.push(pl(bilan.reglages.diff, "réglage différent", "réglages différents") + " : celui de l'appli est gardé.");
  const rien = !bilan.seances.aj && !bilan.notes.aj && !(bilan.reglages.aj + bilan.reglages.completes);
  const d = { t: Date.now(), source: meta.source || "", titre: rien ? "Rien à ajouter : tout était déjà là" : "Import terminé — rien n'a été écrasé", rien, rows, infos, integre, obs };
  /* rapports en texte, à coller à Claude */
  const L = ["IMPORT — Journal d'activité (APK" + (meta.version ? " " + meta.version : "") + ") — " + dateHeure(d.t), "Fichier : " + d.source, d.titre, "", "Élément | Ajouté | Déjà là"];
  rows.forEach(r => L.push(r.join(" | "))); L.push(""); infos.forEach(r => L.push(r[0] + " : " + r[1])); integre.forEach(r => L.push("Déjà dans l'appli — " + r[0] + " : " + r[1]));
  if (obs.length) { L.push("", "À savoir :"); obs.forEach(o => L.push("- " + o)); }
  d.resume = L.join("\n");
  const T = L.slice(); T.push("", "Réglages (clé : résultat) :"); bilan.cles.forEach(c => T.push("- " + c[0] + " : " + c[1]));
  T.push("", "Toutes les lignes de l'Historique après l'import :"); lg.slice().sort((x, y) => y.t - x.t).forEach(x => T.push(texteLigne(x)));
  d.tout = T.join("\n"); return d; }
function texteLigne(x) { return dateFr(x.t) + " " + heure(x.t) + " | " + actInfo(x.act).nom + (x.journee ? " (journée)" : "") + " | " + dureeTxt(x) + " | " + x.resultat + (x.source ? " (" + x.source + ")" : "") + (x.alerte ? " | " + x.alerte : ""); }
function dureeTxt(x) { return x.duree >= 3600 || x.act !== "pompes" || x.journee ? hm(x.duree) : gm(x.duree); }
function rapportHistorique(base, version) { const lg = lignesHistorique(base).sort((x, y) => y.t - x.t), tt = totaux(base, lg);
  return ["HISTORIQUE — Journal d'activité (APK" + (version ? " " + version : "") + ") — " + dateHeure(Date.now()), pl(tt.lignes, "ligne") + " · " + nb(tt.pompes) + " pompes", "", "Date | Activité | Durée | Résultat"].concat(lg.map(texteLigne)).join("\n"); }

/* ===== Enregistrements H10 faits par l'APK (étape 2) =====
   Le service Android écrit un fichier par séance (même forme que la note « h10rec » de la v31, plus les coupures
   de ceinture). La page l'ajoute à la base comme une séance : identifiant = heure de début, donc jamais de doublon. */
const dureeCourte = (s) => s < 60 ? Math.round(s) + " s" : hm(s);
const gg = (v) => v == null ? "–" : (v / 1000).toFixed(1).replace(".", ",");
function seanceDepuisEnregistrement(R) { const fin = R.fin || (R.debut + ((R.l || []).length ? (R.l[R.l.length - 1][0] + 1) * 1000 : 0));
  return { id: R.debut, debut: R.debut, act: R.act || "tennis", close: true, notes: [{ t: fin, h10rec: R }] }; }
function enregistrementValide(R) { return !!R && typeof R === "object" && typeof R.debut === "number" && Array.isArray(R.l) && !!R.res && typeof R.res === "object"; }
function ajouterEnregistrement(base, R) { if (!enregistrementValide(R)) throw new Error("Enregistrement illisible."); return fusionner(base, { courses: [seanceDepuisEnregistrement(R)], reglages: {} }).seances.aj > 0; }
/* noms de fichiers « rec-<début>.json » pas encore dans la base */
function enregistrementsManquants(base, noms) { const vus = new Set(base.courses.map(idSeance)); (base.corbeille || []).forEach(e => { if (e.notes.some(n => n.h10rec)) vus.add(idSeance(e.seance)); }); return (noms || []).filter(n => { const m = /^rec-(\d+)\.json$/.exec(n); return m && !vus.has(m[1]); }); }
function bilanEnregistrement(R) { const L = R.l || [], r = R.res || {}, ecoule = Math.max(L.length, Math.round(((R.fin || R.debut) - R.debut) / 1000)), tr = trousH10(R), de = R.deconnexions || [];
  return { ecoule, enregistre: L.length, perdu: Math.max(0, ecoule - L.length), actif: r.actif || 0, impacts: r.pics || 0, fcMoy: r.fcMoy, fcMax: r.fcMax, trous: tr, deconnexions: de, tempsDeconnecte: de.reduce((a, x) => a + x[1], 0),
    reprises: R.coupures || 0, mouvements: (R.pics || []).length, mouvementsComptes: (R.pics || []).filter(x => x[2]).length, sansMouvement: L.length > 0 && L.every(x => !x[2] && !x[3]), sansCeinture: L.length === 0, gps: gpsBilan(R) }; }
function rapportEnregistrement(R, imp, version) { const b = bilanEnregistrement(R), T = [], g = b.gps;
  T.push("ENREGISTREMENT " + (g ? (b.sansCeinture ? "GPS" : "GPS + H10") : "H10") + " — " + actInfo(R.act).nom + " — " + dateHeure(R.debut) + (version ? " (APK " + version + ")" : ""));
  if (g) lignesGps(T, g);
  T.push(b.sansCeinture && g ? "Durée : " + chrono(b.ecoule) + " · sans ceinture (pas de cardio ni de mouvement)" : "Durée : " + hm(b.ecoule) + " · enregistré " + hm(b.enregistre) + " · perdu " + dureeCourte(b.perdu));
  T.push("FC : moy " + (b.fcMoy || "–") + " / max " + (b.fcMax || "–"));
  T.push(b.sansCeinture && g ? "Mouvement : pas de ceinture" : b.sansMouvement ? "Mouvement : aucune mesure reçue (capteur de mouvement non actif)" : "Impacts : " + b.impacts + (imp ? " (réglage " + gg(imp.seuil) + " g)" : "") + " · mouvements nets : " + b.mouvements + " (comptés " + b.mouvementsComptes + ") · actif " + hm(b.actif));
  T.push("Coupures de la ceinture : " + b.deconnexions.length + (b.deconnexions.length ? " (" + dureeCourte(b.tempsDeconnecte) + " en tout)" : ""));
  T.push("Trous de plus d'une minute : " + b.trous.length + (b.trous.length ? " (" + b.trous.map(x => v1(x.duree / 60)).join(" · ") + " min)" : ""));
  T.push("Reprises après fermeture de l'appli : " + b.reprises);
  const resume = T.join("\n"), D = T.slice();
  if (g && g.ok) tablesGps(D, R, g);
  if (b.deconnexions.length) { D.push("", "Coupures de la ceinture :"); b.deconnexions.forEach(x => D.push("- " + heureS(R.debut + x[0] * 1000) + " pendant " + dureeCourte(x[1]))); }
  if (b.trous.length) { D.push("", "Trous :"); b.trous.forEach(x => D.push("- " + v1(x.duree / 60) + " min à partir de " + heure(R.debut + x.a * 1000))); }
  D.push("", "Par tranche de 10 minutes :", "Tranche | secondes | FC moy/max | mouvement moy | secondes > 1,5 g / > 2 g / > 2,5 g | impacts");
  const tr = {}; (R.l || []).forEach(x => { const k = Math.floor(x[0] / 600); (tr[k] = tr[k] || []).push(x); });
  Object.keys(tr).forEach(k => { const v = tr[k], fcs = v.map(x => x[1]).filter(Boolean), c = (th) => v.filter(x => x[3] > th - 1000).length;
    D.push(heure(R.debut + k * 600000) + " | " + v.length + " | " + (fcs.length ? Math.round(fcs.reduce((a, x) => a + x, 0) / fcs.length) + "/" + Math.max(...fcs) : "–") + " | " + Math.round(v.reduce((a, x) => a + x[2], 0) / v.length) + " | " + c(1500) + " / " + c(2000) + " / " + c(2500) + " | " + v.reduce((a, x) => a + x[4], 0)); });
  return { bilan: b, resume, tout: D.join("\n") }; }
function resumeEnregistrements(base, version) { const recs = paires(base.courses).filter(x => x.n.h10rec).map(x => x.n.h10rec).sort((a, b) => b.debut - a.debut), imp = impLire(base);
  const T = ["ENREGISTREMENTS H10 — résumé — " + dateHeure(Date.now()) + (version ? " (APK " + version + ")" : ""), "Réglage des impacts actuel : " + gg(imp.seuil) + " g", pl(recs.length, "enregistrement")];
  recs.forEach(R => T.push("", rapportEnregistrement(R, null, "").resume)); return T.join("\n"); }

/* ===== 🛰 GPS (étape 4) =====
   Le service Android écoute le GPS pendant la séance (Parcours.java) et range dans l'enregistrement un résumé « gps » :
   distance, secondes de passage de chaque km, tours marqués à la main [seconde, distance, FC], et un profil toutes les 5 s
   (« prof » = distance en mètres, « trace » = latitude et longitude × 100 000). Le tracé complet, un point par seconde,
   reste dans le téléphone (« enregistrements/gps-<début>.txt ») : il servira à recalculer, couper ou découper un parcours.
   Réglage « gps-opt » = {acts: activités avec GPS, source: "sat" (satellites seuls) ou "mix" (position mélangée), km: vibration à chaque km}. */
const GPS_ACTS = ["footing", "marche", "velo"];
function gpsLire(base) { let o = {}; try { o = JSON.parse(base.reglages["gps-opt"] || "{}") || {}; } catch (e) { o = {}; }
  return { acts: Array.isArray(o.acts) ? o.acts.filter(a => typeof a === "string") : GPS_ACTS.slice(), source: o.source === "mix" ? "mix" : "sat", km: o.km !== false }; }
function gpsEcrire(base, o) { base.reglages["gps-opt"] = JSON.stringify({ acts: o.acts, source: o.source, km: o.km }); return gpsLire(base); }
function gpsPour(base, act) { return gpsLire(base).acts.includes(act); }
function gpsBasculer(base, act) { const o = gpsLire(base), i = o.acts.indexOf(act); if (i >= 0) o.acts.splice(i, 1); else o.acts.push(act); return gpsEcrire(base, o); }
const kmTxt = (m) => (m / 1000).toFixed(2).replace(".", ",") + " km";
/* allure pour m mètres en s secondes ; « – » quand ça n'a pas de sens (presque pas de distance, ou plus de 60 min au km) */
const allureTxt = (m, s) => m >= 20 && s > 0 && s / m < 3.6 ? gm(s / (m / 1000)) + " /km" : "–";
const allureVit = (v) => v && v > 0.28 ? gm(1000 / v) + " /km" : "–";
function gpsUtile(R) { const G = R && R.gps; return !!G && G.premier >= 0 && Array.isArray(G.prof) && G.prof.length > 0; }
/* distance (m) à la seconde s de la séance */
function gpsDistA(G, s) { const p = G.prof || [], pas = G.pas || 5, n = p.length; if (!n) return 0; if (s <= 0) return p[0]; if (s >= G.duree) return G.dist;
  const i = Math.floor(s / pas); if (i >= n - 1) { const t0 = (n - 1) * pas, d = G.duree - t0; return d > 0 ? p[n - 1] + (G.dist - p[n - 1]) * (s - t0) / d : G.dist; }
  return p[i] + (p[i + 1] - p[i]) * (s / pas - i); }
/* cardio et impacts de la ceinture entre deux secondes */
function h10Entre(R, a, b) { let n = 0, som = 0, max = 0, imp = 0; (R.l || []).forEach(x => { if (x[0] >= a && x[0] < b) { imp += x[4]; if (x[1]) { n++; som += x[1]; if (x[1] > max) max = x[1]; } } });
  return { fcMoy: n ? Math.round(som / n) : null, fcMax: n ? max : null, impacts: imp }; }
/* Tout ce qu'on sait du parcours : le temps compte à partir de la première position (« attente » = secondes avant).
   kms = un par kilomètre entier, plus le reste ; tours = un par appui sur « Tour », plus le dernier bout jusqu'à la fin. */
function gpsBilan(R) { const G = R && R.gps; if (!G || typeof G !== "object") return null; const ok = gpsUtile(R), depart = Math.max(0, G.premier || 0), duree = G.duree || 0, temps = Math.max(0, duree - depart), dist = G.dist || 0;
  const b = { ok, dist, distP: G.distP || 0, duree, attente: depart, temps, allure: allureTxt(dist, temps), points: G.points || 0, rejetes: G.rejetes || 0, sauts: G.sauts || 0, precMoy: G.precMoy == null ? null : Number(G.precMoy),
    source: G.source === "mix" ? "position mélangée" : "satellites seuls", reprises: G.reprises || 0, fichier: G.fichier || "", kms: [], tours: [] };
  if (!ok) return b; const km = G.km || []; let prec = depart;
  km.forEach((sec, i) => { b.kms.push(Object.assign({ n: i + 1, debut: prec, d: 1000, t: sec - prec, allure: allureTxt(1000, sec - prec) }, h10Entre(R, prec, sec))); prec = sec; });
  const reste = dist - km.length * 1000; if (reste >= 20 && duree > prec) b.kms.push(Object.assign({ n: km.length + 1, debut: prec, d: Math.round(reste), t: duree - prec, allure: allureTxt(reste, duree - prec), partiel: true }, h10Entre(R, prec, duree + 1)));
  if ((G.tours || []).length) { const bornes = [[depart, 0]].concat(G.tours.map(x => [x[0], x[1]]), [[duree, dist]]);
    for (let i = 1; i < bornes.length; i++) { const a = bornes[i - 1], z = bornes[i], d = Math.max(0, z[1] - a[1]), t = Math.max(0, z[0] - a[0]), fin = i === bornes.length - 1;
      b.tours.push(Object.assign({ n: i, debut: a[0], d, t, allure: allureTxt(d, t), fin, fcAppui: fin ? null : (G.tours[i - 1][2] || null) }, h10Entre(R, a[0], z[0] + (fin ? 1 : 0)))); } }
  return b; }
function lignesGps(T, g) { if (!g.ok) { T.push("GPS : aucune position reçue pendant la séance (" + g.points + " points, tous écartés ou absents)."); return; }
  T.push("GPS : " + kmTxt(g.dist) + " en " + chrono(g.temps) + " · allure moyenne " + g.allure + (g.attente > 2 ? " · attente du GPS au départ " + dureeCourte(g.attente) : ""));
  T.push("Qualité du GPS : précision moyenne " + (g.precMoy == null ? "–" : v1(g.precMoy) + " m") + " · " + nb(g.points) + " points, " + g.rejetes + " écartés, " + pl(g.sauts, "saut") + " · " + g.source + " · distance par les positions seules " + kmTxt(g.distP) + (g.reprises ? " · " + pl(g.reprises, "reprise") : "")); }
function tablesGps(T, R, g) { const f = (v) => v == null ? "–" : v, h = (sec) => heureS(R.debut + sec * 1000);
  if (g.tours.length) { T.push("", "Mes tours (bouton Tour) :", "Tour | Départ | Temps | Distance | Allure | FC moy | FC max | Impacts");
    g.tours.forEach(x => T.push([x.n + (x.fin ? " (fin)" : ""), h(x.debut), chrono(x.t), kmTxt(x.d), x.allure, f(x.fcMoy), f(x.fcMax), x.impacts].join(" | "))); }
  if (g.kms.length) { T.push("", "Km par km :", "Km | Départ | Temps | Allure | FC moy | FC max | Impacts");
    g.kms.forEach(x => T.push([x.partiel ? "reste " + x.d + " m" : x.n, h(x.debut), chrono(x.t), x.allure, f(x.fcMoy), f(x.fcMax), x.impacts].join(" | "))); } }
/* Dessin du parcours (sans carte) : départ en vert, arrivée en rouge, un point par km, un drapeau par tour. */
function traceSvg(G, largeur, hauteur) { if (!G || !Array.isArray(G.trace)) return ""; const pts = []; for (let i = 0; i + 1 < G.trace.length; i += 2) if (G.trace[i] || G.trace[i + 1]) pts.push({ i: i / 2, la: G.trace[i] / 1e5, lo: G.trace[i + 1] / 1e5 });
  if (pts.length < 2) return ""; const W = largeur || 320, H = hauteur || 220, M = 14, laMin = Math.min(...pts.map(p => p.la)), laMax = Math.max(...pts.map(p => p.la)), loMin = Math.min(...pts.map(p => p.lo)), loMax = Math.max(...pts.map(p => p.lo));
  const kx = Math.cos((laMin + laMax) / 2 * Math.PI / 180), dx = Math.max(1e-6, (loMax - loMin) * kx), dy = Math.max(1e-6, laMax - laMin), ech = Math.min((W - 2 * M) / dx, (H - 2 * M) / dy), ox = (W - dx * ech) / 2, oy = (H - dy * ech) / 2;
  const X = (p) => (ox + (p.lo - loMin) * kx * ech).toFixed(1), Y = (p) => (oy + (laMax - p.la) * ech).toFixed(1), pas = G.pas || 5, a = (sec) => { const k = Math.round(sec / pas); return pts.find(p => p.i >= k) || pts[pts.length - 1]; };
  let h = '<svg viewBox="0 0 ' + W + ' ' + H + '" width="100%" role="img" aria-label="Dessin du parcours" style="background:#fff;border:1px solid #d9dce1;border-radius:12px;display:block;width:100%;height:auto;aspect-ratio:' + W + '/' + H + ';flex:none"><polyline fill="none" stroke="#B5451B" stroke-width="3" stroke-linejoin="round" stroke-linecap="round" points="' + pts.map(p => X(p) + "," + Y(p)).join(" ") + '"/>';
  (G.km || []).forEach((sec, i) => { const p = a(sec); h += '<circle cx="' + X(p) + '" cy="' + Y(p) + '" r="8" fill="#fff" stroke="#1b1f23" stroke-width="1.5"/><text x="' + X(p) + '" y="' + (Number(Y(p)) + 3.5) + '" font-size="10" font-weight="700" text-anchor="middle" fill="#1b1f23">' + (i + 1) + '</text>'; });
  (G.tours || []).forEach(x => { const p = a(x[0]); h += '<path d="M' + X(p) + ' ' + Y(p) + ' v-14 l9 4 l-9 4" fill="#1f6b43" stroke="#1f6b43" stroke-width="1.5" stroke-linejoin="round"/>'; });
  const d = pts[0], f = pts[pts.length - 1]; h += '<circle cx="' + X(d) + '" cy="' + Y(d) + '" r="6" fill="#1f6b43" stroke="#fff" stroke-width="2"/><rect x="' + (Number(X(f)) - 5) + '" y="' + (Number(Y(f)) - 5) + '" width="10" height="10" fill="#a52a1f" stroke="#fff" stroke-width="2"/>';
  return h + '</svg>'; }

/* ===== Réglage des impacts (repris de la v31) =====
   impact-reglage = {seuil, rearme, bip} en millièmes de g ; impact-essais = journal de chaque réglage et de chaque essai. */
function impLire(base) { let i = { seuil: 2500, rearme: 1800 }; try { i = Object.assign(i, JSON.parse(base.reglages["impact-reglage"] || "{}")); } catch (e) {} i.bip = i.bip !== false; return i; }
function impEcrire(base, imp) { base.reglages["impact-reglage"] = JSON.stringify(imp); }
function essaisLire(base) { return lireListe(base.reglages["impact-essais"]); }
function essaiNoter(base, x) { const l = essaisLire(base); l.push(Object.assign({ t: Date.now() }, x)); base.reglages["impact-essais"] = JSON.stringify(l.slice(-300)); }
/* − sensible / + sensible : par pas de 0,1 g, entre 1,2 g et 4 g */
function impRegler(base, delta, contexte, dernier) { const imp = impLire(base); imp.seuil = Math.min(4000, Math.max(1200, Math.round((imp.seuil + delta) / 100) * 100)); imp.rearme = Math.round(1000 + (imp.seuil - 1000) * 0.6); impEcrire(base, imp);
  essaiNoter(base, { type: "reglage", sens: delta < 0 ? "+ sensible" : "− sensible", seuil: imp.seuil, ctx: contexte || "essai", dernier: dernier || null }); return imp; }
function impBip(base, oui) { const imp = impLire(base); imp.bip = !!oui; impEcrire(base, imp); return imp; }
/* mouvements nets d'un essai : mesures fortes [[t, m], …] regroupées quand elles sont à moins de sep ms */
function calPics(l, sep) { const p = []; let best = null; l.forEach(([t, m]) => { if (m < 1250) return; if (best && t - best[0] < sep) { if (m > best[1]) best = [t, m]; } else { if (best) p.push(best); best = [t, m]; } }); if (best) p.push(best);
  return p.filter((x, i) => !(p[i + 1] && p[i + 1][0] - x[0] < sep && p[i + 1][1] > x[1])); }
function essaiEnDirect(mesures, imp) { const p = calPics(mesures, 600).filter(x => x[1] > 1400); return { comptes: p.filter(x => x[1] > imp.seuil).length, mouvements: p.length, derniers: p.slice(-20).map(x => ({ m: x[1], compte: x[1] > imp.seuil })) }; }
/* fin d'un essai « j'ai fait n coups » : cherche le seuil qui en compte exactement n */
function impEssai(base, mesures, n) { const avant = impLire(base), pics = calPics(mesures, 600).map(x => x[1]).sort((a, b) => b - a); let imp, texte;
  if (pics.length < n) { essaiNoter(base, { type: "essai", n, pics, nonCompte: null, seuil: 1300, manque: n - pics.length }); imp = { seuil: 1300, rearme: 1150 };
    texte = "Je n'ai vu que " + pl(pics.length, "mouvement") + " pour " + pl(n, "coup") + " : la ceinture ne sent pas assez certains coups. Seuil mis au minimum (1,3 g)."; }
  else { const fort = pics[n - 1], faible = pics[n] || 1250, s2 = Math.round((fort + faible) / 2); imp = { seuil: s2, rearme: Math.round(1000 + (s2 - 1000) * 0.6) };
    essaiNoter(base, { type: "essai", n, pics: pics.slice(0, n + 5), nonCompte: pics[n] || null, seuil: s2 });
    texte = "✅ Seuil réglé à " + (s2 / 1000).toFixed(2).replace(".", ",") + " g (avant : " + (avant.seuil / 1000).toFixed(2).replace(".", ",") + " g).\nTes " + pl(n, "coup") + " : " + pics.slice(0, n).map(gg).join(" · ") + " g\nPlus fort mouvement non compté : " + gg(faible) + " g" + (fort - faible < 150 ? "\n⚠️ Écart faible : coups et déplacements se ressemblent, refais un essai." : ""); }
  imp.bip = avant.bip; impEcrire(base, imp); return { imp, texte }; }
function rapportReglages(base) { const l = essaisLire(base).slice().reverse(), imp = impLire(base), essais = l.filter(x => x.type === "essai");
  const T = ["RÉGLAGES DES IMPACTS — " + dateHeure(Date.now()), pl(essais.length, "essai") + " · réglage actuel " + gg(imp.seuil) + " g", "", "Quand | Quoi | Coups (g) | Non compté max | Seuil"];
  l.forEach(x => { const q = dateFr(x.t).slice(0, 5) + " " + heure(x.t);
    T.push(x.type === "essai" ? q + " | essai " + x.n + " coups | " + (x.pics || []).slice(0, x.n).map(gg).join(" · ") + " | " + gg(x.nonCompte) + " | " + gg(x.seuil)
      : q + " | " + (x.ctx === "enreg" ? "en jeu" : "en essai") + " " + x.sens + " | " + (x.dernier ? "dernier " + gg(x.dernier) : "") + " | | " + gg(x.seuil)); });
  if (essais.length > 1) { const tous = [].concat(...essais.map(x => (x.pics || []).slice(0, x.n))).sort((a, b) => a - b), nc = essais.map(x => x.nonCompte || 0);
    if (tous.length) T.push("", "Sur tous les essais : coups entre " + gg(tous[0]) + " et " + gg(tous[tous.length - 1]) + " g (moitié au-dessus de " + gg(tous[Math.floor(tous.length / 2)]) + " g) · mouvements non comptés jusqu'à " + gg(Math.max(...nc)) + " g."); }
  return T.join("\n"); }
const chrono = (s) => { s = Math.max(0, Math.round(s)); const h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60); return (h ? h + ":" + String(m).padStart(2, "0") : m) + ":" + String(s % 60).padStart(2, "0"); };

/* ===== Exercices à répétitions (étape 3) : pompes, squats, développé couché… =====
   Idées de Frédéric (13/11/2026) : une charge possible pour chaque exercice (sac de 2 ou 5 kg, barre…), un objectif
   de répétitions qu'on peut toujours dépasser, et l'enchaînement de plusieurs exercices dans une même séance (tuile Muscu).
   Tout le compteur est ici, sans écran, pour être testé seul. Les heures sont celles du téléphone (millisecondes) :
   la séance est écrite à chaque appui, donc elle reprend telle quelle si l'appli est fermée. */
const EXOS_DEPART = [{ nom: "Pompes", obj: 100, charge: 0, repos: 90 }, { nom: "Squats", obj: 50, charge: 0, repos: 90 }, { nom: "Développé couché", obj: 30, charge: 0, repos: 120 }, { nom: "Biceps barre", obj: 30, charge: 0, repos: 90 }];
const kg = (v) => String(Math.round(v * 10) / 10).replace(".", ",") + " kg";
function chargeTxt(e, avant, apres) { const l = (e.charges || []).filter(x => x > 0); if (!l.length) return ""; const mn = Math.min(...l), mx = Math.max(...l); return avant + (mn === mx ? kg(mx) : kg(mn) + " à " + kg(mx)) + apres; }
function exosLire(base) { let l = lireListe(base.reglages["exos"]).filter(x => x && typeof x.nom === "string" && x.nom); if (!l.length) l = JSON.parse(JSON.stringify(EXOS_DEPART));
  let po = {}; try { po = JSON.parse(base.reglages["pompes-opt"] || "{}") || {}; } catch (e) {}
  if (!l.some(x => x.nom === "Pompes")) l.unshift(Object.assign({}, EXOS_DEPART[0]));
  return l.map(x => { const e = { nom: x.nom, obj: Number(x.obj) > 0 ? Math.round(x.obj) : 0, charge: Number(x.charge) > 0 ? Number(x.charge) : 0, repos: Number(x.repos) >= 0 ? Math.round(x.repos) : 90 };
    if (e.nom === "Pompes") { if (Number(po.obj) > 0) e.obj = Math.round(po.obj); if (po.repos != null && Number(po.repos) >= 0) e.repos = Math.round(po.repos); if (Number(po.charge) >= 0) e.charge = Number(po.charge); } return e; }); }
/* les réglages des pompes restent aussi dans « pompes-opt », la clé de la v31 */
function exosEcrire(base, l) { base.reglages["exos"] = JSON.stringify(l); const p = l.find(x => x.nom === "Pompes"); if (p) { let po = {}; try { po = JSON.parse(base.reglages["pompes-opt"] || "{}") || {}; } catch (e) {} po.obj = p.obj; po.repos = p.repos; po.charge = p.charge; base.reglages["pompes-opt"] = JSON.stringify(po); } }
function exoRegler(base, nom, champs) { const l = exosLire(base); let e = l.find(x => x.nom === nom); if (!e) { e = { nom, obj: 30, charge: 0, repos: 90 }; l.push(e); }
  if (champs.obj != null) e.obj = Math.min(2000, Math.max(0, Math.round(champs.obj))); if (champs.charge != null) e.charge = Math.min(500, Math.max(0, Math.round(champs.charge * 2) / 2)); if (champs.repos != null) e.repos = Math.min(900, Math.max(0, Math.round(champs.repos)));
  exosEcrire(base, l); return e; }
function exoAjouter(base, nom) { nom = String(nom || "").trim().slice(0, 30); if (!nom) return null; const l = exosLire(base), deja = l.find(x => x.nom.toLowerCase() === nom.toLowerCase()); if (deja) return deja; const e = { nom, obj: 30, charge: 0, repos: 90 }; l.push(e); exosEcrire(base, l); return e; }
/* l'enchaînement de la tuile Muscu : la liste ordonnée des exercices */
function programmeLire(base) { const ex = exosLire(base).map(x => x.nom); return lireListe(base.reglages["muscu-programme"]).filter(n => ex.includes(n)); }
function programmeEcrire(base, l) { base.reglages["muscu-programme"] = JSON.stringify(l); }
/* plus longue série déjà faite pour un exercice (pour l'annonce « record battu ») */
function recordSerie(base, nom) { let r = 0; const vu = (n) => { if (n > r) r = n; };
  if (nom === "Pompes") { Integre.CARNET_POMPES.forEach(x => seriesDe(x.tl).forEach(vu)); seriesDe(Integre.JOUR_3008.tl).forEach(vu); }
  paires(base.courses).forEach(({ n }) => { if (nom === "Pompes") { if (n.pompes && n.pompes.series) n.pompes.series.forEach(vu); if (n.pjour && n.pjour.tl) seriesDe(n.pjour.tl).forEach(vu); } else if (n.reps && n.reps.exo === nom && n.reps.series) n.reps.series.forEach(vu); }); return r; }

const r1 = (x) => Math.round(x * 10) / 10;
function repsBloc(e, record) { return { exo: e.nom, obj: e.obj || 0, charge: e.charge || 0, repos: e.repos || 0, t0: null, series: [], cur: null, phase: "attente", debutRepos: 0, signal: 0, objAnnonce: false, record: record || 0, recordBattu: false, attendDiff: false }; }
function repsNouveau(base, act, noms, maintenant) { const ex = exosLire(base); return { v: 1, debut: maintenant, maj: maintenant, act, index: 0, dernierAppui: 0, blocs: noms.map(n => repsBloc(ex.find(x => x.nom === n) || { nom: n, obj: 0, charge: 0, repos: 90 }, recordSerie(base, n))) }; }
const repsCourant = (st) => st.blocs[st.index];
const repsT = (b, maintenant) => b.t0 == null ? 0 : (maintenant - b.t0) / 1000;
const repsTotal = (b) => b.series.reduce((a, x) => a + x.tl.length, 0) + (b.cur ? b.cur.tl.length : 0);
/* un appui sur l'écran = une répétition. Renvoie les annonces à faire : ["record", n] ou ["objectif", n]. */
function repsAppui(st, maintenant, fc) { const b = repsCourant(st), ev = []; if (!b || maintenant - st.dernierAppui < 350) return ev;
  if (b.phase !== "serie") { if (b.t0 == null) b.t0 = maintenant; b.phase = "serie"; b.attendDiff = false; b.cur = { tl: [], man: 0, charge: b.charge, fcDepart: fc || null, fcMax: fc || null, fcFin: null, diff: null }; }
  st.dernierAppui = maintenant; st.maj = maintenant; b.cur.tl.push(r1(repsT(b, maintenant)));
  if (b.record && !b.recordBattu && b.cur.tl.length > b.record) { b.recordBattu = true; ev.push(["record", b.cur.tl.length]); }
  if (b.obj && !b.objAnnonce && repsTotal(b) >= b.obj) { b.objAnnonce = true; ev.push(["objectif", b.obj]); } return ev; }
/* +1 : une répétition faite mais pas prise par l'écran (en série : maintenant ; au repos : ajoutée à la série finie) */
function repsPlus(st, maintenant) { const b = repsCourant(st); if (!b) return false; let sr = null, t = 0;
  if (b.phase === "serie" && b.cur && b.cur.tl.length) { sr = b.cur; t = r1(repsT(b, maintenant)); st.dernierAppui = maintenant; }
  else if (b.phase === "repos" && b.series.length) { sr = b.series[b.series.length - 1]; const l = sr.tl; t = r1(l[l.length - 1] + (l.length > 1 ? (l[l.length - 1] - l[0]) / (l.length - 1) : 1)); }
  if (!sr) return false; sr.tl.push(t); sr.man = (sr.man || 0) + 1; st.maj = maintenant; if (b.obj && !b.objAnnonce && repsTotal(b) >= b.obj) b.objAnnonce = true; return true; }
function repsMoins(st, maintenant) { const b = repsCourant(st); if (!b || b.phase !== "serie" || !b.cur || !b.cur.tl.length) return false; b.cur.tl.pop(); st.maj = maintenant; return true; }
function repsFc(st, fc) { const b = repsCourant(st); if (b && b.cur && fc && (!b.cur.fcMax || fc > b.cur.fcMax)) b.cur.fcMax = fc; }
function repsFinSerie(st, maintenant, fc) { const b = repsCourant(st); if (!b || b.phase !== "serie" || !b.cur || !b.cur.tl.length) return false;
  b.cur.fcFin = fc || null; if (fc && (!b.cur.fcMax || fc > b.cur.fcMax)) b.cur.fcMax = fc; b.series.push(b.cur); if (b.cur.tl.length > b.record) b.record = b.cur.tl.length; b.recordBattu = false;
  b.cur = null; b.phase = "repos"; b.debutRepos = r1(repsT(b, maintenant)); b.signal = 0; b.attendDiff = true; st.maj = maintenant; return true; }
function repsDiff(st, d) { const b = repsCourant(st); if (!b || !b.series.length) return; b.series[b.series.length - 1].diff = d; b.attendDiff = false; }
function repsCharge(st, v) { const b = repsCourant(st); if (!b) return; b.charge = Math.min(500, Math.max(0, Math.round(v * 2) / 2)); if (b.cur && !b.cur.tl.length) b.cur.charge = b.charge; }
/* repos : temps écoulé, temps restant, et le signal à donner une seule fois ("bientot" à 10 s, "reprendre" à 0) */
function repsRepos(st, maintenant) { const b = repsCourant(st); if (!b || b.phase !== "repos") return null; const ecoule = repsT(b, maintenant) - b.debutRepos, r = { ecoule, reste: b.repos ? b.repos - ecoule : null, signal: null };
  if (b.repos) { if (r.reste <= 0 && b.signal < 2) { b.signal = 2; r.signal = "reprendre"; } else if (r.reste > 0 && r.reste <= 10 && b.signal === 0) { b.signal = 1; r.signal = "bientot"; } } return r; }
/* exercice suivant : ferme la série en cours et passe au suivant (ajouté à la suite s'il n'était pas prévu) */
function repsSuivant(st, maintenant, fc, base, nomSuivant) { repsFinSerie(st, maintenant, fc); const b = repsCourant(st); if (b) { b.attendDiff = false; b.fini = true; }
  if (nomSuivant) { const ex = exosLire(base); st.blocs.splice(st.index + 1, 0, repsBloc(ex.find(x => x.nom === nomSuivant) || { nom: nomSuivant, obj: 0, charge: 0, repos: 90 }, recordSerie(base, nomSuivant))); }
  if (st.index + 1 >= st.blocs.length) return false; st.index++; st.maj = maintenant; return true; }
function repsBilanBloc(b) { const sers = b.series.concat(b.cur && b.cur.tl.length ? [b.cur] : []), tl = [].concat(...sers.map(x => x.tl)).sort((x, y) => x - y), n = tl.length; if (!n) return null;
  const repos = sers.slice(1).map((x, i) => Math.round(x.tl[0] - sers[i].tl[sers[i].tl.length - 1])), fcs = sers.map(x => [x.fcDepart || null, x.fcMax || null, x.fcFin || null]), charges = sers.map(x => x.charge || 0);
  const temps = Math.round(tl[n - 1]), pompes = b.exo === "Pompes", mot = pompes ? (n > 1 ? "pompes" : "pompe") : (n > 1 ? "répétitions" : "répétition");
  const d = { obj: b.obj, total: n, temps, series: sers.map(x => x.tl.length), diffs: sers.some(x => x.diff != null) ? sers.map(x => x.diff == null ? null : x.diff) : null, reposMoy: repos.length ? Math.round(repos.reduce((a, x) => a + x, 0) / repos.length) : 0, tl,
    fcs: fcs.some(x => x[1]) ? fcs : null, res: nb(n) + " " + mot + " en " + gm(temps) + ", " + pl(sers.length, "série") };
  if (charges.some(x => x > 0)) { d.charges = charges; d.res += ", " + chargeTxt(d, "charge ", ""); } const man = sers.reduce((a, x) => a + (x.man || 0), 0); if (man) d.manuels = man; d.repos = repos;
  return { exo: b.exo, fin: b.t0 + Math.round(tl[n - 1] * 1000), donnees: d }; }
/* fin de séance → la séance à ranger dans la base (null si aucune répétition). Les pompes gardent la forme de la v31 (note « pompes »). */
function repsSeance(st, maintenant) { const notes = []; st.blocs.forEach(b => { const r = repsBilanBloc(b); if (!r) return; const d = r.donnees, repos = d.repos; delete d.repos; let t = r.fin; while (notes.some(n => n.t === t)) t++;
    const fcMax = d.fcs ? Math.max(...d.fcs.map(x => x[1] || 0)) : 0, n = { t }; if (fcMax) n.fc = fcMax; if (r.exo === "Pompes") n.pompes = d; else n.reps = Object.assign({ exo: r.exo }, d); notes.push(n); });
  if (!notes.length) return null; notes.sort((x, y) => x.t - y.t); return { id: st.debut, debut: st.debut, act: st.act, close: true, notes }; }
/* séries d'un exercice à partir de l'heure de chaque répétition : durée de chaque série et repos avant elle */
function seriesDetail(e) { const tl = (e.tl || []).slice().sort((x, y) => x - y), cs = e.series || [], sers = []; let i = 0;
  if (cs.length && cs.reduce((a, x) => a + x, 0) === tl.length) cs.forEach(k => { sers.push(tl.slice(i, i + k)); i += k; }); else { let c = null; tl.forEach((t, k) => { if (!c || t - tl[k - 1] > 5) { c = []; sers.push(c); } c.push(t); }); }
  return sers.filter(x => x.length).map((x, k) => ({ n: x.length, duree: x[x.length - 1] - x[0], repos: k ? x[0] - sers[k - 1][sers[k - 1].length - 1] : null })); }
function tableSeries(D, nom, e) { D.push("", nom + " — série | répétitions | durée | repos avant | charge | difficulté | FC départ/max/fin");
  seriesDetail(e).forEach((x, i) => D.push((i + 1) + " | " + x.n + " | " + Math.round(x.duree) + " s | " + (x.repos == null ? "–" : gm(x.repos)) + " | " + (e.charges && e.charges[i] ? kg(e.charges[i]) : "–") + " | " + (e.diffs && e.diffs[i] != null && DIFF[e.diffs[i]] ? DIFF[e.diffs[i]] : "–") + " | " + (e.fcs && e.fcs[i] ? e.fcs[i].map(v => v || "–").join("/") : "–")));
  if (e.manuels) D.push(pl(e.manuels, "répétition ajoutée", "répétitions ajoutées") + " avec +1"); }
function rapportReps(seance, version) { const T = ["SÉANCE — " + actInfo(seance.act).nom + " — " + dateHeure(seance.debut) + (version ? " (APK " + version + ")" : "")], D = [];
  seance.notes.forEach(n => { const e = n.reps || n.pompes, nom = n.reps ? n.reps.exo : "Pompes"; if (!e) return;
    T.push(nom + " : " + e.res + (e.obj ? " (objectif " + e.obj + (e.total >= e.obj ? " atteint" : " : il manque " + (e.obj - e.total)) + ")" : "") + (e.reposMoy ? ", repos moyen " + gm(e.reposMoy) : "")); tableSeries(D, nom, e); });
  const resume = T.join("\n"); return { resume, tout: resume + "\n" + D.join("\n") }; }

/* ===== 📋 Détail d'une séance, à coller dans la conversation (validé le 13/11/2026) =====
   Enregistrement H10 : minute par minute (cardio moyen/max, impacts, mouvement) et heure exacte de chaque trou.
   Pompes et exercices : série par série. Autres séances : chaque note avec son heure. */
function manquesH10(R) { const L = R.l || [], fin = R.fin ? Math.round((R.fin - R.debut) / 1000) : (L.length ? L[L.length - 1][0] + 1 : 0), trous = []; let petits = 0, prec = -1;
  const vu = (jusqua) => { const m = jusqua - prec - 1; if (m > 5) trous.push({ a: prec + 1, duree: m }); else if (m >= 1) petits += m; };
  L.forEach(x => { vu(x[0]); prec = x[0]; }); vu(fin); return { trous, petits, fin }; }
function detailH10(T, R, imp) { const L = R.l || [], b = bilanEnregistrement(R), m = manquesH10(R), h = (sec) => heureS(R.debut + sec * 1000), g = b.gps, G = g && g.ok ? R.gps : null;
  if (g) lignesGps(T, g);
  if (b.sansCeinture && g) T.push("Sans ceinture : pas de cardio ni de mouvement.");
  else { T.push("Enregistré " + hm(b.enregistre) + " sur " + hm(b.ecoule) + " · FC moy " + (b.fcMoy || "–") + " / max " + (b.fcMax || "–") + " · " + (b.sansMouvement ? "pas de mesure de mouvement" : pl(b.impacts, "impact") + (imp ? " (réglage " + gg(imp.seuil) + " g)" : "") + " · actif " + hm(b.actif)));
    T.push("", m.trous.length ? "Trous (plus de 5 s sans mesure) : " + m.trous.length : "Trous (plus de 5 s sans mesure) : aucun"); m.trous.forEach(x => T.push("- de " + h(x.a) + " à " + h(x.a + x.duree) + " (" + (x.duree < 60 ? x.duree + " s" : gm(x.duree)) + ")"));
    if (m.petits) T.push("Petits manques (1 à 5 s) : " + m.petits + " s en tout");
    if ((R.deconnexions || []).length) { T.push("Coupures de la ceinture : " + R.deconnexions.length); R.deconnexions.forEach(x => T.push("- " + h(x[0]) + " pendant " + dureeCourte(x[1]))); }
    if (R.coupures) T.push("Reprises après fermeture de l'appli : " + R.coupures); }
  if (G) tablesGps(T, R, g);
  T.push("", "Minute | " + (G ? "Distance (m) | Allure | " : "") + "FC moy | FC max | Impacts | Mouvement moy (g) | Force max (g) | Secondes");
  const parMin = new Map(); L.forEach(x => { const k = Math.floor(x[0] / 60); if (!parMin.has(k)) parMin.set(k, []); parMin.get(k).push(x); });
  if (G) for (let k = 0; k * 60 < G.duree; k++) if (!parMin.has(k)) parMin.set(k, []);
  [...parMin.keys()].sort((x, y) => x - y).forEach(k => { const v = parMin.get(k), fcs = v.map(x => x[1]).filter(Boolean); let gps = "";
    if (G) { const fin = Math.min((k + 1) * 60, G.duree), d = Math.max(0, gpsDistA(G, fin) - gpsDistA(G, k * 60)); gps = Math.round(d) + " | " + allureTxt(d, fin - k * 60) + " | "; }
    T.push(heure(R.debut + k * 60000) + " | " + gps + (fcs.length ? Math.round(fcs.reduce((a, x) => a + x, 0) / fcs.length) : "–") + " | " + (fcs.length ? Math.max(...fcs) : "–") + " | " + v.reduce((a, x) => a + x[4], 0) + " | " +
      (v.length ? (v.reduce((a, x) => a + x[2], 0) / v.length / 1000).toFixed(2).replace(".", ",") : "–") + " | " + (v.length ? ((1000 + Math.max(...v.map(x => x[3]))) / 1000).toFixed(1).replace(".", ",") : "–") + " | " + v.length); }); }
function detailLigne(base, idLigne, version) { const x = lignesHistorique(base).find(l => l.id === idLigne); if (!x) return ""; const r = x.ref || {};
  const T = ["DÉTAIL — " + actInfo(x.act).nom + (x.journee ? " (journée)" : "") + " — " + dateHeure(x.t) + (version ? " (APK " + version + ")" : ""), "Durée " + dureeTxt(x) + " · " + x.resultat + (x.source ? " (" + x.source + ")" : "") + (x.alerte ? " · " + x.alerte : "")];
  if (r.n && r.n.h10rec) detailH10(T, r.n.h10rec, impLire(base));
  else if (r.tl) tableSeries(T, "Pompes", { tl: r.tl });
  else { const autres = []; (r.notes || []).forEach(n => { const e = n.reps || n.pompes || n.pjour; if (e && e.tl && e.tl.length) tableSeries(T, n.reps ? n.reps.exo : n.pjour ? "Pompes (journée)" : "Pompes", e); else autres.push(detailNote(n)); });
    if (autres.length) { T.push("", "Notes :"); autres.forEach(a => T.push("- " + a)); } }
  return T.join("\n"); }

/* ===== 🗑 Supprimer une séance (validé le 13/11/2026) =====
   Rien n'est effacé pour de bon : la séance supprimée part dans la corbeille (gardée dans la base et dans les sauvegardes),
   d'où on peut la remettre. Une fusion ou un nouvel import ne la fait pas revenir. Les lignes intégrées (Carnet, 30/08/2025) ne se suppriment pas. */
const cleCorbeille = (idS, n) => idS + "|" + n.t + "|" + typeNote(n);
function clesCorbeille(base) { const s = new Set(); (base.corbeille || []).forEach(e => (e.notes || []).forEach(n => s.add(cleCorbeille(idSeance(e.seance), n)))); return s; }
function supprimable(ligne) { return !!ligne && !ligne.source && !!ligne.ref && !!ligne.ref.c; }
function supprimerLigne(base, idLigne, maintenant) { const x = lignesHistorique(base).find(l => l.id === idLigne); if (!supprimable(x)) return null; const c = x.ref.c, otees = x.ref.n ? [x.ref.n] : x.ref.notes;
  if (!otees || !otees.length) return null; c.notes = c.notes.filter(n => !otees.includes(n)); if (!c.notes.length) base.courses.splice(base.courses.indexOf(c), 1);
  const e = { t: maintenant, id: idLigne, seance: { id: c.id, debut: c.debut, act: c.act, close: c.close, actOrigine: c.actOrigine }, notes: otees, ligne: { t: x.t, act: x.act, duree: x.duree, pompes: x.pompes, resultat: x.resultat, journee: !!x.journee } };
  if (!Array.isArray(base.corbeille)) base.corbeille = []; base.corbeille.push(e); return e; }
/* remet une séance supprimée à sa place (rien en double) */
function restaurer(base, t, idLigne) { const i = (base.corbeille || []).findIndex(e => e.t === t && e.id === idLigne); if (i < 0) return false; const e = base.corbeille[i], idS = idSeance(e.seance);
  let c = base.courses.find(x => idSeance(x) === idS); if (!c) { c = Object.assign({}, e.seance, { notes: [] }); Object.keys(c).forEach(k => { if (c[k] === undefined) delete c[k]; }); base.courses.push(c); base.courses.sort((x, y) => (x.debut || 0) - (y.debut || 0)); }
  e.notes.forEach(n => { if (!c.notes.some(y => y.t === n.t && typeNote(y) === typeNote(n))) c.notes.push(n); }); c.notes.sort((x, y) => x.t - y.t); base.corbeille.splice(i, 1); return true; }
/* corbeille d'une sauvegarde : reprise seulement pour ce qui n'existe plus dans l'appli (une fusion ne supprime jamais rien) */
function fusionnerCorbeille(base, entrees) { if (!Array.isArray(base.corbeille)) base.corbeille = []; let n = 0; const vivantes = new Set(); base.courses.forEach(c => c.notes.forEach(x => vivantes.add(cleCorbeille(idSeance(c), x))));
  (entrees || []).forEach(e => { if (!e || !e.seance || !Array.isArray(e.notes) || !e.notes.length) return; const cles = e.notes.map(x => cleCorbeille(idSeance(e.seance), x)), deja = clesCorbeille(base);
    if (cles.some(k => vivantes.has(k)) || cles.every(k => deja.has(k))) return; base.corbeille.push(JSON.parse(JSON.stringify(e))); n++; }); return n; }

/* ===== Sauvegarde de l'appli ===== */
function sauvegarde(base, version) { return { format: FORMAT, version: 1, creeeLe: new Date().toISOString(), appli: "APK Journal d'activité " + (version || ""), courses: base.courses, reglages: base.reglages, imports: base.imports, corbeille: base.corbeille || [] }; }
function nomSauvegarde(t) { return "JournalActivite-sauvegarde_" + horodatage(t == null ? Date.now() : t) + ".json"; }

/* Importe un fichier déjà lu (objet) : sauvegarde du Hub (extrait ou complète) ou sauvegarde de l'appli. Renvoie le descriptif. */
function importer(base, fichier, meta) { meta = meta || {}; let paquet, source;
  if (fichier && fichier.format === "mon-hub-sauvegarde") fichier = extraireHub(fichier);
  if (fichier && fichier.type === "hub") { if (!fichier.journaux || !fichier.journaux.length) throw new Error("Je ne trouve pas le Journal d'activité dans cette sauvegarde du Hub.");
    paquet = { courses: [], reglages: {}, muscu: 0 }; const noms = [];
    fichier.journaux.forEach(j => { const p = paquetDepuisHub(j); const tmp = { courses: paquet.courses, reglages: paquet.reglages }; fusionner(tmp, p); paquet.muscu += p.muscu; noms.push("« " + j.nom + " » version " + j.version); });
    source = "sauvegarde du Hub" + (fichier.creeeLe ? " du " + dateHeure(Date.parse(fichier.creeeLe)) : "") + " — Journal trouvé : " + noms.join(", "); }
  else if (fichier && fichier.format === FORMAT && Array.isArray(fichier.courses)) { paquet = { courses: fichier.courses, reglages: fichier.reglages || {}, muscu: 0 };
    source = "sauvegarde de l'appli" + (fichier.creeeLe ? " du " + dateHeure(Date.parse(fichier.creeeLe)) : ""); }
  else throw new Error("Ce fichier n'est ni une sauvegarde du Hub, ni une sauvegarde du Journal d'activité.");
  if (meta.nom) source += " (" + meta.nom + ")";
  if (fichier.format === FORMAT) fusionnerCorbeille(base, fichier.corbeille);
  const bilan = fusionner(base, paquet);
  if (fichier.format === FORMAT) (fichier.imports || []).forEach(i => { if (!base.imports.some(x => x.t === i.t)) base.imports.push(i); });
  const d = descriptif(base, paquet, bilan, { source, version: meta.version });
  base.imports.push({ t: d.t, source, resume: d.resume }); base.imports.sort((x, y) => x.t - y.t);
  return d; }

const N = { FORMAT, CLES_HUB, ACTS, actInfo, actNom, toutesActs, actsVisibles, lireListe, jourCourt, dateFr, heure, heureS, dateHeure, horodatage, gm, hm, nb, pl, v1, libelle,
  DIFF, kg, chargeTxt, exosLire, exoRegler, exoAjouter, programmeLire, programmeEcrire, recordSerie, repsNouveau, repsCourant, repsT, repsTotal, repsAppui, repsPlus, repsMoins, repsFc, repsFinSerie, repsDiff, repsCharge, repsRepos, repsSuivant, repsSeance, rapportReps,
  detailLigne, seriesDetail, supprimable, supprimerLigne, restaurer, clesCorbeille,
  gpsLire, gpsEcrire, gpsPour, gpsBasculer, gpsBilan, gpsUtile, gpsDistA, kmTxt, allureTxt, allureVit, traceSvg,
  seanceDepuisEnregistrement, ajouterEnregistrement, enregistrementsManquants, bilanEnregistrement, rapportEnregistrement, resumeEnregistrements, dureeCourte, gg, chrono,
  impLire, impRegler, impBip, calPics, essaiEnDirect, impEssai, rapportReglages, essaisLire,
  baseVide, baseValide, normaliser, stable, extraireHub, paquetDepuisHub, fusionner, inventaire, lignesHistorique, totaux, derniereParAct, dureeTxt, descriptif, rapportHistorique, sauvegarde, nomSauvegarde, importer };
g.Noyau = N;
if (typeof module !== "undefined" && module.exports) module.exports = N;
})(typeof window !== "undefined" ? window : globalThis);
