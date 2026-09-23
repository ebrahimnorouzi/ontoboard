package de.fizkarlsruhe.ise.ontoboard.odk;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLLogicalAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.RemoveAxiom;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;

/**
 * Retiring a term without breaking everything that used it.
 *
 * <p>Deleting a published term is the one edit a consumer cannot recover from. Their import still
 * resolves, their axioms still parse, and the term they referenced is simply not there any more -
 * no error, no warning, no way to tell it from a term that never existed. Three of the five expert
 * panels raised this independently, and the release comparison added in the same breath now refuses
 * to ship a release that drops one. Which would be an empty gesture if the tool offered no way to
 * retire a term properly.
 *
 * <p>OBO's answer, followed here exactly:
 * <ul>
 *   <li>the term stays, so every reference still resolves;
 *   <li>{@code owl:deprecated true} marks it;
 *   <li>its label gains an {@code obsolete } prefix, so it is obvious in any tool that shows
 *       labels and in any list a curator scans;
 *   <li>its <b>logical</b> axioms go, so it drops out of the hierarchy and stops being inherited
 *       from - an obsolete term that is still somebody's superclass is worse than useless;
 *   <li>{@code IAO:0100001 term replaced by} points at the term to use instead, when there is a
 *       direct replacement, so a consumer can migrate mechanically.
 * </ul>
 *
 * <p>The annotations stay. A definition on an obsolete term is how somebody works out what it used
 * to mean, which is exactly the question they have when they find one.
 *
 * <p>Pure OWL API; no Protege types and no Swing.
 */
public final class Obsoletion {

    /** {@code IAO:0100001 term replaced by} - a direct, mechanical replacement. */
    public static final IRI TERM_REPLACED_BY =
            IRI.create("http://purl.obolibrary.org/obo/IAO_0100001");

    /** {@code oboInOwl:consider} - a suggestion, where there is no exact replacement. */
    public static final IRI CONSIDER =
            IRI.create("http://www.geneontology.org/formats/oboInOwl#consider");

    /** {@code IAO:0000231 has obsolescence reason}. */
    public static final IRI OBSOLESCENCE_REASON =
            IRI.create("http://purl.obolibrary.org/obo/IAO_0000231");

    /** The prefix OBO puts on an obsolete term's label. */
    public static final String LABEL_PREFIX = "obsolete ";

    private Obsoletion() {
    }

