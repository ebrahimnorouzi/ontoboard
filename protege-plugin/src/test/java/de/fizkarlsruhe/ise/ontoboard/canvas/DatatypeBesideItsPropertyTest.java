package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mxgraph.model.mxGeometry;
import de.fizkarlsruhe.ise.ontoboard.layout.BoardSections;
import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import de.fizkarlsruhe.ise.ontoboard.model.OntologyProjection;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.vocab.OWL2Datatype;

/**
 * A data property's range stands with the property, in the Data properties section.
 *
 * <p>Found by a tester after importing a pattern whose value property has the range xsd:float: a
 * lone "float" ellipse in the bottom-left corner of the board, outside every frame and with no line.
 * Ranges wait for a selection since 1.135.1, and the arrangement read only the lines on show.
 */
class DatatypeBesideItsPropertyTest {

    private static final String O = "http://example.org/price#";

    /**
     * The property has no domain, so that it stands in its section: since 1.135.1 a property whose
     * domain names one class on the board stands beside that class, its datatype under it - see
     * {@code PropertiesBesideTheirClassOnTheBoardTest}.
     */
    @Test
    void aRangeWaitingForASelectionKeepsItsDatatypeInTheDataPropertiesSection() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(IRI.create("http://example.org/price"));
        OWLClass price = df.getOWLClass(IRI.create(O + "Price"));
        OWLDataProperty hasValue = df.getOWLDataProperty(IRI.create(O + "hasValue"));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(price));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(hasValue));
        manager.addAxiom(ontology, df.getOWLDataPropertyRangeAxiom(hasValue,
                df.getOWLDatatype(OWL2Datatype.XSD_FLOAT.getIRI())));

        SchemaGraph graph = new SchemaGraph();
        graph.render(OntologyProjection.project(ontology,
                new HashSet<String>(Arrays.asList(O + "Price", O + "hasValue")), false),
                new CanvasLayout());
        Object floatBox = graph.getCellForId(OWL2Datatype.XSD_FLOAT.getIRI().toString());
        assertNotNull(floatBox, "the range's datatype is on the board");
        assertTrue(!graph.isCellVisible(graph.getModel().getEdgeAt(floatBox, 0)),
                "the range line waits for a selection, as the test's premise");

        List<BoardSections.Section> sections = CanvasLayouts.applySections(graph, null, false);

        BoardSections.Section data = null;
        for (BoardSections.Section section : sections) {
            if ("Data properties".equals(section.label)) {
                data = section;
            }
        }
        assertNotNull(data, "a Data properties section");
        mxGeometry at = graph.getModel().getGeometry(floatBox);
        assertTrue(at.getX() >= data.x && at.getY() >= data.y
                && at.getX() + at.getWidth() <= data.x + data.w
                && at.getY() + at.getHeight() <= data.y + data.h,
                "float stands in the Data properties section with has value, at " + at.getX()
                        + "," + at.getY() + "; the section is " + data.x + "," + data.y + " "
                        + data.w + "x" + data.h);
    }
}
