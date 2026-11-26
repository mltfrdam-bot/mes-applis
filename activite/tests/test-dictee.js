// 🎙️ Dictée de l'alimentation (essai 31) : la phrase dictée donne-t-elle les bons favoris et les bonnes quantités ?
const { execFileSync } = require("child_process"), fs = require("fs"), path = require("path"), os = require("os");
const racine = path.join(__dirname, "..", ".."), src = path.join(racine, "android-activite/app/src/main/java/fr/mesapplis/activite"), tmp = fs.mkdtempSync(path.join(os.tmpdir(), "dictee-"));
execFileSync("javac", ["-encoding", "UTF-8", "-d", path.join(tmp, "classes"), path.join(src, "Dictee.java"), path.join(racine, "android-activite/tests/EssaiDictee.java")], { stdio: "inherit" });
const favs = [
  "e|Eau|ml|200||1", "lv|Lait de vache|ml|200||0", "bg|Baguette|g|125|pain|0", "ra|Ratatouille|g|90||0", "rz|Riz basmati cuit|g|90||0",
  "cf|Café|ml|100||0", "pm|Pomme|portion|1||0", "ss|Boisson sucre-sel|ml|500||0", "lc|Lait de coco|ml|100||0"];
const cas = [
  ["eau 20 cl, lait de vache 20 cl, baguette 125 g", "e=200 ; lv=200 ; bg=125 | ?"],
  ["20 centilitres d'eau", "e=200 | ?"],
  ["20 cl d'eau et 125 g de baguette", "e=200 ; bg=125 | ?"],
  ["eau", "e=200* | ?"],
  ["lait de vache", "lv=200* | ?"],
  ["baguette ratatouille riz", "bg=125* ; ra=90* ; rz=90* | ?"],
  ["Eau 20cl", "e=200 | ?"],
  ["l'eau 33 cl", "e=330 | ?"],
  ["un demi litre d'eau", "e=500 | ?"],
  ["deux cent cinquante grammes de riz", "rz=250 | ?"],
  ["riz 150", "rz=150 | ?"],
  ["2 pommes", "pm=2 | ?"],
  ["une pomme", "pm=1* | ?"],
  ["du pain 60 g", "bg=60 | ?"],
  ["eau 20 cl lait de vache 20 cl baguette 125 g", "e=200 ; lv=200 ; bg=125 | ?"],
  ["café 10 cl et chocolat 30 g", "cf=100 | ?chocolat 30 g"],
  ["lait de coco 5 cl", "lc=50 | ?"],
  ["j'ai bu 20 cl d'eau", "e=200 | ?"],
  ["ratatouile 90 g", "ra=90 | ?"],
  ["1,5 l d'eau", "e=1500 | ?"],
  ["jeudi 20 cl", " | ?jeudi 20 cl"],
  ["lait 20 cl", " | ?lait 20 cl"],
  ["eau 20 cl, pain", "e=200 ; bg=125* | ?"],
  ["alimentation 5h du matin eau 20 cl lait de vache 20 cl baguette 125 G", "e=200 ; lv=200 ; bg=125 | ? @05:00"],
  ["alimentation 5h du matin au 20 cl les 20 cl baguette 125 G", "bg=125 | ?20 cl 20 cl @05:00"],
  ["12 h 30 riz 90 g", "rz=90 | ? @12:30"],
  ["à midi ratatouille", "ra=90* | ? @12:00"],
  ["café 10 cl à 7 h du soir", "cf=100 | ? @19:00"],
  ["à 6 heures 20 cl d'eau", "e=200 | ? @06:00"],
  // essai 34 : mots-codes santé (+ enchaînement)
  ["tension 13 8 pouls 62", "tension=130/80p62 | ?"],
  ["tension 130 sur 80", "tension=130/80 | ?"],
  ["tension 13/8", "tension=130/80 | ?"],
  ["tension treize huit", "tension=130/80 | ?"],
  ["tension 138", "tension=130/80 | ?"],
  ["tension à 7 h 13 8", "tension=130/80 | ? @07:00"],
  ["tension 13", " | ?tension « 13 » : il me faut les 2 chiffres"],
  ["poids 62,3", "poids=62,3 | ?"],
  ["poids 62 kilos 300", "poids=62,3 | ?"],
  ["poids soixante deux virgule trois", "poids=62,3 | ?"],
  ["douleur genou droit 4", "douleur=Genou droit:4 | ?"],
  ["douleur au genou droit 4 sur 10, après le tennis", "douleur=Genou droit:4:après le tennis | ?"],
  ["douleur genou droit finie", "douleur=Genou droit:0:fini | ?"],
  ["douleur épaule gauche", " | ?douleur épaule gauche : je n'ai pas compris le niveau de 0 à 10"],
  ["poids 62,3, tension 13 8, alimentation eau 20 cl", "e=200 ; poids=62,3 ; tension=130/80 | ?"],
  ["alimentation 5h du matin de l'eau 20 cl tension 12 7", "e=200 ; tension=120/70 | ? @05:00"],
];
fs.writeFileSync(path.join(tmp, "f.txt"), favs.join("\n") + "\n"); fs.writeFileSync(path.join(tmp, "p.txt"), cas.map(c => c[0]).join("\n") + "\n");
const sortie = execFileSync("java", ["-cp", path.join(tmp, "classes"), "fr.mesapplis.activite.EssaiDictee", path.join(tmp, "f.txt"), path.join(tmp, "p.txt")], { encoding: "utf8" }).trim().split("\n");
let ko = 0; cas.forEach((c, i) => { const ok = sortie[i] === c[1]; if (!ok) ko++; console.log((ok ? "✅" : "❌") + " « " + c[0] + " » → " + sortie[i] + (ok ? "" : "   (attendu : " + c[1] + ")")); });
console.log(ko ? ko + " échec(s)" : "Dictée : " + cas.length + " phrases comprises"); process.exit(ko ? 1 : 0);