    /** Whether this term is already retired. */
    public static boolean isObsolete(OWLOntology ontology, IRI term) {
        if (ontology == null || term == null) {
            return false;
        }
        for (OWLAnnotationAssertionAxiom axiom : ontology.getAnnotationAssertionAxioms(term)) {
            if (OWLRDFVocabulary.OWL_DEPRECATED.getIRI().equals(axiom.getProperty().getIRI())
                    && axiom.getValue() instanceof OWLLiteral
                    && ((OWLLiteral) axiom.getValue()).parseBoolean()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The changes that retire a term.
     *
     * @param replacement the term to use instead, or null
     * @param exact true when {@code replacement} means the same thing and a consumer can swap it
     *     in mechanically ({@code IAO:0100001}); false when it is only a suggestion
     *     ({@code oboInOwl:consider}). The difference matters to anybody migrating: one is safe to
     *     automate and the other needs a person to look.
     * @param reason free text for {@code IAO:0000231}, or null
     */
    public static List<OWLOntologyChange> obsolete(OWLOntology ontology, IRI term,
            IRI replacement, boolean exact, String reason) {
        List<OWLOntologyChange> changes = new ArrayList<OWLOntologyChange>();
        if (ontology == null || term == null || isObsolete(ontology, term)) {
            return changes;
        }
        OWLDataFactory factory = ontology.getOWLOntologyManager().getOWLDataFactory();

        // Declared explicitly, first. Stripping the logical axioms below would otherwise take the
        // class out of the signature altogether - annotations alone do not put it there - and an
        // obsolete term that is not in the signature is indistinguishable from a deleted one,
        // which is the precise failure this whole class exists to prevent. Caught by its own
        // test: the term vanished, and the release comparison read it as removed.
        OWLClass declared = factory.getOWLClass(term);
        if (!ontology.isDeclared(declared)) {
            changes.add(new AddAxiom(ontology, factory.getOWLDeclarationAxiom(declared)));
        }

        changes.add(new AddAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(
                factory.getOWLAnnotationProperty(OWLRDFVocabulary.OWL_DEPRECATED.getIRI()),
                term, factory.getOWLLiteral(true))));

        // The label, prefixed. Done as a replacement rather than an addition, or the term ends up
        // with two labels and every tool that shows one picks arbitrarily.
        for (OWLAnnotationAssertionAxiom axiom : ontology.getAnnotationAssertionAxioms(term)) {
            if (!axiom.getProperty().isLabel() || !(axiom.getValue() instanceof OWLLiteral)) {
                continue;
            }
            String label = ((OWLLiteral) axiom.getValue()).getLiteral();
            if (label.startsWith(LABEL_PREFIX)) {
                continue;
            }
            changes.add(new RemoveAxiom(ontology, axiom));
            changes.add(new AddAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(
                    axiom.getProperty(), term, factory.getOWLLiteral(LABEL_PREFIX + label))));
        }

        // Every logical axiom about it. An obsolete term that is still a superclass is inherited
        // from by terms that are not obsolete, which is worse than leaving it in the hierarchy
        // undeclared - the ontology goes on entailing things through a term nobody should use.
        for (OWLAxiom axiom : logicalAxiomsAbout(ontology, term)) {
            changes.add(new RemoveAxiom(ontology, axiom));
        }

        // Declared before use, for the same reason the class itself is declared above: an
        // undeclared annotation property is an OWL 2 DL violation in its own right. Without this,
        // retiring one term took the whole ontology out of DL - and OntoBoard's own ROBOT >
        // Profile then reported the violation OntoBoard had just introduced. owl:deprecated needs
        // no declaration; it is built in.
        if (replacement != null) {
            IRI pointer = exact ? TERM_REPLACED_BY : CONSIDER;
            declareProperty(changes, ontology, factory, pointer);
            changes.add(new AddAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(
                    factory.getOWLAnnotationProperty(pointer), term, replacement)));
        }
        if (reason != null && !reason.trim().isEmpty()) {
            declareProperty(changes, ontology, factory, OBSOLESCENCE_REASON);
            changes.add(new AddAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(
                    factory.getOWLAnnotationProperty(OBSOLESCENCE_REASON), term,
                    factory.getOWLLiteral(reason.trim()))));
        }
        return changes;
    }

    /** Adds a declaration for an annotation property the ontology does not declare yet. */
    private static void declareProperty(List<OWLOntologyChange> changes, OWLOntology ontology,
            OWLDataFactory factory, IRI property) {
        org.semanticweb.owlapi.model.OWLAnnotationProperty declared =
                factory.getOWLAnnotationProperty(property);
        if (!ontology.isDeclared(declared)) {
            changes.add(new AddAxiom(ontology, factory.getOWLDeclarationAxiom(declared)));
        }
    }

    /**
     * Logical axioms mentioning the term, anywhere in them.
     *
     * <p>Not only the ones where it is the subject: a term that appears in somebody else's
     * restriction is still logically entangled, and leaving that axiom behind means the ontology
     * goes on reasoning through a term nobody should use.
     */
    static Set<OWLAxiom> logicalAxiomsAbout(OWLOntology ontology, IRI term) {
        Set<OWLAxiom> axioms = new LinkedHashSet<OWLAxiom>();
        OWLClass subject = ontology.getOWLOntologyManager().getOWLDataFactory()
                .getOWLClass(term);
        for (OWLLogicalAxiom axiom : ontology.getLogicalAxioms()) {
            if (axiom.getSignature().contains(subject)) {
                axioms.add(axiom);
            }
        }
        return axioms;
    }

    /**
     * Terms whose logical axioms mention this one, so an editor can see what they are about to
     * change.
     *
     * <p>Obsoleting is safe for consumers - the IRI still resolves - but it is not free inside the
     * ontology: anything that was a subclass of this term loses a parent, and the editor should
     * know which terms those are before they press the button, not afterwards.
     */
    public static Set<IRI> termsReferencing(OWLOntology ontology, IRI term) {
        Set<IRI> referencing = new LinkedHashSet<IRI>();
        if (ontology == null || term == null) {
            return referencing;
        }
        OWLClass subject = ontology.getOWLOntologyManager().getOWLDataFactory()
                .getOWLClass(term);
        for (OWLLogicalAxiom axiom : ontology.getLogicalAxioms()) {
            if (!axiom.getSignature().contains(subject)) {
                continue;
            }
            for (OWLEntity entity : axiom.getSignature()) {
                if (entity.isOWLClass() && !entity.getIRI().equals(term)
                        && !entity.asOWLClass().isOWLThing()
                        && !entity.asOWLClass().isOWLNothing()) {
                    referencing.add(entity.getIRI());
                }
            }
        }
        return referencing;
    }

    /**
     * Why this term cannot be obsoleted, or null.
     *
     * <p>Being referenced is not a reason to refuse - it is the ordinary case, and it is exactly
     * what obsoleting handles better than deleting.
     */
    public static String whyNot(OWLOntology ontology, IRI term) {
        if (ontology == null || term == null) {
            return "Select a term first.";
        }
        if (isObsolete(ontology, term)) {
            return "That term is already obsolete.";
        }
        if (!ontology.containsEntityInSignature(term)) {
            return "That term is not in this ontology.";
        }
        return null;
    }
}
