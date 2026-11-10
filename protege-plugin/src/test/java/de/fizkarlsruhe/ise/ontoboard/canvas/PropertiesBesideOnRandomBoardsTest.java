package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mxgraph.model.mxGeometry;
import de.fizkarlsruhe.ise.ontoboard.layout.BoardSections;
import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import de.fizkarlsruhe.ise.ontoboard.layout.FramedGroup;
import de.fizkarlsruhe.ise.ontoboard.layout.PropertiesBeside;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasNode;
import de.fizkarlsruhe.ise.ontoboard.model.Curies;
import de.fizkarlsruhe.ise.ontoboard.model.OntologyProjection;
import de.fizkarlsruhe.ise.ontoboard.model.Projection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
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
import org.semanticweb.owlapi.vocab.OWL2Datatype;

/**
 * Ontologies made up at random, arranged as the board arranges itself - a pattern's frame along
 * the top, planned from the ontology, and the Outline under it: no box on another, none out of its
 * frame, every property whose domain names one class on the board right of that class and level
 * with it, and the same board whatever order the axioms come in.
 *
 * <p>Found by the maintainer on the pizza ontologies (1.135.1): every property stood in a section
 * of its own, joined to nothing until it was clicked.
 */
class PropertiesBesideOnRandomBoardsTest {

    private static final String NS = "urn:random#";
    private static final String PATTERN = SchemaGraph.FRAME_ID_PREFIX + "pattern";
    private static final String[] WORDS = {"process", "quality", "material", "device", "specimen",
        "data set", "report", "role", "function", "value", "unit", "sample", "plan", "site"};

    /** A made-up ontology: its axioms in the order they were made, and what is on the board. */
    private static final class MadeUp {
        final List<OWLAxiom> axioms = new ArrayList<OWLAxiom>();
        final Set<String> on = new HashSet<String>();
        final List<String> classes = new ArrayList<String>();
        final List<String> properties = new ArrayList<String>();
        /** Each property's domain classes on the board, worked out here from the axioms. */
        final Map<String, Set<String>> domains = new HashMap<String, Set<String>>();
    }

