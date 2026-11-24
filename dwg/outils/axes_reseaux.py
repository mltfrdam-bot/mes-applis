"""Axes des reseaux dessines en morceaux de largeur (PDF de bureau d'etudes).
Chaque tuyau est dessine a sa largeur, decoupe en petits quadrilateres pleins (tirets).
On remplace chaque quadrilatere par son axe, puis on raccorde les morceaux en une ligne par troncon,
coupee aux regards (centre du regard = bout du troncon).

Utilise par pdf2dxf.py (--axes) ; fonctions pures, coordonnees PDF (points, y vers le bas).
"""
import math

def _quads(dessins):
    """Quadrilateres (4 traits fermes ou rectangle) -> liste de 4 coins."""
    Q = []
    for d in dessins:
        it = d['items']
        if len(it) == 4 and all(i[0] == 'l' for i in it):
            P = [(i[1].x, i[1].y) for i in it]
            if math.dist((it[-1][2].x, it[-1][2].y), P[0]) < 0.5:
                Q.append(P)
        elif len(it) == 1 and it[0][0] == 're':
            r = it[0][1]; Q.append([(r.x0, r.y0), (r.x1, r.y0), (r.x1, r.y1), (r.x0, r.y1)])
        elif len(it) == 1 and it[0][0] == 'qu':
            q = it[0][1]; Q.append([(q.ul.x, q.ul.y), (q.ur.x, q.ur.y), (q.lr.x, q.lr.y), (q.ll.x, q.ll.y)])
    return Q

