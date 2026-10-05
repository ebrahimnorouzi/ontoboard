package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * The owl:imports structure as a graph.
 *
 * <p>What the imports table cannot show: that one module is imported by several, and that the
 * structure has a shape at all. The table lists the active ontology's direct imports, one row
 * each.
 */
class ImportsGraphTest {

    private static OWLOntology ontology(OWLOntologyManager manager, String iri) throws Exception {
        return manager.createOntology(IRI.create(iri));
    }

    private static void imports(OWLOntologyManager manager, OWLOntology from, String target) {
        manager.applyChange(new org.semanticweb.owlapi.model.AddImport(from,
                manager.getOWLDataFactory().getOWLImportsDeclaration(IRI.create(target))));
    }

    private static List<String> names(List<ImportsGraph.Node> nodes) {
        List<String> names = new ArrayList<String>();
        for (ImportsGraph.Node node : nodes) {
            names.add(node.getShortName());
        }
        return names;
    }

    /** A chain is walked to the end, with depth counted from the root. */
    @Test
    void theClosureIsWalked() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology root = ontology(manager, "http://x/root.owl");
        OWLOntology middle = ontology(manager, "http://x/middle.owl");
        ontology(manager, "http://x/upstream.owl");
        imports(manager, root, "http://x/middle.owl");
        imports(manager, middle, "http://x/upstream.owl");

        ImportsGraph graph = ImportsGraph.of(root, manager);

        assertEquals(3, graph.getNodes().size(), names(graph.getNodes()).toString());
        assertEquals(2, graph.getEdges().size());
        assertEquals(0, graph.getNodes().get(0).getDepth());
        assertTrue(graph.getNodes().get(0).isRoot());
        assertEquals(2, depthOf(graph, "http://x/upstream.owl"));
        assertTrue(graph.getUnresolved().isEmpty());
    }

    /**
     * A module several others import is named as shared.
     *
     * <p>The structural fact the table cannot carry, and the reason to draw this at all.
     */
    @Test
    void aSharedModuleIsIdentified() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology root = ontology(manager, "http://x/root.owl");
        OWLOntology one = ontology(manager, "http://x/one.owl");
        OWLOntology two = ontology(manager, "http://x/two.owl");
        ontology(manager, "http://x/mirror.owl");
        imports(manager, root, "http://x/one.owl");
        imports(manager, root, "http://x/two.owl");
        imports(manager, one, "http://x/mirror.owl");
        imports(manager, two, "http://x/mirror.owl");

        ImportsGraph graph = ImportsGraph.of(root, manager);

        assertEquals(1, graph.shared().size(), graph.shared().toString());
        assertEquals("http://x/mirror.owl", graph.shared().get(0).toString());
        assertEquals(Integer.valueOf(2), graph.importerCounts().get(IRI.create("http://x/mirror.owl")));
        assertEquals(4, graph.getNodes().size(), "the mirror appears once, not twice");
    }

    /**
     * An import cycle terminates.
     *
     * <p>OWL permits one, and the project that has grown an accidental cycle is exactly the one
     * whose owner would open an imports graph.
     */
    @Test
    void aCycleDoesNotRunForever() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology a = ontology(manager, "http://x/a.owl");
        OWLOntology b = ontology(manager, "http://x/b.owl");
        imports(manager, a, "http://x/b.owl");
        imports(manager, b, "http://x/a.owl");

        ImportsGraph graph = ImportsGraph.of(a, manager);

        assertEquals(2, graph.getNodes().size());
        assertEquals(2, graph.getEdges().size(), "both directions are drawn");
    }

    /**
     * An unresolved import is a node, not an omission.
     *
     * <p>Leaving it out would draw the project as importing less than it declares, which is the
     * opposite of what somebody opening this wants to know.
     */
    @Test
    void anUnresolvedImportIsStillDrawn() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology root = ontology(manager, "http://x/root.owl");
        imports(manager, root, "http://nowhere.example/missing.owl");

        ImportsGraph graph = ImportsGraph.of(root, manager);

        assertEquals(2, graph.getNodes().size());
        assertEquals(1, graph.getUnresolved().size());
        assertFalse(nodeFor(graph, "http://nowhere.example/missing.owl").isResolved());
        assertTrue(nodeFor(graph, "http://x/root.owl").isResolved());
    }

    /** An ontology that imports nothing has no structure to draw. */
    @Test
    void nothingImportedIsEmpty() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();

        ImportsGraph graph = ImportsGraph.of(ontology(manager, "http://x/alone.owl"), manager);

        assertTrue(graph.isEmpty());
        assertTrue(graph.shared().isEmpty());
    }

    /** No ontology is not a crash. */
    @Test
    void noOntologyIsEmpty() {
        assertTrue(ImportsGraph.of(null, null).isEmpty());
        assertTrue(ImportsGraph.of(null, null).getNodes().isEmpty());
    }

    // ---------- labels ----------

    /** A box three centimetres wide needs the last segment, not the IRI. */
    @Test
    void theShortNameIsTheLastSegment() {
        assertEquals("chebi_import", ImportsGraph.shortNameOf(
                IRI.create("http://purl.obolibrary.org/obo/mwo/imports/chebi_import.owl")));
        assertEquals("DUL", ImportsGraph.shortNameOf(
                IRI.create("http://www.ontologydesignpatterns.org/ont/dul/DUL.owl")));
        assertEquals("time", ImportsGraph.shortNameOf(IRI.create("http://www.w3.org/2006/time#")));
        assertEquals("(no IRI)", ImportsGraph.shortNameOf(null));
    }

    /** An IRI with no segment at all keeps something to show. */
    @Test
    void aBareIriStillHasALabel() {
        assertFalse(ImportsGraph.shortNameOf(IRI.create("urn:x")).isEmpty());
    }

    // ---------- DOT ----------

    /** The DOT names every node and every edge. */
    @Test
    void theDotIsComplete() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology root = ontology(manager, "http://x/root.owl");
        ontology(manager, "http://x/one.owl");
        imports(manager, root, "http://x/one.owl");

        String dot = ImportsGraph.of(root, manager).toDot();

        assertTrue(dot.startsWith("digraph imports {"), dot);
        assertTrue(dot.contains("\"root\""), dot);
        assertTrue(dot.contains("\"one\""), dot);
        assertTrue(dot.contains("n0 -> n1;"), dot);
        assertTrue(dot.trim().endsWith("}"));
    }

    /** An unresolved node is marked in the DOT as well as on screen. */
    @Test
    void theDotMarksWhatDidNotResolve() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology root = ontology(manager, "http://x/root.owl");
        imports(manager, root, "http://nowhere.example/missing.owl");

        assertTrue(ImportsGraph.of(root, manager).toDot().contains("dashed"));
    }

    /** A quote in a label cannot end the string early. */
    @Test
    void quotingSurvivesAwkwardText() {
        assertEquals("\"a \\\"b\\\" c\"", ImportsGraph.quote("a \"b\" c"));
        assertEquals("\"back\\\\slash\"", ImportsGraph.quote("back\\slash"));
        assertEquals("\"\"", ImportsGraph.quote(null));
    }

    private static int depthOf(ImportsGraph graph, String iri) {
        return nodeFor(graph, iri).getDepth();
    }

    private static ImportsGraph.Node nodeFor(ImportsGraph graph, String iri) {
        for (ImportsGraph.Node node : graph.getNodes()) {
            if (node.getIri().toString().equals(iri)) {
                return node;
            }
        }
        throw new AssertionError(iri + " not among " + names(graph.getNodes()));
    }
}
