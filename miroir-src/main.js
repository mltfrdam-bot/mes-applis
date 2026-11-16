// Miroir — afficher et piloter le téléphone depuis la tablette (câble USB).
// Construit avec les briques libres de Tango (ya-webadb, licence MIT) et le serveur scrcpy (Apache 2.0).
import { Adb, AdbDaemonTransport, AdbPacket, AdbPacketSerializeStream } from "@yume-chan/adb";
import { MaybeConsumable, StructDeserializeStream, pipeFrom, ReadableStream as RS } from "@yume-chan/stream-extra";
import { AdbDaemonWebUsbDeviceManager, AdbDaemonWebUsbDevice } from "@yume-chan/adb-daemon-webusb";
import AdbWebCredentialStore from "@yume-chan/adb-credential-web";
import { AdbScrcpyClient, AdbScrcpyOptionsLatest } from "@yume-chan/adb-scrcpy";
import {
  DefaultServerPath,
  ScrcpyVideoCodecId,
  ScrcpyPointerId,
  AndroidMotionEventAction,
  AndroidKeyEventAction,
  AndroidScreenPowerMode,
} from "@yume-chan/scrcpy";
import {
  WebCodecsVideoDecoder,
  WebGLVideoFrameRenderer,
  BitmapVideoFrameRenderer,
} from "@yume-chan/scrcpy-decoder-webcodecs";

const VERSION = "2.2";
// Dans l'appli Android « Miroir » (sans fil), la page dispose du pont MiroirApp.
const APPLI = typeof window.MiroirApp !== "undefined";
const LIEN_APK = "https://github.com/mltfrdam-bot/mes-applis/releases/download/miroir/Miroir.apk";
const $ = (id) => document.getElementById(id);
const attendre = (ms) => new Promise((r) => setTimeout(r, ms));

// ---------- Réglages gardés sur la tablette ----------
const REGLAGES_DEFAUT = { ecranEteint: false, qualite: "normale", memoCode: false, code: "", autoConnexion: true, adresseTel: "" };
let reglages = { ...REGLAGES_DEFAUT };
try { reglages = { ...REGLAGES_DEFAUT, ...JSON.parse(localStorage.getItem("miroir-reglages") || "{}") }; } catch {}
function sauverReglages() { try { localStorage.setItem("miroir-reglages", JSON.stringify(reglages)); } catch {} }

// ---------- État ----------
const manager = AdbDaemonWebUsbDeviceManager.BROWSER;
const clesWeb = new AdbWebCredentialStore("Miroir");

// Clé de l'ordinateur (fichier « adbkey ») : le téléphone l'a déjà acceptée,
// donc plus besoin d'appuyer sur « Autoriser » sur le téléphone.
function lireCleOrdi() { try { const b = localStorage.getItem("miroir-cle-ordi"); return b ? Uint8Array.from(atob(b), (c) => c.charCodeAt(0)) : null; } catch { return null; } }
const cles = {
  generateKey: () => clesWeb.generateKey(),
  async *iterateKeys() {
    const ordi = lireCleOrdi();
    if (ordi) yield { buffer: ordi, name: "ordinateur" };
    for await (const k of clesWeb.iterateKeys()) yield k;
  },
};
function longueurDer(n) { if (n < 128) return [n]; const o = []; while (n > 0) { o.unshift(n & 255); n >>= 8; } return [0x80 | o.length, ...o]; }
function derBloc(tag, contenu) { return Uint8Array.from([tag, ...longueurDer(contenu.length), ...contenu]); }
function pemVersPkcs8(texte) {
  const m = texte.match(/-----BEGIN (RSA )?PRIVATE KEY-----([\s\S]+?)-----END/);
  if (!m) throw new Error("cle-invalide");
  const der = Uint8Array.from(atob(m[2].replace(/\s+/g, "")), (c) => c.charCodeAt(0));
  if (!m[1]) return der; // déjà au bon format (PKCS#8)
  // PKCS#1 → PKCS#8
  const algo = Uint8Array.from([0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00]);
  return derBloc(0x30, [0x02, 0x01, 0x00, ...algo, ...derBloc(0x04, der)]);
}
async function importerCleOrdi(fichier) {
  try {
    const der = pemVersPkcs8(await fichier.text());
    localStorage.setItem("miroir-cle-ordi", btoa(String.fromCharCode(...der)));
    majCleOrdi();
    message("✔ Clé enregistrée. Appuie sur Connecter : il n'y aura rien à autoriser sur le téléphone.", "info");
  } catch {
    message("Ce fichier n'est pas une clé. Choisis « miroir-cle.adbkey » (dans Téléchargements) ou le fichier « adbkey » de l'ordinateur (sans « .pub »).", "erreur");
  }
}
function majCleOrdi() {
  const ok = !!lireCleOrdi();
  $("etat-cle").textContent = ok ? "🔑 Clé enregistrée ✔" : "";
  $("etat-cle").hidden = !ok;
}
let adb = null, client = null, decodeur = null, toile = null;
let largeurVideo = 0, hauteurVideo = 0;
let connexionEnCours = false;
let verrouEcran = null;

// ---------- Messages ----------
function message(texte, type = "info") {
  const m = $("message");
  m.textContent = texte;
  m.className = "message " + type;
  m.hidden = !texte;
}
function bulle(texte) {
  const b = $("bulle");
  b.textContent = texte;
  b.hidden = false;
  clearTimeout(bulle.t);
  bulle.t = setTimeout(() => (b.hidden = true), 2500);
}
function traduireErreur(e) {
  const t = String((e && (e.message || e.name)) || e);
  if (e instanceof AdbDaemonWebUsbDevice.DeviceBusyError || /busy|claim|in use/i.test(t))
    return "Le téléphone est déjà utilisé par une autre page (Tango ou un autre onglet). Ferme-la, débranche et rebranche le câble, puis réessaie.";
  if (/NotFoundError|No device selected/i.test(t)) return "Aucun téléphone trouvé. Si la tablette affiche ⚡, débranche le câble et rebranche-le en inversant les deux bouts.";
  if (/SecurityError|Access denied/i.test(t)) return "Chrome n'a pas le droit d'accéder au téléphone. Débranche, rebranche, puis réessaie.";
  if (/NetworkError|transfer|disconnected|closed/i.test(t)) return "La liaison avec le téléphone a été coupée (câble débranché ?).";
  if (/usb-refuse/.test(t)) return "Miroir n'a pas l'autorisation d'utiliser le câble. Débranche, rebranche, puis appuie sur « OK » (coche « Toujours »).";
  if (/cable-injoignable/.test(t)) return "Je n'arrive pas à parler au téléphone par le câble. Si Miroir est ouvert dans Chrome, ferme-le ; puis débranche et rebranche le câble.";
  if (/sansfil-injoignable|delai/i.test(t) && APPLI)
    return "Le téléphone ne répond pas en sans fil. Vérifie : 1) la tablette est connectée au point d'accès du téléphone ; 2) depuis le dernier redémarrage du téléphone, tu as bien appuyé sur « 📶 Sans fil » dans Miroir par câble (Chrome).";
  if (/sansfil-coupe/i.test(t)) return "La liaison sans fil a été coupée.";
  if (/VideoDecoder|codec/i.test(t)) return "La tablette n'arrive pas à décoder la vidéo du téléphone.";
  return "Erreur : " + t;
}

