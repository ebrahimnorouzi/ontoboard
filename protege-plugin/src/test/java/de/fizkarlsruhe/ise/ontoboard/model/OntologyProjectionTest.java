package de.fizkarlsruhe.ise.ontoboard.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDataSomeValuesFrom;
import org.semanticweb.owlapi.model.OWLDatatypeRestriction;
import org.semanticweb.owlapi.model.OWLFacetRestriction;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.vocab.OWLFacet;

class OntologyProjectionTest {

    private static final String NS = "http://example.org/tiny#";
    private static final String NS2 = "http://example.org/restrictions#";
    private static final String XSD_STRING = "http://www.w3.org/2001/XMLSchema#string";

    private OWLOntology ontology;
    private OWLOntology restrictionsOntology;

    @BeforeEach
    void loadFixture() throws Exception {
        org.semanticweb.owlapi.model.OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        ontology = manager.loadOntologyFromOntologyDocument(new File("src/test/resources/fixture-tiny.ttl"));
        restrictionsOntology = manager.loadOntologyFromOntologyDocument(
                new File("src/test/resources/fixture-restrictions.ttl"));
    }

    private static Set<String> iris(String... localNames) {
        return Arrays.stream(localNames).map(n -> NS + n).collect(Collectors.toCollection(HashSet::new));
    }

    private static Set<String> restrictionIris(String... localNames) {
        return Arrays.stream(localNames).map(n -> NS2 + n).collect(Collectors.toCollection(HashSet::new));
    }

    private static Set<String> exact(String... fullIris) {
        return new HashSet<>(Arrays.asList(fullIris));
    }

    @Test
    void projectsOnlyEntitiesThatAreOnTheCanvas() {
        Projection p = OntologyProjection.project(ontology, iris("Person"));
        assertEquals(1, p.getNodes().size());
        assertEquals(NS + "Person", p.getNodes().get(0).getId());
        assertEquals(NodeKind.CLASS, p.getNodes().get(0).getKind());
    }

    @Test
    void projectsSubClassOfBetweenTwoOnCanvasClasses() {
        Projection p = OntologyProjection.project(ontology, iris("Person", "Agent"));
        assertEquals(2, p.getNodes().size());
        assertEquals(1, p.getEdges().size());

        CanvasEdge edge = p.getEdges().get(0);
        assertEquals(CanvasEdge.Kind.SUBCLASS, edge.getKind());
        assertEquals(NS + "Person", edge.getSourceId());
        assertEquals(NS + "Agent", edge.getTargetId());
    }

    @Test
    void omitsSubClassOfWhenTheSuperClassIsNotOnTheCanvas() {
        Projection p = OntologyProjection.project(ontology, iris("Person"));
        assertTrue(p.getEdges().isEmpty());
    }

    /** Ontologies written by the retired web app used rdfs:domain/rdfs:range for edges. */
    @Test
    void projectsLegacyDomainRangePairsAsAPropertyEdge() {
        Projection p = OntologyProjection.project(ontology, iris("Person", "Organization"));
        assertEquals(1, p.getEdges().size());

        CanvasEdge edge = p.getEdges().get(0);
        assertEquals(CanvasEdge.Kind.OBJECT_PROPERTY, edge.getKind());
        assertEquals(NS + "Person", edge.getSourceId());
        assertEquals(NS + "Organization", edge.getTargetId());
        assertEquals("worksFor", edge.getLabel());
    }

    @Test
    void projectsClassAssertionAsATypeEdge() {
        Projection p = OntologyProjection.project(ontology, iris("Person", "alice"));

        CanvasNode alice = p.getNodes().stream()
                .filter(n -> n.getId().equals(NS + "alice")).findFirst()
                .orElseThrow(() -> new AssertionError("alice node not found"));
        assertEquals(NodeKind.INDIVIDUAL, alice.getKind());

        assertEquals(1, p.getEdges().size());
        assertEquals(CanvasEdge.Kind.TYPE, p.getEdges().get(0).getKind());
        assertEquals(NS + "alice", p.getEdges().get(0).getSourceId());
        assertEquals(NS + "Person", p.getEdges().get(0).getTargetId());
    }

