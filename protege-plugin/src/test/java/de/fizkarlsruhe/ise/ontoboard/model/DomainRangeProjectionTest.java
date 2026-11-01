package de.fizkarlsruhe.ise.ontoboard.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.axiom.AxiomRemoval;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Domains and ranges on the board, from the property's own node.
 *
 * <p>Measured before: 112 of 921 logical axioms in one published ontology built on BFO were
 * domains and ranges, and none showed - only a pair of one named domain and one named range in
 * the same file, drawn as a class-to-class arrow labelled like a restriction.
 */
class DomainRangeProjectionTest {

    private static final String NS = "http://example.org/o#";

    private final OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
    private final OWLDataFactory df = manager.getOWLDataFactory();
    private final OWLClass process = df.getOWLClass(IRI.create(NS + "Process"));
    private final OWLClass event = df.getOWLClass(IRI.create(NS + "Event"));
    private final OWLClass agent = df.getOWLClass(IRI.create(NS + "Agent"));
    private final OWLObjectProperty hasParticipant =
            df.getOWLObjectProperty(IRI.create(NS + "hasParticipant"));

    private OWLOntology ontology() throws Exception {
        OWLOntology o = manager.createOntology(IRI.create("http://example.org/o"));
        for (OWLClass cls : Arrays.asList(process, event, agent)) {
            manager.addAxiom(o, df.getOWLDeclarationAxiom(cls));
        }
        manager.addAxiom(o, df.getOWLDeclarationAxiom(hasParticipant));
        return o;
    }

    private static Set<String> all(OWLOntology o) {
        return OntologyProjection.termIdentifiers(o,
                org.semanticweb.owlapi.model.parameters.Imports.INCLUDED);
    }

    private static List<CanvasEdge> scopes(Projection projection) {
        List<CanvasEdge> found = new ArrayList<CanvasEdge>();
        for (CanvasEdge edge : projection.getEdges()) {
            if (edge.getKind() == CanvasEdge.Kind.DOMAIN_RANGE) {
                found.add(edge);
            }
        }
        return found;
    }

    @Test
    void aNamedDomainAndRangeAreDrawnFromThePropertyNotAsARestriction() throws Exception {
        OWLOntology o = ontology();
        manager.addAxiom(o, df.getOWLObjectPropertyDomainAxiom(hasParticipant, process));
        manager.addAxiom(o, df.getOWLObjectPropertyRangeAxiom(hasParticipant, agent));

        Projection drawn = OntologyProjection.project(o, all(o), true);

        Set<String> said = new HashSet<String>();
        for (CanvasEdge edge : scopes(drawn)) {
            assertEquals(NS + "hasParticipant", edge.getSourceId());
            said.add(edge.getLabel() + " " + edge.getTargetId());
        }
        assertEquals(new HashSet<String>(Arrays.asList("domain " + NS + "Process",
                "range " + NS + "Agent")), said);
        for (CanvasEdge edge : drawn.getEdges()) {
            assertFalse(edge.getId().startsWith("dr|"),
                    "no class-to-class arrow while the property's own node is on the board");
        }
    }

    @Test
    void withThePropertyOffTheBoardThePairIsStillOneArrow() throws Exception {
        OWLOntology o = ontology();
        manager.addAxiom(o, df.getOWLObjectPropertyDomainAxiom(hasParticipant, process));
        manager.addAxiom(o, df.getOWLObjectPropertyRangeAxiom(hasParticipant, agent));
        Set<String> classesOnly = new HashSet<String>(Arrays.asList(NS + "Process", NS + "Agent"));

        Projection drawn = OntologyProjection.project(o, classesOnly, true);

        assertEquals(1, drawn.getEdges().size());
        assertTrue(drawn.getEdges().get(0).getId().startsWith("dr|"));
    }