// ---------- Écrans ----------
function afficherAccueil() {
  $("accueil").hidden = false;
  $("miroir").hidden = true;
  relacherVerrou();
}
function afficherMiroir() {
  if (!history.state?.miroir) history.pushState({ miroir: 1 }, "");
  $("accueil").hidden = true;
  $("miroir").hidden = false;
  prendreVerrou();
}

// Garder l'écran de la tablette allumé pendant le pilotage
async function prendreVerrou() {
  try { if ("wakeLock" in navigator && !verrouEcran) { verrouEcran = await navigator.wakeLock.request("screen"); verrouEcran.addEventListener("release", () => (verrouEcran = null)); } } catch {}
}
function relacherVerrou() { try { verrouEcran?.release(); } catch {} verrouEcran = null; }
document.addEventListener("visibilitychange", () => { if (document.visibilityState === "visible" && client) prendreVerrou(); });

// ---------- Connexion ----------
// ---------- Sans fil : liaison ADB par le relais de l'appli Android ----------
function adresseTelephone() {
  if (reglages.adresseTel) return reglages.adresseTel.trim();
  try { return window.MiroirApp.passerelle() || ""; } catch { return ""; }
}
// Autorisation Android d'utiliser le câble (fenêtre « Autoriser Miroir à accéder à… »)
function autorisationUsb() {
  try { if (window.MiroirApp.usbAutorise()) return Promise.resolve(true); } catch { return Promise.resolve(false); }
  return new Promise((ok) => {
    window.miroirUsbPermission = (oui) => { window.miroirUsbPermission = null; ok(!!oui); };
    message("👉 Appuie sur « OK » pour autoriser Miroir à utiliser le câble (coche « Toujours » si proposé).");
    window.MiroirApp.demanderUsb();
  });
}
function appareilSansFil(ip) { return appareilRelais("?h=" + encodeURIComponent(ip) + "&p=5555", ip + ":5555", "téléphone (sans fil)", false); }
function appareilCable() { return appareilRelais("?usb=1", "cable", "téléphone (câble)", true); }
function appareilRelais(suffixe, serie, nom, parCable) {
  let ws = null;
  return {
    serial: serie,
    name: nom,
    parCable,
    raw: { close: async () => { try { ws?.close(); } catch {} } },
    async connect() {
      if (parCable && !(await autorisationUsb())) throw new Error("usb-refuse");
      const url = window.MiroirApp.relais() + suffixe;
      ws = new WebSocket(url);
      ws.binaryType = "arraybuffer";
      await new Promise((ok, ko) => {
        ws.onopen = ok;
        ws.onerror = () => ko(new Error(parCable ? "cable-injoignable" : "sansfil-injoignable"));
        ws.onclose = () => ko(new Error(parCable ? "cable-injoignable" : "sansfil-injoignable"));
      });
      const s = ws;
      const lecture = new RS({
        start(c) {
          s.onmessage = (e) => c.enqueue(new Uint8Array(e.data));
          s.onclose = () => { try { c.close(); } catch {} };
          s.onerror = () => { try { c.error(new Error("sansfil-coupe")); } catch {} };
        },
        cancel() { try { s.close(); } catch {} },
      });
      return {
        readable: lecture.pipeThrough(new StructDeserializeStream(AdbPacket)),
        writable: pipeFrom(new MaybeConsumable.WritableStream({
          write(morceau) { if (s.readyState !== 1) throw new Error("sansfil-coupe"); s.send(morceau); },
          close() { try { s.close(); } catch {} },
          abort() { try { s.close(); } catch {} },
        }), new AdbPacketSerializeStream()),
      };
    },
  };
}

let apresPassage = false;
let momentPassage = 0;   // le téléphone vient de passer en sans fil : ne plus utiliser le câble
let modeCable = false;
async function telephoneConnu() {
  if (APPLI && !apresPassage && usbBranche()) return appareilCable();
  if (APPLI) { const ip = adresseTelephone(); return ip ? appareilSansFil(ip) : null; }
  if (!manager) return null;
  const liste = await manager.getDevices();
  return liste[0] || null;
}

async function majBoutonAccueil() {
  if (APPLI) {
    const ip = adresseTelephone();
    $("btn-connecter").textContent = "📶 Connecter sans fil";
    $("adresse-tel").value = reglages.adresseTel || "";
    $("adresse-tel").placeholder = ip ? "automatique : " + ip : "ex. 192.168.43.1";
    return;
  }
  if (!manager) {
    $("btn-connecter").disabled = true;
    message("Ce navigateur ne sait pas parler au téléphone par câble. Ouvre cette page dans Chrome sur la tablette (pas Firefox, pas Safari).", "erreur");
    return;
  }
  const tel = await telephoneConnu();
  $("btn-connecter").textContent = tel ? "▶ Reconnecter « " + tel.name + " »" : "🔌 Connecter";
}

// Limite de temps : si le téléphone ne répond pas, on abandonne l'essai au lieu de rester bloqué.
function avecDelai(promesse, ms) {
  let t;
  return Promise.race([promesse, new Promise((_, rej) => (t = setTimeout(() => rej(new Error("delai")), ms)))]).finally(() => clearTimeout(t));
}
let telEnCours = null;   // téléphone de l'essai en cours (pour pouvoir l'annuler)
let numeroEssai = 0;
async function annulerEssai() {
  numeroEssai++;
  const t = telEnCours; telEnCours = null;
  await nettoyer();
  try { await avecDelai(t?.raw.close(), 2000); } catch {}
  connexionEnCours = false;
}