    @Test
    void projectsExistentialRestrictionAsAnObjectPropertyEdge() {
        Projection p = OntologyProjection.project(restrictionsOntology,
                restrictionIris("Person", "Organization"));
        assertEquals(2, p.getNodes().size());
        assertEquals(1, p.getEdges().size());

        CanvasEdge edge = p.getEdges().get(0);
        assertEquals(CanvasEdge.Kind.OBJECT_PROPERTY, edge.getKind());
        assertEquals(NS2 + "Person", edge.getSourceId());
        assertEquals(NS2 + "Organization", edge.getTargetId());
        assertEquals("worksFor", edge.getLabel());
    }

    @Test
    void projectsUniversalRestrictionWithOnlyQualifierInLabel() {
        Projection p = OntologyProjection.project(restrictionsOntology,
                restrictionIris("Manager", "Person"));
        assertEquals(1, p.getEdges().size());

        CanvasEdge edge = p.getEdges().get(0);
        assertEquals(CanvasEdge.Kind.OBJECT_PROPERTY, edge.getKind());
        assertEquals(NS2 + "Manager", edge.getSourceId());
        assertEquals(NS2 + "Person", edge.getTargetId());
        assertEquals("manages (only)", edge.getLabel());
    }

    @Test
    void projectsPlainDataRestrictionAsADataPropertyEdgeWithADatatypeNode() {
        Projection p = OntologyProjection.project(restrictionsOntology, restrictionIris("Student"));

        assertEquals(2, p.getNodes().size());
        CanvasNode datatypeNode = p.getNodes().stream()
                .filter(n -> n.getKind() == NodeKind.DATATYPE).findFirst()
                .orElseThrow(() -> new AssertionError("no datatype node projected"));
        assertEquals(XSD_STRING, datatypeNode.getId());
        assertEquals("string", datatypeNode.getLabel());

        assertEquals(1, p.getEdges().size());
        CanvasEdge edge = p.getEdges().get(0);
        assertEquals(CanvasEdge.Kind.DATA_PROPERTY, edge.getKind());
        assertEquals(NS2 + "Student", edge.getSourceId());
        assertEquals(XSD_STRING, edge.getTargetId());
        assertEquals("hasName", edge.getLabel());
    }

    @Test
    void dedupesDatatypeNodeWhenTwoClassesShareARange() {
        Projection p = OntologyProjection.project(restrictionsOntology,
                restrictionIris("Student", "Contact"));

        // Student and Contact both restrict a property to xsd:string: exactly one DATATYPE
        // node must be projected, not two, even though two edges point at it.
        assertEquals(3, p.getNodes().size());
        long datatypeNodeCount = p.getNodes().stream().filter(n -> n.getKind() == NodeKind.DATATYPE).count();
        assertEquals(1, datatypeNodeCount);

        assertEquals(2, p.getEdges().size());
        assertTrue(p.getEdges().stream().allMatch(e -> e.getKind() == CanvasEdge.Kind.DATA_PROPERTY));
        assertTrue(p.getEdges().stream().allMatch(e -> e.getTargetId().equals(XSD_STRING)));
    }

    /**
     * End-to-end regression check for the bug where a facet-restricted data range (filler is
     * an OWLDatatypeRestriction, not a plain OWLDatatype) hit a {@code return} instead of a
     * {@code continue} and silently aborted the scan of every remaining SubClassOf axiom.
     *
     * <p>This exercises the real parse-and-project path and has standalone value as an
     * end-to-end check, but it is NOT the sole guard against the regression: it only fails
     * because "Left"/"Right" happens to land after Employee's axiom in this particular
     * ontology's {@code Set} iteration order (verified empirically - "A"/"B" did not
     * reproduce it). That iteration order is an unspecified implementation detail, so a
     * future edit to this fixture could silently stop exercising the bug. See
     * {@link #facetRestrictedDataRangeDoesNotSuppressOtherSubClassEdgesRegardlessOfAxiomOrder()}
     * for the order-independent guard that does not have this weakness.
     */
    @Test
    void facetRestrictedDataRangeDoesNotSuppressOtherSubClassEdges() {
        Projection p = OntologyProjection.project(restrictionsOntology,
                restrictionIris("Employee", "Left", "Right"));

        boolean hasLeftToRight = p.getEdges().stream().anyMatch(e -> e.getKind() == CanvasEdge.Kind.SUBCLASS
                && e.getSourceId().equals(NS2 + "Left") && e.getTargetId().equals(NS2 + "Right"));
        assertTrue(hasLeftToRight, "SubClassOf(Left, Right) must survive even though Employee has an "
                + "unhandled facet-restricted data range axiom in the same axiom set");
    }

