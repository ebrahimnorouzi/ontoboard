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
 *
 * <p>A board saved before 1.66.0 keeps the colours it stored, because a stored assignment always
 * wins: only a namespace met for the first time takes the current palette. Rewriting old boards onto
 * it would be a one-line migration dropping any stored value the palette no longer holds, and is
 * deliberately not done - silently recolouring somebody's saved diagram is a worse outcome than a
 * board carrying one old hex.
 */
public final class PrefixColours {

    /**
     * A lightness ladder rather than a hue wheel.
     *
     * <p>The eight colours here before claimed to "remain distinguishable for the most common forms
     * of colour blindness". Measured, three pairs collapse: {@code #C2554D} and {@code #8A6D3B} are
     * deltaE 5.2 apart under deuteranopia, and {@code #B08900} and {@code #C2554D} are 2.9 apart
     * under tritanopia - which is to say indistinguishable.
     *
     * <p>Eight hues cannot survive a dichromatic collapse. Five spread across a 3.9-to-9.1 contrast
     * range give a worst pair of deltaE 18.6 across normal, deuteranopic, protanopic and tritanopic
     * vision, because lightness is a second channel that no deficiency takes away.
     *
     * <p>A board with more than five namespaces reuses a colour, which is the honest trade: the
     * legend's "Namespaces on this board" section lists every one of them by IRI and remains the
     * authority. A colour two vocabularies share is a smaller lie than a colour two people cannot
     * tell apart.
     */
    private static final String[] PALETTE = {
        "#2B7FD4", // blue,   3.88:1 on the canvas
        "#7B3FA0", // violet, 6.46:1
        "#1E7F5C", // green,  4.65:1
        "#B35C00", // orange, 4.44:1
        "#3B4652", // slate,  9.05:1
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
        // Derived from the namespace, not from how many namespaces have been seen. Keying on
        // assignments.size() meant that removing one namespace and adding another handed out a
        // duplicate, and that the same vocabulary got a different colour on a different board -
        // the opposite of what the persistence in this class exists for.
        int hash = 0;
        for (int i = 0; i < namespace.length(); i++) {
            hash = 31 * hash + namespace.charAt(i);
        }
        int start = Math.floorMod(hash, PALETTE.length);
        String colour = PALETTE[start];
        // Probe forward for one nobody on this board is using, so two namespaces share a colour
        // only once all five are spoken for.
        for (int k = 0; k < PALETTE.length; k++) {
            String candidate = PALETTE[(start + k) % PALETTE.length];
            if (!assignments.containsValue(candidate)) {
                colour = candidate;
                break;
            }
        }
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