// manuel = appui sur le bouton ; sinon reconnexion automatique (câble rebranché, ouverture de la page)
async function connecter(choisirNouveau = false, manuel = true) {
  if (!manuel && usbEnCours) return;
  if (connexionEnCours) {
    if (!manuel) return;
    await annulerEssai();   // le bouton relance toujours une connexion propre
  }
  connexionEnCours = true;
  const essai = ++numeroEssai;
  $("btn-connecter").disabled = false;
  const maxEssais = manuel ? 2 : 5;
  try {
    let tel = choisirNouveau ? null : await telephoneConnu();
    let nouveau = false;
    if (!tel && APPLI) {
      if (manuel) message("Je ne trouve pas le téléphone. Connecte d'abord la tablette au point d'accès Wi-Fi du téléphone, ou tape l'adresse du téléphone ci-dessous.", "erreur");
      return;
    }
    if (!tel) {
      if (!manuel) return;
      message("Choisis ton téléphone dans la liste qui s'ouvre, puis appuie sur « Connexion ».");
      tel = await manager.requestDevice();
      if (!tel) { message("Aucun téléphone trouvé. Vérifie le câble, que Tango est bien fermé et que le débogage USB est toujours activé.", "erreur"); return; }
      nouveau = true;
    }
    for (let n = 1; n <= maxEssais; n++) {
      if (essai !== numeroEssai) return;
      try {
        await uneConnexion(tel, nouveau, essai);
        return;
      } catch (e) {
        console.error(e);
        if (essai !== numeroEssai) return;
        await nettoyer();
        try { await avecDelai(tel.raw.close(), 2000); } catch {}
        if (n === maxEssais || e instanceof AdbDaemonWebUsbDevice.DeviceBusyError) { message(traduireErreur(e), "erreur"); return; }
        message(`Le téléphone n'est pas encore prêt… nouvel essai (${n + 1}/${maxEssais})`);
        await attendre(2000);
        // le téléphone a pu changer d'identité USB après le rebranchement : on le cherche à nouveau
        tel = (await telephoneConnu()) || tel;
      }
    }
  } finally {
    if (essai === numeroEssai) {
      connexionEnCours = false;
      telEnCours = null;
      $("btn-connecter").disabled = false;
      majBoutonAccueil();
    }
  }
}

async function uneConnexion(tel, nouveau, essai) {
  telEnCours = tel;
  tel_actuel = tel;
  message("Connexion au téléphone…");
  const liaison = await avecDelai(tel.connect(), 8000);
  const minuterie = setTimeout(() => message(lireCleOrdi()
    ? "Le téléphone n'accepte pas la clé de l'ordinateur. Vérifie que scrcpy marche toujours sur l'ordinateur avec ce téléphone, puis réessaie."
    : "👉 Sur le téléphone : coche « Toujours autoriser », puis appuie sur « Autoriser ». Si l'écran du téléphone est illisible, utilise le bouton « 🔑 Utiliser la clé de l'ordinateur » ci-dessous."), 2500);
  let transport;
  try {
    // 90 s la première fois (le temps d'appuyer sur « Autoriser »), 12 s pour un téléphone déjà autorisé
    transport = await avecDelai(AdbDaemonTransport.authenticate({ serial: tel.serial, connection: liaison, credentialStore: cles }), nouveau ? 90000 : 12000);
  } finally { clearTimeout(minuterie); }
  if (essai !== numeroEssai) { try { await transport.close(); } catch {} throw new Error("annule"); }
  adb = new Adb(transport);
  adb.disconnected.then(() => quandDeconnecte()).catch(() => quandDeconnecte());
  await avecDelai(lancerMiroir(), 25000);
}

let tel_actuel = null;
async function lancerMiroir() {
  message("Envoi du petit programme de recopie sur le téléphone…");
  const rep = await fetch("scrcpy-server.jar", { cache: "no-cache" }).catch(() => caches.match("scrcpy-server.jar"));
  if (!rep || !rep.ok) throw new Error("Fichier scrcpy-server.jar introuvable");
  await AdbScrcpyClient.pushServer(adb, rep.body);

  const taille = { normale: 1920, haute: 2560, economie: 1280 }[reglages.qualite] || 1920;
  const debit = { normale: 8_000_000, haute: 16_000_000, economie: 4_000_000 }[reglages.qualite] || 8_000_000;
  const options = new AdbScrcpyOptionsLatest({
    audio: false,              // le son reste sur le téléphone
    video: true,
    videoCodec: "h264",
    maxSize: taille,
    videoBitRate: debit,
    maxFps: 60,
    stayAwake: true,           // le téléphone ne se met pas en veille tant qu'il est branché
    powerOffOnClose: false,
    clipboardAutosync: false,
    control: true,
  });

  message("Démarrage de l'image…");
  client = await AdbScrcpyClient.start(adb, DefaultServerPath, options);
  client.output.pipeTo(new WritableStream({ write(l) { console.log("[téléphone]", l); } })).catch(() => {});

  const flux = await client.videoStream;
  if (!WebCodecsVideoDecoder.isSupported) throw new Error("VideoDecoder non disponible");
  const rendu = WebGLVideoFrameRenderer.isSupported ? new WebGLVideoFrameRenderer() : new BitmapVideoFrameRenderer();
  decodeur = new WebCodecsVideoDecoder({ codec: ScrcpyVideoCodecId.H264, renderer: rendu });
  toile = rendu.canvas;
  toile.id = "toile";
  $("cadre").replaceChildren(toile);
  largeurVideo = flux.metadata.width || 0;
  hauteurVideo = flux.metadata.height || 0;
  decodeur.sizeChanged(({ width, height }) => { largeurVideo = width; hauteurVideo = height; ajuster(); });
  flux.sizeChanged(({ width, height }) => { largeurVideo = width; hauteurVideo = height; ajuster(); });
  flux.stream.pipeTo(decodeur.writable).catch(() => {});
  brancherToucher(toile);

  client.exited.then(() => { if (adb) quandDeconnecte(); });

  message("");
  modeCable = !!tel_actuel?.parCable;
  document.body.classList.toggle("cable-actif", modeCable);
  afficherMiroir();
  ajuster();
  majBoutonsReglages();
  if (reglages.ecranEteint) setTimeout(() => ecranTelephone(false), 800);
  bulle("Connecté ✔");
}

