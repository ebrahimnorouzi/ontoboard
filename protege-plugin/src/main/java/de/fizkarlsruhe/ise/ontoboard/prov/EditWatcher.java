package de.fizkarlsruhe.ise.ontoboard.prov;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLAxiomChange;
import org.semanticweb.owlapi.model.OWLClassAssertionAxiom;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLDataPropertyDomainAxiom;
import org.semanticweb.owlapi.model.OWLDataPropertyRangeAxiom;
import org.semanticweb.owlapi.model.OWLDeclarationAxiom;
import org.semanticweb.owlapi.model.OWLDisjointClassesAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLEquivalentClassesAxiom;
import org.semanticweb.owlapi.model.OWLIndividual;
import org.semanticweb.owlapi.model.OWLInverseObjectPropertiesAxiom;
import org.semanticweb.owlapi.model.OWLObjectPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLObjectPropertyCharacteristicAxiom;
import org.semanticweb.owlapi.model.OWLObjectPropertyDomainAxiom;
import org.semanticweb.owlapi.model.OWLObjectPropertyExpression;
import org.semanticweb.owlapi.model.OWLObjectPropertyRangeAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.model.OWLSubDataPropertyOfAxiom;
import org.semanticweb.owlapi.model.OWLSubObjectPropertyOfAxiom;
import org.semanticweb.owlapi.model.OWLSubPropertyChainOfAxiom;

/**
 * Provenance for edits made anywhere in Protege, not only on the canvas.
 *
 * <p>A gap this plugin created. {@link Provenance} is stamped where the canvas makes the edit - so
 * a term dragged on the canvas records who made it and when, and the same term re-parented in
 * Protege's class hierarchy, or given a restriction in the Manchester syntax editor, records
 * nothing. Which is most editing. The result is worse than no provenance: a reader sees dates on
 * some terms and none on others and reasonably concludes the undated ones were never touched, when
 * what actually happened is that somebody used a different view.
 *
 * <p>This works out, from a batch of changes Protege has <em>already applied</em>, which terms an
 * edit was about. That is the whole problem, and it is not the same as the axioms' signature.
 * Adding {@code SubClassOf(Dog, Mammal)} is an edit to {@code Dog}; stamping {@code Mammal} too
 * would mark half the ontology modified every time anybody re-parented anything, and by the end of
 * a week's work every term would carry today's date and the provenance would say nothing at all.
 * So the subject of the axiom is stamped and the rest of it is not.
 *
 * <p>Three rules keep it from stamping things it should not:
 *
 * <ul>
 *   <li><b>Provenance is not an edit.</b> A change that only writes {@code dcterms:contributor},
 *       {@code dcterms:created} or {@code dcterms:date} is ignored. Without this the stamp would
 *       trigger a stamp and the ontology would fill with annotations forever.
 *   <li><b>Vocabulary is not a term.</b> Declaring an annotation property is how an ontology says
 *       it uses {@code dcterms:contributor} or {@code IAO:0000116}, and this plugin adds those
 *       declarations itself alongside every stamp and every note. Counting them as term creation
 *       would have it recording itself as the author of {@code dcterms:contributor}.
 *   <li><b>A term the ontology does not declare is not ours to stamp.</b> This covers deletion,
 *       where the batch removes everything about a term including its declaration - resurrecting
 *       it with a contributor annotation would be a bug with a long tail - and it covers
 *       annotating an imported term, where recording yourself as a contributor to somebody else's
 *       term would be a claim you are not entitled to make.
 * </ul>
 *
 * <p>Pure OWL API; no Protege types and no Swing. The caller owns the clock and decides whether
 * stamping happens at all - see {@link ProvenanceSettings}.
 */
public final class EditWatcher {

    private EditWatcher() {
    }

