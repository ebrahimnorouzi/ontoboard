package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mxgraph.model.mxGeometry;
import de.fizkarlsruhe.ise.ontoboard.layout.BoardSections;
import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import de.fizkarlsruhe.ise.ontoboard.layout.HierarchyColumns;
import de.fizkarlsruhe.ise.ontoboard.layout.PropertiesBeside;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasNode;
import de.fizkarlsruhe.ise.ontoboard.model.OntologyProjection;
import de.fizkarlsruhe.ise.ontoboard.model.Projection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.vocab.OWL2Datatype;

/**
 * Which properties the Outline stands beside a class, and where: arranged as the board arranges
 * itself, from an ontology.
 *
 * <p>Found by the maintainer on the pizza ontologies (1.135.1): "the properties that are not
 * connected, and they just stray in the canvas". A property whose asserted domains name exactly
 * one class on the board stands beside it; one with no domain, owl:Thing, a class expression, a
 * class not on the board, or two classes on the board, stays in its section; a super-property's
 * domain does not count.
 */
class PropertiesBesideTheirClassOnTheBoardTest {

    static final String O = "http://example.org/beside#";

    /** The ontology every case of which properties stand beside a class is in. */
    static final class Cases {
        final OWLOntology ontology;
        final Set<String> on = new HashSet<String>();
        final Map<String, String> labels = new LinkedHashMap<String, String>();

        Cases() throws Exception {
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            OWLDataFactory df = manager.getOWLDataFactory();
            ontology = manager.createOntology(IRI.create("http://example.org/beside"));
            OWLClass a = cls(manager, "A", "alpha", true);
            OWLClass b = cls(manager, "B", "beta", true);
            OWLClass c = cls(manager, "C", "gamma", true);
            OWLClass d = cls(manager, "D", "delta", false);
            OWLObjectProperty p1 = property(manager, "p1", "p one");
            OWLObjectProperty p2 = property(manager, "p2", "p two");
            OWLObjectProperty p3 = property(manager, "p3", "p three");
            OWLObjectProperty p4 = property(manager, "p4", "p four");
            OWLObjectProperty p5 = property(manager, "p5", "p five");
            OWLObjectProperty p6 = property(manager, "p6", "p six");
            OWLObjectProperty p7 = property(manager, "p7", "p seven");
            OWLObjectProperty p8 = property(manager, "p8", "p eight");
            OWLObjectProperty p9 = property(manager, "p9", "p nine");
            OWLDataProperty d1 = dataProperty(manager, "d1", "d one");
            OWLDataProperty d2 = dataProperty(manager, "d2", "d two");
            manager.addAxiom(ontology, df.getOWLObjectPropertyDomainAxiom(p1, a));
            manager.addAxiom(ontology, df.getOWLObjectPropertyRangeAxiom(p1, c));
            manager.addAxiom(ontology, df.getOWLObjectPropertyDomainAxiom(p2, a));
            manager.addAxiom(ontology, df.getOWLObjectPropertyDomainAxiom(p2, b));
            manager.addAxiom(ontology, df.getOWLObjectPropertyDomainAxiom(p3, d));
            manager.addAxiom(ontology, df.getOWLObjectPropertyDomainAxiom(p4,
                    df.getOWLObjectUnionOf(a, b)));
            manager.addAxiom(ontology, df.getOWLObjectPropertyDomainAxiom(p6, df.getOWLThing()));
            manager.addAxiom(ontology, df.getOWLSubObjectPropertyOfAxiom(p7, p1));
            manager.addAxiom(ontology, df.getOWLObjectPropertyDomainAxiom(p8, a));
            manager.addAxiom(ontology, df.getOWLObjectPropertyDomainAxiom(p8, d));
            manager.addAxiom(ontology, df.getOWLObjectPropertyDomainAxiom(p9, a));
            manager.addAxiom(ontology, df.getOWLObjectPropertyDomainAxiom(p9,
                    df.getOWLObjectUnionOf(b, c)));
            manager.addAxiom(ontology, df.getOWLDataPropertyDomainAxiom(d1, b));
            manager.addAxiom(ontology, df.getOWLDataPropertyRangeAxiom(d1,
                    df.getOWLDatatype(OWL2Datatype.XSD_FLOAT.getIRI())));
            manager.addAxiom(ontology, df.getOWLDataPropertyDomainAxiom(d2, a));
            manager.addAxiom(ontology, df.getOWLDataPropertyDomainAxiom(d2, c));
            assertTrue(p5 != null);
        }

