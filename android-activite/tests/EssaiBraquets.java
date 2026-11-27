package fr.mesapplis.activite;

/** Essai hors téléphone de Braquets.java (🚲 Mon VTT), appelé par activite/tests/test-ftms.js. Écrit les réponses en JSON sur la sortie. */
public class EssaiBraquets {
    public static void main(String[] a) {
        StringBuilder sb = new StringBuilder("[");
        Braquets b = Braquets.lire("");                                    // réglages vides = mon Rockrider
        sb.append(b.pignon()).append(',').append(b.plateau()).append(',').append(String.format(java.util.Locale.ROOT, "%.3f", b.developpement())).append(',');
        sb.append(String.format(java.util.Locale.ROOT, "%.2f", b.vitesse(80))).append(',').append(b.watts(80, 0)).append(',').append(b.watts(10, 0)).append(',');
        int i = 0; while (b.plusFacile()) i++; sb.append(i).append(',').append(b.pignon()).append(',').append(b.watts(80, 0)).append(',');
        int j = 0; while (b.plusDur()) j++; sb.append(j).append(',').append(b.pignon()).append(',').append(b.watts(90, 0)).append(',');
        sb.append(b.plateauPlus()).append(',').append('"').append(b.phrase()).append('"').append(',');
        b.auDepart(); sb.append(b.watts(80, 5)).append(',').append(b.watts(80, -10)).append(',').append(b.code()).append(',');
        Braquets c = Braquets.lire("22,32,44|11,13,15,18,21,24,28,32,36|2180|70|13|60|51|18");   // pignons dans le désordre, 3 plateaux
        sb.append(c.pignons[0]).append(',').append(c.pignons[c.pignons.length - 1]).append(',').append(c.pignon()).append(',').append(c.plateau()).append(',');
        c.plateauPlus(); c.plusDur(); sb.append('"').append(c.phrase()).append('"').append(',').append(c.code()).append(',');
        Braquets d = Braquets.lire(Braquets.DEFAUT); d.depuisCode(c.code()); sb.append(d.pignon()).append(',');   // 32×15 n'existe pas : rien ne casse
        d.depuisCode(3015); sb.append(d.pignon()).append(',');
        Braquets e = Braquets.lire("abc|x|y|z|w|v|u|t"); sb.append(e.pignons.length).append(',').append(e.plateau()).append(',').append(e.pignon());
        System.out.println(sb.append(']'));
    }
}
