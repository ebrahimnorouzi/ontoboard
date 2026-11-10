package de.fizkarlsruhe.ise.ontoboard.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * A line from a term to itself, only for an axiom that says something.
 *
 * <p>Found on the egg-boiling study board (1.135.1): PMDco's Fundamental property module states
 * {@code SubObjectPropertyOf(has part, has part)}, and "has part" carried a small is-a loop. An
 * axiom whose two sides are the same term is true of every term and says nothing about this one.
 * A restriction from a class to itself - every whole has a part that is a whole - does say
 * something, and keeps its arrow. Nor does a tautology put a box on the board, or decide where a
 * class stands.
 */
class TautologyIsNoLineTest {

    private static final String NS = "http://example.org/tautology#";

    @Test
    void aTautologyIsNoLineButARestrictionFromAClassToItselfIs() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(IRI.create("urn:test:tautology"));
        OWLObjectProperty hasPart = df.getOWLObjectProperty(IRI.create(NS + "hasPart"));
        OWLObjectProperty hasProperPart = df.getOWLObjectProperty(IRI.create(NS + "hasProperPart"));
        OWLDataProperty hasValue = df.getOWLDataProperty(IRI.create(NS + "hasValue"));
        OWLClass whole = df.getOWLClass(IRI.create(NS + "Whole"));
        OWLClass part = df.getOWLClass(IRI.create(NS + "Part"));
        OWLClassExpression withAPart = df.getOWLObjectSomeValuesFrom(hasPart, part);
        Set<OWLAxiom> axioms = new HashSet<OWLAxiom>(Arrays.<OWLAxiom>asList(
                // Each true of anything, saying nothing.
                df.getOWLSubObjectPropertyOfAxiom(hasPart, hasPart),
                df.getOWLSubDataPropertyOfAxiom(hasValue, hasValue),
                df.getOWLSubClassOfAxiom(whole, whole),
                df.getOWLSubClassOfAxiom(withAPart, withAPart),
                df.getOWLDisjointUnionAxiom(whole,
                        new HashSet<OWLClassExpression>(Arrays.asList(whole, part))),
                // And beside them, what does say something.
                df.getOWLSubObjectPropertyOfAxiom(hasProperPart, hasPart),
                df.getOWLSubClassOfAxiom(whole, df.getOWLObjectSomeValuesFrom(hasPart, whole))));
        manager.addAxioms(ontology, axioms);
        Set<String> on = new HashSet<String>(Arrays.asList(NS + "hasPart", NS + "hasProperPart",
                NS + "hasValue", NS + "Whole", NS + "Part"));

