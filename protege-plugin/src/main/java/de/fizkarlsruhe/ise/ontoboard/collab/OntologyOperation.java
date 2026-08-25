package de.fizkarlsruhe.ise.ontoboard.collab;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One ontology mutation, in the shape the web client already uses.
 *
 * <p>The field names and the type vocabulary are copied from
 * {@code frontend/src/collab/useOperationSync.ts}. That is not a stylistic choice: an
 * operation whose {@code type} or {@code data} shape the web client does not recognise is a
 * <em>silent no-op</em> there, not an error. Nothing logs, nothing throws, the other user
 * simply never sees the edit. That is far harder to notice than a crash, so the vocabulary is
 * pinned here and asserted by test.
 */
public final class OntologyOperation {

    /** The 16 types in useOperationSync.ts. Keep in step with it and with bridge.mjs. */
    public static final Set<String> TYPES = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList(
                    "addClass", "updateClass", "removeClass",
                    "addProperty", "removeProperty", "addSubClassOf",
                    "addIndividual", "updateIndividual",
                    "addLiteral", "updateLiteral", "removeLiteral",
                    "addStickyNote", "updateStickyNote", "removeStickyNote",
                    "addFrame", "updateFrame", "removeFrame")));

    private final String id;
    private final String type;
    private final long timestamp;
    private final String userId;
    private final Map<String, Object> data;

    public OntologyOperation(String id, String type, long timestamp, String userId,
            Map<String, Object> data) {
        if (id == null || id.trim().isEmpty()) {
            // Deduplication is by id. Without one the operation echoes between clients.
            throw new IllegalArgumentException("operation id is required for deduplication");
        }
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("unknown operation type '" + type
                    + "'. The web client would ignore it silently; add it to "
                    + "useOperationSync.ts, bridge.mjs and OntologyOperation.TYPES together.");
        }
        this.id = id;
        this.type = type;
        this.timestamp = timestamp;
        this.userId = userId == null ? "" : userId;
        this.data = data == null ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(data);
    }

    /** A locally-originated operation, with a fresh id and the current time. */
    public static OntologyOperation local(String type, String userId,
            Map<String, Object> data) {
        return new OntologyOperation(UUID.randomUUID().toString(), type,
                System.currentTimeMillis(), userId, data);
    }

    public String getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getUserId() {
        return userId;
    }

    /** Live view of the payload; shape is per-type and defined by useOperationSync.ts. */
    public Map<String, Object> getData() {
        return data;
    }

    /** Convenience for the common case of a payload carrying a single IRI. */
    public String getIri() {
        Object iri = data.get("iri");
        return iri == null ? null : String.valueOf(iri);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof OntologyOperation
                && id.equals(((OntologyOperation) other).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return type + "(" + id.substring(0, Math.min(8, id.length())) + ") by " + userId;
    }
}
