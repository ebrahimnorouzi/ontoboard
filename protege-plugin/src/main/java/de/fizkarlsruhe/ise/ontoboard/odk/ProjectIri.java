package de.fizkarlsruhe.ise.ontoboard.odk;

import java.util.Locale;
import org.semanticweb.owlapi.model.IRI;

/**
 * The namespace a project owns, derived from its ontology IRI.
 *
 * <p>Everything a project publishes under its own name - release artefacts, import modules, ID
 * ranges - hangs below one stem, and the stem is the ontology IRI with any {@code .owl} extension
 * removed. For {@code http://purl.obolibrary.org/obo/mwo.owl} that is
 * {@code http://purl.obolibrary.org/obo/mwo}, and the release of 30 August 2026 is
 * {@code http://purl.obolibrary.org/obo/mwo/releases/2026-08-30/mwo.owl}. That is the OBO pattern,
 * and it is worth following exactly: a consumer reads the date out of the IRI without fetching
 * anything, and the PURL layer resolves it to a file that will never change again.
 *
 * <p>This class exists because three places computed the stem by cutting at the last {@code /},
 * which removes the project itself rather than the extension - and each got a different wrong
 * answer:
 *
 * <ul>
 *   <li>{@code Release.versionIri} turned {@code .../obo/mwo.owl} into
 *       {@code .../obo/releases/<date>/mwo.owl}, dropping the ontology id, so every OBO ontology
 *       released with this plugin collided in one {@code obo/releases/} space.
 *   <li>{@code TermExtract.moduleIriIn} turned it into {@code .../obo/imports/iao_import.owl},
 *       publishing a module into OBO's shared root - which its own javadoc says the naming exists
 *       to avoid, and which two projects extracting from the same source would then share.
 *   <li>The generated Makefile did not strip at all, minting
 *       {@code .../obo/mwo.owl/releases/<date>/mwo.owl}.
 * </ul>
 *
 * <p>So one release could be identified three ways depending on how it was built, and a version
 * IRI is a permanent citation handle - it is baked into the artefact and cannot be taken back once
 * anybody has imported it. One helper, one answer.
 */
public final class ProjectIri {

    private ProjectIri() {
    }

    /**
     * The project's namespace: its IRI without a trailing {@code .owl}.
     *
     * <p>Only the extension is removed. A path segment is the project's identity and removing it
     * yields a namespace belonging to whatever sits above - which for an OBO ontology is OBO
     * itself.
     *
     * @return the stem, or null when there is no IRI to derive one from
     */
    public static String stemOf(String ontologyIri) {
        if (ontologyIri == null || ontologyIri.trim().isEmpty()) {
            return null;
        }
        String iri = ontologyIri.trim();
        while (iri.endsWith("/") || iri.endsWith("#")) {
            iri = iri.substring(0, iri.length() - 1);
        }
        return iri.toLowerCase(Locale.ROOT).endsWith(".owl")
                ? iri.substring(0, iri.length() - ".owl".length()) : iri;
    }

    /** {@link #stemOf(String)} for an {@link IRI}. */
    public static String stemOf(IRI ontologyIri) {
        return ontologyIri == null ? null : stemOf(ontologyIri.toString());
    }

    /**
     * The last path segment of the stem - the project's short name.
     *
     * <p>Used as the file name inside a release directory, so
     * {@code .../mwo/releases/2026-08-30/mwo.owl} rather than a generic one: an ontology saved as
     * {@code ontology.owl} in every release directory is one nobody can tell apart on disk.
     */
    public static String nameOf(String ontologyIri) {
        String stem = stemOf(ontologyIri);
        if (stem == null) {
            return null;
        }
        int lastSlash = stem.lastIndexOf('/');
        String name = lastSlash >= 0 && lastSlash < stem.length() - 1
                ? stem.substring(lastSlash + 1) : stem;
        return name.isEmpty() ? null : name;
    }

    /**
     * The version IRI for a dated release: {@code <stem>/releases/<date>/<name>.owl}.
     *
     * @return null when no IRI was given; the caller decides whether that is an error
     */
    public static String releaseIri(String ontologyIri, String date) {
        String stem = stemOf(ontologyIri);
        String name = nameOf(ontologyIri);
        if (stem == null || name == null) {
            return null;
        }
        return stem + "/releases/" + date + "/" + name + ".owl";
    }
}
