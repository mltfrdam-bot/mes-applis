/* Teste la partie Android qui lit la sauvegarde du Hub (LecteurHub.java), hors du téléphone.
   node activite/tests/test-lecteur.js [vraie-sauvegarde.json]
   Fabrique une fausse sauvegarde du Hub de 20 Mo, la fait lire par le Java avec très peu de mémoire (32 Mo),
   et vérifie que le résultat est exactement ce que la page attend. */
"use strict";
const assert = require("assert"), fs = require("fs"), os = require("os"), path = require("path"), { execFileSync, spawnSync } = require("child_process");
const N = require("../noyau.js");
const racine = path.join(__dirname, "..", ".."), tmp = fs.mkdtempSync(path.join(os.tmpdir(), "lecteur-"));
const env = Object.assign({}, process.env); delete env.JAVA_TOOL_OPTIONS;
execFileSync("javac", ["-encoding", "UTF-8", "-d", path.join(tmp, "classes"),
  path.join(racine, "android-activite/app/src/main/java/fr/mesapplis/activite/LecteurHub.java"), path.join(racine, "android-activite/tests/EssaiLecteur.java")], { env, stdio: "inherit" });
function lecteur(entree) { const sortie = path.join(tmp, "import.json"); try { fs.unlinkSync(sortie); } catch (e) {}
  const r = spawnSync("java", ["-Xmx32m", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-cp", path.join(tmp, "classes"), "fr.mesapplis.activite.EssaiLecteur", entree, sortie], { env, encoding: "utf8" });
  return { code: r.status, message: (r.stdout || "").trim() + (r.stderr || "").trim(), sortie: fs.existsSync(sortie) ? fs.readFileSync(sortie) : null }; }
function ecrire(nom, contenu) { const f = path.join(tmp, nom); fs.writeFileSync(f, contenu); return f; }
let n = 0; function test(nom, fn) { fn(); n++; console.log("✅ " + nom); }

const piege = "guillemets \" barre \\ retour\nligne\ttab é à ç 💪   fin } ] { [ , :";
const gros = ("function f(){ return \"" + piege.replace(/["\\\n\t]/g, " ") + "\"; } // 🏃 \\n \\\" \\\\ {[\n").repeat(140000);       // ~ 20 Mo de « code »
const courses = [{ id: 1, debut: 1, act: "muscu", notes: [{ t: 1, note: piege, fc: 120 }, { t: 2, pompes: { total: 12, tl: [0.5, 1.25], series: [12], res: "12 pompes — " + piege } }] }];
const hub = { format: "mon-hub-sauvegarde", version: 1, hub: "1.35", creeeLe: "2026-11-12T11:24:57.271Z",
  applis: [{ id: "f-gros", nom: "Grosse appli", version: "3", code: gros, majLe: null, nested: { a: [1, 2, { b: "}" }] } }, { id: "f-footing-x", nom: "Footing \"test\"", version: 29, code: gros.slice(0, 500000), source: "fichier" }, "intrus", 12],
  donnees: { "f-gros": { localStorage: { autre: gros.slice(0, 300000), n: 5 }, bases: [{ nom: "b", magasins: [{ nom: "m", lignes: [[1, { x: "}" }]] }] }] },
    "f-footing-x": { bases: [], localStorage: { "footing-courses": JSON.stringify(courses), "act-courante": "muscu", "pompes-journee": "null", "pompes-opt": "{\"obj\":100}", bizarre: null, nombre: 3 } },
    "f-vide": 7 },
  apres: [true, false, null, -1.5e3] };

test("sauvegarde du Hub de " + Math.round(JSON.stringify(hub).length / 1e6) + " Mo lue avec 32 Mo de mémoire : même résultat que la page", () => {
  const r = lecteur(ecrire("hub.json", JSON.stringify(hub))); assert.strictEqual(r.message, "OK"); const lu = JSON.parse(r.sortie.toString("utf8")), attendu = N.extraireHub(hub);
  attendu.journaux.forEach(j => Object.keys(j.localStorage).forEach(k => { if (typeof j.localStorage[k] !== "string") delete j.localStorage[k]; }));
  assert.deepStrictEqual(lu, attendu); assert.strictEqual(JSON.parse(lu.journaux[0].localStorage["footing-courses"])[0].notes[0].note, piege);
  const d = N.importer(N.baseVide(), lu, {}); assert.ok(d.rows.some(x => x[0] === "Séances" && x[1] === "+1")); });
test("même fichier, mis en forme avec des espaces et des retours à la ligne", () => {
  const r = lecteur(ecrire("hub-joli.json", "﻿" + JSON.stringify(hub, null, 2))); assert.strictEqual(r.message, "OK"); assert.strictEqual(JSON.parse(r.sortie.toString("utf8")).journaux[0].nom, "Footing \"test\""); });
test("sauvegarde de l'appli : recopiée telle quelle", () => {
  const s = Buffer.from(JSON.stringify(N.sauvegarde({ courses, reglages: { a: "é💪" }, imports: [] }, "test")), "utf8"); const r = lecteur(ecrire("appli.json", s)); assert.strictEqual(r.message, "OK"); assert.ok(r.sortie.equals(s)); });
test("fichiers refusés avec un message clair, sans rien écrire", () => {
  const cas = [["autre.json", "{\"format\":\"autre\",\"x\":1}", "ni une sauvegarde"], ["texte.txt", "bonjour", "ni une sauvegarde"], ["liste.json", "[1,2]", "ni une sauvegarde"], ["vide.json", "", "ni une sauvegarde"],
    ["sans-journal.json", JSON.stringify({ format: "mon-hub-sauvegarde", applis: [], donnees: { a: { localStorage: { x: "1" } } } }), "Je ne trouve pas le Journal"],
    ["coupe.json", JSON.stringify(hub).slice(0, 2000000), "abîmé"], ["coupe2.json", JSON.stringify(hub).slice(0, -40), "abîmé"]];
  cas.forEach(c => { const r = lecteur(ecrire(c[0], c[1])); assert.strictEqual(r.code, 2, c[0] + " → " + r.message); assert.ok(r.message.includes(c[2]), c[0] + " → " + r.message); assert.strictEqual(r.sortie, null, c[0]); }); });
if (process.argv[2]) test("vraie sauvegarde du Hub : même résultat que la page", () => {
  const r = lecteur(process.argv[2]); assert.strictEqual(r.message, "OK"); const vrai = JSON.parse(fs.readFileSync(process.argv[2], "utf8"));
  assert.deepStrictEqual(JSON.parse(r.sortie.toString("utf8")), N.extraireHub(vrai)); console.log("   " + Math.round(fs.statSync(process.argv[2]).size / 1e6) + " Mo lus, extrait de " + Math.round(r.sortie.length / 1024) + " Ko"); });
fs.rmSync(tmp, { recursive: true, force: true });
console.log("\n" + n + " tests réussis.");
