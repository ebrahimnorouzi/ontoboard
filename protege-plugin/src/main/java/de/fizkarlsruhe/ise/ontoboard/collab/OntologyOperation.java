package de.fizkarlsruhe.ise.ontoboard.collab;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One ontology mutation, in the shape the collaboration bridge carries.
 *
 * <p>The field names and the type vocabulary have to match {@code collab/bridge.mjs} exactly.
 * That is not a stylistic choice: the bridge refuses an operation whose {@code type} it does
 * not know, so a vocabulary that has drifted shows up as a peer whose edits stop arriving. The
 * list is pinned here and compared against the bridge's own by test.
 *
 * <p>Both were originally copied from the OntoBoard web application's
 * {@code frontend/src/collab/useOperationSync.ts}, and the comments here used to name that file
 * as the source of truth. The web application is no longer part of this repository - it is in
 * the history at the tag {@code web-app-final} - so the bridge is now the only other place the
 * vocabulary lives. The shape is unchanged, because an older web client may still be running
 * against the same bridge.
 */
public final class OntologyOperation {

    /** The 18 types the bridge knows. Keep in step with {@code collab/bridge.mjs}. */
    public static final Set<String> TYPES = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList(
                    "addClass", "updateClass", "removeClass",
                    "addProperty", "removeProperty", "addSubClassOf",
                    "addIndividual", "updateIndividual",
                    "addLiteral", "updateLiteral", "removeLiteral",
                    "addStickyNote", "updateStickyNote", "removeStickyNote",
                    "addFrame", "updateFrame", "removeFrame",
                    // Editorial notes, definitions, provenance - any annotation but rdfs:label,
                    // which travels as updateClass/updateIndividual because the web client models
                    // a label as a field on the entity rather than as an annotation.
                    "updateAnnotation")));

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
                    + "'. The bridge would refuse it, so the peer would never see the edit; add "
                    + "it to collab/bridge.mjs and OntologyOperation.TYPES together.");
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

    /** Live view of the payload; the shape is per-type and carried by collab/bridge.mjs. */
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