let deconnexionTraitee = false;
async function quandDeconnecte() {
  if (deconnexionTraitee) return;
  deconnexionTraitee = true;
  await nettoyer();
  afficherAccueil();
  document.body.classList.remove("cable-actif");
  if (passageSansFil && APPLI) {
    apresPassage = true;
    message("✔ Le téléphone est passé en sans fil. Tu peux débrancher le câble. Connexion sans fil…", "info");
    setTimeout(() => {
      if (client || connexionEnCours) return;
      if (!adresseTelephone()) { message("✔ Le téléphone est en sans fil. Débranche le câble, connecte la tablette au point d'accès du téléphone, puis appuie sur « 📶 Connecter sans fil ».", "info"); return; }
      connecter(false, false);
    }, 3000);
  } else if (passageSansFil) {
    message("✔ Le téléphone est passé en sans fil. Tu peux débrancher le câble et ouvrir l'appli Miroir.", "info");
  } else if (APPLI) {
    message("Liaison sans fil perdue. Nouvelle tentative…", "info");
    if (reglages.autoConnexion) setTimeout(() => { if (!client && !connexionEnCours) connecter(false, false); }, 2000);
  } else {
    message("Téléphone déconnecté. Rebranche le câble puis appuie sur « Reconnecter ».", "info");
  }
  majBoutonAccueil();
}

async function nettoyer() {
  const c = client, a = adb, d = decodeur;
  client = null; adb = null; decodeur = null;
  try { await avecDelai(c?.close(), 2000); } catch {}
  try { d?.dispose(); } catch {}
  try { await avecDelai(a?.close(), 2000); } catch {}
  setTimeout(() => (deconnexionTraitee = false), 500);
}

async function deconnecter() {
  if (reglages.ecranEteint && client) { try { await ecranTelephone(true); } catch {} }
  deconnexionTraitee = true;
  await nettoyer();
  afficherAccueil();
  message("Déconnecté.");
  majBoutonAccueil();
}

// Reconnexion automatique quand on branche le câble d'un téléphone déjà autorisé
if (manager && navigator.usb) {
  navigator.usb.addEventListener("connect", async () => {
    if (!reglages.autoConnexion || client || connexionEnCours) return;
    message("Téléphone branché, connexion dans un instant…");
    await attendre(2500);   // laisser au téléphone le temps de se préparer
    if (!client) connecter(false, false);
  });
  navigator.usb.addEventListener("disconnect", (e) => {
    // câble débranché : on libère tout de suite, sans attendre
    if (client || connexionEnCours) { if (telEnCours && e.device !== telEnCours.raw && client) return; numeroEssai++; connexionEnCours = false; quandDeconnecte(); }
    else majBoutonAccueil();
  });
}

// Vérification toutes les 3 s : si le téléphone autorisé vient d'apparaître, on se connecte tout seul
// (au cas où Chrome n'a pas signalé le branchement).
let presentAvant = false;
setInterval(async () => {
  if (!manager || client) { presentAvant = !!client; return; }
  let present = false;
  try { present = !!(await telephoneConnu()); } catch {}
  if (present && !presentAvant && reglages.autoConnexion && !connexionEnCours) {
    message("Téléphone détecté, connexion…");
    connecter(false, false);
  }
  if (present !== presentAvant) majBoutonAccueil();
  presentAvant = present;
}, 3000);

// ---------- Affichage : adapter l'image à la tablette ----------
function ajuster() {
  if (!toile || !largeurVideo || !hauteurVideo) return;
  const cadre = $("cadre").getBoundingClientRect();
  const echelle = Math.min(cadre.width / largeurVideo, cadre.height / hauteurVideo);
  toile.style.width = Math.floor(largeurVideo * echelle) + "px";
  toile.style.height = Math.floor(hauteurVideo * echelle) + "px";
}
window.addEventListener("resize", ajuster);
new ResizeObserver(() => ajuster()).observe(document.getElementById("cadre"));
document.addEventListener("fullscreenchange", () => { setTimeout(ajuster, 100); majPleinEcran(); });

// ---------- Toucher ----------
function position(e) {
  const r = toile.getBoundingClientRect();
  const x = Math.max(0, Math.min(1, (e.clientX - r.left) / r.width)) * largeurVideo;
  const y = Math.max(0, Math.min(1, (e.clientY - r.top) / r.height)) * hauteurVideo;
  return { x, y };
}
function envoyerToucher(e, action) {
  const ctl = client?.controller;
  if (!ctl || !largeurVideo) return;
  const { x, y } = position(e);
  const souris = e.pointerType === "mouse";
  ctl.injectTouch({
    action,
    pointerId: souris ? ScrcpyPointerId.Mouse : BigInt(e.pointerId),
    pointerX: x, pointerY: y,
    videoWidth: largeurVideo, videoHeight: hauteurVideo,
    pressure: action === AndroidMotionEventAction.Up ? 0 : (e.pressure || 1),
    actionButton: souris ? (1 << e.button) : 0,
    buttons: souris ? e.buttons : 0,
  }).catch(() => {});
}
function brancherToucher(c) {
  c.style.touchAction = "none";
  c.addEventListener("pointerdown", (e) => { e.preventDefault(); c.setPointerCapture(e.pointerId); envoyerToucher(e, AndroidMotionEventAction.Down); });
  c.addEventListener("pointermove", (e) => { if (e.pointerType === "mouse" && !e.buttons) return; if (!c.hasPointerCapture(e.pointerId)) return; e.preventDefault(); envoyerToucher(e, AndroidMotionEventAction.Move); });
  c.addEventListener("pointerup", (e) => { e.preventDefault(); envoyerToucher(e, AndroidMotionEventAction.Up); });
  c.addEventListener("pointercancel", (e) => { envoyerToucher(e, AndroidMotionEventAction.Up); });
  c.addEventListener("contextmenu", (e) => e.preventDefault());
  c.addEventListener("wheel", (e) => {
    e.preventDefault();
    const ctl = client?.controller; if (!ctl) return;
    const { x, y } = position(e);
    ctl.injectScroll({ pointerX: x, pointerY: y, videoWidth: largeurVideo, videoHeight: hauteurVideo, scrollX: -Math.sign(e.deltaX), scrollY: -Math.sign(e.deltaY), buttons: 0 }).catch(() => {});
  }, { passive: false });
}

