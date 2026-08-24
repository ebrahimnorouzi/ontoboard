package de.fizkarlsruhe.ise.ontoboard.canvas;

import java.util.Map;

/**
 * Assigns a stroke colour per namespace so a diagram mixing several vocabularies is
 * readable at a glance - imported BFO or RO terms stop looking like local ones.
 *
 * <p>Assignments are written into the caller's map (the sidecar's {@code prefixColors}),
 * so a namespace keeps its colour across restarts. Without that persistence the palette
 * would be handed out in whatever order entities happened to be projected, and the same
 * ontology would come back in different colours each session.
 */
public final class PrefixColours {

    /**
     * Chosen to stay legible as a 2px stroke against a white node fill, and to remain
     * distinguishable for the most common forms of colour blindness (no red/green pair).
     */
    private static final String[] PALETTE = {
        "#4A90D9", // blue
        "#7B61A8", // purple
        "#3E8E5A", // green
        "#B08900", // amber
        "#C2554D", // brick
        "#2A8C8C", // teal
        "#8A6D3B", // brown
        "#5B6B7C", // slate
    };

    private final Map<String, String> assignments;

    /** @param assignments live map to read and extend, typically {@code layout.prefixColors} */
    public PrefixColours(Map<String, String> assignments) {
        this.assignments = assignments;
    }

    /** The stroke colour for the namespace {@code iri} belongs to. Stable for the session. */
    public String colourFor(String iri) {
        String namespace = namespaceOf(iri);
        String existing = assignments.get(namespace);
        if (existing != null) {
            return existing;
        }
        // Deterministic given the current map size, and persisted immediately so the next
        // session reproduces it rather than re-deriving from a different projection order.
        String colour = PALETTE[assignments.size() % PALETTE.length];
        assignments.put(namespace, colour);
        return colour;
    }

    /**
     * Everything up to and including the final {@code #} or {@code /}. Returns the whole
     * string when neither is present, so a delimiter-less IRI still groups with itself.
     */
    public static String namespaceOf(String iri) {
        int hash = iri.lastIndexOf('#');
        if (hash >= 0) {
            return iri.substring(0, hash + 1);
        }
        int slash = iri.lastIndexOf('/');
        if (slash >= 0) {
            return iri.substring(0, slash + 1);
        }
        return iri;
    }
}
