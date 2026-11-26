package fr.mesapplis.activite;

import android.app.Application;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Essai 32 : si l'appli plante, l'erreur exacte est gardée dans « plantage.txt » et montrée au prochain lancement (écran Plantage, 📋 Copier). */
public class Appli extends Application {
    static File fichier(android.content.Context c) { return new File(c.getFilesDir(), "plantage.txt"); }

    @Override public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler avant = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                StringWriter sw = new StringWriter(); e.printStackTrace(new PrintWriter(sw));
                String txt = "🐞 PLANTAGE — Journal ESSAI " + versionNom() + " — " + new SimpleDateFormat("EEE dd/MM HH:mm:ss", Locale.FRANCE).format(new Date())
                        + " — Android " + android.os.Build.VERSION.RELEASE + " — " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                        + "\nFil : " + t.getName() + "\n" + sw;
                if (txt.length() > 12000) txt = txt.substring(0, 12000);
                try (FileOutputStream o = new FileOutputStream(fichier(this), false)) { o.write(txt.getBytes(StandardCharsets.UTF_8)); }
                Bug.note(this, "PLANTAGE", e.toString());
            } catch (Throwable ignored) {}
            if (avant != null) avant.uncaughtException(t, e); else System.exit(2);
        });
    }

    String versionNom() { try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "?"; } }
}