    /** Two domain axioms mean both; a union means either - so a union is not two plain edges. */
    @Test
    void aUnionDomainIsDrawnAsItselfOrSaysOneOf() throws Exception {
        OWLOntology o = ontology();
        manager.addAxiom(o, df.getOWLObjectPropertyDomainAxiom(hasParticipant,
                df.getOWLObjectUnionOf(process, event)));

        Projection withExpressions = OntologyProjection.project(o, all(o), true);
        assertEquals(1, scopes(withExpressions).size());
        assertTrue(scopes(withExpressions).get(0).getTargetId()
                .startsWith(OntologyProjection.EXPRESSION_PREFIX), "to the union's own node");

        Projection without = OntologyProjection.project(o, all(o), false);
        assertEquals(2, scopes(without).size());
        for (CanvasEdge edge : scopes(without)) {
            assertEquals("domain, one of", edge.getLabel());
            assertTrue(edge.getId().startsWith("domain-or|"));
        }
    }

    @Test
    void aDataPropertysRangeIsItsDatatype() throws Exception {
        OWLOntology o = ontology();
        OWLDataProperty age = df.getOWLDataProperty(IRI.create(NS + "age"));
        manager.addAxiom(o, df.getOWLDeclarationAxiom(age));
        manager.addAxiom(o, df.getOWLDataPropertyDomainAxiom(age, agent));
        manager.addAxiom(o, df.getOWLDataPropertyRangeAxiom(age, df.getIntegerOWLDatatype()));

        Projection drawn = OntologyProjection.project(o, all(o), true);

        Set<String> said = new HashSet<String>();
        for (CanvasEdge edge : scopes(drawn)) {
            said.add(edge.getLabel() + " " + edge.getTargetId());
        }
        assertTrue(said.contains("domain " + NS + "Agent"), said.toString());
        assertTrue(said.contains("range http://www.w3.org/2001/XMLSchema#integer"),
                said.toString());
    }

    @Test
    void equivalentPropertiesAreTwoSubPropertyArrows() throws Exception {
        OWLOntology o = ontology();
        OWLObjectProperty involves = df.getOWLObjectProperty(IRI.create(NS + "involves"));
        manager.addAxiom(o, df.getOWLDeclarationAxiom(involves));
        manager.addAxiom(o, df.getOWLEquivalentObjectPropertiesAxiom(hasParticipant, involves));

        int arrows = 0;
        for (CanvasEdge edge : OntologyProjection.project(o, all(o), true).getEdges()) {
            if (edge.getKind() == CanvasEdge.Kind.SUB_PROPERTY) {
                arrows++;
            }
        }
        assertEquals(2, arrows);
    }

    @Test
    void deletingTheEdgeRemovesANamedDomainAndRefusesAUnion() throws Exception {
        OWLOntology o = ontology();
        manager.addAxiom(o, df.getOWLObjectPropertyDomainAxiom(hasParticipant, process));

        assertEquals(1, AxiomRemoval.removalsFor(o,
                "domain|" + NS + "hasParticipant|" + NS + "Process").size());
        assertThrows(AxiomRemoval.UnknownEdgeException.class, () -> AxiomRemoval.removalsFor(o,
                "domain-or|" + NS + "hasParticipant|" + NS + "Process"));
    }

    @Test
    void aBoardShowsTheOntologysOwnTermsFirstAndNoneThatWereHidden() throws Exception {
        OWLOntology o = ontology();

        List<String> shown = OntologyProjection.termsToShow(o,
                Collections.singletonList(NS + "Event"));

        assertFalse(shown.contains(NS + "Event"));
        assertEquals(Arrays.asList(NS + "Agent", NS + "Process", NS + "hasParticipant"), shown);
    }

    /** OBI writes an inverse pair as an equivalence with an inverse: the same line, then. */
    @Test
    void aPropertyEquivalentToAnInverseIsDrawnAsAnInversePair() throws Exception {
        OWLOntology o = ontology();
        OWLObjectProperty participatesIn = df.getOWLObjectProperty(IRI.create(NS + "partIn"));
        manager.addAxiom(o, df.getOWLDeclarationAxiom(participatesIn));
        manager.addAxiom(o, df.getOWLEquivalentObjectPropertiesAxiom(hasParticipant,
                df.getOWLObjectInverseOf(participatesIn)));

        int inverse = 0;
        for (CanvasEdge edge : OntologyProjection.project(o, all(o), true).getEdges()) {
            if (edge.getKind() == CanvasEdge.Kind.INVERSE) {
                inverse++;
            }
        }
        assertEquals(1, inverse);
    }

