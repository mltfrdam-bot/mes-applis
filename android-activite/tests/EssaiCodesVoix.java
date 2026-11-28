package fr.mesapplis.activite;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Mots-codes de fin de dictée (essai 36). java … EssaiCodesVoix <phrases.txt> → « code | phrase sans le code ». Utilisé par activite/tests/test-codes-voix.js. */
public class EssaiCodesVoix {
    public static void main(String[] a) throws Exception {
        System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8"));
        String[] noms = { "aucun", "terminé", "lire", "effacer", "copier" };
        for (String p : Files.readAllLines(Paths.get(a[0]), StandardCharsets.UTF_8)) System.out.println(noms[CodesVoix.code(p)] + " | " + CodesVoix.sans(p));
    }
}
