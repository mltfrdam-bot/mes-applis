/* Teste le langage des home-trainers (Ftms.java) et l'enregistrement d'une séance de home-trainer (Velo.java), hors du téléphone. node activite/tests/test-ftms.js */
"use strict";
const assert = require("assert"), fs = require("fs"), os = require("os"), path = require("path"), { execFileSync, spawnSync } = require("child_process");
const racine = path.join(__dirname, "..", ".."), src = path.join(racine, "android-activite/app/src/main/java/fr/mesapplis/activite"), tmp = fs.mkdtempSync(path.join(os.tmpdir(), "ftms-"));
const env = Object.assign({}, process.env); delete env.JAVA_TOOL_OPTIONS;
execFileSync("javac", ["-encoding", "UTF-8", "-d", path.join(tmp, "classes"), path.join(src, "Ftms.java"), path.join(src, "Velo.java"), path.join(racine, "android-activite/tests/EssaiFtms.java")], { env, stdio: "inherit" });
const r = spawnSync("java", ["-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-cp", path.join(tmp, "classes"), "fr.mesapplis.activite.EssaiFtms", path.join(tmp, "velo.txt"), path.join(tmp, "s.json")], { env, encoding: "utf8" });
assert.strictEqual((r.stdout || "").trim(), "OK", r.stderr); const e = JSON.parse(fs.readFileSync(path.join(tmp, "s.json"), "utf8"));
let n = 0; function test(nom, fn) { fn(); n++; console.log("✅ " + nom); }
test("mesures du home-trainer : vitesse, cadence, puissance, trame en deux morceaux, trame abîmée", () => {
  assert.deepStrictEqual(e.slice(0, 4), [true, 30.5, 90, 215]); assert.deepStrictEqual(e.slice(4, 8), [true, 1234, 180, 30.5], "la vitesse d'avant reste quand la trame n'en a pas"); assert.strictEqual(e[8], false); });
test("commandes : ERG en watts, pente en centièmes de %, résistance selon la plage, rien en mode libre", () => {
  assert.deepStrictEqual(e.slice(9, 15), ["05d700", "1100008a022833", "110000d4fe2833", "04f401", "0464", null]); });
test("réponses du home-trainer en phrases simples", () => {
  assert.strictEqual(e[15], ""); assert.ok(/ne connaît pas le mode pente/.test(e[16])); assert.ok(/autre appli/.test(e[17])); assert.strictEqual(e[18], null); assert.deepStrictEqual(e[19], [0, 1000]); });
test("enregistrement à la seconde, gardé après appli tuée (même résumé relu dans le fichier)", () => {
  const v = e[20], rl = e[21]; assert.strictEqual(v.pMoy, 170); assert.strictEqual(v.pMax, 210); assert.strictEqual(v.cadMoy, 85); assert.strictEqual(v.vitMoy, 36); assert.strictEqual(v.dist, 30);
  assert.deepStrictEqual(v.l, [[0, 150, 85, 360, 1, 1500], [1, 150, 85, 360, 1, 1500], [2, 210, 85, 360, 2, 60]], "une seule ligne par seconde"); assert.deepStrictEqual(rl, v); assert.strictEqual(e[22], 2); assert.strictEqual(e[23], 6); });
fs.rmSync(tmp, { recursive: true, force: true });
console.log("\n" + n + " tests réussis.");
