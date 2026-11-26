package fr.mesapplis.activite;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 🎙️ Compréhension d'une dictée d'alimentation, sans internet (essai 31) : nombres (chiffres ou lettres), unités,
 * noms des favoris et mots-codes. Ex. « eau 20 cl, lait de vache 20 cl, baguette 125 g » ou « 20 centilitres d'eau ».
 * Sans Android : testé hors du téléphone par activite/tests/test-dictee.js.
 */
public class Dictee {
    static String aujourdhui(Calendar k) { return String.format(Locale.ROOT, "%04d-%02d-%02d", k.get(Calendar.YEAR), k.get(Calendar.MONTH) + 1, k.get(Calendar.DAY_OF_MONTH)); }

    /** Même règle que le carnet : 1re prise du matin ou avant 7 h = petit-déjeuner ; 11 h-14 h déjeuner ; dès 19 h dîner ; sinon collation. */
    static String repas(int m, boolean premiere) {
        if (m >= 240 && (m < 420 || (premiere && m < 600))) return "petit-déjeuner";
        if (m >= 660 && m < 840) return "déjeuner";
        if (m >= 1140) return "dîner";
        return "collation";
    }

    static class Item { String id, nom, u, dit = ""; double q; boolean defaut, eau; }
    static class Fav { final String id, nom, u, codes; final double q; final boolean eau; Fav(String id, String nom, String u, double q, String codes, boolean eau) { this.id = id; this.nom = nom; this.u = u == null || u.isEmpty() ? "portion" : u; this.q = q; this.codes = codes == null ? "" : codes; this.eau = eau; } }
    static class Resultat { final List<Item> items = new ArrayList<>(); final List<String> incompris = new ArrayList<>(); final List<Sante> sante = new ArrayList<>(); String heure = null; }

    /** 🩺 Mesure de santé dictée (essai 34) : tension, douleur ou poids. */
    static class Sante { String type, zone = "", note = ""; double sys, dia, pouls, kg, niveau; boolean fini; String ecrit, oral; }

    private static final java.util.regex.Pattern MOT_CODE = java.util.regex.Pattern.compile("(?iu)(?<![\\p{L}\\p{N}_])(alimentations?|aliments?|tensions?|douleurs?|poids)(?![\\p{L}\\p{N}_])");

    /**
     * Note complète (essai 34) : plusieurs mots-codes à la suite — « poids 62,3, tension 13 8, alimentation eau 20 cl ».
     * Sans mot-code, toute la phrase est de l'alimentation (bouton 🎙️ du carnet).
     */
    static Resultat analyser(String phrase, List<Fav> favs) {
        Resultat r = new Resultat();
        String t = oterHeureBrute(phrase == null ? "" : phrase, r);
        java.util.regex.Matcher m = MOT_CODE.matcher(t);
        List<int[]> pos = new ArrayList<>(); List<String> quoi = new ArrayList<>();
        while (m.find()) { pos.add(new int[]{m.start(), m.end()}); String w = m.group(1).toLowerCase(Locale.FRANCE); quoi.add(w.startsWith("tension") ? "tension" : w.startsWith("douleur") ? "douleur" : w.startsWith("poids") ? "poids" : "alimentation"); }
        String avant = pos.isEmpty() ? t : t.substring(0, pos.get(0)[0]);
        if (!avant.replaceAll("[\\s,.;:!?-]", "").isEmpty()) fusion(r, comprendre(avant, favs));
        for (int k = 0; k < pos.size(); k++) {
            String seg = t.substring(pos.get(k)[1], k + 1 < pos.size() ? pos.get(k + 1)[0] : t.length()).replaceAll("^[\\s,.;:!?-]+|[\\s,.;:!?-]+$", "");
            switch (quoi.get(k)) {
                case "tension": tension(seg, r); break;
                case "douleur": douleur(seg, r); break;
                case "poids": poids(seg, r); break;
                default: fusion(r, comprendre(seg, favs));
            }
        }
        return r;
    }

