/**
 * Mon Hub — petit serveur de synchronisation (Google Apps Script).
 *
 * Il garde dans ton Google Drive (dossier « Mon Hub - synchro ») l'état à jour de ton hub
 * (applis + données) et les 10 versions précédentes. Tes hubs (téléphone, tablette)
 * envoient et récupèrent cet état.
 *
 * Tu n'as RIEN à modifier dans ce code : colle-le tel quel, puis « Déployer ».
 * Le mot de passe est choisi depuis le hub au premier branchement.
 */

const DOSSIER = 'Mon Hub - synchro';
const VERSIONS_GARDEES = 10;

function doGet() {
  return repondre({ ok: true, message: 'Serveur Mon Hub en marche.', rev: revActuelle() });
}

function doPost(e) {
  let q;
  try { q = JSON.parse(e.postData.contents); } catch (err) { return repondre({ ok: false, erreur: 'Demande illisible.' }); }
  const props = PropertiesService.getScriptProperties();
  const cleEnregistree = props.getProperty('CLE');

  // Premier branchement : le hub choisit le mot de passe.
  if (q.action === 'init') {
    if (!q.cle || String(q.cle).length < 6) return repondre({ ok: false, erreur: 'Mot de passe trop court (6 caractères minimum).' });
    if (!cleEnregistree) { props.setProperty('CLE', String(q.cle)); return repondre({ ok: true, nouveau: true, rev: revActuelle() }); }
    if (cleEnregistree !== String(q.cle)) return repondre({ ok: false, erreur: 'Mot de passe incorrect.' });
    return repondre({ ok: true, nouveau: false, rev: revActuelle() });
  }
  if (!cleEnregistree || cleEnregistree !== String(q.cle)) return repondre({ ok: false, erreur: 'Mot de passe incorrect.' });

  const verrou = LockService.getScriptLock();
  verrou.waitLock(25000);
  try {
    const rev = revActuelle();
    if (q.action === 'etat') return repondre({ ok: true, rev: rev, info: infoActuelle() });

    if (q.action === 'lire') {
      if (!rev) return repondre({ ok: true, rev: 0, donnees: null });
      const f = fichierVersion(rev);
      return repondre({ ok: true, rev: rev, info: infoActuelle(), donnees: f ? f.getBlob().getDataAsString() : null });
    }

    if (q.action === 'ecrire') {
      // Garde-fou : on n'écrit que si l'appareil partait bien de la dernière version.
      if (Number(q.baseRev || 0) !== rev) return repondre({ ok: false, conflit: true, rev: rev, info: infoActuelle() });
      const nouvelle = rev + 1;
      dossier().createFile('etat-' + nouvelle + '.json', String(q.donnees), MimeType.PLAIN_TEXT);
      const info = { appareil: String(q.appareil || '?'), le: new Date().toISOString(), taille: String(q.donnees).length };
      props.setProperties({ REV: String(nouvelle), INFO: JSON.stringify(info) });
      menage(nouvelle);
      return repondre({ ok: true, rev: nouvelle, info: info });
    }

    if (q.action === 'versions') {
      const l = []; const it = dossier().getFiles();
      while (it.hasNext()) { const f = it.next(); const m = /^etat-(\d+)\.json$/.exec(f.getName()); if (m) l.push({ rev: Number(m[1]), le: f.getDateCreated().toISOString(), taille: f.getSize() }); }
      l.sort(function (a, b) { return b.rev - a.rev; });
      return repondre({ ok: true, rev: rev, versions: l });
    }

    if (q.action === 'lireVersion') {
      const f = fichierVersion(Number(q.rev));
      return repondre({ ok: !!f, rev: Number(q.rev), donnees: f ? f.getBlob().getDataAsString() : null, erreur: f ? undefined : 'Version introuvable.' });
    }

    return repondre({ ok: false, erreur: 'Action inconnue : ' + q.action });
  } finally {
    verrou.releaseLock();
  }
}

/* ---------- Outils ---------- */
function repondre(o) { return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON); }
function revActuelle() { return Number(PropertiesService.getScriptProperties().getProperty('REV') || 0); }
function infoActuelle() { try { return JSON.parse(PropertiesService.getScriptProperties().getProperty('INFO') || 'null'); } catch (e) { return null; } }
function dossier() {
  const it = DriveApp.getFoldersByName(DOSSIER);
  return it.hasNext() ? it.next() : DriveApp.createFolder(DOSSIER);
}
function fichierVersion(rev) {
  const it = dossier().getFilesByName('etat-' + rev + '.json');
  return it.hasNext() ? it.next() : null;
}
function menage(rev) {
  const it = dossier().getFiles();
  while (it.hasNext()) {
    const f = it.next(); const m = /^etat-(\d+)\.json$/.exec(f.getName());
    if (m && Number(m[1]) <= rev - VERSIONS_GARDEES) f.setTrashed(true);
  }
}

/* À lancer une fois depuis l'éditeur (▶ Exécuter) pour donner l'autorisation d'accès au Drive. */
function autoriser() { dossier(); Logger.log('OK : dossier « ' + DOSSIER + ' » prêt.'); }
