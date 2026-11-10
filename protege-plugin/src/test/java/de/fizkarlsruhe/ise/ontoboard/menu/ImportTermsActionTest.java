package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import org.junit.jupiter.api.Test;

/**
 * The part of importing terms that is not Swing: what path goes into the catalog.
 *
 * <p>It matters more than it looks. The catalog is committed and read on somebody else's machine
 * and on CI, so an absolute path from the machine that wrote it resolves to nothing everywhere
 * else - and the person who wrote it has the file locally and never sees the failure.
 */
class ImportTermsActionTest {

    @Test
    void aModuleBesideTheOntologyGetsARelativePath() {
        String path = ImportTermsAction.relativePath(
                new File("/project/src/ontology"),
                new File("/project/src/ontology/imports/iao_import.owl"));

        assertEquals("imports/iao_import.owl", path);
    }

    /**
     * The catalog is read on Linux CI. A backslash in it resolves nowhere there, and nothing on
     * the machine that wrote it will ever say so.
     */
    @Test
    void thePathIsWrittenWithForwardSlashesWhateverThePlatform() {
        String path = ImportTermsAction.relativePath(
                new File("/project/src/ontology"),
                new File("/project/src/ontology/imports/iao_import.owl"));

        assertFalse(path.contains("\\"), path);
    }

    @Test
    void aModuleAboveTheOntologyStillGetsARelativePath() {
        String path = ImportTermsAction.relativePath(
                new File("/project/src/ontology"),
                new File("/project/imports/iao_import.owl"));

        assertEquals("../../imports/iao_import.owl", path);
        assertFalse(path.contains("\\"), path);
    }

    @Test
    void aModuleInTheSameDirectoryIsJustItsName() {
        assertEquals("iao_import.owl", ImportTermsAction.relativePath(
                new File("/project/src/ontology"),
                new File("/project/src/ontology/iao_import.owl")));
    }

    /**
     * Different drives on Windows have no relative path between them. An absolute one at least
     * works on this machine, which beats a path that resolves nowhere at all.
     */
    @Test
    void somewhereWithNoRelativePathFallsBackToAnAbsoluteOne() {
        String path = ImportTermsAction.relativePath(new File("C:\\project\\src\\ontology"),
                new File("D:\\elsewhere\\iao_import.owl"));

        assertTrue(path.contains("iao_import.owl"), path);
        assertFalse(path.contains("\\"), path);
    }

    /**
     * A pattern import records the pattern's own IRI, not the working copy the library made.
     *
     * <p>The copy is what the module is cut from, and its {@code file:} path is this machine's:
     * recorded, it went into two committed files and resolved on no other.
     */
    @Test
    void aPatternRecordsItsOwnIriRatherThanTheLocalCopy() throws Exception {
        org.semanticweb.owlapi.model.IRI own = org.semanticweb.owlapi.model.IRI.create(
                "http://www.ontologydesignpatterns.org/cp/owl/componency.owl");
        org.semanticweb.owlapi.model.IRI copy = org.semanticweb.owlapi.model.IRI.create(
                "file:/Users/someone/project/src/ontology/mirror/componency.owl");
        org.semanticweb.owlapi.model.OWLOntologyManager manager =
                org.semanticweb.owlapi.apibinding.OWLManager.createOWLOntologyManager();
        org.semanticweb.owlapi.model.OWLOntology pattern = manager.createOntology(own);

        assertEquals(own.toString(), ImportTermsAction.recordedSource(pattern, copy, "componency"));
        assertEquals(copy.toString(), ImportTermsAction.recordedSource(pattern, copy, null),
                "any other import records what it was given");
        org.junit.jupiter.api.Assertions.assertNull(ImportTermsAction.recordedSource(
                manager.createOntology(), copy, "anonymous"),
                "a pattern with no IRI records none; its # Pattern: line says where it came from");
    }

    private static final org.semanticweb.owlapi.model.IRI MODULE =
            org.semanticweb.owlapi.model.IRI.create(
                    "https://example.org/myont/imports/x_import.owl");

