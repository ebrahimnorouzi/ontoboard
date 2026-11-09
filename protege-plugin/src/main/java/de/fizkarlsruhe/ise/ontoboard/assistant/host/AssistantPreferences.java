package de.fizkarlsruhe.ise.ontoboard.assistant.host;

import de.fizkarlsruhe.ise.ontoboard.assistant.connect.Connection;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.Connections;
import de.fizkarlsruhe.ise.ontoboard.assistant.roles.RoleStore;
import de.fizkarlsruhe.ise.ontoboard.assistant.session.SettingsStore;
import de.fizkarlsruhe.ise.ontoboard.tools.PreferenceStorage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.protege.editor.core.prefs.Preferences;
import org.protege.editor.core.prefs.PreferencesManager;

/**
 * The assistant's per-person settings in Protégé's preferences, beside the collaboration
 * settings in the same set ({@code de.fizkarlsruhe.ise.ontoboard}).
 *
 * <p><b>A key is stored only when the person ticks "Remember the key on this computer"</b>, as
 * {@code CollabSettingsStore} does for the collaboration token, because these preferences are
 * plain text on disk ({@link PreferenceStorage#where}). Forgetting overwrites the value rather
 * than leaving it.
 *
 * <p>Two limits of the store shape the keys: Protégé's {@code Preferences} has no remove and no
 * way to list keys, so custom role ids are kept in one comma-separated value and a removed role
 * is written empty; and a Java preference key is at most 80 characters, so a connection's name or
 * a role's id is shortened and suffixed with its hash when it would not fit.
 */
public final class AssistantPreferences implements SettingsStore {

    static final String PREFERENCE_SET = "de.fizkarlsruhe.ise.ontoboard";
    static final String CONNECTIONS = "assistant.connections";
    static final String ACTIVE = "assistant.active";
    static final String KEY = "assistant.key.";
    static final String REMEMBER = "assistant.rememberKey.";
    static final String ROLE = "assistant.role";
    static final String FIELD = "assistant.field";
    static final String PROVENANCE = "assistant.provenance";
    static final String ROLE_IDS = "assistant.roleIds";
    static final String ROLE_TEXT = "assistant.roleText.";
    static final String SHARING = "assistant.sharing.";

    /** {@code java.util.prefs.Preferences.MAX_KEY_LENGTH}. */
    static final int MAX_KEY = 80;

    private final Preferences preferences;

    public AssistantPreferences(Preferences preferences) {
        this.preferences = preferences;
    }

    public static AssistantPreferences load() {
        return new AssistantPreferences(
                PreferencesManager.getInstance().getApplicationPreferences(PREFERENCE_SET));
    }

    @Override
    public List<Connection> connections() {
        try {
            return Connections.fromJson(preferences.getString(CONNECTIONS, ""));
        } catch (IllegalArgumentException unreadable) {
            // A hand-edited or half-written value must not take the column down with it.
            return new ArrayList<Connection>();
        }
    }

    @Override
    public void saveConnections(List<Connection> connections) {
        preferences.putString(CONNECTIONS, Connections.toJson(connections));
    }

    @Override
    public String activeConnection() {
        return preferences.getString(ACTIVE, "");
    }

    @Override
    public void setActiveConnection(String name) {
        preferences.putString(ACTIVE, name == null ? "" : name);
    }

    @Override
    public String rememberedKey(String connectionName) {
        if (!preferences.getBoolean(key(REMEMBER, connectionName), false)) {
            return "";
        }
        return preferences.getString(key(KEY, connectionName), "").trim();
    }

    @Override
    public void rememberKey(String connectionName, String apiKey) {
        preferences.putBoolean(key(REMEMBER, connectionName), true);
        preferences.putString(key(KEY, connectionName), apiKey == null ? "" : apiKey.trim());
    }

    @Override
    public void forgetKey(String connectionName) {
        preferences.putString(key(KEY, connectionName), "");
        preferences.putBoolean(key(REMEMBER, connectionName), false);
    }

    @Override
    public boolean isKeyRemembered(String connectionName) {
        return !rememberedKey(connectionName).isEmpty();
    }

    @Override
    public String keyStorageDescription() {
        return PreferenceStorage.where();
    }

    @Override
    public String roleId() {
        String id = preferences.getString(ROLE, "");
        return id.isEmpty() ? "ontology-expert" : id;
    }

    @Override
    public void setRoleId(String id) {
        preferences.putString(ROLE, id == null ? "" : id);
    }

    @Override
    public String field() {
        return preferences.getString(FIELD, "");
    }

    @Override
    public void setField(String field) {
        preferences.putString(FIELD, field == null ? "" : field.trim());
    }

    @Override
    public boolean isProvenance() {
        return preferences.getBoolean(PROVENANCE, true);
    }

    @Override
    public void setProvenance(boolean on) {
        preferences.putBoolean(PROVENANCE, on);
    }

    @Override
    public RoleStore roles() {
        return new RoleStore() {
            @Override
            public Map<String, String> load() {
                Map<String, String> texts = new LinkedHashMap<String, String>();
                for (String id : ids()) {
                    String text = preferences.getString(key(ROLE_TEXT, id), "");
                    if (!text.isEmpty()) {
                        texts.put(id, text);
                    }
                }
                return texts;
            }

            @Override
            public void save(String id, String text) {
                List<String> ids = ids();
                if (!ids.contains(id)) {
                    ids.add(id);
                    preferences.putString(ROLE_IDS, String.join(",", ids));
                }
                preferences.putString(key(ROLE_TEXT, id), text == null ? "" : text);
            }

            @Override
            public void remove(String id) {
                List<String> ids = ids();
                if (ids.remove(id)) {
                    preferences.putString(ROLE_IDS, String.join(",", ids));
                }
                preferences.putString(key(ROLE_TEXT, id), "");
            }
        };
    }

    private List<String> ids() {
        List<String> ids = new ArrayList<String>();
        for (String id : preferences.getString(ROLE_IDS, "").split(",")) {
            if (!id.trim().isEmpty()) {
                ids.add(id.trim());
            }
        }
        return ids;
    }

    /** A preference key within Java's 80 characters, readable where it fits. */
    @Override
    public String conversationSharing(String project) {
        return preferences.getString(key(SHARING, project), "");
    }

    @Override
    public void setConversationSharing(String project, String choice) {
        preferences.putString(key(SHARING, project), choice == null ? "" : choice);
    }

    static String key(String prefix, String name) {
        String plain = prefix + (name == null ? "" : name);
        if (plain.length() <= MAX_KEY) {
            return plain;
        }
        String hash = Integer.toHexString(plain.hashCode());
        String start = name.replaceAll("[^A-Za-z0-9-]", "").toLowerCase(Locale.ROOT);
        int room = MAX_KEY - prefix.length() - hash.length() - 1;
        return prefix + start.substring(0, Math.max(0, Math.min(room, start.length()))) + "."
                + hash;
    }
}
