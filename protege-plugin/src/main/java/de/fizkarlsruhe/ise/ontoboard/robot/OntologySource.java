package de.fizkarlsruhe.ise.ontoboard.robot;

import java.io.File;
import java.util.Locale;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Turning what a user typed into an ontology to read from.
 *
 * <p>The field says "a file or a URL" because both are what people actually have: a downloaded
 * {@code chebi.owl} on disk, or the OBO PURL they copied out of the documentation. Making them
 * choose a kind first, or offering only a file chooser, means the PURL case ends with someone
 * downloading a 700MB file by hand.
 *
 * <p>The other job is not downloading something twice. An OBO PURL takes minutes to fetch and
 * Protege may already have the ontology open - as an import of the file being edited, most
 * commonly. Reusing it is the difference between an extraction that returns immediately and one
 * that appears to hang.
 *
 * <p>Pure OWL API; no Protege types and no Swing, so the matching is testable.
 */
public final class OntologySource {

    private OntologySource() {
    }

    /**
     * What was typed, as an IRI to load.
     *
     * @throws RobotException if it is neither a URL nor a file that exists, saying which, because
     *     "could not load ontology" for a mistyped path is a message that helps nobody
     */
    public static IRI toIri(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            throw new RobotException("No ontology was named to take terms from.");
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("ftp://")) {
            return IRI.create(trimmed);
        }
        if (lower.startsWith("file:")) {
            return IRI.create(trimmed);
        }
        File file = new File(trimmed);
        if (!file.isFile()) {
            throw new RobotException("There is no file at " + file.getAbsolutePath()
                    + ". Give a path to an ontology file, or a URL such as "
                    + "http://purl.obolibrary.org/obo/iao.owl");
        }
        return IRI.create(file.toURI());
    }

    /**
     * An ontology the manager already has for this IRI, or null.
     *
     * <p>Matched three ways because the same ontology is known by three names: its ontology IRI
     * ({@code http://purl.obolibrary.org/obo/iao.owl}), its version IRI, and wherever the document
     * was actually read from - which for a project's own import module is a local file whose path
     * looks nothing like the IRI inside it.
     */
    public static OWLOntology findAlreadyLoaded(OWLOntologyManager manager, IRI iri) {
        if (manager == null || iri == null) {
            return null;
        }
        for (OWLOntology ontology : manager.getOntologies()) {
            if (ontology.getOntologyID().getOntologyIRI().isPresent()
                    && iri.equals(ontology.getOntologyID().getOntologyIRI().get())) {
                return ontology;
            }
            if (ontology.getOntologyID().getVersionIRI().isPresent()
                    && iri.equals(ontology.getOntologyID().getVersionIRI().get())) {
                return ontology;
            }
            try {
                if (iri.equals(manager.getOntologyDocumentIRI(ontology))) {
                    return ontology;
                }
            } catch (RuntimeException noDocumentIri) {
                // An ontology the manager has no document IRI for simply cannot match this way.
                continue;
            }
        }
        return null;
    }

    /**
     * The ontology at this IRI, loaded if it is not already.
     *
     * <p>Into the given manager, which for an extraction is deliberately <em>not</em> Protege's:
     * loading a 700MB ontology into the editing manager to take four terms out of it would leave
     * it in the ontology list, in the imports dialog, and in memory for the rest of the session.
     *
     * @throws RobotException with the reason, since "load failed" for an unreachable PURL and for
     *     a malformed file need different responses from a user
     */
    public static OWLOntology load(OWLOntologyManager manager, IRI iri) {
        OWLOntology alreadyOpen = findAlreadyLoaded(manager, iri);
        if (alreadyOpen != null) {
            return alreadyOpen;
        }
        try {
            return manager.loadOntology(iri);
        } catch (LinkageError incompatible) {
            throw new RobotException("Could not load " + iri
                    + " with this Protege's OWL API.", incompatible);
        } catch (Exception failed) {
            throw new RobotException("Could not load " + iri + ": "
                    + (failed.getMessage() == null ? failed.getClass().getSimpleName()
                            : failed.getMessage()), failed);
        }
    }
}
