package de.fizkarlsruhe.ise.ontoboard.collab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.protege.editor.core.prefs.Preferences;

/**
 * Settings persistence, and in particular the handling of the access token.
 *
 * <p>The token is a bearer credential and Protege's preferences are plain text on disk, so the
 * rules asserted here are about consent rather than convenience: nothing is written unless asked
 * for, and unticking the box removes what was already there. A default-on token would put a
 * credential somewhere the user never agreed to and would not think to clear.
 */
class CollabSettingsStoreTest {

    /** In-memory Preferences. Protege's is an interface, so no running Protege is needed. */
    private static final class FakePreferences implements Preferences {
        private final Map<String, Object> values = new LinkedHashMap<String, Object>();

        @Override
        public void clear() {
            values.clear();
        }

        @Override
        public String getString(String key, String fallback) {
            Object value = values.get(key);
            return value instanceof String ? (String) value : fallback;
        }

        @Override
        public void putString(String key, String value) {
            values.put(key, value);
        }

        @Override
        public List<String> getStringList(String key, List<String> fallback) {
            return fallback;
        }

        @Override
        public void putStringList(String key, List<String> value) {
            values.put(key, new ArrayList<String>(value));
        }

        @Override
        public int getInt(String key, int fallback) {
            Object value = values.get(key);
            return value instanceof Integer ? (Integer) value : fallback;
        }

        @Override
        public void putInt(String key, int value) {
            values.put(key, value);
        }

        @Override
        public float getFloat(String key, float fallback) {
            Object value = values.get(key);
            return value instanceof Float ? (Float) value : fallback;
        }

        @Override
        public void putFloat(String key, float value) {
            values.put(key, value);
        }

        @Override
        public long getLong(String key, long fallback) {
            Object value = values.get(key);
            return value instanceof Long ? (Long) value : fallback;
        }

        @Override
        public void putLong(String key, long value) {
            values.put(key, value);
        }

        @Override
        public double getDouble(String key, double fallback) {
            Object value = values.get(key);
            return value instanceof Double ? (Double) value : fallback;
        }

        @Override
        public void putDouble(String key, double value) {
            values.put(key, value);
        }

        @Override
        public boolean getBoolean(String key, boolean fallback) {
            Object value = values.get(key);
            return value instanceof Boolean ? (Boolean) value : fallback;
        }

        @Override
        public void putBoolean(String key, boolean value) {
            values.put(key, value);
        }

        @Override
        public byte[] getByteArray(String key, byte[] fallback) {
            return fallback;
        }

        @Override
        public void putByteArray(String key, byte[] value) {
            values.put(key, value);
        }

        @Override
        public List<byte[]> getByteArrayList(String key, List<byte[]> fallback) {
            return fallback;
        }

        @Override
        public void putByteArrayList(String key, List<byte[]> value) {
            values.put(key, value);
        }

        /** What actually reached the store, for asserting on the token directly. */
        String raw(String key) {
            Object value = values.get(key);
            return value == null ? null : String.valueOf(value);
        }
    }

    private final FakePreferences preferences = new FakePreferences();

    private static CollabSettings configured() {
        return new CollabSettings("ws://team.example:1235", "board-7", "jwt-secret-value",
                "alice", "#AA0000");
    }

    @Test
    void nothingSavedYetLoadsAsGitMode() {
        CollabSettings loaded = CollabSettingsStore.load(preferences);

        assertEquals(CollabSettings.Mode.GIT, loaded.getMode());
        assertFalse(loaded.isLive());
    }

    @Test
    void everythingExceptTheTokenSurvivesARestart() {
        CollabSettingsStore.save(preferences, configured(), false);

        CollabSettings loaded = CollabSettingsStore.load(preferences);
        assertEquals("ws://team.example:1235", loaded.getBridgeUrl());
        assertEquals("board-7", loaded.getBoard());
        assertEquals("alice", loaded.getDisplayName());
        assertEquals("#AA0000", loaded.getColour());
    }

