// 🎙️ Mots-codes de fin de dictée (essai 36) : « terminé », « lire » / « relire », « effacer », seulement en DERNIER mot.
// essai 38 : « copier » = comme « terminé » + presse-papier.
const { execFileSync } = require("child_process"), fs = require("fs"), path = require("path"), os = require("os");
const racine = path.join(__dirname, "..", ".."), src = path.join(racine, "android-activite/app/src/main/java/fr/mesapplis/activite"), tmp = fs.mkdtempSync(path.join(os.tmpdir(), "codes-"));
execFileSync("javac", ["-encoding", "UTF-8", "-d", path.join(tmp, "classes"), path.join(src, "CodesVoix.java"), path.join(racine, "android-activite/tests/EssaiCodesVoix.java")], { stdio: "inherit" });
const cas = [
  ["bonne sensation ce matin au réveil terminé", "terminé | bonne sensation ce matin au réveil"],
  ["bonne sensation Terminé.", "terminé | bonne sensation"],
  ["eau 20 cl, terminée", "terminé | eau 20 cl"],
  ["Terminer", "terminé | "],
  ["alimentation eau 20 cl relire", "lire | alimentation eau 20 cl"],
  ["eau 20 cl lire", "lire | eau 20 cl"],
  ["eau relire terminé", "lire | eau"],
  ["effacer", "effacer | "],
  ["bla bla effacer", "effacer | bla bla"],
  ["Efface.", "effacer | "],
  ["effacer les traces de béton", "aucun | effacer les traces de béton"],
  ["relire le plan avec Dani", "aucun | relire le plan avec Dani"],
  ["il a terminé le regard", "aucun | il a terminé le regard"],
  ["c'est du délire", "aucun | c'est du délire"],
  ["l'effacement", "aucun | l'effacement"],
  ["poids 62,3 envoyer à Claude terminé", "terminé | poids 62,3 envoyer à Claude"],
  ["rendez-vous avec Serge lundi copier", "copier | rendez-vous avec Serge lundi"],
  ["eau 20 cl, copié.", "copier | eau 20 cl"],
  ["copier le plan pour Dani", "aucun | copier le plan pour Dani"],
  ["la photocopie", "aucun | la photocopie"],
];
fs.writeFileSync(path.join(tmp, "p.txt"), cas.map(c => c[0]).join("\n") + "\n");
const sortie = execFileSync("java", ["-cp", path.join(tmp, "classes"), "fr.mesapplis.activite.EssaiCodesVoix", path.join(tmp, "p.txt")], { encoding: "utf8" }).trim().split("\n");
let ko = 0; cas.forEach((c, i) => { const ok = sortie[i] === c[1]; if (!ok) ko++; console.log((ok ? "✅" : "❌") + " « " + c[0] + " » → " + sortie[i] + (ok ? "" : "   (attendu : " + c[1] + ")")); });
console.log(ko ? "❌ " + ko + " erreur(s)" : "✅ " + cas.length + " phrases correctes"); process.exit(ko ? 1 : 0);