        private OWLClass cls(OWLOntologyManager manager, String name, String label,
                boolean drawn) {
            OWLClass cls = manager.getOWLDataFactory().getOWLClass(IRI.create(O + name));
            declare(manager, cls, label, drawn);
            return cls;
        }

        private OWLObjectProperty property(OWLOntologyManager manager, String name, String label) {
            OWLObjectProperty property =
                    manager.getOWLDataFactory().getOWLObjectProperty(IRI.create(O + name));
            declare(manager, property, label, true);
            return property;
        }

        private OWLDataProperty dataProperty(OWLOntologyManager manager, String name,
                String label) {
            OWLDataProperty property =
                    manager.getOWLDataFactory().getOWLDataProperty(IRI.create(O + name));
            declare(manager, property, label, true);
            return property;
        }

        private void declare(OWLOntologyManager manager, OWLEntity entity, String label,
                boolean drawn) {
            OWLDataFactory df = manager.getOWLDataFactory();
            manager.addAxiom(ontology, df.getOWLDeclarationAxiom(entity));
            manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(),
                    entity.getIRI(), df.getOWLLiteral(label)));
            labels.put(entity.getIRI().toString(), label);
            if (drawn) {
                on.add(entity.getIRI().toString());
            }
        }
    }

    /** Where each property stands once the Outline has arranged the board: beside which class. */
    static Map<String, String> whereEachPropertyStands(SchemaGraph graph,
            List<BoardSections.Section> sections, Cases cases) {
        Map<String, String> where = new LinkedHashMap<String, String>();
        for (String id : new String[] {"p1", "p2", "p3", "p4", "p5", "p6", "p7", "p8", "p9", "d1",
            "d2"}) {
            mxGeometry at = graph.getModel().getGeometry(graph.getCellForId(O + id));
            String said = "at " + Math.round(at.getX()) + "," + Math.round(at.getY());
            for (BoardSections.Section section : sections) {
                if (at.getX() >= section.x && at.getY() >= section.y
                        && at.getX() + at.getWidth() <= section.x + section.w
                        && at.getY() + at.getHeight() <= section.y + section.h) {
                    said = "in " + section.label;
                }
            }
            for (String cls : new String[] {"A", "B", "C"}) {
                mxGeometry c = graph.getModel().getGeometry(graph.getCellForId(O + cls));
                if (at.getX() == c.getX() + c.getWidth() + PropertiesBeside.GAP
                        && at.getY() >= c.getY() && at.getY() <= c.getY() + 400) {
                    said = "beside " + cases.labels.get(O + cls);
                }
            }
            where.put(cases.labels.get(O + id), said);
        }
        return where;
    }

    static Map<String, String> expected() {
        Map<String, String> expected = new LinkedHashMap<String, String>();
        expected.put("p one", "beside alpha");
        expected.put("p two", "in Object properties");
        expected.put("p three", "in Object properties");
        expected.put("p four", "in Object properties");
        expected.put("p five", "in Object properties");
        expected.put("p six", "in Object properties");
        expected.put("p seven", "in Object properties");
        expected.put("p eight", "beside alpha");
        expected.put("p nine", "beside alpha");
        expected.put("d one", "beside beta");
        expected.put("d two", "in Data properties");
        return expected;
    }

    /** Drawn from the projection and arranged by the Outline, as a new board is. */
    static SchemaGraph arranged(Projection projection, List<BoardSections.Section> sections) {
        SchemaGraph graph = new SchemaGraph();
        graph.render(projection, new CanvasLayout());
        sections.addAll(CanvasLayouts.applySections(graph, null, false));
        return graph;
    }

    @Test
    void aPropertyStandsBesideTheOneClassItsDomainsNameOnTheBoardAndOnlyThen() throws Exception {
        Cases cases = new Cases();
        for (boolean expressions : new boolean[] {false, true}) {
            List<BoardSections.Section> sections = new ArrayList<BoardSections.Section>();
            SchemaGraph graph = arranged(OntologyProjection.project(cases.ontology, cases.on,
                    expressions), sections);

            assertEquals(expected(), whereEachPropertyStands(graph, sections, cases),
                    "class expressions " + (expressions ? "drawn" : "not drawn"));
        }
    }

    /**
     * Beside alpha, its three properties one under the other in label order, the first level with
     * the class's top; beside beta, d one with its datatype under it.
     */
    @Test
    void aClasssPropertiesStandInLabelOrderAndADatatypeUnderItsProperty() throws Exception {
        Cases cases = new Cases();
        List<BoardSections.Section> sections = new ArrayList<BoardSections.Section>();
        SchemaGraph graph = arranged(OntologyProjection.project(cases.ontology, cases.on, false),
                sections);

        mxGeometry alpha = geometry(graph, O + "A");
        double y = alpha.getY();
        for (String id : new String[] {"p8", "p9", "p1"}) {
            mxGeometry at = geometry(graph, O + id);
            assertEquals(alpha.getX() + alpha.getWidth() + PropertiesBeside.GAP, at.getX(), 0.001,
                    cases.labels.get(O + id) + " right of alpha");
            assertEquals(y, at.getY(), 0.001, cases.labels.get(O + id) + " in its place");
            y += at.getHeight() + 16;
        }
        mxGeometry one = geometry(graph, O + "d1");
        mxGeometry datatype = geometry(graph, OWL2Datatype.XSD_FLOAT.getIRI().toString());
        assertEquals(one.getX() + HierarchyColumns.INDENT, datatype.getX(), 0.001,
                "float set in under d one");
        assertEquals(one.getY() + one.getHeight() + HierarchyColumns.UNDER, datatype.getY(),
                0.001, "float under d one");
    }

    /**
     * Every property beside a class: no Object or Data properties frame at all. A section with
     * nothing in it is not drawn.
     */
    @Test
    void aSectionWithNoPropertyLeftInItIsNotDrawn() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(IRI.create("http://example.org/pizza"));
        OWLClass pizza = df.getOWLClass(IRI.create(O + "Pizza"));
        OWLObjectProperty topping = df.getOWLObjectProperty(IRI.create(O + "hasTopping"));
        OWLDataProperty calories = df.getOWLDataProperty(IRI.create(O + "hasCalories"));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(pizza));
        manager.addAxiom(ontology, df.getOWLObjectPropertyDomainAxiom(topping, pizza));
        manager.addAxiom(ontology, df.getOWLDataPropertyDomainAxiom(calories, pizza));
        Set<String> on = new HashSet<String>();
        for (OWLEntity entity : new OWLEntity[] {pizza, topping, calories}) {
            on.add(entity.getIRI().toString());
        }
        List<BoardSections.Section> sections = new ArrayList<BoardSections.Section>();
        arranged(OntologyProjection.project(ontology, on, false), sections);

        List<String> labels = new ArrayList<String>();
        for (BoardSections.Section section : sections) {
            labels.add(section.label);
        }
        assertEquals(new ArrayList<String>(), labels, "sections drawn");
    }

    /**
     * Two properties joined to one beside a class - its sub-property and its inverse, neither
     * with a domain - stay in the section. The lines between them would run from the section to
     * the class's side, and wait for a selection, as a range does; selected, each is drawn,
     * routed round the boxes. (Until the egg board's review of 1.135.1 they were drawn always.)
     */
    @Test
    void aSubPropertyAndAnInverseInTheSectionAreJoinedToTheirPartnerBesideItsClassWhenSelected()
            throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(IRI.create("http://example.org/parts"));
        OWLClass whole = df.getOWLClass(IRI.create(O + "Whole"));
        OWLClass part = df.getOWLClass(IRI.create(O + "Part"));
        OWLObjectProperty hasPart = df.getOWLObjectProperty(IRI.create(O + "hasPart"));
        OWLObjectProperty hasLeg = df.getOWLObjectProperty(IRI.create(O + "hasLeg"));
        OWLObjectProperty partOf = df.getOWLObjectProperty(IRI.create(O + "partOf"));
        for (OWLEntity entity : new OWLEntity[] {whole, part, hasPart, hasLeg, partOf}) {
            manager.addAxiom(ontology, df.getOWLDeclarationAxiom(entity));
        }
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(part, whole));
        manager.addAxiom(ontology, df.getOWLObjectPropertyDomainAxiom(hasPart, whole));
        manager.addAxiom(ontology, df.getOWLSubObjectPropertyOfAxiom(hasLeg, hasPart));
        manager.addAxiom(ontology, df.getOWLInverseObjectPropertiesAxiom(hasPart, partOf));
        Set<String> on = new HashSet<String>();
        for (OWLEntity entity : new OWLEntity[] {whole, part, hasPart, hasLeg, partOf}) {
            on.add(entity.getIRI().toString());
        }
        Projection projection = OntologyProjection.project(ontology, on, false);
        List<BoardSections.Section> sections = new ArrayList<BoardSections.Section>();
        SchemaGraph graph = framedAndRouted(projection, sections);

        mxGeometry wholeBox = geometry(graph, O + "Whole");
        assertEquals(wholeBox.getX() + wholeBox.getWidth() + PropertiesBeside.GAP,
                geometry(graph, O + "hasPart").getX(), 0.001, "has part beside Whole");
        assertEquals(1, sections.size(), "one section");
        for (String inSection : new String[] {"hasLeg", "partOf"}) {
            mxGeometry at = geometry(graph, O + inSection);
            BoardSections.Section section = sections.get(0);
            assertTrue(at.getX() >= section.x && at.getX() + at.getWidth() <= section.x + section.w
                    && at.getY() >= section.y
                    && at.getY() + at.getHeight() <= section.y + section.h,
                    inSection + " in the section");
        }
        BoardMeasures measured = new BoardMeasures("parts", graph);
        assertEquals(new ArrayList<String>(), measured.through, "lines through boxes");
        String[] lines = {"subprop|" + O + "hasLeg|" + O + "hasPart",
            "inv|" + O + "hasPart|" + O + "partOf"};
        for (String line : lines) {
            Object edge = graph.getCellForId(line);
            assertNotNull(edge, line);
            assertFalse(graph.isCellVisible(edge), line + " waits for a selection");
        }
        graph.setFocus(Collections.singleton(O + "hasPart"));
        graph.getView().validate();
        for (String line : lines) {
            Object edge = graph.getCellForId(line);
            assertTrue(graph.isCellVisible(edge), line + " drawn, has part selected");
            assertNotNull(graph.routeOf(edge), line + " routed");
        }
        assertEquals(new ArrayList<String>(), new BoardMeasures("parts", graph).through,
                "lines through boxes, has part selected");
    }

    /** Arranged by the Outline, its sections framed as the board frames them, and routed. */
    static SchemaGraph framedAndRouted(Projection projection,
            List<BoardSections.Section> sections) {
        SchemaGraph graph = arranged(projection, sections);
        CanvasLayout framed = new CanvasLayout();
        for (CanvasNode node : projection.getNodes()) {
            mxGeometry at = geometry(graph, node.getId());
            CanvasLayout.NodeLayout placed = new CanvasLayout.NodeLayout(at.getX(), at.getY());
            placed.w = at.getWidth();
            placed.h = at.getHeight();
            framed.nodes.put(node.getId(), placed);
        }
        for (BoardSections.Section section : sections) {
            CanvasLayout.FrameLayout frame = new CanvasLayout.FrameLayout();
            frame.id = CanvasLayouts.SECTION_PREFIX
                    + section.label.toLowerCase(Locale.ROOT).replace(' ', '-');
            frame.label = section.label;
            frame.x = section.x;
            frame.y = section.y;
            frame.w = section.w;
            frame.h = section.h;
            framed.frames.add(frame);
        }
        graph.render(projection, framed);
        graph.routeEdges();
        graph.getView().validate();
        return graph;
    }

    static mxGeometry geometry(SchemaGraph graph, String id) {
        Object cell = graph.getCellForId(id);
        assertNotNull(cell, id + " on the board");
        return graph.getModel().getGeometry(cell);
    }
}
