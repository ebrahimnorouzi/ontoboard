package de.fizkarlsruhe.ise.ontoboard.axiom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;

/**
 * Builds the changes that introduce a new class, individual or object property.
 *
 * <p>Everything here is a pure function over an {@link OWLOntology} and its data factory,
 * returning {@link OWLOntologyChange} objects for the caller to apply through
 * {@code OWLModelManager}. Nothing is mutated and no Protege type is referenced, so the
 * logic is unit-testable without a live editor kit - the reason the interesting decisions
 * (naming, namespace, duplicate handling) live here rather than in the Swing layer.
 */
public final class EntityFactory {

    /** Used only when the ontology is anonymous, which is legal but rare. */
    public static final String FALLBACK_NAMESPACE = "http://www.ontoboard.org/untitled#";

    /** What kind of entity to introduce. */
    public enum Kind {
        CLASS,
        INDIVIDUAL,
        OBJECT_PROPERTY
    }

    private EntityFactory() {
    }

    /**
     * Builds an IRI for {@code localName} inside the ontology's own namespace, so new terms
     * belong to the ontology being edited rather than to some hard-coded vocabulary.
     *
     * @throws IllegalArgumentException if the name is null, blank, or contains characters
     *     that would produce a malformed or ambiguous IRI
     */
    public static IRI iriFor(OWLOntology ontology, String localName) {
        if (localName == null) {
            throw new IllegalArgumentException("entity name must not be null");
        }
        String trimmed = localName.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(
                    "entity name must not be blank - a blank name would mint a bare '#' IRI");
        }
        if (trimmed.indexOf('#') >= 0 || trimmed.indexOf('/') >= 0
                || trimmed.indexOf(' ') >= 0) {
            throw new IllegalArgumentException(
                    "entity name must not contain '#', '/' or a space: '" + trimmed + "'");
        }
        return IRI.create(namespaceOf(ontology) + trimmed);
    }

    /**
     * The namespace new entities are minted into: the ontology IRI plus {@code #}, unless it
     * already ends in a delimiter.
     *
     * <p>{@code getOntologyIRI()} returns a <em>Guava</em> {@code Optional} in OWL API 4, so
     * this uses {@code isPresent()}/{@code get()} rather than any {@code java.util.Optional}
     * method.
     */
    public static String namespaceOf(OWLOntology ontology) {
        com.google.common.base.Optional<IRI> ontologyIri =
                ontology.getOntologyID().getOntologyIRI();
        if (!ontologyIri.isPresent()) {
            return FALLBACK_NAMESPACE;
        }
        String base = ontologyIri.get().toString();
        return base.endsWith("#") || base.endsWith("/") ? base : base + "#";
    }

    /**
     * The declaration needed to introduce {@code iri} as {@code kind}.
     *
     * @return an empty list when the entity is already declared - re-declaring would add a
     *     duplicate axiom and dirty the ontology for no reason
     */
    public static List<OWLOntologyChange> declare(OWLOntology ontology, IRI iri, Kind kind) {
        OWLDataFactory factory = ontology.getOWLOntologyManager().getOWLDataFactory();
        OWLEntity entity = entityFor(factory, iri, kind);
        if (ontology.isDeclared(entity)) {
            return Collections.emptyList();
        }
        List<OWLOntologyChange> changes = new ArrayList<OWLOntologyChange>();
        changes.add(new AddAxiom(ontology, factory.getOWLDeclarationAxiom(entity)));
        return changes;
    }

    /** The entity {@code iri} denotes when read as {@code kind}. Creates no axioms. */
    public static OWLEntity entityFor(OWLDataFactory factory, IRI iri, Kind kind) {
        switch (kind) {
            case INDIVIDUAL:
                return factory.getOWLNamedIndividual(iri);
            case OBJECT_PROPERTY:
                return factory.getOWLObjectProperty(iri);
            case CLASS:
            default:
                return factory.getOWLClass(iri);
        }
    }
}
