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

    // ------------------------------------------------------------------ the name typed

    /**
     * What a name typed for a new term comes to, before anything is made (F7): the same for a
     * class or an individual on the board and for a property in Create relation.
     */
    public static final class NameCheck {
        /** Refused, asked about, or let through. */
        public enum Verdict {
            REFUSED,
            ASK,
            GOES
        }

        private final Verdict verdict;
        private final String name;
        private final String sentence;

        private NameCheck(Verdict verdict, String name, String sentence) {
            this.verdict = verdict;
            this.name = name;
            this.sentence = sentence;
        }

        public Verdict verdict() {
            return verdict;
        }

        /** The name, trimmed; empty when it is refused. */
        public String name() {
            return name;
        }

        /** What is said - the refusal, or the question; null when the name goes. */
        public String sentence() {
            return sentence;
        }
    }

    /**
     * A name typed for a new {@code what} ("class", "individual", "property"): blank is refused
     * with a sentence; a name that another term here or in an import has as its label is asked
     * about - two terms may be meant to read alike, but nothing else says they now do, until
     * a build reports duplicate_label; anything else goes, trimmed.
     */
    public static NameCheck checkName(OWLOntology ontology, String typed, String what) {
        String name = typed == null ? "" : typed.trim();
        if (name.isEmpty()) {
            return new NameCheck(NameCheck.Verdict.REFUSED, "", "Give the new " + what
                    + " a name.");
        }
        String twin = ontology == null ? null : termNamed(ontology, name);
        if (twin != null) {
            return new NameCheck(NameCheck.Verdict.ASK, name, "\"" + name
                    + "\" is already the name of " + twin + ".\nMake another " + what
                    + " with the same name?");
        }
        return new NameCheck(NameCheck.Verdict.GOES, name, null);
    }

    /** The short name of a term here or in an import whose label is {@code name}, or null. */
    public static String termNamed(OWLOntology ontology, String name) {
        String wanted = name.trim();
        for (OWLOntology part : ontology.getImportsClosure()) {
            for (org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom axiom
                    : part.getAxioms(org.semanticweb.owlapi.model.AxiomType.ANNOTATION_ASSERTION)) {
                if (axiom.getProperty().isLabel() && axiom.getValue().asLiteral().isPresent()
                        && axiom.getValue().asLiteral().get().getLiteral().trim()
                                .equalsIgnoreCase(wanted)
                        && axiom.getSubject() instanceof IRI) {
                    return ((IRI) axiom.getSubject()).getShortForm();
                }
            }
        }
        return null;
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
