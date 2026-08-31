package de.fizkarlsruhe.ise.ontoboard.prov;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAnnotationValue;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.RemoveAxiom;

/**
 * Records who added a term and when, the way OBO ontologies actually do it.
 *
 * <p>The properties were chosen from evidence rather than intuition, and the intuitive answer was
 * wrong. {@code dcterms:creator} is what one reaches for and it is effectively dead in OBO
 * term-level practice: zero uses across released {@code obi.owl}, {@code pato.owl} and
 * {@code omo.owl}, declaration-only in {@code ro.owl}, and not declared in the OBO Metadata
 * Ontology at all. {@code oboInOwl:created_by} is a legacy carry-over from the OBO flat-file
 * format. What released OBO ontologies and the OBO Academy's guidance actually use is:
 *
 * <ul>
 *   <li>{@code dcterms:contributor} for the person, with an <b>ORCID IRI</b> as the value
 *   <li>{@code dcterms:created} for when the term was introduced
 *   <li>{@code dcterms:date} for when it was last changed
 * </ul>
 *
 * <p><b>Dates are {@code xsd:date}, not {@code xsd:dateTime}.</b> Both appear in the wild -
 * {@code ro.owl} uses dateTime, {@code obi.owl} uses date - and date is the better of the two
 * here. {@code dcterms:date} is rewritten on every edit, so second precision would put a
 * one-line diff in the ontology every time anybody touched a term, and would record what time of
 * day each contributor works. Day precision collapses a day's edits to a single change.
 *
 * <p>Everything takes the date as a parameter rather than reading a clock, so the output is
 * reproducible and testable. Nothing here applies anything: these are
 * {@link OWLOntologyChange} objects for a caller to put through {@code OWLModelManager}.
 */
public final class Provenance {

    public static final IRI CONTRIBUTOR = IRI.create("http://purl.org/dc/terms/contributor");
    public static final IRI CREATED = IRI.create("http://purl.org/dc/terms/created");
    public static final IRI MODIFIED = IRI.create("http://purl.org/dc/terms/date");

    /** Recognised so an ORCID is written as an IRI rather than as a string. */
    private static final String ORCID_PREFIX = "https://orcid.org/";

    private Provenance() {
    }

