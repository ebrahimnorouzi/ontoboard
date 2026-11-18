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
        assertEquals(own.toString(), ImportTermsAction.recordedSource(pattern, copy, null),
                "a file of this machine records the ontology's own IRI");
        org.junit.jupiter.api.Assertions.assertNull(ImportTermsAction.recordedSource(
                manager.createOntology(), copy, "anonymous"),
                "a pattern with no IRI records none; its # Pattern: line says where it came from");
    }

    /**
     * F4: a file of this machine is an address on no other. Taken from one, the term list - a
     * committed file - records the ontology's own IRI, as the module's provenance does, and
     * none for an ontology without one: never the path.
     */
    @Test
    void aLocalFileWithoutAnIriRecordsNoSource() throws Exception {
        org.semanticweb.owlapi.model.OWLOntology anonymous =
                org.semanticweb.owlapi.apibinding.OWLManager.createOWLOntologyManager()
                        .createOntology();

        org.junit.jupiter.api.Assertions.assertNull(ImportTermsAction.recordedSource(anonymous,
                org.semanticweb.owlapi.model.IRI.create(
                        "file:/C:/Users/someone/Downloads/mini.owl"), null));
    }

    /** A source on the web is recorded as given: it is where the terms were taken from. */
    @Test
    void aSourceOnTheWebIsRecordedAsGiven() throws Exception {
        org.semanticweb.owlapi.model.OWLOntology mini =
                org.semanticweb.owlapi.apibinding.OWLManager.createOWLOntologyManager()
                        .createOntology(org.semanticweb.owlapi.model.IRI.create(
                                "https://example.org/mini.owl"));

        assertEquals("https://w3id.org/mini/1.0/mini.owl", ImportTermsAction.recordedSource(mini,
                org.semanticweb.owlapi.model.IRI.create("https://w3id.org/mini/1.0/mini.owl"),
                null));
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

    /** After ODK built a module: the terms asked for that it does not hold are named. */
    @Test
    void theTermsAModuleDoesNotHoldAreNamedInTheOrderAsked() throws Exception {
        org.semanticweb.owlapi.model.OWLOntologyManager manager =
                org.semanticweb.owlapi.apibinding.OWLManager.createOWLOntologyManager();
        org.semanticweb.owlapi.model.OWLOntology module = manager.createOntology(
                org.semanticweb.owlapi.model.IRI.create("http://example.org/module"));
        org.semanticweb.owlapi.model.OWLDataFactory factory = manager.getOWLDataFactory();
        manager.addAxiom(module, factory.getOWLDeclarationAxiom(factory.getOWLClass(
                org.semanticweb.owlapi.model.IRI.create("http://example.org/A"))));
        manager.addAxiom(module, factory.getOWLDeclarationAxiom(factory.getOWLObjectProperty(
                org.semanticweb.owlapi.model.IRI.create("http://example.org/p"))));

        assertEquals(java.util.Arrays.asList(
                org.semanticweb.owlapi.model.IRI.create("http://example.org/C"),
                org.semanticweb.owlapi.model.IRI.create("http://example.org/B")),
                ImportTermsAction.notIn(module, java.util.Arrays.asList(
                        org.semanticweb.owlapi.model.IRI.create("http://example.org/C"),
                        org.semanticweb.owlapi.model.IRI.create("http://example.org/A"),
                        org.semanticweb.owlapi.model.IRI.create("http://example.org/p"),
                        org.semanticweb.owlapi.model.IRI.create("http://example.org/B"))));
    }

    /**
     * In an ODK project the catalog is ODK's own, as its update writes it: one that serves
     * the module from its place already is not written again in OntoBoard's form.
     */
    @Test
    void aCatalogThatServesTheModuleFromItsPlaceIsKnown(
            @org.junit.jupiter.api.io.TempDir File folder) throws Exception {
        File catalog = new File(folder, "catalog-v001.xml");
        java.nio.file.Files.write(catalog.toPath(), ("<?xml version=\"1.0\" encoding=\"UTF-8\" "
                + "standalone=\"no\"?>\n"
                + "<catalog prefer=\"public\" "
                + "xmlns=\"urn:oasis:names:tc:entity:xmlns:xml:catalog\">\n"
                + "    <group id=\"Folder Repository, directory=, recursive=false, "
                + "Auto-Update=false, version=2\" prefer=\"public\" xml:base=\"\">\n"
                + "        <uri name=\"http://example.org/x/imports/a_import.owl\" "
                + "uri=\"imports/a_import.owl\"/>\n"
                + "    </group>\n</catalog>\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        org.semanticweb.owlapi.model.IRI module = org.semanticweb.owlapi.model.IRI.create(
                "http://example.org/x/imports/a_import.owl");

        assertTrue(ImportTermsAction.serves(catalog, module, "imports/a_import.owl"));
        assertTrue(ImportTermsAction.serves(catalog, module, "imports\\a_import.owl"),
                "however the path is written");
        assertFalse(ImportTermsAction.serves(catalog, module, "elsewhere/a_import.owl"),
                "served from another place");
        assertFalse(ImportTermsAction.serves(catalog, org.semanticweb.owlapi.model.IRI.create(
                "http://example.org/x/imports/b_import.owl"), "imports/b_import.owl"));
        assertFalse(ImportTermsAction.serves(new File(folder, "none.xml"), module,
                "imports/a_import.owl"), "no catalog");
    }

    /** "604 axioms for 1 terms" was the first sentence a participant read after importing one. */
    @Test
    void oneTermIsOneTerm() {
        assertEquals("Wrote a module of 604 axioms for 1 term, and imported it.",
                ImportTermsAction.moduleSummary(604, 1));
        assertEquals("Wrote a module of 1 axiom for 2 terms, and imported it.",
                ImportTermsAction.moduleSummary(1, 2));
    }

    /**
     * The 1.154.0 review's M4: an import already declared keeps its kind (Task 18's ruling), so
     * a kind chosen for it is not taken - and the result says so, with where an import's kind is
     * changed; the form's help says it before. The same kind as declared needs no word.
     */
    @Test
    void anImportAlreadyDeclaredSaysTheKindChosenIsNotTaken() {
        de.fizkarlsruhe.ise.ontoboard.odk.ImportProducts.Product ro =
                de.fizkarlsruhe.ise.ontoboard.odk.ImportProducts.named(
                        de.fizkarlsruhe.ise.ontoboard.odk.ImportProducts.declaredIn(
                                "id: egg\nimport_group:\n  products:\n    - id: ro\n"), "ro");

        String note = ImportTermsAction.alreadyDeclared("ro", ro,
                de.fizkarlsruhe.ise.ontoboard.odk.ImportProducts.Kind.WHOLE);

        assertTrue(note.contains("not taken"), note);
        assertTrue(note.contains(
                de.fizkarlsruhe.ise.ontoboard.odk.ImportProducts.Kind.WHOLE.words()), note);
        assertTrue(note.contains("OntoBoard > Project > Project configuration..."), note);
        assertFalse(ImportTermsAction.alreadyDeclared("ro", ro,
                de.fizkarlsruhe.ise.ontoboard.odk.ImportProducts.Kind.MODULE)
                .contains("not taken"));
        assertTrue(ImportTermsAction.kindParameter(null).getHelp().contains("already declared"),
                ImportTermsAction.kindParameter(null).getHelp());
    }
}