    /**
     * The default. A token left on disk without being asked for is a credential the user never
     * consented to storing, and Protege's preferences are plain text.
     */
    @Test
    void theTokenIsNotWrittenUnlessRememberingWasAskedFor() {
        CollabSettingsStore.save(preferences, configured(), false);

        assertEquals("", preferences.raw(CollabSettingsStore.KEY_TOKEN),
                "the token reached disk without being asked for");
        assertEquals("", CollabSettingsStore.load(preferences).getToken());
        assertFalse(CollabSettingsStore.isTokenRemembered(preferences));
    }

    @Test
    void theTokenIsWrittenAndReloadedWhenRememberingWasAskedFor() {
        CollabSettingsStore.save(preferences, configured(), true);

        assertEquals("jwt-secret-value", CollabSettingsStore.load(preferences).getToken());
        assertTrue(CollabSettingsStore.isTokenRemembered(preferences));
        assertTrue(CollabSettingsStore.load(preferences).isLive(),
                "a fully configured, remembered session should come back live");
    }

    /**
     * Only writing the token when the box is ticked would leave an earlier one behind, so
     * unticking has to overwrite. Otherwise the user asks for the credential to be removed and it
     * quietly stays.
     */
    @Test
    void untickingRememberRemovesATokenAlreadyOnDisk() {
        CollabSettingsStore.save(preferences, configured(), true);
        assertEquals("jwt-secret-value", preferences.raw(CollabSettingsStore.KEY_TOKEN));

        CollabSettingsStore.save(preferences, configured(), false);

        assertEquals("", preferences.raw(CollabSettingsStore.KEY_TOKEN),
                "the old token was left on disk after the user asked for it not to be");
    }

    @Test
    void forgettingTheTokenLeavesTheRestOfTheConfigurationAlone() {
        CollabSettingsStore.save(preferences, configured(), true);

        CollabSettingsStore.forgetToken(preferences);

        CollabSettings loaded = CollabSettingsStore.load(preferences);
        assertEquals("", loaded.getToken());
        assertEquals("board-7", loaded.getBoard(), "only the credential should have gone");
        assertEquals("ws://team.example:1235", loaded.getBridgeUrl());
        assertFalse(loaded.isLive(),
                "without a token the session is git mode, which is the honest state");
    }

    /**
     * A ticked box with an empty value is not a remembered token. Reporting it as one would show
     * the user a session that cannot connect as though it were configured.
     */
    @Test
    void aTickedBoxWithNoTokenDoesNotCountAsRemembered() {
        preferences.putBoolean(CollabSettingsStore.KEY_REMEMBER_TOKEN, true);
        preferences.putString(CollabSettingsStore.KEY_TOKEN, "   ");

        assertFalse(CollabSettingsStore.isTokenRemembered(preferences));
    }

    @Test
    void nullFieldsAreStoredAsEmptyRatherThanCrashingTheStore() {
        CollabSettingsStore.save(preferences,
                new CollabSettings(null, null, null, null, null), true);

        CollabSettings loaded = CollabSettingsStore.load(preferences);
        assertEquals(CollabSettings.Mode.GIT, loaded.getMode());
    }

    /** Naming the location is what makes the checkbox informed consent rather than a click. */
    @Test
    void theStorageLocationIsNamedSpecificallyEnoughToBeFound() {
        String description = CollabSettingsStore.tokenStorageDescription();

        assertTrue(description.length() > 15, description);
        assertTrue(description.contains("registry") || description.contains("~/"),
                "the description must point somewhere a user could actually look: "
                        + description);
    }

    @Test
    void ourKeysAreNamespacedSoTheyCannotCollideWithProteges() {
        for (String key : new String[] {CollabSettingsStore.KEY_SERVER,
            CollabSettingsStore.KEY_BOARD, CollabSettingsStore.KEY_TOKEN,
            CollabSettingsStore.KEY_REMEMBER_TOKEN, CollabSettingsStore.KEY_DISPLAY_NAME,
            CollabSettingsStore.KEY_COLOUR}) {
            assertTrue(key.startsWith("collab."), key);
        }
        assertTrue(CollabSettingsStore.PREFERENCE_SET.startsWith("de.fizkarlsruhe"),
                CollabSettingsStore.PREFERENCE_SET);
    }
}