// Glisser automatique (pour déverrouiller)
async function glisser(x1, y1, x2, y2, etapes = 10) {
  const ctl = client?.controller; if (!ctl) return;
  const base = { pointerId: ScrcpyPointerId.VirtualFinger, videoWidth: largeurVideo, videoHeight: hauteurVideo, actionButton: 0, buttons: 0 };
  await ctl.injectTouch({ ...base, action: AndroidMotionEventAction.Down, pointerX: x1, pointerY: y1, pressure: 1 });
  for (let i = 1; i <= etapes; i++) {
    await attendre(15);
    await ctl.injectTouch({ ...base, action: AndroidMotionEventAction.Move, pointerX: x1 + (x2 - x1) * i / etapes, pointerY: y1 + (y2 - y1) * i / etapes, pressure: 1 });
  }
  await ctl.injectTouch({ ...base, action: AndroidMotionEventAction.Up, pointerX: x2, pointerY: y2, pressure: 0 });
}

// ---------- Touches ----------
const TOUCHE = { retour: 4, accueil: 3, recents: 187, marche: 26, reveil: 224, entree: 66, effacer: 67, volPlus: 24, volMoins: 25 };
async function touche(code) {
  const ctl = client?.controller; if (!ctl) return;
  await ctl.injectKeyCode({ action: AndroidKeyEventAction.Down, keyCode: code, repeat: 0, metaState: 0 });
  await ctl.injectKeyCode({ action: AndroidKeyEventAction.Up, keyCode: code, repeat: 0, metaState: 0 });
}

async function ecranTelephone(allume) {
  const ctl = client?.controller; if (!ctl) return;
  await ctl.setScreenPowerMode(allume ? AndroidScreenPowerMode.Normal : AndroidScreenPowerMode.Off);
}

// ---------- Déverrouiller ----------
let codeSaisi = "";
function ouvrirDeverrouillage() {
  codeSaisi = reglages.memoCode ? reglages.code : "";
  $("memo-code").checked = reglages.memoCode;
  majCode();
  ouvrir("panneau-code");
}
function majCode() { $("code-affiche").textContent = codeSaisi ? "•".repeat(codeSaisi.length) : "—"; }
async function deverrouiller() {
  const code = codeSaisi;
  reglages.memoCode = $("memo-code").checked;
  reglages.code = reglages.memoCode ? code : "";
  sauverReglages();
  fermer("panneau-code");
  if (!client) return;
  bulle("Déverrouillage…");
  await touche(TOUCHE.reveil);
  await attendre(500);
  await glisser(largeurVideo / 2, hauteurVideo * 0.85, largeurVideo / 2, hauteurVideo * 0.3);
  await attendre(700);
  if (code) {
    await client.controller.injectText(code);
    await attendre(200);
    await touche(TOUCHE.entree);
  }
  if (reglages.ecranEteint) setTimeout(() => ecranTelephone(false), 1200);
}

// ---------- Capture d'écran ----------
async function capture() {
  if (!decodeur) return;
  const image = await decodeur.snapshot();
  if (!image) { bulle("Capture impossible pour l'instant"); return; }
  const nom = "Capture_" + horodatage() + ".png";
  telechargerBlob(image, nom);
  bulle("📷 Capture enregistrée dans Téléchargements de la tablette");
}
function horodatage() {
  const d = new Date(), p = (n) => String(n).padStart(2, "0");
  return d.getFullYear() + p(d.getMonth() + 1) + p(d.getDate()) + "_" + p(d.getHours()) + p(d.getMinutes()) + p(d.getSeconds());
}
async function telechargerBlob(blob, nom) {
  if (APPLI) {
    const id = window.MiroirApp.fichierDebut(nom, blob.type || "application/octet-stream");
    const PAS = 512 * 1024;
    for (let i = 0; i < blob.size; i += PAS) {
      const octets = new Uint8Array(await blob.slice(i, i + PAS).arrayBuffer());
      let bin = ""; for (let j = 0; j < octets.length; j += 8192) bin += String.fromCharCode(...octets.subarray(j, j + 8192));
      window.MiroirApp.fichierMorceau(id, btoa(bin));
    }
    window.MiroirApp.fichierFin(id);
    return;
  }
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url; a.download = nom; document.body.appendChild(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 60000);
}

