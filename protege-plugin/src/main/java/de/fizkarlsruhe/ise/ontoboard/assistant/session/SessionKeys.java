package de.fizkarlsruhe.ise.ontoboard.assistant.session;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keys typed in this Protégé session, by connection name: held in memory only and gone when
 * Protégé closes. The default home for a key (spec D2).
 */
public final class SessionKeys {

    private static final Map<String, String> KEYS = new ConcurrentHashMap<String, String>();
    /**
     * Every key this session has held or been told of - typed, remembered or from the
     * environment. What OntoBoard writes is redacted against them all, so a key replaced,
     * forgotten or deleted in Settings stays out of a conversation or a role that holds it
     * (1.145.0, review 1.144.0 privacy-01). In memory only, like the typed keys.
     */
    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();

    private SessionKeys() {
    }

    /** Empty when none was typed. */
    public static String get(String connectionName) {
        String key = connectionName == null ? null : KEYS.get(connectionName);
        return key == null ? "" : key;
    }

    public static void put(String connectionName, String key) {
        if (connectionName == null) {
            return;
        }
        if (key == null || key.trim().isEmpty()) {
            KEYS.remove(connectionName);
        } else {
            KEYS.put(connectionName, key.trim());
            SEEN.add(key.trim());
        }
    }

    public static void clear(String connectionName) {
        if (connectionName != null) {
            KEYS.remove(connectionName);
        }
    }

    /** Keeps these keys known for the rest of the session; a blank one is not a key. */
    public static void note(Collection<String> keys) {
        if (keys == null) {
            return;
        }
        for (String key : keys) {
            if (key != null && !key.trim().isEmpty()) {
                SEEN.add(key.trim());
            }
        }
    }

    /** These keys, kept known from now on, and every other key of the session. */
    public static Collection<String> seen(Collection<String> knownNow) {
        note(knownNow);
        return new ArrayList<String>(SEEN);
    }
}
