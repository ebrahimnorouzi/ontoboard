package de.fizkarlsruhe.ise.ontoboard.views;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.RemoveImport;

/**
 * Edit &gt; Undo of a pattern import takes its frame off the board, with the terms only it put
 * there (1.135.1, Windows study rehearsal). A tester imported the wrong pattern in T5 and undid
 * it: the frame stayed until the next import re-arranged the board, and three terms it had put
 * there - publication, publishing process, is specified output of - stayed to the end of the study.
 */
class UndonePatternImportTest {

    private static final String PUB = "http://purl.obolibrary.org/obo/IAO_0000311";
    private static final String PROCESS = "http://purl.obolibrary.org/obo/IAO_0000444";
    private static final String MINE = "https://example.org/eggboil/EGGBOIL_0001000";

    private CanvasLayout board;
    private final Function<String, Collection<String>> termsOf =
            id -> "publication".equals(id) ? Arrays.asList(PUB, PROCESS)
                    : Collections.<String>emptyList();

    @BeforeEach
    void boardAfterTheWrongImport() {
        board = new CanvasLayout();
        CanvasLayout.FrameLayout frame = new CanvasLayout.FrameLayout();
        frame.id = "ontoboard-frame-pattern-publication";
        frame.label = "Publication";
        frame.pattern = "publication";
        frame.x = 0;
        frame.y = 0;
        frame.w = 600;
        frame.h = 300;
        board.frames.add(frame);
        CanvasLayout.NoteLayout note = new CanvasLayout.NoteLayout();
        note.id = SchemaCanvasView.SHARED_NOTE_PREFIX + "publication";
        note.x = 20;
        note.y = 20;
        board.notes.add(note);
        board.onCanvas.addAll(Arrays.asList(PUB, PROCESS, MINE));
        board.nodes.put(PUB, new CanvasLayout.NodeLayout(40, 160));
        // Brought by the pattern, standing outside its frame.
        board.nodes.put(PROCESS, new CanvasLayout.NodeLayout(900, 600));
        board.nodes.put(MINE, new CanvasLayout.NodeLayout(900, 100));
    }

    @Test
    void anUndonePatternsFrameGoesWithTheTermsOnlyItBrought() {
        Set<String> shown = new HashSet<String>(Collections.singletonList(MINE));
        List<String> gone = SchemaCanvasView.unframePatternsNotInUse(board,
                Collections.<String>emptySet(), shown, termsOf);
        assertEquals(Collections.singletonList("Publication"), gone);
        assertTrue(board.frames.isEmpty(), "the frame went");
        assertTrue(board.notes.isEmpty(), "and the note it carried");
        assertEquals(Collections.singletonList(MINE), board.onCanvas);
        assertFalse(board.nodes.containsKey(PUB));
        assertFalse(board.nodes.containsKey(PROCESS));
    }

    @Test
    void aPatternStillImportedKeepsItsFrame() {
        List<String> gone = SchemaCanvasView.unframePatternsNotInUse(board,
                new HashSet<String>(Collections.singletonList("publication")), null, termsOf);
        assertTrue(gone.isEmpty());
        assertEquals(1, board.frames.size());
    }

    @Test
    void onABoardOfHandPickedTermsOnlyTheFrameAndItsNoteGo() {
        List<String> gone = SchemaCanvasView.unframePatternsNotInUse(board,
                Collections.<String>emptySet(), null, termsOf);
        assertEquals(Collections.singletonList("Publication"), gone);
        assertTrue(board.frames.isEmpty());
        assertEquals(Arrays.asList(PUB, PROCESS, MINE), board.onCanvas,
                "a hand-picked board keeps what somebody put on it");
    }

    private static final String EGG = "https://example.org/eggboil/";
    private static final String OBJECT =
            "http://www.ontologydesignpatterns.org/cp/owl/componency.owl#Object";
    private static final String CONCEPT =
            "http://www.ontologydesignpatterns.org/cp/owl/classification.owl#Concept";

    /** A pattern's frame, holding one class. */
    private static void frame(CanvasLayout board, String pattern, String label, double y,
            String term) {
        CanvasLayout.FrameLayout frame = new CanvasLayout.FrameLayout();
        frame.id = "ontoboard-frame-pattern-" + pattern;
        frame.label = label;
        frame.pattern = pattern;
        frame.x = 0;
        frame.y = y;
        frame.w = 600;
        frame.h = 300;
        board.frames.add(frame);
        board.onCanvas.add(term);
        board.nodes.put(term, new CanvasLayout.NodeLayout(40, y + 60));
    }

    /**
     * An ontology that imports Componency's module and holds Classification's Concept itself -
     * added with "Copy the axioms straight into this ontology" - and a board framing both.
     */
    private final OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
    private final org.semanticweb.owlapi.model.OWLDataFactory df = manager.getOWLDataFactory();

    private OWLOntology importingComponencyWithClassificationCopiedIn(CanvasLayout board)
            throws Exception {
        OWLOntology edit = manager.createOntology(IRI.create(EGG + "eggboil.owl"));
        OWLOntology componency = manager.createOntology(
                IRI.create(EGG + "eggboil/imports/componency_import.owl"));
        manager.addAxiom(componency, df.getOWLDeclarationAxiom(df.getOWLClass(IRI.create(OBJECT))));
        manager.applyChange(new AddImport(edit,
                df.getOWLImportsDeclaration(componency.getOntologyID().getOntologyIRI().get())));
        manager.addAxiom(edit, df.getOWLDeclarationAxiom(df.getOWLClass(IRI.create(CONCEPT))));
        frame(board, "componency", "Componency Pattern", 0, OBJECT);
        frame(board, "classification", "Classification Pattern", 400, CONCEPT);
        return edit;
    }

