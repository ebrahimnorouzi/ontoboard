package de.fizkarlsruhe.ise.ontoboard.pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

class ImportedPatternsTest {

    private static final String OBJECT =
            "http://www.ontologydesignpatterns.org/cp/owl/componency.owl#Object";
    private static final String BASE = "http://example.org/pizza/imports/";

    /**
     * An ontology that imports a pattern says so, and which of its classes came with it.
     *
     * <p>Reported: the pizza ontology, put on the board with Add all, showed no frames - though it
     * imports three patterns - because frames were drawn only as a pattern was imported.
     */
    @Test
    void aPatternImportIsRecognisedByItsTermList(@TempDir File imports) throws Exception {
        OWLOntology edit = importing(imports, "componency", OBJECT,
                "# Source: http://www.ontologydesignpatterns.org/cp/owl/componency.owl\n"
                        + "# Pattern: componency\n" + OBJECT + "\n");

        List<ImportedPatterns.Found> found = ImportedPatterns.in(edit);

        assertEquals(1, found.size());
        assertEquals("Componency Pattern", found.get(0).getLabel());
        assertEquals("[" + OBJECT + "]", found.get(0).getClasses().toString());
    }

    /** One imported before term lists named the pattern is known by the module's name. */
    @Test
    void anOlderImportIsRecognisedByItsName(@TempDir File imports) throws Exception {
        OWLOntology edit = importing(imports, "componency", OBJECT, null);

        assertEquals("Componency Pattern", ImportedPatterns.in(edit).get(0).getLabel());
    }

    /** A module that shares a pattern's name but none of its classes is not that pattern. */
    @Test
    void aNameAloneIsNotEnough(@TempDir File imports) throws Exception {
        OWLOntology edit = importing(imports, "componency", "http://example.org/other#Thing",
                null);

        assertTrue(ImportedPatterns.in(edit).isEmpty());
    }

    /**
     * A release, which has merged its imports, still shows its patterns: their classes keep the
     * patterns' own IRIs. The pizza ontology's pizza.owl is one, and the first file a reader opens.
     */
    @Test
    void aMergedReleaseIsRecognisedByTheNamespaces() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory factory = manager.getOWLDataFactory();
        OWLOntology release = manager.createOntology(IRI.create("http://example.org/pizza.owl"));
        for (String iri : new String[] {OBJECT,
            "http://www.ontologydesignpatterns.org/cp/owl/classification.owl#Concept",
            "http://example.org/pizza#Pizza"}) {
            manager.addAxiom(release,
                    factory.getOWLDeclarationAxiom(factory.getOWLClass(IRI.create(iri))));
        }

        List<ImportedPatterns.Found> found = ImportedPatterns.in(release);

        assertEquals(2, found.size(), found.toString());
        assertEquals("Classification Pattern", found.get(0).getLabel());
        assertEquals("Componency Pattern", found.get(1).getLabel());
        assertEquals("[" + OBJECT + "]", found.get(1).getClasses().toString());
    }

    /** OBO's namespace names no pattern, so a BFO class claims none of the BFO-based ones. */
    @Test
    void aSharedNamespaceClaimsNothing() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory factory = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(IRI.create("http://example.org/o.owl"));
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(factory.getOWLClass(
                IRI.create("http://purl.obolibrary.org/obo/BFO_0000001"))));

        assertTrue(ImportedPatterns.in(ontology).isEmpty());
    }

    /** An ontology importing {@code name}'s module, saved in {@code imports}, holding one class. */
    private static OWLOntology importing(File imports, String name, String classIri,
            String termList) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory factory = manager.getOWLDataFactory();
        IRI moduleIri = IRI.create(BASE + name + "_import.owl");
        OWLOntology module = manager.createOntology(moduleIri);
        manager.addAxiom(module,
                factory.getOWLDeclarationAxiom(factory.getOWLClass(IRI.create(classIri))));
        File moduleFile = new File(imports, name + "_import.owl");
        manager.saveOntology(module, IRI.create(moduleFile.toURI()));
        manager.removeOntology(module);
        if (termList != null) {
            Files.write(new File(imports, name + "_terms.txt").toPath(),
                    termList.getBytes(StandardCharsets.UTF_8));
        }
        manager.loadOntologyFromOntologyDocument(moduleFile);
        OWLOntology edit = manager.createOntology(IRI.create("http://example.org/pizza.owl"));
        manager.applyChange(new AddImport(edit, factory.getOWLImportsDeclaration(moduleIri)));
        return edit;
    }
}
