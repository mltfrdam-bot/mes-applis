/* Teste le fantôme de course de la partie Android (Fantome.java), hors du téléphone. node activite/tests/test-fantome.js */
"use strict";
const assert = require("assert"), fs = require("fs"), os = require("os"), path = require("path"), { execFileSync, spawnSync } = require("child_process");
const racine = path.join(__dirname, "..", ".."), src = path.join(racine, "android-activite/app/src/main/java/fr/mesapplis/activite"), tmp = fs.mkdtempSync(path.join(os.tmpdir(), "fantome-"));
const env = Object.assign({}, process.env); delete env.JAVA_TOOL_OPTIONS;
execFileSync("javac", ["-encoding", "UTF-8", "-d", path.join(tmp, "classes"), path.join(src, "Fantome.java"), path.join(src, "Json.java"), path.join(racine, "android-activite/tests/EssaiFantome.java")], { env, stdio: "inherit" });
function java(fantome, questions) { const f = path.join(tmp, "f.json"), q = path.join(tmp, "q.txt"), s = path.join(tmp, "s.json"); fs.writeFileSync(f, typeof fantome === "string" ? fantome : JSON.stringify(fantome)); fs.writeFileSync(q, questions.join("\n") + "\n");
  const r = spawnSync("java", ["-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-cp", path.join(tmp, "classes"), "fr.mesapplis.activite.EssaiFantome", f, q, s], { env, encoding: "utf8" }); assert.strictEqual((r.stdout || "").trim(), "OK", r.stderr); return JSON.parse(fs.readFileSync(s, "utf8")); }
let n = 0; function test(nom, fn) { fn(); n++; console.log("✅ " + nom); }

/* un fantôme simple : 10 s d'attente du GPS, puis 3 m/s pendant 1 000 s (3 km), comme l'écrit la page */
const prof = []; for (let s = 0; s <= 1010; s += 5) prof.push(Math.max(0, Math.round((s - 10) * 3)));
const F = { ref: 1794577582627, nom: "Course du 13/11 à 17:46", premier: 10, duree: 1010, dist: 3000, pas: 5, prof };

test("le fantôme avance à son rythme : écart en mètres et en secondes, devant comme derrière", () => {
  const r = java(F, ["E 100 300", "E 100 360", "E 100 240", "E 0 0", "E 500 1500", "E 502 1512"]), e = r.reponses; assert.strictEqual(r.lu, true);
  assert.deepStrictEqual([e[0].m, e[0].s, e[0].dist, e[0].fini], [0, 0, 300, false]); assert.strictEqual(e[0].nom, "Course du 13/11 à 17:46"); assert.strictEqual(e[0].total, 3000); assert.strictEqual(e[0].temps, 1000);
  assert.deepStrictEqual([e[1].m, e[1].s], [60, 20], "60 m devant = 20 s d'avance"); assert.deepStrictEqual([e[2].m, e[2].s], [-60, -20], "60 m derrière = 20 s de retard");
  assert.deepStrictEqual([e[3].m, e[3].s], [0, 0]); assert.deepStrictEqual([e[4].m, e[4].s], [0, 0]); assert.deepStrictEqual([e[5].m, e[5].s, e[5].dist], [6, 2, 1506], "entre deux points du profil"); });

test("quand le fantôme a fini, ou quand on a dépassé sa distance : l'écart reste juste", () => {
  const e = java(F, ["E 1000 3000", "E 1100 3000", "E 900 3000", "E 950 3100", "E 1200 2700"]).reponses;
  assert.deepStrictEqual([e[0].m, e[0].s, e[0].fini], [0, 0, true]); assert.deepStrictEqual([e[1].m, e[1].s, e[1].fini], [0, -100, true], "arrivé 100 s après lui");
  assert.deepStrictEqual([e[2].m, e[2].s, e[2].fini], [300, 100, false], "arrivé 100 s avant lui"); assert.deepStrictEqual([e[3].m, e[3].s], [250, 50]); assert.deepStrictEqual([e[4].m, e[4].s, e[4].dist], [-300, -300, 3000]); });

test("fantôme qui s'est arrêté 2 minutes (feu rouge) : le temps à une distance donnée reste le bon", () => {
  const p = []; for (let s = 0; s <= 600; s += 5) p.push(s < 200 ? s * 3 : s < 320 ? 600 : 600 + (s - 320) * 3); const G = { ref: 1, nom: "Arrêt", premier: 0, duree: 600, dist: 1440, pas: 5, prof: p }, e = java(G, ["E 250 600", "E 250 590", "E 250 610", "E 330 600"]).reponses;
  assert.deepStrictEqual([e[0].m, e[0].dist], [0, 600]); assert.strictEqual(e[0].s, 0, "au même endroit que le fantôme arrêté : égalité"); assert.ok(Math.abs(e[1].s + 53) <= 1, String(e[1].s)); assert.ok(Math.abs(e[2].s - 73) <= 1, "10 m plus loin : le fantôme y passera à 323 s : " + e[2].s); assert.deepStrictEqual([e[3].m, e[3].s], [-30, -130]); });

