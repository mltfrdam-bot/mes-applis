"""Conversion PDF vectoriel -> DXF (pour Plans DWG).
Usage : python3 -I pdf2dxf.py plan.pdf sortie.dxf <metres_par_point> [--leger] [--page N]
 - metres_par_point : 1 point PDF = combien de metres sur le terrain
   (plan a l'echelle 1/E sur papier : 0.0254/72*E ; sinon mesurer une cote connue).
 - Calques : ceux du PDF (OCG) s'il y en a, sinon un calque par couleur.
 - --leger : enleve les traits gris clairs (textures, hachures de remplissage).
 - Traits -> LWPOLYLINE, courbes de Bezier en 8 morceaux, remplissages -> HATCH plein (blanc ignore).
 - --textes : ecrit aussi les textes du PDF (TEXT, calque TEXTES_<couleur>), utiles pour les cotes
   (niveaux, diametres, longueurs) lues par l'appli.
 - --sans MOT1,MOT2 : calques du PDF dont le nom contient un de ces mots : pas copies (vegetation, vehicules...).
"""
import sys, math
import pymupdf, ezdxf
from ezdxf.colors import rgb2int

NOMS = {(0,0,0):'NOIR',(1,0,0):'ROUGE',(0,0,1):'BLEU',(0,1,0):'VERT',(0,1,1):'CYAN',(1,0,1):'MAGENTA',(1,1,0):'JAUNE'}
ACI = {1:(255,0,0),2:(255,255,0),3:(0,255,0),4:(0,255,255),5:(0,0,255),6:(255,0,255),7:(0,0,0),8:(128,128,128),9:(192,192,192)}

def aci_proche(rgb):
    return min(ACI, key=lambda k: sum((a-b)**2 for a,b in zip(ACI[k], rgb)))

def nom_couleur(c):
    if c is None: return 'SANS_COULEUR'
    r = tuple(round(v,2) for v in c)
    if r in NOMS: return NOMS[r]
    if abs(r[0]-r[1])<0.03 and abs(r[1]-r[2])<0.03: return 'GRIS_%d' % round(r[0]*100)
    return 'C_%02X%02X%02X' % tuple(round(v*255) for v in c)

def propre(n):
    # noms de calques DXF : pas de < > / \ " : ; ? * | = `
    for ch in '<>/\\":;?*|=`': n=n.replace(ch,'-')
    return n.strip() or 'SANS_NOM'

def bezier(p0,p1,p2,p3,n=8):
    out=[]
    for i in range(1,n+1):
        t=i/n; u=1-t
        out.append((u**3*p0[0]+3*u*u*t*p1[0]+3*u*t*t*p2[0]+t**3*p3[0], u**3*p0[1]+3*u*u*t*p1[1]+3*u*t*t*p2[1]+t**3*p3[1]))
    return out

def main():
    a=sys.argv[1:]
    leger='--leger' in a; a=[x for x in a if x!='--leger']
    textes='--textes' in a; a=[x for x in a if x!='--textes']
    sans=set()
    if '--sans' in a: i=a.index('--sans'); sans=set(a[i+1].split(',')); del a[i:i+2]
    page=0
    if '--page' in a: i=a.index('--page'); page=int(a[i+1])-1; del a[i:i+2]
    src, dst, mpp = a[0], a[1], float(a[2])
    doc=pymupdf.open(src); p=doc[page]; H=p.rect.height
    T=lambda x,y:(x*mpp,(H-y)*mpp)
    dxf=ezdxf.new('R2010'); dxf.units=6; msp=dxf.modelspace()
    calques={}
    def calque(nom,c):
        if nom not in calques and nom not in dxf.layers:
            l=dxf.layers.add(nom); rgb=tuple(round(v*255) for v in (c or (0,0,0)))
            l.color=aci_proche(rgb); l.rgb=rgb; calques[nom]=l
        calques.setdefault(nom,None)
        return nom
    nb=0; ignores=0
    for d in p.get_drawings():
        c=d.get('color'); fc=d.get('fill')
        if leger and c and abs(c[0]-c[1])<0.03 and abs(c[1]-c[2])<0.03 and 0.55<c[0]<0.97: ignores+=1; continue
        nomc = (d.get('layer') if d.get('layer') not in (None,'','0') else None) or nom_couleur(c if c else fc)
        if any(m in nomc for m in sans): ignores+=1; continue
        nomc=propre(nomc)
        lay=calque(nomc, c if c else fc)
        # chemins : enchainer les morceaux qui se suivent
        chemins=[]; cour=[]
        for it in d['items']:
            k=it[0]
            if k=='l': seg=[(it[1].x,it[1].y),(it[2].x,it[2].y)]
            elif k=='c': p0,p1,p2,p3=[(q.x,q.y) for q in it[1:5]]; seg=[p0]+bezier(p0,p1,p2,p3)
            elif k=='re': r=it[1]; seg=[(r.x0,r.y0),(r.x1,r.y0),(r.x1,r.y1),(r.x0,r.y1),(r.x0,r.y0)]
            elif k=='qu': q=it[1]; seg=[(q.ul.x,q.ul.y),(q.ur.x,q.ur.y),(q.lr.x,q.lr.y),(q.ll.x,q.ll.y),(q.ul.x,q.ul.y)]
            else: continue
            if cour and math.dist(cour[-1],seg[0])<1e-3: cour+=seg[1:]
            else:
                if len(cour)>1: chemins.append(cour)
                cour=list(seg)
        if len(cour)>1: chemins.append(cour)
        if fc and d['type'] in ('f','fs') and not all(v>0.97 for v in fc):
            for ch in chemins:
                if len(ch)>=3:
                    h=msp.add_hatch(dxfattribs={'layer':lay}); h.set_solid_fill(color=aci_proche(tuple(round(v*255) for v in fc)))
                    h.rgb=tuple(round(v*255) for v in fc); h.paths.add_polyline_path([T(*q) for q in ch], is_closed=True); nb+=1
        if d['type'] in ('s','fs') and c:
            for ch in chemins:
                msp.add_lwpolyline([T(*q) for q in ch], dxfattribs={'layer':lay}); nb+=1
    nt=0
    if textes:
        for b in p.get_text('rawdict')['blocks']:
            for l in b.get('lines',[]):
                dx,dy=l['dir']; ang=math.degrees(math.atan2(-dy,dx))
                for sp in l['spans']:
                    t=''.join(ch['c'] for ch in sp['chars']).strip()
                    if not t: continue
                    col=sp.get('color',0); rgb=((col>>16)&255,(col>>8)&255,col&255)
                    lay=calque('TEXTES_'+nom_couleur(tuple(v/255 for v in rgb)), tuple(v/255 for v in rgb))
                    o=sp['chars'][0]['origin'] if sp['chars'] else sp['origin']
                    x,y=T(o[0],o[1])
                    msp.add_text(t, height=max(sp['size']*0.7*mpp,0.01), rotation=ang, dxfattribs={'layer':lay,'insert':(x,y)}); nt+=1
    dxf.saveas(dst)
    print('OK', nb, 'objets,', len(calques), 'calques,', ignores, 'traits gris ignores,', nt, 'textes')

if __name__=='__main__': main()