// ---------- Fichiers ----------
let dossierCourant = "/sdcard/Download";
const RACCOURCIS = [
  ["📥 Téléchargements", "/sdcard/Download"],
  ["📷 Photos", "/sdcard/DCIM/Camera"],
  ["🖼️ Captures", "/sdcard/DCIM/Screenshots"],
  ["📄 Documents", "/sdcard/Documents"],
  ["📁 Tout le téléphone", "/sdcard"],
];
function tailleLisible(o) {
  o = Number(o);
  if (o < 1024) return o + " o";
  if (o < 1048576) return (o / 1024).toFixed(0) + " Ko";
  if (o < 1073741824) return (o / 1048576).toFixed(1) + " Mo";
  return (o / 1073741824).toFixed(2) + " Go";
}
async function ouvrirFichiers() {
  $("raccourcis").replaceChildren(...RACCOURCIS.map(([nom, chemin]) => {
    const b = document.createElement("button"); b.textContent = nom; b.onclick = () => listerDossier(chemin); return b;
  }));
  ouvrir("panneau-fichiers");
  await listerDossier(dossierCourant);
}
async function listerDossier(chemin) {
  if (!adb) return;
  dossierCourant = chemin;
  $("chemin").textContent = chemin.replace("/sdcard", "Téléphone");
  const tbody = $("liste-fichiers");
  tbody.innerHTML = "<tr><td colspan=3>Chargement…</td></tr>";
  const sync = await adb.sync();
  try {
    let entrees = (await sync.readdir(chemin)).filter((e) => e.name !== "." && e.name !== ".." && !e.name.startsWith("."));
    entrees.sort((a, b) => (b.type === 4) - (a.type === 4) || Number(b.mtime) - Number(a.mtime));
    const lignes = [];
    if (chemin !== "/sdcard" && chemin !== "/") {
      const tr = document.createElement("tr");
      tr.innerHTML = "<td>⬆️</td><td><b>Dossier parent</b></td><td></td>";
      tr.onclick = () => listerDossier(chemin.replace(/\/[^/]+$/, "") || "/sdcard");
      lignes.push(tr);
    }
    for (const e of entrees) {
      const dossier = e.type === 4 || e.type === 10;
      const tr = document.createElement("tr");
      const date = new Date(Number(e.mtime) * 1000).toLocaleString("fr-FR", { day: "2-digit", month: "2-digit", year: "2-digit", hour: "2-digit", minute: "2-digit" });
      tr.innerHTML = `<td>${dossier ? "📁" : "📄"}</td><td class="nom"></td><td class="droite">${dossier ? "" : tailleLisible(e.size) + "<br><small>" + date + "</small>"}</td>`;
      tr.querySelector(".nom").textContent = e.name;
      tr.onclick = () => dossier ? listerDossier(chemin + "/" + e.name) : recupererFichier(chemin + "/" + e.name, e.name, Number(e.size));
      lignes.push(tr);
    }
    if (!entrees.length) { const tr = document.createElement("tr"); tr.innerHTML = "<td colspan=3>Dossier vide</td>"; lignes.push(tr); }
    tbody.replaceChildren(...lignes);
  } catch (e) {
    tbody.innerHTML = "<tr><td colspan=3>Impossible d'ouvrir ce dossier.</td></tr>";
  } finally { await sync.dispose(); }
}
async function recupererFichier(chemin, nom, taille) {
  if (!adb) return;
  const prog = $("progression");
  prog.hidden = false; prog.textContent = "Récupération de « " + nom + " »… 0 %";
  const sync = await adb.sync();
  try {
    const morceaux = []; let recu = 0;
    const lecteur = sync.read(chemin).getReader();
    for (;;) {
      const { done, value } = await lecteur.read();
      if (done) break;
      morceaux.push(value.slice()); recu += value.length;
      prog.textContent = "Récupération de « " + nom + " »… " + (taille ? Math.floor(recu * 100 / taille) : 0) + " %";
    }
    telechargerBlob(new Blob(morceaux), nom);
    prog.textContent = "✔ « " + nom + " » enregistré dans Téléchargements de la tablette";
  } catch (e) {
    prog.textContent = "✖ Échec de la récupération de « " + nom + " »";
  } finally { await sync.dispose(); setTimeout(() => (prog.hidden = true), 4000); }
}
async function envoyerFichiers(fichiers) {
  if (!adb || !fichiers.length) return;
  const prog = $("progression");
  prog.hidden = false;
  const dest = dossierCourant || "/sdcard/Download";
  let ok = 0;
  for (const f of fichiers) {
    prog.textContent = "Envoi de « " + f.name + " » vers le téléphone…";
    const sync = await adb.sync();
    try {
      let envoye = 0;
      const suivi = f.stream().pipeThrough(new TransformStream({ transform(m, c) { envoye += m.length; prog.textContent = "Envoi de « " + f.name + " »… " + Math.floor(envoye * 100 / (f.size || 1)) + " %"; c.enqueue(m); } }));
      await sync.write({ filename: dest + "/" + f.name, file: suivi, mtime: Math.floor((f.lastModified || Date.now()) / 1000) });
      ok++;
    } catch (e) { console.error(e); }
    finally { await sync.dispose(); }
    // Pour que le fichier apparaisse tout de suite dans la galerie du téléphone
    adb.subprocess.noneProtocol.spawnWait(["am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", "file://" + dest + "/" + f.name]).catch(() => {});
  }
  prog.textContent = `✔ ${ok} fichier(s) envoyé(s) dans ${dest.replace("/sdcard", "Téléphone")}`;
  setTimeout(() => (prog.hidden = true), 4000);
  listerDossier(dest);
}

// ---------- Clavier ----------
// Le texte reste visible dans la zone de saisie ; seules les différences partent vers le téléphone
// (lettres ajoutées, effacements, mots remplacés par les suggestions du clavier).
let texteEnvoye = "";
function ouvrirClavier() { texteEnvoye = ""; $("saisie").value = ""; ouvrir("panneau-clavier"); setTimeout(() => { $("saisie").focus(); ajuster(); }, 100); }
let fileClavier = Promise.resolve();
function synchroniserTexte() {
  fileClavier = fileClavier.then(async () => {
    const nouveau = $("saisie").value, ancien = texteEnvoye;
    if (nouveau === ancien || !client) return;
    let commun = 0;
    while (commun < ancien.length && commun < nouveau.length && ancien[commun] === nouveau[commun]) commun++;
    const aEffacer = [...ancien.slice(commun)].length;
    for (let i = 0; i < aEffacer; i++) await touche(TOUCHE.effacer);
    const ajout = nouveau.slice(commun);
    if (ajout) await client.controller.injectText(ajout);
    texteEnvoye = nouveau;
  }).catch(() => {});
}
$("saisie").addEventListener("input", synchroniserTexte);
$("saisie").addEventListener("keydown", (e) => {
  if (e.key === "Enter") { e.preventDefault(); validerLigne(); }
  else if (e.key === "Backspace" && !e.target.value) { e.preventDefault(); touche(TOUCHE.effacer); }
});
function validerLigne() {
  fileClavier = fileClavier.then(async () => { await touche(TOUCHE.entree); $("saisie").value = ""; texteEnvoye = ""; });
}
function effacerUn() {
  const c = $("saisie");
  if (c.value) { c.value = [...c.value].slice(0, -1).join(""); synchroniserTexte(); }
  else touche(TOUCHE.effacer);
  c.focus();
}

// ---------- Geste « Retour » de la tablette ----------
// Pendant le pilotage, il ferme d'abord la fenêtre ouverte, sinon il fait « Retour » sur le téléphone.
// Il ne fait plus quitter Miroir.
window.addEventListener("popstate", () => {
  if (!client) return;
  const ouvert = [...document.querySelectorAll(".panneau")].find((p) => !p.hidden);
  if (ouvert) ouvert.hidden = true;
  else touche(TOUCHE.retour);
  history.pushState({ miroir: 1 }, "");
});

