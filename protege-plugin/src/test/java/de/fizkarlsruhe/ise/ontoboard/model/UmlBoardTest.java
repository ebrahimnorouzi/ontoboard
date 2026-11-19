package de.fizkarlsruhe.ise.ontoboard.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * A board drawn UML-like (1.155.0, decided 2026-11-19): a class's data properties are rows in its
 * box, "name : datatype [n..m]", its keys a row too, and the datatype boxes and lines that said the
 * same thing go.
 */
class UmlBoardTest {

    private static final String U = "http://example.org/u#";

    private static OWLOntology ontology() throws Exception {
        return OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(new StringDocumentSource(
                "Prefix(:=<" + U + ">)\nPrefix(xsd:=<http://www.w3.org/2001/XMLSchema#>)\n"
                + "Prefix(rdfs:=<http://www.w3.org/2000/01/rdf-schema#>)\n"
                + "Ontology(<http://example.org/u>\n"
                + "Declaration(Class(:Person)) Declaration(Class(:Pizza)) Declaration(Class(:Margherita))\n"
                + "Declaration(DataProperty(:name)) Declaration(DataProperty(:age))\n"
                + "Declaration(DataProperty(:id)) Declaration(DataProperty(:topping))\n"
                + "Declaration(DataProperty(:kind)) Declaration(ObjectProperty(:likes))\n"
                + "AnnotationAssertion(rdfs:label :name \"name\"@en)\n"
                + "DataPropertyDomain(:name :Person) DataPropertyRange(:name xsd:string)\n"
                + "DataPropertyDomain(:age :Person) DataPropertyRange(:age xsd:integer)\n"
                + "FunctionalDataProperty(:age)\n"
                + "SubClassOf(:Person DataExactCardinality(1 :name xsd:string))\n"
                + "SubClassOf(:Pizza DataSomeValuesFrom(:topping xsd:string))\n"
                + "SubClassOf(:Margherita :Pizza)\n"
                + "SubClassOf(:Margherita DataHasValue(:kind \"classic\"))\n"
                + "HasKey(:Person () (:id))\n"
                + "ObjectPropertyDomain(:likes :Person) ObjectPropertyRange(:likes :Pizza)\n"
                + ")"));
    }

    private static Projection drawn(OWLOntology ontology) {
        return UmlBoard.withRows(OntologyProjection.project(ontology,
                new HashSet<String>(Arrays.asList(U + "Person", U + "Pizza", U + "Margherita")), false),
                ontology);
    }

    private static List<String> rowsOf(Projection projection, String id) {
        for (CanvasNode node : projection.getNodes()) {
            if (node.getId().equals(id)) {
                return node.getRows();
            }
        }
        throw new AssertionError("no node " + id + " in " + projection.getNodes());
    }

    @Test
    void aClassHoldsItsDataPropertiesAsRows() throws Exception {
        Projection projection = drawn(ontology());

        assertEquals(Arrays.asList("age : integer [0..1]", "name : string [1]", "key: id"),
                rowsOf(projection, U + "Person"));
        assertEquals(Collections.singletonList("topping : string [1..*]"), rowsOf(projection, U + "Pizza"));
        assertEquals(Collections.singletonList("kind = \"classic\""), rowsOf(projection, U + "Margherita"));
    }

    @Test
    void theDatatypeBoxesTheRowsReplaceGo() throws Exception {
        Projection projection = drawn(ontology());

        for (CanvasNode node : projection.getNodes()) {
            assertFalse(node.getKind() == NodeKind.DATATYPE, "a datatype box is left: " + node);
        }
        for (CanvasEdge edge : projection.getEdges()) {
            assertFalse(edge.getKind() == CanvasEdge.Kind.DATA_PROPERTY, "a line to a datatype is left: " + edge);
        }
    }

    private static final String I = "http://example.org/imported#";

    /**
     * The same ontology with two properties of its own nothing uses - nickname, knows - importing
     * one whose two properties nothing uses either.
     */
    private static OWLOntology withAnImport() throws Exception {
        org.semanticweb.owlapi.model.OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        manager.loadOntologyFromOntologyDocument(new StringDocumentSource(
                "Prefix(:=<" + I + ">)\nOntology(<http://example.org/imported>\n"
                + "Declaration(DataProperty(:borrowed)) Declaration(ObjectProperty(:lent))\n)"));
        return manager.loadOntologyFromOntologyDocument(new StringDocumentSource(
                "Prefix(:=<" + U + ">)\nPrefix(xsd:=<http://www.w3.org/2001/XMLSchema#>)\n"
                + "Ontology(<http://example.org/u>\nImport(<http://example.org/imported>)\n"
                + "Declaration(Class(:Person)) Declaration(Class(:Pizza))\n"
                + "Declaration(DataProperty(:name)) Declaration(DataProperty(:nickname))\n"
                + "Declaration(ObjectProperty(:likes)) Declaration(ObjectProperty(:knows))\n"
                + "DataPropertyDomain(:name :Person) DataPropertyRange(:name xsd:string)\n"
                + "ObjectPropertyDomain(:likes :Person) ObjectPropertyRange(:likes :Pizza)\n"
                + ")"));
    }

