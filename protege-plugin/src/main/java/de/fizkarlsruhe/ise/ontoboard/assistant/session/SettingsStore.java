package de.fizkarlsruhe.ise.ontoboard.assistant.session;

import de.fizkarlsruhe.ise.ontoboard.assistant.connect.Connection;
import de.fizkarlsruhe.ise.ontoboard.assistant.roles.RoleStore;
import java.util.List;

/**
 * A person's assistant settings: connections, a remembered key per connection (only if they
 * ticked the box), the role and their field. Kept in Protégé's preferences by
 * {@code host/AssistantPreferences}; an interface so the Settings tab is testable without Protégé.
 */
public interface SettingsStore {

    List<Connection> connections();

    void saveConnections(List<Connection> connections);

    /** The active connection's name, or empty. */
    String activeConnection();

    void setActiveConnection(String name);

    /** Empty unless the person chose to remember a key for this connection. */
    String rememberedKey(String connectionName);

    void rememberKey(String connectionName, String key);

    /** Overwrites the stored key, so unticking the box takes it off the disk. */
    void forgetKey(String connectionName);

    boolean isKeyRemembered(String connectionName);

    /** Where a remembered key is stored, in words: shown next to the tick box. */
    String keyStorageDescription();

    String roleId();

    void setRoleId(String id);

    /** The domain expert's field, or empty. */
    String field();

    void setField(String field);

    /** Roles kept for this person, used when the ontology has no project folder. */
    RoleStore roles();

    /**
     * Whether what Accept adds is annotated {@code dcterms:contributor "OntoBoard assistant
     * (<kind> <model>)"} (spec D5). On unless switched off.
     */
    boolean isProvenance();

    void setProvenance(boolean on);

    /**
     * This person's choice for their own saved conversations in one project - "private",
     * "released", or "" when they have not chosen. Kept per person, never in the project: one
     * person's Release in the shared project file published everyone's conversations (1.144.0,
     * review 1.143.0 I-1).
     *
     * @param project the project folder's key, {@code ProjectFolder.key()}
     */
    String conversationSharing(String project);

    void setConversationSharing(String project, String choice);

    /** The active connection, the first one when none was chosen, or null when there are none. */
    default Connection active() {
        List<Connection> all = connections();
        for (Connection connection : all) {
            if (connection.getName().equals(activeConnection())) {
                return connection;
            }
        }
        return all.isEmpty() ? null : all.get(0);
    }
}