    /** A module holding one subclass axiom per name given, saved to {@code file}. */
    private static org.semanticweb.owlapi.model.OWLOntology moduleOf(File file, String... names)
            throws Exception {
        org.semanticweb.owlapi.model.OWLOntologyManager manager =
                org.semanticweb.owlapi.apibinding.OWLManager.createOWLOntologyManager();
        org.semanticweb.owlapi.model.OWLDataFactory factory = manager.getOWLDataFactory();
        org.semanticweb.owlapi.model.OWLOntology module = manager.createOntology(MODULE);
        for (String name : names) {
            manager.addAxiom(module, factory.getOWLSubClassOfAxiom(
                    factory.getOWLClass(org.semanticweb.owlapi.model.IRI.create(
                            "https://example.org/x#" + name)),
                    factory.getOWLClass(org.semanticweb.owlapi.model.IRI.create(
                            "https://example.org/x#Thing"))));
        }
        manager.saveOntology(module, org.semanticweb.owlapi.model.IRI.create(file.toURI()));
        return module;
    }

    /** What a file says, read by a manager of its own, as Protege reads it on reopening. */
    private static java.util.Set<org.semanticweb.owlapi.model.OWLAxiom> readBack(File file)
            throws Exception {
        return org.semanticweb.owlapi.apibinding.OWLManager.createOWLOntologyManager()
                .loadOntologyFromOntologyDocument(file).getAxioms();
    }

    /**
     * Importing again from a source already imported changes the module the manager has; it does
     * not swap it for another.
     *
     * <p>Protege's model manager keeps the ontologies in view in a list of its own, so an ontology
     * removed from the OWL API manager behind its back stays in that list - detached, its manager
     * gone - and every view asking it anything threw IllegalStateException until a restart. Found
     * by importing the same pattern twice in a rehearsal of the study.
     */
    @Test
    void importingAgainChangesTheModuleTheManagerHasInPlace(
            @org.junit.jupiter.api.io.TempDir File folder) throws Exception {
        File file = new File(folder, "x_import.owl");
        moduleOf(file, "A");
        org.semanticweb.owlapi.model.OWLOntologyManager protege =
                org.semanticweb.owlapi.apibinding.OWLManager.createOWLOntologyManager();
        org.semanticweb.owlapi.model.OWLOntology held =
                protege.loadOntologyFromOntologyDocument(file);

        moduleOf(file, "A", "B");
        org.semanticweb.owlapi.model.OWLOntology now = ImportTermsAction.intoManager(protege,
                MODULE, file, changes -> protege.applyChanges(changes));

        assertTrue(now == held, "the ontology everything else holds is the one that changed");
        assertTrue(held.getOWLOntologyManager() == protege, "and it is still the manager's");
        assertEquals(readBack(file), held.getAxioms(), "it says what the file says now");
        assertEquals(1, protege.getOntologies().size());
    }

    /** A module the manager does not have yet is loaded from the file just written. */
    @Test
    void aModuleNotOpenYetIsLoaded(@org.junit.jupiter.api.io.TempDir File folder)
            throws Exception {
        File file = new File(folder, "x_import.owl");
        moduleOf(file, "A");
        org.semanticweb.owlapi.model.OWLOntologyManager protege =
                org.semanticweb.owlapi.apibinding.OWLManager.createOWLOntologyManager();

        org.semanticweb.owlapi.model.OWLOntology now = ImportTermsAction.intoManager(protege,
                MODULE, file, changes -> {
                    throw new AssertionError("nothing to change in place");
                });

        assertTrue(protege.getOntologies().contains(now));
        assertEquals(readBack(file), now.getAxioms());
    }

    /** "604 axioms for 1 terms" was the first sentence a participant read after importing one. */
    @Test
    void oneTermIsOneTerm() {
        assertEquals("Wrote a module of 604 axioms for 1 term, and imported it.",
                ImportTermsAction.moduleSummary(604, 1));
        assertEquals("Wrote a module of 1 axiom for 2 terms, and imported it.",
                ImportTermsAction.moduleSummary(1, 2));
    }
}
