package de.fizkarlsruhe.ise.ontoboard.assistant.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.assistant.connect.Connection;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.ConnectionKind;
import de.fizkarlsruhe.ise.ontoboard.assistant.roles.RoleStore;
import de.fizkarlsruhe.ise.ontoboard.collab.CollabSettingsStoreAccess;
import de.fizkarlsruhe.ise.ontoboard.tools.PreferenceStorage;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The assistant's per-person settings in Protégé's preferences, and above all the key (spec D2):
 * nothing stored unless the person ticked the box, and unticking erases it.
 */
class AssistantPreferencesTest {

    private final FakePreferences preferences = new FakePreferences();
    private final AssistantPreferences store = new AssistantPreferences(preferences);

    @Test
    void connectionsRoundTrip() {
        List<Connection> saved = Arrays.asList(
                Connection.of("Claude", ConnectionKind.ANTHROPIC).withModel("claude-x"),
                Connection.of("Ollama", ConnectionKind.OLLAMA).withModel("llama3.1"));

        store.saveConnections(saved);
        store.setActiveConnection("Ollama");

        assertEquals(saved, store.connections());
        assertEquals("Ollama", store.activeConnection());
        assertEquals("Ollama", store.active().getName());
    }

    @Test
    void theFirstConnectionIsActiveWhenNoneWasChosen() {
        store.saveConnections(Arrays.asList(Connection.of("A", ConnectionKind.OLLAMA)));

        assertEquals("A", store.active().getName());
        assertNull(new AssistantPreferences(new FakePreferences()).active());
    }

    @Test
    void unreadableConnectionsAreNoConnections() {
        preferences.putString("assistant.connections", "not json");

        assertTrue(store.connections().isEmpty());
    }

    @Test
    void aKeyIsNotStoredUnlessRemembered() {
        assertEquals("", store.rememberedKey("Claude"));
        assertFalse(store.isKeyRemembered("Claude"));
        for (Map.Entry<String, Object> value : preferences.all().entrySet()) {
            assertFalse(String.valueOf(value.getValue()).contains("sk-"), value.getKey());
        }

        store.rememberKey("Claude", "  sk-secret\n");

        assertEquals("sk-secret", store.rememberedKey("Claude"));
        assertTrue(store.isKeyRemembered("Claude"));
    }

    @Test
    void forgettingOverwritesTheStoredKey() {
        store.rememberKey("Claude", "sk-secret");

        store.forgetKey("Claude");

        assertEquals("", store.rememberedKey("Claude"));
        assertFalse(store.isKeyRemembered("Claude"));
        for (Map.Entry<String, Object> value : preferences.all().entrySet()) {
            assertFalse(String.valueOf(value.getValue()).contains("sk-secret"),
                    "the key is still on disk under " + value.getKey());
        }
    }

    @Test
    void preferenceKeysStayWithinJavasEightyCharacters() throws IOException {
        String longName = "A connection with a really quite long name for the lab's Ollama server";
        store.rememberKey(longName, "k");
        store.roles().save("a-very-long-custom-role-name-that-goes-on-and-on-and-on-and-on", "x");

        for (String key : preferences.all().keySet()) {
            assertTrue(key.length() <= 80, key.length() + ": " + key);
        }
        assertEquals("k", store.rememberedKey(longName));
    }

    @Test
    void rolesWithoutAProjectFolderLiveInPreferences() throws IOException {
        RoleStore roles = store.roles();

        roles.save("ontology-expert", "# Ontology expert\nedited");
        roles.save("materials", "# Materials\nsteel");
        roles.remove("ontology-expert");

        Map<String, String> loaded = store.roles().load();
        assertEquals(1, loaded.size());
        assertEquals("# Materials\nsteel", loaded.get("materials"));
    }

    @Test
    void theRoleAndTheFieldAreRemembered() {
        assertEquals("ontology-expert", store.roleId());

        store.setRoleId("domain-expert");
        store.setField("materials science");

        assertEquals("domain-expert", store.roleId());
        assertEquals("materials science", store.field());
    }

    @Test
    void theStorageDescriptionIsTheCollaborationTokensOne() {
        assertEquals(CollabSettingsStoreAccess.tokenStorageDescription(),
                PreferenceStorage.where());
        assertEquals(PreferenceStorage.where(), store.keyStorageDescription());
    }
}