    private static MadeUp madeUp(long seed) {
        Random random = new Random(seed);
        OWLDataFactory df = OWLManager.getOWLDataFactory();
        MadeUp made = new MadeUp();
        List<OWLClass> classes = new ArrayList<OWLClass>();
        int count = 10 + random.nextInt(15);
        for (int c = 0; c < count; c++) {
            OWLClass cls = df.getOWLClass(IRI.create(NS + "c" + c));
            classes.add(cls);
            declare(made, df, cls.getIRI(), df.getOWLDeclarationAxiom(cls),
                    WORDS[random.nextInt(WORDS.length)] + " " + c, true);
            made.classes.add(cls.getIRI().toString());
            if (c > 0 && random.nextInt(10) < 7) {
                made.axioms.add(df.getOWLSubClassOfAxiom(cls, classes.get(random.nextInt(c))));
            }
        }
        OWLClass away = df.getOWLClass(IRI.create(NS + "away"));
        declare(made, df, away.getIRI(), df.getOWLDeclarationAxiom(away), "not drawn", false);
        List<OWLObjectProperty> objects = new ArrayList<OWLObjectProperty>();
        int objectCount = 6 + random.nextInt(9);
        for (int p = 0; p < objectCount; p++) {
            OWLObjectProperty property = df.getOWLObjectProperty(IRI.create(NS + "p" + p));
            objects.add(property);
            declare(made, df, property.getIRI(), df.getOWLDeclarationAxiom(property),
                    "has " + WORDS[random.nextInt(WORDS.length)] + " " + p, true);
            made.properties.add(property.getIRI().toString());
            for (OWLClassExpression domain : domains(random, df, classes, away)) {
                made.axioms.add(df.getOWLObjectPropertyDomainAxiom(property, domain));
                note(made, property.getIRI().toString(), domain);
            }
            if (random.nextBoolean()) {
                made.axioms.add(df.getOWLObjectPropertyRangeAxiom(property,
                        classes.get(random.nextInt(classes.size()))));
            }
            if (p > 0 && random.nextInt(10) < 2) {
                OWLObjectProperty up = objects.get(random.nextInt(p));
                made.axioms.add(df.getOWLSubObjectPropertyOfAxiom(property, up));
            } else if (p > 0 && random.nextInt(10) < 2) {
                made.axioms.add(df.getOWLInverseObjectPropertiesAxiom(property,
                        objects.get(random.nextInt(p))));
            }
        }
        OWL2Datatype[] datatypes = {OWL2Datatype.XSD_STRING, OWL2Datatype.XSD_FLOAT,
            OWL2Datatype.XSD_INTEGER};
        int dataCount = 2 + random.nextInt(5);
        for (int d = 0; d < dataCount; d++) {
            OWLDataProperty property = df.getOWLDataProperty(IRI.create(NS + "d" + d));
            declare(made, df, property.getIRI(), df.getOWLDeclarationAxiom(property),
                    "has " + WORDS[random.nextInt(WORDS.length)] + " value " + d, true);
            made.properties.add(property.getIRI().toString());
            for (OWLClassExpression domain : domains(random, df, classes, away)) {
                made.axioms.add(df.getOWLDataPropertyDomainAxiom(property, domain));
                note(made, property.getIRI().toString(), domain);
            }
            made.axioms.add(df.getOWLDataPropertyRangeAxiom(property,
                    df.getOWLDatatype(datatypes[random.nextInt(datatypes.length)].getIRI())));
        }
        int individuals = random.nextInt(5);
        for (int i = 0; i < individuals; i++) {
            org.semanticweb.owlapi.model.OWLNamedIndividual one =
                    df.getOWLNamedIndividual(IRI.create(NS + "i" + i));
            declare(made, df, one.getIRI(), df.getOWLDeclarationAxiom(one), "thing " + i, true);
            made.axioms.add(df.getOWLClassAssertionAxiom(
                    classes.get(random.nextInt(classes.size())), one));
        }
        return made;
    }

    /** None, one class, two, a union, owl:Thing, or one class and one not on the board. */
    private static List<OWLClassExpression> domains(Random random, OWLDataFactory df,
            List<OWLClass> classes, OWLClass away) {
        List<OWLClassExpression> domains = new ArrayList<OWLClassExpression>();
        OWLClass one = classes.get(random.nextInt(classes.size()));
        OWLClass two = classes.get(random.nextInt(classes.size()));
        switch (random.nextInt(8)) {
            case 0:
                break;
            case 1:
                domains.add(one);
                domains.add(two);
                break;
            case 2:
                domains.add(df.getOWLObjectUnionOf(one, two));
                break;
            case 3:
                domains.add(df.getOWLThing());
                break;
            case 4:
                domains.add(one);
                domains.add(away);
                break;
            default:
                domains.add(one);
        }
        return domains;
    }