def _mil(a, b): return ((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)
def _cen(P): return (sum(p[0] for p in P) / 4, sum(p[1] for p in P) / 4)

def axes_morceaux(dessins, voisin_max):
    """Un segment d'axe par quadrilatere. Le sens est choisi grace au morceau voisin le plus proche."""
    Q = _quads(dessins); C = [_cen(P) for P in Q]
    # grille pour les voisins
    g = {}; t = voisin_max
    for i, c in enumerate(C): g.setdefault((int(c[0] // t), int(c[1] // t)), []).append(i)
    segs = []
    for i, P in enumerate(Q):
        A = (_mil(P[0], P[1]), _mil(P[2], P[3]))   # axe si les cotes 0 et 2 sont les largeurs
        B = (_mil(P[1], P[2]), _mil(P[3], P[0]))   # axe si les cotes 1 et 3 sont les largeurs
        la, lb = math.dist(*A), math.dist(*B)
        c = C[i]; best = None
        gx, gy = int(c[0] // t), int(c[1] // t)
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for j in g.get((gx + dx, gy + dy), []):
                    if j == i: continue
                    d = math.dist(c, C[j])
                    if d < t and (best is None or d < best[0]): best = (d, j)
        if best:
            v = (C[best[1]][0] - c[0], C[best[1]][1] - c[1]); n = math.hypot(*v) or 1
            def cosv(S):
                w = (S[1][0] - S[0][0], S[1][1] - S[0][1]); m = math.hypot(*w) or 1
                return abs(w[0] * v[0] + w[1] * v[1]) / (m * n)
            S = A if cosv(A) >= cosv(B) else B
        else:
            S = A if la >= lb else B
        if math.dist(*S) > 1e-6: segs.append(S)
    return segs

def _angle(u, v):
    nu, nv = math.hypot(*u) or 1, math.hypot(*v) or 1
    c = max(-1, min(1, (u[0] * v[0] + u[1] * v[1]) / (nu * nv)))
    return math.degrees(math.acos(c))

def chainer(segs, ecart_max, regards=(), r_regard=0):
    """Raccorde les segments bout a bout (ecart <= ecart_max, direction proche). Un ecart qui passe
    sur un regard n'est pas franchi. Retourne des polylignes [(x,y),...]."""
    n = len(segs); libre = [True] * n
    bouts = {}
    t = max(ecart_max, 1e-6)
    for i, (a, b) in enumerate(segs):
        for k, p in ((0, a), (1, b)): bouts.setdefault((int(p[0] // t), int(p[1] // t)), []).append((i, k))
    def sur_regard(p, q):
        m = _mil(p, q)
        return any(math.dist(m, r) < r_regard for r in regards)
    def suivant(p, dirp):
        best = None
        gx, gy = int(p[0] // t), int(p[1] // t)
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for (j, k) in bouts.get((gx + dx, gy + dy), []):
                    if not libre[j]: continue
                    q = segs[j][k]; d = math.dist(p, q)
                    if d > ecart_max: continue
                    o = segs[j][1 - k]; dj = (o[0] - q[0], o[1] - q[1])
                    ang = _angle(dirp, dj)
                    lim = 60 if d < ecart_max * 0.35 else 30
                    if ang > lim: continue
                    if d > 1e-6 and _angle(dirp, (q[0] - p[0], q[1] - p[1])) > lim: continue
                    if regards and d > 1e-6 and sur_regard(p, q): continue
                    sc = d + ang * ecart_max / 180
                    if best is None or sc < best[0]: best = (sc, j, k)
        return best
    lignes = []
    for i in range(n):
        if not libre[i]: continue
        libre[i] = False; L = [segs[i][0], segs[i][1]]
        for sens in (1, 0):
            while True:
                p, q = (L[-1], L[-2]) if sens else (L[0], L[1])
                b = suivant(p, (p[0] - q[0], p[1] - q[1]))
                if not b: break
                _, j, k = b; libre[j] = False
                a, o = segs[j][k], segs[j][1 - k]
                pts = [a, o] if math.dist(a, p) > 1e-6 else [o]
                if sens: L.extend(pts)
                else: L[0:0] = pts[::-1]
        lignes.append(L)
    return lignes

def simplifier(L, tol):
    """Douglas-Peucker."""
    if len(L) < 3: return L
    a, b = L[0], L[-1]; dx, dy = b[0] - a[0], b[1] - a[1]; n = math.hypot(dx, dy)
    dmax, im = -1, 0
    for i in range(1, len(L) - 1):
        p = L[i]
        d = abs(dy * (p[0] - a[0]) - dx * (p[1] - a[1])) / n if n > 1e-9 else math.dist(p, a)
        if d > dmax: dmax, im = d, i
    if dmax > tol: return simplifier(L[:im + 1], tol)[:-1] + simplifier(L[im:], tol)
    return [a, b]

def centres_regards(dessins, tmin, tmax):
    """Centres des symboles de regard : boites des traits qui se touchent, taille entre tmin et tmax."""
    B = []
    for d in dessins:
        r = d['rect']
        if r.width < tmax * 1.2 and r.height < tmax * 1.2: B.append([r.x0, r.y0, r.x1, r.y1])
    par = list(range(len(B)))
    def f(i):
        while par[i] != i: par[i] = par[par[i]]; i = par[i]
        return i
    marge = tmin * 0.05
    ordre = sorted(range(len(B)), key=lambda i: B[i][0])
    for a_i, i in enumerate(ordre):
        for j in ordre[a_i + 1:]:
            if B[j][0] > B[i][2] + marge: break
            if B[j][1] <= B[i][3] + marge and B[i][1] <= B[j][3] + marge: par[f(i)] = f(j)
    G = {}
    for i, b in enumerate(B):
        r = f(i); g = G.setdefault(r, [b[0], b[1], b[2], b[3]])
        g[0] = min(g[0], b[0]); g[1] = min(g[1], b[1]); g[2] = max(g[2], b[2]); g[3] = max(g[3], b[3])
    C = []
    for g in G.values():
        w, h = g[2] - g[0], g[3] - g[1]
        if tmin <= max(w, h) <= tmax: C.append(((g[0] + g[2]) / 2, (g[1] + g[3]) / 2))
    return C

def accrocher_regards(L, regards, dist):
    """Prolonge chaque bout de ligne jusqu'au centre du regard le plus proche (<= dist)."""
    for idx in (0, -1):
        p = L[idx]; best = None
        for r in regards:
            d = math.dist(p, r)
            if d <= dist and (best is None or d < best[0]): best = (d, r)
        if best and best[0] > 1e-6:
            if idx == 0: L.insert(0, best[1])
            else: L.append(best[1])
    return L

def longueur(L): return sum(math.dist(L[i], L[i + 1]) for i in range(len(L) - 1))