    /**
     * Whether this ontology already records term-level provenance.
     *
     * <p>Used to decide the default. Stamping an ontology that has never carried provenance
     * introduces a convention its maintainers did not choose, on every term anyone adds, and it
     * would show up as unexplained churn in their next diff. So the plugin follows what the
     * ontology already does, and only a deliberate setting overrides that.
     */
    public static boolean isUsedIn(OWLOntology ontology) {
        if (ontology == null) {
            return false;
        }
        for (OWLAnnotationAssertionAxiom axiom
                : ontology.getAxioms(AxiomType.ANNOTATION_ASSERTION)) {
            IRI property = axiom.getProperty().getIRI();
            if (CONTRIBUTOR.equals(property) || CREATED.equals(property)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The annotations recording that {@code agent} created something on {@code isoDate}.
     *
     * <p>Shared by the entity and axiom paths so a term and the axioms introducing it carry
     * identical provenance; two subtly different stamps for one action would be worse than none.
     */
    public static Set<OWLAnnotation> creationAnnotations(OWLDataFactory factory, String agent,
            String isoDate) {
        Set<OWLAnnotation> annotations = new LinkedHashSet<OWLAnnotation>();
        if (factory == null || agent == null || agent.trim().isEmpty()) {
            return annotations;
        }
        annotations.add(factory.getOWLAnnotation(property(factory, CONTRIBUTOR),
                agentValue(factory, agent)));
        if (isoDate != null && !isoDate.trim().isEmpty()) {
            annotations.add(factory.getOWLAnnotation(property(factory, CREATED),
                    date(factory, isoDate)));
        }
        return annotations;
    }

    /**
     * Stamps a newly created entity.
     *
     * @param isoDate {@code YYYY-MM-DD}; the caller owns the clock
     * @return changes to apply, empty when there is no agent to record
     */
    public static List<OWLOntologyChange> stampNew(OWLOntology ontology, IRI entity, String agent,
            String isoDate) {
        List<OWLOntologyChange> changes = new ArrayList<OWLOntologyChange>();
        if (ontology == null || entity == null || agent == null || agent.trim().isEmpty()) {
            return changes;
        }
        OWLDataFactory factory = ontology.getOWLOntologyManager().getOWLDataFactory();
        changes.add(new AddAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(
                property(factory, CONTRIBUTOR), entity, agentValue(factory, agent))));
        if (isoDate != null && !isoDate.trim().isEmpty()) {
            changes.add(new AddAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(
                    property(factory, CREATED), entity, date(factory, isoDate))));
        }
        return changes;
    }

    /**
     * Records that {@code entity} changed on {@code isoDate}, replacing any previous date.
     *
     * <p>Replaced rather than added: {@code dcterms:date} means "last modified", so accumulating
     * one per edit would turn a single-valued fact into a growing list and make the real answer
     * unfindable. Contributors accumulate - several people genuinely do contribute to one term -
     * and a contributor already recorded is not added twice.
     */
    public static List<OWLOntologyChange> stampModified(OWLOntology ontology, IRI entity,
            String agent, String isoDate) {
        List<OWLOntologyChange> changes = new ArrayList<OWLOntologyChange>();
        if (ontology == null || entity == null) {
            return changes;
        }
        OWLDataFactory factory = ontology.getOWLOntologyManager().getOWLDataFactory();

        if (isoDate != null && !isoDate.trim().isEmpty()) {
            OWLAnnotationValue newDate = date(factory, isoDate);
            boolean alreadyCorrect = false;
            for (OWLAnnotationAssertionAxiom existing
                    : ontology.getAnnotationAssertionAxioms(entity)) {
                if (MODIFIED.equals(existing.getProperty().getIRI())) {
                    if (existing.getValue().equals(newDate)) {
                        // Editing twice in one day must not produce a change at all, or the
                        // ontology is dirtied for nothing and the diff says something happened
                        // when nothing did.
                        alreadyCorrect = true;
                    } else {
                        changes.add(new RemoveAxiom(ontology, existing));
                    }
                }
            }
            if (!alreadyCorrect) {
                changes.add(new AddAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(
                        property(factory, MODIFIED), entity, newDate)));
            }
        }

        if (agent != null && !agent.trim().isEmpty()) {
            OWLAnnotationValue who = agentValue(factory, agent);
            boolean known = false;
            for (OWLAnnotationAssertionAxiom existing
                    : ontology.getAnnotationAssertionAxioms(entity)) {
                if (CONTRIBUTOR.equals(existing.getProperty().getIRI())
                        && existing.getValue().equals(who)) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                changes.add(new AddAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(
                        property(factory, CONTRIBUTOR), entity, who)));
            }
        }
        return changes;
    }

    /**
     * Declarations for the annotation properties, so a stamped ontology declares what it uses.
     *
     * <p>{@code ROBOT report} flags an undeclared annotation property, so omitting these would
     * make every stamped project fail its own quality check.
     */
    public static List<OWLOntologyChange> declareProperties(OWLOntology ontology) {
        List<OWLOntologyChange> changes = new ArrayList<OWLOntologyChange>();
        if (ontology == null) {
            return changes;
        }
        OWLDataFactory factory = ontology.getOWLOntologyManager().getOWLDataFactory();
        for (IRI iri : new IRI[] {CONTRIBUTOR, CREATED, MODIFIED}) {
            OWLAnnotationProperty declared = property(factory, iri);
            if (!ontology.isDeclared(declared)) {
                changes.add(new AddAxiom(ontology,
                        factory.getOWLDeclarationAxiom(declared)));
            }
        }
        return changes;
    }

    /**
     * The value to record for an agent.
     *
     * <p>An ORCID becomes an IRI, which is what released OBO ontologies carry and what makes the
     * attribution resolvable and unambiguous - two people can share a name, but not an ORCID. A
     * plain name is recorded as a literal rather than refused, because insisting on an ORCID
     * would leave anyone without one unable to record provenance at all.
     */
    static OWLAnnotationValue agentValue(OWLDataFactory factory, String agent) {
        String trimmed = agent.trim();
        String orcid = normaliseOrcid(trimmed);
        return orcid != null ? IRI.create(orcid) : factory.getOWLLiteral(trimmed);
    }

    /**
     * The canonical ORCID IRI for {@code value}, or null when it is not an ORCID.
     *
     * <p>Accepts the bare digits, the http form and the https form, because those are the three
     * ways people paste one, and records the https form because that is what OBO files use.
     */
    static String normaliseOrcid(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        String digits = trimmed;
        for (String prefix : new String[] {"https://orcid.org/", "http://orcid.org/",
            "orcid.org/"}) {
            if (digits.toLowerCase().startsWith(prefix)) {
                digits = digits.substring(prefix.length());
                break;
            }
        }
        // Four groups of four, the last of which may end in X.
        if (digits.matches("\\d{4}-\\d{4}-\\d{4}-\\d{3}[\\dXx]")) {
            return ORCID_PREFIX + digits.toUpperCase();
        }
        return null;
    }

    private static OWLAnnotationProperty property(OWLDataFactory factory, IRI iri) {
        return factory.getOWLAnnotationProperty(iri);
    }

    /**
     * A date literal.
     *
     * <p>Built from an explicit datatype IRI because {@code OWL2Datatype} has no {@code XSD_DATE}:
     * {@code xsd:date} is deliberately absent from OWL 2's normative datatype map. That restriction
     * governs data ranges in <em>logical</em> axioms, and these are annotation assertions, which
     * OWL 2 does not interpret logically at all - so the datatype is free, and {@code obi.owl}
     * carries exactly this form. Anyone tempted to "fix" this to {@code XSD_DATE_TIME} should know
     * that it would add second precision to a value rewritten on every edit.
     */
    private static OWLAnnotationValue date(OWLDataFactory factory, String isoDate) {
        return factory.getOWLLiteral(isoDate.trim(),
                factory.getOWLDatatype(IRI.create("http://www.w3.org/2001/XMLSchema#date")));
    }

    /**
     * Today, as {@code YYYY-MM-DD}.
     *
     * <p>Here rather than in each caller because three of them had rolled their own and one of
     * them rolled it wrong - it passed a full ISO timestamp to {@link #stampModified}, which
     * builds an {@code xsd:date} literal, so the ontology got
     * {@code "2026-08-31T09:14:02Z"^^xsd:date}: a literal outside its own datatype's value space,
     * which a strict parser is entitled to reject and a lax one silently keeps.
     *
     * <p>Every method that writes a date still takes it as a parameter. This is for callers that
     * genuinely mean "now"; the ones under test pass their own so the output is reproducible.
     */
    public static String today() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)
                .format(new java.util.Date());
    }

    /**
     * The creation date recorded for an entity, or empty.
     *
     * <p>Used to decide that a term made today does not also need a "last modified today". The two
     * dates together would say nothing the creation date does not already say, and would put a
     * second line in the diff for every new term.
     */
    public static String createdOn(OWLOntology ontology, IRI entity) {
        if (ontology == null || entity == null) {
            return "";
        }
        for (OWLAnnotationAssertionAxiom axiom : ontology.getAnnotationAssertionAxioms(entity)) {
            if (CREATED.equals(axiom.getProperty().getIRI())
                    && axiom.getValue() instanceof org.semanticweb.owlapi.model.OWLLiteral) {
                return ((org.semanticweb.owlapi.model.OWLLiteral) axiom.getValue()).getLiteral();
            }
        }
        return "";
    }

    /** Every contributor recorded for an entity, for a panel to show. */
    public static List<String> contributorsOf(OWLOntology ontology, IRI entity) {
        List<String> contributors = new ArrayList<String>();
        if (ontology == null || entity == null) {
            return contributors;
        }
        for (OWLAnnotationAssertionAxiom axiom : ontology.getAnnotationAssertionAxioms(entity)) {
            if (CONTRIBUTOR.equals(axiom.getProperty().getIRI())) {
                OWLAnnotationValue value = axiom.getValue();
                contributors.add(value instanceof IRI ? value.toString()
                        : ((org.semanticweb.owlapi.model.OWLLiteral) value).getLiteral());
            }
        }
        Collections.sort(contributors);
        return contributors;
    }
}