        for (boolean expressions : new boolean[] {false, true}) {
            Projection projection = OntologyProjection.project(ontology, on, expressions);
            List<String> loops = new ArrayList<String>();
            boolean wholeHasAWholePart = false;
            boolean properPartIsAPart = false;
            for (CanvasEdge edge : projection.getEdges()) {
                if (edge.getSourceId().equals(edge.getTargetId())) {
                    if (edge.getKind() == CanvasEdge.Kind.OBJECT_PROPERTY
                            && edge.getSourceId().equals(NS + "Whole")) {
                        wholeHasAWholePart = true;
                    } else {
                        loops.add(edge.getId() + " (" + edge.getKind() + ")");
                    }
                }
                properPartIsAPart |= edge.getKind() == CanvasEdge.Kind.SUB_PROPERTY
                        && edge.getSourceId().equals(NS + "hasProperPart")
                        && edge.getTargetId().equals(NS + "hasPart");
            }
            assertEquals(new ArrayList<String>(), loops,
                    "a line from a term to itself for a tautology, expressions " + expressions);
            assertTrue(wholeHasAWholePart,
                    "Whole SubClassOf hasPart some Whole keeps its arrow, expressions " + expressions);
            assertTrue(properPartIsAPart,
                    "a real sub-property keeps its line, expressions " + expressions);
        }
    }

    /**
     * A general axiom with the same expression on both sides puts nothing on the board: no line,
     * and no box for the expression either. An expression another axiom uses keeps its box.
     *
     * <p>Found in review (1.135.1): the loop was gone, but SubClassOf(p some A, p some A) still
     * left a grey "∃ p" box hanging off A by its operand line, which reads as "∃ p ⊑ A".
     */
    @Test
    void aTautologicalGeneralAxiomPutsNoBoxOnTheBoard() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(IRI.create("urn:test:tautological-gci"));
        OWLObjectProperty p = df.getOWLObjectProperty(IRI.create(NS + "p"));
        OWLClass a = df.getOWLClass(IRI.create(NS + "A"));
        OWLClass b = df.getOWLClass(IRI.create(NS + "B"));
        OWLClass c = df.getOWLClass(IRI.create(NS + "C"));
        OWLClassExpression pSomeA = df.getOWLObjectSomeValuesFrom(p, a);
        manager.addAxioms(ontology, new HashSet<OWLAxiom>(Arrays.<OWLAxiom>asList(
                // Nothing else is said about p some A.
                df.getOWLSubClassOfAxiom(pSomeA, pSomeA),
                // B and A is A and B, written the other way round.
                df.getOWLSubClassOfAxiom(df.getOWLObjectIntersectionOf(a, b),
                        df.getOWLObjectIntersectionOf(b, a)),
                // And this says something about A and B, so its box stays.
                df.getOWLSubClassOfAxiom(df.getOWLObjectIntersectionOf(a, b), c))));
        Set<String> on = new HashSet<String>(Arrays.asList(NS + "p", NS + "A", NS + "B", NS + "C"));

        for (boolean expressions : new boolean[] {false, true}) {
            Projection projection = OntologyProjection.project(ontology, on, expressions);
            Map<String, String> boxes = new HashMap<String, String>();
            for (CanvasNode node : projection.getNodes()) {
                if (node.getKind() == NodeKind.EXPRESSION) {
                    boxes.put(node.getId(), node.getLabel());
                }
            }
            List<String> lines = new ArrayList<String>();
            for (CanvasEdge edge : projection.getEdges()) {
                lines.add(nameOf(edge.getSourceId(), boxes) + " -> "
                        + nameOf(edge.getTargetId(), boxes));
            }
            List<String> labels = new ArrayList<String>(boxes.values());
            Collections.sort(labels);
            Collections.sort(lines);
            assertEquals(expressions ? Arrays.asList("⊓") : Collections.<String>emptyList(),
                    labels, "the expression boxes, expressions " + expressions);
            assertEquals(expressions
                    ? Arrays.asList("⊓ -> A", "⊓ -> B", "⊓ -> C")
                    : Collections.<String>emptyList(), lines,
                    "the lines, expressions " + expressions);
        }
    }

    /** A term by its local name, an expression's box by what it says. */
    private static String nameOf(String id, Map<String, String> boxes) {
        if (boxes.containsKey(id)) {
            return boxes.get(id);
        }
        return id.startsWith(NS) ? id.substring(NS.length()) : id;
    }

    /**
     * A tautology on a class somebody made decides neither the frame it stands in nor the class it
     * is drawn under.
     *
     * <p>Found in review (1.135.1): with a-made under b-between under z-framed, a class in a
     * pattern's frame, SubClassOf(a-made, a-made) - typed in Protégé's description view, say -
     * made the climb from a-made step to a-made itself, which sorts first, and stop there. a-made
     * then stood in the outline below the frames, its is-a line running up into one.
     */
    @Test
    void aTautologyDecidesNotWhereAClassStands() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(IRI.create("urn:test:tautology-and-frames"));
        OWLClass framed = df.getOWLClass(IRI.create(NS + "z-framed"));
        OWLClass made = df.getOWLClass(IRI.create(NS + "a-made"));
        OWLClass between = df.getOWLClass(IRI.create(NS + "b-between"));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(made));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(between));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(made, between));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(between, framed));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(made, made));

        Map<String, String> inTheFrame = new HashMap<String, String>();
        inTheFrame.put(NS + "a-made", NS + "z-framed");
        inTheFrame.put(NS + "b-between", NS + "z-framed");
        assertEquals(inTheFrame, OntologyProjection.ownClassesUnder(ontology,
                Collections.singleton(NS + "z-framed")), "both stand in z-framed's frame");
        assertEquals(Collections.singletonMap(NS + "a-made", NS + "b-between"),
                OntologyProjection.superclassesAmong(ontology,
                        Arrays.asList(NS + "a-made", NS + "b-between")),
                "a-made is drawn under b-between, not under itself");
    }
}