    /** BFO: a continuant is disjoint with anything that is part of an occurrent. */
    @Test
    void aClassDisjointWithARestrictionIsDisjointWithItsNode() throws Exception {
        OWLOntology o = ontology();
        manager.addAxiom(o, df.getOWLDisjointClassesAxiom(agent,
                df.getOWLObjectSomeValuesFrom(hasParticipant, process)));

        boolean drawn = false;
        for (CanvasEdge edge : OntologyProjection.project(o, all(o), true).getEdges()) {
            drawn |= edge.getKind() == CanvasEdge.Kind.DISJOINT
                    && (edge.getSourceId().startsWith(OntologyProjection.EXPRESSION_PREFIX)
                            || edge.getTargetId().startsWith(OntologyProjection.EXPRESSION_PREFIX));
        }
        assertTrue(drawn);
    }

    @Test
    void anEquivalenceBetweenTwoRestrictionsJoinsTheirNodesBothWays() throws Exception {
        OWLOntology o = ontology();
        manager.addAxiom(o, df.getOWLEquivalentClassesAxiom(
                df.getOWLObjectSomeValuesFrom(hasParticipant, process),
                df.getOWLObjectSomeValuesFrom(hasParticipant, event)));

        int joins = 0;
        for (CanvasEdge edge : OntologyProjection.project(o, all(o), true).getEdges()) {
            if (edge.getId().startsWith("eqx|")) {
                joins++;
            }
        }
        assertEquals(2, joins);
    }

    @Test
    void aFunctionalDataPropertySaysSoInItsLabel() throws Exception {
        OWLOntology o = ontology();
        OWLDataProperty age = df.getOWLDataProperty(IRI.create(NS + "age"));
        manager.addAxiom(o, df.getOWLDeclarationAxiom(age));
        manager.addAxiom(o, df.getOWLFunctionalDataPropertyAxiom(age));

        for (CanvasNode node : OntologyProjection.project(o, all(o), true).getNodes()) {
            if (node.getId().equals(NS + "age")) {
                assertTrue(node.getLabel().endsWith("(functional)"), node.getLabel());
                return;
            }
        }
        throw new AssertionError("age is not on the board");
    }

    /** The last logical axiom the board did not draw: four in each ontology measured. */
    @Test
    void aSwrlRuleIsANodeThatReadsTheRuleWithItsTermsJoinedIfAndThen() throws Exception {
        OWLOntology o = ontology();
        OWLDataProperty age = df.getOWLDataProperty(IRI.create(NS + "age"));
        manager.addAxiom(o, df.getOWLDeclarationAxiom(age));
        org.semanticweb.owlapi.model.SWRLVariable x = df.getSWRLVariable(IRI.create(NS + "x"));
        org.semanticweb.owlapi.model.SWRLVariable a = df.getSWRLVariable(IRI.create(NS + "a"));
        manager.addAxiom(o, df.getSWRLRule(
                new HashSet<org.semanticweb.owlapi.model.SWRLAtom>(Arrays.asList(
                        df.getSWRLClassAtom(agent, x), df.getSWRLDataPropertyAtom(age, x, a))),
                Collections.<org.semanticweb.owlapi.model.SWRLAtom>singleton(
                        df.getSWRLClassAtom(process, x))));

        Projection drawn = OntologyProjection.project(o, all(o), true);

        CanvasNode rule = null;
        for (CanvasNode node : drawn.getNodes()) {
            if (node.getLabel().startsWith("rule: ")) {
                rule = node;
            }
        }
        assertTrue(rule != null, "a node for the rule");
        assertTrue(rule.getLabel().contains("?x") && rule.getLabel().contains("\u2192"),
                rule.getLabel());
        Set<String> parts = new HashSet<String>();
        for (CanvasEdge edge : drawn.getEdges()) {
            if (edge.getKind() == CanvasEdge.Kind.RULE_PART) {
                parts.add(edge.getLabel() + " " + (edge.getLabel().equals("if")
                        ? edge.getSourceId() : edge.getTargetId()));
            }
        }
        assertEquals(new HashSet<String>(Arrays.asList("if " + NS + "Agent", "if " + NS + "age",
                "then " + NS + "Process")), parts);
    }
}
