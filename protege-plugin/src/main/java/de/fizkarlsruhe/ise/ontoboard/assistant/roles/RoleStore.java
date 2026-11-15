package de.fizkarlsruhe.ise.ontoboard.assistant.roles;

import java.io.IOException;
import java.util.Map;

/**
 * Where edited and custom roles are kept: files in the project folder, so a team shares them
 * through git, or Protégé's preferences when the ontology has no file yet (spec D4).
 */
public interface RoleStore {

    /** Every stored text by role id. */
    Map<String, String> load() throws IOException;

    void save(String id, String text) throws IOException;

    void remove(String id) throws IOException;

    /**
     * Whether two ids that differ only in case name one stored role here, as two file names do
     * on Windows and macOS. Not in Protégé's preferences, nor in a folder on Linux.
     */
    default boolean ignoresCase() {
        return false;
    }
}
