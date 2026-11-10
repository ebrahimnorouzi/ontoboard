package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mxgraph.model.mxGeometry;
import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import de.fizkarlsruhe.ise.ontoboard.layout.FramedGroup;
import de.fizkarlsruhe.ise.ontoboard.layout.HierarchyColumns;
import de.fizkarlsruhe.ise.ontoboard.layout.PropertiesBeside;
import de.fizkarlsruhe.ise.ontoboard.model.OntologyProjection;
import de.fizkarlsruhe.ise.ontoboard.model.Projection;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDatatype;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.vocab.OWL2Datatype;

/**
 * Which properties stand beside which class is decided once, and the pattern frames - planned from
 * the ontology before the board is drawn - and the board drawn from it decide the same.
 *
 * <p>Found by the maintainer on the pizza ontologies (1.135.1); a property must never stand
 * beside its class in a frame and in its section on the Outline, or its domain line be drawn
 * always on one path and wait on the other.
 */
class PropertiesBesideAgreeOnEveryPathTest {

    private static final String O = PropertiesBesideTheirClassOnTheBoardTest.O;

    @Test
    void theFramesAndTheBoardStandTheSamePropertiesBesideTheSameClasses() throws Exception {
        PropertiesBesideTheirClassOnTheBoardTest.Cases cases =
                new PropertiesBesideTheirClassOnTheBoardTest.Cases();
        Map<String, String> expected = new TreeMap<String, String>();
        expected.put(O + "p1", O + "A");
        expected.put(O + "p8", O + "A");
        expected.put(O + "p9", O + "A");
        expected.put(O + "d1", O + "B");
        for (boolean expressions : new boolean[] {false, true}) {
            Projection projection = OntologyProjection.project(cases.ontology, cases.on,
                    expressions);
            SchemaGraph graph = new SchemaGraph();

            Map<String, List<PropertiesBeside.Item>> planned =
                    CanvasLayouts.besideTheirClass(projection, graph);
            graph.render(projection, new CanvasLayout());
            Map<String, String> drawn = graph.propertiesBesideTheirClass();

            Map<String, String> frames = new TreeMap<String, String>();
            List<String> d1 = null;
            for (Map.Entry<String, List<PropertiesBeside.Item>> cls : planned.entrySet()) {
                for (PropertiesBeside.Item item : cls.getValue()) {
                    frames.put(item.getId(), cls.getKey());
                    if (item.getId().equals(O + "d1")) {
                        d1 = item.getIds();
                    }
                }
            }
            String which = expressions ? "class expressions drawn" : "class expressions not drawn";
            assertEquals(expected, frames, "planned for the frames, " + which);
            assertEquals(expected, new TreeMap<String, String>(drawn), "on the board, " + which);
            assertEquals(Arrays.asList(O + "d1", OWL2Datatype.XSD_FLOAT.getIRI().toString()), d1,
                    "d one with its datatype, " + which);
        }
    }

    /**
     * Price in a pattern's frame, has amount beside it, and a restriction of Price's pointing at
     * has amount's datatype too: the datatype stands under has amount in the frame, as the Outline
     * would put it - Price stays in its frame, and the Outline reads what hangs off a property
     * without it. Read with Price, the datatype hung off Price, which a frame does not place, and
     * was left to the Outline with nothing to hang off.
     */
    @Test
    void aDatatypeAFramedClassAlsoPointsAtStandsUnderItsPropertyInTheFrame() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(IRI.create("http://example.org/amounts"));
        OWLClass price = df.getOWLClass(IRI.create(O + "Price"));
        OWLDataProperty amount = df.getOWLDataProperty(IRI.create(O + "hasAmount"));
        OWLDatatype decimal = df.getOWLDatatype(OWL2Datatype.XSD_DECIMAL.getIRI());
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(price,
                df.getOWLDataSomeValuesFrom(amount, decimal)));
        manager.addAxiom(ontology, df.getOWLDataPropertyDomainAxiom(amount, price));
        manager.addAxiom(ontology, df.getOWLDataPropertyRangeAxiom(amount, decimal));
        Set<String> on = new HashSet<String>(Arrays.asList(O + "Price", O + "hasAmount"));
        Projection projection = OntologyProjection.project(ontology, on, false);
        SchemaGraph graph = new SchemaGraph();

        FramedGroup.Placement placement = FramedGroup.plan("Prices",
                Collections.singletonList(O + "Price"), new CanvasLayout(),
                Collections.<String, double[]>emptyMap(), Collections.<String, String>emptyMap(),
                Collections.<String, String>emptyMap(), CanvasLayouts.besideTheirClass(
                        projection, graph, Collections.singleton(O + "Price")));
        FramedGroup.withId(placement.getFrame(), SchemaGraph.FRAME_ID_PREFIX + "prices");
        CanvasLayout layout = new CanvasLayout();
        layout.frames.add(placement.getFrame());
        layout.nodes.putAll(placement.getNodes());
        graph.render(projection, layout);
        CanvasLayouts.applySections(graph, placement.getNodes().keySet(), false);

        mxGeometry property = PropertiesBesideTheirClassOnTheBoardTest.geometry(graph,
                O + "hasAmount");
        mxGeometry datatype = PropertiesBesideTheirClassOnTheBoardTest.geometry(graph,
                OWL2Datatype.XSD_DECIMAL.getIRI().toString());
        assertEquals((property.getX() + HierarchyColumns.INDENT) + ","
                + (property.getY() + property.getHeight() + HierarchyColumns.UNDER),
                datatype.getX() + "," + datatype.getY(), "decimal under has amount");
        CanvasLayout.FrameLayout frame = placement.getFrame();
        assertTrue(datatype.getX() + datatype.getWidth() <= frame.x + frame.w
                && datatype.getY() + datatype.getHeight() <= frame.y + frame.h,
                "decimal in the frame");
    }
}