    /**
     * Edit &gt; Undo of an import that is no pattern's leaves every pattern's frame (1.135.1
     * review). With one pattern imported, the patterns the ontology uses were taken to be the
     * imported ones only, so undoing an IAO import took off the frame of a pattern whose axioms had
     * been copied straight into the ontology.
     */
    @Test
    void undoingAnotherImportLeavesAPatternCopiedIntoTheOntologyFramed() throws Exception {
        CanvasLayout framed = new CanvasLayout();
        OWLOntology edit = importingComponencyWithClassificationCopiedIn(framed);
        OWLImportsDeclaration iao = df.getOWLImportsDeclaration(
                IRI.create(EGG + "eggboil/imports/iao_import.owl"));
        manager.createOntology(iao.getIRI());
        manager.applyChange(new AddImport(edit, iao));
        List<org.semanticweb.owlapi.model.OWLOntologyChange> undo =
                Collections.<org.semanticweb.owlapi.model.OWLOntologyChange>singletonList(
                        new RemoveImport(edit, iao));
        manager.applyChanges(undo);

        List<String> gone = SchemaCanvasView.unframePatternsTakenBack(framed, edit,
                SchemaCanvasView.patternsTakenBack(undo, edit), null, termsOf);

        assertEquals(Collections.emptyList(), gone);
        assertEquals(2, framed.frames.size(), "both frames stay");
    }

    /** Undo of a pattern's import takes that pattern's frame, and only that one. */
    @Test
    void undoingAPatternsImportTakesOnlyItsFrame() throws Exception {
        CanvasLayout framed = new CanvasLayout();
        OWLOntology edit = importingComponencyWithClassificationCopiedIn(framed);
        List<org.semanticweb.owlapi.model.OWLOntologyChange> undo =
                Collections.<org.semanticweb.owlapi.model.OWLOntologyChange>singletonList(
                        new RemoveImport(edit, df.getOWLImportsDeclaration(
                                IRI.create(EGG + "eggboil/imports/componency_import.owl"))));
        manager.applyChanges(undo);

        List<String> gone = SchemaCanvasView.unframePatternsTakenBack(framed, edit,
                SchemaCanvasView.patternsTakenBack(undo, edit), null, termsOf);

        assertEquals(Collections.singletonList("Componency Pattern"), gone);
        assertEquals(1, framed.frames.size());
        assertEquals("classification", framed.frames.get(0).pattern);
    }

    /**
     * Which pattern an import was is read from its term list, as the board read it when it framed
     * the pattern: Agent Role's module is {@code agentrole_import.owl}, and "agentrole" is another
     * pattern of the library, its twin.
     */
    @Test
    void anImportTakenBackIsKnownByItsTermListWhateverItsModuleIsCalled(
            @org.junit.jupiter.api.io.TempDir java.io.File imports) throws Exception {
        IRI moduleIri = IRI.create(EGG + "eggboil/imports/agentrole_import.owl");
        OWLOntology module = manager.createOntology(moduleIri);
        manager.addAxiom(module, df.getOWLDeclarationAxiom(df.getOWLClass(
                IRI.create("http://www.ontologydesignpatterns.org/cp/owl/agentrole.owl#Agent"))));
        java.io.File moduleFile = new java.io.File(imports, "agentrole_import.owl");
        manager.saveOntology(module, IRI.create(moduleFile.toURI()));
        manager.removeOntology(module);
        java.nio.file.Files.write(new java.io.File(imports, "agentrole_terms.txt").toPath(),
                ("# Pattern: agent-role\n"
                        + "http://www.ontologydesignpatterns.org/cp/owl/agentrole.owl#Agent\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        manager.loadOntologyFromOntologyDocument(moduleFile);
        OWLOntology edit = manager.createOntology(IRI.create(EGG + "eggboil.owl"));
        OWLImportsDeclaration agentRole = df.getOWLImportsDeclaration(moduleIri);
        manager.applyChange(new AddImport(edit, agentRole));
        List<org.semanticweb.owlapi.model.OWLOntologyChange> undo =
                Collections.<org.semanticweb.owlapi.model.OWLOntologyChange>singletonList(
                        new RemoveImport(edit, agentRole));
        manager.applyChanges(undo);

        assertEquals(Collections.singleton("agent-role"),
                SchemaCanvasView.patternsTakenBack(undo, edit));
        assertEquals(Collections.emptySet(), SchemaCanvasView.patternsTakenBack(Arrays.asList(
                new RemoveImport(edit, agentRole), new AddImport(edit, agentRole)), edit),
                "a module rebuilt in place");
    }

    /**
     * Edit &gt; Redo of an import says it is back (1.135.1): the line went on saying "No longer
     * imported: iao" after Redo had brought it back.
     */
    @Test
    void anImportBroughtBackIsSaidToBeBack() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology active = manager.createOntology(IRI.create("http://example.org/x/x.owl"));
        OWLImportsDeclaration iao = manager.getOWLDataFactory().getOWLImportsDeclaration(
                IRI.create("http://example.org/x/imports/iao_import.owl"));
        assertEquals(Collections.singletonList("iao"), SchemaCanvasView.importsBroughtBack(
                Collections.singletonList(new AddImport(active, iao)), active));
        assertEquals(Collections.emptyList(), SchemaCanvasView.importsBroughtBack(Arrays.asList(
                new RemoveImport(active, iao), new AddImport(active, iao)), active),
                "a module rebuilt in place");
        assertEquals("Imported again: iao. File > Save keeps it.",
                SchemaCanvasView.broughtBackStatus(Collections.singletonList("iao")));
    }
}
