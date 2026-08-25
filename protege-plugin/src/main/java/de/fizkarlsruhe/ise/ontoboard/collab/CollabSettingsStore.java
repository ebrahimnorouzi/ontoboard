package de.fizkarlsruhe.ise.ontoboard.collab;

import org.protege.editor.core.prefs.Preferences;
import org.protege.editor.core.prefs.PreferencesManager;

/**
 * Remembers a user's collaboration settings between Protege sessions.
 *
 * <p>OntoBoard ships no server. A team runs its own and each member points the plugin at it, so
 * these five fields are per-user configuration rather than anything shared, and Protege's own
 * preference store is the right home for them.
 *
 * <p><b>The access token is not saved unless asked for.</b> It is a bearer credential, and
 * Protege's preferences are plain text on disk - the Windows registry under
 * {@code HKCU\\SOFTWARE\\JavaSoft\\Prefs}, a file under {@code ~/.java} elsewhere - readable by
 * anything running as that user. Saving it by default would put a credential somewhere the user
 * never agreed to and would not think to clear, so {@link #isTokenRemembered} defaults to false
 * and the dialog says plainly where it goes when they turn it on. Everything else is harmless to
 * keep: a server address, a board id, a display name and a colour.
 *
 * <p>The methods taking a {@link Preferences} exist so all of this is testable against a fake -
 * the interface is Protege's, but nothing here needs a running Protege.
 */
public final class CollabSettingsStore {

    /** Our own preference set, so nothing here can disturb Protege's own keys. */
    static final String PREFERENCE_SET = "de.fizkarlsruhe.ise.ontoboard";

    static final String KEY_SERVER = "collab.server";
    static final String KEY_BOARD = "collab.board";
    static final String KEY_TOKEN = "collab.token";
    static final String KEY_REMEMBER_TOKEN = "collab.rememberToken";
    static final String KEY_DISPLAY_NAME = "collab.displayName";
    static final String KEY_COLOUR = "collab.colour";

    private CollabSettingsStore() {
    }

    /** Settings as last saved, or {@link CollabSettings#offline()} if never configured. */
    public static CollabSettings load() {
        return load(preferences());
    }

    /**
     * Saves everything except the token, which is written only when {@code rememberToken} is set
     * and otherwise actively cleared - so turning the option off removes a token already on disk
     * rather than leaving it there.
     */
    public static void save(CollabSettings settings, boolean rememberToken) {
        save(preferences(), settings, rememberToken);
    }

    /** True when a token was saved on this machine, so the dialog can show the box ticked. */
    public static boolean isTokenRemembered() {
        return isTokenRemembered(preferences());
    }

    /** Forgets the token without touching the rest, for a "sign out of the board" action. */
    public static void forgetToken() {
        forgetToken(preferences());
    }

    // ------------------------------------------------------------------ testable core

    static CollabSettings load(Preferences preferences) {
        return new CollabSettings(
                preferences.getString(KEY_SERVER, ""),
                preferences.getString(KEY_BOARD, ""),
                preferences.getBoolean(KEY_REMEMBER_TOKEN, false)
                        ? preferences.getString(KEY_TOKEN, "") : "",
                preferences.getString(KEY_DISPLAY_NAME, ""),
                preferences.getString(KEY_COLOUR, ""));
    }

    static void save(Preferences preferences, CollabSettings settings, boolean rememberToken) {
        preferences.putString(KEY_SERVER, nullToEmpty(settings.getBridgeUrl()));
        preferences.putString(KEY_BOARD, nullToEmpty(settings.getBoard()));
        preferences.putString(KEY_DISPLAY_NAME, nullToEmpty(settings.getDisplayName()));
        preferences.putString(KEY_COLOUR, nullToEmpty(settings.getColour()));
        preferences.putBoolean(KEY_REMEMBER_TOKEN, rememberToken);
        if (rememberToken) {
            preferences.putString(KEY_TOKEN, nullToEmpty(settings.getToken()));
        } else {
            // Overwritten rather than left: a user who unticks the box has asked for the
            // credential to stop being on disk, and only writing when true would leave it.
            preferences.putString(KEY_TOKEN, "");
        }
    }

    static boolean isTokenRemembered(Preferences preferences) {
        return preferences.getBoolean(KEY_REMEMBER_TOKEN, false)
                && !preferences.getString(KEY_TOKEN, "").trim().isEmpty();
    }

    static void forgetToken(Preferences preferences) {
        preferences.putString(KEY_TOKEN, "");
        preferences.putBoolean(KEY_REMEMBER_TOKEN, false);
    }

    /**
     * Where a saved token would live, for the dialog to say out loud.
     *
     * <p>Naming the location is the difference between a user consenting to storing a credential
     * and merely clicking past a checkbox.
     */
    static String tokenStorageDescription() {
        String os = System.getProperty("os.name", "");
        if (os.toLowerCase().contains("win")) {
            return "the Windows registry, under HKEY_CURRENT_USER\\SOFTWARE\\JavaSoft\\Prefs";
        }
        if (os.toLowerCase().contains("mac")) {
            return "a plist under ~/Library/Preferences";
        }
        return "a file under ~/.java/.userPrefs";
    }

    private static Preferences preferences() {
        return PreferencesManager.getInstance().getApplicationPreferences(PREFERENCE_SET);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