test("les phrases dites par la voix", () => {
  const e = java(F, ["P 52 12", "P -30 9", "P 2 1", "P 148 61", "P -1250 400", "P 2400 -3", "P 12 0.2", "P -7 1", "P 1000 120"]).reponses;
  assert.deepStrictEqual(e, ["52 mètres d'avance, 12 secondes.", "30 mètres de retard, 9 secondes.", "À égalité avec le fantôme.", "150 mètres d'avance, 1 minute 1.", "1,3 kilomètre de retard, 6 minutes 40.", "2,4 kilomètres d'avance, 3 secondes.", "12 mètres d'avance.", "7 mètres de retard, 1 seconde.", "1 kilomètre d'avance, 2 minutes."]); });

test("fichier vide, abîmé ou trop court : pas de fantôme, pas de plantage", () => {
  for (const t of ["", "{}", "n'importe quoi", '{"ref":1,"prof":[0,1]}', '{"ref":1,"pas":5,"duree":20,"dist":10,"prof":[0,5,10,10]}', '{"ref":1,"prof":[0,a,3]}']) assert.strictEqual(java(t, ["E 10 10"]).lu, false, t);
  const mini = java('{"ref":7,"nom":"Sans \\"rien\\"","prof":[0,100,200,300,400,500,600,700]}', ["E 10 250"]); assert.strictEqual(mini.lu, true); assert.strictEqual(mini.reponses[0].total, 700); assert.strictEqual(mini.reponses[0].m, 50); });

test("1.13 : prévision d'arrivée (Java et page donnent le même calcul ; la montée du fantôme est comptée)", () => {
  /* fantôme : 4 km de plat à 4:30 /km (1 080 s) puis 2 km de montée à 6:15 /km (750 s) : 6 km en 30:30 */
  const tG = (x) => x <= 4000 ? x * 0.27 : 1080 + (x - 4000) * 0.375, dG = (t) => t <= 1080 ? t / 0.27 : Math.min(6000, 4000 + (t - 1080) / 0.375);
  const p = []; for (let s = 0; s <= 1830; s += 5) p.push(Math.round(dG(s))); const G = { ref: 2, nom: "Côte", premier: 0, duree: 1830, dist: 6000, pas: 5, prof: p };
  /* moi : 4 % plus vite partout ; au km 3 (sur le plat), la prévision doit déjà compter la montée : 0,96 × 30:30 = 29:17 */
  const moi = (x) => 0.96 * tG(x), sec = moi(3000), d0 = dG((sec - 30) / 0.96);
  const N = require("../noyau.js"), r = java(G, ["V " + sec + " 3000 " + (sec - 30) + " " + d0, "V 300 900 270 800", "V 1800 6000 1770 5900", "Q 1781 11", "Q 1890 -60", "T 900 3000 0 500"]), e = r.reponses;
  assert.ok(Math.abs(e[0][0] - 1757) <= 2, "arrivée prévue " + e[0][0]); assert.ok(Math.abs(e[0][1] - 73) <= 2, "écart prévu " + e[0][1]);
  assert.ok(Math.abs(e[0][2] - 279) <= 2, "à tenir maintenant " + e[0][2]); assert.ok(Math.abs(e[0][3] - 351) <= 2, "moyenne sur le restant " + e[0][3]);
  assert.strictEqual(e[1], null, "avant le 1er km : calcul en cours"); assert.strictEqual(e[2], null, "à la distance du fantôme : plus de prévision");
  assert.strictEqual(e[3], "Arrivée prévue 29 minutes 41, 11 secondes d'avance."); assert.strictEqual(e[4], "Arrivée prévue 31 minutes 30, 1 minute de retard.");
  assert.ok(Math.abs(e[5].tour - 135) <= 1, "le fantôme a mis 135 s sur les 500 derniers mètres : " + e[5].tour); assert.strictEqual(e[5].prev, null);
  const js = N.prevFantome(G, sec, 3000, sec - 30, d0); assert.ok(Math.abs(js.t - e[0][0]) <= 1 && Math.abs(js.e - e[0][1]) <= 1 && Math.abs(js.tenir - e[0][2]) <= 1 && Math.abs(js.moy - e[0][3]) <= 1, JSON.stringify(js));
  /* fantôme hors d'atteinte : « à tenir » = -1 */ const loin = java(G, ["V 1850 5000 1820 4920"]).reponses[0]; assert.strictEqual(loin[2], -1); assert.ok(loin[1] < 0); });

fs.rmSync(tmp, { recursive: true, force: true });
console.log("\n" + n + " tests réussis.");