    static void fusion(Resultat r, Resultat a) { r.items.addAll(a.items); r.incompris.addAll(a.incompris); if (r.heure == null) r.heure = a.heure; }

    /** Heure dite, ôtée du texte d'origine (accents gardés pour la zone de la douleur). */
    static String oterHeureBrute(String t, Resultat r) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?iu)(?:(?<![\\p{L}\\p{N}_])(?:à|a|vers)\\s+)?(?<![\\p{L}\\p{N}_,.])(\\d{1,2})\\s*(?:h|heures?)(?![\\p{L}\\p{N}_])(?:\\s*(\\d{1,2})(?![\\p{L}\\p{N}_,.])(?!\\s*(?:\\d|(?:cl|ml|l|g|kg|gr|centilitres?|millilitres?|litres?|grammes?|kilos?)(?![\\p{L}\\p{N}_]))))?(?:\\s+(du matin|ce matin|du soir|ce soir|de l['’ ]?apr[eè]s[- ]midi|cet apr[eè]s[- ]midi))?").matcher(t);
        if (m.find()) {
            int hh = Integer.parseInt(m.group(1)), mm = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
            String moment = m.group(3) == null ? "" : m.group(3).toLowerCase(Locale.FRANCE);
            if (hh < 12 && (moment.contains("soir") || moment.contains("midi"))) hh += 12;
            if (hh <= 23 && mm <= 59) { r.heure = String.format(Locale.ROOT, "%02d:%02d", hh, mm); return t.substring(0, m.start()) + " , " + t.substring(m.end()); }
        }
        m = java.util.regex.Pattern.compile("(?iu)(?:(?<![\\p{L}\\p{N}_])(?:à|a|vers)\\s+)?(?<![\\p{L}\\p{N}_])(midi|minuit)(?![\\p{L}\\p{N}_])(?:\\s*(\\d{1,2})(?![\\p{L}\\p{N}_,.])(?!\\s*(?:cl|ml|l|g|kg|gr)(?![\\p{L}\\p{N}_])))?").matcher(t);
        if (m.find()) {
            int mm = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
            if (mm <= 59) { r.heure = String.format(Locale.ROOT, "%02d:%02d", m.group(1).equalsIgnoreCase("midi") ? 12 : 0, mm); return t.substring(0, m.start()) + " , " + t.substring(m.end()); }
        }
        return t;
    }

    /** Tous les nombres d'un texte (déjà mis en forme par norm), avec la position du mot qui suit chacun. */
    static List<double[]> nombres(String[] mots) {
        List<double[]> l = new ArrayList<>();
        for (int i = 0; i < mots.length; ) { double[] n = lireNombre(mots, i, true); if (n == null) { i++; continue; } l.add(new double[]{n[0], i, n[1]}); i = (int) n[1]; }
        return l;
    }

    /** « 13 8 », « 13 sur 8 », « 13/8 », « 130 80 », « 138 », « pouls 62 ». 13 → 130 mmHg (comme l'appareil le dit souvent). */
    static void tension(String seg, Resultat r) {
        String[] mots = norm(seg.replace('/', ' ')).split(" ");
        Double sys = null, dia = null, pouls = null; boolean apresPouls = false;
        for (int i = 0; i < mots.length; ) {
            if (mots[i].startsWith("pouls") || mots[i].equals("pulsations") || mots[i].equals("cardiaque") || mots[i].equals("battements")) { apresPouls = true; i++; continue; }
            double[] n = lireNombre(mots, i, true);
            if (n == null) { i++; continue; }
            if (apresPouls && pouls == null) pouls = n[0];
            else if (sys == null) sys = n[0];
            else if (dia == null) dia = n[0];
            else if (pouls == null) pouls = n[0];
            i = (int) n[1];
        }
        if (sys != null && dia == null && sys >= 90 && sys <= 209 && sys == Math.rint(sys) && ((int) (sys % 10)) >= 5) { double s0 = sys; sys = Math.floor(s0 / 10); dia = s0 % 10; }   // « 138 » = 13/8
        if (sys == null || dia == null) { r.incompris.add("tension" + (seg.isEmpty() ? "" : " « " + seg + " »") + " : il me faut les 2 chiffres"); return; }
        if (sys < 30) sys = sys * 10; if (dia < 20) dia = dia * 10;
        if (sys < 70 || sys > 260 || dia < 30 || dia > 160 || dia >= sys) { r.incompris.add("tension « " + seg + " » : chiffres pas plausibles"); return; }
        if (pouls != null && (pouls < 25 || pouls > 230)) { r.incompris.add("pouls « " + nb(pouls) + " »"); pouls = null; }
        Sante x = new Sante(); x.type = "tension"; x.sys = Math.round(sys); x.dia = Math.round(dia); x.pouls = pouls == null ? 0 : Math.round(pouls);
        x.ecrit = "🩺 Tension " + nb(x.sys) + "/" + nb(x.dia) + (x.pouls > 0 ? ", pouls " + nb(x.pouls) : "");
        x.oral = "Tension " + nb(x.sys / 10) + " " + nb(x.dia / 10) + (x.pouls > 0 ? ", pouls " + nb(x.pouls) : "");
        r.sante.add(x);
    }

    /** « 62,3 », « 62 kilos 300 », « soixante deux virgule trois ». */
    static void poids(String seg, Resultat r) {
        String[] mots = norm(seg).replace("kilogrammes", "kg").replace("kilos", "kg").replace("kilo", "kg").split(" ");
        List<double[]> l = nombres(mots);
        if (l.isEmpty()) { r.incompris.add("poids : je n'ai pas entendu le chiffre"); return; }
        double kg = l.get(0)[0];
        if (l.size() > 1 && kg == Math.rint(kg)) { double d = l.get(1)[0]; int ix = (int) l.get(0)[2];   // « 62 kg 300 » ou « 62 virgule 3 »
            if (ix < mots.length && (mots[ix].equals("kg") || mots[ix].equals("virgule")) && d == Math.rint(d)) kg += d < 10 ? d / 10 : d < 100 ? d / 100 : d / 1000; }
        if (kg < 30 || kg > 250) { r.incompris.add("poids « " + seg + " » : chiffre pas plausible"); return; }
        Sante x = new Sante(); x.type = "poids"; x.kg = Math.round(kg * 10) / 10.0;
        x.ecrit = "⚖️ Poids " + nb(x.kg).replace('.', ',') + " kg"; x.oral = "Poids " + nb(x.kg).replace('.', ',') + " kilos";
        r.sante.add(x);
    }

    /** « genou droit 4 », « genou droit 4 sur 10, après le tennis », « genou droit finie ». */
    static void douleur(String seg, Resultat r) {
        String t = seg;
        boolean fini = false;
        java.util.regex.Matcher mf = java.util.regex.Pattern.compile("(?iu)(?<![\\p{L}\\p{N}_])(finie?s?|termin[ée]e?s?|disparue?s?|partie?s?|pass[ée]e?s?|guérie?s?|plus mal)(?![\\p{L}\\p{N}_])").matcher(t);
        if (mf.find()) { fini = true; t = t.substring(0, mf.start()) + " " + t.substring(mf.end()); }
        // niveau : un nombre de 0 à 10, en chiffres ou en lettres, éventuellement suivi de « sur 10 »
        java.util.regex.Matcher mn = java.util.regex.Pattern.compile("(?iu)(?<![\\p{L}\\p{N}_,.])(10|\\d|zéro|zero|un|une|deux|trois|quatre|cinq|six|sept|huit|neuf|dix)(?![\\p{L}\\p{N}_,.])(\\s*(?:sur|/)\\s*10)?").matcher(t);
        Integer niv = null; int a = -1, b = -1;
        while (mn.find()) {
            String w = mn.group(1).toLowerCase(Locale.FRANCE);
            boolean article = (w.equals("un") || w.equals("une")) && mn.group(2) == null && t.substring(mn.end()).trim().matches("(?su)^\\p{L}.*");
            if (article) continue;
            Double v = w.matches("\\d+") ? Double.valueOf(w) : NOMBRES.get(norm(w));
            if (v == null) continue;
            niv = (int) Math.round(v); a = mn.start(); b = mn.end();
            if (mn.group(2) != null) break;
        }
        String zone, note = "";
        if (niv != null) { zone = t.substring(0, a); note = t.substring(b); } else { zone = t; }
        zone = zone.replaceAll("(?iu)(?<![\\p{L}\\p{N}_])(niveau|note|intensit[ée]|a|à|au|de|du|la|le|les|mon|ma|mes|j'ai|j ai|mal)(?![\\p{L}\\p{N}_])\\s*$", "").replaceAll("^[\\s,.;:!?-]+|[\\s,.;:!?-]+$", "").replaceAll("\\s+", " ");
        zone = zone.replaceAll("(?iu)^(au|à la|à l'|a la|a l|à|a|de la|du|mon|ma|mes|le|la|les)\\s+", "").trim();
        note = note.replaceAll("^[\\s,.;:!?-]+|[\\s,.;:!?-]+$", "").replaceAll("\\s+", " ");
        if (zone.isEmpty()) { r.incompris.add("douleur : je n'ai pas compris où"); return; }
        if (niv == null && !fini) { r.incompris.add("douleur " + zone + " : je n'ai pas compris le niveau de 0 à 10"); return; }
        if (niv != null && (niv < 0 || niv > 10)) { r.incompris.add("douleur " + zone + " : le niveau va de 0 à 10"); return; }
        Sante x = new Sante(); x.type = "douleur"; x.zone = zone.substring(0, 1).toUpperCase(Locale.FRANCE) + zone.substring(1); x.niveau = niv == null ? 0 : niv; x.fini = fini; x.note = note;
        x.ecrit = "😣 Douleur " + x.zone + (niv != null ? " " + niv + "/10" : "") + (fini ? " · finie ✅" : "") + (note.isEmpty() ? "" : " · " + note);
        x.oral = "Douleur " + x.zone + (niv != null ? ", " + niv + " sur 10" : "") + (fini ? ", finie" : "");
        r.sante.add(x);
    }

    /** Heure dite (« à 5 h du matin », « 12 h 30 », « 7 heures », « à midi ») : ôtée de la phrase, rangée dans r.heure (« HH:MM »). */
    static String oterHeure(String n, Resultat r) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:\\b(?:a|vers)\\s+)?\\b(\\d{1,2})\\s*(?:h|heure|heures)\\b(?:\\s*(\\d{1,2})\\b(?!\\s*(?:\\d|(?:cl|ml|l|g|kg|gr|centilitres?|millilitres?|litres?|grammes?)\\b)))?(?:\\s+(du matin|ce matin|du soir|ce soir|de l apres midi|cet apres midi))?").matcher(n);
        if (m.find()) {
            int hh = Integer.parseInt(m.group(1)), mm = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
            String moment = m.group(3) == null ? "" : m.group(3);
            if (hh < 12 && (moment.contains("soir") || moment.contains("apres midi"))) hh += 12;
            if (hh <= 23 && mm <= 59) { r.heure = String.format(Locale.ROOT, "%02d:%02d", hh, mm); return (n.substring(0, m.start()) + " , " + n.substring(m.end())).trim(); }
        }
        m = java.util.regex.Pattern.compile("(?:\\b(?:a|vers)\\s+)?\\b(midi|minuit)\\b(?:\\s*(\\d{1,2})\\b(?!\\s*(?:cl|ml|l|g|kg|gr)\\b))?").matcher(n);
        if (m.find()) {
            int mm = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
            if (mm <= 59) { r.heure = String.format(Locale.ROOT, "%02d:%02d", m.group(1).equals("midi") ? 12 : 0, mm); return (n.substring(0, m.start()) + " , " + n.substring(m.end())).trim(); }
        }
        return n;
    }

    static String qte(Item it, boolean oral) {
        double q = it.q;
        if ("ml".equals(it.u)) {
            if (q >= 1000 && q % 100 == 0) return nb(q / 1000) + (oral ? (q == 1000 ? " litre" : " litres") : " l");
            if (q % 10 == 0) return nb(q / 10) + (oral ? " centilitres" : " cl");
            return nb(q) + (oral ? " millilitres" : " ml");
        }
        if ("g".equals(it.u)) return nb(q) + (oral ? " grammes" : " g");
        return nb(q) + (q > 1 ? " portions" : " portion");
    }
    static String nb(double v) { return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.FRANCE, "%.1f", v).replace(".0", ""); }

    static String norm(String s) {
        String t = Normalizer.normalize(s == null ? "" : s.toLowerCase(Locale.FRANCE), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        t = t.replace('’', ' ').replace('\'', ' ').replace('-', ' ');
        t = t.replaceAll("(\\d)([a-z])", "$1 $2").replaceAll("([a-z])(\\d)", "$1 $2");
        t = t.replaceAll("(?<!\\d),|,(?!\\d)", " , ").replaceAll("[.;:!?](?!\\d)", " , ");
        return t.replaceAll("\\s+", " ").trim();
    }

    private static final java.util.Set<String> VIDES = new java.util.HashSet<>(java.util.Arrays.asList(
            "l", "d", "de", "du", "des", "alimentation", "aliments", "aliment", "matin", "soir", "la", "le", "les", "j", "ai", "bu", "mange", "pris", "prends", "je", "c", "est", "en", "y", "a", "au", "aux", "mon", "ma", "mes", "un", "une"));
    private static final java.util.Set<String> SEPARE = new java.util.HashSet<>(java.util.Arrays.asList(",", "et", "puis", "plus", "avec", "ensuite"));
    private static final Map<String, String> UNITES = new HashMap<>();
    private static final Map<String, Double> NOMBRES = new HashMap<>();
    static {
        for (String u : new String[]{"cl", "centilitre", "centilitres", "centi"}) UNITES.put(u, "cl");
        for (String u : new String[]{"ml", "millilitre", "millilitres"}) UNITES.put(u, "ml");
        for (String u : new String[]{"l", "litre", "litres"}) UNITES.put(u, "l");
        for (String u : new String[]{"g", "gr", "gramme", "grammes", "grame", "grames"}) UNITES.put(u, "g");
        for (String u : new String[]{"kg", "kilo", "kilos", "kilogramme", "kilogrammes"}) UNITES.put(u, "kg");
        for (String u : new String[]{"portion", "portions", "part", "parts", "morceau", "morceaux", "piece", "pieces"}) UNITES.put(u, "p");
        String[] n = {"zero", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf", "dix", "onze", "douze", "treize", "quatorze", "quinze", "seize"};
        for (int i = 0; i < n.length; i++) NOMBRES.put(n[i], (double) i);
        NOMBRES.put("une", 1.0); NOMBRES.put("vingt", 20.0); NOMBRES.put("vingts", 20.0); NOMBRES.put("trente", 30.0); NOMBRES.put("quarante", 40.0);
        NOMBRES.put("cinquante", 50.0); NOMBRES.put("soixante", 60.0); NOMBRES.put("cent", 100.0); NOMBRES.put("cents", 100.0); NOMBRES.put("mille", 1000.0); NOMBRES.put("demi", 0.5);
    }

    /** Morceaux de la phrase : Q (quantité, avec ou sans unité), F (favori), S (séparateur), X (mot inconnu). */
    static class Morceau { char k; double v; String u; Item f; String txt; boolean pris; Morceau(char k) { this.k = k; } }

    /** Lit un nombre à la position i (« 62,3 », « 20 », « deux cent cinquante », « un demi », « 13 et demi »).
        Renvoie {valeur, position suivante} ou null. « un » / « une » seuls ne comptent que si toutUn (sinon ce sont des articles). */
    static double[] lireNombre(String[] mots, int i, boolean toutUn) {
        if (i >= mots.length) return null;
        String w = mots[i];
        if (w.matches("\\d+([.,]\\d+)?")) {
            double v = Double.parseDouble(w.replace(',', '.')); int j = i + 1;
            if (j + 1 < mots.length && mots[j].equals("et") && mots[j + 1].equals("demi")) { v += 0.5; j += 2; }
            else if (j + 1 < mots.length && mots[j].equals("virgule") && mots[j + 1].matches("\\d+")) { v += Double.parseDouble("0." + mots[j + 1]); j += 2; }
            return new double[]{v, j};
        }
        boolean un = w.equals("un") || w.equals("une");
        if (!NOMBRES.containsKey(w) || (un && !toutUn && !(i + 1 < mots.length && (UNITES.containsKey(mots[i + 1]) || mots[i + 1].equals("demi"))))) return null;
        double tot = 0, cur = 0; int j = i;
        while (j < mots.length && (NOMBRES.containsKey(mots[j]) || (mots[j].equals("et") && j + 1 < mots.length && NOMBRES.containsKey(mots[j + 1]) && j > i))) {
            String x = mots[j];
            if (x.equals("et")) { j++; continue; }
            double n = NOMBRES.get(x);
            if (x.equals("demi")) cur = (cur == 1 && !mots[j - 1].equals("et")) ? 0.5 : cur + 0.5;   // « un demi » = 0,5 ; « deux et demi » = 2,5
            else if (n == 100) cur = (cur == 0 ? 1 : cur) * 100;
            else if (n == 1000) { tot += (cur == 0 ? 1 : cur) * 1000; cur = 0; }
            else if (x.equals("vingt") && cur == 4) cur = 80;          // quatre-vingt
            else if (cur == 60 && n >= 10 && n < 20) cur += n;          // soixante-dix…
            else if (cur == 80 && n < 20) cur += n;
            else if (cur > 0 && cur < 10 && n < 10 && !x.equals("demi")) break;   // « treize huit » = deux nombres
            else if (cur >= 10 && cur % 10 != 0 && n < 100) break;
            else cur += n;
            j++;
        }
        return new double[]{tot + cur, j};
    }

    static Resultat comprendre(String phrase, List<Fav> favs) {
        Resultat r = new Resultat();
        String[] mots = oterHeure(norm(phrase), r).replaceAll("\\s+", " ").trim().split(" ");
        // noms et mots-codes des favoris, découpés en mots « utiles »
        List<String[]> noms = new ArrayList<>(); List<Fav> deQui = new ArrayList<>();
        for (Fav f : favs) {
            List<String> variantes = new ArrayList<>(); variantes.add(f.nom);
            for (String c : f.codes.split(",")) if (!c.trim().isEmpty()) variantes.add(c.trim());
            for (String v : variantes) { String[] t = utiles(norm(v.replaceAll("\\(.*?\\)", " "))); if (t.length > 0) { noms.add(t); deQui.add(f); } }
        }
        List<Morceau> m = new ArrayList<>();
        int i = 0;
        while (i < mots.length) {
            String w = mots[i];
            if (w.isEmpty()) { i++; continue; }
            if (SEPARE.contains(w) && !(w.equals("et") && i + 1 < mots.length && mots[i + 1].equals("demi"))) { m.add(new Morceau('S')); i++; continue; }
            // nombre (chiffres ou lettres)
            double[] nb = lireNombre(mots, i, false);
            Double v = nb == null ? null : nb[0]; int j = nb == null ? i : (int) nb[1];
            if (v != null) {
                Morceau q = new Morceau('Q'); q.v = v; q.txt = String.join(" ", java.util.Arrays.copyOfRange(mots, i, j));
                if (j < mots.length && UNITES.containsKey(mots[j])) { q.u = UNITES.get(mots[j]); q.txt += " " + mots[j]; j++; }
                m.add(q); i = j; continue;
            }
            if (UNITES.containsKey(w) && !w.equals("l") && !w.equals("g")) { m.add(new Morceau('S')); i++; continue; }   // unité sans nombre : on l'ignore
            // favori : le plus long nom (ou mot-code) qui commence ici
            int meilleur = 0; Fav fav = null;
            for (int k = 0; k < noms.size(); k++) { int lg = correspond(mots, i, noms.get(k)); if (lg > meilleur) { meilleur = lg; fav = deQui.get(k); } }
            if (fav == null) { int[] u = premierMotUnique(mots[i], noms, deQui); if (u[0] >= 0) { meilleur = 1; fav = deQui.get(u[0]); } }
            if (fav != null) {
                Morceau f = new Morceau('F'); f.f = new Item();
                f.f.id = fav.id; f.f.nom = fav.nom; f.f.u = fav.u; f.f.eau = fav.eau; f.f.q = fav.q;
                f.txt = String.join(" ", java.util.Arrays.copyOfRange(mots, i, Math.min(mots.length, i + meilleur)));
                m.add(f); i += meilleur; continue;
            }
            Morceau x = new Morceau(VIDES.contains(w) ? 'V' : 'X'); x.txt = w; m.add(x); i++;
        }
        // sens de la phrase : « quantité puis nom » ou « nom puis quantité » (d'après le premier couple)
        boolean qAvant = false;
        for (Morceau x : m) { if (x.k == 'Q') { qAvant = true; break; } if (x.k == 'F') { qAvant = false; break; } }
        for (int a = 0; a < m.size(); a++) {
            Morceau f = m.get(a); if (f.k != 'F') continue;
            Morceau q1 = qAvant ? voisin(m, a, -1) : voisin(m, a, 1), q2 = qAvant ? voisin(m, a, 1) : voisin(m, a, -1);
            Item it = f.f;
            // essai 33 : on préfère la quantité dont l'unité va avec l'aliment (cl pour une boisson, g pour un plat)
            Morceau q = q1 != null && compatible(q1.u, it.u) ? q1 : q2 != null && compatible(q2.u, it.u) ? q2 : q1 != null ? q1 : q2;
            if (q != null) { q.pris = true; it.q = convertir(q.v, q.u, it); it.dit = qAvant ? q.txt + " " + f.txt : f.txt + " " + q.txt; }
            else { it.defaut = true; it.dit = f.txt; }
            if (it.q > 0) r.items.add(it);
        }
        // passages non compris (mots inconnus et quantités orphelines), regroupés
        StringBuilder g = new StringBuilder();
        for (Morceau x : m) {
            if (x.k == 'X' || (x.k == 'Q' && !x.pris)) { if (g.length() > 0) g.append(' '); g.append(x.txt); }
            else if (x.k == 'S' || x.k == 'F') { if (g.length() > 0) { r.incompris.add(g.toString()); g.setLength(0); } }
        }
        if (g.length() > 0) r.incompris.add(g.toString());
        return r;
    }

    static boolean compatible(String u, String fu) {
        if (u == null || "p".equals(u) || "portion".equals(fu)) return true;
        if ("ml".equals(fu)) return u.equals("cl") || u.equals("ml") || u.equals("l");
        if ("g".equals(fu)) return u.equals("g") || u.equals("kg");
        return true;
    }

    /** Quantité libre la plus proche (sans traverser un séparateur ni un autre favori). */
    static Morceau voisin(List<Morceau> m, int a, int sens) {
        for (int b = a + sens; b >= 0 && b < m.size(); b += sens) {
            Morceau x = m.get(b);
            if (x.k == 'Q') return x.pris ? null : x;
            if (x.k == 'S' || x.k == 'F') return null;
        }
        return null;
    }

    /** Convertit la quantité dite dans l'unité du favori (ml, g ou portion). Un nombre seul et petit = nombre de fois la quantité par défaut. */
    static double convertir(double v, String u, Item it) {
        String fu = it.u;
        if (u == null) {
            if ("portion".equals(fu)) return v;
            if (v <= 10) return v * it.q;                         // « deux eaux » → 2 × 20 cl
            if ("ml".equals(fu)) return v <= 100 ? v * 10 : v;    // « eau 20 » → 20 cl
            return v;                                             // « riz 90 » → 90 g
        }
        switch (u) {
            case "cl": return "portion".equals(fu) ? 1 : v * 10;
            case "ml": return "portion".equals(fu) ? 1 : v;
            case "l": return "portion".equals(fu) ? 1 : v * 1000;
            case "kg": return "portion".equals(fu) ? 1 : v * 1000;
            case "g": return "portion".equals(fu) ? 1 : v;
            default: return "portion".equals(fu) ? v : v * it.q;  // « 2 portions de riz » → 2 × 90 g
        }
    }

    static String[] utiles(String s) {
        List<String> l = new ArrayList<>();
        for (String w : s.split(" ")) if (!w.isEmpty() && !VIDES.contains(w) && !w.equals(",")) l.add(w);
        return l.toArray(new String[0]);
    }

    /** Nombre de mots de la phrase (à partir de i) qui correspondent à tous les mots du nom, en sautant les petits mots. 0 sinon. */
    static int correspond(String[] mots, int i, String[] nom) {
        int j = i, k = 0;
        while (k < nom.length && j < mots.length) {
            if (VIDES.contains(mots[j]) && j > i) { j++; continue; }
            if (!proche(mots[j], nom[k])) return 0;
            j++; k++;
        }
        return k == nom.length ? j - i : 0;
    }

    /** « riz » quand le favori s'appelle « Riz basmati » : accepté si un seul favori commence par ce mot. */
    static int[] premierMotUnique(String w, List<String[]> noms, List<Fav> deQui) {
        if (w.length() < 3 || VIDES.contains(w)) return new int[]{-1};
        int trouve = -1; String id = null;
        for (int k = 0; k < noms.size(); k++) {
            if (proche(w, noms.get(k)[0])) { String fid = deQui.get(k).id; if (id != null && !id.equals(fid)) return new int[]{-1}; id = fid; if (trouve < 0 || noms.get(k).length == 1) trouve = k; }
        }
        return new int[]{trouve};
    }

    /** Deux mots « proches » : pareils, au pluriel près, ou une seule lettre de différence (mots de 5 lettres ou plus). */
    static boolean proche(String a, String b) {
        if (a.equals(b)) return true;
        String sa = a.replaceAll("[sx]$", ""), sb = b.replaceAll("[sx]$", "");
        if (sa.equals(sb) && sa.length() >= 2) return true;
        if (Math.min(a.length(), b.length()) < 5 || Math.abs(a.length() - b.length()) > 1) return false;
        return distance(a, b) <= 1;
    }

    static int distance(String a, String b) {
        int[] p = new int[b.length() + 1], c = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) p[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            c[0] = i;
            for (int j = 1; j <= b.length(); j++) c[j] = Math.min(Math.min(c[j - 1] + 1, p[j] + 1), p[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            int[] t = p; p = c; c = t;
        }
        return p[b.length()];
    }
}
