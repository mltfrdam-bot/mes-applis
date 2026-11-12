package fr.mesapplis.activite;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/** Petit lanceur pour tester LecteurHub hors du téléphone : java … EssaiLecteur <fichier choisi> <import.json>
    Écrit « OK », ou « REFUS: message » (code de sortie 2). Utilisé par activite/tests/test-lecteur.js. */
public class EssaiLecteur {
    public static void main(String[] a) throws Exception {
        final File entree = new File(a[0]);
        try {
            LecteurHub.preparer(() -> new FileInputStream(entree), new File(a[1]));
            System.out.println("OK");
        } catch (IOException e) {
            System.out.println("REFUS: " + e.getMessage());
            System.exit(2);
        }
    }
}