// ---------- Panneaux ----------
function ouvrir(id) { document.querySelectorAll(".panneau").forEach((p) => (p.hidden = p.id !== id)); }
function fermer(id) { $(id).hidden = true; }
document.querySelectorAll("[data-fermer]").forEach((b) => (b.onclick = () => fermer(b.dataset.fermer)));

// ---------- Plein écran ----------
let pleinEcranAppli = true;
async function basculerPleinEcran() {
  if (APPLI) { pleinEcranAppli = !pleinEcranAppli; window.MiroirApp.pleinEcran(pleinEcranAppli); $("b-plein").querySelector("span").textContent = pleinEcranAppli ? "Quitter plein écran" : "Plein écran"; return; }
  try {
    if (document.fullscreenElement) await document.exitFullscreen();
    else await document.documentElement.requestFullscreen({ navigationUI: "hide" });
  } catch {}
}
function majPleinEcran() { $("b-plein").querySelector("span").textContent = document.fullscreenElement ? "Quitter plein écran" : "Plein écran"; }

// ---------- Réglages ----------
function majBoutonsReglages() {
  $("b-ecran-tel").querySelector("span").textContent = reglages.ecranEteint ? "Écran tél. : éteint" : "Écran tél. : allumé";
  $("b-ecran-tel").classList.toggle("actif", reglages.ecranEteint);
  $("r-qualite").value = reglages.qualite;
  $("r-auto").checked = reglages.autoConnexion;
}
$("r-qualite").onchange = (e) => { reglages.qualite = e.target.value; sauverReglages(); bulle("Prendra effet à la prochaine connexion"); };
$("r-auto").onchange = (e) => { reglages.autoConnexion = e.target.checked; sauverReglages(); };
$("r-oublier-code").onclick = () => { reglages.memoCode = false; reglages.code = ""; sauverReglages(); bulle("Code oublié"); };

// ---------- Boutons ----------
const actions = {
  "btn-connecter": () => connecter(false, true),
  "btn-autre": () => connecter(true, true),
  "b-retour": () => touche(TOUCHE.retour),
  "b-accueil": () => touche(TOUCHE.accueil),
  "b-recents": () => touche(TOUCHE.recents),
  "b-marche": () => touche(TOUCHE.marche),
  "b-deverrouiller": ouvrirDeverrouillage,
  "b-ecran-tel": async () => { reglages.ecranEteint = !reglages.ecranEteint; sauverReglages(); majBoutonsReglages(); await ecranTelephone(!reglages.ecranEteint); },
  "b-capture": capture,
  "b-fichiers": ouvrirFichiers,
  "b-clavier": ouvrirClavier,
  "b-notif": () => client?.controller.expandNotificationPanel(),
  "b-volplus": () => touche(TOUCHE.volPlus),
  "b-volmoins": () => touche(TOUCHE.volMoins),
  "b-plein": basculerPleinEcran,
  "b-reglages": () => { majBoutonsReglages(); ouvrir("panneau-reglages"); },
  "b-deconnecter": deconnecter,
  "c-valider": deverrouiller,
  "c-effacer": () => { codeSaisi = codeSaisi.slice(0, -1); majCode(); },
  "k-effacer": effacerUn,
  "k-entree": () => { validerLigne(); $("saisie").focus(); },
  "f-envoyer": () => $("f-choix").click(),
  "b-sansfil": ouvrirSansFil,
  "sf-lancer": lancerSansFil,
  "sf-cle": exporterCle,
  "btn-usb": () => { apresPassage = false; connecter(false, true); },
  "c-sansfil": lancerSansFil,
  "c-deverrouiller": ouvrirDeverrouillage,
  "c-pointacces": ouvrirPointAcces,
  "b-pointacces": ouvrirPointAcces,
  "btn-adresse-ok": () => { reglages.adresseTel = $("adresse-tel").value.trim(); sauverReglages(); majBoutonAccueil(); bulle(reglages.adresseTel ? "Adresse enregistrée" : "Adresse automatique"); },
  "btn-cle": () => $("cle-choix").click(),
  "r-oublier-cle": () => { localStorage.removeItem("miroir-cle-ordi"); majCleOrdi(); bulle("Clé oubliée"); },
};
for (const [id, fn] of Object.entries(actions)) { const el = $(id); if (el) el.addEventListener("click", () => { Promise.resolve(fn()).catch((e) => { console.error(e); bulle(traduireErreur(e)); }); }); }
document.querySelectorAll("[data-chiffre]").forEach((b) => (b.onclick = () => { if (codeSaisi.length < 16) { codeSaisi += b.dataset.chiffre; majCode(); } }));
$("cle-choix").onchange = (e) => { const f = e.target.files[0]; e.target.value = ""; if (f) importerCleOrdi(f); };
$("f-choix").onchange = (e) => { envoyerFichiers([...e.target.files]); e.target.value = ""; };

// ---------- Démarrage ----------
$("version").textContent = VERSION + (APPLI ? " (appli sans fil)" : "");
document.body.classList.add(APPLI ? "mode-appli" : "mode-chrome");
$("sf-lien-apk").href = LIEN_APK;
if (APPLI) {
  // Clavier : on sort du plein écran le temps de taper, pour que l'image reste au-dessus du clavier
  new MutationObserver(() => { window.MiroirApp.pleinEcran($("panneau-clavier").hidden ? pleinEcranAppli : false); setTimeout(ajuster, 300); })
    .observe($("panneau-clavier"), { attributes: true, attributeFilter: ["hidden"] });
  $("b-plein").querySelector("span").textContent = "Quitter plein écran";
}
majCleOrdi();
majBoutonAccueil().then(async () => {
  // Appli : si le téléphone est branché par câble, on le passe d'abord en sans fil
  // (appli) câble branché : telephoneConnu() choisit le câble et affiche le téléphone
  // Reconnexion directe si le téléphone déjà autorisé est disponible
  if (reglages.autoConnexion && (await telephoneConnu())) connecter(false, false);
});