    /**
     * Order-independent regression test for the same bug as
     * {@link #facetRestrictedDataRangeDoesNotSuppressOtherSubClassEdges()}, but immune to Set
     * iteration order: it calls the package-private
     * {@code OntologyProjection.collectSubClassEdges(OWLOntology, Collection, Set, List, List)}
     * overload directly with a hand-built {@link LinkedHashSet}, whose iteration order is
     * insertion order by contract. The facet-restricted axiom is inserted FIRST, so if the
     * unsupported-data-range branch ever regresses back to {@code return} instead of
     * {@code continue}, this test fails no matter how the axioms were built, no matter what
     * fixture exists, and no matter what future edits are made to any ontology file.
     */
    @Test
    void facetRestrictedDataRangeDoesNotSuppressOtherSubClassEdgesRegardlessOfAxiomOrder()
            throws Exception {
        OWLDataFactory factory = OWLManager.getOWLDataFactory();

        OWLClass employee = factory.getOWLClass(IRI.create(NS2 + "OrderProbeEmployee"));
        OWLClass left = factory.getOWLClass(IRI.create(NS2 + "OrderProbeLeft"));
        OWLClass right = factory.getOWLClass(IRI.create(NS2 + "OrderProbeRight"));
        OWLDataProperty hasSalary = factory.getOWLDataProperty(IRI.create(NS2 + "orderProbeHasSalary"));

        OWLFacetRestriction minZero = factory.getOWLFacetRestriction(OWLFacet.MIN_INCLUSIVE, 0);
        OWLDatatypeRestriction facetRestrictedInteger =
                factory.getOWLDatatypeRestriction(factory.getIntegerOWLDatatype(), minZero);
        OWLDataSomeValuesFrom facetRange = factory.getOWLDataSomeValuesFrom(hasSalary, facetRestrictedInteger);

        OWLSubClassOfAxiom facetAxiom = factory.getOWLSubClassOfAxiom(employee, facetRange);
        OWLSubClassOfAxiom plainAxiom = factory.getOWLSubClassOfAxiom(left, right);

        // LinkedHashSet's iteration order is insertion order, by contract - not a hope, a
        // guarantee. The facet-restricted axiom goes in FIRST, deliberately.
        Set<OWLSubClassOfAxiom> axioms = new LinkedHashSet<>();
        axioms.add(facetAxiom);
        axioms.add(plainAxiom);

        Set<String> on = new HashSet<>(Arrays.asList(
                employee.getIRI().toString(), left.getIRI().toString(), right.getIRI().toString()));
        List<CanvasNode> nodes = new ArrayList<>();
        List<CanvasEdge> edges = new ArrayList<>();

        OWLOntology host = OWLManager.createOWLOntologyManager()
                .createOntology(IRI.create("http://example.org/order-probe"));
        OntologyProjection.collectSubClassEdges(host, axioms, on, nodes, edges);

        boolean hasLeftToRight = edges.stream().anyMatch(e -> e.getKind() == CanvasEdge.Kind.SUBCLASS
                && e.getSourceId().equals(left.getIRI().toString())
                && e.getTargetId().equals(right.getIRI().toString()));
        assertTrue(hasLeftToRight, "SubClassOf(Left, Right) must survive even when the "
                + "facet-restricted axiom is guaranteed to be visited strictly first");
    }

    @Test
    void localNameStripsTrailingHashBeforeFallingBackToSlashSegment() {
        Projection p = OntologyProjection.project(restrictionsOntology,
                exact("http://example.org/restrictions/thing#"));
        assertEquals(1, p.getNodes().size());
        assertEquals("thing", p.getNodes().get(0).getLabel());
    }

    @Test
    void localNameStripsTrailingSlashBeforeFallingBackToSlashSegment() {
        Projection p = OntologyProjection.project(restrictionsOntology,
                exact("http://example.org/restrictions/container/"));
        assertEquals(1, p.getNodes().size());
        assertEquals("container", p.getNodes().get(0).getLabel());
    }
    // ---------- terms carrying an editorial note ----------

