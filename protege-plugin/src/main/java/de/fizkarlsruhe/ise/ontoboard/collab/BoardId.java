package de.fizkarlsruhe.ise.ontoboard.collab;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * A board's name, derived from the ontology it is for.
 *
 * <p>The board id was free text in a dialog and nothing connected it to anything. Two people could
 * type the same id while editing unrelated ontologies, join the same session, and watch each
 * other's axioms arrive into the wrong file - each of them seeing a class appear that belongs to
 * somebody else's project, with no error anywhere. The reverse was as easy: a typo in the id made
 * a second, empty board rather than a failure, so two collaborators sat in separate sessions
 * wondering why the other had gone quiet.
 *
 * <p>Deriving the id from the ontology IRI closes both by construction. Two people editing the
 * same ontology compute the same id without agreeing on anything; two people editing different
 * ontologies compute different ids and never meet. Nobody has to be told a board name, which also
 * removes the step where it gets mistyped.
 *
 * <p>The id keeps a readable stem so a person can tell which project a session belongs to, and a
 * short digest of the whole IRI so that two ontologies whose names happen to end the same way -
 * {@code purl.obolibrary.org/obo/mwo.owl} and {@code example.org/private/mwo.owl} - are never the
 * same board. The stem alone would be a collision waiting to happen between an OBO ontology and
 * somebody's local copy of it.
 *
 * <p>No Protege types and no Swing.
 */
public final class BoardId {

    /**
     * How much of the digest to keep.
     *
     * <p>Six hex characters is 24 bits. These are not secrets and not adversarial - the question
     * is only whether two ontologies a person happens to work on collide, and at that scale 24
     * bits is far more than enough while keeping the id short enough to read out loud.
     */
    private static final int DIGEST_CHARS = 6;

    private BoardId() {
    }

    /**
     * The board id for an ontology.
     *
     * @param ontologyIri the ontology's own IRI, not its file location - a file path differs
     *     between two people editing the same ontology, which is exactly when they must agree
     * @return a stable id, or empty when there is no IRI to derive one from
     */
    public static String forOntology(String ontologyIri) {
        String iri = ontologyIri == null ? "" : ontologyIri.trim();
        if (iri.isEmpty()) {
            return "";
        }
        return stemOf(iri) + "-" + digestOf(iri);
    }

    /**
     * Whether {@code boardId} is the one this ontology derives.
     *
     * <p>Used to notice that the open ontology is not the one the board was set up for - which
     * happens whenever somebody switches ontology and forgets the board id follows the project,
     * not the window.
     */
    public static boolean matches(String boardId, String ontologyIri) {
        String derived = forOntology(ontologyIri);
        return !derived.isEmpty() && derived.equals(boardId == null ? "" : boardId.trim());
    }

    /**
     * Why the board id and the open ontology do not belong together, or null when they do.
     *
     * <p>Deliberately not phrased as an error. A deliberate override is legitimate - two people
     * may want one board across a pair of related files, and somebody may be joining a session
     * arranged by name - so this reports what it sees and leaves the decision alone. What it will
     * not do is stay quiet, because the failure it describes is invisible until somebody else's
     * class turns up in your ontology.
     */
    public static String mismatchWarning(String boardId, String ontologyIri) {
        String trimmed = boardId == null ? "" : boardId.trim();
        if (trimmed.isEmpty() || matches(trimmed, ontologyIri)) {
            return null;
        }
        String derived = forOntology(ontologyIri);
        if (derived.isEmpty()) {
            return "This ontology has no IRI of its own, so nothing can check that the board '"
                    + trimmed + "' belongs to it. Give it an IRI in the ontology header and "
                    + "everyone editing it will reach the same board without being told its name.";
        }
        return "The board '" + trimmed + "' is not the one this ontology derives (" + derived
                + "). That is fine if you meant it - but if the other editors are on the derived "
                + "board you will not see each other, and if they are on this one editing a "
                + "different ontology their changes will land in yours.";
    }

    /**
     * The readable part: the last meaningful segment of the IRI.
     *
     * <p>Meaningful rather than merely last, because an OBO release IRI ends in the same file name
     * as the ontology it releases and a trailing slash ends in nothing at all.
     */
    private static String stemOf(String iri) {
        String working = iri;
        int fragment = working.indexOf('#');
        if (fragment > 0) {
            working = working.substring(0, fragment);
        }
        while (working.endsWith("/")) {
            working = working.substring(0, working.length() - 1);
        }
        int lastSlash = working.lastIndexOf('/');
        String name = lastSlash >= 0 && lastSlash < working.length() - 1
                ? working.substring(lastSlash + 1) : working;
        for (String extension : new String[] {".owl", ".obo", ".ttl", ".rdf", ".ofn", ".omn"}) {
            if (name.toLowerCase(Locale.ROOT).endsWith(extension)) {
                name = name.substring(0, name.length() - extension.length());
                break;
            }
        }
        String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) {
            return "ontology";
        }
        // Long enough to recognise, short enough to read out. The digest carries the identity.
        return slug.length() > 24 ? slug.substring(0, 24).replaceAll("-+$", "") : slug;
    }

    /**
     * A short digest of the whole IRI.
     *
     * <p>SHA-256 rather than {@code hashCode}, because {@code String.hashCode} is only specified
     * to be stable within a run for some implementations, and a board id that changed between
     * Java versions would split a team without warning.
     */
    private static String digestOf(String iri) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(iri.getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < hashed.length && hex.length() < DIGEST_CHARS; i++) {
                hex.append(String.format("%02x", hashed[i]));
            }
            return hex.substring(0, DIGEST_CHARS);
        } catch (NoSuchAlgorithmException | UnsupportedEncodingException impossible) {
            // Both are required of every Java runtime. If one is genuinely missing, a board id
            // that is merely ugly beats one that throws while somebody is trying to collaborate.
            return "000000";
        }
    }
}