// ---------- Appli : câble branché → le téléphone passe en sans fil tout seul ----------
let usbEnCours = false;
function usbBranche() { try { return !!window.MiroirApp.usbPresent?.(); } catch { return false; } }
async function clesPourUsb() {
  // Mêmes clés que pour le sans fil : la clé enregistrée (si importée) puis celle de Miroir
  const liste = [];
  const ordi = lireCleOrdi();
  if (ordi) liste.push(btoa(String.fromCharCode(...ordi)));
  let web = [];
  for await (const k of clesWeb.iterateKeys()) web.push(k.buffer);
  if (!web.length) web.push((await clesWeb.generateKey()).buffer);
  for (const b of web) { let bin = ""; for (const o of b) bin += String.fromCharCode(o); liste.push(btoa(bin)); }
  return liste;
}
async function activerSansFilParCable() {
  if (!APPLI || usbEnCours) return;
  if (client) { bulle("Déjà connecté sans fil : rien à faire avec le câble"); return; }
  usbEnCours = true;
  if (connexionEnCours) await annulerEssai();
  afficherAccueil();
  message("🔌 Câble branché : passage du téléphone en sans fil…");
  try { window.MiroirApp.activerSansFil(JSON.stringify(await clesPourUsb())); }
  catch (e) { usbEnCours = false; message("Erreur : " + e, "erreur"); }
}
window.miroirCableBranche = () => {
  if (!APPLI) return;
  if (client && !modeCable) { bulle("Déjà connecté sans fil"); return; }
  if (client) return;
  if (Date.now() - momentPassage < 20000) return;   // le téléphone se rebranche tout seul juste après le passage : on l'ignore
  apresPassage = false;
  setTimeout(() => { if (!client) { if (connexionEnCours) annulerEssai().then(() => connecter(false, false)); else connecter(false, false); } }, 1500);
};
window.miroirUsbEtat = (type, texte) => {
  if (type === "info") { message(texte); return; }
  usbEnCours = false;
  if (type === "erreur") { message(texte, "erreur"); return; }
  // réussi
  message("✔ Le téléphone est passé en sans fil. Tu peux débrancher le câble. Connexion sans fil…");
  setTimeout(() => {
    if (client || connexionEnCours) return;
    if (!adresseTelephone()) { message("✔ Le téléphone est en sans fil. Débranche le câble, connecte la tablette au point d'accès du téléphone, puis appuie sur « 📶 Connecter sans fil ».", "info"); return; }
    connecter(false, false);
  }, 3000);
};
if (!APPLI && "serviceWorker" in navigator) navigator.serviceWorker.register("sw.js").catch(() => {});

// ---------- Raccourci : réglages du point d'accès du téléphone ----------
async function ouvrirPointAcces() {
  if (!adb) return;
  const essais = [
    "am start -n com.android.settings/.TetherSettings",
    "am start -a android.settings.TETHER_SETTINGS",
    "am start -n 'com.android.settings/.Settings$TetherSettingsActivity'",
    "am start -a android.settings.WIRELESS_SETTINGS",
  ];
  for (const c of essais) {
    try {
      const sortie = await adb.subprocess.noneProtocol.spawnWaitText(["sh", "-c", c + " 2>&1"]);
      if (!/Error|Exception|not found|unable/i.test(sortie)) { bulle("📡 Réglages du point d'accès ouverts sur le téléphone"); return; }
    } catch {}
  }
  bulle("Je n'ai pas trouvé le réglage : ouvre-le à la main (Paramètres → Connexions)");
}

// ---------- Passer en sans fil (depuis Chrome, par câble) ----------
let passageSansFil = false;
async function adressesTelephone() {
  try {
    const sortie = await adb.subprocess.noneProtocol.spawnWaitText(["ip", "-4", "-o", "addr", "show"]);
    return sortie.split("\n").map((l) => l.match(/^\d+:\s+(\S+)\s+inet\s+([\d.]+)/)).filter(Boolean)
      .filter((m) => m[1] !== "lo" && !/^rmnet|^ccmni|^dummy/.test(m[1])).map((m) => ({ nom: m[1], ip: m[2] }));
  } catch { return []; }
}
async function exporterCle() {
  // La clé de Miroir (déjà acceptée par le téléphone) est enregistrée en fichier pour l'appli sans fil.
  let cle = null;
  for await (const k of clesWeb.iterateKeys()) { cle = k; break; }
  if (!cle) { bulle("Aucune clé à enregistrer"); return; }
  let bin = ""; for (const o of cle.buffer) bin += String.fromCharCode(o);
  const pem = "-----BEGIN PRIVATE KEY-----\n" + btoa(bin).match(/.{1,64}/g).join("\n") + "\n-----END PRIVATE KEY-----\n";
  await telechargerBlob(new Blob([pem], { type: "application/octet-stream" }), "miroir-cle.adbkey");
  bulle("🔑 Clé enregistrée dans Téléchargements : miroir-cle.adbkey");
}
async function ouvrirSansFil() {
  ouvrir("panneau-sansfil");
  $("sf-etat").textContent = "Le téléphone va passer en mode sans fil. L'image va se couper : c'est normal.";
  $("sf-lancer").hidden = false;
  const ips = await adressesTelephone();
  $("sf-adresses").textContent = ips.length ? "Adresse(s) du téléphone : " + ips.map((x) => x.ip).join(" ou ") : "";
}
async function lancerSansFil() {
  if (!adb) return;
  $("sf-lancer").hidden = true;
  $("sf-etat").textContent = "Activation du sans fil…";
  passageSansFil = true;
  if (APPLI) {
    // Dans l'appli : on coupe nous-mêmes la liaison câble, puis on se reconnecte sans fil
    momentPassage = Date.now();
    message("Passage en sans fil…");
    try { await avecDelai(adb.tcpip.setPort(5555), 4000); } catch {}
    await quandDeconnecte();
    setTimeout(() => (passageSansFil = false), 8000);
    return;
  }
  try {
    await adb.tcpip.setPort(5555);
    $("sf-etat").textContent = "✔ C'est fait. Tu peux débrancher le câble, puis ouvrir l'appli Miroir sur la tablette (connectée au point d'accès du téléphone).";
  } catch (e) {
    // le téléphone redémarre sa liaison : une coupure ici est normale
    $("sf-etat").textContent = "✔ C'est fait (la liaison câble s'est coupée, c'est normal). Débranche le câble et ouvre l'appli Miroir.";
  }
  setTimeout(() => (passageSansFil = false), 8000);
}
