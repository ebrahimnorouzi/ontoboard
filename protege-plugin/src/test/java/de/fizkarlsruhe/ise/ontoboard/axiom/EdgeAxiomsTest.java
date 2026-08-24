package de.fizkarlsruhe.ise.ontoboard.axiom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLObjectProperty;

/**
 * Each candidate must produce exactly the OWL form the design spec tabulates in section 5.2,
 * which was taken from OWLAx (arXiv:1808.10105 section 2). Assertions compare constructed
 * axiom objects, never rendered strings, so a renderer change cannot mask a wrong axiom.
 */
class EdgeAxiomsTest {

    private static final String NS = "http://example.org/o#";

    private OWLDataFactory factory;
    private OWLClass person;
    private OWLClass organization;
    private OWLObjectProperty worksFor;

    @BeforeEach
    void setUp() {
        factory = OWLManager.createOWLOntologyManager().getOWLDataFactory();
        person = factory.getOWLClass(IRI.create(NS + "Person"));
        organization = factory.getOWLClass(IRI.create(NS + "Organization"));
        worksFor = factory.getOWLObjectProperty(IRI.create(NS + "worksFor"));
    }

    private OWLAxiom build(EdgeAxioms.Candidate candidate) {
        return EdgeAxioms.build(factory, candidate, person, worksFor, organization);
    }

    @Test
    void existentialIsSubClassOfSomeValuesFrom() {
        assertEquals(
                factory.getOWLSubClassOfAxiom(person,
                        factory.getOWLObjectSomeValuesFrom(worksFor, organization)),
                build(EdgeAxioms.Candidate.EXISTENTIAL));
    }

    @Test
    void scopedDomainPutsTheRestrictionOnTheLeft() {
        assertEquals(
                factory.getOWLSubClassOfAxiom(
                        factory.getOWLObjectSomeValuesFrom(worksFor, organization), person),
                build(EdgeAxioms.Candidate.SCOPED_DOMAIN));
    }

    @Test
    void scopedRangeUsesAllValuesFrom() {
        assertEquals(
                factory.getOWLSubClassOfAxiom(person,
                        factory.getOWLObjectAllValuesFrom(worksFor, organization)),
                build(EdgeAxioms.Candidate.SCOPED_RANGE));
    }

    @Test
    void globalDomainIsAPropertyDomainAxiom() {
        assertEquals(factory.getOWLObjectPropertyDomainAxiom(worksFor, person),
                build(EdgeAxioms.Candidate.GLOBAL_DOMAIN));
    }

    @Test
    void globalRangeIsAPropertyRangeAxiom() {
        assertEquals(factory.getOWLObjectPropertyRangeAxiom(worksFor, organization),
                build(EdgeAxioms.Candidate.GLOBAL_RANGE));
    }

    @Test
    void functionalityIsMaxCardinalityOne() {
        assertEquals(
                factory.getOWLSubClassOfAxiom(person,
                        factory.getOWLObjectMaxCardinality(1, worksFor, organization)),
                build(EdgeAxioms.Candidate.FUNCTIONALITY));
    }

    @Test
    void subClassOfIsPlainHierarchy() {
        assertEquals(factory.getOWLSubClassOfAxiom(person, organization),
                EdgeAxioms.subClassOf(factory, person, organization));
    }

    /**
     * Scoped domain and existential differ only in which side the restriction sits on - the
     * easiest pair to implement backwards, and the mistake would be invisible in a UI.
     */
    @Test
    void scopedDomainIsNotTheSameAsExistential() {
        assertTrue(!build(EdgeAxioms.Candidate.SCOPED_DOMAIN)
                        .equals(build(EdgeAxioms.Candidate.EXISTENTIAL)),
                "the restriction must not be on the same side in both");
    }

    @Test
    void everyCandidateProducesADistinctAxiom() {
        EdgeAxioms.Candidate[] all = EdgeAxioms.Candidate.values();
        for (int i = 0; i < all.length; i++) {
            for (int j = i + 1; j < all.length; j++) {
                assertTrue(!build(all[i]).equals(build(all[j])),
                        all[i] + " and " + all[j] + " produced the same axiom");
            }
        }
    }

    @Test
    void everyCandidateIsPresentableToAUser() {
        for (EdgeAxioms.Candidate candidate : EdgeAxioms.Candidate.values()) {
            assertTrue(candidate.getDisplayName().length() > 0);
            assertTrue(candidate.getDlNotation().length() > 0);
            assertTrue(candidate.getExplanation().length() > 20,
                    candidate + " needs an explanation a domain expert can act on");
        }
    }

    @Test
    void theDefaultIsExistentialBecauseThatIsWhatAnArrowUsuallyMeans() {
        assertEquals(EdgeAxioms.Candidate.EXISTENTIAL, EdgeAxioms.DEFAULT);
    }
}