    /**
     * The provenance changes {@code edits} call for, or an empty list.
     *
     * <p>Call after the edits have been applied, which is when Protege's change listener fires.
     * "Declared" is therefore read against the state the edits produced: a term created in this
     * batch is declared, and a term deleted in it is not.
     *
     * @param ontology the one ontology to stamp; changes to any other are ignored, so editing an
     *     import does not write provenance into the file you have open
     * @param isoDate {@code YYYY-MM-DD}
     */
    public static List<OWLOntologyChange> stampsFor(OWLOntology ontology,
            List<? extends OWLOntologyChange> edits, String agent, String isoDate) {
        List<OWLOntologyChange> stamps = new ArrayList<OWLOntologyChange>();
        if (ontology == null || edits == null || agent == null || agent.trim().isEmpty()) {
            return stamps;
        }

        Set<IRI> touched = new LinkedHashSet<IRI>();
        Set<IRI> created = new LinkedHashSet<IRI>();
        for (OWLOntologyChange edit : edits) {
            if (edit == null || !edit.isAxiomChange() || !ontology.equals(edit.getOntology())) {
                continue;
            }
            OWLAxiom axiom = edit.getAxiom();
            if (isProvenance(axiom)) {
                continue;
            }
            for (IRI subject : subjectsOf(axiom)) {
                touched.add(subject);
                if (edit.isAddAxiom() && axiom instanceof OWLDeclarationAxiom) {
                    created.add(subject);
                }
            }
        }

        for (IRI term : touched) {
            if (!isDeclaredIn(ontology, term)) {
                continue;
            }
            // A term declared in this batch is new, unless the ontology already carried
            // provenance for it - which happens when a declaration is re-added by an undo, and
            // in that case the term is not new and dcterms:created must not be rewritten.
            boolean isNew = created.contains(term)
                    && Provenance.contributorsOf(ontology, term).isEmpty();
            if (isNew) {
                stamps.addAll(Provenance.stampNew(ontology, term, agent, isoDate));
            } else if (!isoDate.equals(Provenance.createdOn(ontology, term))) {
                stamps.addAll(Provenance.stampModified(ontology, term, agent, isoDate));
            }
            // A term created today and edited today gets no modification date. "Last modified" on
            // the day of creation says nothing the creation date does not, and writing it anyway
            // would add a second line to the diff for every new term - including the ones this
            // plugin's own canvas path has already stamped, which is how the two paths avoid
            // fighting each other over the same term.
        }
        if (!stamps.isEmpty()) {
            // Prepended, so the properties are declared before anything asserts with them - and
            // so a project that runs ROBOT report over its own output does not fail on an
            // undeclared annotation property this plugin introduced.
            stamps.addAll(0, Provenance.declareProperties(ontology));
        }
        return stamps;
    }

    /**
     * Whether this axiom is itself a provenance stamp.
     *
     * <p>The termination condition. Applying a stamp fires the change listener again; if a stamp
     * counted as an edit it would call for a stamp, and the ontology would grow annotations for as
     * long as the application stayed open.
     */
    public static boolean isProvenance(OWLAxiom axiom) {
        if (!(axiom instanceof OWLAnnotationAssertionAxiom)) {
            return false;
        }
        IRI property = ((OWLAnnotationAssertionAxiom) axiom).getProperty().getIRI();
        return Provenance.CONTRIBUTOR.equals(property)
                || Provenance.CREATED.equals(property)
                || Provenance.MODIFIED.equals(property);
    }