    private static void declare(MadeUp made, OWLDataFactory df, IRI iri, OWLAxiom declaration,
            String label, boolean drawn) {
        made.axioms.add(declaration);
        made.axioms.add(df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), iri,
                df.getOWLLiteral(label)));
        if (drawn) {
            made.on.add(iri.toString());
        }
    }

    /** A named domain on the board, owl:Thing aside, as D1 counts it - worked out by hand here. */
    private static void note(MadeUp made, String property, OWLClassExpression domain) {
        Set<String> classes = made.domains.get(property);
        if (classes == null) {
            classes = new HashSet<String>();
            made.domains.put(property, classes);
        }
        if (!domain.isAnonymous() && !domain.isOWLThing()
                && made.on.contains(domain.asOWLClass().getIRI().toString())) {
            classes.add(domain.asOWLClass().getIRI().toString());
        }
    }

    /**
     * Whether a property stands beside its class, worked out by hand here: it has one domain
     * class on the board, wherever its super-properties stand.
     */
    private static boolean standsBesideItsClass(MadeUp made, String property) {
        Set<String> domains = made.domains.get(property);
        return domains != null && domains.size() == 1;
    }

    /** The made-up ontology, its axioms in an order shuffled by {@code order}, or as made. */
    private static OWLOntology ontology(MadeUp made, long order) throws Exception {
        List<OWLAxiom> axioms = new ArrayList<OWLAxiom>(made.axioms);
        if (order != 0) {
            Collections.shuffle(axioms, new Random(order));
        }
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create("urn:random"));
        for (OWLAxiom axiom : axioms) {
            manager.addAxiom(ontology, axiom);
        }
        return ontology;
    }

    /** The board as arrangeEverything leaves it: the first five classes framed as a pattern. */
    private static SchemaGraph arranged(MadeUp made, OWLOntology ontology,
            List<BoardSections.Section> sections) {
        SchemaGraph graph = new SchemaGraph();
        Projection projection = OntologyProjection.project(ontology, made.on, false);
        List<String> pattern = new ArrayList<String>(made.classes.subList(0, 5));
        Map<String, double[]> sizes = new HashMap<String, double[]>();
        Map<String, String> names = new HashMap<String, String>();
        for (CanvasNode node : projection.getNodes()) {
            if (pattern.contains(node.getId())) {
                String label = Curies.displayLabel(node.getCurie(), node.getLabel(), node.getId());
                sizes.put(node.getId(), graph.fittedSize(label, SchemaStyles.CLASS));
                names.put(node.getId(), label);
            }
        }
        FramedGroup.Placement placement = FramedGroup.plan("pattern", pattern, new CanvasLayout(),
                sizes, OntologyProjection.superclassesAmong(ontology, pattern), names,
                CanvasLayouts.besideTheirClass(projection, graph));
        FramedGroup.withId(placement.getFrame(), PATTERN);
        CanvasLayout layout = new CanvasLayout();
        layout.frames.add(placement.getFrame());
        layout.nodes.putAll(placement.getNodes());
        graph.render(projection, layout);
        sections.addAll(CanvasLayouts.applySections(graph, placement.getNodes().keySet(), false));
        return graph;
    }

    private static double[] box(SchemaGraph graph, String id) {
        mxGeometry at = graph.getModel().getGeometry(graph.getCellForId(id));
        return new double[] {at.getX(), at.getY(), at.getWidth(), at.getHeight()};
    }

    private static boolean within(double[] box, double[] frame) {
        return box[0] >= frame[0] && box[1] >= frame[1] && box[0] + box[2] <= frame[0] + frame[2]
                && box[1] + box[3] <= frame[1] + frame[3];
    }

    /** What is wrong on one board: boxes on boxes, out of their frames, properties astray. */
    private static List<String> wrongWith(long seed, MadeUp made, SchemaGraph graph,
            List<BoardSections.Section> sections) {
        List<String> wrong = new ArrayList<String>();
        List<String> boxes = new ArrayList<String>();
        for (Object cell : graph.getChildVertices(graph.getDefaultParent())) {
            String id = graph.getIdForCell(cell);
            if (!SchemaGraph.isAnnotationId(id)) {
                boxes.add(id);
            }
        }
        for (int i = 0; i < boxes.size(); i++) {
            for (int j = i + 1; j < boxes.size(); j++) {
                double[] a = box(graph, boxes.get(i));
                double[] b = box(graph, boxes.get(j));
                if (a[0] < b[0] + b[2] && b[0] < a[0] + a[2] && a[1] < b[1] + b[3]
                        && b[1] < a[1] + a[3]) {
                    wrong.add(seed + ": " + boxes.get(i) + " on " + boxes.get(j));
                }
            }
        }
        double[] frame = box(graph, PATTERN);
        for (String cls : made.classes.subList(0, 5)) {
            if (!within(box(graph, cls), frame)) {
                wrong.add(seed + ": " + cls + " out of the pattern's frame");
            }
        }
        Map<String, List<String>> besideEach = new TreeMap<String, List<String>>();
        for (String property : made.properties) {
            Set<String> domains = made.domains.get(property);
            double[] at = box(graph, property);
            if (!standsBesideItsClass(made, property)) {
                boolean inASection = false;
                for (BoardSections.Section section : sections) {
                    inASection |= within(at, new double[] {section.x, section.y, section.w,
                        section.h});
                }
                if (!inASection) {
                    wrong.add(seed + ": " + property + " not in a section");
                }
                continue;
            }
            String cls = domains.iterator().next();
            double[] c = box(graph, cls);
            if (at[0] != c[0] + c[2] + PropertiesBeside.GAP || at[1] < c[1]) {
                wrong.add(seed + ": " + property + " at " + at[0] + "," + at[1] + ", not beside "
                        + cls + " at " + c[0] + "," + c[1] + " " + c[2] + "x" + c[3]);
            }
            if (within(c, frame) != within(at, frame)) {
                wrong.add(seed + ": " + property + " not in the frame its class " + cls
                        + " is in");
            }
            List<String> stack = besideEach.get(cls);
            if (stack == null) {
                stack = new ArrayList<String>();
                besideEach.put(cls, stack);
            }
            stack.add(property);
        }
        // The first of each class's properties level with its top, the others one under another.
        for (Map.Entry<String, List<String>> stack : besideEach.entrySet()) {
            double top = Double.MAX_VALUE;
            for (String property : stack.getValue()) {
                top = Math.min(top, box(graph, property)[1]);
            }
            if (top != box(graph, stack.getKey())[1]) {
                wrong.add(seed + ": the first property beside " + stack.getKey() + " at y " + top
                        + ", the class at " + box(graph, stack.getKey())[1]);
            }
        }
        return wrong;
    }

    @Test
    void onRandomBoardsEveryPropertyStandsBesideItsClassAndNoBoxOnAnotherOrOutOfItsFrame()
            throws Exception {
        List<String> wrong = new ArrayList<String>();
        int beside = 0;
        for (long seed = 1; seed <= 40; seed++) {
            MadeUp made = madeUp(seed);
            List<BoardSections.Section> sections = new ArrayList<BoardSections.Section>();
            SchemaGraph graph = arranged(made, ontology(made, 0), sections);
            wrong.addAll(wrongWith(seed, made, graph, sections));
            for (String property : made.properties) {
                beside += standsBesideItsClass(made, property) ? 1 : 0;
            }
        }
        assertTrue(beside > 100, "properties beside their class: " + beside);
        assertEquals(new ArrayList<String>(), wrong.subList(0, Math.min(30, wrong.size())),
                wrong.size() + " wrong");
    }

    @Test
    void theSameOntologyGivesTheSameBoardWhateverOrderItsAxiomsComeIn() throws Exception {
        List<String> moved = new ArrayList<String>();
        for (long seed = 1; seed <= 12; seed++) {
            MadeUp made = madeUp(seed);
            String first = null;
            for (long order = 0; order <= 3; order++) {
                List<BoardSections.Section> sections = new ArrayList<BoardSections.Section>();
                SchemaGraph graph = arranged(made, ontology(made, order * 7919 + seed), sections);
                Map<String, String> where = new TreeMap<String, String>();
                for (Object cell : graph.getChildVertices(graph.getDefaultParent())) {
                    double[] at = box(graph, graph.getIdForCell(cell));
                    where.put(graph.getIdForCell(cell), String.format(Locale.ROOT,
                            "%.0f,%.0f %.0fx%.0f", at[0], at[1], at[2], at[3]));
                }
                StringBuilder all = new StringBuilder(where.toString());
                for (BoardSections.Section section : sections) {
                    all.append(String.format(Locale.ROOT, " %s %.0f,%.0f %.0fx%.0f",
                            section.label, section.x, section.y, section.w, section.h));
                }
                if (first == null) {
                    first = all.toString();
                } else if (!first.equals(all.toString())) {
                    moved.add("seed " + seed + ", order " + order);
                }
            }
        }
        assertEquals(new ArrayList<String>(), moved);
    }
}