    /** Every term, as a board that shows every term has them: the imports' too. */
    private static java.util.Set<String> everyTerm(OWLOntology ontology) {
        return OntologyProjection.termIdentifiers(ontology,
                org.semanticweb.owlapi.model.parameters.Imports.INCLUDED);
    }

    private static java.util.Set<String> ids(Projection projection) {
        java.util.Set<String> ids = new HashSet<String>();
        for (CanvasNode node : projection.getNodes()) {
            ids.add(node.getId());
        }
        return ids;
    }

    @Test
    void aBoardOfEveryTermDrawsNoPropertyBox() throws Exception {
        OWLOntology ontology = withAnImport();

        Projection projection = UmlBoard.draw(ontology, everyTerm(ontology), false, false);

        for (CanvasNode node : projection.getNodes()) {
            assertFalse(node.getKind() == NodeKind.OBJECT_PROPERTY || node.getKind() == NodeKind.DATA_PROPERTY,
                    "a property box: " + node);
        }
        assertEquals(Collections.singletonList("name : string"), rowsOf(projection, U + "Person"));
        boolean arrow = false;
        for (CanvasEdge edge : projection.getEdges()) {
            arrow |= edge.getSourceId().equals(U + "Person") && edge.getTargetId().equals(U + "Pizza");
        }
        assertTrue(arrow, "likes is an arrow from Person to Pizza: " + projection.getEdges());
    }

    @Test
    void theOpenOntologysUnusedPropertiesAreDrawnWhenAskedFor() throws Exception {
        OWLOntology ontology = withAnImport();
        java.util.Set<String> every = everyTerm(ontology);

        assertEquals(new HashSet<String>(Arrays.asList(U + "nickname", U + "knows")),
                UmlBoard.unused(UmlBoard.draw(ontology, every, false, false), ontology, every));
        java.util.Set<String> shown = ids(UmlBoard.draw(ontology, every, false, true));
        assertTrue(shown.contains(U + "nickname") && shown.contains(U + "knows"), shown.toString());
        assertFalse(shown.contains(I + "borrowed") || shown.contains(I + "lent"),
                "an import's unused property is never drawn: " + shown);
        assertFalse(shown.contains(U + "name") || shown.contains(U + "likes"),
                "a property drawn as a row or an arrow is not a box as well: " + shown);
    }

    /**
     * OWL's own top and bottom properties are no property of the ontology's, though a file that
     * names one has it in its signature - MWO's topDataProperty came back as a box with Show unused
     * properties.
     */
    @Test
    void owlsTopAndBottomPropertiesAreNeverUnused() throws Exception {
        OWLOntology ontology = OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(
                new StringDocumentSource("Prefix(:=<" + U + ">)\nPrefix(owl:=<http://www.w3.org/2002/07/owl#>)\n"
                        + "Ontology(<http://example.org/u>\n"
                        + "Declaration(DataProperty(:nickname)) Declaration(ObjectProperty(:knows))\n"
                        + "SubDataPropertyOf(:nickname owl:topDataProperty)\n"
                        + "SubObjectPropertyOf(:knows owl:topObjectProperty)\n"
                        + "SubObjectPropertyOf(owl:bottomObjectProperty :knows)\n)"));
        java.util.Set<String> every = everyTerm(ontology);
        assertTrue(every.contains("http://www.w3.org/2002/07/owl#topDataProperty"),
                "the file names OWL's top data property: " + every);

        assertEquals(new HashSet<String>(Arrays.asList(U + "nickname", U + "knows")),
                UmlBoard.unused(UmlBoard.draw(ontology, every, false, false), ontology, every));
    }

    @Test
    void theLayerSwitchesTakeTheRowsAndTheArrows() throws Exception {
        OWLOntology ontology = withAnImport();
        Projection projection = UmlBoard.draw(ontology, everyTerm(ontology), false, false);
        assertFalse(rowsOf(projection, U + "Person").isEmpty(), "Person has its rows to take");
        assertTrue(likesArrowIn(projection), "and likes its arrow: " + projection.getEdges());

        assertTrue(rowsOf(UmlBoard.withoutRows(projection), U + "Person").isEmpty());
        assertFalse(likesArrowIn(UmlBoard.withoutObjectProperties(projection, ontology)),
                "likes's arrow is left");
        assertTrue(likesArrowIn(UmlBoard.withoutRows(projection)), "the rows' switch leaves the arrows");
        assertFalse(rowsOf(UmlBoard.withoutObjectProperties(projection, ontology), U + "Person").isEmpty(),
                "and the arrows' switch the rows");
    }

    private static boolean likesArrowIn(Projection projection) {
        for (CanvasEdge edge : projection.getEdges()) {
            if (edge.getSourceId().equals(U + "Person") && edge.getTargetId().equals(U + "Pizza")) {
                return true;
            }
        }
        return false;
    }

    @Test
    void anObjectPropertyStaysItsArrow() throws Exception {
        Projection projection = drawn(ontology());

        boolean arrow = false;
        for (CanvasEdge edge : projection.getEdges()) {
            arrow |= edge.getSourceId().equals(U + "Person") && edge.getTargetId().equals(U + "Pizza")
                    && edge.getLabel().startsWith("likes");
        }
        assertTrue(arrow, "likes is an arrow from Person to Pizza: " + projection.getEdges());
    }
}