    /**
     * The terms an axiom is <em>about</em>, which is not its signature.
     *
     * <p>Only the subject side, for the reason in the class comment. Where an axiom is genuinely
     * symmetric - equivalence, disjointness, inverse properties - every named side is a subject,
     * because saying "A is equivalent to B" is an edit to both of them.
     *
     * <p>An axiom whose subject is anonymous contributes nothing: a general class inclusion is
     * about an expression rather than a term, and there is nowhere to put the annotation. So is an
     * axiom type not listed here - a swap, an SWRL rule, a datatype definition. Those go
     * unrecorded, which is honest: this returns what it can attribute, not a guess.
     */
    public static Set<IRI> subjectsOf(OWLAxiom axiom) {
        Set<IRI> subjects = new LinkedHashSet<IRI>();
        if (axiom == null) {
            return subjects;
        }
        if (axiom instanceof OWLDeclarationAxiom) {
            OWLEntity declared = ((OWLDeclarationAxiom) axiom).getEntity();
            // An annotation property declaration is vocabulary, not a term. Ontologies declare
            // dcterms:contributor and IAO:0000116 so their files validate, and this plugin adds
            // those declarations itself alongside every stamp and every note - so counting them
            // as term creation would have the plugin recording itself as the author of
            // dcterms:contributor, and stamping its own stamps. That is what the test caught.
            if (!declared.isOWLAnnotationProperty()) {
                subjects.add(declared.getIRI());
            }
        } else if (axiom instanceof OWLAnnotationAssertionAxiom) {
            Object subject = ((OWLAnnotationAssertionAxiom) axiom).getSubject();
            if (subject instanceof IRI) {
                subjects.add((IRI) subject);
            }
        } else if (axiom instanceof OWLSubClassOfAxiom) {
            addNamed(subjects, ((OWLSubClassOfAxiom) axiom).getSubClass());
        } else if (axiom instanceof OWLEquivalentClassesAxiom) {
            for (OWLClassExpression side
                    : ((OWLEquivalentClassesAxiom) axiom).getClassExpressions()) {
                addNamed(subjects, side);
            }
        } else if (axiom instanceof OWLDisjointClassesAxiom) {
            for (OWLClassExpression side
                    : ((OWLDisjointClassesAxiom) axiom).getClassExpressions()) {
                addNamed(subjects, side);
            }
        } else if (axiom instanceof OWLSubObjectPropertyOfAxiom) {
            addNamed(subjects, ((OWLSubObjectPropertyOfAxiom) axiom).getSubProperty());
        } else if (axiom instanceof OWLSubPropertyChainOfAxiom) {
            addNamed(subjects, ((OWLSubPropertyChainOfAxiom) axiom).getSuperProperty());
        } else if (axiom instanceof OWLSubDataPropertyOfAxiom) {
            addEntity(subjects,
                    ((OWLSubDataPropertyOfAxiom) axiom).getSubProperty().asOWLDataProperty());
        } else if (axiom instanceof OWLInverseObjectPropertiesAxiom) {
            OWLInverseObjectPropertiesAxiom inverse = (OWLInverseObjectPropertiesAxiom) axiom;
            addNamed(subjects, inverse.getFirstProperty());
            addNamed(subjects, inverse.getSecondProperty());
        } else if (axiom instanceof OWLObjectPropertyDomainAxiom) {
            addNamed(subjects, ((OWLObjectPropertyDomainAxiom) axiom).getProperty());
        } else if (axiom instanceof OWLObjectPropertyRangeAxiom) {
            addNamed(subjects, ((OWLObjectPropertyRangeAxiom) axiom).getProperty());
        } else if (axiom instanceof OWLObjectPropertyCharacteristicAxiom) {
            addNamed(subjects, ((OWLObjectPropertyCharacteristicAxiom) axiom).getProperty());
        } else if (axiom instanceof OWLDataPropertyDomainAxiom) {
            addEntity(subjects,
                    ((OWLDataPropertyDomainAxiom) axiom).getProperty().asOWLDataProperty());
        } else if (axiom instanceof OWLDataPropertyRangeAxiom) {
            addEntity(subjects,
                    ((OWLDataPropertyRangeAxiom) axiom).getProperty().asOWLDataProperty());
        } else if (axiom instanceof OWLClassAssertionAxiom) {
            addIndividual(subjects, ((OWLClassAssertionAxiom) axiom).getIndividual());
        } else if (axiom instanceof OWLObjectPropertyAssertionAxiom) {
            addIndividual(subjects, ((OWLObjectPropertyAssertionAxiom) axiom).getSubject());
        } else if (axiom instanceof OWLDataPropertyAssertionAxiom) {
            addIndividual(subjects, ((OWLDataPropertyAssertionAxiom) axiom).getSubject());
        }
        return subjects;
    }

    /**
     * Whether the ontology declares this term.
     *
     * <p>The whole point of the check is that a deleted term is not declared any more, so the
     * imports closure is deliberately not consulted: a term that only its import declares is not
     * one this ontology may claim a contributor for.
     */
    private static boolean isDeclaredIn(OWLOntology ontology, IRI term) {
        for (OWLEntity entity : ontology.getEntitiesInSignature(term)) {
            if (!ontology.getDeclarationAxioms(entity).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static void addNamed(Set<IRI> subjects, OWLClassExpression expression) {
        if (expression != null && !expression.isAnonymous()) {
            subjects.add(expression.asOWLClass().getIRI());
        }
    }

    private static void addNamed(Set<IRI> subjects, OWLObjectPropertyExpression expression) {
        if (expression != null && !expression.isAnonymous()) {
            subjects.add(expression.asOWLObjectProperty().getIRI());
        }
    }

    private static void addIndividual(Set<IRI> subjects, OWLIndividual individual) {
        if (individual != null && individual.isNamed()) {
            subjects.add(individual.asOWLNamedIndividual().getIRI());
        }
    }

    private static void addEntity(Set<IRI> subjects, OWLEntity entity) {
        if (entity != null) {
            subjects.add(entity.getIRI());
        }
    }

    /**
     * Whether a batch of changes contains anything worth stamping at all.
     *
     * <p>Cheap enough to run on every change event, which is the point: the listener fires
     * constantly and most of what it sees - a stamp, a change to another ontology - calls for
     * nothing.
     */
    public static boolean isWorthStamping(OWLOntology ontology,
            List<? extends OWLOntologyChange> edits) {
        if (ontology == null || edits == null) {
            return false;
        }
        for (OWLOntologyChange edit : edits) {
            if (edit instanceof OWLAxiomChange && ontology.equals(edit.getOntology())
                    && !isProvenance(edit.getAxiom())
                    && !subjectsOf(edit.getAxiom()).isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
