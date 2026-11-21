package de.fizkarlsruhe.ise.ontoboard.views;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasNode;
import de.fizkarlsruhe.ise.ontoboard.model.NodeKind;
import de.fizkarlsruhe.ise.ontoboard.model.OntologyProjection;
import de.fizkarlsruhe.ise.ontoboard.model.Projection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.parameters.Imports;

/**
 * What the board draws (1.155.0): UML-like unless properties are asked for as boxes, and the layer
 * switches take what stands for that kind of property on either board.
 */
class BoardDrawingTest {

    private static final String U = "http://example.org/u#";

    private static OWLOntology ontology() throws Exception {
        return OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(new StringDocumentSource(
                "Prefix(:=<" + U + ">)\nPrefix(xsd:=<http://www.w3.org/2001/XMLSchema#>)\n"
                + "Ontology(<http://example.org/u>\n"
                + "Declaration(Class(:Person)) Declaration(Class(:Pizza))\n"
                + "Declaration(DataProperty(:name)) Declaration(DataProperty(:nickname))\n"
                + "Declaration(ObjectProperty(:likes))\n"
                + "DataPropertyDomain(:name :Person) DataPropertyRange(:name xsd:string)\n"
                + "ObjectPropertyDomain(:likes :Person) ObjectPropertyRange(:likes :Pizza)\n"
                + ")"));
    }

    private static Set<String> every(OWLOntology ontology) {
        return OntologyProjection.termIdentifiers(ontology, Imports.INCLUDED);
    }

    private static Set<NodeKind> kinds(Projection projection) {
        Set<NodeKind> kinds = new HashSet<NodeKind>();
        for (CanvasNode node : projection.getNodes()) {
            kinds.add(node.getKind());
        }
        return kinds;
    }

    private static List<String> rowsOfPerson(Projection projection) {
        for (CanvasNode node : projection.getNodes()) {
            if (node.getId().equals(U + "Person")) {
                return node.getRows();
            }
        }
        return Collections.emptyList();
    }

    private static boolean likes(Projection projection) {
        for (CanvasEdge edge : projection.getEdges()) {
            if (edge.getSourceId().equals(U + "Person") && edge.getTargetId().equals(U + "Pizza")) {
                return true;
            }
        }
        return false;
    }

    @Test
    void aBoardIsDrawnUmlLikeUnlessPropertiesAreAskedForAsBoxes() throws Exception {
        OWLOntology ontology = ontology();

        Projection uml = SchemaCanvasView.drawBoard(ontology, every(ontology), false,
                new SchemaCanvasView.Drawing(false, false, true, true));
        Projection boxes = SchemaCanvasView.drawBoard(ontology, every(ontology), false,
                new SchemaCanvasView.Drawing(true, false, true, true));

        assertFalse(kinds(uml).contains(NodeKind.DATA_PROPERTY) || kinds(uml).contains(NodeKind.OBJECT_PROPERTY),
                "a property box on the UML-like board: " + kinds(uml));
        assertEquals(Collections.singletonList("name : string"), rowsOfPerson(uml));
        assertTrue(likes(uml), "likes is an arrow from Person to Pizza");
        assertEquals(OntologyProjection.project(ontology, every(ontology), false).getNodes(), boxes.getNodes(),
                "as boxes, the board of 1.154.0");
        assertTrue(rowsOfPerson(boxes).isEmpty());
    }

    @Test
    void unusedPropertiesAreDrawnOnlyWhenAskedFor() throws Exception {
        OWLOntology ontology = ontology();

        Set<String> without = new HashSet<String>();
        for (CanvasNode node : SchemaCanvasView.drawBoard(ontology, every(ontology), false,
                new SchemaCanvasView.Drawing(false, false, true, true)).getNodes()) {
            without.add(node.getId());
        }
        Set<String> with = new HashSet<String>();
        for (CanvasNode node : SchemaCanvasView.drawBoard(ontology, every(ontology), false,
                new SchemaCanvasView.Drawing(false, true, true, true)).getNodes()) {
            with.add(node.getId());
        }

        assertFalse(without.contains(U + "nickname"));
        assertTrue(with.contains(U + "nickname"), with.toString());
    }

    /**
     * A board arranged by 1.154.0 kept its properties in two frames, "Object properties" and "Data
     * properties"; drawn UML-like they would stand empty. A frame stays while a property of its
     * kind is a box on the board - an unused one shown - and every other frame stays.
     */
    @Test
    void aUmlLikeBoardDropsThePropertyFramesItHasNothingFor() throws Exception {
        OWLOntology ontology = ontology();
        java.util.List<de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout.FrameLayout> frames =
                new java.util.ArrayList<de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout.FrameLayout>();
        for (String id : new String[] {"section-object-properties", "section-data-properties", "mine"}) {
            de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout.FrameLayout frame =
                    new de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout.FrameLayout();
            frame.id = de.fizkarlsruhe.ise.ontoboard.canvas.SchemaGraph.FRAME_ID_PREFIX + id;
            frames.add(frame);
        }

        SchemaCanvasView.dropEmptyPropertyFrames(frames, SchemaCanvasView.drawBoard(ontology,
                every(ontology), false, new SchemaCanvasView.Drawing(false, true, true, true)));

        Set<String> left = new HashSet<String>();
        for (de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout.FrameLayout frame : frames) {
            left.add(frame.id.substring(de.fizkarlsruhe.ise.ontoboard.canvas.SchemaGraph.FRAME_ID_PREFIX.length()));
        }
        assertEquals(new HashSet<String>(java.util.Arrays.asList("section-data-properties", "mine")), left,
                "nickname, unused and shown, is a data property's box; no object property is one");
    }

    @Test
    void theLayerSwitchesTakeTheRowsAndTheArrowsOfAUmlLikeBoard() throws Exception {
        OWLOntology ontology = ontology();

        Projection noData = SchemaCanvasView.drawBoard(ontology, every(ontology), false,
                new SchemaCanvasView.Drawing(false, false, false, true));
        Projection noObject = SchemaCanvasView.drawBoard(ontology, every(ontology), false,
                new SchemaCanvasView.Drawing(false, false, true, false));

        assertTrue(rowsOfPerson(noData).isEmpty(), "no rows with Data properties off");
        assertTrue(likes(noData), "and the arrows stay");
        assertFalse(likes(noObject), "no likes arrow with Object properties off");
        assertFalse(rowsOfPerson(noObject).isEmpty(), "and the rows stay");
    }
}