    /**
     * The reason to write a note is that somebody comes back to it, and a board of forty terms
     * gives no clue which of them anybody has said anything about.
     */
    @Test
    void aClassWithAnEditorNoteIsMarked() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create("http://example.org/o"));
        OWLDataFactory factory = manager.getOWLDataFactory();
        OWLClass person = factory.getOWLClass(IRI.create("http://example.org/o#Person"));
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(person));
        manager.applyChanges(de.fizkarlsruhe.ise.ontoboard.prov.EditorNotes.addNote(ontology,
                person.getIRI(), de.fizkarlsruhe.ise.ontoboard.prov.EditorNotes.Kind.EDITOR,
                "the definition needs work"));

        Projection projection = OntologyProjection.project(ontology,
                new java.util.HashSet<String>(java.util.Arrays.asList(
                        "http://example.org/o#Person")));

        assertEquals(1, projection.getNodes().size());
        assertTrue(projection.getNodes().get(0).hasNote(),
                "a term with an editor note should be marked on the board");
    }

    @Test
    void aClassWithNoNoteIsNotMarked() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create("http://example.org/o"));
        OWLDataFactory factory = manager.getOWLDataFactory();
        OWLClass person = factory.getOWLClass(IRI.create("http://example.org/o#Person"));
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(person));
        manager.addAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(
                factory.getRDFSComment(), person.getIRI(),
                factory.getOWLLiteral("an ordinary comment, not an editorial note")));

        Projection projection = OntologyProjection.project(ontology,
                new java.util.HashSet<String>(java.util.Arrays.asList(
                        "http://example.org/o#Person")));

        assertFalse(projection.getNodes().get(0).hasNote(),
                "rdfs:comment is not an editorial note and must not mark the term");
    }

    /**
     * The mark is read from the ontology on every build rather than stored, so removing a note
     * unmarks the term without anything else having to remember it did.
     */
    @Test
    void removingTheNoteUnmarksTheTerm() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create("http://example.org/o"));
        OWLDataFactory factory = manager.getOWLDataFactory();
        OWLClass person = factory.getOWLClass(IRI.create("http://example.org/o#Person"));
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(person));
        java.util.Set<String> onCanvas = new java.util.HashSet<String>(
                java.util.Arrays.asList("http://example.org/o#Person"));
        manager.applyChanges(de.fizkarlsruhe.ise.ontoboard.prov.EditorNotes.addNote(ontology,
                person.getIRI(), de.fizkarlsruhe.ise.ontoboard.prov.EditorNotes.Kind.EDITOR,
                "temporary"));
        assertTrue(OntologyProjection.project(ontology, onCanvas).getNodes().get(0).hasNote());

        manager.applyChanges(de.fizkarlsruhe.ise.ontoboard.prov.EditorNotes.removeNote(ontology,
                person.getIRI(), de.fizkarlsruhe.ise.ontoboard.prov.EditorNotes.Kind.EDITOR,
                "temporary"));

        assertFalse(OntologyProjection.project(ontology, onCanvas).getNodes().get(0).hasNote());
    }

    /**
     * A note is a fact about the ontology at the moment of drawing, not part of what makes a node
     * that node. Folding it into identity would make a node stop equalling itself across an
     * annotation edit, which the canvas's own selection handling relies on.
     */
    @Test
    void aNoteDoesNotChangeWhichNodeANodeIs() {
        CanvasNode plain = new CanvasNode("http://example.org/o#Person", NodeKind.CLASS, "Person");
        CanvasNode marked = new CanvasNode("http://example.org/o#Person", NodeKind.CLASS,
                "Person", java.util.Collections.singletonList("check the definition"));

        assertEquals(plain, marked);
        assertEquals(plain.hashCode(), marked.hashCode());
    }


    // ---------- imported terms ----------

    /**
     * An ontology that imports another, both in one manager so the import resolves.
     *
     * <p>Built rather than loaded from a fixture because the interesting part is the import
     * declaration, and a fixture pair on disk would put the thing under test in two files nobody reads
     * while testing.
     */
    private OWLOntology editFileImporting(String importedNs, String importedClass) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory factory = manager.getOWLDataFactory();

        IRI importedIri = IRI.create("http://example.org/imported");
        OWLOntology upstream = manager.createOntology(importedIri);
        manager.addAxiom(upstream, factory.getOWLDeclarationAxiom(
                factory.getOWLClass(IRI.create(importedNs + importedClass))));

        OWLOntology edit = manager.createOntology(IRI.create("http://example.org/edit"));
        manager.applyChange(new org.semanticweb.owlapi.model.AddImport(edit,
                factory.getOWLImportsDeclaration(importedIri)));
        manager.addAxiom(edit, factory.getOWLDeclarationAxiom(
                factory.getOWLClass(IRI.create("http://example.org/edit#Mine"))));
        return edit;
    }

    /**
     * The defect this pins: dragging an imported term onto the board did nothing visible.
     *
     * <p>{@code project} looked only at the edit file's own signature, so no node was drawn - and
     * {@code pruneStaleMembers} then deleted the sidecar entry, so the attempt left no trace either.
     * For an ODK project, which is what this plugin scaffolds, most terms a curator refers to are
     * imported, so the canvas could not draw the ordinary case.
     */
    @Test
    void anImportedClassOnTheBoardIsDrawn() throws Exception {
        String importedNs = "http://purl.obolibrary.org/obo/";
        OWLOntology edit = editFileImporting(importedNs, "BFO_0000002");

        Projection projection = OntologyProjection.project(edit,
                new HashSet<String>(Arrays.asList(importedNs + "BFO_0000002")));

        assertEquals(1, projection.getNodes().size(), projection.getNodes().toString());
        assertEquals(importedNs + "BFO_0000002", projection.getNodes().get(0).getId());
    }

    @Test
    void anImportedTermIsMarkedImportedAndALocalOneIsNot() throws Exception {
        String importedNs = "http://purl.obolibrary.org/obo/";
        OWLOntology edit = editFileImporting(importedNs, "BFO_0000002");

        Projection projection = OntologyProjection.project(edit, new HashSet<String>(
                Arrays.asList(importedNs + "BFO_0000002", "http://example.org/edit#Mine")));

        for (CanvasNode node : projection.getNodes()) {
            if (node.getId().startsWith(importedNs)) {
                assertTrue(node.isImported(), node.getId() + " came from an import");
            } else {
                assertFalse(node.isImported(), node.getId() + " is declared in the edit file");
            }
        }
        assertEquals(2, projection.getNodes().size());
    }

    /**
     * "Add all" stays local, which is the other half of the decision.
     *
     * <p>Widening the projection to imports is safe because it is gated on membership. Widening this
     * would not be: an ODK project importing BFO, ChEBI and the relations ontology would put tens of
     * thousands of terms on the board on one click, and that is not a diagram.
     */
    @Test
    void addAllStillOffersOnlyTheEditFilesOwnTerms() throws Exception {
        String importedNs = "http://purl.obolibrary.org/obo/";
        OWLOntology edit = editFileImporting(importedNs, "BFO_0000002");

        Set<String> offered = OntologyProjection.everythingWorthShowing(edit);

        assertTrue(offered.contains("http://example.org/edit#Mine"), offered.toString());
        assertFalse(offered.contains(importedNs + "BFO_0000002"),
                "Add all must not pull in an import's terms: " + offered);
    }

    /** The search index does look into imports, so a term you have not drawn can still be found. */
    @Test
    void theSearchIndexSeesImportedTerms() throws Exception {
        String importedNs = "http://purl.obolibrary.org/obo/";
        OWLOntology edit = editFileImporting(importedNs, "BFO_0000002");

        List<String> withImports = new ArrayList<String>();
        for (CanvasNode term : OntologyProjection.everyTermWorthShowing(edit,
                org.semanticweb.owlapi.model.parameters.Imports.INCLUDED)) {
            withImports.add(term.getId());
        }
        List<String> localOnly = new ArrayList<String>();
        for (CanvasNode term : OntologyProjection.everyTermWorthShowing(edit)) {
            localOnly.add(term.getId());
        }

        assertTrue(withImports.contains(importedNs + "BFO_0000002"), withImports.toString());
        assertFalse(localOnly.contains(importedNs + "BFO_0000002"), localOnly.toString());
    }

    /**
     * A hierarchy edge between two imported terms is drawn.
     *
     * <p>The axiom lives in the import, not the edit file, so an EXCLUDED query would find the two
     * nodes and no line between them - a diagram that draws BFO's classes as unrelated boxes, which is
     * worse than not drawing them.
     */
    @Test
    void anAxiomInsideAnImportIsDrawnBetweenTwoImportedTerms() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory factory = manager.getOWLDataFactory();
        IRI importedIri = IRI.create("http://example.org/imported");
        OWLOntology upstream = manager.createOntology(importedIri);
        OWLClass parent = factory.getOWLClass(IRI.create("http://example.org/up#Parent"));
        OWLClass child = factory.getOWLClass(IRI.create("http://example.org/up#Child"));
        manager.addAxiom(upstream, factory.getOWLDeclarationAxiom(parent));
        manager.addAxiom(upstream, factory.getOWLDeclarationAxiom(child));
        manager.addAxiom(upstream, factory.getOWLSubClassOfAxiom(child, parent));

        OWLOntology edit = manager.createOntology(IRI.create("http://example.org/edit"));
        manager.applyChange(new org.semanticweb.owlapi.model.AddImport(edit,
                factory.getOWLImportsDeclaration(importedIri)));

        Projection projection = OntologyProjection.project(edit, new HashSet<String>(Arrays.asList(
                "http://example.org/up#Parent", "http://example.org/up#Child")));

        assertEquals(1, projection.getEdges().size(), projection.getEdges().toString());
    }

    // ---------- what "Add all" offers ----------

    /**
     * The defect this pins: "Add all" collected classes and individuals only, so the one bulk
     * gesture in the tool silently withheld two of the four kinds the canvas can draw. Press it on
     * an ontology built around its object properties and you got the class tree and no properties,
     * with nothing saying why - and the property hierarchy the canvas can render was unreachable
     * except by dragging each property across by hand.
     */
    @Test
    void addAllOffersPropertiesAsWellAsClassesAndIndividuals() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create("http://example.org/o"));
        OWLDataFactory factory = manager.getOWLDataFactory();
        String ns = "http://example.org/o#";

        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(
                factory.getOWLClass(IRI.create(ns + "Pizza"))));
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(
                factory.getOWLNamedIndividual(IRI.create(ns + "margherita"))));
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(
                factory.getOWLObjectProperty(IRI.create(ns + "hasTopping"))));
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(
                factory.getOWLDataProperty(IRI.create(ns + "hasCalories"))));

        java.util.Set<String> offered = OntologyProjection.everythingWorthShowing(ontology);

        assertTrue(offered.contains(ns + "Pizza"), offered.toString());
        assertTrue(offered.contains(ns + "margherita"), offered.toString());
        assertTrue(offered.contains(ns + "hasTopping"),
                "object properties were left off the board: " + offered);
        assertTrue(offered.contains(ns + "hasCalories"),
                "data properties were left off the board: " + offered);
        assertEquals(4, offered.size(), offered.toString());
    }

    /**
     * Datatypes are deliberately not offered: they earn a node only where a data property edge
     * puts them there, and xsd:string sitting alone on a board attached to nothing is noise.
     */
    @Test
    void addAllDoesNotOfferBareDatatypes() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create("http://example.org/o"));
        OWLDataFactory factory = manager.getOWLDataFactory();
        manager.addAxiom(ontology, factory.getOWLDataPropertyRangeAxiom(
                factory.getOWLDataProperty(IRI.create("http://example.org/o#hasCalories")),
                factory.getOWLDatatype(IRI.create(
                        "http://www.w3.org/2001/XMLSchema#integer"))));

        for (String iri : OntologyProjection.everythingWorthShowing(ontology)) {
            assertFalse(iri.startsWith("http://www.w3.org/2001/XMLSchema#"), iri);
        }
    }

    @Test
    void nothingToOfferIsNotAnAnswer() {
        assertTrue(OntologyProjection.everythingWorthShowing(null).isEmpty());
    }

    /**
     * Projecting nothing is empty, not a crash.
     *
     * <p>Protege has no active ontology while it is switching between them and before the first
     * one loads, and the canvas renders on construction. Until 1.74.0 that threw a
     * NullPointerException out of the view's constructor, and Protege replaced the Schema Canvas
     * with "An error occurred whilst creating the view" for the rest of the session.
     */
    @Test
    void noOntologyProjectsToAnEmptyBoard() {
        Projection projection = OntologyProjection.project(null,
                java.util.Collections.singleton("http://example.org/o#Thing"));

        assertTrue(projection.getNodes().isEmpty());
        assertTrue(projection.getEdges().isEmpty());
    }

    /** And neither does a missing membership set, which is the other half of the same call. */
    @Test
    void noMembershipProjectsToAnEmptyBoard() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create("http://example.org/o"));

        Projection projection = OntologyProjection.project(ontology, null);

        assertTrue(projection.getNodes().isEmpty());
        assertTrue(projection.getEdges().isEmpty());
    }
}
