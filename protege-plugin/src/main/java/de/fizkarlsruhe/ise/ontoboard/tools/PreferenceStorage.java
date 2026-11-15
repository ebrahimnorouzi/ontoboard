package de.fizkarlsruhe.ise.ontoboard.tools;

import java.util.Locale;

/**
 * Where Protégé's preferences - and so anything OntoBoard remembers in them - are kept on disk,
 * in words a person can check: plain text, readable by anything running as that user.
 *
 * <p>One sentence for every credential OntoBoard can remember (the collaboration token, a model's
 * API key), so the place named when someone ticks "remember" is the same everywhere. Naming the
 * place is the difference between consenting to store a credential and clicking past a box.
 */
public final class PreferenceStorage {

    private PreferenceStorage() {
    }

    public static String where() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "the Windows registry, under HKEY_CURRENT_USER\\SOFTWARE\\JavaSoft\\Prefs";
        }
        if (os.contains("mac")) {
            return "a plist under ~/Library/Preferences";
        }
        return "a file under ~/.java/.userPrefs";
    }
}
